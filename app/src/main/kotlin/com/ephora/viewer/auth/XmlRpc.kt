package com.ephora.viewer.auth

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

// Cliente XML-RPC mínimo para login.cgi de Second Life.
// Decisión de protocolo: login usa XML-RPC por POST HTTPS, método login_to_simulator.
// No se usa ninguna librería externa, solo XmlPullParser del SDK de Android.
// SIN VERIFICAR: el manejo de <dateTime.iso8601> y <base64> como string crudo,
// porque login.cgi no los usa según el alcance pedido.

class XmlRpcFault(val faultCode: Int, val faultString: String) :
    Exception("XML-RPC fault $faultCode: $faultString")

object XmlRpc {

    // Construye <methodCall> con un único <param> que es un <struct>.
    // params: claves del struct, valores: String->string, Int/Long->int,
    // Boolean->boolean 1/0, List/Array->array, Map->struct anidado.
    fun buildMethodCall(methodName: String, params: Map<String, Any>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\"?>")
        sb.append("<methodCall><methodName>")
        sb.append(escapeXml(methodName))
        sb.append("</methodName><params><param><value><struct>")
        for ((k, v) in params) {
            sb.append("<member><name>").append(escapeXml(k)).append("</name>")
            appendValue(sb, v)
            sb.append("</member>")
        }
        sb.append("</struct></value></param></params></methodCall>")
        return sb.toString()
    }

    // Parsea <methodResponse> a Map. Si viene <fault>, lanza XmlRpcFault.
    // Tolera enteros en <int> o <i4>, y booleanos <boolean>1/0.
    // Los booleanos que el servidor manda como <string>true/false</string>
    // se devuelven como String "true"/"false"; el llamante los interpreta.
    fun parseMethodResponse(xml: String): Map<String, Any?> {
        if (xml.isBlank()) throw IllegalArgumentException("respuesta XML vacía")
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(StringReader(xml))
        p.nextTag()
        p.require(XmlPullParser.START_TAG, null, "methodResponse")
        p.nextTag()
        return when (p.name) {
            "fault" -> {
                p.nextTag()
                p.require(XmlPullParser.START_TAG, null, "value")
                val v = parseValue(p)
                val m = v as? Map<*, *>
                    ?: throw IllegalArgumentException("fault sin struct")
                val codeRaw = m["faultCode"]
                val code = when (codeRaw) {
                    is Int -> codeRaw
                    is Long -> codeRaw.toInt()
                    is String -> codeRaw.trim().toIntOrNull() ?: 0
                    else -> 0
                }
                val str = (m["faultString"] as? String)
                    ?: m["faultString"]?.toString() ?: ""
                throw XmlRpcFault(code, str)
            }
            "params" -> {
                p.nextTag()
                p.require(XmlPullParser.START_TAG, null, "param")
                p.nextTag()
                p.require(XmlPullParser.START_TAG, null, "value")
                val v = parseValue(p)
                @Suppress("UNCHECKED_CAST")
                (v as? Map<String, Any?>)
                    ?: throw IllegalArgumentException("se esperaba struct en respuesta")
            }
            else -> throw IllegalArgumentException("se esperaba <params> o <fault>, llegó <${p.name}>")
        }
    }

    // Escapa SIEMPRE: & < > " '
    private fun escapeXml(s: String): String {
        // & primero para no re-escapar.
        return s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun appendValue(sb: StringBuilder, v: Any) {
        when (v) {
            is String -> sb.append("<value><string>").append(escapeXml(v)).append("</string></value>")
            is Int -> sb.append("<value><int>").append(v).append("</int></value>")
            // XML-RPC int es 32 bits. SIN VERIFICAR si login.cgi acepta Long;
            // se envía como <int> por compatibilidad.
            is Long -> sb.append("<value><int>").append(v).append("</int></value>")
            is Boolean -> sb.append("<value><boolean>").append(if (v) "1" else "0").append("</boolean></value>")
            is Double -> sb.append("<value><double>").append(v).append("</double></value>")
            is Float -> sb.append("<value><double>").append(v.toDouble()).append("</double></value>")
            is List<*> -> {
                sb.append("<value><array><data>")
                for (e in v) {
                    if (e == null) sb.append("<value><string></string></value>")
                    else appendValue(sb, e as Any)
                }
                sb.append("</data></array></value>")
            }
            is Array<*> -> appendValue(sb, v.toList())
            is Map<*, *> -> {
                sb.append("<value><struct>")
                for ((mk, mv) in v) {
                    val key = mk as? String
                        ?: throw IllegalArgumentException("clave de struct no es String")
                    sb.append("<member><name>").append(escapeXml(key)).append("</name>")
                    if (mv == null) sb.append("<value><string></string></value>")
                    else appendValue(sb, mv as Any)
                    sb.append("</member>")
                }
                sb.append("</struct></value>")
            }
            else -> throw IllegalArgumentException("tipo no soportado para XML-RPC: ${v.javaClass.name}")
        }
    }

    private fun parseValue(p: XmlPullParser): Any? {
        p.require(XmlPullParser.START_TAG, null, "value")
        var textBuffer: String? = null
        while (true) {
            when (p.next()) {
                XmlPullParser.TEXT -> {
                    val t = p.text
                    if (!t.isNullOrBlank()) textBuffer = (textBuffer ?: "") + t
                }
                XmlPullParser.START_TAG -> {
                    val tag = p.name
                    val result: Any? = when (tag) {
                        "string" -> readTextOf(tag, p)
                        "int", "i4" -> {
                            val raw = readTextOf(tag, p).trim()
                            raw.toIntOrNull() ?: raw
                        }
                        "boolean" -> {
                            val raw = readTextOf(tag, p).trim()
                            raw == "1" || raw.equals("true", ignoreCase = true)
                        }
                        "double" -> {
                            val raw = readTextOf(tag, p).trim()
                            raw.toDoubleOrNull() ?: raw
                        }
                        "array" -> parseArray(p)
                        "struct" -> parseStruct(p)
                        // SIN VERIFICAR: otros tipos se devuelven como string crudo.
                        else -> readTextOf(tag, p)
                    }
                    // Avanzar hasta </value>, saltando blancos de formato.
                    var e: Int
                    do { e = p.next() } while (e == XmlPullParser.TEXT && p.text.isBlank())
                    if (e != XmlPullParser.END_TAG || p.name != "value") {
                        throw IllegalArgumentException("se esperaba </value>")
                    }
                    return result
                }
                XmlPullParser.END_TAG -> {
                    if (p.name == "value") return textBuffer ?: ""
                    else throw IllegalArgumentException("fin inesperado </${p.name}> en <value>")
                }
                XmlPullParser.END_DOCUMENT -> throw IllegalArgumentException("XML truncado en <value>")
                else -> {}
            }
        }
    }

    private fun readTextOf(tag: String, p: XmlPullParser): String {
        p.require(XmlPullParser.START_TAG, null, tag)
        val sb = StringBuilder()
        while (true) {
            when (p.next()) {
                XmlPullParser.TEXT -> sb.append(p.text)
                XmlPullParser.END_TAG -> {
                    if (p.name == tag) return sb.toString()
                    else throw IllegalArgumentException("fin inesperado </${p.name}> esperando </$tag>")
                }
                XmlPullParser.START_TAG -> throw IllegalArgumentException("tag inesperado <${p.name}> dentro de <$tag>")
                XmlPullParser.END_DOCUMENT -> throw IllegalArgumentException("XML truncado dentro de <$tag>")
                else -> {}
            }
        }
    }

    private fun parseStruct(p: XmlPullParser): Map<String, Any?> {
        p.require(XmlPullParser.START_TAG, null, "struct")
        val map = LinkedHashMap<String, Any?>()
        while (true) {
            when (p.nextTag()) {
                XmlPullParser.START_TAG -> {
                    if (p.name != "member") throw IllegalArgumentException("se esperaba <member>, llegó <${p.name}>")
                    // <member><name>clave</name><value>...</value></member>
                    p.nextTag()
                    p.require(XmlPullParser.START_TAG, null, "name")
                    val key = readTextOf("name", p)
                    p.nextTag()
                    p.require(XmlPullParser.START_TAG, null, "value")
                    val value = parseValue(p)
                    p.nextTag()
                    p.require(XmlPullParser.END_TAG, null, "member")
                    map[key] = value
                }
                XmlPullParser.END_TAG -> {
                    if (p.name == "struct") return map
                    else throw IllegalArgumentException("fin inesperado </${p.name}> en <struct>")
                }
                else -> throw IllegalArgumentException("evento inesperado en <struct>")
            }
        }
    }

    private fun parseArray(p: XmlPullParser): List<Any?> {
        p.require(XmlPullParser.START_TAG, null, "array")
        val out = ArrayList<Any?>()
        val first = p.nextTag()
        if (first == XmlPullParser.END_TAG && p.name == "array") return out // <array/> o <array></array>
        if (first == XmlPullParser.START_TAG && p.name == "data") {
            while (true) {
                when (p.nextTag()) {
                    XmlPullParser.START_TAG -> {
                        if (p.name != "value") throw IllegalArgumentException("se esperaba <value> en <data>")
                        out.add(parseValue(p))
                    }
                    XmlPullParser.END_TAG -> {
                        if (p.name == "data") break
                        else throw IllegalArgumentException("fin inesperado </${p.name}> en <data>")
                    }
                    else -> throw IllegalArgumentException("evento inesperado en <data>")
                }
            }
            p.nextTag()
            p.require(XmlPullParser.END_TAG, null, "array")
            return out
        }
        // Tolerancia SIN VERIFICAR: <array> sin <data>, con <value> directos.
        if (first == XmlPullParser.START_TAG && p.name == "value") {
            out.add(parseValue(p))
            while (true) {
                when (p.nextTag()) {
                    XmlPullParser.START_TAG -> {
                        if (p.name != "value") throw IllegalArgumentException("se esperaba <value> en <array>")
                        out.add(parseValue(p))
                    }
                    XmlPullParser.END_TAG -> {
                        if (p.name == "array") return out
                        else throw IllegalArgumentException("fin inesperado </${p.name}> en <array>")
                    }
                    else -> throw IllegalArgumentException("evento inesperado en <array>")
                }
            }
        }
        throw IllegalArgumentException("forma de <array> no reconocida")
    }
}
