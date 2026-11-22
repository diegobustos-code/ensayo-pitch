package cl.fireproof.ensayopitch

import java.text.Normalizer

/** Normalización de texto en español y comparación con lo que reconoce el micrófono. */
object TextMatch {

    val STOP: Set<String> = ("""
a al algo ante antes aqui asi aun bajo bien cada como con contra cual cuando de del desde donde dos e el ella ellas ellos
en entre era es esa ese eso esta este esto estos estas fue ha hace han hasta hay la las le les lo los mas me mi mis muy
nada ni no nos nuestra nuestro nuestros o otra otro para pero poco por porque que quien se sea ser si sin sobre son su
sus tambien te tiene tienen todo todos tu tus un una uno unos unas y ya yo eso esas esos les nosotros ustedes hoy
primero segundo tercero despues ademas entonces luego tambien siempre nunca ahora asimismo finalmente
""").split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()

    private val MARKS = Regex("\\p{Mn}+")
    private val NON_WORD = Regex("[^a-z0-9ñ ]+")
    private val SPACES = Regex("\\s+")

    fun stripAccents(s: String): String = MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "")

    fun normalize(s: String): String {
        var t = stripAccents(s.lowercase())
        t = t.replace("%", " por ciento ")
        t = NON_WORD.replace(t, " ")
        return SPACES.replace(t, " ").trim()
    }

    fun words(s: String): List<String> = normalize(s).split(" ").filter { it.isNotEmpty() }

    /** Últimas palabras con contenido: la "frase de salida" de cada turno. */
    fun tailWords(text: String, n: Int = 6): List<String> =
        words(text).filter { it.length > 2 && it !in STOP }.takeLast(n)

    fun similar(a: String, b: String): Boolean {
        if (a == b) return true
        return a.length >= 5 && b.length >= 5 && a.substring(0, 5) == b.substring(0, 5)
    }

    /** ¿Se dijeron (en orden, con holgura) la mayoría de las palabras finales del turno? */
    fun tailHit(tail: List<String>, recent: List<String>, threshold: Double = 0.7): Boolean {
        if (tail.isEmpty()) return false
        val window = recent.takeLast(tail.size * 4 + 6)
        var i = 0
        var hits = 0
        for (t in tail) {
            var j = i
            while (j < window.size) {
                if (similar(window[j], t)) {
                    hits++
                    i = j + 1
                    break
                }
                j++
            }
        }
        return hits.toDouble() / tail.size >= threshold
    }

    fun keywordHit(alts: List<String>, textNorm: String): Boolean {
        val padded = " $textNorm "
        for (alt in alts) {
            val a = normalize(alt)
            if (a.isEmpty()) continue
            if (a.contains(" ")) {
                if (textNorm.contains(a)) return true
            } else if (a.length >= 6) {
                if (Regex("\\b" + Regex.escape(a.substring(0, 5))).containsMatchIn(textNorm)) return true
            } else if (padded.contains(" $a ")) {
                return true
            }
        }
        return false
    }

    // ---------- números en palabras (español de Chile, sin tildes) ----------
    private val UNITS = listOf(
        "cero", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve", "diez",
        "once", "doce", "trece", "catorce", "quince", "dieciseis", "diecisiete", "dieciocho", "diecinueve",
        "veinte", "veintiuno", "veintidos", "veintitres", "veinticuatro", "veinticinco", "veintiseis",
        "veintisiete", "veintiocho", "veintinueve"
    )
    private val TENS = mapOf(3 to "treinta", 4 to "cuarenta", 5 to "cincuenta", 6 to "sesenta", 7 to "setenta",
        8 to "ochenta", 9 to "noventa")
    private val HUNDREDS = mapOf(1 to "ciento", 2 to "doscientos", 3 to "trescientos", 4 to "cuatrocientos",
        5 to "quinientos", 6 to "seiscientos", 7 to "setecientos", 8 to "ochocientos", 9 to "novecientos")

    private fun below1000(n: Int): String {
        if (n < 30) return UNITS[n]
        if (n < 100) {
            val t = TENS[n / 10]!!
            return if (n % 10 == 0) t else "$t y ${UNITS[n % 10]}"
        }
        if (n == 100) return "cien"
        val h = HUNDREDS[n / 100]!!
        return if (n % 100 == 0) h else "$h ${below1000(n % 100)}"
    }

    /** "uno" pasa a "un" delante de "mil" o "millones" (veintiún mil, un millón). */
    private fun apocope(s: String): String = when {
        s == "uno" -> "un"
        s.endsWith("veintiuno") -> s.removeSuffix("veintiuno") + "veintiun"
        s.endsWith(" uno") -> s.removeSuffix(" uno") + " un"
        else -> s
    }

    fun numberToWords(n: Long): String {
        if (n < 0) return "menos " + numberToWords(-n)
        if (n < 1000) return below1000(n.toInt())
        if (n < 1_000_000) {
            val th = (n / 1000).toInt()
            val rest = (n % 1000).toInt()
            val head = if (th == 1) "mil" else apocope(below1000(th)) + " mil"
            return if (rest == 0) head else "$head ${below1000(rest)}"
        }
        if (n < 1_000_000_000_000L) {
            val mi = n / 1_000_000
            val rest = n % 1_000_000
            val head = if (mi == 1L) "un millon" else apocope(numberToWords(mi)) + " millones"
            return if (rest == 0L) head else "$head ${numberToWords(rest)}"
        }
        return n.toString()
    }

    /** "1.700" -> {"1700", "mil setecientos"}; "21" + "mil" -> también "veintiun mil". */
    fun numberForms(token: String, following: String = ""): Set<String> {
        val raw = token.replace(".", "").replace(",", ".")
        val value = raw.toDoubleOrNull() ?: return emptySet()
        val forms = linkedSetOf<String>()
        forms.add(if (raw.contains(".")) raw.trimEnd('0').trimEnd('.') else raw)
        if (value == Math.floor(value) && value < 1e12) {
            val n = value.toLong()
            forms.add(numberToWords(n))
            val f = normalize(following)
            val mult = when (f) {
                "mil" -> 1000L
                "millon", "millones" -> 1_000_000L
                else -> 0L
            }
            if (mult > 0) {
                forms.add(numberToWords(n * mult))
                forms.add(apocope(numberToWords(n)) + " " + f)
                forms.add(numberToWords(n) + " " + f)
            }
        }
        return forms.filter { it.isNotEmpty() }.toSet()
    }

    /** Puntos clave automáticos: cifras del guion y palabras con más contenido. */
    fun autoKeywords(text: String, maxWords: Int = 4): List<Keyword> {
        val kws = mutableListOf<Keyword>()
        val toks = Regex("\\d[\\d.,]*\\s*%?|\\S+").findAll(text).map { it.value }.toList()
        for ((i, tok) in toks.withIndex()) {
            val m = Regex("^(\\d[\\d.,]*)\\s*(%?)").find(tok) ?: continue
            val num = m.groupValues[1].trimEnd('.', ',')
            val nxt = if (i + 1 < toks.size) toks[i + 1] else ""
            val forms = numberForms(num, nxt)
            if (forms.isEmpty()) continue
            val nn = normalize(nxt)
            val label = num + when {
                m.groupValues[2].isNotEmpty() -> " %"
                nn == "mil" || nn == "millones" || nn == "millon" -> " $nn"
                else -> ""
            }
            kws.add(Keyword(label, forms.sorted()))
        }
        data class Cand(val proper: Boolean, val len: Int, val word: String, val norm: String)
        val seen = hashSetOf<String>()
        val cands = mutableListOf<Cand>()
        for (m in Regex("[A-Za-zÁÉÍÓÚÑáéíóúñü]+").findAll(text)) {
            val w = m.value
            val n = normalize(w)
            if (n in STOP || n.length < 6 || n in seen) continue
            val before = text.substring(0, m.range.first).trimEnd()
            val sentenceStart = before.isEmpty() || before.last() in ".:;¿?¡!"
            if (sentenceStart && n.length < 9) continue
            seen.add(n)
            cands.add(Cand(w[0].isUpperCase() && !sentenceStart, n.length, w, n))
        }
        cands.sortWith(compareBy<Cand>({ !it.proper }, { -it.len }))
        for (c in cands.take(maxWords)) kws.add(Keyword(c.word, listOf(c.norm)))
        val out = mutableListOf<Keyword>()
        val labels = hashSetOf<String>()
        for (k in kws) {
            if (k.label.lowercase() in labels) continue
            labels.add(k.label.lowercase())
            out.add(k)
        }
        return out.take(8)
    }
}

data class Keyword(val label: String, val alts: List<String>)
