package com.aethernova.nova.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.aethernova.nova.MainActivity
import com.aethernova.nova.NovaApp
import com.aethernova.nova.R
import com.aethernova.nova.data.Plan
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * Four nudges, each rescheduling itself after it fires:
 *  MORNING  05:35 daily               — walk + "how did you sleep?"
 *  WORKOUT  replaced by schedule nudges (from the user's workout days)
 *  EVENING  40 min after a late shift, else 20:45 — protein recap from the real log
 *  WEEKLY   Sun 20:30 — look back at the week
 */
object Reminders {
    const val CHANNEL = "nova_nudges"
    enum class Type { MORNING, WORKOUT, EVENING, WEEKLY, SUN, BEDTIME, WATER }

    fun createChannel(context: Context) {
        val ch = NotificationChannel(CHANNEL, "Nova nudges", NotificationManager.IMPORTANCE_DEFAULT)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    fun scheduleAll(context: Context) = Type.entries.forEach { schedule(context, it) }

    fun cancelAll(context: Context) = Type.entries.forEach { WorkManager.getInstance(context).cancelUniqueWork("nova_${it.name}") }

    fun schedule(context: Context, type: Type) {
        val next = nextTime(type, LocalDateTime.now())
            ?: run { WorkManager.getInstance(context).cancelUniqueWork("nova_${type.name}"); return }
        val delay = Duration.between(LocalDateTime.now(), next).toMillis().coerceAtLeast(1000)
        val req = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("type" to type.name))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("nova_${type.name}", ExistingWorkPolicy.REPLACE, req)
    }

    private fun timeFor(type: Type, date: LocalDate): LocalTime? = when (type) {
        Type.MORNING -> Plan.routine.walkAt?.plusMinutes(35) ?: LocalTime.of(7, 30)
        Type.WORKOUT -> null   // replaced by two-stage schedule nudges (v0.7)
        Type.EVENING -> Plan.blocksFor(date).filter { it.kind == com.aethernova.nova.data.Kind.WORK && it.end >= LocalTime.of(20, 0) }
            .maxOfOrNull { it.end }?.plusMinutes(40)?.coerceAtMost(LocalTime.of(22, 0)) ?: LocalTime.of(20, 45)
        Type.WEEKLY -> if (date.dayOfWeek == DayOfWeek.SUNDAY) LocalTime.of(20, 30) else null
        // Apr–Sep: daily daylight nudge. Oct–Mar the sun is too weak in the UK/northern latitudes, so a weekly note instead.
        Type.SUN -> if (date.monthValue in 4..9 || date.dayOfWeek == DayOfWeek.MONDAY) LocalTime.of(12, 45) else null
        Type.BEDTIME -> null   // replaced by two-stage schedule nudges (v0.7)
        Type.WATER -> LocalTime.of(15, 30)
    }

    // ---------- Focus timer end ----------
    fun scheduleFocusEnd(context: Context, endAt: Long, isBreak: Boolean) {
        val req = OneTimeWorkRequestBuilder<FocusWorker>()
            .setInitialDelay((endAt - System.currentTimeMillis()).coerceAtLeast(1000), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("break" to isBreak))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("nova_focus", ExistingWorkPolicy.REPLACE, req)
    }

    // ---------- One-off reminders ("remind me at 9pm to…") ----------
    fun scheduleNote(context: Context, at: LocalDateTime, text: String) {
        val delay = Duration.between(LocalDateTime.now(), at).toMillis().coerceAtLeast(1000)
        val req = OneTimeWorkRequestBuilder<NoteWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("text" to text))
            .build()
        WorkManager.getInstance(context).enqueue(req)
    }

    fun cancelFocusEnd(context: Context) = WorkManager.getInstance(context).cancelUniqueWork("nova_focus")

    // ---------- 20-20-20 eye breaks inside a focus block ----------
    fun scheduleEyeBreaks(context: Context, minutes: Int) {
        cancelEyeBreaks(context)
        com.aethernova.nova.data.EyeBreaks.offsets(minutes).forEachIndexed { i, m ->
            val req = OneTimeWorkRequestBuilder<EyeBreakWorker>().setInitialDelay(m.toLong(), TimeUnit.MINUTES).addTag("nova_eye").build()
            WorkManager.getInstance(context).enqueueUniqueWork("nova_eye_$i", ExistingWorkPolicy.REPLACE, req)
        }
    }
    fun cancelEyeBreaks(context: Context) = WorkManager.getInstance(context).cancelAllWorkByTag("nova_eye")

    fun nextTime(type: Type, now: LocalDateTime): LocalDateTime? {
        for (i in 0..14) {
            val d = now.toLocalDate().plusDays(i.toLong())
            val t = timeFor(type, d) ?: continue
            val at = d.atTime(t)
            if (at.isAfter(now.plusSeconds(30))) return at
        }
        return null
    }

    fun notify(context: Context, id: Int, title: String, text: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_nova)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
    }
}

