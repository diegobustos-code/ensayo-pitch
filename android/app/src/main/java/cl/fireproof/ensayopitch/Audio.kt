package cl.fireproof.ensayopitch

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** Detector de voz por energía con piso de ruido que se adapta a la sala. */
class Vad {
    private var floor = -60.0
    private var hang = 0.0
    private var calibrated = 0

    fun feed(db: Double, dt: Double): Boolean {
        if (calibrated < 10) {
            floor = if (calibrated == 0) db else 0.8 * floor + 0.2 * db
            calibrated++
            return false
        }
        val loud = db > max(floor + 10.0, -55.0)
        floor = if (!loud) 0.97 * floor + 0.03 * db else 0.999 * floor + 0.001 * db
        if (loud) {
            hang = 0.3
            return true
        }
        if (hang > 0) {
            hang -= dt
            return true
        }
        return false
    }
}

interface AudioListener {
    fun onLevel(db: Double, voiced: Boolean)
    fun onPartial(text: String)
    fun onFinal(text: String)
    fun onModel(ok: Boolean, message: String)
    fun onAudioError(message: String)
}

/** Micrófono + reconocimiento de voz offline (Vosk), en un hilo aparte. */
class AudioEngine(private val ctx: Context, private val listener: AudioListener) {
    companion object {
        const val RATE = 16000
        const val BLOCK = 1600 // 0,1 s
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile var muted = false
    @Volatile private var running = false
    @Volatile private var resetRec = false
    private var thread: Thread? = null
    private var model: Model? = null

    fun loadModel() {
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
        } catch (_: Throwable) {
        }
        StorageService.unpack(ctx, "model-es", "model",
            { m: Model ->
                model = m
                listener.onModel(true, "Reconocimiento de voz offline listo: revisa tus puntos clave y la frase final.")
            },
            { e: java.io.IOException ->
                listener.onModel(false, "No pude cargar el modelo de voz (${e.message}). Se mide el tiempo, pero no las palabras.")
            })
    }

    fun resetRecognizer() {
        resetRec = true
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        thread = Thread {
            val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, max(minBuf, BLOCK * 4))
            } catch (e: Exception) {
                main.post { listener.onAudioError("No pude abrir el micrófono: ${e.message}") }
                running = false
                return@Thread
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                main.post { listener.onAudioError("No pude abrir el micrófono. Revisa el permiso en Ajustes.") }
                running = false
                return@Thread
            }
            val buf = ShortArray(BLOCK)
            val vad = Vad()
            var recognizer: Recognizer? = null
            var recModel: Model? = null
            rec.startRecording()
            try {
                while (running) {
                    var n = 0
                    while (n < BLOCK && running) {
                        val r = rec.read(buf, n, BLOCK - n)
                        if (r <= 0) break
                        n += r
                    }
                    if (n <= 0) continue
                    var acc = 0.0
                    for (i in 0 until n) acc += buf[i].toDouble() * buf[i]
                    val rms = sqrt(acc / n) / 32768.0
                    val db = if (rms > 1e-6) 20 * log10(rms) else -100.0
                    val dt = n.toDouble() / RATE
                    if (muted) {
                        main.post { listener.onLevel(db, false) }
                        continue
                    }
                    val voiced = vad.feed(db, dt)
                    main.post { listener.onLevel(db, voiced) }
                    val m = model ?: continue
                    if (recognizer == null || recModel !== m || resetRec) {
                        resetRec = false
                        try { recognizer?.close() } catch (_: Throwable) {}
                        recognizer = Recognizer(m, RATE.toFloat())
                        recModel = m
                    }
                    val rcg = recognizer ?: continue
                    if (rcg.acceptWaveForm(buf, n)) {
                        val t = JSONObject(rcg.result).optString("text", "")
                        if (t.isNotEmpty()) main.post { listener.onFinal(t) }
                    } else {
                        val t = JSONObject(rcg.partialResult).optString("partial", "")
                        if (t.isNotEmpty()) main.post { listener.onPartial(t) }
                    }
                }
            } catch (e: Exception) {
                main.post { listener.onAudioError("Se cortó el micrófono: ${e.message}") }
            } finally {
                try { rec.stop() } catch (_: Throwable) {}
                rec.release()
                try { recognizer?.close() } catch (_: Throwable) {}
                running = false
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        thread?.join(1500)
        thread = null
    }
}
