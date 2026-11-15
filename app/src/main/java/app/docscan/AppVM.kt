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

enum class Screen { Home, Camera, Crop, Review, Settings, Sign, Trim }

data class Page(
    val id: String,
    val work: String,                 // upright working photo
    val w: Int, val h: Int,           // its size
    val cropped: String? = null,      // straightened page
    val quad: List<Offset>? = null,   // the 4 corners (in the working photo)
    val mode: String = "orig",        // orig (default: the picture as photographed) | color | vivid | gray | bw
    val clarity: Int = 60,            // 0..100, the one text-clarity slider (color / vivid / gray / bw)
    val oClarity: Int = 0,            // the TEXT CLARITY slider (every look; for "orig" it is the only slider): 0 = no change
    val rot: Int = 0,                 // clockwise quarter turns done on the review screen (instant: the files are turned only when saving)
    val prev: String? = null,         // preview of the chosen look
    val prevKey: String = "",
    val prevSide: Int = 0,            // long side of that preview (a quick small one first, the big one when the person pauses)
    val thumbs: String? = null,       // prefix of the small look previews
    val cw: Int = 0, val ch: Int = 0, // size of the straightened page (0 = not known yet)
    val sigs: List<SigPlace> = emptyList(),   // signatures placed on this page
) {
    /** The slider value that belongs to the chosen look (orig: the text clarity, others: the effect amount). */
    val eff: Int get() = if (mode == "orig") oClarity else clarity
    /** Changes whenever the look changes: a preview is up to date when prevKey == key. */
    val look: String get() = if (mode == "orig") "orig:$oClarity" else "$mode:$clarity:$oClarity"
    /** Original with no clarity needs no preview file at all: the straightened page itself is shown (nothing to load twice). */
    val needsPrev: Boolean get() = !(mode == "orig" && oClarity <= 0)
}

class Saved(val place: String, val files: List<File>)

/** A signature of the collection: a transparent PNG kept in the app's own folder (stays after the app is closed). */
class Sig(val id: String, val path: String, val w: Int, val h: Int)

/** A signature placed on a page. cx, cy = its centre and w = its width, all as fractions of the page as it is shown (0..1); rot = clockwise degrees. */
data class SigPlace(val id: String, val sigId: String, val cx: Float, val cy: Float, val w: Float, val rot: Float, val alpha: Float = 1f)

