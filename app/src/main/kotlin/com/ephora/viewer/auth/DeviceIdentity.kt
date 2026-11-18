package com.ephora.viewer.auth

import android.content.Context
import java.security.MessageDigest
import java.util.UUID

// Identidad estable del dispositivo para login.cgi (mac e id0).
// No es la MAC real del teléfono: id0 es un UUID aleatorio generado una sola
// vez y guardado en SharedPreferences privado; mac es el MD5 hex minúsculo
// de ese UUID (32 caracteres). Lo importante es que sea estable entre logins,
// porque la rejilla lo usa para identificar la instalación.
object DeviceIdentity {

    // Devuelve Pair(mac, id0).
    fun getOrCreate(context: Context): Pair<String, String> {
        val prefs = context.getSharedPreferences("ephora_device", Context.MODE_PRIVATE)
        var id0 = prefs.getString("id0", null)?.trim()
        if (id0.isNullOrEmpty()) {
            id0 = UUID.randomUUID().toString()
            prefs.edit().putString("id0", id0).apply()
        }
        val clean = id0!!
        val mac = md5HexLower(clean)
        return Pair(mac, clean)
    }

    private fun md5HexLower(s: String): String {
        val md = MessageDigest.getInstance("MD5")
        val d = md.digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(d.size * 2)
        for (b in d) {
            sb.append(Character.forDigit((b.toInt() shr 4) and 0xF, 16))
            sb.append(Character.forDigit(b.toInt() and 0xF, 16))
        }
        return sb.toString()
    }
}
