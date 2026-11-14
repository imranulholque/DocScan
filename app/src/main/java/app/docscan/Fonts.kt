package app.docscan

import android.content.Context
import android.graphics.Typeface
import java.io.File

/**
 * Page numbers look like Times New Roman. Android has no Times New Roman, so the app carries Liberation Serif
 * (same letter shapes and exactly the same widths, free licence: SIL OFL) in assets/fonts.
 * PDF page numbers use the standard PDF font Times-Roman (real text), pictures use this file.
 */
object Fonts {
    private const val ASSET = "fonts/LiberationSerif-Regular.ttf"
    private var tf: Typeface? = null

    fun typeface(ctx: Context): Typeface = tf ?: (try {
        Typeface.createFromAsset(ctx.assets, ASSET)
    } catch (_: Exception) {
        Typeface.SERIF
    }).also { tf = it }

    /** A real file path of the font for Python (Pillow). Copied from assets once. */
    fun path(ctx: Context): String {
        val f = File(ctx.filesDir, "fonts/LiberationSerif-Regular.ttf")
        if (!f.exists() || f.length() == 0L) {
            f.parentFile?.mkdirs()
            try {
                ctx.assets.open(ASSET).use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            } catch (_: Exception) { }
        }
        return f.absolutePath
    }
}
