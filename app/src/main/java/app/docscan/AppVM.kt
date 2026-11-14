package app.docscan

import android.app.Application
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

enum class Screen { Home, Camera, Crop, Review, Settings }

data class Page(
    val id: String,
    val work: String,                 // upright working photo
    val w: Int, val h: Int,           // its size
    val cropped: String? = null,      // straightened page
    val quad: List<Offset>? = null,   // the 4 corners (in the working photo)
    val mode: String = "orig",        // orig (default: the picture as photographed) | color | color | vivid | gray | bw
    val clarity: Int = 60,            // 0..100, the one text-clarity slider (color / vivid / gray / bw)
    val oClarity: Int = 0,            // the same slider for "orig": 0 = picture exactly as photographed
    val prev: String? = null,         // preview of the chosen look
    val prevKey: String = "",
    val thumbs: String? = null,       // prefix of the small look previews
) {
    /** The clarity value that belongs to the chosen look. */
    val eff: Int get() = if (mode == "orig") oClarity else clarity
    /** Changes whenever the look changes: a preview is up to date when prevKey == key. */
    val look: String get() = "$mode:$eff"
}

class Saved(val place: String, val files: List<File>)

class AppVM(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication<Application>()
    private val prefs = ctx.getSharedPreferences("docscan", Context.MODE_PRIVATE)

    var lang by mutableStateOf(prefs.getString("lang", "bn") ?: "bn")
        private set
    var fmt by mutableStateOf(prefs.getString("fmt", "pdf") ?: "pdf")
        private set
    var pdfSize by mutableStateOf(prefs.getString("pdfSize", "fit") ?: "fit")
        private set
    var treeUri by mutableStateOf(prefs.getString("tree", null))
        private set
    var numPos by mutableStateOf(prefs.getString("numPos", "off") ?: "off")     // off | tl tc tr bl bc br
        private set
    var numSize by mutableIntStateOf(prefs.getInt("numSize", 12))                // points, Times New Roman
        private set
    val str: Str get() = if (lang == "bn") Bn else En

    fun chooseLang(v: String) { lang = v; prefs.edit().putString("lang", v).apply() }
    fun chooseFmt(v: String) { fmt = v; prefs.edit().putString("fmt", v).apply() }
    fun choosePdfSize(v: String) { pdfSize = v; prefs.edit().putString("pdfSize", v).apply() }
    fun setTree(v: String?) { treeUri = v; prefs.edit().putString("tree", v).apply() }
    fun chooseNumPos(v: String) { numPos = v; prefs.edit().putString("numPos", v).apply() }
    fun chooseNumSize(v: Int) { numSize = v; prefs.edit().putInt("numSize", v).apply() }

    var screen by mutableStateOf(Screen.Home)
    val pages = mutableStateListOf<Page>()
    var current by mutableIntStateOf(0)
    var cropQueue by mutableStateOf<List<String>>(emptyList())
    var cropReturn by mutableStateOf(Screen.Home)
    var busy by mutableStateOf<String?>(null)
    var saved by mutableStateOf<Saved?>(null)

    private val sessionDir = File(ctx.filesDir, "session").apply { deleteRecursively(); mkdirs() }

    // ------------------------------------------------------------ auto update (see Updater.kt)
    var autoUpdate by mutableStateOf(prefs.getBoolean("auto_update", true))
        private set
    var update by mutableStateOf<Updater.Info?>(null)              // a newer build that can be installed
    var updateAsk by mutableStateOf(false)                         // show the "Update now?" question
    var updateProgress by mutableStateOf<Pair<Long, Long>?>(null)  // null = not downloading
    var pendingApk by mutableStateOf<File?>(null)                  // downloaded, waiting for Android's installer
    var askInstallPerm by mutableStateOf(false)
    var checkingUpdate by mutableStateOf(false)
    private val askedBuilds = HashSet<Int>()
    private val cancelUpdate = AtomicBoolean(false)

    fun chooseAutoUpdate(on: Boolean) {
        autoUpdate = on
        prefs.edit().putBoolean("auto_update", on).apply()
        if (!on) { update = null; updateAsk = false }
    }

    /** manual = the person pressed "Check for updates": always answer. Automatic: ask about each build only once per run. */
    suspend fun checkUpdate(manual: Boolean) {
        if (!Updater.canSelfUpdate()) { if (manual) toast(str.updDev); return }
        if (checkingUpdate || updateProgress != null) return
        checkingUpdate = true
        try {
            val info = withContext(Dispatchers.IO) { Updater.check() }
            update = info
            if (info == null) { if (manual) toast(str.updUpToDate) }
            else if (manual || askedBuilds.add(info.build)) updateAsk = true
        } catch (e: Exception) {
            if (manual) toast(str.errPrefix + (e.message ?: ""))
        } finally {
            checkingUpdate = false
        }
    }

    fun startUpdate() {
        val info = update ?: return
        if (updateProgress != null) return
        updateAsk = false
        cancelUpdate.set(false)
        updateProgress = 0L to info.size
        viewModelScope.launch {
            try {
                pendingApk = withContext(Dispatchers.IO) {
                    Updater.download(ctx, info, { d, t -> updateProgress = d to t }, { cancelUpdate.get() })
                }
            } catch (e: Exception) {
                if (e.message != "cancelled") toast(str.errPrefix + (e.message ?: ""))
            } finally {
                updateProgress = null
            }
        }
    }

    fun cancelUpdateDownload() = cancelUpdate.set(true)

    init {
        viewModelScope.launch { try { Engine.warmUp() } catch (_: Exception) { } }
        // a few seconds after start, then every 10 minutes while the app stays open (GitHub allows 60 asks / hour)
        viewModelScope.launch {
            delay(8_000)
            while (true) {
                if (autoUpdate && Updater.canSelfUpdate()) checkUpdate(false)
                delay(10 * 60 * 1000L)
            }
        }
    }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
    fun page(id: String): Page? = pages.firstOrNull { it.id == id }
    private fun upd(id: String, f: (Page) -> Page) {
        val i = pages.indexOfFirst { it.id == id }
        if (i >= 0) pages[i] = f(pages[i])
    }
    private fun del(path: String?) { if (path != null) File(path).delete() }
    private fun newId() = UUID.randomUUID().toString().take(8)
    private fun file(id: String, tag: String) = File(sessionDir, "$id-$tag-${System.nanoTime()}.jpg").absolutePath

    // ------------------------------------------------------------ adding pictures
    /** A photo file (camera or gallery copy) becomes a page that still has to be cropped.
     *  Done natively (no Python): the picture is ready in a fraction of a second, so the crop screen opens at once. */
    suspend fun addPhoto(src: File): String? {
        val id = newId()
        val work = File(sessionDir, "$id-work.jpg")
        return try {
            val (w, h) = try {
                prepareNative(src, work)
            } catch (e: Exception) {
                Engine.prepare(src.absolutePath, work.absolutePath)      // rare formats: the Python way
            }
            pages.add(Page(id, work.absolutePath, w, h))
            id
        } catch (e: Exception) {
            toast(str.errPrefix + (e.message ?: ""))
            null
        } finally {
            src.delete()
        }
    }

    var importing by mutableIntStateOf(0)          // pictures from the gallery that are still being prepared

    /** The first picture opens the crop screen at once, the others are prepared while the person crops it. */
    suspend fun importUris(uris: List<Uri>) {
        importing += uris.size
        var first = true
        try {
            for (u in uris) {
                val tmp = File(ctx.cacheDir, "imp_${System.nanoTime()}.jpg")
                try {
                    withContext(Dispatchers.IO) {
                        ctx.contentResolver.openInputStream(u)!!.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                    }
                } catch (e: Exception) {
                    toast(str.errPrefix + (e.message ?: "")); importing--; continue
                }
                val id = addPhoto(tmp)
                importing--
                if (id == null) continue
                if (first) {
                    first = false
                    current = pages.indexOfFirst { it.id == id }
                    queueCrop(listOf(id), Screen.Review)
                } else {
                    cropQueue = cropQueue + id
                }
            }
        } finally {
            importing = 0
            finishCropIfDone()
        }
    }

    // ------------------------------------------------------------ cropping
    fun queueCrop(ids: List<String>, back: Screen) { cropQueue = ids; cropReturn = back; screen = Screen.Crop }

    fun recrop(id: String) = queueCrop(listOf(id), Screen.Review)

    suspend fun applyCrop(id: String, quad: List<Offset>) {
        val p = page(id) ?: return
        val dst = file(id, "crop")
        Engine.crop(p.work, quad, dst)
        del(p.cropped); del(p.prev)
        upd(id) { it.copy(cropped = dst, quad = quad, prev = null, prevKey = "", thumbs = null) }
    }

    /** The page being cropped is done (or discarded): go on with the queue. */
    fun nextCrop(discard: Boolean = false) {
        val id = cropQueue.firstOrNull()
        if (discard && id != null) {
            val p = page(id)
            if (p != null && p.cropped == null) { del(p.work); pages.removeAll { it.id == id } }
        }
        cropQueue = cropQueue.drop(1)
        finishCropIfDone()
    }

    /** Queue empty and nothing more is being prepared: leave the crop screen. */
    fun finishCropIfDone() {
        if (screen != Screen.Crop || cropQueue.isNotEmpty() || importing > 0) return
        val back = cropReturn
        screen = if (back == Screen.Review && pages.none { it.cropped != null }) Screen.Home else back
        if (screen == Screen.Review) current = current.coerceIn(0, max(0, pages.size - 1))
    }

    // ------------------------------------------------------------ page editing
    /** Picks the look and / or moves the clarity slider (the slider value belongs to the look that is chosen). */
    fun setLook(id: String, mode: String? = null, clarity: Int? = null) = upd(id) { p ->
        val m = mode ?: p.mode
        val q = p.copy(mode = m)
        when {
            clarity == null -> q
            m == "orig" -> q.copy(oClarity = clarity)
            else -> q.copy(clarity = clarity)
        }
    }

    fun applyLookToAll(from: Page) {
        for (p in pages.toList()) upd(p.id) { it.copy(mode = from.mode, clarity = from.clarity, oClarity = from.oClarity) }
    }

    suspend fun ensurePreview(id: String) {
        val p = page(id) ?: return
        val c = p.cropped ?: return
        val key = p.look
        if (p.prev != null && p.prevKey == key) return
        val dst = file(id, "prev")
        try {
            Engine.render(c, dst, p.mode, p.eff, 1400)
        } catch (e: CancellationException) {
            File(dst).delete(); throw e
        } catch (e: Exception) {
            toast(str.errPrefix + (e.message ?: "")); return
        }
        val now = page(id)
        if (now == null) { File(dst).delete(); return }
        del(now.prev)
        upd(id) { it.copy(prev = dst, prevKey = key) }
    }

    suspend fun ensureThumbs(id: String) {
        val p = page(id) ?: return
        val c = p.cropped ?: return
        if (p.thumbs != null) return
        val prefix = File(sessionDir, "$id-th${System.nanoTime()}").absolutePath
        try { Engine.thumbs(c, prefix, 60) } catch (e: CancellationException) { throw e } catch (e: Exception) { return }
        upd(id) { it.copy(thumbs = prefix) }
    }

    suspend fun rotate(id: String) {
        val p = page(id) ?: return
        val c = p.cropped ?: return
        busy = str.working
        try {
            val dst = file(id, "crop")
            Engine.rotate(c, dst, 1)
            del(c); del(p.prev)
            upd(id) { it.copy(cropped = dst, prev = null, prevKey = "", thumbs = null) }
        } catch (e: Exception) {
            toast(str.errPrefix + (e.message ?: ""))
        } finally { busy = null }
    }

    fun delete(index: Int) {
        val p = pages.getOrNull(index) ?: return
        del(p.work); del(p.cropped); del(p.prev)
        pages.removeAt(index)
        current = current.coerceIn(0, max(0, pages.size - 1))
        if (pages.isEmpty()) screen = Screen.Home
    }

    fun move(index: Int, by: Int) {
        val to = index + by
        if (index !in pages.indices || to !in pages.indices) return
        val p = pages.removeAt(index)
        pages.add(to, p)
        current = to
    }

    fun clearAll() {
        pages.clear(); cropQueue = emptyList(); current = 0; saved = null
        sessionDir.deleteRecursively(); sessionDir.mkdirs()
        screen = Screen.Home
    }

    // ------------------------------------------------------------ export (save / share)
    /** Makes the final files (full quality) in the cache: one PDF with all pages, or one picture per page. */
    suspend fun export(format: String, size: String): List<File> {
        val out = File(ctx.cacheDir, "out").apply { deleteRecursively(); mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val list = pages.filter { it.cropped != null }
        if (format == "pdf") {
            busy = str.processing
            val f = File(out, "DocScan_$ts.pdf")
            Engine.makePdf(list.map { Triple(it.cropped!!, it.mode, it.eff) }, f.absolutePath, size, numPos, numSize)
            return listOf(f)
        }
        val ext = if (format == "png") "png" else "jpg"
        val files = ArrayList<File>()
        list.forEachIndexed { i, p ->
            busy = str.exporting(i + 1, list.size)
            val f = File(out, if (list.size == 1) "DocScan_$ts.$ext" else "DocScan_${ts}_${i + 1}.$ext")
            Engine.exportImage(p.cropped!!, f.absolutePath, format, p.mode, p.eff, i + 1, numPos, numSize, Fonts.path(ctx))
            files.add(f)
        }
        return files
    }

    fun folderName(): String? {
        val t = treeUri ?: return null
        return try {
            androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, Uri.parse(t))?.name
        } catch (_: Exception) { null }
    }
}

