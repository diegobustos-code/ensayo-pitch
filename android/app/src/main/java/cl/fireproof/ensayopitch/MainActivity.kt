package cl.fireproof.ensayopitch

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.text.Html
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val INK = 0xFF2B2722.toInt()
private const val MUTED = 0xFF776D63.toInt()
private const val WOOD = 0xFFB5652B.toInt()
private const val WOOD_LIGHT = 0xFFFFF3E4.toInt()
private const val DARK = 0xFF3A342E.toInt()
private const val PAPER = 0xFFF4F1EC.toInt()
private const val LINE = 0xFFE4DDD3.toInt()
private const val GREEN = 0xFF2F7D4F.toInt()
private const val YELLOW = 0xFFC29A2B.toInt()
private const val RED = 0xFFB23A2A.toInt()
private val PALETTE = intArrayOf(0xFFB5652B.toInt(), 0xFF3F6E8C.toInt(), 0xFF5B8A3C.toInt(), 0xFF8C4F7A.toInt(),
    0xFFC29A2B.toInt(), 0xFF4F5D6B.toInt(), 0xFFA14A3B.toInt(), 0xFF2F7D73.toInt())
private const val TEAM_PROFILE = "__equipo__"
private const val PICK_PPTX = 41
private const val PICK_GUION = 42
private const val SAVE_TEMPLATE = 43
private const val APP_VERSION = "2.0"

class LevelMeter(ctx: Context) : View(ctx) {
    private var db = -90.0
    private var voiced = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    fun set(d: Double, v: Boolean) { db = d; voiced = v; invalidate() }
    override fun onDraw(c: Canvas) {
        val r = height / 2f
        p.color = 0xFFEBE5DC.toInt()
        c.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), r, r, p)
        val frac = ((db + 70) / 60).coerceIn(0.0, 1.0)
        p.color = if (voiced) GREEN else 0xFFB9AE9F.toInt()
        c.drawRoundRect(RectF(0f, 0f, (width * frac).toFloat(), height.toFloat()), r, r, p)
    }
}

class Bar(ctx: Context) : View(ctx) {
    var frac = 0.0; set(v) { field = v; invalidate() }
    var color = GREEN; set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val r = height / 2f
        p.color = 0xFFEBE5DC.toInt()
        c.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), r, r, p)
        p.color = color
        c.drawRoundRect(RectF(0f, 0f, (width * frac.coerceIn(0.0, 1.0)).toFloat(), height.toFloat()), r, r, p)
    }
}

class MainActivity : Activity(), AudioListener {

    private lateinit var store: Store
    private lateinit var audio: AudioEngine
    private lateinit var root: FrameLayout
    private val main = Handler(Looper.getMainLooper())
    private lateinit var deck: Deck
    private val colors = HashMap<String, Int>()
    private val bitmaps = HashMap<String, Bitmap?>()

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var voices: List<Voice> = emptyList()
    private var utter = 0

    private var sttOk = false
    private var sttMsg = "Preparando el reconocimiento de voz… (la primera vez tarda unos segundos)"
    private var screen = "home"

    // estado de inicio
    private var profile: String? = null
    private var mode = TEAM
    private var autoAdvance = true
    private var showScript = true
    private var voiceIdx = 0
    private var speed = 0

    // vistas vivas
    private var meter: LevelMeter? = null
    private var sttLabel: TextView? = null
    private var voiceSpinner: Spinner? = null

    // ensayo
    private var reh: Rehearsal? = null
    private var countdown = 0
    private var shownIdx = -1
    private var machineDeadline: Double? = null
    private var partial = ""
    private val heard = StringBuilder()
    private lateinit var sv: SessionViews

