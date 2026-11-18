package com.ephora.viewer.net

// Máquina de estados del handshake. La UI solo observa.
sealed class ConnectionState {
    object Idle : ConnectionState()
    object OpeningCircuit : ConnectionState()
    object WaitingHandshake : ConnectionState()
    object WaitingMovementComplete : ConnectionState()
    object RequestingCaps : ConnectionState()
    data class Connected(val agentName: String, val regionName: String, val position: Triple<Float, Float, Float>) : ConnectionState()
    data class Failed(val message: String) : ConnectionState()
    data class Disconnected(val reason: String) : ConnectionState()
}

fun ConnectionState.uiText(): String = when (this) {
    is ConnectionState.Idle -> "En espera…"
    is ConnectionState.OpeningCircuit -> "Abriendo circuito…"
    is ConnectionState.WaitingHandshake -> "Esperando datos de la región…"
    is ConnectionState.WaitingMovementComplete -> "Entrando en la región…"
    is ConnectionState.RequestingCaps -> "Pidiendo capabilities…"
    is ConnectionState.Connected -> "Conectado como $agentName en $regionName"
    is ConnectionState.Failed -> message
    is ConnectionState.Disconnected -> reason
}
