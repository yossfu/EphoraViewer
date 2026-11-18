package com.ephora.viewer.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Registro en memoria para depurar en el móvil (pantalla de logs, M2.1).
// Anillo de 1000 líneas: lo viejo se descarta solo. Se lee desde la UI
// (panel de registro) y se copia o comparte con un botón.
//
// REGLA DE SEGURIDAD: aquí NUNCA se escriben session_id, agent_id ni
// circuit_code. Solo nombres de mensaje, secuencias, tamaños, estados
// y mensajes de error. Quien instrumenta el código debe cumplirla.
object AppLog {

    private const val MAX_LINES = 1000
    private val lines = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun add(tag: String, message: String) {
        val line = "${timeFormat.format(Date())} [$tag] $message"
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
    }

    @Synchronized
    fun snapshot(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() {
        lines.clear()
    }
}
