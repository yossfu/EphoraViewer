package com.ephora.viewer.world

// Conversión SL -> Filament. Sin estado.
// Punto SL (x este, y norte, z arriba) -> Filament (x, z, -y).
// Es una ROTACIÓN PROPIA (determinante +1, sin espejo, winding conservado):
// la base Qmap es la rotación de -90° sobre X: (x, y, z) -> (x, z, -y).
//
// La matriz de un prim se construye como M = T · Qmap · Q · S:
// la escala S se aplica PRIMERO en el marco SL del prim (sus mallas
// unitarias viven en marco SL: X frente, Y izquierda, Z arriba), luego
// la rotación Q del prim (marco SL), luego Qmap (SL -> Filament), luego
// la traslación. NO usar la conjugación q_map·q·q_map^-1 aquí: esa es la
// misma rotación expresada en marco Filament, y aplicarla a vértices en
// marco SL dejaría los prims girados 90° (la Z de SL quedaría hacia el
// observador en vez de hacia arriba).
object SlToFilament {

    fun slPointToFilament(x: Float, y: Float, z: Float) = floatArrayOf(x, z, -y)

    fun quatToMat4ColMajor(qx: Float, qy: Float, qz: Float, qw: Float): FloatArray {
        val n = Math.sqrt((qx * qx + qy * qy + qz * qz + qw * qw).toDouble()).toFloat().let { if (it == 0f) 1f else it }
        val x = qx / n; val y = qy / n; val z = qz / n; val w = qw / n
        val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val wx = w * x; val wy = w * y; val wz = w * z
        // Column-major para TransformManager.setTransform.
        return floatArrayOf(
            1 - 2 * (yy + zz), 2 * (xy + wz), 2 * (xz - wy), 0f,
            2 * (xy - wz), 1 - 2 * (xx + zz), 2 * (yz + wx), 0f,
            2 * (xz + wy), 2 * (yz - wx), 1 - 2 * (xx + yy), 0f,
            0f, 0f, 0f, 1f
        )
    }

    // M = T · Qmap · Q · S, en column-major (float[16]).
    // slPos/slQuat/slScale en marco SL. slQuat es (x, y, z, w).
    fun compose(slPos: FloatArray, slQuat: FloatArray, slScale: FloatArray): FloatArray {
        val fp = slPointToFilament(slPos[0], slPos[1], slPos[2])
        val r = quatToMat4ColMajor(slQuat[0], slQuat[1], slQuat[2], slQuat[3])
        val m = FloatArray(16)
        // Columna j de R_f = Qmap · (columna j de R_sl), escalada por slScale[j].
        // Qmap · (a, b, c) = (a, c, -b).
        for (j in 0..2) {
            val c0 = r[j * 4 + 0]
            val c1 = r[j * 4 + 1]
            val c2 = r[j * 4 + 2]
            val s = slScale[j]
            m[j * 4 + 0] = c0 * s
            m[j * 4 + 1] = c2 * s
            m[j * 4 + 2] = -c1 * s
            m[j * 4 + 3] = 0f
        }
        m[12] = fp[0]
        m[13] = fp[1]
        m[14] = fp[2]
        m[15] = 1f
        return m
    }

    // Filament NO tiene atributo NORMAL: la normal va empaquetada en el
    // atributo TANGENTS como cuaternión float4 del marco tangente
    // (tangente = q·(1,0,0), binormal = q·(0,1,0), normal = q·(0,0,1)).
    // Este helper calcula el cuaternión de arco más corto que lleva +Z
    // a la normal unitaria (nx, ny, nz): q = (-ny, nx, 0, 1+nz).
    // Verificado contra el AAR filament-android 1.56.0: VertexAttribute
    // solo tiene POSITION, TANGENTS, COLOR, UV0, UV1, BONE_INDICES,
    // BONE_WEIGHTS, UNUSED y CUSTOM0-7 (no existe NORMAL).
    fun tangentQuaternion(nx: Float, ny: Float, nz: Float): FloatArray {
        if (nz < -0.9999f) return floatArrayOf(1f, 0f, 0f, 0f) // n ≈ (0,0,-1): giro 180° sobre X
        val x = -ny
        val y = nx
        val w = 1f + nz
        val l = Math.sqrt((x * x + y * y + w * w).toDouble()).toFloat().let { if (it == 0f) 1f else it }
        return floatArrayOf(x / l, y / l, 0f, w / l)
    }

    // RegionHandle solo lectura: bits altos = X global, bajos = Y global.
    fun regionHandleToGlobal(handle: Long) = Pair((handle shr 32) and 0xFFFFFFFFL, handle and 0xFFFFFFFFL)
}
