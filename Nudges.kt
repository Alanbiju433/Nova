package com.aethernova.nova.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.aethernova.nova.MainActivity
import com.aethernova.nova.NovaApp
import com.aethernova.nova.R
import com.aethernova.nova.data.Kind
import com.aethernova.nova.data.Plan
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** How Nova checks whether you actually started. */
enum class Detect { ACK, WALK, SESSION, SCREEN_OFF }

/**
 * One two-stage reminder: a gentle nudge at [gentleAt]; if you haven't started by [checkAt],
 * a second, louder one. [loud] = false keeps the second one firm but quiet (online class, social, bedtime).
 */
data class Nudge(
    val key: String, val what: String, val gentleAt: LocalDateTime, val checkAt: LocalDateTime,
    val gentle: String, val shout: String, val loud: Boolean, val detect: Detect,
    /** false = one gentle reminder only, never a second (wind-down breathing). */
    val twoStage: Boolean = true,
    /** Overlay page the notification opens (e.g. "breathe"), or null for the home screen. */
    val opens: String? = null,
)

/** Builds the day's nudges from the routine in [Plan]. Pure, unit-tested. */
object NudgePlan {
    private fun t(s: String) = LocalTime.parse(s)

    fun forDate(d: LocalDate, manglish: Boolean, windDown: Boolean = true, name: String = "friend"): List<Nudge> {
        val out = mutableListOf<Nudge>()
        fun at(time: LocalTime) = d.atTime(time)
        val da = if (manglish) "machane" else name
        val key = { what: String, time: LocalTime -> "${d}_${time}_$what" }
        val up = { x: String -> x.uppercase(Locale.getDefault()) }

        for (b in Plan.blocksFor(d)) {
            val leave = b.start.minusMinutes(b.travelMin.toLong())
            when (b.kind) {
                Kind.WALK -> out += Nudge(key("walk", b.start), "Morning walk", at(b.start), at(b.start.plusMinutes(20)),
                    "Good morning! Walk time — shoes on 👟", "${up(da)}! UP! Walk now — the streak is watching!", true, Detect.WALK)
                Kind.WORK -> {
                    out += Nudge(key("work", b.start), b.what, at(leave.minusMinutes(15)), at(leave.plusMinutes(2)),
                        if (b.travelMin > 0) "${b.what} at ${b.start} — leave by $leave." else "${b.what} starts at ${b.start}.",
                        "${up(b.what)}! Out the door NOW, $da!", true, Detect.ACK)
                    // after a late shift: one soft wind-down prompt, never a shout
                    if (windDown && b.end >= LocalTime.of(20, 0) && b.end <= LocalTime.of(23, 0))
                        out += Nudge(key("winddown", b.end), "Wind down", at(b.end.plusMinutes(30)), at(b.end.plusMinutes(30)),
                            "Home? 3 minutes of slow breathing before bed — tap to start.", "", false, Detect.ACK, twoStage = false, opens = "breathe")
                }
                Kind.ONLINE -> out += Nudge(key("online", b.start), b.what, at(b.start.minusMinutes(10)), at(b.start.minusMinutes(2)),
                    "${b.what} starts at ${b.start} — log in.", "It's starting — join now!", false, Detect.ACK)
                Kind.CLASS -> out += Nudge(key("class", b.start), b.what, at(leave.minusMinutes(15)), at(leave.plusMinutes(2)),
                    "${b.what} at ${b.start}. Time to head out.", "${up(b.what)} SOON — MOVE, $da!", true, Detect.ACK)
                Kind.WORSHIP -> out += Nudge(key("worship", b.start), b.what, at(leave.minusMinutes(15)), at(leave.plusMinutes(0)),
                    "${b.what} at ${b.start} — get ready.", "Time to leave for ${b.what.lowercase(Locale.getDefault())}!", true, Detect.ACK)
                Kind.SOCIAL, Kind.OTHER -> out += Nudge(key(if (b.kind == Kind.SOCIAL) "social" else "other", b.start), b.what,
                    at(leave.minusMinutes(10)), at(b.start.minusMinutes(1)),
                    "${b.what} at ${b.start}.", "${b.what} is starting — go on, $da!", false, Detect.ACK)
                Kind.WORKOUT -> out += Nudge(key("session", b.start), b.what, at(b.start), at(b.start.plusMinutes(20)),
                    "${b.what} time — 30 minutes, you've got this.", "WORKOUT, $da! Mat down, NOW! No excuses today!", true, Detect.SESSION)
                Kind.BED -> out += Nudge(key("bed", b.start), "Bedtime", at(b.start.minusMinutes(10)), at(b.start.plusMinutes(15)),
                    "Wind-down: screens away, bed by ${b.start}.", "Still on your phone? Phone down, sleep, $da.", false, Detect.SCREEN_OFF)
            }
        }
        return out.filter { it.gentleAt.toLocalDate() == d }.sortedBy { it.gentleAt }
    }

    /** Loud alarms only between 05:00 and 22:30 — never in the middle of the night. */
    fun loudAllowed(at: LocalDateTime) = at.toLocalTime() >= LocalTime.of(5, 0) && at.toLocalTime() <= LocalTime.of(22, 30)

    /** The next (nudge, phase) after [now] across today and tomorrow. */
    fun next(now: LocalDateTime, manglish: Boolean, windDown: Boolean = true, name: String = "friend"): Pair<Nudge, String>? =
        (0..1L).flatMap { forDate(now.toLocalDate().plusDays(it), manglish, windDown, name) }
            .flatMap { if (it.twoStage) listOf(it to "gentle", it to "check") else listOf(it to "gentle") }
            .map { (n, ph) -> Triple(n, ph, if (ph == "gentle") n.gentleAt else n.checkAt) }
            .filter { it.third.isAfter(now.plusSeconds(20)) }
            .minByOrNull { it.third }?.let { it.first to it.second }
}

