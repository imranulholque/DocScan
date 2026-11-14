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

    suspend fun detect(path: String): Detect = py {
        val r = mod.callAttr("detect", path).toString()
        val parts = r.split("|")
        val v = parts[1].split(",").map { it.toFloat() }
        Detect(parts[0] == "1", List(4) { Offset(v[it * 2], v[it * 2 + 1]) })
    }

    /** One camera frame (raw RGB, upright, w x h) -> the page outline as fractions 0..1 (TL, TR, BR, BL), or null when no page is seen. */
    suspend fun live(rgb: ByteArray, w: Int, h: Int): List<Offset>? = py {
        val r = mod.callAttr("live", rgb, w, h).toString()
        if (r.isEmpty()) null else {
            val v = r.split(",").map { it.toFloat() }
            List(4) { Offset(v[it * 2], v[it * 2 + 1]) }
        }
    }

    suspend fun crop(path: String, quad: List<Offset>, dst: String): Pair<Int, Int> = py {
        val q = quad.joinToString(",") { "${it.x},${it.y}" }
        size(mod.callAttr("crop", path, q, dst).toString())
    }

    suspend fun rotate(path: String, dst: String, turns: Int) {
        py { mod.callAttr("rotate", path, dst, turns) }
    }

    suspend fun render(path: String, dst: String, mode: String, clarity: Int, maxSide: Int) {
        py { mod.callAttr("render", path, dst, mode, clarity.toDouble(), maxSide) }
    }

    suspend fun thumbs(path: String, prefix: String, clarity: Int) {
        py { mod.callAttr("thumbs", path, prefix, clarity.toDouble()) }
    }

    /** number = page number to print (used when numPos is not "off"), numPos = off | tl tc tr bl bc br. */
    suspend fun exportImage(
        path: String, dst: String, fmt: String, mode: String, clarity: Int,
        number: Int, numPos: String, numSize: Int, fontPath: String,
    ) {
        py { mod.callAttr("export_image", path, dst, fmt, mode, clarity.toDouble(), number, numPos, numSize.toDouble(), fontPath) }
    }

    suspend fun makePdf(pages: List<Triple<String, String, Int>>, dst: String, pageSize: String, numPos: String, numSize: Int) {
        val lines = pages.joinToString("\n") { "${it.first}|${it.second}|${it.third}" }
        py { mod.callAttr("make_pdf", lines, dst, pageSize, numPos, numSize.toDouble()) }
    }
}
