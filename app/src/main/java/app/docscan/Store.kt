package app.docscan

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import java.io.File

/** Saving to the phone's memory and sharing with other apps. */
object Store {
    fun mime(f: File): String = when (f.extension.lowercase()) {
        "pdf" -> "application/pdf"
        "png" -> "image/png"
        else -> "image/jpeg"
    }

    /** Copies the files to the chosen folder (SAF) or, with no choice, to Pictures/DocScanner and Documents/DocScanner. Returns a readable place. */
    fun save(ctx: Context, files: List<File>, treeUri: String?): String {
        val resolver = ctx.contentResolver
        if (treeUri != null) {
            val dir = DocumentFile.fromTreeUri(ctx, Uri.parse(treeUri))
            if (dir != null && dir.canWrite()) {
                for (f in files) {
                    val target = dir.createFile(mime(f), f.name) ?: throw IllegalStateException("Cannot create ${f.name}")
                    resolver.openOutputStream(target.uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
                }
                return dir.name ?: "folder"
            }
        }
        var place = ""
        for (f in files) {
            val image = mime(f).startsWith("image/")
            val rel = if (image) Environment.DIRECTORY_PICTURES + "/DocScanner" else Environment.DIRECTORY_DOCUMENTS + "/DocScanner"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime(f))
                put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = if (image) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values) ?: throw IllegalStateException("Cannot save ${f.name}")
            resolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            place = rel
        }
        return place
    }

    fun share(ctx: Context, files: List<File>, title: String) {
        val uris = files.map { FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", it) }
        val type = if (files.all { mime(it) == mime(files[0]) }) mime(files[0]) else "*/*"
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris[0]) }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)) }
        }
        intent.type = type
        val clip = ClipData.newRawUri("", uris[0])
        for (i in 1 until uris.size) clip.addItem(ClipData.Item(uris[i]))
        intent.clipData = clip
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(intent, title))
    }
}
