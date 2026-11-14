package app.docscan

import androidx.compose.ui.geometry.Offset
import com.chaquo.python.Python
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/** Bridge to engine.py (the tested Image Fixer code, run by Chaquopy). One Python thread, so heavy jobs never overlap. */
object Engine {
    class Detect(val sure: Boolean, val quad: List<Offset>)

    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(null, r, "docscan-python", 32L * 1024 * 1024) }
        .asCoroutineDispatcher()
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

    suspend fun exportImage(path: String, dst: String, fmt: String, mode: String, clarity: Int) {
        py { mod.callAttr("export_image", path, dst, fmt, mode, clarity.toDouble()) }
    }

    suspend fun makePdf(pages: List<Triple<String, String, Int>>, dst: String, pageSize: String) {
        val lines = pages.joinToString("\n") { "${it.first}|${it.second}|${it.third}" }
        py { mod.callAttr("make_pdf", lines, dst, pageSize) }
    }
}
