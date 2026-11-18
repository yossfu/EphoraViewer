package com.ephora.viewer.world

import com.ephora.viewer.net.PacketReader
import com.ephora.viewer.util.AppLog

// Parsers PUROS Kotlin, sin Filament. High 12 fiable zerocoded,
// High 13 no fiable sin zerocode, High 14 fiable, High 15 fiable sin
// zerocode, High 16. Números verificados en la plantilla v2.0.
// Un prim con datos incompletos se registra y se ignora, nunca se
// rompe el circuito. Si sobran bytes al final de un bloque se saltan
// por longitud y se registran.
enum class PrimType { BOX, CYLINDER, PRISM, SPHERE, TORUS, TUBE, RING, SCULPT, MESH, TREE, GRASS, AVATAR, PARTICLE, UNKNOWN }

data class ShapeParams(
    val pathCurve: Int, val profileCurve: Int,
    val pathBegin: Float, val pathEnd: Float,
    val pathScaleX: Float, val pathScaleY: Float,
    val pathShearX: Float, val pathShearY: Float,
    val pathTwist: Float, val pathTwistBegin: Float, val pathRadiusOffset: Float,
    val pathTaperX: Float, val pathTaperY: Float,
    val pathRevolutions: Float, val pathSkew: Float,
    val profileBegin: Float, val profileEnd: Float, val profileHollow: Float
)

data class ParsedPrim(
    val localId: Long, val fullId: String, val pCode: Int, val state: Int,
    val crc: Long, val material: Int, val clickAction: Int, val scale: FloatArray,
    val pos: FloatArray, val vel: FloatArray, val acc: FloatArray,
    val rot: FloatArray, val angVel: FloatArray,
    val parentId: Long, val updateFlags: Long, val shape: ShapeParams,
    val primType: PrimType, val textureLen: Int,
    val ownerId: String = "", val treeSpecies: Int = 0, val hasExtra: Boolean = false
)

object ShapeQuanta {
    fun pb(v: Int) = v * 0.00002f
    fun pe(v: Int) = (50000 - v) * 0.00002f
    fun ps(v: Int) = (200 - v) * 0.01f
    fun s8(v: Int) = v * 0.01f
    fun rev(v: Int) = v * 0.015f + 1f
    fun prof(v: Int) = v * 0.00002f
}

object PrimTypeMap {
    // Mapeo libomv (Primitive.Type, spec §4.10.2). El tipo de curva va
    // en el nibble ALTO: PathCurve Line 0x10, Circle 0x20, Circle2 0x30,
    // Flexible 0x80; ProfileCurve Circle 0x00, Square 0x10, IsoTriangle
    // 0x20, EqualTriangle 0x30, RightTriangle 0x40, HalfCircle 0x50.
    // (El nibble bajo son otros flags; usarlo, como llegó de Perchance,
    // dejaba casi todo como UNKNOWN.) pathScaleY es el valor
    // DESEMPAQUETADO ((200-byte)*0.01, rango 0..2).
    fun map(pCode: Int, pathCurve: Int, profileCurve: Int, pathScaleY: Float, hasSculpt: Boolean, hasMesh: Boolean): PrimType {
        if (pCode == 47) return PrimType.AVATAR
        if (pCode == 95) return PrimType.GRASS
        if (pCode == 143) return PrimType.PARTICLE
        if (pCode == 255 || pCode == 111) return PrimType.TREE
        if (hasMesh) return PrimType.MESH
        if (hasSculpt) return PrimType.SCULPT
        if (pCode != 9) return PrimType.UNKNOWN
        val pc = (pathCurve shr 4) and 0x0F
        val pr = (profileCurve shr 4) and 0x0F
        return when (pc) {
            1, 8 -> when (pr) { // Line o Flexible
                0 -> PrimType.CYLINDER
                1 -> PrimType.BOX
                2, 3, 4 -> PrimType.PRISM
                else -> PrimType.UNKNOWN
            }
            2 -> when (pr) { // Circle
                0 -> if (pathScaleY > 0.75f) PrimType.SPHERE else PrimType.TORUS
                5 -> PrimType.SPHERE
                3 -> PrimType.RING
                1 -> if (pathScaleY <= 0.75f) PrimType.TUBE else PrimType.UNKNOWN
                else -> PrimType.UNKNOWN
            }
            3 -> if (pr == 0) PrimType.SPHERE else PrimType.UNKNOWN // Circle2 (D9)
            else -> PrimType.UNKNOWN
        }
    }
}