class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as NovaApp
        val type = Reminders.Type.valueOf(inputData.getString("type") ?: return Result.success())
        val s = app.settings.current
        if (s.reminders) {
            val name = s.buddyName
            val today = LocalDate.now()
            when (type) {
                Reminders.Type.MORNING -> {
                    val hours = com.aethernova.nova.data.Weather.today(s.lat, s.lon)
                    val w = hours?.let { com.aethernova.nova.data.Weather.window(it, today, 5, 6) }
                    val fest = com.aethernova.nova.data.Festivals.today(today, s.manglish)?.let { "$it " } ?: ""
                    val wx = w?.let { "${it.tempC.toInt()}°C, ${com.aethernova.nova.data.Weather.describe(it.code)}. " } ?: ""
                    // follow-up tests due in a week or today
                    app.repo.dao.openFollowupsNow().forEach { f ->
                        val due = runCatching { LocalDate.parse(f.dueDay) }.getOrNull() ?: return@forEach
                        val daysTo = java.time.temporal.ChronoUnit.DAYS.between(today, due)
                        if (daysTo == 7L || daysTo == 1L || daysTo == 0L || daysTo < 0 && daysTo % 7 == 0L)
                            Reminders.notify(applicationContext, 9 + f.id.toInt(), "$name — ${f.title}",
                                when { daysTo > 1 -> "Due in a week (${f.dueDay}). Booked it yet?"; daysTo == 1L -> "Tomorrow! ${f.note}"
                                    daysTo == 0L -> "Today — good luck. Ask for a copy of the results and add them to Nova."; else -> "This was due on ${f.dueDay}. Book it or update the date." })
                    }
                    // job deadlines (7 days, 2 days, today) and the next dissertation milestone (a week out)
                    app.repo.dao.jobsWithDeadlinesNow().forEach { j ->
                        val d = runCatching { LocalDate.parse(j.deadline) }.getOrNull() ?: return@forEach
                        val left = java.time.temporal.ChronoUnit.DAYS.between(today, d)
                        if (left == 7L || left == 2L || left == 0L) Reminders.notify(applicationContext, 200 + j.id.toInt(), "$name — ${j.company}",
                            if (left == 0L) "${j.role}: deadline is TODAY." else "${j.role}: deadline in $left days (${j.deadline}).")
                    }
                    app.repo.dao.nextMilestone()?.let { m ->
                        val left = runCatching { java.time.temporal.ChronoUnit.DAYS.between(today, LocalDate.parse(m.dueDay)) }.getOrNull()
                        if (left == 7L || left == 1L) Reminders.notify(applicationContext, 190, "$name — dissertation",
                            "\"${m.title}\" is due ${if (left == 1L) "tomorrow" else "in a week"} (${m.dueDay}).")
                    }
                    val walk = if (Plan.routine.walkAt != null) "Walk time. " + wx + com.aethernova.nova.data.WalkAdvisor.advise(today, w?.rainPct, w?.tempC) + " " else wx
                    Reminders.notify(applicationContext, 1, name, (fest + walk + "Open me for your briefing.").trim())
                }
                Reminders.Type.WORKOUT -> {
                    val session = Plan.sessionFor(today)
                    val week = Plan.weekNumber(s.planStart, today)
                    val done = app.repo.dao.workoutsSinceNow(today.toString()).any { it.kind == session?.code }
                    if (session != null && !done) Reminders.notify(applicationContext, 2, "$name — ${session.title}",
                        Plan.dosage(week) + " Open me for the moves.")
                }
                Reminders.Type.EVENING -> Reminders.notify(applicationContext, 3, name, app.brain.eveningLine())
                Reminders.Type.SUN -> Reminders.notify(applicationContext, 5, name,
                    if (today.monthValue in 4..9) "Sun's out-ish — get 15 minutes of daylight on your face and arms. Your vitamin D needs it."
                    else "Winter sun (Oct–Mar) is too weak in northern countries to make enough vitamin D. A pharmacist can advise whether you need a supplement.")
                Reminders.Type.BEDTIME -> Reminders.notify(applicationContext, 6, name,
                    "Wind-down time: screens away in 10 min, bed by 22:15. The 5 AM walk thanks you.")
                Reminders.Type.WATER -> {
                    val ml = app.repo.dao.waterForDayNow(today.toString())
                    if (ml < s.waterTarget / 2) Reminders.notify(applicationContext, 7, name,
                        "Only ${ml} ml of water so far. Grab a glass — tap +250 on my widget.")
                }
                Reminders.Type.WEEKLY -> {
                    val totals = app.repo.dao.dailyTotalsSince(today.minusDays(6).toString())
                    val avgP = if (totals.isEmpty()) 0.0 else totals.sumOf { it.protein } / totals.size
                    val sessions = app.repo.dao.workoutsSinceNow(today.minusDays(6).toString()).count { it.kind in listOf("A", "B", "C") }
                    Reminders.notify(applicationContext, 4, "$name — weekly recap",
                        "This week: ${sessions}/3 sessions, avg ${"%.0f".format(avgP)} g protein/day. Your weekly coach review is ready — open Life → Coach. Back up too!")
                }
            }
        }
        Reminders.schedule(applicationContext, type)
        return Result.success()
    }
}

class FocusWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val isBreak = inputData.getBoolean("break", false)
        val name = (applicationContext as NovaApp).settings.current.buddyName
        Reminders.notify(applicationContext, 8, name,
            if (isBreak) "Break's over — back to the dissertation?" else "Focus block done! Stand up, stretch, drink water. 5-minute break.")
        return Result.success()
    }
}

class NoteWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val text = inputData.getString("text") ?: "Reminder"
        val name = (applicationContext as NovaApp).settings.current.buddyName
        Reminders.notify(applicationContext, (System.currentTimeMillis() % 100000).toInt() + 100, "$name reminder", text.replaceFirstChar { it.uppercase() })
        return Result.success()
    }
}

class EyeBreakWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as NovaApp
        // the app shows its own stretch animation while open
        if (app.settings.current.eyeBreaks && !app.foreground)
            Reminders.notify(applicationContext, 11, "${app.settings.current.buddyName} — eye break", com.aethernova.nova.data.EyeBreaks.TEXT)
        return Result.success()
    }
}