object Nudges {
    private const val CH_GENTLE = "nova_nudge_gentle"
    private const val CH_LOUD = "nova_nudge_loud"
    const val ACTION_ACK = "com.aethernova.nova.NUDGE_ACK"

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_GENTLE, "Schedule nudges (gentle)", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_LOUD, "Schedule nudges (LOUD)", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            enableVibration(true); vibrationPattern = longArrayOf(0, 600, 300, 600, 300, 900)
        })
    }

    fun scheduleNext(ctx: Context) {
        val app = ctx.applicationContext as NovaApp
        val wm = WorkManager.getInstance(ctx)
        if (!app.settings.current.nudges) { wm.cancelUniqueWork("nova_nudge"); return }
        val (n, phase) = NudgePlan.next(LocalDateTime.now(), app.settings.current.manglish, app.settings.current.windDown, app.settings.current.callName) ?: return
        val at = if (phase == "gentle") n.gentleAt else n.checkAt
        val req = OneTimeWorkRequestBuilder<NudgeWorker>()
            .setInitialDelay(Duration.between(LocalDateTime.now(), at).toMillis().coerceAtLeast(1000), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("key" to n.key, "phase" to phase))
            .build()
        wm.enqueueUniqueWork("nova_nudge", ExistingWorkPolicy.REPLACE, req)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("nova_nudges", Context.MODE_PRIVATE)
    fun ack(ctx: Context, key: String) { prefs(ctx).edit().putBoolean("ack_$key", true).apply() }
    fun isAcked(ctx: Context, key: String) = prefs(ctx).getBoolean("ack_$key", false)

    fun notify(ctx: Context, n: Nudge, loud: Boolean) {
        val id = n.key.hashCode()
        val open = PendingIntent.getActivity(ctx, id, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { n.opens?.let { putExtra("open", it) } }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val ack = PendingIntent.getBroadcast(ctx, id, Intent(ctx, NudgeReceiver::class.java).setAction(ACTION_ACK)
            .putExtra("key", n.key).putExtra("id", id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = NotificationCompat.Builder(ctx, if (loud) CH_LOUD else CH_GENTLE)
            .setSmallIcon(R.drawable.ic_stat_nova)
            .setContentTitle(if (loud) "⚠️ ${n.what}" else n.what)
            .setContentText(if (loud) n.shout else n.gentle)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (loud) n.shout else n.gentle))
            .setContentIntent(open).setAutoCancel(true)
        if (n.twoStage) b.addAction(0, if (n.detect == Detect.SCREEN_OFF) "Going to bed ✓" else "On it ✓", ack)
        if (loud) b.setPriority(NotificationCompat.PRIORITY_MAX).setCategory(NotificationCompat.CATEGORY_ALARM)
        if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) runCatching { NotificationManagerCompat.from(ctx).notify(id, b.build()) }
    }

    /** Speaks the shout through the ALARM stream, so it's heard even if media volume is down. */
    suspend fun shout(ctx: Context, text: String) {
        var tts: TextToSpeech? = null
        val ready = withTimeoutOrNull(4000) {
            suspendCancellableCoroutine<Boolean> { cont -> tts = TextToSpeech(ctx) { st -> if (cont.isActive) cont.resume(st == TextToSpeech.SUCCESS) } }
        } ?: false
        val t = tts ?: return
        if (ready) {
            t.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            t.language = Locale.getDefault(); t.setSpeechRate(1.15f); t.setPitch(1.2f)
            var done = false
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { done = true }
                @Deprecated("Deprecated in Java") override fun onError(id: String?) { done = true }
            })
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "shout")
            withTimeoutOrNull(12_000) { while (!done) delay(200) }
        }
        t.shutdown()
    }
}

class NudgeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as NovaApp
        val s = app.settings.current
        val key = inputData.getString("key") ?: return Result.success()
        val phase = inputData.getString("phase") ?: "gentle"
        val today = LocalDate.parse(key.substringBefore('_'))
        val n = NudgePlan.forDate(today, s.manglish, s.windDown, s.callName).firstOrNull { it.key == key }
        if (n != null && s.nudges && !Nudges.isAcked(applicationContext, key)) {
            if (phase == "gentle") Nudges.notify(applicationContext, n, loud = false)
            else if (n.twoStage && !started(n)) {
                val loud = n.loud && s.loudNudges && NudgePlan.loudAllowed(LocalDateTime.now())
                Nudges.notify(applicationContext, n, loud)
                if (loud) Nudges.shout(applicationContext, n.shout)
            }
        }
        Nudges.scheduleNext(applicationContext)
        return Result.success()
    }

    private suspend fun started(n: Nudge): Boolean {
        val app = applicationContext as NovaApp
        val day = n.gentleAt.toLocalDate().toString()
        return when (n.detect) {
            Detect.ACK -> false
            Detect.WALK -> app.repo.dao.workoutsSinceNow(day).any { it.kind == "walk" && it.day == day } ||
                (runCatching { com.aethernova.nova.health.HealthData(applicationContext).stepsBetween(n.gentleAt, LocalDateTime.now()) }.getOrNull() ?: 0L) > 600
            Detect.SESSION -> app.repo.dao.workoutsSinceNow(day).any { it.day == day && it.kind != "walk" }
            Detect.SCREEN_OFF -> !applicationContext.getSystemService(PowerManager::class.java).isInteractive
        }
    }
}

class NudgeReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Nudges.ACTION_ACK) return
        intent.getStringExtra("key")?.let { Nudges.ack(ctx, it) }
        NotificationManagerCompat.from(ctx).cancel(intent.getIntExtra("id", 0))
    }
}
