package com.ephora.viewer.world

import android.content.res.AssetManager
import com.google.android.filament.Colors
import com.google.android.filament.Engine
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import java.nio.ByteBuffer

// Carga de .filamat compilados por GitHub Actions con matc 1.56.0
// (del tarball del release Filament 1.56.0). Solo carga, no compila.
// Devuelve null si los assets no están (el workflow no los generó):
// MainActivity degrada con gracia (la app sigue como en M2, sin mundo).
object Materials {
    fun load(engine: Engine, assets: AssetManager): Pair<Material, Material>? {
        return try {
            fun one(path: String): Material {
                val b = assets.open(path).readBytes()
                val bb = ByteBuffer.allocateDirect(b.size).put(b)
                bb.flip()
                return Material.Builder().payload(bb, bb.remaining()).build(engine)
            }
            Pair(one("filament/terrain_prim_lit.filamat"), one("filament/water_unlit.filamat"))
        } catch (_: Exception) {
            null
        }
    }

    fun tint(engine: Engine, base: Material, r: Float, g: Float, b: Float): MaterialInstance {
        val i = base.createInstance()
        i.setParameter("baseColor", Colors.RgbType.SRGB, r, g, b)
        return i
    }

    fun primInstances(engine: Engine, terrain: Material): Map<PrimType, MaterialInstance> = mapOf(
        PrimType.BOX to tint(engine, terrain, 0.6f, 0.6f, 0.6f),
        PrimType.CYLINDER to tint(engine, terrain, 0.75f, 0.6f, 0.3f),
        PrimType.PRISM to tint(engine, terrain, 0.7f, 0.7f, 0.7f),
        PrimType.SPHERE to tint(engine, terrain, 0.3f, 0.5f, 0.9f),
        PrimType.TORUS to tint(engine, terrain, 0.7f, 0.7f, 0.7f),
        PrimType.TUBE to tint(engine, terrain, 0.6f, 0.6f, 0.6f),
        PrimType.RING to tint(engine, terrain, 0.7f, 0.7f, 0.7f),
        PrimType.SCULPT to tint(engine, terrain, 1f, 0.2f, 1f),
        PrimType.MESH to tint(engine, terrain, 1f, 0.2f, 1f),
        PrimType.TREE to tint(engine, terrain, 1f, 1f, 1f),
        PrimType.UNKNOWN to tint(engine, terrain, 1f, 0.15f, 0.15f)
    )

    fun waterInstance(water: Material): MaterialInstance {
        val i = water.createInstance()
        i.setParameter("baseColor", Colors.RgbType.SRGB, 0.3f, 0.6f, 0.9f)
        i.setParameter("opacity", 0.7f)
        return i
    }
}
