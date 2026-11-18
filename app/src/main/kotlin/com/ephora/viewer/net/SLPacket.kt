package com.ephora.viewer.net

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

// Regla de oro: cabecera big-endian, cuerpo little-endian.
// Cabecera 6 bytes: flags, seq BE 4 bytes (1..0xFFFFFF, wrap a 1), extra count 1 byte.
// SIN VERIFICAR D1: se asume nº mensaje FUERA de zerocode (cabecera+nº sin comprimir,
// cuerpo sí). Validar con primer RegionHandshake real: SimName legible + WaterHeight sensata.
object SlFlags {
    const val ZEROCODED = 0x80
    const val RELIABLE = 0x40
    const val RESENT = 0x20
    const val ACK = 0x10
}

const val UDP_MTU = 1200
const val RECV_BUF = 8192
const val SEQ_MAX = 0xFFFFFF

data class DecodedPacket(
    val sequence: Int,
    val flags: Int,
    val frequency: Frequency,
    val messageId: Int,
    val body: ByteArray,
    val appendedAcks: List<Int>
)

class PacketWriter {
    private val out = ByteArrayOutputStream()

    fun putU8(v: Int) { out.write(v and 0xFF) }
    fun putBool(b: Boolean) { out.write(if (b) 1 else 0) }
    fun putU16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
    fun putS16(v: Int) = putU16(v and 0xFFFF)
    fun putU32(v: Long) { for (i in 0..3) out.write(((v shr (8 * i)) and 0xFF).toInt()) }
    fun putS32(v: Int) = putU32(v.toLong() and 0xFFFFFFFFL)
    fun putU64(v: Long) { for (i in 0..7) out.write(((v shr (8 * i)) and 0xFF).toInt()) } // M2 < 2^63
    fun putF32(v: Float) { out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v).array()) }
    fun putUuid(s: String) { // SIN VERIFICAR orden: 16 bytes tal cual hex sin guiones.
        val h = s.replace("-", "")
        require(h.length == 32) { "UUID malformado" }
        for (i in 0 until 16) out.write(h.substring(i * 2, i * 2 + 2).toInt(16))
    }
    fun putVector3(x: Float, y: Float, z: Float) { putF32(x); putF32(y); putF32(z) }
    // LLQuaternion en el paquete son 12 bytes (X, Y, Z); W se reconstruye.
    // (D3 de la spec M2: 12 bytes según la plantilla; no 16.)
    fun putQuaternion(x: Float, y: Float, z: Float) { putF32(x); putF32(y); putF32(z) }
    fun putVar1(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        require(b.size <= 255) { "Var1>255" }
        putU8(b.size)
        out.write(b)
    }
    fun putVar2(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        require(b.size <= 65535) { "Var2>64k" }
        putU16(b.size)
        out.write(b)
    }
    fun putBytes(b: ByteArray) { out.write(b) }
    fun toByteArray() = out.toByteArray()
}

class PacketReader(val buf: ByteArray) {
    var pos = 0

    fun remaining() = buf.size - pos
    private fun need(n: Int) { if (remaining() < n) throw IllegalArgumentException("paquete corto: faltan $n bytes") }
    fun getU8(): Int { need(1); return buf[pos++].toInt() and 0xFF }
    fun getBool(): Boolean = getU8() != 0
    fun getU16(): Int {
        need(2)
        val v = (buf[pos].toInt() and 0xFF) or ((buf[pos + 1].toInt() and 0xFF) shl 8)
        pos += 2
        return v
    }
    fun getS16(): Int { val v = getU16(); return if (v and 0x8000 != 0) v - 0x10000 else v }
    fun getU32(): Long {
        need(4)
        var v = 0L
        for (i in 0..3) v = v or ((buf[pos + i].toLong() and 0xFF) shl (8 * i))
        pos += 4
        return v
    }
    fun getS32(): Int = getU32().toInt()
    fun getU64(): Long {
        need(8)
        var v = 0L
        for (i in 0..7) v = v or ((buf[pos + i].toLong() and 0xFF) shl (8 * i))
        pos += 8
        return v
    }
    fun getF32(): Float {
        need(4)
        return ByteBuffer.wrap(buf, pos, 4).order(ByteOrder.LITTLE_ENDIAN).float.also { pos += 4 }
    }
    fun getUuid(): String { // 16 bytes en orden tal cual, a string con guiones.
        need(16)
        val sb = StringBuilder()
        for (i in 0 until 16) sb.append(String.format("%02x", buf[pos + i]))
        pos += 16
        val h = sb.toString()
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
    }
    fun getVector3(): FloatArray = floatArrayOf(getF32(), getF32(), getF32())
    fun getQuaternion(): FloatArray {
        // 12 bytes (X, Y, Z); W se reconstruye (D3).
        val x = getF32()
        val y = getF32()
        val z = getF32()
        val w2 = 1f - x * x - y * y - z * z
        val w = if (w2 > 0f) sqrt(w2) else 0f
        return floatArrayOf(x, y, z, w)
    }
    fun getVar1(): String {
        val n = getU8()
        need(n)
        val s = buf.copyOfRange(pos, pos + n).toString(Charsets.UTF_8)
        pos += n
        return s
    }
    fun getVar2(): String {
        val n = getU16()
        need(n)
        val s = buf.copyOfRange(pos, pos + n).toString(Charsets.UTF_8)
        pos += n
        return s
    }
    // Bloque Variable final que termina justo antes del count = 0 repeticiones.
    fun getCount8OrZeroIfEnd(): Int = if (remaining() == 0) 0 else getU8()
}

object SLPacket {