private fun deq(v: Int, max: Int, min: Float, maxF: Float) = min + (v.toFloat() / max) * (maxF - min)
private fun rotW(x: Float, y: Float, z: Float): Float {
    val s = x * x + y * y + z * z
    return if (s > 1f) 0f else Math.sqrt((1 - s).toDouble()).toFloat()
}
private fun norm4(a: FloatArray): FloatArray {
    val l = Math.sqrt((a[0] * a[0] + a[1] * a[1] + a[2] * a[2] + a[3] * a[3]).toDouble()).toFloat()
    return if (l == 0f) a else floatArrayOf(a[0] / l, a[1] / l, a[2] / l, a[3] / l)
}

// ExtraParams (Variable 1, spec §4.10.3): count U8; por cada parámetro:
// type U16, length U32, data[length]. Tipos: Flexible 0x10, Light 0x20,
// Sculpt 0x30, LightImage 0x40, Mesh 0x60. SculptData = 17 B: UUID (16)
// + type (1): 3 bits bajos = SculptType (Mesh = 5).
// `bytes` es el CONTENIDO del campo (sin el prefijo de longitud).
// Devuelve (hasSculpt, hasMesh).
private fun parseExtraParamsBytes(bytes: ByteArray): Pair<Boolean, Boolean> {
    var sculpt = false
    var mesh = false
    if (bytes.isEmpty()) return Pair(false, false)
    val r = PacketReader(bytes)
    val count = r.getU8()
    repeat(count) {
        if (r.remaining() < 6) throw IllegalArgumentException("ExtraParams corto")
        val type = r.getU16()
        val len = r.getU32().toInt()
        if (len < 0 || r.remaining() < len) throw IllegalArgumentException("ExtraParams len $len")
        if (type == 0x30) {
            if (len >= 17) {
                val sculptType = r.buf[r.pos + 16].toInt() and 0x07
                if (sculptType == 5) mesh = true else sculpt = true
            } else {
                sculpt = true
            }
        } else if (type == 0x60) {
            mesh = true
        }
        r.pos += len
    }
    return Pair(sculpt, mesh)
}

object ObjectUpdate {

