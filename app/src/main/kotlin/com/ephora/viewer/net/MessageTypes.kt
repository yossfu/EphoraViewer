package com.ephora.viewer.net

// Tabla M2. Números verificados en message_template.msg del visor oficial.
// HIGH = 1 byte, MEDIUM = 2 bytes, LOW = 4 bytes 0xFFFFxxxx, FIXED = 4 bytes 0xFFFFFFxx.
enum class Frequency { HIGH, MEDIUM, LOW, FIXED }

object MessageTypes {
    const val START_PING_CHECK_HIGH = 1
    const val COMPLETE_PING_CHECK_HIGH = 2
    const val AGENT_UPDATE_HIGH = 4
    const val USE_CIRCUIT_CODE_LOW = 3
    const val AGENT_THROTTLE_LOW = 81
    const val REGION_HANDSHAKE_LOW = 148
    const val REGION_HANDSHAKE_REPLY_LOW = 149
    const val COMPLETE_AGENT_MOVEMENT_LOW = 249
    const val AGENT_MOVEMENT_COMPLETE_LOW = 250
    const val LOGOUT_REQUEST_LOW = 252
    const val LOGOUT_REPLY_LOW = 253
    // Wire 0xFFFFFFFB/FD. En Int firmado: -5/-3 (mismo patrón de bits).
    const val PACKET_ACK_FIXED = -5
    const val CLOSE_CIRCUIT_FIXED = -3

    // Nombre legible de un mensaje, para el registro (AppLog).
    fun name(freq: Frequency, id: Int): String = when (freq) {
        Frequency.HIGH -> when (id) {
            START_PING_CHECK_HIGH -> "StartPingCheck"
            COMPLETE_PING_CHECK_HIGH -> "CompletePingCheck"
            AGENT_UPDATE_HIGH -> "AgentUpdate"
            else -> "High($id)"
        }
        Frequency.MEDIUM -> "Medium($id)"
        Frequency.LOW -> when (id) {
            USE_CIRCUIT_CODE_LOW -> "UseCircuitCode"
            AGENT_THROTTLE_LOW -> "AgentThrottle"
            REGION_HANDSHAKE_LOW -> "RegionHandshake"
            REGION_HANDSHAKE_REPLY_LOW -> "RegionHandshakeReply"
            COMPLETE_AGENT_MOVEMENT_LOW -> "CompleteAgentMovement"
            AGENT_MOVEMENT_COMPLETE_LOW -> "AgentMovementComplete"
            LOGOUT_REQUEST_LOW -> "LogoutRequest"
            LOGOUT_REPLY_LOW -> "LogoutReply"
            else -> "Low($id)"
        }
        Frequency.FIXED -> when (id) {
            PACKET_ACK_FIXED -> "PacketAck"
            CLOSE_CIRCUIT_FIXED -> "CloseCircuit"
            else -> "Fixed($id)"
        }
    }
}
