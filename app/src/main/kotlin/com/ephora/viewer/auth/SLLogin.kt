package com.ephora.viewer.auth

import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest

// Login real XML-RPC contra login.cgi, método login_to_simulator.
// URLs y lista de options vienen de la especificación del hito M1
// (docs/login-protocol-spec.md, verificada contra el visor oficial de SL
// y libopenmetaverse). SIN VERIFICAR contra el servidor real: la lista
// exacta de options, el formato de next_options y de inventory-root; la
// primera prueba real de login es la que los confirma.

enum class Grid(val url: String) {
    AGNI("https://login.agni.lindenlab.com/cgi-bin/login.cgi"),
    ADITI("https://login.aditi.lindenlab.com/cgi-bin/login.cgi")
}

data class LoginCredentials(
    val first: String,
    val last: String,
    val password: String,
    val start: String = "last",
    val grid: Grid = Grid.AGNI
)

data class LoginSession(
    val sessionId: String,
    val secureSessionId: String,
    val agentId: String,
    val firstName: String,
    val lastName: String,
    val circuitCode: Int,
    val simIp: String,
    val simPort: Int,
    val regionX: Int,
    val regionY: Int,
    val seedCapability: String,
    val agentAccess: String,
    val inventoryRoot: String?,
    val motd: String?
)

sealed class LoginResult {
    data class Success(val session: LoginSession) : LoginResult()
    data class Failure(val reason: String?, val message: String) : LoginResult()
}

object SLLogin {

    // La red corre en Thread en segundo plano, el resultado se entrega
    // en el hilo principal con Handler(Looper.getMainLooper()).
    // La contraseña nunca se guarda; la sesión solo vive en memoria (la guarda el llamante).
    fun login(
        credentials: LoginCredentials,
        mac: String,
        id0: String,
        onResult: (LoginResult) -> Unit
    ) {
        val mainHandler = Handler(Looper.getMainLooper())
        fun post(r: LoginResult) = mainHandler.post { onResult(r) }
        Thread(Runnable {
            try {
                val r = doLoginBlocking(credentials, mac, id0)
                post(r)
            } catch (e: SocketTimeoutException) {
                post(LoginResult.Failure("network", "Tiempo de espera agotado al contactar con el servidor de login. Revisa tu conexión e inténtalo de nuevo."))
            } catch (e: UnknownHostException) {
                post(LoginResult.Failure("network", "Sin conexión o no se pudo resolver el servidor de login. Revisa tu conexión."))
            } catch (e: IOException) {
                // No se incluye body ni datos sensibles, solo mensaje corto de red.
                post(LoginResult.Failure("network", "Error de red al contactar con el servidor de login."))
            } catch (e: XmlRpcFault) {
                post(LoginResult.Failure("invalid_response", "Respuesta de fallo del servidor (fault ${e.faultCode}): ${e.faultString}"))
            } catch (e: Exception) {
                post(LoginResult.Failure("invalid_response", "Respuesta inválida del servidor de login."))
            }
        }).start()
    }