    // Movimiento (ObjectData Variable 1) por longitud. 60/32/16 + 76/48
    // con CollisionPlane (avatares). Rotación 12 bytes: X,Y,Z + W reconstruida.
    private fun parseMove(r: PacketReader, len: Int): Array<FloatArray> {
        val start = r.pos
        return when (len) {
            60 -> {
                val p = r.getVector3()
                val v = r.getVector3()
                val a = r.getVector3()
                val q3 = r.getVector3()
                val w = rotW(q3[0], q3[1], q3[2])
                val av = r.getVector3()
                arrayOf(p, v, a, floatArrayOf(q3[0], q3[1], q3[2], w), av)
            }
            32 -> {
                val p = floatArrayOf(
                    deq(r.getU16(), 65535, -128f, 384f), deq(r.getU16(), 65535, -128f, 384f), deq(r.getU16(), 65535, -256f, 768f)
                )
                val v = floatArrayOf(
                    deq(r.getU16(), 65535, -256f, 256f), deq(r.getU16(), 65535, -256f, 256f), deq(r.getU16(), 65535, -256f, 256f)
                )
                val a = floatArrayOf(
                    deq(r.getU16(), 65535, -256f, 256f), deq(r.getU16(), 65535, -256f, 256f), deq(r.getU16(), 65535, -256f, 256f)
                )
                val q = floatArrayOf(
                    deq(r.getU16(), 65535, -1f, 1f), deq(r.getU16(), 65535, -1f, 1f), deq(r.getU16(), 65535, -1f, 1f), deq(r.getU16(), 65535, -1f, 1f)
                )
                val av = floatArrayOf(
                    deq(r.getU16(), 65535, -256f, 256f), deq(r.getU16(), 65535, -256f, 256f), deq(r.getU16(), 65535, -256f, 256f)
                )
                arrayOf(p, v, a, norm4(q), av)
            }
            16 -> {
                val p = floatArrayOf(
                    deq(r.getU8(), 255, -256f, 256f), deq(r.getU8(), 255, -256f, 256f), deq(r.getU8(), 255, -256f, 256f)
                )
                val v = floatArrayOf(
                    deq(r.getU8(), 255, -256f, 256f), deq(r.getU8(), 255, -256f, 256f), deq(r.getU8(), 255, -256f, 256f)
                )
                val a = floatArrayOf(
                    deq(r.getU8(), 255, -256f, 256f), deq(r.getU8(), 255, -256f, 256f), deq(r.getU8(), 255, -256f, 256f)
                )
                val q = floatArrayOf(
                    deq(r.getU8(), 255, -1f, 1f), deq(r.getU8(), 255, -1f, 1f), deq(r.getU8(), 255, -1f, 1f), deq(r.getU8(), 255, -1f, 1f)
                )
                val av = floatArrayOf(
                    deq(r.getU8(), 255, -256f, 256f), deq(r.getU8(), 255, -256f, 256f), deq(r.getU8(), 255, -256f, 256f)
                )
                arrayOf(p, v, a, norm4(q), av)
            }
            76 -> {
                r.getVector3()
                r.getF32() // CollisionPlane 4xF32, solo avatares.
                val p = r.getVector3()
                val v = r.getVector3()
                val a = r.getVector3()
                val q3 = r.getVector3()
                val w = rotW(q3[0], q3[1], q3[2])
                val av = r.getVector3()
                arrayOf(p, v, a, floatArrayOf(q3[0], q3[1], q3[2], w), av)
            }
            48 -> {
                r.getVector3()
                r.getF32() // CollisionPlane 4xF32.
                val p = floatArrayOf(
                    deq(r.getU16(), 65535, -128f, 384f), deq(r.getU16(), 65535, -128f, 384f), deq(r.getU16(), 65535, -256f, 768f)
                )
                val v = floatArrayOf(
                    deq(r.getU16(), 65535, -256f, 256f), deq(r.getU16(), 65535, -256f, 256f), deq(r.getU16(), 65535, -256f, 256f)
                )
                val a = floatArrayOf(
                    deq(r.getU16(), 65535, -256f, 256f), deq(r.getU16(), 65535, -256f, 256f), deq(r.getU16(), 65535, -256f, 256f)
                )
                val q = floatArrayOf(
                    deq(r.getU16(), 65535, -1f, 1f), deq(r.getU16(), 65535, -1f, 1f), deq(r.getU16(), 65535, -1f, 1f), deq(r.getU16(), 65535, -1f, 1f)
                )
                val av = floatArrayOf(
                    deq(r.getU16(), 65535, -256f, 256f), deq(r.getU16(), 65535, -256f, 256f), deq(r.getU16(), 65535, -256f, 256f)
                )
                arrayOf(p, v, a, norm4(q), av)
            }
            else -> {
                AppLog.add("OBJECT", "movimiento len=$len desconocida, se ignora solo el movimiento")
                r.pos = start + len
                arrayOf(
                    floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0f),
                    floatArrayOf(0f, 0f, 0f, 1f), floatArrayOf(0f, 0f, 0f)
                )
            }
        }
    }

    // ObjectUpdate High 12 (completo). El bloque ObjectData NO termina
    // en TextureEntry: tras ella vienen TextureAnim, NameValue, Data,
    // Text, TextColor, MediaURL, PSBlock, ExtraParams, Sound, OwnerID,
    // Gain, Flags, Radius, JointType, JointPivot y JointAxisOrAnchor
    // (plantilla v2.0). Hay que consumirlos todos o el siguiente bloque
    // del mensaje se leería desalineado.
    fun parseFull(body: ByteArray): List<ParsedPrim> {
        val out = mutableListOf<ParsedPrim>()
        val r = PacketReader(body)
        try {
            r.getU64() // RegionHandle
            r.getU16() // TimeDilation
        } catch (e: Exception) {
            AppLog.add("OBJECT", "RegionData corto")
            return out
        }
        val count = r.getCount8OrZeroIfEnd()
        repeat(count) {
            try {
                val id = r.getU32()
                val st = r.getU8()
                val full = r.getUuid()
                val crc = r.getU32()
                val pcode = r.getU8()
                val mat = r.getU8()
                val click = r.getU8()
                val sc = r.getVector3()
                // ObjectData Variable 1: U8 len + bytes.
                val mlen = r.getU8()
                val mstart = r.pos
                val mv = try {
                    parseMove(r, mlen)
                } catch (e: Exception) {
                    AppLog.add("OBJECT", "mov fail local=$id")
                    r.pos = mstart + mlen
                    arrayOf(
                        floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0f),
                        floatArrayOf(0f, 0f, 0f, 1f), floatArrayOf(0f, 0f, 0f)
                    )
                }
                if (r.pos != mstart + mlen) {
                    AppLog.add("OBJECT", "mov sobran ${mstart + mlen - r.pos} local=$id")
                    r.pos = mstart + mlen
                }
                val parent = r.getU32()
                val flags = r.getU32()
                val pathCurve = r.getU8()
                val profCurve = r.getU8()
                val pBegin = r.getU16()
                val pEnd = r.getU16()
                val psx = r.getU8()
                val psy = r.getU8()
                // S8: un byte con signo cada uno, en orden.
                val shx = r.buf[r.pos++].toInt()
                val shy = r.buf[r.pos++].toInt()
                val tw = r.buf[r.pos++].toInt()
                val twb = r.buf[r.pos++].toInt()
                val ro = r.buf[r.pos++].toInt()
                val tx = r.buf[r.pos++].toInt()
                val ty = r.buf[r.pos++].toInt()
                val rev = r.getU8()
                val skew = r.buf[r.pos++].toInt()
                val profB = r.getU16()
                val profE = r.getU16()
                val profH = r.getU16()
                // TextureEntry Variable 2 (se salta por longitud).
                val tlen = r.getU16()
                if (r.remaining() < tlen) {
                    AppLog.add("OBJECT", "TextureEntry corta local=$id")
                    return@repeat
                }
                r.pos += tlen
                // Campos finales del bloque (plantilla v2.0): se saltan
                // por longitud, salvo ExtraParams (detectar sculpt/mesh).
                skipVar1(r) // TextureAnim
                skipVar2(r) // NameValue
                skipVar2(r) // Data
                skipVar1(r) // Text
                if (r.remaining() < 4) throw IllegalArgumentException("sin TextColor")
                r.pos += 4 // TextColor Fixed 4
                skipVar1(r) // MediaURL
                skipVar1(r) // PSBlock
                val epLen = r.getU8() // ExtraParams Variable 1
                if (r.remaining() < epLen) throw IllegalArgumentException("ExtraParams corto")
                val epBytes = r.buf.copyOfRange(r.pos, r.pos + epLen)
                r.pos += epLen
                val (hasSculpt, hasMesh) = try {
                    parseExtraParamsBytes(epBytes)
                } catch (e: Exception) {
                    AppLog.add("OBJECT", "ExtraParams ilegible local=$id: ${e.message}")
                    Pair(false, false)
                }
                r.getUuid() // Sound
                val owner = r.getUuid() // OwnerID
                r.getF32() // Gain
                r.getU8() // Flags
                r.getF32() // Radius
                r.getU8() // JointType
                r.getVector3() // JointPivot
                r.getVector3() // JointAxisOrAnchor
                val shape = ShapeParams(
                    pathCurve, profCurve,
                    ShapeQuanta.pb(pBegin), ShapeQuanta.pe(pEnd),
                    ShapeQuanta.ps(psx), ShapeQuanta.ps(psy),
                    ShapeQuanta.s8(shx), ShapeQuanta.s8(shy),
                    ShapeQuanta.s8(tw), ShapeQuanta.s8(twb), ShapeQuanta.s8(ro),
                    ShapeQuanta.s8(tx), ShapeQuanta.s8(ty),
                    ShapeQuanta.rev(rev), ShapeQuanta.s8(skew),
                    ShapeQuanta.prof(profB), ShapeQuanta.prof(profE), ShapeQuanta.prof(profH)
                )
                val pt = PrimTypeMap.map(pcode, pathCurve, profCurve, shape.pathScaleY, hasSculpt, hasMesh)
                out.add(
                    ParsedPrim(
                        id, full, pcode, st, crc, mat, click, sc,
                        mv[0], mv[1], mv[2], mv[3], mv[4],
                        parent, flags, shape, pt, tlen, owner, 0, hasSculpt || hasMesh
                    )
                )
            } catch (e: Exception) {
                AppLog.add("OBJECT", "prim incompleto, se ignora: ${e.message}")
            }
        }
        return out
    }

    private fun skipVar1(r: PacketReader) {
        val n = r.getU8()
        if (r.remaining() < n) throw IllegalArgumentException("Variable 1 corta")
        r.pos += n
    }

    private fun skipVar2(r: PacketReader) {
        val n = r.getU16()
        if (r.remaining() < n) throw IllegalArgumentException("Variable 2 corta")
        r.pos += n
    }

    // ObjectUpdateCompressed High 13: blob binario. Los bloques son
    // Variable 2 (longitud), así que un fallo en un bloque no
    // desalinea el siguiente.
    fun parseCompressed(body: ByteArray): List<ParsedPrim> {
        val out = mutableListOf<ParsedPrim>()
        val r = PacketReader(body)
        try {
            r.getU64()
            r.getU16()
        } catch (e: Exception) {
            return out
        }
        val count = r.getCount8OrZeroIfEnd()
        repeat(count) {
            try {
                val flags0 = r.getU32() // UpdateFlags del bloque Variable.
                val dlen = r.getU16() // Data Variable 2.
                if (r.remaining() < dlen) {
                    AppLog.add("OBJECT", "compressed corto")
                    return@repeat
                }
                val dend = r.pos + dlen
                val dr = PacketReader(r.buf.copyOfRange(r.pos, dend))
                r.pos = dend
                val full = dr.getUuid()
                val local = dr.getU32()
                val pcode = dr.getU8()
                val st = dr.getU8()
                val crc = dr.getU32()
                val mat = dr.getU8()
                val click = dr.getU8()
                val sc = dr.getVector3()
                val pos = dr.getVector3()
                val q3 = dr.getVector3()
                val w = rotW(q3[0], q3[1], q3[2])
                val rot = floatArrayOf(q3[0], q3[1], q3[2], w)
                val cflags = dr.getU32()
                val owner = dr.getUuid()
                var ang = floatArrayOf(0f, 0f, 0f)
                var parent = 0L
                var tree = 0
                if (cflags and 0x80L != 0L) ang = dr.getVector3()
                if (cflags and 0x20L != 0L) parent = dr.getU32()
                if (cflags and 0x02L != 0L) tree = dr.getU8()
                if (cflags and 0x01L != 0L && tree == 0) {
                    val n = dr.getU8()
                    if (dr.remaining() < n) throw IllegalArgumentException("scratch corto")
                    dr.pos += n
                }
                if (cflags and 0x04L != 0L) { // Text NUL + color 4 B.
                    while (dr.remaining() > 0 && dr.buf[dr.pos].toInt() != 0) dr.pos++
                    dr.pos++
                    if (dr.remaining() < 4) throw IllegalArgumentException("textcolor corto")
                    dr.pos += 4
                }
                if (cflags and 0x200L != 0L) { // MediaURL NUL.
                    while (dr.remaining() > 0 && dr.buf[dr.pos].toInt() != 0) dr.pos++
                    dr.pos++
                }
                if (cflags and 0x08L != 0L) { // ParticleSystem 86 B.
                    if (dr.remaining() < 86) throw IllegalArgumentException("particles corto")
                    dr.pos += 86
                }
                // ExtraParams: en el blob viene sin prefijo de longitud
                // exterior (el blob ya es Variable 2): count U8 + params.
                var hasSculpt = false
                var hasMesh = false
                if (dr.remaining() > 0) {
                    val epCount = dr.getU8()
                    var i = 0
                    while (i < epCount) {
                        if (dr.remaining() < 6) throw IllegalArgumentException("ExtraParams corto")
                        val type = dr.getU16()
                        val len = dr.getU32().toInt()
                        if (len < 0 || dr.remaining() < len) throw IllegalArgumentException("ExtraParams len $len")
                        if (type == 0x30) {
                            if (len >= 17) {
                                val sculptType = dr.buf[dr.pos + 16].toInt() and 0x07
                                if (sculptType == 5) hasMesh = true else hasSculpt = true
                            } else {
                                hasSculpt = true
                            }
                        } else if (type == 0x60) {
                            hasMesh = true
                        }
                        dr.pos += len
                        i++
                    }
                }
                if (cflags and 0x10L != 0L) { // Sound: UUID + Gain + Flags + Radius = 25 B.
                    if (dr.remaining() < 25) throw IllegalArgumentException("sound corto")
                    dr.pos += 25
                }
                if (cflags and 0x100L != 0L) { // NameValues NUL.
                    while (dr.remaining() > 0 && dr.buf[dr.pos].toInt() != 0) dr.pos++
                    dr.pos++
                }
                val pc = dr.getU8()
                val pb = dr.getU16()
                val pe2 = dr.getU16()
                val psx = dr.getU8()
                val psy = dr.getU8()
                val shx = dr.buf[dr.pos++].toInt()
                val shy = dr.buf[dr.pos++].toInt()
                val tw = dr.buf[dr.pos++].toInt()
                val twb = dr.buf[dr.pos++].toInt()
                val ro = dr.buf[dr.pos++].toInt()
                val tx = dr.buf[dr.pos++].toInt()
                val ty = dr.buf[dr.pos++].toInt()
                val rev = dr.getU8()
                val skew = dr.buf[dr.pos++].toInt()
                val profC = dr.getU8()
                val profB = dr.getU16()
                val profE = dr.getU16()
                val profH = dr.getU16()
                if (dr.remaining() < 4) throw IllegalArgumentException("sin TextureEntry len")
                val tlen = dr.getS32()
                if (tlen < 0 || dr.remaining() < tlen) throw IllegalArgumentException("texture len $tlen rem ${dr.remaining()}")
                dr.pos += tlen
                if (dr.remaining() != 0) {
                    AppLog.add("OBJECT", "compressed sobran ${dr.remaining()} local=$local")
                    throw IllegalArgumentException("sobran bytes")
                }
                val shape = ShapeParams(
                    pc, profC,
                    ShapeQuanta.pb(pb), ShapeQuanta.pe(pe2),
                    ShapeQuanta.ps(psx), ShapeQuanta.ps(psy),
                    ShapeQuanta.s8(shx), ShapeQuanta.s8(shy),
                    ShapeQuanta.s8(tw), ShapeQuanta.s8(twb), ShapeQuanta.s8(ro),
                    ShapeQuanta.s8(tx), ShapeQuanta.s8(ty),
                    ShapeQuanta.rev(rev), ShapeQuanta.s8(skew),
                    ShapeQuanta.prof(profB), ShapeQuanta.prof(profE), ShapeQuanta.prof(profH)
                )
                val pt = PrimTypeMap.map(pcode, pc, profC, shape.pathScaleY, hasSculpt, hasMesh)
                out.add(
                    ParsedPrim(
                        local, full, pcode, st, crc, mat, click, sc,
                        pos, floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0f), rot, ang,
                        parent, flags0, shape, pt, tlen, owner, tree, hasSculpt || hasMesh
                    )
                )
            } catch (e: Exception) {
                AppLog.add("OBJECT", "compressed fail: ${e.message}")
            }
        }
        return out
    }

    // ObjectUpdateCached High 14: LocalID + CRC + UpdateFlags.
    fun parseCached(body: ByteArray): List<Triple<Long, Long, Long>> {
        val r = PacketReader(body)
        val o = mutableListOf<Triple<Long, Long, Long>>()
        try {
            r.getU64()
            r.getU16()
        } catch (e: Exception) {
            return o
        }
        val n = r.getCount8OrZeroIfEnd()
        repeat(n) {
            try {
                val id = r.getU32()
                val crc = r.getU32()
                val f = r.getU32()
                o.add(Triple(id, crc, f))
            } catch (e: Exception) {
                AppLog.add("OBJECT", "cached corto")
                return o
            }
        }
        return o
    }

    data class Terse(
        val localId: Long, val pos: FloatArray, val rot: FloatArray,
        val vel: FloatArray, val acc: FloatArray, val ang: FloatArray
    )

    // ImprovedTerseObjectUpdate High 15: solo movimiento. Data 44 B
    // (no avatar) o 60 B (avatar, con plano de colisión). TextureEntry
    // Variable 2 se salta. Sin interpolación: aplicar tal cual.
    fun parseTerse(body: ByteArray): List<Terse> {
        val r = PacketReader(body)
        val o = mutableListOf<Terse>()
        try {
            r.getU64()
            r.getU16()
        } catch (e: Exception) {
            return o
        }
        val n = r.getCount8OrZeroIfEnd()
        repeat(n) {
            try {
                val dlen = r.getU8()
                if (r.remaining() < dlen) {
                    AppLog.add("OBJECT", "terse corto")
                    return o
                }
                val dend = r.pos + dlen
                val dr = PacketReader(r.buf.copyOfRange(r.pos, dend))
                r.pos = dend
                val local = dr.getU32()
                dr.getU8() // State
                val av = dr.getU8()
                if (av == 1) {
                    if (dr.remaining() < 16) throw IllegalArgumentException("terse avatar sin plano")
                    dr.pos += 16 // CollisionPlane 4xF32
                }
                val pos = dr.getVector3()
                val vel = floatArrayOf(
                    deq(dr.getU16(), 65535, -128f, 128f), deq(dr.getU16(), 65535, -128f, 128f), deq(dr.getU16(), 65535, -128f, 128f)
                )
                val acc = floatArrayOf(
                    deq(dr.getU16(), 65535, -64f, 64f), deq(dr.getU16(), 65535, -64f, 64f), deq(dr.getU16(), 65535, -64f, 64f)
                )
                val rot = floatArrayOf(
                    deq(dr.getU16(), 65535, -1f, 1f), deq(dr.getU16(), 65535, -1f, 1f), deq(dr.getU16(), 65535, -1f, 1f), deq(dr.getU16(), 65535, -1f, 1f)
                )
                val ang = floatArrayOf(
                    deq(dr.getU16(), 65535, -64f, 64f), deq(dr.getU16(), 65535, -64f, 64f), deq(dr.getU16(), 65535, -64f, 64f)
                )
                // TextureEntry Variable 2 (en el lector exterior, tras Data).
                if (r.remaining() < 2) {
                    AppLog.add("OBJECT", "terse sin textura")
                    return@repeat
                }
                val tl = r.getU16()
                if (r.remaining() < tl) {
                    AppLog.add("OBJECT", "terse textura corta")
                    return@repeat
                }
                r.pos += tl
                o.add(Terse(local, pos, norm4(rot), vel, acc, ang))
            } catch (e: Exception) {
                AppLog.add("OBJECT", "terse prim incompleto, se ignora")
            }
        }
        return o
    }

    // KillObject High 16: lista de LocalID.
    fun parseKill(body: ByteArray): List<Long> {
        val r = PacketReader(body)
        val o = mutableListOf<Long>()
        try {
            val n = r.getCount8OrZeroIfEnd()
            repeat(n) { o.add(r.getU32()) }
        } catch (e: Exception) {
            AppLog.add("OBJECT", "kill corto")
        }
        return o
    }
}
