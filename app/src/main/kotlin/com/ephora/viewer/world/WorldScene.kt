package com.ephora.viewer.world

import android.os.Handler
import android.os.Looper
import com.ephora.viewer.net.CameraVectors
import com.ephora.viewer.util.AppLog
import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Scene

// Dueño de la escena: tabla por LocalID, ciclo de vida de entidades
// Filament, culling 128 m, sincronización tabla -> escena como mucho 1
// vez por frame vía Handler. NUNCA se toca Filament desde el hilo UDP:
// las ramas de UdpCircuit solo llaman a onTerrain/onPrims/onTerse/
// onKill, y la sincronización real ocurre en el hilo principal.
class WorldScene(
    private val engine: Engine,
    private val scene: Scene,
    private val terrain: TerrainMesh,
    private val prims: PrimGeometry,
    private val primMats: Map<PrimType, MaterialInstance>
) {
    data class Entry(
        var fullId: String, var pos: FloatArray, var rot: FloatArray, var scale: FloatArray,
        var type: PrimType, var shape: ShapeParams, var parent: Long, var flags: Long,
        var entity: Int = 0
    )

    private val table = mutableMapOf<Long, Entry>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var avatarPosSl = floatArrayOf(128f, 128f, 25f)
    private var markerEntity: Int = 0
    private var dirty = true
    private var syncQueued = false
    private var lastLoggedCounts: Pair<Int, Int>? = null

    var terrainPatches = 0
    var objectCount = 0

    fun onTerrain(h: FloatArray, patches: Int) {
        terrainPatches = patches
        terrain.queueUpdate(h)
        dirty = true
        requestSync()
    }

    fun setWaterHeight(h: Float) {
        terrain.setWater(h)
    }

    fun onPrims(list: List<ParsedPrim>) {
        synchronized(table) {
            for (p in list) {
                if (p.pCode == 95 || p.pCode == 143 || p.primType == PrimType.GRASS || p.primType == PrimType.PARTICLE) continue // no dibujar
                if (p.primType == PrimType.AVATAR) continue // M4
                val e = table[p.localId]
                if (e == null) {
                    table[p.localId] = Entry(p.fullId, p.pos, p.rot, p.scale, p.primType, p.shape, p.parentId, p.updateFlags)
                } else {
                    e.pos = p.pos
                    e.rot = p.rot
                    e.scale = p.scale
                    e.type = p.primType
                    e.shape = p.shape
                }
            }
            objectCount = table.size
        }
        dirty = true
        requestSync()
    }

    fun onTerse(list: List<ObjectUpdate.Terse>) {
        synchronized(table) {
            for (t in list) {
                table[t.localId]?.let { e ->
                    e.pos = t.pos
                    e.rot = t.rot
                }
            }
        }
        dirty = true
        requestSync()
    }

    fun onKill(ids: List<Long>) {
        val toDestroy = mutableListOf<Int>()
        synchronized(table) {
            for (id in ids) {
                table.remove(id)?.let { if (it.entity != 0) toDestroy.add(it.entity) }
            }
            objectCount = table.size
        }
        dirty = true
        mainHandler.post {
            for (e in toDestroy) {
                try {
                    scene.removeEntity(e)
                    engine.destroyEntity(e)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun requestSync() {
        if (syncQueued) return
        syncQueued = true
        mainHandler.post {
            syncQueued = false
            syncNow()
        }
    }

    // Llamar como mucho 1 vez por frame desde el Choreographer de
    // MainActivity (hilo principal). Sin cambios pendientes, no hace nada.
    fun syncNow() {
        if (!dirty) return
        dirty = false
        val em = EntityManager.get()
        val toDestroy = mutableListOf<Int>()
        synchronized(table) {
            for ((id, e) in table) {
                val dx = e.pos[0] - avatarPosSl[0]
                val dy = e.pos[1] - avatarPosSl[1]
                val dz = e.pos[2] - avatarPosSl[2]
                if (dx * dx + dy * dy + dz * dz > 128 * 128) {
                    // Fuera de rango: si tenía entidad, se destruye de verdad.
                    if (e.entity != 0) {
                        toDestroy.add(e.entity)
                        e.entity = 0
                    }
                    continue
                }
                val mesh = prims.get(e.type) ?: continue
                val mat = primMats[e.type] ?: primMats[PrimType.BOX] ?: continue
                if (e.entity == 0) {
                    val en = em.create()
                    // boundingBox EXPLÍCITA siempre: unitaria transformada por la escala.
                    val sc = floatArrayOf(e.scale[0], e.scale[2], e.scale[1]) // SL -> Filament (x, z, y)
                    RenderableManager.Builder(1)
                        .boundingBox(Box(0f, 0f, 0f, 0.5f * sc[0] + 0.01f, 0.5f * sc[1] + 0.01f, 0.5f * sc[2] + 0.01f))
                        .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, mesh.vb, mesh.ib, 0, mesh.count)
                        .material(0, mat)
                        .castShadows(true)
                        .receiveShadows(true)
                        .build(engine, en)
                    scene.addEntity(en)
                    e.entity = en
                }
                try {
                    engine.transformManager.setTransform(e.entity, SlToFilament.compose(e.pos, e.rot, e.scale))
                } catch (_: Exception) {
                    AppLog.add("SCENE", "transform fail $id")
                }
            }
        }
        for (e in toDestroy) {
            try {
                scene.removeEntity(e)
                engine.destroyEntity(e)
            } catch (_: Exception) {
            }
        }
        // Marcador del propio avatar (sustituto visible: caja pequeña;
        // M4 hará el avatar real).
        if (markerEntity == 0) {
            val mm = prims.get(PrimType.BOX) ?: return
            val en = em.create()
            val ymat = primMats[PrimType.BOX] ?: return
            RenderableManager.Builder(1)
                .boundingBox(Box(0f, 0f, 0f, 0.3f, 0.6f, 0.3f))
                .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, mm.vb, mm.ib, 0, mm.count)
                .material(0, ymat)
                .build(engine, en)
            scene.addEntity(en)
            markerEntity = en
        }
        try {
            engine.transformManager.setTransform(
                markerEntity,
                SlToFilament.compose(
                    floatArrayOf(avatarPosSl[0], avatarPosSl[1], avatarPosSl[2] + 0.5f),
                    floatArrayOf(0f, 0f, 0f, 1f),
                    floatArrayOf(0.4f, 0.4f, 1.2f)
                )
            )
        } catch (_: Exception) {
        }
        val counts = Pair(objectCount, terrainPatches)
        if (counts != lastLoggedCounts) {
            lastLoggedCounts = counts
            AppLog.add("SCENE", "${objectCount} objetos, $terrainPatches parches")
        }
    }

    fun setAvatarSl(p: FloatArray) {
        avatarPosSl = p.copyOf()
        dirty = true
    }

    // Objetivo de la cámara en Filament: avatar + 1.2 m (pecho).
    fun avatarTarget(): FloatArray {
        return SlToFilament.slPointToFilament(avatarPosSl[0], avatarPosSl[1], avatarPosSl[2] + 1.2f)
    }

    // Vectores de cámara que MainActivity calcula cada frame (marco SL).
    // Se guardan para AgentUpdateSender (vía cameraVectors).
    private var lastCam = CameraVectors(128f, 128f, 25f, 0f, 1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 1024f)

    fun setExternal(c: CameraVectors) {
        lastCam = c
    }

    fun cameraVectors(far: Float = 1024f): CameraVectors {
        return lastCam.copy(far = far)
    }

    fun destroy() {
        synchronized(table) {
            for ((_, e) in table) {
                if (e.entity != 0) {
                    try {
                        scene.removeEntity(e.entity)
                        engine.destroyEntity(e.entity)
                    } catch (_: Exception) {
                    }
                    e.entity = 0
                }
            }
            table.clear()
            objectCount = 0
        }
        if (markerEntity != 0) {
            try {
                scene.removeEntity(markerEntity)
                engine.destroyEntity(markerEntity)
            } catch (_: Exception) {
            }
            markerEntity = 0
        }
        terrain.destroy()
    }
}