/** The photo a new signature is made from (already upright and not too big). */
class SigSrc(val path: String, val w: Int, val h: Int)

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
    var autoCrop by mutableStateOf(prefs.getBoolean("autoCrop", true))   // find the page by itself after a photo
        private set
    val str: Str get() = if (lang == "bn") Bn else En

    fun chooseLang(v: String) { lang = v; prefs.edit().putString("lang", v).apply() }
    fun chooseAutoCrop(on: Boolean) { autoCrop = on; prefs.edit().putBoolean("autoCrop", on).apply() }
    fun chooseFmt(v: String) { fmt = v; prefs.edit().putString("fmt", v).apply() }
    fun choosePdfSize(v: String) { checkpoint("size"); pdfSize = v; prefs.edit().putString("pdfSize", v).apply() }
    fun setTree(v: String?) { treeUri = v; prefs.edit().putString("tree", v).apply() }
    fun chooseNumPos(v: String) { checkpoint("numpos"); numPos = v; prefs.edit().putString("numPos", v).apply() }
    fun chooseNumSize(v: Int) { checkpoint("numsize"); numSize = v; prefs.edit().putInt("numSize", v).apply() }

    // ------------------------------------------------------------ undo (looks, page number, page size) and reset all
    private class Snap(val looks: Map<String, Triple<String, Int, Int>>, val sigs: Map<String, List<SigPlace>>, val numPos: String, val numSize: Int, val pdfSize: String)
    private val undoStack = ArrayList<Snap>()
    private var lastKey = ""
    private var lastAt = 0L
    var undoCount by mutableIntStateOf(0)
        private set

    /** Remembers the state before an edit. Many small edits of the same kind in a row (a slider drag) are ONE undo step. */
    private fun checkpoint(key: String?) {
        val now = System.currentTimeMillis()
        if (key != null && key == lastKey && now - lastAt < 700) { lastAt = now; return }
        lastKey = key ?: ""; lastAt = now
        undoStack.add(Snap(pages.associate { it.id to Triple(it.mode, it.clarity, it.oClarity) }, pages.associate { it.id to it.sigs }, numPos, numSize, pdfSize))
        if (undoStack.size > 40) undoStack.removeAt(0)
        undoCount = undoStack.size
    }

    fun undo() {
        val snap = undoStack.removeLastOrNull() ?: return
        for (p in pages.toList()) {
            snap.looks[p.id]?.let { (m, c, o) -> upd(p.id) { it.copy(mode = m, clarity = c, oClarity = o) } }
            snap.sigs[p.id]?.let { sg -> upd(p.id) { it.copy(sigs = sg) } }
        }
        selSig = null
        numPos = snap.numPos; numSize = snap.numSize; pdfSize = snap.pdfSize
        prefs.edit().putString("numPos", numPos).putInt("numSize", numSize).putString("pdfSize", pdfSize).apply()
        lastKey = ""; undoCount = undoStack.size
    }

    /** Everything back to the start EXCEPT the crop: every page = Original with no change, no page number, page size = picture size. */
    fun resetAll() {
        checkpoint(null)
        for (p in pages.toList()) upd(p.id) { it.copy(mode = "orig", clarity = 60, oClarity = 0) }
        numPos = "off"; numSize = 12; pdfSize = "fit"
        prefs.edit().putString("numPos", numPos).putInt("numSize", numSize).putString("pdfSize", pdfSize).apply()
    }

    var screen by mutableStateOf(Screen.Home)
    var reviewTab by mutableIntStateOf(0)           // 0 effect, 1 signature, 2 page size, 3 page number (kept while another screen is open)
    val pages = mutableStateListOf<Page>()
    var current by mutableIntStateOf(0)
    var cropQueue by mutableStateOf<List<String>>(emptyList())
    var cropReturn by mutableStateOf(Screen.Home)
    var busy by mutableStateOf<String?>(null)
    var saved by mutableStateOf<Saved?>(null)

    // ------------------------------------------------------------ ID card: front, then back -> ONE page
    /** 0 = normal scanning, 1 = the FRONT of the card is being taken / cut, 2 = the BACK. */
    var idStep by mutableIntStateOf(0)
        private set
    private var idFrontId: String? = null
    private var idBackId: String? = null
    private var idComposing = false
    /** Length of the card's long side on the paper, mm (86 = real size, 120 = large). */
    var idSize by mutableIntStateOf(prefs.getInt("idSize", 86))
        private set

    fun chooseIdSize(v: Int) { idSize = v; prefs.edit().putInt("idSize", v).apply() }

    fun startIdCard() {
        dropIdPages()
        idStep = 1; retakeId = null; camForSig = false
        screen = Screen.Camera
    }

    private fun dropIdPages() {
        for (pid in listOfNotNull(idFrontId, idBackId)) page(pid)?.let { del(it.work); del(it.cropped); del(it.prev); pages.remove(it) }
        idFrontId = null; idBackId = null
    }

    /** Leaves ID card mode without a result (the person went back): the half-made card pages are removed. */
    fun cancelIdCard() {
        if (idStep == 0) return
        dropIdPages(); idStep = 0
        current = current.coerceIn(0, max(0, pages.size - 1))
    }

    /** Both sides are cut out: put them on one A4 page (engine.id_card) and open it on the review screen like any other page. */
    private fun composeIdCard(frontId: String, backId: String) {
        idComposing = true
        viewModelScope.launch {
            busy = str.idMaking
            try {
                val f = page(frontId)?.cropped
                val b = page(backId)?.cropped
                if (f == null || b == null) throw IllegalStateException("card picture missing")
                val id = newId()
                val dst = file(id, "idcard")
                val (w, h) = Engine.idCard(f, b, dst, idSize)
                dropIdPages()
                pages.add(Page(id, dst, w, h, cropped = dst, cw = w, ch = h))
                current = pages.size - 1
                idStep = 0
                screen = Screen.Review
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(str.errPrefix + (e.message ?: ""))
                cancelIdCard()
                screen = if (pages.any { it.cropped != null }) Screen.Review else Screen.Home
            } finally {
                idComposing = false
                busy = null
            }
        }
    }

    // ------------------------------------------------------------ camera -> crop hand-over, and "Retake"
    /** The first page in the crop queue was just taken with the shutter (the crop screen then says "photo taken, move the phone away"). */
    var cropFromShutter by mutableStateOf(false)
        private set
    private var liveHintId: String? = null
    private var liveHint: List<Offset>? = null

    /** Where the live camera saw the page (fractions 0..1) when the shutter was pressed: the crop screen's corners start there. */
    fun takeLiveHint(id: String): List<Offset>? {
        val h = if (liveHintId == id) liveHint else null
        liveHint = null; liveHintId = null
        return h
    }

    /** Retake: the page that the next photo (camera or gallery) will replace, in exactly the same place. */
    var retakeId by mutableStateOf<String?>(null)
    private var replace: Pair<String, String>? = null          // (new page id, old page id)

    fun startRetake(id: String) { retakeId = id; screen = Screen.Camera }
    fun cancelRetake() { retakeId = null }

    /** A shutter photo became page [id]. */
    fun photoTaken(id: String, hint: List<Offset>?) {
        liveHint = hint; liveHintId = id
        retakeId?.let { replace = id to it }
    }

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

    /**
     * manual = the person pressed "Check for updates": always answer.
     * Automatic: while a newer build is not installed, ask once every time the app is opened (this set lives as long as the app
     * is open; "Later" hides the question until the app is closed completely and opened again. The Home card stays meanwhile).
     */
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
        // right after start, then every 10 minutes while the app stays open (GitHub allows 60 asks / hour)
        viewModelScope.launch {
            delay(2_000)
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
            } catch (e: Throwable) {                                      // rare formats, or a phone with little memory (OutOfMemoryError):
                if (e is CancellationException) throw e
                Engine.prepare(src.absolutePath, work.absolutePath)      // the Python way (slower, but safe)
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
                    retakeId?.let { replace = id to it }
                    if (replace?.first != id) current = pages.indexOfFirst { it.id == id }
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
    fun queueCrop(ids: List<String>, back: Screen, fromShutter: Boolean = false) {
        cropQueue = ids; cropReturn = back; cropFromShutter = fromShutter; screen = Screen.Crop
    }

    fun recrop(id: String) = queueCrop(listOf(id), Screen.Review)

    suspend fun applyCrop(id: String, quad: List<Offset>) {
        val p = page(id) ?: return
        val dst = file(id, "crop")
        val (cw, ch) = Engine.crop(p.work, quad, dst)
        del(p.cropped); del(p.prev)
        upd(id) { it.copy(cropped = dst, quad = quad, rot = 0, prev = null, prevKey = "", prevSide = 0, thumbs = null, cw = cw, ch = ch) }
        replace?.let { (newId, oldId) -> if (newId == id) swapIn(newId, oldId) }
        // ID card mode: the first finished crop is the FRONT, the next one the BACK
        if (idStep == 1 && idFrontId == null) { idFrontId = id; idStep = 2 }
        else if (idStep == 2 && idBackId == null && id != idFrontId) idBackId = id
    }

    /** Retake: the new page takes the old page's place (same position, same look), the old page is gone. */
    private fun swapIn(newId: String, oldId: String) {
        replace = null
        val ni = pages.indexOfFirst { it.id == newId }
        val oldPage = page(oldId)
        if (ni < 0 || oldPage == null) return
        val np = pages.removeAt(ni).copy(mode = oldPage.mode, clarity = oldPage.clarity, oClarity = oldPage.oClarity, sigs = oldPage.sigs)
        val oi = pages.indexOfFirst { it.id == oldId }
        if (oi < 0) { pages.add(ni.coerceAtMost(pages.size), np); return }
        pages[oi] = np
        del(oldPage.work); del(oldPage.cropped); del(oldPage.prev)
        current = oi
    }

    /** The page being cropped is done (or discarded): go on with the queue. */
    fun nextCrop(discard: Boolean = false) {
        val id = cropQueue.firstOrNull()
        if (discard && id != null) {
            val p = page(id)
            if (p != null && p.cropped == null) { del(p.work); pages.removeAll { it.id == id } }
        }
        cropQueue = cropQueue.drop(1)
        cropFromShutter = false
        finishCropIfDone()
    }

    /** Queue empty and nothing more is being prepared: leave the crop screen. */
    fun finishCropIfDone() {
        if (screen != Screen.Crop || cropQueue.isNotEmpty() || importing > 0) return
        if (idStep > 0) {                                           // ID card: front done -> camera for the back; back done -> one page
            val f = idFrontId; val b = idBackId
            replace = null; retakeId = null
            if (f != null && b != null) { if (!idComposing) composeIdCard(f, b) } else screen = Screen.Camera
            return
        }
        val back = cropReturn
        replace = null; retakeId = null
        screen = if (back == Screen.Review && pages.none { it.cropped != null }) Screen.Home else back
        if (screen == Screen.Review) current = current.coerceIn(0, max(0, pages.size - 1))
    }

    // ------------------------------------------------------------ page editing
    /** Picks the look and / or moves the clarity slider (the slider value belongs to the look that is chosen). */
    fun setLook(id: String, mode: String? = null, clarity: Int? = null, text: Int? = null) {
        checkpoint(if (mode != null) null else if (text != null) "text:$id" else "clarity:$id")
        setLookNow(id, mode, clarity, text)
    }

    private fun setLookNow(id: String, mode: String?, clarity: Int?, text: Int?) = upd(id) { p ->
        val m = mode ?: p.mode
        var q = p.copy(mode = m)
        if (clarity != null) q = if (m == "orig") q.copy(oClarity = clarity) else q.copy(clarity = clarity)
        if (text != null) q = q.copy(oClarity = text)          // the text clarity slider: the same value for every look
        q
    }

    fun applyLookToAll(from: Page) {
        checkpoint(null)
        for (p in pages.toList()) upd(p.id) { it.copy(mode = from.mode, clarity = from.clarity, oClarity = from.oClarity) }
    }

    suspend fun ensurePreview(id: String, side: Int = 1400) {
        val p = page(id) ?: return
        val c = p.cropped ?: return
        if (!p.needsPrev) return
        val key = p.look
        if (p.prev != null && p.prevKey == key && p.prevSide >= side) return
        val dst = file(id, "prev")
        try {
            Engine.render(c, dst, p.mode, p.eff, side, if (p.mode == "orig") 0 else p.oClarity)
        } catch (e: CancellationException) {
            File(dst).delete(); throw e
        } catch (e: Exception) {
            toast(str.errPrefix + (e.message ?: "")); return
        }
        val now = page(id)
        if (now == null) { File(dst).delete(); return }
        del(now.prev)
        upd(id) { it.copy(prev = dst, prevKey = key, prevSide = side) }
    }

    suspend fun ensureThumbs(id: String) {
        val p = page(id) ?: return
        val c = p.cropped ?: return
        if (p.thumbs != null) return
        val prefix = File(sessionDir, "$id-th${System.nanoTime()}").absolutePath
        try { Engine.thumbs(c, prefix, 60) } catch (e: CancellationException) { throw e } catch (e: Exception) { return }
        upd(id) { it.copy(thumbs = prefix) }
    }

    /** Instant: only the number of quarter turns changes. The screen turns the picture it already has; the files are turned when saving. */
    fun rotate(id: String) {
        // signatures on the page turn with it (their place on the page, their direction and their width relative to the new page width)
        upd(id) { p ->
            val dw0 = if (p.rot % 2 == 0) p.cw else p.ch
            val dh0 = if (p.rot % 2 == 0) p.ch else p.cw
            val k = if (dw0 > 0 && dh0 > 0) dw0.toFloat() / dh0 else 1f
            p.copy(
                rot = (p.rot + 1) % 4,
                sigs = p.sigs.map { it.copy(cx = 1f - it.cy, cy = it.cx, w = (it.w * k).coerceIn(0.03f, 1.5f), rot = (it.rot + 90f) % 360f) },
            )
        }
    }

    fun delete(index: Int) {
        val p = pages.getOrNull(index) ?: return
        del(p.work); del(p.cropped); del(p.prev)
        pages.removeAt(index)
        current = current.coerceIn(0, max(0, pages.size - 1))
        if (pages.isEmpty()) screen = Screen.Home
    }

    /** Drag and drop in the thumbnail strip: the page [from] goes to place [to]; it stays the selected page. */
    fun moveTo(from: Int, to: Int) {
        if (from !in pages.indices || to !in pages.indices || from == to) return
        val p = pages.removeAt(from)
        pages.add(to, p)
        current = to
    }

    fun move(index: Int, by: Int) {
        val to = index + by
        if (index !in pages.indices || to !in pages.indices) return
        val p = pages.removeAt(index)
        pages.add(to, p)
        current = to
    }

    fun clearAll() {
        idStep = 0; idFrontId = null; idBackId = null
        pages.clear(); cropQueue = emptyList(); current = 0; saved = null; selSig = null; reviewTab = 0
        undoStack.clear(); undoCount = 0
        sessionDir.deleteRecursively(); sessionDir.mkdirs()
        screen = Screen.Home
    }

    // ------------------------------------------------------------ manual crop of the finished page (edit screen)
    /** The page that is being trimmed on the Trim screen. */
    var trimId by mutableStateOf<String?>(null)

    /** Opens the manual crop on the straightened page. A turn done on the review screen is made real first, so the crop sees the page as it is shown. */
    suspend fun openTrim(id: String) {
        val p = page(id) ?: return
        val c = p.cropped ?: return
        if (p.rot % 4 != 0) {
            val dst = file(id, "crop")
            Engine.rotate(c, dst, p.rot)
            del(c); del(p.prev)
            val odd = p.rot % 2 == 1
            upd(id) { it.copy(cropped = dst, rot = 0, prev = null, prevKey = "", prevSide = 0, thumbs = null, cw = if (odd) it.ch else it.cw, ch = if (odd) it.cw else it.ch) }
        }
        trimId = id
        screen = Screen.Trim
    }

    /** Cuts the finished page again: [quad] (4 corners, picture pixels of the straightened page) is straightened into the new page. */
    suspend fun applyTrim(id: String, quad: List<Offset>) {
        val p = page(id) ?: return
        val src = p.cropped ?: return
        val dst = file(id, "crop")
        val (cw, ch) = Engine.crop(src, quad, dst)
        del(src); del(p.prev)
        upd(id) { it.copy(cropped = dst, prev = null, prevKey = "", prevSide = 0, thumbs = null, cw = cw, ch = ch) }
    }

    // ------------------------------------------------------------ signatures
    /** The collection (newest first). Each one is a transparent PNG in the app's own folder. */
    val signatures = mutableStateListOf<Sig>()
    private val sigDir = File(ctx.filesDir, "signatures").apply { mkdirs() }
    /** The photo a new signature is being made from (the Sign screen is open while this is set). */
    var sigSrc by mutableStateOf<SigSrc?>(null)
        private set
    /** The camera was opened to take a signature photo (not a page). */
    var camForSig by mutableStateOf(false)
    /** The signature on the page that is selected (id of its SigPlace). */
    var selSig by mutableStateOf<String?>(null)

    init {
        val files = sigDir.listFiles { f -> f.extension == "png" }?.sortedByDescending { it.lastModified() } ?: emptyList()
        for (f in files) {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, o)
            if (o.outWidth > 0 && o.outHeight > 0) signatures.add(Sig(f.nameWithoutExtension, f.absolutePath, o.outWidth, o.outHeight))
        }
    }

    /** A photo (from the camera or a copy of a gallery picture) is the start of a new signature: the Sign screen opens. */
    suspend fun startSignature(src: File) {
        val work = File(sessionDir, "sigsrc-${System.nanoTime()}.jpg")
        try {
            val (w, h) = try {
                prepareNative(src, work, 2200)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Engine.prepare(src.absolutePath, work.absolutePath)
            }
            sigSrc?.let { File(it.path).delete() }
            sigSrc = SigSrc(work.absolutePath, w, h)
            camForSig = false
            screen = Screen.Sign
        } catch (e: Exception) {
            toast(str.errPrefix + (e.message ?: ""))
        } finally {
            src.delete()
        }
    }

    suspend fun importSignature(uri: Uri) {
        val tmp = File(ctx.cacheDir, "sig_${System.nanoTime()}.jpg")
        try {
            withContext(Dispatchers.IO) {
                ctx.contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            }
        } catch (e: Exception) {
            toast(str.errPrefix + (e.message ?: "")); return
        }
        startSignature(tmp)
    }

    /** Leaves the Sign screen (the signature was saved, or the person went back). */
    fun closeSign(tab: Int = 1) {
        sigSrc?.let { File(it.path).delete() }
        sigSrc = null
        reviewTab = tab
        screen = if (pages.isEmpty()) Screen.Home else Screen.Review
    }

    /** A finished signature PNG (made in the cache) joins the collection. */
    fun addSignature(png: File, w: Int, h: Int): Sig {
        val id = newId()
        val dst = File(sigDir, "$id.png")
        png.copyTo(dst, overwrite = true)
        png.delete()
        val sg = Sig(id, dst.absolutePath, w, h)
        signatures.add(0, sg)
        return sg
    }

    fun deleteSignature(id: String) {
        val sg = signatures.firstOrNull { it.id == id } ?: return
        signatures.remove(sg)
        File(sg.path).delete()
        for (p in pages.toList()) if (p.sigs.any { it.sigId == id }) upd(p.id) { it.copy(sigs = it.sigs.filter { s -> s.sigId != id }) }
        selSig = null
    }

    /** The signature goes to the middle of the page (the person drags it where it belongs). */
    fun placeSig(pageId: String, sigId: String) {
        val sg = signatures.firstOrNull { it.id == sigId } ?: return
        val p = page(pageId) ?: return
        checkpoint(null)
        val pageAspect = if (p.cw > 0 && p.ch > 0) (if (p.rot % 2 == 0) p.cw.toFloat() / p.ch else p.ch.toFloat() / p.cw) else 0.72f
        var w = 0.36f
        val hFrac = w / pageAspect * sg.h / sg.w                     // its height as a fraction of the page height
        if (hFrac > 0.30f) w *= 0.30f / hFrac
        val pl = SigPlace(newId(), sigId, 0.5f, 0.5f, w.coerceIn(0.06f, 0.9f), 0f)
        upd(pageId) { it.copy(sigs = it.sigs + pl) }
        selSig = pl.id
    }

    /** Moves / resizes / turns one signature. A drag is ONE undo step. */
    fun updateSig(pageId: String, id: String, f: (SigPlace) -> SigPlace) {
        checkpoint("sig:$id")
        upd(pageId) { p -> p.copy(sigs = p.sigs.map { if (it.id == id) f(it) else it }) }
    }

    fun removeSig(pageId: String, id: String) {
        checkpoint(null)
        upd(pageId) { p -> p.copy(sigs = p.sigs.filter { it.id != id }) }
        if (selSig == id) selSig = null
    }

    /** The same signature in the same place on every other page too (pressing it again does not add it twice). */
    fun copySigToAll(pageId: String, id: String) {
        val src = page(pageId)?.sigs?.firstOrNull { it.id == id } ?: return
        checkpoint(null)
        for (p in pages.toList()) {
            if (p.id == pageId) continue
            upd(p.id) { q -> q.copy(sigs = q.sigs.filter { it.sigId != src.sigId } + src.copy(id = newId())) }
        }
    }

    private fun sigString(p: Page): String = p.sigs.mapNotNull { pl ->
        val sg = signatures.firstOrNull { it.id == pl.sigId } ?: return@mapNotNull null
        "${sg.path}~${pl.cx}~${pl.cy}~${pl.w}~${pl.rot}~${pl.alpha}"
    }.joinToString(";")

    // ------------------------------------------------------------ camera tips (shown once)
    var camTipsSeen by mutableStateOf(prefs.getBoolean("camTipsSeen", false))
        private set
    fun markCamTipsSeen() { camTipsSeen = true; prefs.edit().putBoolean("camTipsSeen", true).apply() }
    var sigTipsSeen by mutableStateOf(prefs.getBoolean("sigTipsSeen", false))
        private set
    fun markSigTipsSeen() { sigTipsSeen = true; prefs.edit().putBoolean("sigTipsSeen", true).apply() }

    // ------------------------------------------------------------ export (save / share)
    /** Makes the final files (full quality) in the cache: one PDF with all pages, or one picture per page. */
    suspend fun export(format: String, size: String): List<File> {
        val out = File(ctx.cacheDir, "out").apply { deleteRecursively(); mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val list = pages.filter { it.cropped != null }
        if (format == "pdf") {
            busy = str.processing
            val f = File(out, "DocScanner_$ts.pdf")
            Engine.makePdf(list.map { Engine.PdfItem(it.cropped!!, it.mode, it.eff, if (it.mode == "orig") 0 else it.oClarity, it.rot, sigString(it)) }, f.absolutePath, size, numPos, numSize)
            return listOf(f)
        }
        val ext = if (format == "png") "png" else "jpg"
        val files = ArrayList<File>()
        list.forEachIndexed { i, p ->
            busy = str.exporting(i + 1, list.size)
            val f = File(out, if (list.size == 1) "DocScanner_$ts.$ext" else "DocScanner_${ts}_${i + 1}.$ext")
            Engine.exportImage(p.cropped!!, f.absolutePath, format, p.mode, p.eff, i + 1, numPos, numSize, Fonts.path(ctx), if (p.mode == "orig") 0 else p.oClarity, p.rot, sigString(p))
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

/** Decoded pictures stay in memory (about 1/8 of the app's memory): a picture is never decoded twice while it is in use. */
private val bitmapCache = object : android.util.LruCache<String, ImageBitmap>(
    (Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtLeast(8 * 1024 * 1024),
) {
    override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
}

/** The picture if it is already decoded (instant, no waiting), else null. */
fun cachedBitmap(path: String, maxSide: Int): ImageBitmap? = bitmapCache.get("$path@$maxSide")

/** Decodes a picture file, no bigger than about maxSide pixels on the long side. */
fun decodeBitmap(path: String, maxSide: Int): ImageBitmap? {
    cachedBitmap(path, maxSide)?.let { return it }
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, o)
    var sample = 1
    while (max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
    val bmp = BitmapFactory.decodeFile(path, o2)?.asImageBitmap()
    if (bmp != null) bitmapCache.put("$path@$maxSide", bmp)
    return bmp
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
