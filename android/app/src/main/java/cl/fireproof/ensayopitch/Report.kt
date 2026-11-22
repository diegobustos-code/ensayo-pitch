package cl.fireproof.ensayopitch

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** Historial y ajustes en el almacenamiento privado de la app. */
class Store(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("ajustes", Context.MODE_PRIVATE)
    private val histFile get() = File(ctx.filesDir, "historial.json")

    fun getString(k: String, d: String? = null): String? = prefs.getString(k, d)
    fun getInt(k: String, d: Int) = prefs.getInt(k, d)
    fun getBool(k: String, d: Boolean) = prefs.getBoolean(k, d)
    fun put(k: String, v: Any?) {
        val e = prefs.edit()
        when (v) {
            null -> e.remove(k)
            is String -> e.putString(k, v)
            is Int -> e.putInt(k, v)
            is Boolean -> e.putBoolean(k, v)
        }
        e.apply()
    }

    fun history(): List<JSONObject> = try {
        val a = JSONArray(histFile.readText())
        (0 until a.length()).map { a.getJSONObject(it) }
    } catch (_: Exception) {
        emptyList()
    }

    fun historyFor(profile: String?): List<JSONObject> =
        history().filter { (if (it.isNull("profile")) null else it.optString("profile")) == profile }

    fun addHistory(entry: JSONObject) {
        val a = JSONArray()
        (history() + entry).takeLast(200).forEach { a.put(it) }
        histFile.writeText(a.toString())
    }

    fun reportsDir(): File = File(ctx.filesDir, "Informes").also { it.mkdirs() }
}

object Report {
    fun summary(reh: Rehearsal): JSONObject {
        val o = JSONObject()
        o.put("date", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()))
        o.put("mode", reh.mode)
        o.put("profile", reh.profile ?: JSONObject.NULL)
        o.put("total", reh.totalElapsed())
        o.put("target_total", reh.deck.totalTarget)
        o.put("verdict", reh.verdict())
        val sp = JSONArray()
        for (d in reh.perSpeaker()) {
            sp.put(JSONObject().put("speaker", d.speaker).put("target", d.target).put("real", d.real)
                .put("kw_hit", d.kwHit).put("kw_total", d.kwTotal).put("machine", d.machine))
        }
        o.put("speakers", sp)
        return o
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun color(real: Double, target: Double): String {
        val d = real - target
        return if (abs(d) <= max(3.0, 0.12 * target)) "#2f7d4f" else if (d > 0) "#b23a2a" else "#b7791f"
    }

