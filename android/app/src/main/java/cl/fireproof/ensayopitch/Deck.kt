package cl.fireproof.ensayopitch

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Lee la presentación: quién habla en cada lámina, cuánto tiempo le toca y qué tiene que decir.
 * Las notas del orador deben empezar así:  NOMBRE (25 s). texto que se dice...
 */
data class Section(
    val index: Int,
    val slideNo: Int,
    val speaker: String,
    val targetS: Int,
    val text: String,
    val title: String,
    val image: String?,          // ruta dentro de assets, o null
    val tail: List<String>,
    val keywords: List<Keyword>,
    val hasPause: Boolean,
)

class Deck(val name: String, val sections: List<Section>, val isDefault: Boolean) {
    val speakers: List<String> get() = sections.map { it.speaker }.distinct()
    val totalTarget: Int get() = sections.sumOf { it.targetS }
    /** Rango aceptable: hasta 10 % menos que la meta y 5 s de más (5:00 -> 4:30-5:05). */
    val minTotal: Double get() = Math.round(totalTarget * 0.9).toDouble()
    val maxTotal: Double get() = totalTarget + 5.0
    fun targetFor(sp: String) = sections.filter { it.speaker == sp }.sumOf { it.targetS }
}

object DeckParser {
    private const val P = "http://schemas.openxmlformats.org/presentationml/2006/main"
    private const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val REL = "http://schemas.openxmlformats.org/package/2006/relationships"

    private val NOTE_RE = Regex(
        "^\\s*([A-ZÁÉÍÓÚÑÜa-záéíóúñü][A-ZÁÉÍÓÚÑÜa-záéíóúñü .]*?)\\s*\\((\\d+)\\s*s(?:eg)?\\)\\.?\\s*(.*)$",
        RegexOption.DOT_MATCHES_ALL
    )

    fun prettyName(raw: String): String {
        val small = setOf("de", "del", "la", "y")
        return raw.trim().split(Regex("\\s+")).joinToString(" ") { p ->
            if (p.lowercase() in small) p.lowercase()
            else p.take(1).uppercase() + p.drop(1).lowercase()
        }
    }

    fun cleanSpoken(body: String): String {
        var t = body
        t = Regex("\\((?:pausa|silencio|[^)]{0,40}(?:mostrar|apunta|señala|clic)[^)]*)\\)", RegexOption.IGNORE_CASE)
            .replace(t, " ")
        t = Regex("\\bTodos\\s*:\\s*['\"‘“]?[^'\"’”]*['\"’”]?").replace(t, " ")
        t = Regex("[‘’“”'\"]").replace(t, "")
        return Regex("\\s+").replace(t, " ").trim()
    }

    data class Parsed(val speaker: String, val secs: Int, val text: String, val pause: Boolean)

    fun parseNotes(raw: String): Parsed? {
        val m = NOTE_RE.find(raw) ?: return null
        val body = m.groupValues[3]
        return Parsed(
            prettyName(m.groupValues[1]), m.groupValues[2].toInt(), cleanSpoken(body),
            Regex("\\(pausa\\)", RegexOption.IGNORE_CASE).containsMatchIn(body)
        )
    }

    fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }.take(16)

    // ---------- lectura del .pptx ----------
    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (!e.isDirectory && (e.name.endsWith(".xml") || e.name.endsWith(".rels"))) out[e.name] = z.readBytes()
                e = z.nextEntry
            }
        }
        return out
    }

    private fun xml(b: ByteArray): Element {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        return f.newDocumentBuilder().parse(ByteArrayInputStream(b)).documentElement
    }

    private fun rels(files: Map<String, ByteArray>, path: String): Map<String, String> {
        val dir = path.substringBeforeLast('/', "")
        val relPath = (if (dir.isEmpty()) "" else "$dir/") + "_rels/" + path.substringAfterLast('/') + ".rels"
        val b = files[relPath] ?: return emptyMap()
        val root = xml(b)
        val nodes = root.getElementsByTagNameNS(REL, "Relationship")
        val out = HashMap<String, String>()
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as Element
            out[el.getAttribute("Id")] = resolve(dir, el.getAttribute("Target"))
        }
        return out
    }

    private fun resolve(dir: String, target: String): String {
        if (target.startsWith("/")) return target.drop(1)
        val parts = (if (dir.isEmpty()) emptyList() else dir.split("/")).toMutableList()
        for (p in target.split("/")) {
            when (p) {
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                ".", "" -> {}
                else -> parts.add(p)
            }
        }
        return parts.joinToString("/")
    }

    private fun paragraphs(sp: Element): List<String> {
        val ps = sp.getElementsByTagNameNS(A, "p")
        val out = mutableListOf<String>()
        for (i in 0 until ps.length) {
            val p = ps.item(i) as Element
            val ts = p.getElementsByTagNameNS(A, "t")
            val sb = StringBuilder()
            for (j in 0 until ts.length) sb.append(ts.item(j).textContent)
            out.add(sb.toString())
        }
        return out
    }

    private fun phType(sp: Element): String? {
        val ph = sp.getElementsByTagNameNS(P, "ph")
        if (ph.length == 0) return null
        return (ph.item(0) as Element).getAttribute("type").ifEmpty { "body?" }
    }

    private fun shapes(root: Element): List<Element> {
        val l = root.getElementsByTagNameNS(P, "sp")
        return (0 until l.length).map { l.item(it) as Element }
    }

    private fun notesText(root: Element): String {
        for (sp in shapes(root)) {
            if (phType(sp) == "body") return paragraphs(sp).joinToString("\n")
        }
        return ""
    }

    private fun slideTitle(root: Element): String {
        var best = ""
        for (sp in shapes(root)) {
            val txt = paragraphs(sp).joinToString("\n").trim()
            val t = phType(sp)
            if ((t == "title" || t == "ctrTitle") && txt.isNotEmpty()) return txt
            if (txt.length > best.length) best = txt
        }
        return best.take(140)
    }

    fun titlesOf(bytes: ByteArray): Map<Int, String> {
        val files = unzip(bytes)
        val presRels = rels(files, "ppt/presentation.xml")
        val pres = xml(files["ppt/presentation.xml"] ?: return emptyMap())
        val ids = pres.getElementsByTagNameNS(P, "sldId")
        val out = HashMap<Int, String>()
        for (i in 0 until ids.length) {
            val rid = (ids.item(i) as Element).getAttributeNS(R, "id")
            val path = presRels[rid] ?: continue
            val x = files[path] ?: continue
            out[i + 1] = slideTitle(xml(x))
        }
        return out
    }

    /**
     * Lee un .pptx. [imageFor] da la imagen de cada lámina (o null).
     * [curated] reemplaza los puntos clave automáticos cuando es la presentación del equipo.
     */
    fun load(bytes: ByteArray, name: String, isDefault: Boolean,
             imageFor: (Int) -> String? = { null },
             curated: Map<Int, List<Keyword>> = emptyMap()): Deck {
        val files = unzip(bytes)
        val presRels = rels(files, "ppt/presentation.xml")
        val pres = xml(files["ppt/presentation.xml"] ?: error("No es una presentación de PowerPoint válida."))
        val ids = pres.getElementsByTagNameNS(P, "sldId")
        val sections = mutableListOf<Section>()
        for (i in 0 until ids.length) {
            val slideNo = i + 1
            val rid = (ids.item(i) as Element).getAttributeNS(R, "id")
            val slidePath = presRels[rid] ?: continue
            val slideXml = files[slidePath] ?: continue
            val notesPath = rels(files, slidePath).values.firstOrNull { it.contains("notesSlide") }
            val raw = notesPath?.let { files[it] }?.let { notesText(xml(it)) } ?: ""
            val parsed = parseNotes(raw) ?: continue
            val kws = (if (isDefault) curated[slideNo] else null) ?: TextMatch.autoKeywords(parsed.text)
            sections.add(
                Section(
                    index = sections.size, slideNo = slideNo, speaker = parsed.speaker, targetS = parsed.secs,
                    text = parsed.text, title = slideTitle(xml(slideXml)), image = imageFor(slideNo),
                    tail = TextMatch.tailWords(parsed.text, 6), keywords = kws, hasPause = parsed.pause,
                )
            )
        }
        require(sections.isNotEmpty()) { "No encontré notas con el formato «NOMBRE (25 s). texto» en esta presentación. Si tienes el guion aparte, cárgalo con «Guion»." }
        return Deck(name, sections, isDefault)
    }
}

/** Títulos de cada lámina de un .pptx (número de lámina -> título). */
fun DeckParser.slideTitles(bytes: ByteArray): Map<Int, String> = titlesOf(bytes)

/** Guion (y opcionalmente la presentación para los títulos). */
fun DeckParser.loadWithGuion(entries: List<GuionEntry>, name: String, pptx: ByteArray?): Deck {
    val titles = if (pptx != null) titlesOf(pptx) else emptyMap()
    val sections = entries.mapIndexed { i, e ->
        val speaker = if (e.speaker == e.speaker.uppercase()) prettyName(e.speaker) else e.speaker.trim().split(Regex("\\s+")).joinToString(" ")
        val text = cleanSpoken(e.text)
        val kws = if (e.keywords.isNotEmpty()) e.keywords.map { labelKeyword(it) } else TextMatch.autoKeywords(text)
        Section(
            index = i, slideNo = e.slide, speaker = speaker, targetS = e.secs, text = text,
            title = titles[e.slide] ?: (text.take(110) + if (text.length > 110) "…" else ""),
            image = null, tail = TextMatch.tailWords(text, 6), keywords = kws,
            hasPause = Regex("\\(pausa\\)", RegexOption.IGNORE_CASE).containsMatchIn(e.text),
        )
    }
    return Deck(name, sections, false)
}