    fun zerocodeEncode(src: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        var i = 0
        while (i < src.size) {
            val b = src[i].toInt() and 0xFF
            if (b != 0) {
                o.write(b)
                i++
            } else {
                var n = 0
                while (i < src.size && (src[i].toInt() and 0xFF) == 0 && n < 255) { n++; i++ }
                o.write(0)
                o.write(n)
            }
        }
        return o.toByteArray()
    }

    fun zerocodeDecode(src: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        var i = 0
        while (i < src.size) {
            val b = src[i].toInt() and 0xFF
            i++
            if (b != 0) o.write(b)
            else {
                if (i >= src.size) throw IllegalArgumentException("zerocode truncado")
                val n = src[i].toInt() and 0xFF
                i++
                repeat(n) { o.write(0) }
            }
        }
        return o.toByteArray()
    }

    private fun encodeMsgNum(f: Frequency, id: Int): ByteArray = when (f) {
        Frequency.HIGH -> byteArrayOf((id and 0xFF).toByte())
        Frequency.MEDIUM -> byteArrayOf(((id shr 8) and 0xFF).toByte(), (id and 0xFF).toByte())
        Frequency.LOW -> {
            val w = (0xFFFF0000L or (id.toLong() and 0xFFFFL)).toInt()
            byteArrayOf(((w shr 24) and 0xFF).toByte(), ((w shr 16) and 0xFF).toByte(), ((w shr 8) and 0xFF).toByte(), (w and 0xFF).toByte())
        }
        Frequency.FIXED -> byteArrayOf(((id shr 24) and 0xFF).toByte(), ((id shr 16) and 0xFF).toByte(), ((id shr 8) and 0xFF).toByte(), (id and 0xFF).toByte())
    }

    private fun decodeMsgNum(b: ByteArray, off: Int): Triple<Frequency, Int, Int> {
        val b0 = b[off].toInt() and 0xFF
        if (b0 != 0xFF) return Triple(Frequency.HIGH, b0, 1)
        val b1 = b[off + 1].toInt() and 0xFF
        if (b1 != 0xFF) return Triple(Frequency.MEDIUM, (b0 shl 8) or b1, 2)
        val b2 = b[off + 2].toInt() and 0xFF
        val b3 = b[off + 3].toInt() and 0xFF
        val w = (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        // Low llega hasta 0xFFFFFFF9; Fixed empieza en 0xFFFFFFFA.
        return if (b2 == 0xFF && b3 >= 0xFA) Triple(Frequency.FIXED, w, 4)
        else Triple(Frequency.LOW, w and 0xFFFF, 4)
    }

    fun buildDatagram(seq: Int, freq: Frequency, msgId: Int, body: ByteArray, reliable: Boolean, zerocoded: Boolean, resent: Boolean = false): ByteArray {
        require(seq in 1..SEQ_MAX) { "seq fuera de rango" }
        var useZc = zerocoded
        var encBody = if (useZc) zerocodeEncode(body) else body
        var flags = 0
        if (useZc) flags = flags or SlFlags.ZEROCODED
        if (reliable) flags = flags or SlFlags.RELIABLE
        if (resent) flags = flags or SlFlags.RESENT
        // Sin ACKs añadidos en envío M2 (solo PacketAck). Recepción sí los soporta.
        val mn = encodeMsgNum(freq, msgId)
        var total = 6 + mn.size + encBody.size
        if (useZc && total > UDP_MTU) {
            useZc = false
            encBody = body
            flags = flags and SlFlags.ZEROCODED.inv()
            total = 6 + mn.size + encBody.size
        }
        if (total > UDP_MTU) throw IllegalArgumentException("datagrama >MTU $total")
        val o = ByteArrayOutputStream(total)
        o.write(flags)
        o.write((seq shr 24) and 0xFF)
        o.write((seq shr 16) and 0xFF)
        o.write((seq shr 8) and 0xFF)
        o.write(seq and 0xFF)
        o.write(0)
        o.write(mn)
        o.write(encBody)
        return o.toByteArray()
    }

    fun parseDatagram(d: ByteArray, len: Int): DecodedPacket {
        require(len >= 7) { "datagrama demasiado corto" }
        val flags = d[0].toInt() and 0xFF
        val seq = ((d[1].toLong() and 0xFF) shl 24) or ((d[2].toLong() and 0xFF) shl 16) or ((d[3].toLong() and 0xFF) shl 8) or (d[4].toLong() and 0xFF)
        val seqI = seq.toInt()
        val extra = d[5].toInt() and 0xFF
        var off = 6 + extra
        require(off < len) { "extra header inválido" }
        val (freq, msgId, mnLen) = decodeMsgNum(d, off)
        off += mnLen
        var bodyEnd = len
        val acks = mutableListOf<Int>()
        if (flags and SlFlags.ACK != 0) { // últimos: N*4 BE + 1 byte count.
            require(len >= 1) { "falta count de ACKs" }
            val n = d[len - 1].toInt() and 0xFF
            require(len >= off + n * 4 + 1) { "ACKs exceden paquete" }
            for (i in 0 until n) {
                val p = len - 1 - n * 4 + i * 4
                val a = ((d[p].toLong() and 0xFF) shl 24) or ((d[p + 1].toLong() and 0xFF) shl 16) or ((d[p + 2].toLong() and 0xFF) shl 8) or (d[p + 3].toLong() and 0xFF)
                acks.add(a.toInt())
            }
            bodyEnd = len - n * 4 - 1
        }
        val enc = d.copyOfRange(off, bodyEnd)
        val body = if (flags and SlFlags.ZEROCODED != 0) zerocodeDecode(enc) else enc
        return DecodedPacket(seqI, flags, freq, msgId, body, acks)
    }
}
