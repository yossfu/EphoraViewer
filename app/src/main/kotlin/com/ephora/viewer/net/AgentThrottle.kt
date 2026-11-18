package com.ephora.viewer.net

// AgentThrottle Low81, zerocoded, NO fiable. GenCounter=0.
// Orden y valores por defecto libomv, total 1536000.
object AgentThrottle {
    const val RESEND = 150000f
    const val LAND = 170000f
    const val WIND = 34000f
    const val CLOUD = 34000f
    const val TASK = 360448f
    const val TEXTURE = 360448f
    const val ASSET = 220000f

    fun buildBody(agentId: String, sessionId: String, circuitCode: Int, genCounter: Int = 0): ByteArray {
        val w = PacketWriter()
        w.putUuid(agentId)
        w.putUuid(sessionId)
        w.putU32(circuitCode.toLong() and 0xFFFFFFFFL)
        w.putU32(genCounter.toLong() and 0xFFFFFFFFL)
        w.putF32(RESEND)
        w.putF32(LAND)
        w.putF32(WIND)
        w.putF32(CLOUD)
        w.putF32(TASK)
        w.putF32(TEXTURE)
        w.putF32(ASSET)
        return w.toByteArray()
    }
}