/** Puntos clave escogidos a mano para la presentación del equipo. */
object Curated {
    private fun k(label: String, vararg alts: String) = Keyword(label, alts.toList())

    val KEYWORDS: Map<Int, List<Keyword>> = mapOf(
        1 to listOf(k("levanten la mano", "levanten la mano", "levanten"), k("parcela", "parcela"),
            k("cerrar el terreno", "cerrarlo", "cerrar"), k("las mismas ramas", "mismas ramas", "ramas")),
        2 to listOf(k("Camila y Andrés", "camila", "andres"), k("36 y 38 años", "treinta y seis", "treinta y ocho", "36", "38"),
            k("Pirque", "pirque"), k("autoconstructores", "autoconstructor", "auto constructor"),
            k("maestro de confianza", "maestro"), k("ellos compran el bloque", "compran")),
        3 to listOf(k("cierre y bodega", "bodega"), k("\$1.700 el bloque", "mil setecientos", "1700"),
            k("21 mil por m²", "veintiun mil", "veintiuno mil", "veinte y un mil", "21000", "metro cuadrado"),
            k("ningún bloque con residuos", "residuos")),
        4 to listOf(k("cerros de ramas", "cerros", "ramas"), k("retiro \$130 mil", "ciento treinta mil", "130000"),
            k("quema prohibida", "prohibido", "quemar"), k("multa de 5 millones", "cinco millones", "millones"),
            k("interfaz urbano-rural", "interfaz", "urbano rural"), k("60 % de los incendios", "sesenta", "incendios")),
        5 to listOf(k("cuatro cosas", "cuatro cosas"), k("bloque de hormigón", "hormigon"),
            k("retiro, quema o acumulan", "acumulan", "queman"), k("dos gastos separados", "gastos separados", "separados")),
        6 to listOf(k("ramas de poda", "poda", "ramas"), k("Virginia", "virginia"), k("BioBloque", "biobloque", "bio bloque"),
            k("constructor civil municipal", "constructor civil", "municipal"), k("ajustamos el cliente", "ajustamos"),
            k("no concluyente", "no concluyente", "concluyente"), k("probetas físicas", "probetas")),
        7 to listOf(k("de 21 a 16 mil", "dieciseis mil", "16000", "dieciséis"), k("más de 20 % menos", "veinte por ciento", "20"),
            k("30 % más liviano", "liviano"), k("retiro a costo cero", "cero", "gratis"),
            k("cierre de 48 m", "cuarenta y ocho"), k("240 mil menos", "doscientos cuarenta mil", "240000")),
        8 to listOf(k("retiro por sector", "sector", "junta de vecinos"), k("chipeamos", "chipeamos", "chip"),
            k("hervimos (azúcares)", "hervimos", "azucares"), k("cal", "cal"),
            k("cemento y arena, 30 %", "treinta por ciento", "arena"), k("la venta paga el retiro", "gratis", "venta")),
        9 to listOf(k("SolidWorks", "solidworks", "solid works", "solid"), k("bloque de control", "control"),
            k("hervirla y cal", "hervirla", "cal"), k("62 %", "sesenta y dos"), k("sobre el 50 %", "cincuenta"),
            k("cierres y bodegas", "bodegas", "cierres")),
        10 to listOf(k("tres desafíos", "tres desafios", "desafios"), k("del parcelero al autoconstructor", "autoconstructor", "auto constructor"),
            k("parcelero proveedor", "proveedor"), k("cambiamos el proceso", "proceso"),
            k("más canales", "canales"), k("preventa", "preventa", "pre venta")),
        11 to listOf(k("SolidWorks y expertos", "solidworks", "solid works", "expertos"), k("César", "cesar"),
            k("Bastián", "bastian"), k("Gaspar", "gaspar"), k("Daniel", "daniel"), k("Diego Gutiérrez", "gutierrez")),
        12 to listOf(k("escuchar el feedback", "feedback", "fidback"), k("ajustamos el segmento", "segmento"),
            k("la información llega a todos", "informacion"), k("documento de precios", "documento", "precio")),
        13 to listOf(k("más barato, más liviano", "barato", "liviano"), k("preventa", "preventa", "pre venta"),
            k("BioBloques", "biobloque", "bio bloque"), k("¿Construimos su cierre?", "construimos")),
    )
}