    private class SessionViews(
        val badge: TextView, val who: TextView, val slideLbl: TextView, val image: ImageView, val titleCard: TextView,
        val clock: TextView, val bar: Bar, val total: TextView, val voice: TextView, val kw: TextView,
        val heard: TextView, val next: TextView, val script: TextView,
    )

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            main.postDelayed(this, 100)
        }
    }

    // ---------------------------------------------------------------- ciclo de vida
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        root = FrameLayout(this)
        root.setBackgroundColor(PAPER)
        setContentView(root)
        profile = store.getString("profile")?.let { if (it == TEAM_PROFILE) null else it }
        mode = store.getString("mode", TEAM) ?: TEAM
        autoAdvance = store.getBool("auto", true)
        showScript = store.getBool("show_script", true)
        voiceIdx = store.getInt("voice", 0)
        speed = store.getInt("speed", 0)
        if (store.getString("deck") == "custom") { store.put("has_pptx", true); store.put("pptx_name", store.getString("deck_name", "presentación")); store.put("deck", null) }
        loadMaterial(quiet = true)

        audio = AudioEngine(this, this)
        audio.loadModel()
        tts = TextToSpeech(this) { st -> onTtsInit(st) }
        showHome()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) audio.start()
        else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) audio.start()
            else {
                sttMsg = "Sin permiso de micrófono no puedo escucharte. Actívalo en Ajustes → Apps → Ensayo Pitch → Permisos."
                sttLabel?.text = sttMsg
            }
        }
    }

    override fun onDestroy() {
        main.removeCallbacks(ticker)
        audio.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- utilidades de vista
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float) = v * resources.displayMetrics.density

    private fun rounded(color: Int, radius: Float = 12f, stroke: Int? = null, strokeW: Int = 1) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dpf(radius)
        if (stroke != null) setStroke(dp(strokeW), stroke)
    }

    private fun tv(text: CharSequence = "", size: Float = 15f, bold: Boolean = false, color: Int = INK): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setLineSpacing(0f, 1.12f)
        }

    private fun html(s: String) = Html.fromHtml(s, Html.FROM_HTML_MODE_COMPACT)

    private fun vbox(pad: Int = 0) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
    }

    private fun hbox() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
                   top: Int = 0, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(top) }

    private fun card(): LinearLayout = vbox(14).apply { background = rounded(Color.WHITE, 14f, LINE) }

    private fun button(text: String, primary: Boolean = false, onClick: () -> Unit): TextView = tv(text, if (primary) 17f else 14f, primary,
        if (primary) Color.WHITE else INK).apply {
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(if (primary) 14 else 10), dp(14), dp(if (primary) 14 else 10))
        val bg = if (primary) rounded(WOOD, 12f) else rounded(Color.WHITE, 10f, 0xFFD6CDC1.toInt())
        background = RippleDrawable(ColorStateList.valueOf(0x33000000), bg, null)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun colorFor(name: String): Int {
        if (name == TEAM_PROFILE) return DARK
        return colors.getOrPut(name) { PALETTE[colors.size % PALETTE.size] }
    }

    private fun initials(name: String): String {
        if (name == TEAM_PROFILE) return "★"
        val parts = name.split(" ").filter { it.isNotEmpty() && it[0].isUpperCase() }
        return parts.take(2).joinToString("") { it.take(1) }.ifEmpty { name.take(2) }
    }

    private fun avatar(name: String, size: Int = 38): TextView = tv(initials(name), 14f, true, Color.WHITE).apply {
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(colorFor(name)) }
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    private fun now() = SystemClock.elapsedRealtime() / 1000.0

    private fun bitmap(path: String?): Bitmap? {
        if (path == null) return null
        return bitmaps.getOrPut(path) {
            try { assets.open(path).use { BitmapFactory.decodeStream(it) } } catch (_: Exception) { null }
        }
    }

    // ---------------------------------------------------------------- presentación
    private val pptxFile get() = File(filesDir, "custom.pptx")
    private val guionFile get() = File(filesDir, "custom_guion.bin")

    /** Carga lo que corresponda: guion (+ presentación), presentación sola o el pitch del equipo. */
    private fun loadMaterial(quiet: Boolean = false) {
        val hasP = store.getBool("has_pptx", false) && pptxFile.exists()
        val hasG = store.getBool("has_guion", false) && guionFile.exists()
        try {
            deck = when {
                hasG -> DeckParser.loadWithGuion(GuionParser.parse(guionFile.readBytes()),
                    store.getString("guion_name", "guion") ?: "guion", if (hasP) pptxFile.readBytes() else null)
                hasP -> DeckParser.load(pptxFile.readBytes(), store.getString("pptx_name", "presentación") ?: "presentación", false)
                else -> loadTeamDeck()
            }
        } catch (e: Exception) {
            if (!quiet) Toast.makeText(this, "No pude leer ${if (hasG) "el guion" else "la presentación"}: ${e.message}", Toast.LENGTH_LONG).show()
            store.put("has_pptx", false)
            store.put("has_guion", false)
            deck = loadTeamDeck()
        }
        colors.clear()
        deck.speakers.forEach { colorFor(it) }
        if (profile != null && profile !in deck.speakers) profile = null
    }

    private fun loadTeamDeck(): Deck {
        val bytes = assets.open("default.pptx").use { it.readBytes() }
        val slides = (assets.list("slides") ?: emptyArray()).toSet()
        return DeckParser.load(bytes, "Pitch Fire Proof", true, { n ->
            val name = "s-%02d.jpg".format(n)
            if (name in slides) "slides/$name" else null
        }, Curated.KEYWORDS)
    }

    private fun displayName(uri: android.net.Uri): String {
        try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0).substringBeforeLast('.')
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "archivo"
    }

    private var pendingTemplate = ""

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            when (requestCode) {
                PICK_PPTX -> {
                    val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    val name = displayName(uri)
                    val hasG = store.getBool("has_guion", false) && guionFile.exists()
                    fun usePptx(keepGuion: Boolean) {
                        if (!keepGuion) DeckParser.load(bytes, name, false) // revisa que traiga notas
                        pptxFile.writeBytes(bytes)
                        store.put("has_pptx", true)
                        store.put("pptx_name", name)
                        store.put("has_guion", keepGuion)
                        loadMaterial()
                        Toast.makeText(this, "Presentación cargada. En el teléfono se muestra el título de cada lámina en vez de la imagen.", Toast.LENGTH_LONG).show()
                        showHome()
                    }
                    if (hasG) {
                        AlertDialog.Builder(this)
                            .setTitle("¿Usar el guion que ya cargaste?")
                            .setMessage("Guion: ${store.getString("guion_name", "")}\n\nSí: el guion pone el texto y los tiempos, y la presentación los títulos.\nNo: se leen las notas del orador de la presentación.")
                            .setPositiveButton("Sí") { _, _ -> usePptx(true) }
                            .setNegativeButton("No") { _, _ ->
                                try { usePptx(false) } catch (e: Exception) {
                                    Toast.makeText(this, "No pude leer esa presentación: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                            .show()
                    } else usePptx(false)
                    return
                }
                PICK_GUION -> {
                    val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    val entries = GuionParser.parse(bytes)
                    guionFile.writeBytes(bytes)
                    store.put("has_guion", true)
                    store.put("guion_name", displayName(uri))
                    loadMaterial()
                    val people = entries.map { it.speaker }.distinct().size
                    Toast.makeText(this, "Guion cargado: ${entries.size} partes, $people personas, meta ${fmt(deck.totalTarget.toDouble())}.", Toast.LENGTH_LONG).show()
                }
                SAVE_TEMPLATE -> {
                    val bytes = assets.open("plantillas/$pendingTemplate").use { it.readBytes() }
                    contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
                    Toast.makeText(this, "Plantilla guardada. Ábrela con Word, Google Docs o un editor de texto, rellénala y cárgala con «Guion».", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "No pude leer ese archivo: ${e.message}", Toast.LENGTH_LONG).show()
        }
        showHome()
    }

    private fun openDoc(code: Int, mimes: Array<String>) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, mimes)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, code)
    }

    private fun pickDeck() = openDoc(PICK_PPTX, arrayOf(
        "application/vnd.openxmlformats-officedocument.presentationml.presentation", "application/octet-stream"))

    private fun pickGuion() = openDoc(PICK_GUION, arrayOf(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "text/plain", "application/octet-stream"))

    private fun saveTemplate() {
        val opts = arrayOf("Plantilla de Word (.docx)", "Plantilla de texto (.txt)", "Ejemplo relleno con el pitch del equipo (.docx)")
        AlertDialog.Builder(this)
            .setTitle("Plantilla del guion")
            .setItems(opts) { _, which ->
                val (asset, title, mime) = when (which) {
                    1 -> Triple("Plantilla_Guion.txt", "Guion_del_pitch.txt", "text/plain")
                    2 -> Triple("Guion_ejemplo_FireProof.docx", "Guion_ejemplo_FireProof.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                    else -> Triple("Plantilla_Guion.docx", "Guion_del_pitch.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                }
                pendingTemplate = asset
                val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = mime
                    putExtra(Intent.EXTRA_TITLE, title)
                }
                @Suppress("DEPRECATION")
                startActivityForResult(i, SAVE_TEMPLATE)
            }
            .show()
    }

    // ---------------------------------------------------------------- voz del teléfono
    private fun onTtsInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) return
        ttsReady = true
        val pref = listOf(Locale("es", "CL"), Locale("es", "US"), Locale("es", "419"), Locale("es", "MX"), Locale("es", "ES"), Locale("es"))
        for (l in pref) {
            val r = t.setLanguage(l)
            if (r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED) break
        }
        voices = try {
            (t.voices ?: emptySet()).filter { it.locale.language == "es" && !it.isNetworkConnectionRequired && !it.features.contains("notInstalled") }
                .sortedWith(compareBy({ if (it.locale.country == "CL" || it.locale.country == "US" || it.locale.country == "MX") 0 else 1 }, { it.name }))
        } catch (_: Exception) { emptyList() }
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { runOnUiThread { onTtsDone() } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { runOnUiThread { onTtsDone() } }
        })
        runOnUiThread { fillVoices() }
    }

    private fun voiceNames(): List<String> =
        if (voices.isEmpty()) listOf(if (ttsReady) "Voz por defecto del teléfono" else "Cargando voces…")
        else voices.mapIndexed { i, v -> "Voz ${i + 1} · ${v.locale.displayCountry.ifEmpty { v.locale.toLanguageTag() }}" }

    private fun fillVoices() {
        val sp = voiceSpinner ?: return
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, voiceNames())
        sp.setSelection(min(voiceIdx, max(0, voiceNames().size - 1)))
    }

    private fun speak(text: String, speakerIdx: Int): Boolean {
        val t = tts ?: return false
        if (!ttsReady) return false
        if (voices.isNotEmpty()) {
            try { t.setVoice(voices[(voiceIdx + speakerIdx) % voices.size]) } catch (_: Exception) {}
        }
        val pitch = floatArrayOf(0f, -0.25f, 0.2f, -0.1f, 0.3f, -0.35f, 0.1f)[speakerIdx % 7]
        t.setPitch(1f + pitch * 0.6f)
        t.setSpeechRate(1f + speed * 0.12f)
        utter++
        return t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u$utter") == TextToSpeech.SUCCESS
    }

    private fun stopSpeaking() {
        try { tts?.stop() } catch (_: Exception) {}
    }

    private fun onTtsDone() {
        val r = reh ?: return
        val cur = r.current ?: return
        if (cur.byMachine && !r.finished) main.postDelayed({ next("voz-pc") }, 250)
    }

    private fun speedText(v: Int) = mapOf(-5 to "Muy lenta", -4 to "Bastante lenta", -3 to "Lenta", -2 to "Un poco lenta",
        -1 to "Algo más lenta de lo normal", 0 to "Velocidad normal", 1 to "Un poco más rápida de lo normal",
        2 to "Algo rápida", 3 to "Rápida", 4 to "Bastante rápida", 5 to "Muy rápida")[v] ?: ""

    // ---------------------------------------------------------------- audio
    override fun onLevel(db: Double, voiced: Boolean) {
        meter?.set(db, voiced)
        val r = reh ?: return
        if (r.started && !r.finished) r.onAudio(now(), 0.1, voiced)
    }

    override fun onPartial(text: String) = onText(text, false)
    override fun onFinal(text: String) = onText(text, true)

    override fun onModel(ok: Boolean, message: String) {
        sttOk = ok
        sttMsg = message
        sttLabel?.text = message
    }

    override fun onAudioError(message: String) {
        sttMsg = "⚠ $message"
        sttLabel?.text = sttMsg
    }

    private fun onText(text: String, isFinal: Boolean) {
        val r = reh ?: return
        if (!r.started || r.finished || r.current?.byMachine == true) return
        r.onText(text, isFinal)
        if (isFinal) {
            heard.append(text).append('\n')
            partial = ""
        } else partial = text
        if (screen == "session") {
            sv.heard.text = html(heardHtml())
            updateKeywords()
        }
    }

    private fun heardHtml(): String {
        val lines = heard.toString().trimEnd().lines().takeLast(6).joinToString("<br>") {
            if (it.startsWith("—")) "<font color='#9a9087'>$it</font>" else it
        }
        return lines + if (partial.isNotEmpty()) "<br><font color='#9a9087'><i>$partial</i></font>" else ""
    }

    // ---------------------------------------------------------------- INICIO
    private fun showHome() {
        screen = "home"
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root.removeAllViews()
        val outer = vbox()
        val scroll = ScrollView(this)
        val col = vbox(16)
        scroll.addView(col)
        outer.addView(scroll, lp(h = 0, weight = 1f))

        val head = hbox()
        head.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) }, LinearLayout.LayoutParams(dp(52), dp(52)))
        val ht = vbox().apply { setPadding(dp(12), 0, 0, 0) }
        ht.addView(tv(html("Ensayo del pitch <small><font color='#b5652b'>v$APP_VERSION</font></small>"), 24f, true))
        ht.addView(tv("${deck.name} · ${deck.sections.size} partes · meta ${fmt(deck.totalTarget.toDouble())} (rango ${fmt(deck.minTotal)}–${fmt(deck.maxTotal)})", 13f, color = MUTED))
        head.addView(ht)
        col.addView(head)

        val r1 = hbox()
        r1.addView(button("Presentación…") { pickDeck() }, lp(0, weight = 1f))
        r1.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        r1.addView(button("Guion…") { pickGuion() }, lp(0, weight = 1f))
        col.addView(r1, lp(top = 12))
        val r2 = hbox()
        r2.addView(button("Plantilla del guion…") { saveTemplate() }, lp(0, weight = 1f))
        r2.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        r2.addView(button("Pitch del equipo") {
            store.put("has_pptx", false); store.put("has_guion", false); loadMaterial(); showHome()
        }, lp(0, weight = 1f))
        col.addView(r2, lp(top = 8))

        // perfiles
        val pc = card()
        pc.addView(tv("¿Quién eres?", 17f, true))
        pc.addView(tv("Elige tu perfil. Tus partes quedan marcadas y el informe se enfoca en ti.", 13f, color = MUTED))
        val names = deck.speakers + TEAM_PROFILE
        var row: LinearLayout? = null
        names.forEachIndexed { i, n ->
            if (i % 2 == 0) {
                row = hbox()
                pc.addView(row, lp(top = 8))
            }
            val sel = (profile ?: TEAM_PROFILE) == n
            val item = hbox().apply {
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = RippleDrawable(ColorStateList.valueOf(0x22000000),
                    if (sel) rounded(WOOD_LIGHT, 10f, WOOD, 2) else rounded(Color.WHITE, 10f, LINE), null)
                isClickable = true
                setOnClickListener {
                    profile = if (n == TEAM_PROFILE) null else n
                    if (profile == null) mode = TEAM
                    showHome()
                }
            }
            item.addView(avatar(n, 34))
            val t = vbox().apply { setPadding(dp(8), 0, 0, 0) }
            t.addView(tv(if (n == TEAM_PROFILE) "Todo el equipo" else n, 14f, true).apply { maxLines = 1 })
            t.addView(tv(if (n == TEAM_PROFILE) "sin perfil" else "${deck.turnLabel(n)} · ${fmt(deck.targetFor(n).toDouble())}",
                12f, color = MUTED))
            item.addView(t)
            row!!.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i % 2 == 0) rightMargin = dp(4) else leftMargin = dp(4)
            })
        }
        if (names.size % 2 == 1) row!!.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(4) })
        col.addView(pc, lp(top = 14))

        // modo
        val mc = card()
        mc.addView(tv("¿Cómo quieres ensayar?", 17f, true))
        fun modeItem(m: String, title: String, desc: String, enabled: Boolean): View {
            val sel = mode == m
            return vbox(12).apply {
                background = RippleDrawable(ColorStateList.valueOf(0x22000000),
                    if (sel) rounded(WOOD_LIGHT, 12f, WOOD, 2) else rounded(Color.WHITE, 12f, LINE), null)
                alpha = if (enabled) 1f else 0.45f
                addView(tv(title, 15f, true))
                addView(tv(desc, 13f, color = MUTED))
                isClickable = true
                setOnClickListener {
                    if (!enabled) Toast.makeText(this@MainActivity, "Primero elige tu perfil.", Toast.LENGTH_SHORT).show()
                    else { mode = m; showHome() }
                }
            }
        }
        mc.addView(modeItem(TEAM, "Ensayo del equipo", "Todos hablan frente al teléfono. El reloj corre sin parar y mide cuánto se demora cada uno.", true), lp(top = 8))
        mc.addView(modeItem(SOLO, "Ensayo individual", "Tú dices tus partes y el teléfono lee las de tus compañeros, así practicas los cambios de turno.", profile != null), lp(top = 8))
        col.addView(mc, lp(top = 14))

        // opciones
        val oc = card()
        val mr = hbox()
        mr.addView(tv("Micrófono", 14f, true))
        meter = LevelMeter(this)
        mr.addView(meter, LinearLayout.LayoutParams(0, dp(12), 1f).apply { leftMargin = dp(12) })
        oc.addView(mr)
        sttLabel = tv(sttMsg, 12.5f, color = MUTED)
        oc.addView(sttLabel, lp(top = 6))
        fun sw(text: String, on: Boolean, f: (Boolean) -> Unit) = Switch(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(INK)
            isChecked = on
            setOnCheckedChangeListener { _, c -> f(c) }
        }
        oc.addView(sw("Pasar solo a la siguiente parte cuando se dice la frase final", autoAdvance) { autoAdvance = it }, lp(top = 10))
        oc.addView(sw("Mostrar mi guion en pantalla mientras hablo", showScript) { showScript = it }, lp(top = 8))
        oc.addView(tv("Voz del teléfono (ensayo individual)", 14f, true), lp(top = 14))
        voiceSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, voiceNames())
            setSelection(min(voiceIdx, max(0, voiceNames().size - 1)))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { voiceIdx = pos }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        oc.addView(voiceSpinner)
        val speedLbl = tv(speedText(speed), 13f, true, if (speed == 0) GREEN else WOOD).apply { gravity = Gravity.CENTER }
        oc.addView(speedLbl, lp(top = 8))
        val srow = hbox()
        srow.addView(tv("Lenta", 12f, color = MUTED))
        srow.addView(SeekBar(this).apply {
            max = 10
            progress = speed + 5
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    speed = p - 5
                    speedLbl.text = speedText(speed)
                    speedLbl.setTextColor(if (speed == 0) GREEN else WOOD)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    if (ttsReady && voices.isNotEmpty() || ttsReady) {
                        voiceIdx = voiceSpinner?.selectedItemPosition ?: voiceIdx
                        speak("Así suena esta velocidad.", 0)
                    }
                }
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        srow.addView(tv("Rápida", 12f, color = MUTED))
        oc.addView(srow)
        col.addView(oc, lp(top = 14))

        // info
        val ic = card()
        val p = profile
        if (p == null) {
            ic.addView(tv("Orden del pitch", 17f, true))
            val sb = StringBuilder()
            for (s in deck.sections) {
                val c = String.format("#%06X", 0xFFFFFF and colorFor(s.speaker))
                sb.append("<b>${s.slideNo}</b> · <font color='$c'><b>${s.speaker}</b></font> · ${s.targetS} s<br>")
            }
            ic.addView(tv(html(sb.toString()), 14f), lp(top = 6))
        } else {
            ic.addView(tv("Hola, ${p.split(" ")[0]}. Tus turnos", 17f, true))
            val sb = StringBuilder()
            deck.turnsOf(p).forEachIndexed { n, turn ->
                val first = turn.first()
                val last = turn.last()
                val prev = deck.sections.getOrNull(first.index - 1)?.speaker
                val cue = if (prev != null) "entras después de <b>$prev</b>" else "abres el pitch"
                val slides = if (turn.size == 1) "Lámina ${first.slideNo}"
                    else "Láminas " + turn.dropLast(1).joinToString(", ") { it.slideNo.toString() } + " y ${last.slideNo}"
                sb.append("<b>Turno ${n + 1}</b> · $slides · ${fmt(turn.sumOf { it.targetS }.toDouble())} · $cue<br>")
                for (x in turn) {
                    val pre = if (turn.size > 1) "Lámina ${x.slideNo} (${x.targetS} s): " else ""
                    sb.append("<font color='#776d63'>${pre}no olvides ${x.keywords.joinToString(", ") { it.label }}</font><br>")
                    if (x !== last) sb.append("<font color='#776d63'>&nbsp;&nbsp;→ al terminar, pasa a la lámina ${deck.sections[x.index + 1].slideNo}</font><br>")
                }
                sb.append("<font color='#776d63'>Frase de salida: «…${last.tail.joinToString(" ")}»</font><br><br>")
            }
            val hist = store.historyFor(p).takeLast(5).reversed()
            if (hist.isNotEmpty()) {
                sb.append("<b>Tus últimos ensayos</b><br>")
                for (h in hist) {
                    val sp = h.optJSONArray("speakers")
                    var mine = 0.0; var target = 0.0
                    if (sp != null) for (i in 0 until sp.length()) {
                        val o = sp.getJSONObject(i)
                        if (o.optString("speaker") == p) { mine = o.optDouble("real"); target = o.optDouble("target") }
                    }
                    sb.append("${h.optString("date")} · ${if (h.optString("mode") == SOLO) "individual" else "equipo"} · total ${fmt(h.optDouble("total"))} · tú ${fmt(mine)} de ${fmt(target)}<br>")
                }
            }
            ic.addView(tv(html(sb.toString()), 13.5f), lp(top = 6))
        }
        col.addView(ic, lp(top = 14))

        val bottom = vbox().apply { setPadding(dp(16), dp(10), dp(16), dp(14)); setBackgroundColor(PAPER) }
        bottom.addView(button("Empezar ensayo", primary = true) { startRehearsal() })
        outer.addView(bottom)
        root.addView(outer)
    }

    // ---------------------------------------------------------------- ENSAYO
    private fun startRehearsal() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7)
            return
        }
        store.put("profile", profile ?: TEAM_PROFILE)
        store.put("mode", mode)
        store.put("auto", autoAdvance)
        store.put("show_script", showScript)
        store.put("voice", voiceIdx)
        store.put("speed", speed)
        if (mode == SOLO && profile == null) mode = TEAM
        reh = Rehearsal(deck, mode, profile, autoAdvance)
        heard.setLength(0)
        partial = ""
        shownIdx = -1
        buildSession()
        countdown = 3
        showCountdown()
        main.postDelayed({ count() }, 1000)
    }

    private fun buildSession() {
        screen = "session"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root.removeAllViews()
        meter = null; sttLabel = null; voiceSpinner = null
        val outer = vbox()
        val scroll = ScrollView(this)
        val col = vbox(14)
        scroll.addView(col)
        outer.addView(scroll, lp(h = 0, weight = 1f))

        val badge = tv("", 13f, true, Color.WHITE).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(6), dp(8), dp(6)) }
        col.addView(badge)
        val who = tv("", 26f, true)
        col.addView(who, lp(top = 6))
        val slideLbl = tv("", 13f, color = MUTED)
        col.addView(slideLbl)
        val frame = FrameLayout(this).apply { background = rounded(DARK, 10f); setPadding(dp(6), dp(6), dp(6), dp(6)) }
        val image = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER }
        val titleCard = tv("", 20f, true, PAPER).apply { gravity = Gravity.CENTER; setPadding(dp(16), dp(30), dp(16), dp(30)) }
        frame.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        frame.addView(titleCard, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(frame, lp(top = 10))
        val clock = tv("0:00", 54f, true).apply { typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD) }
        col.addView(clock, lp(top = 6))
        val bar = Bar(this)
        col.addView(bar, lp(h = dp(10)))
        val total = tv("", 13f, color = MUTED)
        col.addView(total, lp(top = 6))
        val voice = tv("", 13f, color = MUTED)
        col.addView(voice)
        val next = tv("", 15f)
        col.addView(next, lp(top = 8))
        val script = tv("", 17f).apply { background = rounded(0xFFFBFAF8.toInt(), 10f, LINE); setPadding(dp(12), dp(10), dp(12), dp(10)) }
        col.addView(script, lp(top = 10))
        col.addView(tv("Puntos clave", 16f, true), lp(top = 12))
        val kw = tv("", 14.5f)
        col.addView(kw, lp(top = 4))
        col.addView(tv("Lo que escucha el teléfono", 16f, true), lp(top = 12))
        val heardTv = tv("", 13.5f).apply { background = rounded(0xFFFBFAF8.toInt(), 10f, LINE); setPadding(dp(10), dp(8), dp(10), dp(8)); minHeight = dp(70) }
        col.addView(heardTv, lp(top = 4))

        val bar2 = hbox().apply { setPadding(dp(12), dp(8), dp(12), dp(12)); setBackgroundColor(PAPER) }
        bar2.addView(button("← Repetir") { back() }, lp(0, weight = 1f))
        bar2.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        bar2.addView(button("Siguiente →", primary = true) { next("tecla") }, lp(0, weight = 1.4f))
        bar2.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        bar2.addView(button("Terminar") { askFinish() }, lp(0, weight = 1f))
        outer.addView(bar2)
        root.addView(outer)
        sv = SessionViews(badge, who, slideLbl, image, titleCard, clock, bar, total, voice, kw, heardTv, next, script)
    }

    private fun showCountdown() {
        val s0 = deck.sections[0]
        showSlide(s0)
        sv.badge.text = ""
        sv.badge.background = null
        sv.who.text = "Empieza en $countdown…"
        sv.who.setTextColor(INK)
        sv.slideLbl.text = "Abre ${s0.speaker}"
        sv.clock.text = "0:00"
        sv.bar.frac = 0.0
    }

    private fun count() {
        val r = reh ?: return
        if (screen != "session") return
        countdown--
        if (countdown > 0) {
            showCountdown()
            main.postDelayed({ count() }, 1000)
            return
        }
        r.start(now())
        main.removeCallbacks(ticker)
        main.post(ticker)
        enterSection()
    }

    private fun showSlide(s: Section) {
        val bm = bitmap(s.image)
        if (bm != null) {
            sv.image.setImageBitmap(bm)
            sv.image.visibility = View.VISIBLE
            sv.titleCard.visibility = View.GONE
        } else {
            sv.image.visibility = View.GONE
            sv.titleCard.visibility = View.VISIBLE
            sv.titleCard.text = s.title.ifEmpty { "Lámina ${s.slideNo}" }
        }
    }

    private fun enterSection() {
        val r = reh ?: return
        val run = r.current ?: return
        val s = run.section
        shownIdx = r.idx
        partial = ""
        audio.resetRecognizer()
        heard.append("— ${s.speaker}, lámina ${s.slideNo} —\n")
        sv.heard.text = html(heardHtml())
        showSlide(s)
        val mine = r.isMine(run)
        val color = colorFor(s.speaker)
        sv.who.text = s.speaker
        sv.who.setTextColor(color)
        sv.slideLbl.text = "Lámina ${s.slideNo} · parte ${s.index + 1} de ${r.runs.size} · meta ${s.targetS} s"
        when {
            run.byMachine -> {
                sv.badge.text = "EL TELÉFONO LEE ESTA PARTE · escucha y prepárate"
                sv.badge.setTextColor(INK)
                sv.badge.background = rounded(0xFFEBE5DC.toInt(), 6f)
            }
            mine -> {
                sv.badge.text = "TE TOCA"
                sv.badge.setTextColor(Color.WHITE)
                sv.badge.background = rounded(WOOD, 6f)
            }
            else -> {
                sv.badge.text = "HABLA ${s.speaker.uppercase()}"
                sv.badge.setTextColor(Color.WHITE)
                sv.badge.background = rounded(color, 6f)
            }
        }
        val show = run.byMachine || !mine || showScript || r.profile == null
        sv.script.text = if (show) s.text else "(Guion oculto: dilo de memoria. Puedes activarlo en el inicio.)"
        val nx = r.nextRun
        sv.next.text = if (nx == null) html("Última parte del pitch.") else if (nx.section.speaker == s.speaker) {
            html("Sigue hablando <b>${if (mine) "tú" else s.speaker}</b>: pasa a la lámina ${nx.section.slideNo}")
        } else {
            val extra = if (r.isMine(nx) && !mine) " · <font color='#b5652b'><b>¡te toca a ti!</b></font>" else ""
            html("Sigue: <b>${nx.section.speaker}</b> (lámina ${nx.section.slideNo})$extra")
        }
        machineDeadline = null
        if (run.byMachine) {
            audio.muted = true
            val idx = deck.speakers.indexOf(s.speaker)
            if (!speak(s.text, max(0, idx))) machineDeadline = now() + s.targetS
        } else {
            audio.muted = false
        }
        updateKeywords()
    }

    private fun updateKeywords() {
        val run = reh?.current ?: return
        sv.kw.text = when {
            run.byMachine -> html("<font color='#776d63'>(parte leída por el teléfono)</font>")
            !sttOk -> html("<font color='#776d63'>Sin reconocimiento de voz: solo se mide el tiempo.</font>")
            else -> {
                val items = run.section.keywords.mapIndexed { i, k ->
                    if (i in run.hits) "<font color='#2f7d4f'>✔ ${k.label}</font>" else "<font color='#9a9087'>○ ${k.label}</font>"
                }
                html(items.joinToString("<br>") + if (run.tailHit) "<br><font color='#2f7d4f'>✔ frase final</font>" else "")
            }
        }
    }

    private fun tick() {
        val r = reh ?: return
        if (screen != "session" || r.finished) return
        val t = now()
        var run = r.current ?: return
        val dl = machineDeadline
        if (run.byMachine && dl != null && t >= dl) {
            next("voz-pc"); return
        }
        if (run.byMachine) run.end = t
        val reason = r.poll(t)
        if (r.finished) { endRehearsal(); return }
        if (reason != null || shownIdx != r.idx) {
            stopSpeaking()
            enterSection()
            run = r.current ?: return
        }
        val el = t - (run.start ?: t)
        val tgt = run.section.targetS.toDouble()
        val frac = if (tgt > 0) el / tgt else 0.0
        val col = if (frac < 0.85) GREEN else if (frac <= 1.1) YELLOW else RED
        sv.clock.text = html("<font color='${String.format("#%06X", 0xFFFFFF and col)}'>${fmt(el)}</font> <small><font color='#776d63'>/ ${fmt(tgt)}</font></small>")
        sv.bar.frac = min(1.0, frac)
        sv.bar.color = col
        sv.total.text = "Total: ${fmt(r.totalElapsed(t))} de ${fmt(deck.totalTarget.toDouble())} (rango ${fmt(deck.minTotal)}–${fmt(deck.maxTotal)})"
        sv.voice.text = if (run.byMachine) "Micrófono en pausa mientras habla el teléfono"
        else "Con voz ${"%.0f".format(run.voiced)} s" + if (run.silenceNow >= 1.5 && run.spokeOnce) " · silencio ${"%.0f".format(run.silenceNow)} s" else ""
    }

    private fun next(how: String) {
        val r = reh ?: return
        if (!r.started || r.finished) return
        stopSpeaking()
        if (r.advance(now(), how)) enterSection() else endRehearsal()
    }

    private fun back() {
        val r = reh ?: return
        if (!r.started || r.finished) return
        stopSpeaking()
        r.back(now())
        enterSection()
    }

    private fun askFinish() {
        val r = reh ?: return
        if (!r.started) { cancelToHome(); return }
        AlertDialog.Builder(this)
            .setTitle("¿Terminar el ensayo?")
            .setMessage("Se guarda el resultado hasta donde llegaste.")
            .setPositiveButton("Terminar") { _, _ ->
                stopSpeaking()
                r.finish(now())
                endRehearsal()
            }
            .setNegativeButton("Seguir", null)
            .show()
    }

    private fun cancelToHome() {
        main.removeCallbacks(ticker)
        stopSpeaking()
        audio.muted = false
        reh = null
        showHome()
    }

    private fun endRehearsal() {
        main.removeCallbacks(ticker)
        audio.muted = false
        val r = reh ?: return
        val prev = store.historyFor(r.profile).lastOrNull()
        val file = try { Report.write(r, store.reportsDir()) } catch (_: Exception) { null }
        val summary = Report.summary(r).put("report", file?.absolutePath ?: "")
        store.addHistory(summary)
        showReport(r, file, prev?.optDouble("total"))
    }

    // ---------------------------------------------------------------- RESULTADO
    private fun showReport(r: Rehearsal, file: File?, prevTotal: Double?) {
        screen = "report"
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root.removeAllViews()
        val outer = vbox()
        val scroll = ScrollView(this)
        val col = vbox(16)
        scroll.addView(col)
        outer.addView(scroll, lp(h = 0, weight = 1f))
        val prof = r.profile
        col.addView(tv("Resultado · ${prof ?: "Todo el equipo"}", 22f, true))
        val total = r.totalElapsed()
        val (vtxt, vcol) = when (r.verdict()) {
            "ok" -> "Dentro del tiempo" to GREEN
            "corto" -> "Quedó corto" to YELLOW
            else -> "Se pasaron del tiempo" to RED
        }
        col.addView(tv(fmt(total), 56f, true, vcol), lp(top = 4))
        col.addView(tv("$vtxt (meta ${fmt(r.deck.minTotal)}–${fmt(r.deck.maxTotal)})" + (prevTotal?.let { " · la vez anterior: ${fmt(it)}" } ?: ""), 15f))
        val mine = r.perSpeaker().firstOrNull { it.speaker == prof }
        if (mine != null) {
            col.addView(tv(html("Tus partes: <b>${fmt(mine.real)}</b> de ${fmt(mine.target.toDouble())} (${diffLabel(mine.real, mine.target.toDouble())}) · puntos clave ${mine.kwHit}/${mine.kwTotal}"), 14f), lp(top = 8))
        }
        val tips = Report.tips(r)
        col.addView(tv(html("<b>Para revisar:</b> " + if (tips.isEmpty()) "nada fuera de lo normal${if (prof != null) " en tus partes" else ""}." else tips.take(6).joinToString(" · ")), 14f), lp(top = 8))

        col.addView(tv("Por integrante", 17f, true), lp(top = 16))
        col.addView(table(listOf("Integrante", "Meta", "Real", "Dif.", "Puntos"),
            r.perSpeaker().map { d ->
                listOf(d.speaker + (if (d.speaker == prof) " (tú)" else "") + (if (d.machine) " · tel." else ""),
                    fmt(d.target.toDouble()), fmt(d.real), diffLabel(d.real, d.target.toDouble()),
                    if (d.machine) "—" else "${d.kwHit}/${d.kwTotal}") to (d.speaker == prof)
            }), lp(top = 6))
        col.addView(tv("Por lámina", 17f, true), lp(top = 16))
        col.addView(table(listOf("#", "Orador", "Meta", "Real", "Faltó decir"),
            r.runs.filter { it.start != null }.map { run ->
                listOf(run.section.slideNo.toString(), run.section.speaker, "${run.section.targetS} s", "%.0f s".format(run.elapsed),
                    if (run.byMachine) "—" else run.missedLabels().joinToString(", ").ifEmpty { "nada" }) to (run.section.speaker == prof)
            }), lp(top = 6))
        col.addView(tv("Los puntos clave salen de lo que entiende el micrófono: tómalos como pista. Lo más confiable es el tiempo.", 12.5f, color = MUTED), lp(top = 12))

        val bottom = vbox().apply { setPadding(dp(16), dp(8), dp(16), dp(14)); setBackgroundColor(PAPER) }
        val row = hbox()
        row.addView(button("Compartir informe") { file?.let { share(it) } }, lp(0, weight = 1f))
        row.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        row.addView(button("Inicio") { reh = null; showHome() }, lp(0, weight = 1f))
        bottom.addView(row)
        bottom.addView(button("Ensayar de nuevo", primary = true) { startRehearsal() }, lp(top = 8))
        outer.addView(bottom)
        root.addView(outer)
    }

    private fun table(head: List<String>, rows: List<Pair<List<String>, Boolean>>): View {
        val t = TableLayout(this).apply {
            background = rounded(Color.WHITE, 10f, LINE)
            isStretchAllColumns = false
            setColumnShrinkable(head.size - 1, true)
            setColumnShrinkable(0, true)
        }
        fun cell(s: String, bold: Boolean, fg: Int, bg: Int) = tv(s, 13f, bold, fg).apply {
            setPadding(dp(8), dp(7), dp(8), dp(7))
            setBackgroundColor(bg)
        }
        val hr = TableRow(this)
        head.forEach { hr.addView(cell(it, true, Color.WHITE, DARK)) }
        t.addView(hr)
        for ((vals, mine) in rows) {
            val tr = TableRow(this)
            vals.forEach { tr.addView(cell(it, false, INK, if (mine) WOOD_LIGHT else Color.WHITE)) }
            t.addView(tr)
        }
        return t
    }

    private fun share(f: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.archivos", f)
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "text/html"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, f.nameWithoutExtension.replace('_', ' '))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(i, "Compartir informe"))
        } catch (e: Exception) {
            Toast.makeText(this, "No pude compartir el informe: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------------------------------------------------------- teclas y botón atrás
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (screen == "session") {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_DPAD_DOWN -> { next("tecla"); return true }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_UP -> { back(); return true }
                KeyEvent.KEYCODE_ESCAPE -> { askFinish(); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (screen) {
            "session" -> askFinish()
            "report" -> { reh = null; showHome() }
            else -> super.onBackPressed()
        }
    }
}
