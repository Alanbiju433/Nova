package com.aethernova.nova.assist

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.aethernova.nova.NovaApp
import com.aethernova.nova.R
import com.aethernova.nova.data.Secure
import java.io.File

/**
 * Always-listening wake word, even with the screen off. Uses Picovoice Porcupine, which runs a tiny
 * on-device model — audio never leaves the phone and nothing is recorded. Needs a free AccessKey
 * from console.picovoice.ai. Built-in word: "Jarvis"; or import your own "Hey Nova" .ppn file.
 */
class WakeService : Service() {
    private var manager: PorcupineManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopListening(); stopSelf(); return START_NOT_STICKY }
            ACTION_PAUSE -> { pauseMic(); return START_STICKY }
            ACTION_RESUME -> { startMic(); return START_STICKY }
        }
        ensureChannel(this)
        val n = notification(this, "Listening for \"${wordLabel(this)}\"")
        runCatching {
            ServiceCompat.startForeground(this, NOTIF_ID, n,
                if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        }.onFailure { lastError = "Android blocked the mic service: ${it.message}"; stopSelf(); return START_NOT_STICKY }
        startMic()
        return START_STICKY
    }

    private fun startMic() {
        if (manager != null) { runCatching { manager!!.start() }; running = true; return }
        val key = Secure.get(this, "picovoice_key")
        if (key.isBlank()) { lastError = "Add your Picovoice AccessKey in Settings."; stopSelf(); return }
        val custom = File(filesDir, "wakeword/custom.ppn")
        try {
            val b = PorcupineManager.Builder().setAccessKey(key).setSensitivity(0.6f)
                .setErrorCallback { e -> lastError = e.message }
            if (custom.exists()) b.setKeywordPath(custom.absolutePath) else b.setKeyword(Porcupine.BuiltInKeyword.JARVIS)
            manager = b.build(applicationContext) { onWake() }
            manager!!.start()
            running = true; lastError = null
        } catch (e: Exception) {
            lastError = "Wake word failed: ${e.message}"
            manager = null; stopSelf()
        }
    }

    private fun pauseMic() { runCatching { manager?.stop() } }

    private fun stopListening() {
        runCatching { manager?.stop(); manager?.delete() }
        manager = null; running = false
    }

    private fun onWake() {
        pauseMic()   // free the microphone for the assistant screen
        runCatching {
            val v = getSystemService(Vibrator::class.java)
            v?.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
        }
        val open = Intent(this, AssistActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(AssistActivity.EXTRA_FROM_WAKE, true)
        // Opening a screen from the background needs "Display over other apps" on Android 10+.
        if (Settings.canDrawOverlays(this)) runCatching { startActivity(open) }.onFailure { tapToTalk(open) }
        else tapToTalk(open)
    }

    private fun tapToTalk(open: Intent) {
        val pi = PendingIntent.getActivity(this, 7, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_stat_nova).setContentTitle("Yes?").setContentText("Tap to talk to ${(application as NovaApp).settings.current.buddyName}")
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(pi).setAutoCancel(true).setTimeoutAfter(15_000).build()
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_TAP, n) }
        // resume listening shortly if nobody taps
        android.os.Handler(mainLooper).postDelayed({ if (!AssistActivity.open) startMic() }, 15_000)
    }

    override fun onDestroy() { stopListening(); super.onDestroy() }

    companion object {
        const val ACTION_STOP = "nova.wake.STOP"
        const val ACTION_PAUSE = "nova.wake.PAUSE"
        const val ACTION_RESUME = "nova.wake.RESUME"
        private const val CHANNEL = "nova_wake"
        private const val CHANNEL_ALERT = "nova_wake_alert"
        private const val NOTIF_ID = 42
        private const val NOTIF_TAP = 43

        @Volatile var running = false; private set
        @Volatile var lastError: String? = null

        fun wordLabel(ctx: Context) = if (File(ctx.filesDir, "wakeword/custom.ppn").exists()) "your custom word" else "Jarvis"

        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, WakeService::class.java))
        fun stop(ctx: Context) = runCatching { ctx.startService(Intent(ctx, WakeService::class.java).setAction(ACTION_STOP)) }
        fun pause(ctx: Context) { if (running) runCatching { ctx.startService(Intent(ctx, WakeService::class.java).setAction(ACTION_PAUSE)) } }
        fun resume(ctx: Context) { if (running) runCatching { ctx.startService(Intent(ctx, WakeService::class.java).setAction(ACTION_RESUME)) } }

        private fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Nova listening", NotificationManager.IMPORTANCE_MIN))
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERT, "Nova wake-up", NotificationManager.IMPORTANCE_HIGH))
        }

        private fun notification(ctx: Context, text: String): Notification {
            val stop = PendingIntent.getService(ctx, 1, Intent(ctx, WakeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
            return NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_nova).setContentTitle("Nova is listening").setContentText(text)
                .setOngoing(true).setSilent(true)
                .addAction(0, "Stop", stop).build()
        }
    }
}
