package com.ephora.viewer.net

import android.os.Handler
import android.os.Looper
import com.ephora.viewer.util.AppLog
import java.net.HttpURLConnection
import java.net.URL

// POST HTTPS array LLSD al seed_capability. Reintentos máx 3, nunca >60s desde login.
// El último error queda en lastError para mostrarlo en pantalla y en el registro:
// sin ese detalle, un fallo de capabilities es imposible de diagnosticar.
object CapsClient {

    val MIN_CAPS = listOf(
        "EventQueueGet", "SimulatorFeatures",
        "FetchInventory2", "FetchInventoryDescendents2",
        "FetchLib2", "FetchLibDescendents2",
        "GetDisplayNames", "MapLayer", "AgentPreferences",
        "ChatSessionRequest", "UpdateAvatarAppearance",
        "ProductInfoRequest", "ServerReleaseNotes", "ViewerAsset"
    )

    val lastCaps = mutableMapOf<String, String>()

    // Último error detallado del fetch (HTTP, red, respuesta o límite).
    @Volatile var lastError: String? = null
        private set

    fun fetchBlocking(seedUrl: String, wanted: List<String>, deadlineMs: Long): Map<String, String>? {
        lastError = null
        if (seedUrl.isBlank()) {
            lastError = "seed_capability vacía (el login no la entregó)"
            AppLog.add("CAPS", "Capabilities: $lastError")
            return null
        }
        // Solo el host, nunca la URL completa: el path es un token de sesión.
        val host = try { URL(seedUrl).host } catch (_: Exception) { "?" }
        AppLog.add("CAPS", "Capabilities: pidiendo ${wanted.size} a $host")
        val body = LlsdXml.buildArray(wanted)
        var attempt = 0
        while (attempt < 3) {
            if (System.currentTimeMillis() > deadlineMs) {
                lastError = "límite de 60 s desde el login"
                AppLog.add("CAPS", "Capabilities: $lastError")
                return null
            }
            try {
                val result = postCaps(seedUrl, body)
                if (result != null) {
                    synchronized(lastCaps) { lastCaps.clear(); lastCaps.putAll(result) }
                    return result
                }
                // postCaps ya registró el motivo en lastError y en el registro.
            } catch (e: Exception) {
                lastError = "error de red (${e.message})"
                AppLog.add("CAPS", "Intento ${attempt + 1}: falló (${e.message})")
            }
            attempt++
            if (attempt < 3) {
                try { Thread.sleep(500) } catch (_: Exception) {}
            }
        }
        AppLog.add("CAPS", "Capabilities: falló tras 3 intentos (${lastError ?: "sin detalle"})")
        return null
    }

    // Un intento POST con hasta 2 redirecciones (re-POST a Location).
    // Devuelve el mapa o null (con lastError detallado).
    private fun postCaps(urlStr: String, body: String): Map<String, String>? {
        var current = urlStr
        var redirects = 0
        while (true) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.connectTimeout = 60000
            c.readTimeout = 60000
            c.requestMethod = "POST"
            c.doOutput = true
            c.doInput = true
            c.instanceFollowRedirects = false
            // LLSD-XML: application/llsd+xml (ver spec M1, sección 2).
            c.setRequestProperty("Content-Type", "application/llsd+xml")
            val b = body.toByteArray(Charsets.UTF_8)
            c.setFixedLengthStreamingMode(b.size)
            c.outputStream.use { it.write(b) }
            val code = c.responseCode
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                val loc = c.getHeaderField("Location")
                try { c.disconnect() } catch (_: Exception) {}
                if (loc.isNullOrBlank() || redirects >= 2) {
                    lastError = "HTTP $code sin Location"
                    AppLog.add("CAPS", "Intento: HTTP $code sin Location")
                    return null
                }
                AppLog.add("CAPS", "Intento: HTTP $code → redirección")
                current = loc
                redirects++
                continue
            }
            if (code != 200) {
                lastError = "HTTP $code"
                AppLog.add("CAPS", "Intento: HTTP $code")
                try { c.disconnect() } catch (_: Exception) {}
                return null
            }
            val resp = c.inputStream.bufferedReader(Charsets.UTF_8).readText()
            try { c.disconnect() } catch (_: Exception) {}
            if (resp.isBlank()) {
                lastError = "respuesta vacía"
                AppLog.add("CAPS", "Intento: respuesta vacía")
                return null
            }
            return try {
                val m = LlsdXml.parseStringMap(resp)
                AppLog.add("CAPS", "Intento: OK, ${m.size} capabilities")
                m
            } catch (e: Exception) {
                lastError = "respuesta inválida (${e.message})"
                AppLog.add("CAPS", "Intento: respuesta inválida (${e.message})")
                null
            }
        }
    }

    fun fetchAsync(seedUrl: String, wanted: List<String>, deadlineMs: Long, onResult: (Map<String, String>?) -> Unit) {
        val h = Handler(Looper.getMainLooper())
        Thread(Runnable {
            val r = fetchBlocking(seedUrl, wanted, deadlineMs)
            h.post { onResult(r) }
        }).start()
    }
}
