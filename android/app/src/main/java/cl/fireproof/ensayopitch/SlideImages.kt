package cl.fireproof.ensayopitch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.min
import kotlin.math.roundToInt

/** Imágenes de las láminas en el teléfono: PDF tal cual, o una vista simple armada desde el .pptx. */
object SlideImages {
    private const val P = "http://schemas.openxmlformats.org/presentationml/2006/main"
    private const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val REL = "http://schemas.openxmlformats.org/package/2006/relationships"

    fun isPdf(b: ByteArray) = b.size > 5 && String(b, 0, 5, Charsets.ISO_8859_1) == "%PDF-"

    private fun save(bm: Bitmap, f: File) {
        FileOutputStream(f).use { bm.compress(Bitmap.CompressFormat.JPEG, 88, it) }
    }

    /** Una imagen por página del PDF. Se guardan en caché según el contenido del archivo. */
    fun fromPdf(ctx: Context, file: File, hash: String): Map<Int, String> {
        val dir = File(ctx.filesDir, "laminas/pdf-$hash")
        val cached = dir.listFiles()?.filter { it.name.matches(Regex("p-\\d+\\.jpg")) }?.sortedBy { it.name }
        if (!cached.isNullOrEmpty()) return cached.mapIndexed { i, f -> (i + 1) to f.absolutePath }.toMap()
        dir.mkdirs()
        val out = HashMap<Int, String>()
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                for (i in 0 until r.pageCount) {
                    r.openPage(i).use { page ->
                        val w = 1280
                        val h = if (page.width > 0) (w.toDouble() * page.height / page.width).roundToInt() else 720
                        val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bm.eraseColor(Color.WHITE)
                        page.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val f = File(dir, "p-%03d.jpg".format(i + 1))
                        save(bm, f)
                        bm.recycle()
                        out[i + 1] = f.absolutePath
                    }
                }
            }
        }
        return out
    }

    // ---------------------------------------------------------------- vista simple desde el .pptx
    class Content(val title: String, val lines: List<String>, val picture: ByteArray?)

    private fun xml(b: ByteArray): Element {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        return f.newDocumentBuilder().parse(ByteArrayInputStream(b)).documentElement
    }

    private fun resolve(dir: String, target: String): String {
        if (target.startsWith("/")) return target.drop(1)
        val parts = (if (dir.isEmpty()) emptyList() else dir.split("/")).toMutableList()
        for (p in target.split("/")) when (p) {
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
            ".", "" -> {}
            else -> parts.add(p)
        }
        return parts.joinToString("/")
    }

    private fun rels(files: Map<String, ByteArray>, path: String): Map<String, String> {
        val dir = path.substringBeforeLast('/', "")
        val relPath = (if (dir.isEmpty()) "" else "$dir/") + "_rels/" + path.substringAfterLast('/') + ".rels"
        val b = files[relPath] ?: return emptyMap()
        val nodes = xml(b).getElementsByTagNameNS(REL, "Relationship")
        val out = HashMap<String, String>()
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as Element
            if (el.getAttribute("TargetMode") == "External") continue
            out[el.getAttribute("Id")] = resolve(dir, el.getAttribute("Target"))
        }
        return out
    }

    fun contents(bytes: ByteArray): Map<Int, Content> {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (!e.isDirectory && e.name.startsWith("ppt/")) files[e.name] = z.readBytes()
                e = z.nextEntry
            }
        }
        val presRels = rels(files, "ppt/presentation.xml")
        val pres = xml(files["ppt/presentation.xml"] ?: return emptyMap())
        val ids = pres.getElementsByTagNameNS(P, "sldId")
        val out = HashMap<Int, Content>()
        for (i in 0 until ids.length) {
            val path = presRels[(ids.item(i) as Element).getAttributeNS(R, "id")] ?: continue
            val root = xml(files[path] ?: continue)
            val srels = rels(files, path)
            var title = ""
            val lines = mutableListOf<String>()
            val sps = root.getElementsByTagNameNS(P, "sp")
            for (k in 0 until sps.length) {
                val sp = sps.item(k) as Element
                val ph = sp.getElementsByTagNameNS(P, "ph")
                val type = if (ph.length > 0) (ph.item(0) as Element).getAttribute("type") else ""
                val ps = sp.getElementsByTagNameNS(A, "p")
                for (j in 0 until ps.length) {
                    val ts = (ps.item(j) as Element).getElementsByTagNameNS(A, "t")
                    val sb = StringBuilder()
                    for (t in 0 until ts.length) sb.append(ts.item(t).textContent)
                    val line = sb.toString().trim()
                    if (line.isEmpty()) continue
                    if ((type == "title" || type == "ctrTitle") && title.isEmpty()) title = line
                    else if (line !in lines) lines.add(line)
                }
            }
            if (title.isEmpty() && lines.isNotEmpty()) title = lines.maxByOrNull { it.length }!!.also { lines.remove(it) }
            var best = 0L
            var pic: ByteArray? = null
            val pics = root.getElementsByTagNameNS(P, "pic")
            for (k in 0 until pics.length) {
                val pe = pics.item(k) as Element
                val blip = pe.getElementsByTagNameNS(A, "blip").item(0) as? Element ?: continue
                val target = srels[blip.getAttributeNS(R, "embed")] ?: continue
                val ext = pe.getElementsByTagNameNS(A, "ext").item(0) as? Element
                val area = (ext?.getAttribute("cx")?.toLongOrNull() ?: 1L) * (ext?.getAttribute("cy")?.toLongOrNull() ?: 1L)
                val low = target.lowercase()
                if (!(low.endsWith(".png") || low.endsWith(".jpg") || low.endsWith(".jpeg") || low.endsWith(".gif") || low.endsWith(".webp") || low.endsWith(".bmp"))) continue
                if (area > best) { best = area; pic = files[target] }
            }
            out[i + 1] = Content(title, lines, pic)
        }
        return out
    }

    /** Arma una imagen por lámina con su título, su texto y su foto principal. */
    fun simple(ctx: Context, bytes: ByteArray, hash: String): Map<Int, String> {
        val dir = File(ctx.filesDir, "laminas/simple-$hash")
        val cached = dir.listFiles()?.filter { it.name.matches(Regex("c-\\d+\\.jpg")) }
        if (!cached.isNullOrEmpty()) return cached.associate { it.name.substring(2, 5).toInt() to it.absolutePath }
        dir.mkdirs()
        val out = HashMap<Int, String>()
        val W = 1280
        val H = 720
        for ((n, c) in contents(bytes)) {
            val bm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            val cv = Canvas(bm)
            cv.drawColor(Color.WHITE)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = 0xFFB5652B.toInt()
            cv.drawRect(0f, 0f, W.toFloat(), 10f, p)
            var textW = W - 120
            c.picture?.let { b ->
                val opts = BitmapFactory.Options().apply { inSampleSize = 1 }
                BitmapFactory.decodeByteArray(b, 0, b.size, opts)?.let { img ->
                    val box = RectF(W * 0.52f, 140f, W * 0.96f, H - 50f)
                    val sc = min(box.width() / img.width, box.height() / img.height)
                    val dw = img.width * sc
                    val dh = img.height * sc
                    val l = box.left + (box.width() - dw) / 2
                    val t = box.top + (box.height() - dh) / 2
                    cv.drawBitmap(img, Rect(0, 0, img.width, img.height), RectF(l, t, l + dw, t + dh), Paint(Paint.FILTER_BITMAP_FLAG))
                    img.recycle()
                    textW = (W * 0.52f).toInt() - 90
                }
            }
            fun block(text: String, size: Float, bold: Boolean, color: Int, x: Float, y: Float, w: Int, maxH: Int): Float {
                val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = size; this.color = color
                    typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
                }
                val sl = StaticLayout.Builder.obtain(text, 0, text.length, tp, w).setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1.15f).setMaxLines(maxOf(1, (maxH / (size * 1.3f)).toInt()))
                    .setEllipsize(android.text.TextUtils.TruncateAt.END).build()
                cv.save(); cv.translate(x, y); sl.draw(cv); cv.restore()
                return sl.height.toFloat()
            }
            val th = block(c.title.ifEmpty { "Lámina $n" }, 44f, true, 0xFF2B2722.toInt(), 60f, 40f, W - 120, 110)
            block(c.lines.take(9).joinToString("\n") { "• $it" }, 26f, false, 0xFF4A433C.toInt(), 60f, maxOf(150f, 40f + th + 30f), textW, H - 220)
            block("Lámina $n · vista simple", 18f, false, 0xFF9A9087.toInt(), W - 300f, H - 44f, 240, 30)
            val f = File(dir, "c-%03d.jpg".format(n))
            save(bm, f)
            bm.recycle()
            out[n] = f.absolutePath
        }
        return out
    }
}
