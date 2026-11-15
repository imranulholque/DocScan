package app.docscan

import androidx.compose.ui.geometry.Offset
import com.chaquo.python.Python
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/** Bridge to engine.py (the tested Image Fixer code, run by Chaquopy). One Python thread, so heavy jobs never overlap. */
object Engine {
    class Detect(val sure: Boolean, val quad: List<Offset>)

    // Background priority: the heavy numpy / OpenCV work must never take the CPU away from the UI thread,
    // otherwise animations (like the crop screen's scanning line) stall while a page is being analysed.
    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(null, {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "docscanner-python", 32L * 1024 * 1024)
    }.asCoroutineDispatcher()
    // The page finder (live outline + auto crop) is plain Kotlin now (PageFinder.kt, no OpenCV, no Python): its own thread, so it never
    // waits behind a Python job (effects, saving) and the Python thread never waits behind it.
    private val finder = Executors.newSingleThreadExecutor { r ->
        Thread(null, {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LESS_FAVORABLE)
            r.run()
        }, "docscanner-finder", 16L * 1024 * 1024)
    }.asCoroutineDispatcher()
    private val mod by lazy { Python.getInstance().getModule("engine") }

    private suspend fun <T> py(block: () -> T): T = withContext(dispatcher) { block() }

    private fun size(s: String): Pair<Int, Int> {
        val p = s.split(",")
        return p[0].toInt() to p[1].toInt()
    }

    /** Loads Python + numpy + OpenCV in the background right at start, so the first scan is not slow. */
    suspend fun warmUp() { py { mod.callAttr("look", "color", 50.0) } }

    suspend fun prepare(src: String, dst: String): Pair<Int, Int> =
        py { size(mod.callAttr("prepare", src, dst).toString()) }

    /** Auto crop after a photo. With OpenCV in the build: the learned Python finder (engine.detect, about 0.1 s, trained on the person's own
     *  corrected crops). Otherwise (or on any error) the native Kotlin finder, as before; the old Python finder is only the last safety net. */
    suspend fun detect(path: String): Detect {
        val fast = try {
            py { mod.callAttr("fast_ok").toBoolean() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) { false }
        if (fast) {
            try {
                return detectPython(path)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Throwable) { }
        }
        try {
            val r = withContext(finder) { PageFinder.detect(path) }
            return Detect(r.first, r.second)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) { }
        return detectPython(path)
    }

    private suspend fun detectPython(path: String): Detect = py {
        val r = mod.callAttr("detect", path).toString()
        val parts = r.split("|")
        val v = parts[1].split(",").map { it.toFloat() }
        Detect(parts[0] == "1", List(4) { Offset(v[it * 2], v[it * 2 + 1]) })
    }

    /** One camera frame (raw RGB, upright, w x h) -> the page outline as fractions 0..1 (TL, TR, BR, BL), or null when no page is seen. */
    suspend fun live(rgb: ByteArray, w: Int, h: Int): List<Offset>? = withContext(finder) {
        // 1. the 4 straight sides of the page (Python linefinder, exact corners); 2. when that sees no page: the Kotlin colour finder
        if (linesOk) {
            try {
                val r = mod.callAttr("live_lines", rgb, w, h).toString()
                if (r.isNotEmpty()) {
                    val v = r.split(",").map { it.toFloat() }
                    if (v.size == 8) return@withContext List(4) { Offset(v[it * 2], v[it * 2 + 1]) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Throwable) { }
        }
        try { PageFinder.live(rgb, w, h) } catch (_: Exception) { null }
    }

    private val linesOk by lazy { try { mod.callAttr("fast_ok").toBoolean() } catch (_: Throwable) { false } }

    suspend fun crop(path: String, quad: List<Offset>, dst: String): Pair<Int, Int> = py {
        val q = quad.joinToString(",") { "${it.x},${it.y}" }
        size(mod.callAttr("crop", path, q, dst).toString())
    }

    /** Front + back of an ID card (both already cut out and straightened) -> ONE A4 page picture at [dst] (engine.id_card). Returns its size.
     *  sizeMm = length of the card's long side on the paper (86 = real size). */
    suspend fun idCard(front: String, back: String, dst: String, sizeMm: Int): Pair<Int, Int> =
        py { size(mod.callAttr("id_card", front, back, dst, sizeMm.toDouble()).toString()) }

    suspend fun rotate(path: String, dst: String, turns: Int) {
        py { mod.callAttr("rotate", path, dst, turns) }
    }

    /** clarity = the effect amount (for "orig": the text clarity), textClarity = the text clarity slider of the other looks. */
    suspend fun render(path: String, dst: String, mode: String, clarity: Int, maxSide: Int, textClarity: Int = 0) {
        py { mod.callAttr("render", path, dst, mode, clarity.toDouble(), maxSide, textClarity.toDouble()) }
    }

    suspend fun thumbs(path: String, prefix: String, clarity: Int) {
        py { mod.callAttr("thumbs", path, prefix, clarity.toDouble()) }
    }

    /** number = page number to print (used when numPos is not "off"), numPos = off | tl tc tr bl bc br. */
    suspend fun exportImage(
        path: String, dst: String, fmt: String, mode: String, clarity: Int,
        number: Int, numPos: String, numSize: Int, fontPath: String, textClarity: Int = 0, turns: Int = 0, sigs: String = "",
    ) {
        py { mod.callAttr("export_image", path, dst, fmt, mode, clarity.toDouble(), number, numPos, numSize.toDouble(), fontPath, textClarity.toDouble(), turns, sigs) }
    }

    /** sigs = the signatures on this page as engine.sign_page wants them ("path~cx~cy~width~rot~alpha" joined by ";"). */
    class PdfItem(val path: String, val mode: String, val clarity: Int, val textClarity: Int, val turns: Int, val sigs: String = "")

    suspend fun makePdf(pages: List<PdfItem>, dst: String, pageSize: String, numPos: String, numSize: Int) {
        val lines = pages.joinToString("\n") { "${it.path}|${it.mode}|${it.clarity}|${it.textClarity}|${it.turns}|${it.sigs}" }
        py { mod.callAttr("make_pdf", lines, dst, pageSize, numPos, numSize.toDouble()) }
    }

    // ------------------------------------------------------------ signatures (Image Fixer's background remover, run by Chaquopy)
    /** Where the writing is on a picture: a box (picture pixels) around the ink. */
    suspend fun sigGuess(path: String): List<Float> = py {
        mod.callAttr("sig_guess", path).toString().split(",").map { it.toFloat() }
    }

    /** Picture cropped to [box] (l, t, r, b) -> transparent PNG at [dst]. Returns its size. */
    suspend fun sigMake(
        src: String, dst: String, box: List<Float>, strength: Int, uneven: Boolean, tint: String, remove: Boolean, bold: Int, maxSide: Int,
        trim: Boolean = true,
    ): Pair<Int, Int> = py {
        size(mod.callAttr("sig_make", src, dst, box.joinToString(",") { it.toString() }, strength, uneven, tint, remove, bold, maxSide, trim).toString())
    }
}
