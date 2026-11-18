package com.ephora.viewer.net

// Parseo RegionHandshake Low148 (fiable, zerocoded) y AgentMovementComplete Low250.
// RegionInfo4 Variable opcional: si el cuerpo termina justo antes del count => 0 reps.
enum class SimAccess(val code: Int) {
    UNKNOWN(0), TRIAL(7), PG(13), MATURE(21), ADULT(42), DOWN(254), NON_EXISTENT(255);

    companion object {
        fun from(c: Int) = values().firstOrNull { it.code == c } ?: UNKNOWN
    }
}

enum class RegionFlags(val bit: Long) { UNKNOWN(0L) } // SIN VERIFICAR: bits exactos no inventados; se expone raw U32.
enum class RegionProtocols(val bit: Long) { UNKNOWN(0L) } // SIN VERIFICAR: igual.

data class RegionHandshakeData(
    val regionFlags: Long,
    val simAccess: SimAccess,
    val simName: String,
    val simOwner: String,
    val isEstateManager: Boolean,
    val waterHeight: Float,
    val billableFactor: Float,
    val cacheId: String,
    val terrainBase: List<String>,
    val terrainDetail: List<String>,
    val terrainStart: FloatArray,
    val terrainRange: FloatArray,
    val regionId: String,
    val cpuClass: Int,
    val cpuRatio: Int,
    val coloName: String,
    val productSku: String,
    val productName: String,
    val regionFlagsExt: Long?,
    val regionProtocols: Long?
)

data class AgentMovementCompleteData(
    val agentId: String,
    val sessionId: String,
    val position: FloatArray,
    val lookAt: FloatArray,
    val regionHandle: Long,
    val timestamp: Long,
    val channelVersion: String
)

object RegionHandshake {

    fun parseHandshake(body: ByteArray): RegionHandshakeData {
        val r = PacketReader(body)
        val flags = r.getU32()
        val access = SimAccess.from(r.getU8())
        val name = r.getVar1()
        val owner = r.getUuid()
        val estate = r.getBool()
        val water = r.getF32()
        val bill = r.getF32()
        val cache = r.getUuid()
        val base = (0 until 4).map { r.getUuid() }
        val detail = (0 until 4).map { r.getUuid() }
        val start = FloatArray(4) { r.getF32() }
        val range = FloatArray(4) { r.getF32() }
        val regionId = r.getUuid()
        val cpuC = r.getS32()
        val cpuR = r.getS32()
        val colo = r.getVar1()
        val sku = r.getVar1()
        val pname = r.getVar1()
        var ext: Long? = null
        var proto: Long? = null
        val n = r.getCount8OrZeroIfEnd() // RegionInfo4 Variable opcional.
        if (n > 0) {
            ext = r.getU64()
            proto = r.getU64()
        }
        // libomv sobrescribe Flags con RegionFlagsExtended cuando viene
        // RegionInfo4; Ephora guarda ambos valores por separado para no
        // perder el Flags original de RegionInfo.
        return RegionHandshakeData(flags, access, name, owner, estate, water, bill, cache, base, detail, start, range, regionId, cpuC, cpuR, colo, sku, pname, ext, proto)
    }

    fun buildReply(agentId: String, sessionId: String, flags: Long = 4L): ByteArray {
        val w = PacketWriter()
        w.putUuid(agentId)
        w.putUuid(sessionId)
        w.putU32(flags)
        return w.toByteArray()
    }

    fun parseMovementComplete(body: ByteArray): AgentMovementCompleteData {
        val r = PacketReader(body)
        val a = r.getUuid()
        val s = r.getUuid()
        val pos = r.getVector3()
        val look = r.getVector3()
        val handle = r.getU64()
        val ts = r.getU32()
        val ver = r.getVar2()
        return AgentMovementCompleteData(a, s, pos, look, handle, ts, ver)
    }
}
