package cl.fireproof.ensayopitch

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToInt

/**
 * Lee el guion desde la plantilla de Word (.docx, una tabla) o desde texto (.txt, bloques separados por ---).
 * Columnas: Lámina · Quién habla · Segundos · Lo que dice · Puntos clave (separados por ;)
 */
data class GuionEntry(val slide: Int, val speaker: String, val secs: Int, val text: String, val keywords: List<String>)

object GuionParser {
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    fun parseSecs(v: String?): Int? {
        val s = (v ?: "").trim().lowercase()
        if (s.isEmpty()) return null
        Regex("^(\\d+)\\s*:\\s*(\\d{1,2})").find(s)?.let { return it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
        val m = Regex("(\\d+(?:[.,]\\d+)?)").find(s) ?: return null
        var n = m.groupValues[1].replace(",", ".").toDouble()
        if (Regex("\\bmin").containsMatchIn(s)) n *= 60
        return n.roundToInt()
    }

    fun splitKeywords(v: String?): List<String> =
        (v ?: "").split(Regex("[;\\n•]+")).map { it.trim(' ', '.', '-', '–', '\t') }.filter { it.isNotEmpty() }

    fun colKind(h: String): String? {
        val n = TextMatch.normalize(h)
        if (n.isEmpty()) return null
        return when {
            "clave" in n || "puntos" in n || "no olvidar" in n -> "kw"
            "quien" in n || "orador" in n || "nombre" in n || "integrante" in n || "habla" in n -> "speaker"
            "segundo" in n || "tiempo" in n || "duracion" in n || n == "s" || n == "seg" -> "secs"
            "dice" in n || "texto" in n || "guion" in n || "decir" in n -> "text"
            "lamina" in n || "diapositiva" in n || "slide" in n || n in setOf("n", "no", "nro", "numero") -> "slide"
            else -> null
        }
    }

    private fun clean(s: String) = s.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    fun rowsToEntries(rows: List<List<String>>): List<GuionEntry> {
        if (rows.isEmpty()) return emptyList()
        val kinds = rows[0].map { colKind(it) }
        if ("speaker" !in kinds || "secs" !in kinds) return emptyList()
        val out = mutableListOf<GuionEntry>()
        var last = 0
        for (r in rows.drop(1)) {
            val d = HashMap<String, String>()
            kinds.forEachIndexed { i, k -> if (k != null && k !in d && i < r.size) d[k] = r[i].trim() }
            val sp = d["speaker"] ?: ""
            val text = d["text"] ?: ""
            if (sp.isEmpty() && text.isEmpty()) continue
            val slide = d["slide"]?.takeIf { it.isNotEmpty() }?.let { parseSecs(it) } ?: (last + 1)
            last = slide
            out.add(GuionEntry(slide, sp.ifEmpty { "Sin nombre" }, parseSecs(d["secs"]) ?: 20, clean(text), splitKeywords(d["kw"])))
        }
        return out
    }

    private fun kids(el: Element, name: String): List<Element> {
        val out = mutableListOf<Element>()
        var n = el.firstChild
        while (n != null) {
            if (n is Element && n.namespaceURI == W && n.localName == name) out.add(n)
            n = n.nextSibling
        }
        return out
    }

    private fun cellText(tc: Element): String {
        val ps = tc.getElementsByTagNameNS(W, "p")
        val lines = mutableListOf<String>()
        for (i in 0 until ps.length) {
            val ts = (ps.item(i) as Element).getElementsByTagNameNS(W, "t")
            val sb = StringBuilder()
            for (j in 0 until ts.length) sb.append(ts.item(j).textContent)
            lines.add(sb.toString())
        }
        return lines.joinToString("\n").trim()
    }

    fun parseDocx(bytes: ByteArray): List<GuionEntry> {
        var doc: ByteArray? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (e.name == "word/document.xml") { doc = z.readBytes(); break }
                e = z.nextEntry
            }
        }
        val data = doc ?: return emptyList()
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val root = f.newDocumentBuilder().parse(ByteArrayInputStream(data)).documentElement
        val tbls = root.getElementsByTagNameNS(W, "tbl")
        for (i in 0 until tbls.length) {
            val tbl = tbls.item(i) as Element
            val rows = kids(tbl, "tr").map { tr -> kids(tr, "tc").map { cellText(it) } }
            val entries = rowsToEntries(rows)
            if (entries.isNotEmpty()) return entries
        }
        return emptyList()
    }

    fun parseTxt(raw: String): List<GuionEntry> {
        val text = raw.replace("\r\n", "\n").trimStart('﻿')
        val blocks = ("\n$text\n").split(Regex("\\n\\s*-{3,}\\s*\\n"))
        val out = mutableListOf<GuionEntry>()
        var last = 0
        val keyRe = Regex("^\\s*([A-Za-zÁÉÍÓÚÑáéíóúñ ]{2,30}):\\s*(.*)$")
        for (b in blocks) {
            val d = HashMap<String, String>()
            var cur: String? = null
            for (line in b.split("\n")) {
                if (line.trim().startsWith("#")) continue
                val m = keyRe.find(line)
                val kind = m?.let { colKind(it.groupValues[1]) }
                if (kind != null) {
                    cur = kind
                    d[kind] = m.groupValues[2].trim()
                } else {
                    val c = cur
                    if (c != null && (c == "text" || c == "kw")) d[c] = ((d[c] ?: "") + "\n" + line).trim()
                }
            }
            val sp = d["speaker"] ?: ""
            val body = d["text"] ?: ""
            if (sp.isEmpty() && body.isEmpty()) continue
            val slide = d["slide"]?.takeIf { it.isNotEmpty() }?.let { parseSecs(it) } ?: (last + 1)
            last = slide
            out.add(GuionEntry(slide, sp.ifEmpty { "Sin nombre" }, parseSecs(d["secs"]) ?: 20, clean(body), splitKeywords(d["kw"])))
        }
        return out
    }

    /** Detecta el formato por el contenido: un .docx es un zip (empieza con "PK"). */
    fun parse(bytes: ByteArray): List<GuionEntry> {
        val isZip = bytes.size > 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        val entries = if (isZip) parseDocx(bytes) else {
            val txt = try {
                val dec = Charsets.UTF_8.newDecoder()
                dec.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            } catch (_: Exception) {
                String(bytes, Charsets.ISO_8859_1)
            }
            parseTxt(txt)
        }
        require(entries.isNotEmpty()) {
            "No encontré el guion. Usa la plantilla: columnas «Quién habla» y «Segundos», o bloques «Quién habla: …» separados por ---."
        }
        return entries
    }
}
