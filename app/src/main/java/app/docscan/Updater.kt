package app.docscan

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Auto update, same way as Image Fixer's updater.py:
 *  - the GitHub build publishes every build as a Release called  build-<number>  with the file DocScan.apk, and
 *    puts the number + repository into BuildConfig (BUILD, REPO) while building;
 *  - the app asks  https://api.github.com/repos/<repo>/releases/latest  and compares the number with its own;
 *  - install = download the APK, check size + SHA-256, hand it to Android's installer (one tap on "Install").
 *    Settings stay, because the new APK has the same signature and the same package name.
 */
object Updater {
    class Info(
        val build: Int, val name: String, val notes: String, val url: String,
        val size: Long, val sha256: String, val page: String,
    )

    class UpdateError(msg: String) : Exception(msg)

    const val ASSET = "DocScan.apk"
    private const val API = "https://api.github.com"
    private val TAG = Regex("^build-(\\d+)$")

    fun canSelfUpdate() = BuildConfig.REPO.isNotEmpty() && BuildConfig.BUILD > 0

    /** GitHub 'latest release' json -> the update, or null when there is nothing newer to install. */
    fun parse(data: JSONObject, repo: String, current: Int): Info? {
        val tag = data.optString("tag_name")
        val m = TAG.matchEntire(tag) ?: return null
        if (data.optBoolean("draft") || data.optBoolean("prerelease")) return null
        val build = m.groupValues[1].toInt()
        if (build <= current) return null
        val assets = data.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name") != ASSET) continue
            val url = a.optString("browser_download_url")
            if (!url.startsWith("https://github.com/$repo/releases/download/")) {
                throw UpdateError("The update has an unexpected download address.")
            }
            val digest = a.optString("digest")
            return Info(
                build, data.optString("name").ifEmpty { tag }, data.optString("body").trim(), url,
                a.optLong("size"), if (digest.startsWith("sha256:")) digest.substring(7).lowercase() else "",
                data.optString("html_url").ifEmpty { "https://github.com/$repo/releases" },
            )
        }
        return null                                   // release still being uploaded: ask again next time
    }

    /** Asks GitHub (blocking, call it off the main thread). null = up to date. */
    fun check(): Info? {
        if (!canSelfUpdate()) throw UpdateError("This copy was not made by the GitHub build, so it does not know where to look for updates.")
        val c = URL("$API/repos/${BuildConfig.REPO}/releases/latest").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000; c.readTimeout = 15000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "DocScan-updater")
            val code = c.responseCode
            if (code == 404) return null              // no release published yet
            if (code != 200) throw UpdateError("GitHub answered $code.")
            val text = c.inputStream.bufferedReader().use { it.readText() }
            return parse(JSONObject(text), BuildConfig.REPO, BuildConfig.BUILD)
        } catch (e: UpdateError) {
            throw e
        } catch (e: Exception) {
            throw UpdateError("Could not reach GitHub (${e.message}).")
        } finally {
            c.disconnect()
        }
    }

    /** Streams the APK into the cache and checks size + SHA-256. progress(done, total); cancelled() stops it. */
    fun download(ctx: Context, info: Info, progress: (Long, Long) -> Unit, cancelled: () -> Boolean): File {
        val dir = File(ctx.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
        val dest = File(dir, ASSET)
        val c = URL(info.url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000; c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "DocScan-updater")
            if (c.responseCode != 200) throw UpdateError("Download failed (${c.responseCode}).")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: info.size
            val md = MessageDigest.getInstance("SHA-256")
            var done = 0L
            c.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        if (cancelled()) throw UpdateError("cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); md.update(buf, 0, n)
                        done += n
                        progress(done, total)
                    }
                }
            }
            if (info.size > 0 && done != info.size) throw UpdateError("The download is incomplete ($done of ${info.size} bytes).")
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            if (info.sha256.isNotEmpty() && hex != info.sha256) throw UpdateError("The downloaded file is damaged (checksum does not match).")
            return dest
        } catch (e: Exception) {
            dest.delete()
            throw if (e is UpdateError) e else UpdateError("Download failed (${e.message}).")
        } finally {
            c.disconnect()
        }
    }

    /** Android asks once: "allow this app to install apps". */
    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    fun openInstallSettings(ctx: Context) {
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName)))
    }

    /** Opens Android's installer for the downloaded APK (the person taps "Install"). */
    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(intent)
    }
}
