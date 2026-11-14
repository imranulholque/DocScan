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
    val mode: String = "color",       // orig | color | gray | bw
    val clarity: Int = 60,            // 0..100, the one text-clarity slider
    val prev: String? = null,         // preview of the chosen look
    val prevKey: String = "",
    val thumbs: String? = null,       // prefix of the 4 small look previews
)

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
    val str: Str get() = if (lang == "bn") Bn else En

    fun setLang(v: String) { lang = v; prefs.edit().putString("lang", v).apply() }
    fun setFmt(v: String) { fmt = v; prefs.edit().putString("fmt", v).apply() }
    fun setPdfSize(v: String) { pdfSize = v; prefs.edit().putString("pdfSize", v).apply() }
    fun setTree(v: String?) { treeUri = v; prefs.edit().putString("tree", v).apply() }

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

    fun setAutoUpdate(on: Boolean) {
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
    /** A photo file (camera or gallery copy) becomes a page that still has to be cropped. */
    suspend fun addPhoto(src: File): String? {
        val id = newId()
        val work = File(sessionDir, "$id-work.jpg")
        return try {
            val (w, h) = Engine.prepare(src.absolutePath, work.absolutePath)
            pages.add(Page(id, work.absolutePath, w, h))
            id
        } catch (e: Exception) {
            toast(str.errPrefix + (e.message ?: ""))
            null
        } finally {
            src.delete()
        }
    }

    suspend fun importUris(uris: List<Uri>) {
        busy = str.working
        try {
            val ids = ArrayList<String>()
            for (u in uris) {
                val tmp = File(ctx.cacheDir, "imp_${System.nanoTime()}.jpg")
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(u)!!.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                }
                addPhoto(tmp)?.let { ids.add(it) }
            }
            if (ids.isNotEmpty()) {
                current = pages.indexOfFirst { it.id == ids[0] }
                queueCrop(ids, Screen.Review)
            }
        } catch (e: Exception) {
            toast(str.errPrefix + (e.message ?: ""))
        } finally {
            busy = null
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
        if (cropQueue.isEmpty()) {
            val back = cropReturn
            screen = if (back == Screen.Review && pages.none { it.cropped != null }) Screen.Home else back
            if (screen == Screen.Review) current = current.coerceIn(0, max(0, pages.size - 1))
        }
    }

    // ------------------------------------------------------------ page editing
    fun setLook(id: String, mode: String? = null, clarity: Int? = null) =
        upd(id) { it.copy(mode = mode ?: it.mode, clarity = clarity ?: it.clarity) }

    fun applyLookToAll(from: Page) { for (p in pages.toList()) upd(p.id) { it.copy(mode = from.mode, clarity = from.clarity) } }

    suspend fun ensurePreview(id: String) {
        val p = page(id) ?: return
        val c = p.cropped ?: return
        val key = "${p.mode}:${p.clarity}"
        if (p.prev != null && p.prevKey == key) return
        val dst = file(id, "prev")
        try {
            Engine.render(c, dst, p.mode, p.clarity, 1400)
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
            Engine.makePdf(list.map { Triple(it.cropped!!, it.mode, it.clarity) }, f.absolutePath, size)
            return listOf(f)
        }
        val ext = if (format == "png") "png" else "jpg"
        val files = ArrayList<File>()
        list.forEachIndexed { i, p ->
            busy = str.exporting(i + 1, list.size)
            val f = File(out, if (list.size == 1) "DocScan_$ts.$ext" else "DocScan_${ts}_${i + 1}.$ext")
            Engine.exportImage(p.cropped!!, f.absolutePath, format, p.mode, p.clarity)
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