    private fun doLoginBlocking(
        credentials: LoginCredentials,
        mac: String,
        id0: String
    ): LoginResult {
        // passwd = "$1$" + MD5 hex minúsculo de la contraseña en UTF-8.
        // Nunca se envía la contraseña en claro. No se registra en logs.
        // Limitación JVM: String es inmutable y no se puede borrar de memoria
        // de forma segura; se evita crear copias extra.
        val passwd = "\$1\$" + md5HexLower(credentials.password)

        // platform_version desde el SDK. Si fuera null se usa "unknown".
        val platformVersion: String = try { Build.VERSION.RELEASE ?: "unknown" } catch (_: Exception) { "unknown" }

        // Petición exacta de la especificación M1. Tipos: agree_to_tos /
        // read_critical / viewer_digest como String, last_exec_event como
        // Int, extended_errors como Boolean.
        val options = listOf(
            "inventory-root", "inventory-skeleton", "inventory-lib-root",
            "inventory-lib-owner", "inventory-skel-lib", "buddy-list",
            "display_names", "adult_compliant", "login-flags", "global-textures",
            "max-agent-groups", "gestures", "event_categories", "event_notifications",
            "classified_categories", "initial-outfit", "ui-config"
        )
        val baseParams: Map<String, Any> = mapOf(
            "first" to credentials.first,
            "last" to credentials.last,
            "passwd" to passwd,
            "start" to credentials.start,
            "channel" to "Ephora Viewer",
            "version" to "0.1.0",
            "platform" to "Android",
            "platform_version" to platformVersion,
            "mac" to mac,
            "id0" to id0,
            "agree_to_tos" to "true",
            "read_critical" to "true",
            "viewer_digest" to "",
            "last_exec_event" to 0,
            "extended_errors" to true,
            "options" to options
        )

        var currentUrl = credentials.grid.url
        var currentMethod = "login_to_simulator"
        var currentParams: Map<String, Any> = baseParams
        var redirects = 0 // como mucho 3 repeticiones tras el primer POST

        while (true) {
            val xmlRequest = XmlRpc.buildMethodCall(currentMethod, currentParams)
            val (httpCode, body) = httpPostXml(currentUrl, xmlRequest)
            if (httpCode != 200) {
                return LoginResult.Failure("http", "No se pudo contactar con el servidor de login (HTTP $httpCode)")
            }
            if (body.isBlank()) {
                return LoginResult.Failure("invalid_response", "Respuesta vacía del servidor de login.")
            }
            val resp: Map<String, Any?> = try {
                XmlRpc.parseMethodResponse(body)
            } catch (e: XmlRpcFault) {
                throw e
            } catch (e: Exception) {
                return LoginResult.Failure("invalid_response", "Respuesta inválida del servidor de login.")
            }

            val loginRaw = resp["login"]
            val loginStr = when (loginRaw) {
                is Boolean -> if (loginRaw) "true" else "false"
                is String -> loginRaw.trim().lowercase()
                null -> ""
                else -> loginRaw.toString().trim().lowercase()
            }

            when (loginStr) {
                "true" -> return buildSession(resp)
                "false" -> {
                    val reason = (resp["reason"] as? String)?.takeIf { it.isNotBlank() } ?: "login_failed"
                    // reason y message tal cual del servidor.
                    val message = when (val m = resp["message"]) {
                        is String -> m
                        null -> "Login fallido."
                        else -> m.toString()
                    }
                    return LoginResult.Failure(reason, message)
                }
                "indeterminate" -> {
                    if (redirects >= 3) {
                        return LoginResult.Failure("indeterminate", "El servidor devolvió 'indeterminate' 3 veces. No se pudo completar el login. Inténtalo más tarde.")
                    }
                    val nextUrl = (resp["next_url"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                        ?: return LoginResult.Failure("indeterminate", "El servidor pidió continuar (indeterminate) pero no dio next_url.")
                    val nextMethod = (resp["next_method"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: currentMethod
                    val nextOptions = resp["next_options"]
                    // Se repite el MISMO POST base a next_url con next_method.
                    // SIN VERIFICAR: el formato exacto de next_options no está verificado;
                    // se reenvía tal cual como param "next_options" sin alterar el resto.
                    val nextParams: MutableMap<String, Any> = baseParams.toMutableMap()
                    if (nextOptions != null) {
                        nextParams["next_options"] = nextOptions
                    }
                    currentUrl = nextUrl
                    currentMethod = nextMethod
                    currentParams = nextParams
                    redirects++
                    continue
                }
                else -> return LoginResult.Failure("invalid_response", "Respuesta inválida del servidor de login.")
            }
        }
    }

    private fun buildSession(resp: Map<String, Any?>): LoginResult {
        val firstRaw = anyToString(resp["first_name"]).trim()
        // Trim de comillas: el servidor puede devolver el nombre entre comillas.
        val firstName = firstRaw.trim().trim('"', '\'').trim()
        val lastName = anyToString(resp["last_name"]).trim()
        val s = LoginSession(
            sessionId = anyToString(resp["session_id"]),
            secureSessionId = anyToString(resp["secure_session_id"]),
            agentId = anyToString(resp["agent_id"]),
            firstName = firstName,
            lastName = lastName,
            circuitCode = anyToInt(resp["circuit_code"]),
            simIp = anyToString(resp["sim_ip"]),
            simPort = anyToInt(resp["sim_port"]),
            regionX = anyToInt(resp["region_x"]),
            regionY = anyToInt(resp["region_y"]),
            seedCapability = anyToString(resp["seed_capability"]),
            agentAccess = anyToString(resp["agent_access"]),
            inventoryRoot = extractInventoryRoot(resp["inventory-root"]),
            // message como motd.
            motd = when (val m = resp["message"]) {
                null -> null
                is String -> m
                else -> m.toString()
            }
        )
        return LoginResult.Success(s)
    }

    private fun anyToInt(v: Any?): Int = when (v) {
        is Int -> v
        is Long -> v.toInt()
        is Double -> v.toInt()
        is Boolean -> if (v) 1 else 0
        is String -> v.trim().toIntOrNull() ?: 0
        else -> 0
    }

    private fun anyToString(v: Any?): String = when (v) {
        null -> ""
        is String -> v
        else -> v.toString()
    }

    private fun extractInventoryRoot(v: Any?): String? {
        // SIN VERIFICAR: inventory-root puede venir como String o como array
        // con un struct con folder_id. Se toleran ambas formas.
        return when (v) {
            null -> null
            is String -> v.takeIf { it.isNotBlank() }
            is Map<*, *> -> {
                val f = v["folder_id"]
                (f as? String)?.takeIf { it.isNotBlank() } ?: f?.toString()?.takeIf { it.isNotBlank() }
            }
            is List<*> -> {
                val first = v.firstOrNull() ?: return null
                when (first) {
                    is String -> first.takeIf { it.isNotBlank() }
                    is Map<*, *> -> {
                        val f = first["folder_id"]
                        (f as? String)?.takeIf { it.isNotBlank() } ?: f?.toString()?.takeIf { it.isNotBlank() }
                    }
                    else -> first?.toString()?.takeIf { it.isNotBlank() }
                }
            }
            else -> v.toString().takeIf { it.isNotBlank() }
        }
    }

    private fun httpPostXml(urlStr: String, xml: String): Pair<Int, String> {
        // HTTPS con timeout 60 s connect y read. Sin TrustAll ni validación desactivada.
        // Sin reintento automático (el único bucle es el de indeterminate del protocolo).
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 60000
        conn.readTimeout = 60000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.doInput = true
        conn.setRequestProperty("Content-Type", "text/xml")
        val bytes = xml.toByteArray(Charsets.UTF_8)
        conn.setFixedLengthStreamingMode(bytes.size)
        conn.outputStream.use { it.write(bytes) }
        val code = conn.responseCode
        val body = try {
            if (code == 200) conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            else try { conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText() ?: "" } catch (_: Exception) { "" }
        } catch (_: Exception) { "" }
        try { conn.disconnect() } catch (_: Exception) {}
        return Pair(code, body)
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
