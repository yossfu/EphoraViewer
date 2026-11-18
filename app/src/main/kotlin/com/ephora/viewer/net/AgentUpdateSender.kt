package com.ephora.viewer.net

// AgentUpdate High4, zerocoded, NO fiable, cada 500ms SOLO tras MovementComplete.
// D14: NO copiar bug libomv (AtAxis=LeftAxis). Aquí AtAxis=adelante, Left=izquierda, Up=arriba.
data class CameraVectors(
    val cx: Float, val cy: Float, val cz: Float,
    val ax: Float, val ay: Float, val az: Float,
    val lx: Float, val ly: Float, val lz: Float,
    val ux: Float, val uy: Float, val uz: Float,
    val far: Float
)

class AgentUpdateSender(
    private val sendFunc: (ByteArray) -> Unit,
    private val agentId: String,
    private val sessionId: String
) {
    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile private var cam = CameraVectors(0f, 0f, 0f, 0f, 1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 1024f)
    @Volatile var movementDone = false
        private set
    private var lastSent: ByteArray? = null
    private var identical = 0

    fun setCamera(v: CameraVectors) { cam = v }
    fun setMovementComplete() { movementDone = true }
    fun start() {
        if (running) return
        running = true
        thread = Thread(Runnable { loop() })
        thread?.start()
    }
    fun stop() {
        running = false
        try { thread?.join(500) } catch (_: Exception) {}
    }

    private fun loop() {
        while (running) {
            try {
                if (movementDone) {
                    val b = buildBody()
                    val prev = lastSent
                    if (prev != null && prev.contentEquals(b)) {
                        // Duplicado: se envían hasta 10 iguales en total
                        // y luego se calla hasta que algo cambie.
                        identical++
                        if (identical < 10) sendFunc(b)
                    } else {
                        identical = 0
                        lastSent = b
                        sendFunc(b)
                    }
                }
                Thread.sleep(500)
            } catch (_: InterruptedException) {
                break
            } catch (_: Exception) {
                try { Thread.sleep(500) } catch (_: Exception) { break }
            }
        }
    }

    private fun buildBody(): ByteArray {
        val w = PacketWriter()
        w.putUuid(agentId)
        w.putUuid(sessionId)
        // Rotaciones identidad (0,0,0); W se reconstruye al leer (12 bytes cada una, D3).
        w.putQuaternion(0f, 0f, 0f)
        w.putQuaternion(0f, 0f, 0f)
        w.putU8(0)
        val c = cam
        w.putVector3(c.cx, c.cy, c.cz)
        w.putVector3(c.ax, c.ay, c.az)
        w.putVector3(c.lx, c.ly, c.lz)
        w.putVector3(c.ux, c.uy, c.uz)
        w.putF32(c.far)
        w.putU32(0L)
        w.putU8(0)
        return w.toByteArray()
    }
}
