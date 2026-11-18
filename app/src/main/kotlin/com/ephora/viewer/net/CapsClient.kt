package com.ephora.viewer.net

import android.os.Handler
import android.os.Looper
import com.ephora.viewer.util.AppLog
import java.net.HttpURLConnection
import java.net.URL

// POST HTTPS array LLSD al seed_capability. Reintentos máx 3, nunca >60s desde login.
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

    fun fetchBlocking(seedUrl: String, wanted: List<String>, deadlineMs: Long): Map<String, String>? {
        val body = LlsdXml.buildArray(wanted)
        var attempt = 0
        while (attempt < 3) {
            if (System.currentTimeMillis() > deadlineMs) return null
            try {
                val c = URL(seedUrl).openConnection() as HttpURLConnection
                c.connectTimeout = 60000
                c.readTimeout = 60000
                c.requestMethod = "POST"
                c.doOutput = true
                c.doInput = true
                // LLSD-XML: application/llsd+xml (ver spec M1, sección 2).
                c.setRequestProperty("Content-Type", "application/llsd+xml")
                val b = body.toByteArray(Charsets.UTF_8)
                c.setFixedLengthStreamingMode(b.size)
                c.outputStream.use { it.write(b) }
                if (c.responseCode != 200) {
                    AppLog.add("CAPS", "Intento ${attempt + 1}: HTTP ${c.responseCode}")
                    attempt++
                    continue
                }
                val resp = c.inputStream.bufferedReader(Charsets.UTF_8).readText()
                try { c.disconnect() } catch (_: Exception) {}
                if (resp.isBlank()) {
                    AppLog.add("CAPS", "Intento ${attempt + 1}: respuesta vacía")
                    attempt++
                    continue
                }
                val m = LlsdXml.parseStringMap(resp)
                AppLog.add("CAPS", "Intento ${attempt + 1}: OK, ${m.size} capabilities")
                synchronized(lastCaps) { lastCaps.clear(); lastCaps.putAll(m) }
                return m
            } catch (e: Exception) {
                AppLog.add("CAPS", "Intento ${attempt + 1}: falló (${e.message})")
                attempt++
                try { Thread.sleep(500) } catch (_: Exception) {}
            }
        }
        return null
    }

    fun fetchAsync(seedUrl: String, wanted: List<String>, deadlineMs: Long, onResult: (Map<String, String>?) -> Unit) {
        val h = Handler(Looper.getMainLooper())
        Thread(Runnable {
            val r = fetchBlocking(seedUrl, wanted, deadlineMs)
            h.post { onResult(r) }
        }).start()
    }
}
