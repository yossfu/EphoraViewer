package com.ephora.viewer.net

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

// LLSD-XML aparte de XmlRpc M1: <llsd><array><string>.. + <llsd><map><key>..<string>/<url>..
object LlsdXml {

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    fun buildArray(items: List<String>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\"?><llsd><array>")
        for (s in items) sb.append("<string>").append(esc(s)).append("</string>")
        sb.append("</array></llsd>")
        return sb.toString()
    }

    fun parseStringMap(xml: String): Map<String, String> {
        if (xml.isBlank()) throw IllegalArgumentException("LLSD vacío")
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(StringReader(xml))
        p.nextTag()
        p.require(XmlPullParser.START_TAG, null, "llsd")
        p.nextTag()
        p.require(XmlPullParser.START_TAG, null, "map")
        val m = LinkedHashMap<String, String>()
        while (true) {
            when (p.nextTag()) {
                XmlPullParser.START_TAG -> {
                    if (p.name != "key") throw IllegalArgumentException("se esperaba <key>")
                    val k = p.nextText()
                    p.nextTag() // valor <string> o <url>
                    if (p.name != "string" && p.name != "url") {
                        // Valor no textual (p. ej. <integer>): no es una URL,
                        // se ignora en vez de romper todo el mapa.
                        skipElement(p)
                    } else {
                        val v = p.nextText()
                        m[k] = v
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (p.name == "map") return m
                }
                else -> {}
            }
        }
    }

    // Consume el elemento actual (ya estamos en su START_TAG) hasta su END_TAG.
    private fun skipElement(p: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (p.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }
}
