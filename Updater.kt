package com.aethernova.nova.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Self-update from GitHub Releases. Put each new APK as an asset on a release tagged like
 * "v0.3.0" in the repo set in Settings (default Alanbiju433/nova). The app compares the tag
 * with its own version, downloads the APK and hands it to Android's installer.
 * Because every build is signed with the same key, it installs as an update and keeps your data.
 */
object Updater {
    data class Release(val version: String, val notes: String, val apkUrl: String)

    private val http = OkHttpClient.Builder().readTimeout(120, TimeUnit.SECONDS).build()

    fun currentVersion(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"

    /** Returns the newer release, or null if already up to date. Throws on network errors. */
    suspend fun check(ctx: Context, repo: String): Release? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/${repo.trim()}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 404) throw Exception("No releases found for $repo yet")
            if (!resp.isSuccessful) throw Exception("GitHub ${resp.code}")
            val o = JSONObject(resp.body!!.string())
            val tag = o.getString("tag_name").removePrefix("v")
            val assets = o.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") }
                ?: throw Exception("Release $tag has no .apk attached")
            if (isNewer(tag, currentVersion(ctx))) Release(tag, o.optString("body"), apk.getString("browser_download_url")) else null
        }
    }

    suspend fun download(ctx: Context, url: String, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "nova-update.apk")
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("Download failed (${resp.code})")
            val body = resp.body!!
            val total = body.contentLength()
            body.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        read += n
                        if (total > 0) onProgress((read * 100 / total).toInt())
                    }
                }
            }
        }
        out
    }

    /** Opens Android's installer. First time, Android asks you to allow installs from Nova. */
    fun install(ctx: Context, apk: File): Boolean {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }

    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
