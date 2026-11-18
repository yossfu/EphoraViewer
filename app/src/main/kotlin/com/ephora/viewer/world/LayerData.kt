package com.ephora.viewer.world

import com.ephora.viewer.util.AppLog

// Parser PURO Kotlin de LayerData, sin Filament. Data = Variable 2 (U16 LE len + bytes).
// Cabecera grupo: stride U16 LE, patch_size U8 (16/32), layer_type U8 (76 terreno).
// Parche: quant_wbits U8 (97 END_OF_PATCHES), dc F32 LE, range U16 LE, patchids 10 bits.
// prequant=(qw>>4)+2, wbits=(qw&0xF)+2, quantize=1<<prequant. Bits MSB-first, multibyte LE.
// Coeficientes: 0 (1 bit) = 0; 10 (2 bits) = EOB; 110/111 (3 bits, el tercer bit ES
// el signo: 0 = positivo, 1 = negativo) + wbits bits de magnitud.
// Constantes (patch_code.h): ZERO_CODE 0x0, ZERO_EOB 0x2, POSITIVE_VALUE 0x6, NEGATIVE_VALUE 0x7.
object LayerData {
    const val LAYER_TERRAIN = 76
    const val END_OF_PATCHES = 97
    const val SIZE = 256

    val heights = FloatArray(SIZE * SIZE)
    val received = BooleanArray(16 * 16) // rejilla 16x16 para size 16
    var patchCount = 0
        private set

    // Zigzag diagonal (equivale a decopy_matrix del visor). Vale para 16 y 32.
    private fun decopy(size: Int): IntArray {
        val out = IntArray(size * size)
        var k = 0
        for (s in 0 until 2 * size - 1) {
            val lo = maxOf(0, s - size + 1)
            val hi = minOf(s, size - 1)
            if (s % 2 == 0) {
                for (x in hi downTo lo) {
                    val y = s - x
                    out[k++] = y * size + x
                }
            } else {
                for (x in lo..hi) {
                    val y = s - x
                    out[k++] = y * size + x
                }
            }
        }
        return out
    }

    private class Bits(val b: ByteArray, var byte: Int = 0, var bit: Int = 7) {
        fun read(n: Int): Int {
            var v = 0
            repeat(n) { v = (v shl 1) or read1() }
            return v
        }

        fun read1(): Int {
            if (byte >= b.size) throw IllegalArgumentException("bitstream corto")
            val v = (b[byte].toInt() shr bit) and 1
            bit--
            if (bit < 0) {
                bit = 7
                byte++
            }
            return v
        }

        fun align() {
            if (bit != 7) {
                bit = 7
                byte++
            }
        }

        fun remainingBits() = (b.size - byte) * 8 - (7 - bit)
    }

    private fun leU16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun leF32(b: ByteArray, o: Int) = java.nio.ByteBuffer.wrap(b, o, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).float

    private const val OO_SQRT2 = 0.7071067811865475 // 1/sqrt(2)

    fun reset() {
        heights.fill(0f)
        received.fill(false)
        patchCount = 0
    }

