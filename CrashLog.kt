package com.aethernova.nova

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves the reason for any crash so the next launch can show it (and you can copy it to me).
 * Java/Kotlin crashes give a full stack trace; native crashes (camera/MediaPipe) are picked up
 * from Android's exit-reason history on Android 11+.
 */
object CrashLog {
    private fun file(ctx: Context) = File(ctx.filesDir, "last_crash.txt")
    private const val PREFS = "nova_crash"

    fun install(ctx: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                file(ctx).writeText(header(ctx) + "Thread: ${t.name}\n\n" + sw.toString().take(12_000))
            }
            previous?.uncaughtException(t, e)
        }
        runCatching { captureNativeCrash(ctx) }
    }

    private fun header(ctx: Context): String {
        val v = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.UK).format(Date())
        return "Nova $v · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL} · $time\n"
    }

    private fun captureNativeCrash(ctx: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getLong("seen_ts", 0)
        val am = ctx.getSystemService(ActivityManager::class.java)
        val info = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 5)
            .firstOrNull { it.timestamp > seen } ?: return
        prefs.edit().putLong("seen_ts", info.timestamp).apply()
        if (info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
            (info.reason == ApplicationExitInfo.REASON_CRASH && !file(ctx).exists())) {
            val kind = if (info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) "native crash" else "crash"
            file(ctx).writeText(header(ctx) + "Previous exit: $kind\n${info.description ?: ""}\n")
        }
    }

    fun read(ctx: Context): String? = file(ctx).takeIf { it.exists() }?.readText()
    fun clear(ctx: Context) { file(ctx).delete() }

    // Guard so a native crash while starting the camera doesn't repeat on every Play-mode open.
    fun markCameraStarting(ctx: Context, starting: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("camera_starting", starting).commit()

    fun cameraCrashedLastTime(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("camera_starting", false)
}
