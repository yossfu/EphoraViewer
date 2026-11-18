package com.ephora.viewer.world

import android.os.Handler
import android.os.Looper
import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Scene
import com.google.android.filament.VertexBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Heightfield -> VertexBuffer/IndexBuffer en el hilo principal.
// Malla 257x257 (muestra con clamp a 255), normales por diferencias
// centrales, color por altura (verde bajo -> marrón alto). Debounce <=1/s.
// Agua: plano a WaterHeight, material unlit transparente.
//
// WINDING: Filament descarta las caras traseras (culling back por
// defecto). Los triángulos se emiten CCW vistos desde arriba (+Y):
// (a, b, c) y (b, d, c) con a=(j,i), b=(j,i+1), c=(j+1,i), d=(j+1,i+1).
// El orden inverso dejaría el terreno invisible desde arriba.
class TerrainMesh(
    private val engine: Engine,
    private val scene: Scene,
    private val terrainMat: MaterialInstance,
    private val waterMat: MaterialInstance
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var terrainEntity: Int = 0
    private var waterEntity: Int = 0
    private var terrainVb: VertexBuffer? = null
    private var terrainIb: IndexBuffer? = null
    private var waterVb: VertexBuffer? = null
    private var waterIb: IndexBuffer? = null
    private var lastBuild = 0L
    private var pendingH: FloatArray? = null
    private var pendingScheduled = false

    @Volatile
    var waterHeight: Float = 20f

    fun queueUpdate(h: FloatArray) {
        pendingH = h.copyOf()
        val now = System.currentTimeMillis()
        if (now - lastBuild < 1000) {
            if (!pendingScheduled) {
                pendingScheduled = true
                mainHandler.postDelayed(
                    { pendingScheduled = false; pendingH?.let { rebuildTerrain(it) } },
                    1000 - (now - lastBuild)
                )
            }
            return
        }
        rebuildTerrain(h)
    }

    fun setWater(h: Float) {
        waterHeight = h
        mainHandler.post { rebuildWater() }
    }

    private fun colorFor(y: Float): FloatArray {
        // Verde bajo -> marrón alto. y 0..60 aprox.
        val t = (y.coerceIn(0f, 60f)) / 60f
        return floatArrayOf(0.25f + 0.45f * t, 0.55f - 0.2f * t, 0.2f, 1f)
    }

    private fun rebuildTerrain(h: FloatArray) {
        lastBuild = System.currentTimeMillis()
        val n = 257
        val vCount = n * n
        val stride = 11 * 4
        val bb = ByteBuffer.allocateDirect(vCount * stride).order(ByteOrder.LITTLE_ENDIAN)
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (j in 0 until n) for (i in 0 until n) {
            val sx = i.coerceAtMost(255)
            val sy = j.coerceAtMost(255)
            val y = h[sy * 256 + sx]
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        for (j in 0 until n) for (i in 0 until n) {
            val sx = i.coerceAtMost(255)
            val sy = j.coerceAtMost(255)
            val y = h[sy * 256 + sx]
            // Terreno SL 256x256 (este/norte) -> Filament (x, altura, -y).
            val fx = sx.toFloat()
            val fy = y
            val fz = -sy.toFloat()
            // Normal por diferencias centrales. En SL: (-dh/dx, -dh/dy, 1)*2
            // = (xm-xp, ym-yp, 2). A Filament (x, z, -y): (nx, 2, -ny).
            val xm = h[sy * 256 + (sx - 1).coerceAtLeast(0)]
            val xp = h[sy * 256 + (sx + 1).coerceAtMost(255)]
            val ym = h[(sy - 1).coerceAtLeast(0) * 256 + sx]
            val yp = h[(sy + 1).coerceAtMost(255) * 256 + sx]
            var fnx = xm - xp
            var fny = 2f
            var fnz = -(ym - yp)
            val l = Math.sqrt((fnx * fnx + fny * fny + fnz * fnz).toDouble()).toFloat().let { if (it == 0f) 1f else it }
            fnx /= l
            fny /= l
            fnz /= l
            val c = colorFor(y)
            bb.putFloat(fx)
            bb.putFloat(fy)
            bb.putFloat(fz)
            // Sin atributo NORMAL en Filament: la normal va como
            // cuaternión del marco tangente (TANGENTS, float4).
            val q = SlToFilament.tangentQuaternion(fnx, fny, fnz)
            bb.putFloat(q[0])
            bb.putFloat(q[1])
            bb.putFloat(q[2])
            bb.putFloat(q[3])
            bb.putFloat(c[0])
            bb.putFloat(c[1])
            bb.putFloat(c[2])
            bb.putFloat(c[3])
        }
        bb.flip()
        val vb = VertexBuffer.Builder().vertexCount(vCount).bufferCount(1)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, stride)
            .attribute(VertexBuffer.VertexAttribute.TANGENTS, 0, VertexBuffer.AttributeType.FLOAT4, 12, stride)
            .attribute(VertexBuffer.VertexAttribute.COLOR, 0, VertexBuffer.AttributeType.FLOAT4, 28, stride)
            .build(engine)
        vb.setBufferAt(engine, 0, bb)
        val idxCount = 256 * 256 * 6
        val ib = IndexBuffer.Builder().indexCount(idxCount).bufferType(IndexBuffer.Builder.IndexType.UINT).build(engine)
        val ibBuf = ByteBuffer.allocateDirect(idxCount * 4).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        for (j in 0 until 256) for (i in 0 until 256) {
            val a = j * n + i
            val b = a + 1
            val c = a + n
            val d = c + 1
            ibBuf.put(a)
            ibBuf.put(b)
            ibBuf.put(c)
            ibBuf.put(b)
            ibBuf.put(d)
            ibBuf.put(c)
        }
        ibBuf.flip()
        ib.setBuffer(engine, ibBuf)
        val em = EntityManager.get()
        val e = em.create()
        val cx = 128f
        val cz = -128f
        val cy = (minY + maxY) / 2f
        RenderableManager.Builder(1)
            .boundingBox(Box(cx, cy, cz, 128f, (maxY - minY) / 2f + 5f, 128f))
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib, 0, idxCount)
            .material(0, terrainMat)
            .castShadows(false)
            .receiveShadows(true)
            .build(engine, e)
        scene.addEntity(e)
        if (terrainEntity != 0) {
            scene.removeEntity(terrainEntity)
            engine.destroyEntity(terrainEntity)
            terrainVb?.let { engine.destroyVertexBuffer(it) }
            terrainIb?.let { engine.destroyIndexBuffer(it) }
        }
        terrainEntity = e
        terrainVb = vb
        terrainIb = ib
    }

    private fun rebuildWater() {
        val s = 256f
        val y = waterHeight
        // Plano de 4 vértices en Filament (0..256, y, 0..-256).
        // Winding CCW desde arriba: (0,1,2),(1,3,2).
        val verts = floatArrayOf(0f, y, 0f, s, y, 0f, 0f, y, -s, s, y, -s)
        val bb = ByteBuffer.allocateDirect(4 * 3 * 4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        bb.put(verts)
        bb.flip()
        val vb = VertexBuffer.Builder().vertexCount(4).bufferCount(1)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
            .build(engine)
        vb.setBufferAt(engine, 0, bb)
        val ib = IndexBuffer.Builder().indexCount(6).bufferType(IndexBuffer.Builder.IndexType.UINT).build(engine)
        val ibB = ByteBuffer.allocateDirect(6 * 4).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        ibB.put(intArrayOf(0, 1, 2, 1, 3, 2))
        ibB.flip()
        ib.setBuffer(engine, ibB)
        val e = EntityManager.get().create()
        RenderableManager.Builder(1)
            .boundingBox(Box(128f, y, -128f, 128f, 1f, 128f))
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib, 0, 6)
            .material(0, waterMat)
            .castShadows(false)
            .build(engine, e)
        scene.addEntity(e)
        if (waterEntity != 0) {
            scene.removeEntity(waterEntity)
            engine.destroyEntity(waterEntity)
            waterVb?.let { engine.destroyVertexBuffer(it) }
            waterIb?.let { engine.destroyIndexBuffer(it) }
        }
        waterEntity = e
        waterVb = vb
        waterIb = ib
    }

    fun destroy() {
        if (terrainEntity != 0) {
            scene.removeEntity(terrainEntity)
            engine.destroyEntity(terrainEntity)
        }
        if (waterEntity != 0) {
            scene.removeEntity(waterEntity)
            engine.destroyEntity(waterEntity)
        }
    }
}