    // body = cuerpo UDP ya dezerocodificado (el nº de mensaje va DENTRO de
    // la región zerocodificada, D1 de M2). El cuerpo de LayerData es:
    // LayerID Single (Type U8) + LayerData Single (Data Variable 2).
    // Devuelve nº de parches nuevos de este mensaje.
    fun parseMessage(body: ByteArray): Int {
        if (body.size < 2) return 0
        val data: ByteArray
        if (body.size >= 3 && leU16(body, 1) + 3 == body.size) {
            data = body.copyOfRange(3, body.size) // [Type U8][U16 len][bytes]
        } else if (body.size >= 2 && leU16(body, 0) + 2 == body.size) {
            data = body.copyOfRange(2, body.size) // [U16 len][bytes], sin Type
        } else {
            AppLog.add("TERRAIN", "LayerData forma no reconocida len=${body.size}")
            return 0
        }
        var p = 0
        var added = 0
        while (p + 4 <= data.size) {
            val stride = leU16(data, p)
            p += 2
            val patchSize = data[p].toInt() and 0xFF
            p++
            val layerType = data[p].toInt() and 0xFF
            p++
            if (layerType != LAYER_TERRAIN) {
                AppLog.add("TERRAIN", "layer_type=$layerType ignorado")
                return added
            }
            val size = patchSize
            if (size != 16 && size != 32) {
                AppLog.add("TERRAIN", "patch_size $size inválido")
                return added
            }
            if (stride != 256 && stride != 257) {
                AppLog.add("TERRAIN", "stride $stride inesperado (se esperaba 256/257)")
            }
            while (true) {
                if (p >= data.size) {
                    AppLog.add("TERRAIN", "grupo truncado")
                    return added
                }
                val qw = data[p].toInt() and 0xFF
                p++
                if (qw == END_OF_PATCHES) break
                if (p + 4 + 2 > data.size) {
                    AppLog.add("TERRAIN", "cabecera parche corta")
                    return added
                }
                val dc = leF32(data, p)
                p += 4
                val range = leU16(data, p)
                p += 2
                if (p + 2 > data.size) {
                    AppLog.add("TERRAIN", "sin patchids")
                    return added
                }
                // patchids: 10 bits MSB-first a caballo de bytes.
                val bitStart = p
                val b0 = data[p].toInt() and 0xFF
                val b1 = data[p + 1].toInt() and 0xFF
                val ids = ((b0 shl 2) or (b1 shr 6)) and 0x3FF
                val px = ids shr 5
                val py = ids and 0x1F
                val cell = if (size == 16) 16 else 32
                val ox = px * cell
                val oy = py * cell
                val valid = ox + size <= SIZE && oy + size <= SIZE
                if (!valid) AppLog.add("TERRAIN", "Received invalid terrain packet (patch $px,$py size $size)")
                val prequant = (qw shr 4) + 2
                val wbits = (qw and 0xF) + 2
                val quantize = 1 shl prequant
                val bits = Bits(data, bitStart, 7)
                bits.read(10) // consumir patchids
                val ncoef = size * size
                val cpatch = FloatArray(ncoef)
                var idx = 0
                var ok = true
                while (idx < ncoef) {
                    if (bits.remainingBits() < 1) {
                        ok = false
                        break
                    }
                    val bit1 = bits.read1()
                    if (bit1 == 0) {
                        cpatch[idx] = 0f
                        idx++
                        continue
                    }
                    if (bits.remainingBits() < 1) {
                        ok = false
                        break
                    }
                    val bit2 = bits.read1()
                    if (bit2 == 0) break // EOB "10": el resto son 0
                    // "110" / "111": el tercer bit ES el signo (0 = +, 1 = -).
                    if (bits.remainingBits() < 1 + wbits) {
                        ok = false
                        break
                    }
                    val sign = bits.read1()
                    val mag = bits.read(wbits)
                    cpatch[idx] = if (sign == 0) mag.toFloat() else -mag.toFloat()
                    idx++
                }
                bits.align()
                p = bits.byte
                if (!ok) {
                    AppLog.add("TERRAIN", "coefs truncados")
                    return added
                }
                // Desviación documentada respecto a la spec (§3.2): el visor
                // descarta TODO el grupo si un parche es inválido. M3 solo
                // descarta el parche inválido y aplica los válidos del mismo
                // grupo (cada parche es independiente en el heightfield).
                if (!valid) continue
                applyPatch(ox, oy, size, cpatch, quantize, prequant, range, dc)
                if (size == 16) {
                    if (px < 16 && py < 16 && !received[py * 16 + px]) {
                        received[py * 16 + px] = true
                        patchCount++
                    }
                    added++
                } else {
                    patchCount++
                    added++
                }
            }
        }
        return added
    }

    private fun applyPatch(ox: Int, oy: Int, size: Int, cpatch: FloatArray, quantize: Int, prequant: Int, range: Int, dc: Float) {
        val dm = decopy(size)
        val deq = FloatArray(size * size) { i ->
            val x = i % size
            val y = i / size
            (1 + 2 * (x + y)).toFloat()
        }
        val block = FloatArray(size * size) { i -> cpatch[dm[i]] * deq[i] }
        val cos = FloatArray(size * size) { k ->
            val u = k / size
            val n = k % size
            Math.cos((2 * n + 1) * u * Math.PI / (2 * size)).toFloat()
        }
        val tmp = FloatArray(size * size)
        val out = FloatArray(size * size)
        for (x in 0 until size) {
            for (n in 0 until size) {
                var t = OO_SQRT2 * block[x]
                for (u in 1 until size) t += block[u * size + x] * cos[u * size + n]
                tmp[n * size + x] = (t * (2.0 / size)).toFloat()
            }
        }
        for (y in 0 until size) {
            for (n in 0 until size) {
                var t = OO_SQRT2 * tmp[y * size]
                for (u in 1 until size) t += tmp[y * size + u] * cos[u * size + n]
                out[y * size + n] = (t * (2.0 / size)).toFloat()
            }
        }
        val mult = (1.0f / quantize) * range
        val addval = mult * (1 shl (prequant - 1)) + dc
        for (y in 0 until size) for (x in 0 until size) {
            val gx = ox + x
            val gy = oy + y
            if (gx >= SIZE || gy >= SIZE) continue
            heights[gy * SIZE + gx] = out[y * size + x] * mult + addval
        }
    }
}
