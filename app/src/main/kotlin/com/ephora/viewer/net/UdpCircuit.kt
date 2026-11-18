package com.ephora.viewer.net

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ephora.viewer.auth.LoginSession
import com.ephora.viewer.util.AppLog
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

// Circuito UDP. Hilos: recepción + tick 100ms. Sin coroutines (Thread+Handler como M1).
// Retransmisión fiables 4000ms máx 3 intentos, nunca infinito. ACKs vía PacketAck 500ms/10.
// Dedup 1000 seq. SIMULATOR_TIMEOUT 30s. Global handshake 60s. Solo acepta sim_ip:port.
// Nunca registra session_id/agent_id/circuit en logs.
class UdpCircuit(
    private val simIp: String,
    private val simPort: Int,
    private val session: LoginSession,
    private val loginTimeMs: Long = System.currentTimeMillis()
) {
    private val TAG = "UdpCircuit"

    var state: ConnectionState = ConnectionState.Idle
        private set
    var onStateChanged: ((ConnectionState) -> Unit)? = null
    var onPacket: ((DecodedPacket) -> Unit)? = null
    @Volatile var lagMs: Long = -1
        private set
    var regionInfo: RegionHandshakeData? = null
        private set
    var movementInfo: AgentMovementCompleteData? = null
        private set
    val capsUrls = mutableMapOf<String, String>()

    private var socket: DatagramSocket? = null
    private var running = false
    private var seq = 1
    private val mainHandler = Handler(Looper.getMainLooper())

    private data class Pend(val freq: Frequency, val msgId: Int, val body: ByteArray, val zc: Boolean, var attempts: Int, var lastSent: Long)
    private val needAck = mutableMapOf<Int, Pend>()
    private val pendingAcks = mutableListOf<Int>()
    private val seen = LinkedHashSet<Int>()
    private val seenOrder = ArrayDeque<Int>()
    private var lastRecvTime = 0L
    private var handshakeStart = 0L
    private var gotHandshake = false
    private var gotMovement = false
    private var replySent = false
    private var throttleSent = false
    private var useCircuitSeq = -1
    private var completeSent = false
    private var pingId = 0
    private val pingTimes = mutableMapOf<Int, Long>()
    private var lastPingSent = 0L
    private var lastAckSent = 0L
    private var updateSender: AgentUpdateSender? = null

    private fun setState(s: ConnectionState) {
        state = s
        AppLog.add("UDP", "Estado: ${s.uiText()}")
        mainHandler.post { onStateChanged?.invoke(s) }
    }

    private fun nextSeq(): Int {
        synchronized(this) {
            val s = seq
            seq++
            if (seq > SEQ_MAX) seq = 1
            return s
        }
    }

    fun start() {
        if (running) return
        running = true
        handshakeStart = System.currentTimeMillis()
        lastRecvTime = handshakeStart
        setState(ConnectionState.OpeningCircuit)
        try {
            socket = DatagramSocket()
        } catch (e: Exception) {
            setState(ConnectionState.Failed("No se pudo abrir UDP: ${e.message}"))
            running = false
            return
        }
        Thread(Runnable { recvLoop() }).start()
        Thread(Runnable { tickLoop() }).start()
        AppLog.add("UDP", "Circuito: abriendo UDP hacia $simIp:$simPort")
        // Paso 1: UseCircuitCode fiable. CompleteAgentMovement se envía SOLO
        // cuando llega el ACK de UseCircuitCode (así lo hacen libomv y Lumiya:
        // el sim descarta paquetes de un agente sin circuito abierto).
        useCircuitSeq = sendReliable(Frequency.LOW, MessageTypes.USE_CIRCUIT_CODE_LOW, buildUseBody(), false)
        setState(ConnectionState.WaitingHandshake)
    }

    fun stop(sendClose: Boolean = true) {
        running = false
        updateSender?.stop()
        if (sendClose) {
            try {
                val d = SLPacket.buildDatagram(nextSeq(), Frequency.FIXED, MessageTypes.CLOSE_CIRCUIT_FIXED, ByteArray(0), false, false)
                sendRaw(d)
            } catch (_: Exception) {
            }
        }
        try { socket?.close() } catch (_: Exception) {}
        if (state !is ConnectionState.Failed && state !is ConnectionState.Disconnected) {
            setState(ConnectionState.Disconnected("Circuito cerrado."))
        }
    }

    fun requestLogout() {
        try {
            val w = PacketWriter()
            w.putUuid(session.agentId)
            w.putUuid(session.sessionId)
            sendReliable(Frequency.LOW, MessageTypes.LOGOUT_REQUEST_LOW, w.toByteArray(), false)
        } catch (_: Exception) {
        }
    }

    private fun buildUseBody(): ByteArray {
        val w = PacketWriter()
        w.putU32(session.circuitCode.toLong() and 0xFFFFFFFFL)
        w.putUuid(session.sessionId)
        w.putUuid(session.agentId)
        return w.toByteArray()
    }

    private fun buildCompleteMoveBody(): ByteArray {
        val w = PacketWriter()
        w.putUuid(session.agentId)
        w.putUuid(session.sessionId)
        w.putU32(session.circuitCode.toLong() and 0xFFFFFFFFL)
        return w.toByteArray()
    }

    // Devuelve el nº de secuencia asignado, para seguir su ACK.
    fun sendReliable(freq: Frequency, msgId: Int, body: ByteArray, zc: Boolean): Int {
        val s = nextSeq()
        val d = SLPacket.buildDatagram(s, freq, msgId, body, true, zc)
        synchronized(needAck) { needAck[s] = Pend(freq, msgId, body, zc, 0, System.currentTimeMillis()) }
        AppLog.add("UDP", "→ ${MessageTypes.name(freq, msgId)} seq=$s ${d.size} B fiable")
        sendRaw(d)
        return s
    }

    fun sendUnreliable(freq: Frequency, msgId: Int, body: ByteArray, zc: Boolean) {
        val d = SLPacket.buildDatagram(nextSeq(), freq, msgId, body, false, zc)
        AppLog.add("UDP", "→ ${MessageTypes.name(freq, msgId)} ${d.size} B")
        sendRaw(d)
    }

    private fun sendRaw(d: ByteArray) {
        try {
            val a = InetAddress.getByName(simIp)
            socket?.send(DatagramPacket(d, d.size, a, simPort))
        } catch (e: Exception) {
            Log.w(TAG, "send fallo len=${d.size}")
        }
    }

    // Un ACK (añadido o vía PacketAck) confirma un paquete fiable enviado.
    // Al confirmarse UseCircuitCode, se envía CompleteAgentMovement (paso 3).
    private fun onAckReceived(ackedSeq: Int) {
        synchronized(needAck) { needAck.remove(ackedSeq) }
        if (ackedSeq == useCircuitSeq && !completeSent) {
            completeSent = true
            AppLog.add("UDP", "ACK UseCircuitCode seq=$ackedSeq → enviando CompleteAgentMovement")
            sendReliable(Frequency.LOW, MessageTypes.COMPLETE_AGENT_MOVEMENT_LOW, buildCompleteMoveBody(), false)
        }
    }

    private fun recvLoop() {
        val buf = ByteArray(RECV_BUF)
        while (running) {
            try {
                val s = socket ?: break
                s.soTimeout = 500
                val p = DatagramPacket(buf, buf.size)
                try {
                    s.receive(p)
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                }
                if (p.address.hostAddress != simIp || p.port != simPort) {
                    AppLog.add("UDP", "Paquete de origen distinto (${p.address.hostAddress}:${p.port}), ignorado")
                    continue // validar origen.
                }
                lastRecvTime = System.currentTimeMillis()
                val dec = try {
                    SLPacket.parseDatagram(p.data.copyOf(p.length), p.length)
                } catch (e: Exception) {
                    AppLog.add("UDP", "Paquete no decodificado (${p.length} B): ${e.message}")
                    continue
                }
                AppLog.add("UDP", "← ${MessageTypes.name(dec.frequency, dec.messageId)} seq=${dec.sequence} ${p.length} B" +
                    (if (dec.flags and SlFlags.RELIABLE != 0) " fiable" else "") +
                    (if (dec.flags and SlFlags.ZEROCODED != 0) " zerocoded" else "") +
                    (if (dec.appendedAcks.isNotEmpty()) " +${dec.appendedAcks.size} ACKs" else ""))
                val isRel = (dec.flags and SlFlags.RELIABLE) != 0
                if (isRel) {
                    var dup = false
                    synchronized(seen) {
                        dup = seen.contains(dec.sequence)
                        if (!dup) {
                            seen.add(dec.sequence)
                            seenOrder.add(dec.sequence)
                            if (seen.size > 1000) {
                                val o = seenOrder.removeFirst()
                                seen.remove(o)
                            }
                        }
                    }
                    synchronized(pendingAcks) { if (!pendingAcks.contains(dec.sequence)) pendingAcks.add(dec.sequence) }
                    if (dup) continue
                }
                for (a in dec.appendedAcks) onAckReceived(a)
                handlePacket(dec)
                try { onPacket?.invoke(dec) } catch (_: Exception) {}
            } catch (e: Exception) {
                if (!running) break
            }
        }
    }

    private fun handlePacket(d: DecodedPacket) {
        when {
            d.frequency == Frequency.FIXED && d.messageId == MessageTypes.PACKET_ACK_FIXED -> {
                try {
                    val r = PacketReader(d.body)
                    val n = r.getCount8OrZeroIfEnd()
                    repeat(n) { val id = r.getU32().toInt(); onAckReceived(id) }
                } catch (_: Exception) {
                }
            }
            d.frequency == Frequency.LOW && d.messageId == MessageTypes.REGION_HANDSHAKE_LOW -> {
                try {
                    val info = RegionHandshake.parseHandshake(d.body)
                    regionInfo = info
                    AppLog.add("UDP", "RegionHandshake: '${info.simName}' agua=${info.waterHeight} acceso=${info.simAccess}")
                    // Validación D1: SimName legible + WaterHeight sensata => zerocode OK.
                    val reply = RegionHandshake.buildReply(session.agentId, session.sessionId, 4L) // D6: 4 SelfAppearanceSupport (Lumiya 0). Si falla, probar 0.
                    sendReliable(Frequency.LOW, MessageTypes.REGION_HANDSHAKE_REPLY_LOW, reply, true) // D4: fiable por prudencia, SIN VERIFICAR.
                    replySent = true
                    gotHandshake = true
                    afterHandshakeProgress()
                } catch (e: Exception) {
                    AppLog.add("UDP", "RegionHandshake: parse falló: ${e.message}")
                    Log.w(TAG, "handshake parse fallo")
                }
            }
            d.frequency == Frequency.LOW && d.messageId == MessageTypes.AGENT_MOVEMENT_COMPLETE_LOW -> {
                try {
                    val m = RegionHandshake.parseMovementComplete(d.body)
                    movementInfo = m
                    AppLog.add("UDP", "MovementComplete pos=(${m.position[0]}, ${m.position[1]}, ${m.position[2]}) canal='${m.channelVersion}'")
                    gotMovement = true
                    afterHandshakeProgress()
                } catch (e: Exception) {
                    AppLog.add("UDP", "MovementComplete: parse falló: ${e.message}")
                }
            }
            d.frequency == Frequency.HIGH && d.messageId == MessageTypes.START_PING_CHECK_HIGH -> {
                try {
                    val r = PacketReader(d.body)
                    val pid = r.getU8()
                    val w = PacketWriter()
                    w.putU8(pid)
                    sendUnreliable(Frequency.HIGH, MessageTypes.COMPLETE_PING_CHECK_HIGH, w.toByteArray(), false)
                } catch (_: Exception) {
                }
            }
            d.frequency == Frequency.HIGH && d.messageId == MessageTypes.COMPLETE_PING_CHECK_HIGH -> {
                try {
                    val r = PacketReader(d.body)
                    val pid = r.getU8()
                    val t = pingTimes.remove(pid)
                    if (t != null) lagMs = System.currentTimeMillis() - t
                } catch (_: Exception) {
                }
            }
            d.frequency == Frequency.LOW && d.messageId == MessageTypes.LOGOUT_REPLY_LOW -> {
                setState(ConnectionState.Disconnected("Sesión cerrada."))
                stop(false)
            }
            else -> {}
        }
    }

    private fun afterHandshakeProgress() {
        if (gotHandshake && gotMovement && replySent) {
            if (!throttleSent) {
                throttleSent = true
                try {
                    sendUnreliable(Frequency.LOW, MessageTypes.AGENT_THROTTLE_LOW, AgentThrottle.buildBody(session.agentId, session.sessionId, session.circuitCode), true)
                } catch (_: Exception) {
                }
                // AgentUpdate solo después de MovementComplete (corrompe posición antes, sim 1.40.4).
                val sender = AgentUpdateSender(
                    { b -> try { sendUnreliable(Frequency.HIGH, MessageTypes.AGENT_UPDATE_HIGH, b, true) } catch (_: Exception) {} },
                    session.agentId,
                    session.sessionId
                )
                updateSender = sender
                movementInfo?.let { mi ->
                    sender.setCamera(CameraVectors(mi.position[0], mi.position[1], mi.position[2], 0f, 1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 1024f))
                }
                sender.setMovementComplete()
                sender.start()
                setState(ConnectionState.RequestingCaps)
                fetchCaps()
                // Si Movement ya estaba, el estado WaitingMovementComplete se salta a RequestingCaps.
            }
        } else if (gotHandshake && !gotMovement) {
            setState(ConnectionState.WaitingMovementComplete)
        }
    }

    private fun fetchCaps() {
        Thread(Runnable {
            val seed = session.seedCapability
            val deadline = loginTimeMs + 60000L
            val res = CapsClient.fetchBlocking(seed, CapsClient.MIN_CAPS, deadline)
            mainHandler.post {
                if (!running) return@post
                if (res != null) {
                    synchronized(capsUrls) { capsUrls.clear(); capsUrls.putAll(res) }
                    val missing = CapsClient.MIN_CAPS.filter { !res.containsKey(it) }
                    AppLog.add("CAPS", "Capabilities: ${res.size} recibidas" + if (missing.isEmpty()) "" else " (faltan: ${missing.joinToString(", ")})")
                    val ri = regionInfo
                    val mi = movementInfo
                    val rn = ri?.simName ?: "región"
                    val an = "${session.firstName} ${session.lastName}"
                    val p = mi?.position
                    setState(ConnectionState.Connected(an, rn, if (p != null) Triple(p[0], p[1], p[2]) else Triple(0f, 0f, 0f)))
                } else {
                    val detail = CapsClient.lastError ?: "3 intentos o 60 s"
                    AppLog.add("CAPS", "Capabilities: falló ($detail)")
                    setState(ConnectionState.Failed("No se pudieron pedir capabilities ($detail)."))
                }
            }
        }).start()
    }

    private fun tickLoop() {
        while (running) {
            try {
                Thread.sleep(100)
                val now = System.currentTimeMillis()
                if (now - handshakeStart > 60000 && state !is ConnectionState.Connected && state !is ConnectionState.Failed && state !is ConnectionState.Disconnected) {
                    AppLog.add("UDP", "Handshake agotado (60 s sin completar)")
                    setState(ConnectionState.Failed("Handshake agotado (60 s sin completar)."))
                    continue
                }
                if (now - lastRecvTime > 30000 && state !is ConnectionState.Failed && state !is ConnectionState.Disconnected) {
                    AppLog.add("UDP", "Sin paquetes del sim 30 s (SIMULATOR_TIMEOUT)")
                    setState(ConnectionState.Failed("Se perdió la conexión con la región (30 s sin paquetes)."))
                    continue
                }
                // Retransmisión 4000ms máx 3.
                val toResend = mutableListOf<Pair<Int, Pend>>()
                synchronized(needAck) { for ((k, v) in needAck) if (now - v.lastSent >= 4000) toResend.add(Pair(k, v)) }
                for ((s, p) in toResend) {
                    if (p.attempts >= 3) {
                        synchronized(needAck) { needAck.remove(s) }
                        AppLog.add("UDP", "Sin ACK seq=$s tras 3 intentos")
                        if (state is ConnectionState.OpeningCircuit || state is ConnectionState.WaitingHandshake) {
                            setState(ConnectionState.Failed("Sin ACK del simulador (60 s)."))
                        }
                        continue
                    }
                    p.attempts++
                    p.lastSent = now
                    AppLog.add("UDP", "Retransmitiendo ${MessageTypes.name(p.freq, p.msgId)} seq=$s intento ${p.attempts}")
                    try {
                        val d = SLPacket.buildDatagram(s, p.freq, p.msgId, p.body, true, p.zc, true)
                        sendRaw(d)
                    } catch (_: Exception) {
                    }
                }
                // ACKs 500ms o 10 pendientes.
                var toAck: List<Int>? = null
                synchronized(pendingAcks) {
                    if (pendingAcks.isNotEmpty() && (now - lastAckSent >= 500 || pendingAcks.size >= 10)) {
                        toAck = pendingAcks.toList()
                        pendingAcks.clear()
                        lastAckSent = now
                    }
                }
                if (toAck != null && toAck!!.isNotEmpty()) {
                    try {
                        val w = PacketWriter()
                        w.putU8(toAck!!.size)
                        for (a in toAck!!) w.putU32(a.toLong() and 0xFFFFFFFFL)
                        sendUnreliable(Frequency.FIXED, MessageTypes.PACKET_ACK_FIXED, w.toByteArray(), false)
                    } catch (_: Exception) {
                    }
                }
                // Pings 2200ms. StartPingCheck: PingID U8 + OldestUnacked U32. Complete solo PingID. NO zerocoded.
                if (now - lastPingSent >= 2200 && (state is ConnectionState.Connected || gotMovement)) {
                    lastPingSent = now
                    pingId = (pingId + 1) and 0xFF
                    pingTimes[pingId] = now
                    var oldest = 0L
                    synchronized(needAck) { if (needAck.isNotEmpty()) oldest = needAck.keys.minOrNull()?.toLong() ?: 0L }
                    try {
                        val w = PacketWriter()
                        w.putU8(pingId)
                        w.putU32(oldest)
                        sendUnreliable(Frequency.HIGH, MessageTypes.START_PING_CHECK_HIGH, w.toByteArray(), false)
                    } catch (_: Exception) {
                    }
                }
            } catch (_: InterruptedException) {
                break
            } catch (_: Exception) {
            }
        }
    }
}