/** Decodes a picture file, no bigger than about maxSide pixels on the long side. */
fun decodeBitmap(path: String, maxSide: Int): ImageBitmap? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, o)
    var sample = 1
    while (max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeFile(path, o2)?.asImageBitmap()
}

/** Camera / gallery photo -> upright working JPEG (EXIF turned, long side <= maxSide). Native and fast. Returns (w, h). */
suspend fun prepareNative(src: File, dst: File, maxSide: Int = 2600): Pair<Int, Int> = withContext(Dispatchers.IO) {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(src.absolutePath, o)
    if (o.outWidth <= 0 || o.outHeight <= 0) throw IllegalStateException("unreadable picture")
    var sample = 1
    while (max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val bmp = BitmapFactory.decodeFile(src.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: throw IllegalStateException("unreadable picture")
    val deg = try {
        when (android.media.ExifInterface(src.absolutePath).getAttributeInt(
            android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL,
        )) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    } catch (_: Exception) { 0f }
    val k = maxSide.toFloat() / max(bmp.width, bmp.height)
    val m = android.graphics.Matrix()
    if (k < 1f) m.postScale(k, k)
    if (deg != 0f) m.postRotate(deg)
    val out = if (m.isIdentity) bmp else android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    dst.outputStream().buffered().use { out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it) }
    val size = out.width to out.height
    if (out !== bmp) bmp.recycle()
    out.recycle()
    size
}
