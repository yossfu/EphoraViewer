package com.ephora.viewer.world

import com.google.android.filament.Engine
import com.google.android.filament.IndexBuffer
import com.google.android.filament.VertexBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Mallas UNITARIAS por PrimType, marco local SL (X frente, Y izquierda,
// Z arriba; eje del cilindro en Z; toro en el plano XY). Una
// VertexBuffer/IndexBuffer por forma, COMPARTIDA por todos los prims de
// ese tipo. Colores de depuración. M3 ignora cortes/tapers/twists a la
// hora de generar geometría (los valores se guardan en la tabla).
//
// WINDING: Filament descarta las caras traseras por defecto, así que
// todos los triángulos se emiten CCW vistos desde fuera (normal hacia
// fuera). La caja y el prisma que llegaron de Perchance tenían todas
// las caras invertidas (invisibles desde fuera); aquí están corregidas
// y verificadas cara a cara con producto cruzado.
class PrimGeometry(private val engine: Engine) {

    data class Mesh(val vb: VertexBuffer, val ib: IndexBuffer, val count: Int)

    private val cache = mutableMapOf<PrimType, Mesh>()

    fun get(t: PrimType): Mesh? {
        if (t == PrimType.GRASS || t == PrimType.PARTICLE || t == PrimType.AVATAR) return null // No se dibujan.
        return cache.getOrPut(t) { build(t) }
    }

    private fun interleave(pos: FloatArray, nor: FloatArray, col: FloatArray): ByteBuffer {
        val n = pos.size / 3
        val bb = ByteBuffer.allocateDirect(n * 40).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) {
            bb.putFloat(pos[i * 3])
            bb.putFloat(pos[i * 3 + 1])
            bb.putFloat(pos[i * 3 + 2])
            bb.putFloat(nor[i * 3])
            bb.putFloat(nor[i * 3 + 1])
            bb.putFloat(nor[i * 3 + 2])
            bb.putFloat(col[i * 4])
            bb.putFloat(col[i * 4 + 1])
            bb.putFloat(col[i * 4 + 2])
            bb.putFloat(col[i * 4 + 3])
        }
        bb.flip()
        return bb
    }

    private fun toMesh(pos: FloatArray, nor: FloatArray, col: FloatArray, idx: IntArray): Mesh {
        val vb = VertexBuffer.Builder().vertexCount(pos.size / 3).bufferCount(1)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 40)
            .attribute(VertexBuffer.VertexAttribute.NORMAL, 0, VertexBuffer.AttributeType.FLOAT3, 12, 40)
            .attribute(VertexBuffer.VertexAttribute.COLOR, 0, VertexBuffer.AttributeType.FLOAT4, 24, 40)
            .build(engine)
        vb.setBufferAt(engine, 0, interleave(pos, nor, col))
        val ib = IndexBuffer.Builder().indexCount(idx.size).bufferType(IndexBuffer.Builder.IndexType.UINT).build(engine)
        val b = ByteBuffer.allocateDirect(idx.size * 4).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        b.put(idx)
        b.flip()
        ib.setBuffer(engine, b)
        return Mesh(vb, ib, idx.size)
    }

    private fun boxMesh(r: Float, g: Float, b: Float): Mesh {
        // Cubo 1x1x1 centrado. 8 vértices con normales promediadas
        // (iluminación aproximada, suficiente para M3).
        // 0=(-,-,-) 1=(+,-,-) 2=(+,+,-) 3=(-,+,-) 4=(-,-,+) 5=(+,-,+) 6=(+,+,+) 7=(-,+,+)
        val pos = floatArrayOf(
            -.5f, -.5f, -.5f, .5f, -.5f, -.5f, .5f, .5f, -.5f, -.5f, .5f, -.5f,
            -.5f, -.5f, .5f, .5f, -.5f, .5f, .5f, .5f, .5f, -.5f, .5f, .5f
        )
        val nor = FloatArray(8 * 3)
        for (i in 0 until 8) {
            val l = Math.sqrt((pos[i * 3] * pos[i * 3] + pos[i * 3 + 1] * pos[i * 3 + 1] + pos[i * 3 + 2] * pos[i * 3 + 2]).toDouble()).toFloat()
            nor[i * 3] = pos[i * 3] / l
            nor[i * 3 + 1] = pos[i * 3 + 1] / l
            nor[i * 3 + 2] = pos[i * 3 + 2] / l
        }
        val col = FloatArray(8 * 4) { k -> if (k % 4 == 3) 1f else listOf(r, g, b)[k % 4] }
        // Caras hacia fuera: -Z, +Z, -Y, +X, +Y, -X (verificado con producto cruzado).
        return toMesh(
            pos, nor, col, intArrayOf(
                0, 2, 1, 0, 3, 2,
                4, 5, 6, 4, 6, 7,
                0, 5, 4, 0, 1, 5,
                1, 6, 5, 1, 2, 6,
                2, 7, 6, 2, 3, 7,
                0, 7, 3, 0, 4, 7
            )
        )
    }

    private fun cylMesh(r: Float, g: Float, b: Float): Mesh {
        val s = 24
        val pos = mutableListOf<Float>()
        val nor = mutableListOf<Float>()
        val col = mutableListOf<Float>()
        val idx = mutableListOf<Int>()
        for (i in 0 until s) {
            val a1 = i * 2 * Math.PI / s
            val a2 = (i + 1) * 2 * Math.PI / s
            val x1 = (Math.cos(a1) * 0.5).toFloat()
            val y1 = (Math.sin(a1) * 0.5).toFloat()
            val x2 = (Math.cos(a2) * 0.5).toFloat()
            val y2 = (Math.sin(a2) * 0.5).toFloat()
            val base = pos.size / 3
            pos.addAll(listOf(x1, y1, -.5f, x2, y2, -.5f, x2, y2, .5f, x1, y1, .5f))
            val nx = (Math.cos((a1 + a2) / 2)).toFloat()
            val ny = (Math.sin((a1 + a2) / 2)).toFloat()
            repeat(4) {
                nor.addAll(listOf(nx, ny, 0f))
                col.addAll(listOf(r, g, b, 1f))
            }
            idx.addAll(listOf(base, base + 1, base + 2, base, base + 2, base + 3))
        }
        // Tapas: centros + fans.
        var c0 = pos.size / 3
        pos.addAll(listOf(0f, 0f, .5f))
        nor.addAll(listOf(0f, 0f, 1f))
        col.addAll(listOf(r, g, b, 1f))
        for (i in 0 until s) {
            val a1 = i * 2 * Math.PI / s
            val a2 = (i + 1) * 2 * Math.PI / s
            val x1 = (Math.cos(a1) * 0.5).toFloat()
            val y1 = (Math.sin(a1) * 0.5).toFloat()
            val x2 = (Math.cos(a2) * 0.5).toFloat()
            val y2 = (Math.sin(a2) * 0.5).toFloat()
            val v0 = pos.size / 3
            pos.addAll(listOf(x1, y1, .5f))
            nor.addAll(listOf(0f, 0f, 1f))
            col.addAll(listOf(r, g, b, 1f))
            val v1 = pos.size / 3
            pos.addAll(listOf(x2, y2, .5f))
            nor.addAll(listOf(0f, 0f, 1f))
            col.addAll(listOf(r, g, b, 1f))
            idx.addAll(listOf(c0, v0, v1))
        }
        var c1 = pos.size / 3
        pos.addAll(listOf(0f, 0f, -.5f))
        nor.addAll(listOf(0f, 0f, -1f))
        col.addAll(listOf(r, g, b, 1f))
        for (i in 0 until s) {
            val a1 = i * 2 * Math.PI / s
            val a2 = (i + 1) * 2 * Math.PI / s
            val x1 = (Math.cos(a1) * 0.5).toFloat()
            val y1 = (Math.sin(a1) * 0.5).toFloat()
            val x2 = (Math.cos(a2) * 0.5).toFloat()
            val y2 = (Math.sin(a2) * 0.5).toFloat()
            val v0 = pos.size / 3
            pos.addAll(listOf(x1, y1, -.5f))
            nor.addAll(listOf(0f, 0f, -1f))
            col.addAll(listOf(r, g, b, 1f))
            val v1 = pos.size / 3
            pos.addAll(listOf(x2, y2, -.5f))
            nor.addAll(listOf(0f, 0f, -1f))
            col.addAll(listOf(r, g, b, 1f))
            idx.addAll(listOf(c1, v1, v0))
        }
        return toMesh(pos.toFloatArray(), nor.toFloatArray(), col.toFloatArray(), idx.toIntArray())
    }

    private fun sphereMesh(r: Float, g: Float, b: Float): Mesh {
        val sl = 16
        val st = 12
        val pos = mutableListOf<Float>()
        val nor = mutableListOf<Float>()
        val col = mutableListOf<Float>()
        val idx = mutableListOf<Int>()
        for (j in 0..st) for (i in 0..sl) {
            val th = i * 2 * Math.PI / sl
            val ph = j * Math.PI / st
            val x = (Math.sin(ph) * Math.cos(th) * 0.5).toFloat()
            val y = (Math.sin(ph) * Math.sin(th) * 0.5).toFloat()
            val z = (Math.cos(ph) * 0.5).toFloat()
            pos.addAll(listOf(x, y, z))
            val l = Math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()
            nor.addAll(listOf(x / l, y / l, z / l))
            col.addAll(listOf(r, g, b, 1f))
        }
        for (j in 0 until st) for (i in 0 until sl) {
            val a = j * (sl + 1) + i
            val b2 = a + sl + 1
            idx.addAll(listOf(a, b2, a + 1, a + 1, b2, b2 + 1))
        }
        return toMesh(pos.toFloatArray(), nor.toFloatArray(), col.toFloatArray(), idx.toIntArray())
    }

    private fun torusMesh(): Mesh {
        val rs = 24
        val ts = 12
        val ringR = 0.5f
        val tubeR = 0.25f
        val pos = mutableListOf<Float>()
        val nor = mutableListOf<Float>()
        val col = mutableListOf<Float>()
        val idx = mutableListOf<Int>()
        for (i in 0..rs) for (j in 0..ts) {
            val u = i * 2 * Math.PI / rs
            val v = j * 2 * Math.PI / ts
            val x = ((ringR + tubeR * Math.cos(v)) * Math.cos(u)).toFloat()
            val y = ((ringR + tubeR * Math.cos(v)) * Math.sin(u)).toFloat()
            val z = (tubeR * Math.sin(v)).toFloat()
            pos.addAll(listOf(x, y, z))
            val nx = (Math.cos(v) * Math.cos(u)).toFloat()
            val ny = (Math.cos(v) * Math.sin(u)).toFloat()
            val nz = Math.sin(v).toFloat()
            nor.addAll(listOf(nx, ny, nz))
            col.addAll(listOf(0.7f, 0.7f, 0.7f, 1f))
        }
        for (i in 0 until rs) for (j in 0 until ts) {
            val a = i * (ts + 1) + j
            val b2 = a + ts + 1
            idx.addAll(listOf(a, b2, a + 1, a + 1, b2, b2 + 1))
        }
        return toMesh(pos.toFloatArray(), nor.toFloatArray(), col.toFloatArray(), idx.toIntArray())
    }

    private fun prismMesh(): Mesh {
        // Prisma triangular extruido en Z, 1x1x1.
        // 0=(-.5,-.5,-.5) 1=(.5,-.5,-.5) 2=(0,.5,-.5) 3=(-.5,-.5,.5) 4=(.5,-.5,.5) 5=(0,.5,.5)
        val p = floatArrayOf(
            -.5f, -.5f, -.5f, .5f, -.5f, -.5f, 0f, .5f, -.5f,
            -.5f, -.5f, .5f, .5f, -.5f, .5f, 0f, .5f, .5f
        )
        // Normales (0,0,1) en todos los vértices: iluminación aproximada, suficiente para M3.
        val n = FloatArray(6 * 3) { 0f }.apply {
            for (i in 0 until 6) {
                this[i * 3] = 0f
                this[i * 3 + 1] = 0f
                this[i * 3 + 2] = 1f
            }
        }
        val c = FloatArray(6 * 4) { k -> if (k % 4 == 3) 1f else 0.7f }
        // Caras hacia fuera (verificado con producto cruzado): base -Z,
        // tapa +Z, laterales -Y, inclinado 1, inclinado 2.
        return toMesh(
            p, n, c, intArrayOf(
                0, 2, 1,
                3, 4, 5,
                0, 4, 3, 0, 1, 4,
                1, 5, 4, 1, 2, 5,
                2, 3, 5, 2, 0, 3
            )
        )
    }

    private fun treeMesh(): Mesh {
        // Tronco (cono marrón bajo) + 2 conos verdes apilados. Sustituto
        // documentado: el árbol Linden real es M5.
        val pos = mutableListOf<Float>()
        val nor = mutableListOf<Float>()
        val col = mutableListOf<Float>()
        val idx = mutableListOf<Int>()
        fun cone(cx: Float, cy: Float, cz: Float, rad: Float, h: Float, cr: Float, cg: Float, cb: Float) {
            val s = 10
            val base = pos.size / 3
            pos.addAll(listOf(cx, cy, cz + h))
            nor.addAll(listOf(0f, 0f, 1f))
            col.addAll(listOf(cr, cg, cb, 1f))
            for (i in 0..s) {
                val a = i * 2 * Math.PI / s
                pos.addAll(listOf(cx + Math.cos(a).toFloat() * rad, cy + Math.sin(a).toFloat() * rad, cz))
                nor.addAll(listOf(Math.cos(a).toFloat(), Math.sin(a).toFloat(), 0.5f))
                col.addAll(listOf(cr, cg, cb, 1f))
            }
            for (i in 0 until s) idx.addAll(listOf(base, base + 1 + i, base + 2 + i))
        }
        cone(0f, 0f, -0.5f, 0.08f, 0.5f, 0.35f, 0.2f, 0.1f)
        cone(0f, 0f, 0f, 0.4f, 0.6f, 0.1f, 0.5f, 0.15f)
        cone(0f, 0f, 0.4f, 0.28f, 0.5f, 0.12f, 0.6f, 0.18f)
        return toMesh(pos.toFloatArray(), nor.toFloatArray(), col.toFloatArray(), idx.toIntArray())
    }

    private fun build(t: PrimType): Mesh = when (t) {
        PrimType.BOX -> boxMesh(0.6f, 0.6f, 0.6f)
        PrimType.CYLINDER -> cylMesh(0.75f, 0.6f, 0.3f)
        PrimType.PRISM -> prismMesh()
        PrimType.SPHERE -> sphereMesh(0.3f, 0.5f, 0.9f)
        PrimType.TORUS -> torusMesh()
        PrimType.RING -> torusMesh()
        PrimType.TUBE -> boxMesh(0.6f, 0.6f, 0.6f) // sustituta
        PrimType.SCULPT -> boxMesh(1f, 0.2f, 1f) // magenta
        PrimType.MESH -> boxMesh(1f, 0.2f, 1f)
        PrimType.TREE -> treeMesh()
        PrimType.UNKNOWN -> boxMesh(1f, 0.15f, 0.15f) // roja
        else -> boxMesh(0.6f, 0.6f, 0.6f)
    }
}
