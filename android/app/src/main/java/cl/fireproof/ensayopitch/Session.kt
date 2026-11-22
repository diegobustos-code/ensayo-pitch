package cl.fireproof.ensayopitch

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Modelo del ensayo, sin interfaz. El reloj no se detiene entre turnos: el ensayo corre de
 * principio a fin y cada segundo se le carga al turno que está en pantalla.
 * Los tiempos van en segundos (Double).
 */
const val TEAM = "team"
const val SOLO = "solo"
const val TOTAL_MIN = 270.0
const val TOTAL_MAX = 305.0

class SectionRun(val section: Section) {
    var start: Double? = null
    var end: Double? = null
    var voiced = 0.0
    var longestSilence = 0.0
    var silenceNow = 0.0
    var spokeOnce = false
    val words = mutableListOf<String>()
    var transcript = ""
    val hits = linkedSetOf<Int>()
    var tailHit = false
    var byMachine = false
    var howEnded = ""

    val elapsed: Double
        get() {
            val s = start ?: return 0.0
            return (end ?: s) - s
        }

    fun hitLabels() = section.keywords.filterIndexed { i, _ -> i in hits }.map { it.label }
    fun missedLabels() = section.keywords.filterIndexed { i, _ -> i !in hits }.map { it.label }
}

data class SpeakerTotal(
    val speaker: String, var target: Int = 0, var real: Double = 0.0, var voiced: Double = 0.0,
    var kwHit: Int = 0, var kwTotal: Int = 0, var machine: Boolean = false, var turns: Int = 0,
)

class Rehearsal(val deck: Deck, val mode: String = TEAM, val profile: String? = null, val autoAdvance: Boolean = true) {
    val runs = deck.sections.map { SectionRun(it) }
    var idx = -1
        private set
    var t0: Double? = null
        private set
    var tEnd: Double? = null
        private set
    private var nowT = 0.0
    private var pendingAt: Double? = null
    private var pendingReason = ""

    val started get() = t0 != null
    val finished get() = tEnd != null
    val current: SectionRun? get() = runs.getOrNull(idx)
    val nextRun: SectionRun? get() = runs.getOrNull(idx + 1)

    fun isMine(r: SectionRun) = profile != null && r.section.speaker == profile
    fun machineReads(r: SectionRun) = mode == SOLO && profile != null && r.section.speaker != profile

    fun totalElapsed(now: Double? = null): Double {
        val s = t0 ?: return 0.0
        return (tEnd ?: now ?: nowT) - s
    }

    fun start(now: Double) {
        t0 = now
        nowT = now
        enter(0, now)
    }

    private fun enter(i: Int, now: Double) {
        idx = i
        val r = runs[i]
        r.start = now; r.end = null
        r.voiced = 0.0; r.longestSilence = 0.0; r.silenceNow = 0.0
        r.spokeOnce = false
        r.words.clear(); r.transcript = ""; r.hits.clear(); r.tailHit = false
        r.byMachine = machineReads(r)
        r.howEnded = ""
        pendingAt = null
    }

    /** Pasa al turno siguiente. Devuelve false si se terminó el ensayo. */
    fun advance(now: Double, how: String = "tecla"): Boolean {
        val cur = current ?: return false
        if (finished) return false
        cur.end = now
        cur.howEnded = how
        if (idx + 1 >= runs.size) {
            tEnd = now
            return false
        }
        enter(idx + 1, now)
        return true
    }

    /** Vuelve al turno anterior y lo repite desde cero. */
    fun back(now: Double) {
        if (finished || idx <= 0) return
        current?.let { it.start = null; it.end = null }
        enter(idx - 1, now)
    }

    fun finish(now: Double) {
        if (finished) return
        current?.let {
            it.end = now
            if (it.howEnded.isEmpty()) it.howEnded = "fin"
        }
        tEnd = now
    }

    fun onAudio(now: Double, dt: Double, isVoiced: Boolean) {
        nowT = now
        val r = current ?: return
        if (finished) return
        r.end = now
        if (r.byMachine) return
        if (isVoiced) {
            r.voiced += dt
            r.spokeOnce = true
            r.silenceNow = 0.0
        } else {
            r.silenceNow += dt
            if (r.spokeOnce) r.longestSilence = max(r.longestSilence, r.silenceNow)
        }
    }

    fun onText(text: String, isFinal: Boolean) {
        val r = current ?: return
        if (finished || r.byMachine) return
        val norm = TextMatch.normalize(text)
        if (isFinal && norm.isNotEmpty()) {
            r.transcript += " $norm"
            r.words.addAll(norm.split(" "))
        }
        val probe = (r.transcript + " " + (if (isFinal) "" else norm)).trim()
        r.section.keywords.forEachIndexed { i, k ->
            if (i !in r.hits && TextMatch.keywordHit(k.alts, probe)) r.hits.add(i)
        }
        val recent = r.words + (if (isFinal || norm.isEmpty()) emptyList() else norm.split(" "))
        if (!r.tailHit && TextMatch.tailHit(r.section.tail, recent)) r.tailHit = true
    }

    fun silenceLimit(r: SectionRun) = if (r.section.hasPause) 7.0 else 3.5

    /** Revisa si corresponde avanzar solo. Devuelve el motivo si avanzó. */
    fun poll(now: Double): String? {
        nowT = now
        val r = current ?: return null
        if (finished || !autoAdvance || r.byMachine) return null
        val st = r.start ?: return null
        val el = now - st
        val enough = el >= 0.4 * r.section.targetS
        if (pendingAt == null && enough) {
            if (r.tailHit) {
                pendingAt = now + 0.7; pendingReason = "frase"
            } else if (mode == SOLO && r.spokeOnce && r.silenceNow >= silenceLimit(r)) {
                pendingAt = now; pendingReason = "silencio"
            } else if (mode == TEAM && r.spokeOnce && r.silenceNow >= 6.0 && el >= 0.7 * r.section.targetS) {
                pendingAt = now; pendingReason = "silencio"
            }
        }
        val p = pendingAt
        if (p != null && now >= p) {
            val reason = pendingReason
            // el turno termina cuando se dijo la frase, no cuando reaccionó el teléfono
            val endAt = max(st, now - (if (reason == "silencio") r.silenceNow else 0.7))
            advance(endAt, reason)
            return reason
        }
        return null
    }

    fun perSpeaker(): List<SpeakerTotal> {
        val out = LinkedHashMap<String, SpeakerTotal>()
        for (r in runs) {
            val d = out.getOrPut(r.section.speaker) { SpeakerTotal(r.section.speaker) }
            d.target += r.section.targetS
            d.real += r.elapsed
            d.voiced += r.voiced
            d.kwHit += r.hits.size
            d.kwTotal += r.section.keywords.size
            d.machine = d.machine || r.byMachine
            d.turns += 1
        }
        return out.values.toList()
    }

    fun verdict(): String {
        val t = totalElapsed()
        return when {
            t < deck.minTotal -> "corto"
            t > deck.maxTotal -> "largo"
            else -> "ok"
        }
    }
}

fun fmt(sec: Double): String {
    val s = max(0, sec.roundToInt())
    return "%d:%02d".format(s / 60, s % 60)
}

fun diffLabel(real: Double, target: Double): String {
    val d = real - target
    if (abs(d) < 1.5) return "justo"
    return if (d > 0) "+${d.roundToInt()} s" else "−${(-d).roundToInt()} s"
}