    /** Escribe el informe HTML y el CSV. Devuelve el archivo HTML. */
    fun write(reh: Rehearsal, dir: File): File {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
        val who = reh.profile ?: "Equipo"
        val base = "Ensayo_${stamp}_${who.replace(' ', '_')}"
        val csv = StringBuilder("﻿Lámina;Orador;Meta (s);Real (s);Diferencia;Con voz (s);Silencio más largo (s);Puntos dichos;Puntos que faltaron;Leído por el teléfono;Cómo terminó\n")
        val ran = reh.runs.filter { it.start != null }
        for (r in ran) {
            val s = r.section
            csv.append(listOf(s.slideNo, s.speaker, s.targetS, "%.1f".format(Locale.US, r.elapsed),
                diffLabel(r.elapsed, s.targetS.toDouble()), "%.1f".format(Locale.US, r.voiced),
                "%.1f".format(Locale.US, r.longestSilence), r.hitLabels().joinToString(", "),
                r.missedLabels().joinToString(", "), if (r.byMachine) "sí" else "no", r.howEnded).joinToString(";"))
            csv.append("\n")
        }
        File(dir, "$base.csv").writeText(csv.toString())

        val total = reh.totalElapsed()
        val (vtxt, vcol) = when (reh.verdict()) {
            "ok" -> "Dentro del tiempo" to "#2f7d4f"
            "corto" -> "Quedó corto" to "#b7791f"
            else -> "Se pasaron del tiempo" to "#b23a2a"
        }
        val prof = reh.profile
        val mode = if (reh.mode == SOLO) "Ensayo individual" else "Ensayo del equipo"
        val spRows = reh.perSpeaker().joinToString("") { d ->
            val mine = prof != null && d.speaker == prof
            "<tr class='${if (mine) "mine" else ""}'><td>${esc(d.speaker)}${if (mine) " (tú)" else ""}" +
                "${if (d.machine) " · leído por el teléfono" else ""}</td><td>${fmt(d.target.toDouble())}</td>" +
                "<td style='color:${color(d.real, d.target.toDouble())};font-weight:600'>${fmt(d.real)}</td>" +
                "<td>${diffLabel(d.real, d.target.toDouble())}</td><td>${if (d.machine) "—" else "${d.kwHit}/${d.kwTotal}"}</td></tr>"
        }
        val secRows = ran.joinToString("") { r ->
            val s = r.section
            val mine = prof != null && s.speaker == prof
            val miss = if (r.byMachine) "—" else r.missedLabels().joinToString(", ") { esc(it) }.ifEmpty { "—" }
            "<tr class='${if (mine) "mine" else ""}'><td>${s.slideNo}</td><td>${esc(s.speaker)}</td><td>${s.targetS} s</td>" +
                "<td style='color:${color(r.elapsed, s.targetS.toDouble())};font-weight:600'>${"%.0f".format(r.elapsed)} s</td>" +
                "<td>${diffLabel(r.elapsed, s.targetS.toDouble())}</td>" +
                "<td>${if (r.byMachine) "—" else "%.0f s".format(r.longestSilence)}</td><td>$miss</td></tr>"
        }
        val tips = tips(reh).joinToString("") { "<li>${esc(it)}</li>" }
            .ifEmpty { "<li>Nada fuera de lo normal${if (prof != null) " en tus partes" else ""}.</li>" }
        val html = """<!doctype html><html lang="es"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Informe de ensayo · ${esc(who)}</title>
<style>
body{font-family:Roboto,Arial,sans-serif;background:#f4f1ec;color:#2b2722;margin:0;padding:18px}
.wrap{max-width:980px;margin:auto}h1{margin:0 0 4px;font-size:22px}.sub{color:#6b625a;margin-bottom:16px}
.big{font-size:44px;font-weight:700} table{width:100%;border-collapse:collapse;background:#fff;margin-bottom:20px}
th,td{padding:7px 8px;text-align:left;border-bottom:1px solid #eee;font-size:14px}th{background:#3a342e;color:#fff}
tr.mine td{background:#fff6e5}h2{font-size:17px;margin:20px 0 8px}
</style></head><body><div class="wrap">
<h1>Informe de ensayo · ${esc(who)}</h1>
<div class="sub">$mode · ${SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.US).format(Date())} · meta ${fmt(reh.deck.minTotal)}–${fmt(reh.deck.maxTotal)} · ${esc(reh.deck.name)}</div>
<div class="big" style="color:$vcol">${fmt(total)}</div><div>$vtxt · meta del guion ${fmt(reh.deck.totalTarget.toDouble())}</div>
<h2>${if (prof != null) "Para ti" else "Para mejorar"}</h2><ul>$tips</ul>
<h2>Por integrante</h2>
<table><tr><th>Integrante</th><th>Meta</th><th>Real</th><th>Diferencia</th><th>Puntos clave</th></tr>$spRows</table>
<h2>Por lámina</h2>
<table><tr><th>#</th><th>Orador</th><th>Meta</th><th>Real</th><th>Dif.</th><th>Silencio máx.</th><th>Puntos que faltaron</th></tr>$secRows</table>
<p class="sub">Los puntos clave salen de lo que reconoce el micrófono, así que pueden fallar con ruido o si hablas muy rápido. Tómalos como pista, no como nota.</p>
</div></body></html>"""
        val f = File(dir, "$base.html")
        f.writeText(html)
        return f
    }

    fun tips(reh: Rehearsal): List<String> {
        val out = mutableListOf<String>()
        for (r in reh.runs) {
            if (r.start == null || r.byMachine) continue
            val s = r.section
            if (reh.profile != null && s.speaker != reh.profile) continue
            val d = r.elapsed - s.targetS
            if (d > max(4.0, 0.2 * s.targetS)) out.add("Lámina ${s.slideNo} (${s.speaker}): ${"%.0f".format(d)} s de más.")
            else if (d < -max(4.0, 0.25 * s.targetS)) out.add("Lámina ${s.slideNo} (${s.speaker}): ${"%.0f".format(-d)} s de menos.")
            if (r.longestSilence >= 4) out.add("Lámina ${s.slideNo}: silencio de ${"%.0f".format(r.longestSilence)} s.")
        }
        return out
    }
}
