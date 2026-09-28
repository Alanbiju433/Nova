package com.aethernova.nova.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
import com.aethernova.nova.data.Med
import com.aethernova.nova.data.MedLog
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/** Pure scheduling + adherence maths for supplements/meds. */
object MedPlan {
    fun slots(m: Med): List<LocalTime> = m.times.split(',').mapNotNull { runCatching { LocalTime.parse(it.trim()) }.getOrNull() }.sorted()

    fun activeOn(m: Med, d: LocalDate): Boolean {
        if (!m.active) return false
        val start = runCatching { LocalDate.parse(m.startDay) }.getOrDefault(LocalDate.MIN)
        val end = m.endDay.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (d.isBefore(start) || (end != null && d.isAfter(end))) return false
        return m.weekday == 0 || d.dayOfWeek.value == m.weekday
    }

    fun nextTime(m: Med, slot: LocalTime, now: LocalDateTime): LocalDateTime? {
        for (i in 0..14L) {
            val d = now.toLocalDate().plusDays(i)
            val at = d.atTime(slot)
            if (activeOn(m, d) && at.isAfter(now.plusSeconds(20))) return at
        }
        return null
    }

    /** % of due doses taken between [from] and [to] (inclusive), only counting slots already passed. */
    fun adherence(meds: List<Med>, logs: List<MedLog>, from: LocalDate, to: LocalDate, now: LocalDateTime): Int? {
        var due = 0; var taken = 0
        val done = logs.map { Triple(it.medId, it.day, it.slot) }.toSet()
        var d = from
        while (!d.isAfter(to)) {
            for (m in meds) if (activeOn(m, d)) for (s in slots(m)) {
                if (d.atTime(s).isAfter(now)) continue
                due++
                if (Triple(m.id, d.toString(), s.toString()) in done) taken++
            }
            d = d.plusDays(1)
        }
        return if (due == 0) null else taken * 100 / due
    }
}

object MedReminders {
    private const val CH = "nova_meds"
    const val ACTION_TAKEN = "com.aethernova.nova.MED_TAKEN"
    const val ACTION_SNOOZE = "com.aethernova.nova.MED_SNOOZE"

    fun channel(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)
        .createNotificationChannel(NotificationChannel(CH, "Supplements & meds", NotificationManager.IMPORTANCE_HIGH))

    private fun work(id: Long, slot: String) = "med_${id}_$slot"

    fun schedule(ctx: Context, m: Med, slot: LocalTime, overrideAt: LocalDateTime? = null) {
        val wm = WorkManager.getInstance(ctx)
        val at = overrideAt ?: MedPlan.nextTime(m, slot, LocalDateTime.now()) ?: run { wm.cancelUniqueWork(work(m.id, slot.toString())); return }
        val req = OneTimeWorkRequestBuilder<MedWorker>()
            .setInitialDelay(Duration.between(LocalDateTime.now(), at).toMillis().coerceAtLeast(1000), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("med" to m.id, "slot" to slot.toString(), "day" to at.toLocalDate().toString()))
            .build()
        wm.enqueueUniqueWork(work(m.id, slot.toString()), ExistingWorkPolicy.REPLACE, req)
    }

    fun scheduleAll(ctx: Context) {
        val app = ctx.applicationContext as NovaApp
        app.appScope.launch {
            channel(ctx)
            app.repo.dao.activeMedsNow().forEach { m -> MedPlan.slots(m).forEach { schedule(ctx, m, it) } }
        }
    }

    fun cancel(ctx: Context, m: Med) = MedPlan.slots(m).forEach { WorkManager.getInstance(ctx).cancelUniqueWork(work(m.id, it.toString())) }

    fun notify(ctx: Context, m: Med, slot: String, day: String) {
        val id = (m.id * 100 + slot.hashCode() % 100).toInt()
        fun pi(action: String, code: Int) = PendingIntent.getBroadcast(ctx, code, Intent(ctx, MedReceiver::class.java).setAction(action)
            .putExtra("med", m.id).putExtra("slot", slot).putExtra("day", day).putExtra("id", id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CH).setSmallIcon(R.drawable.ic_stat_nova)
            .setContentTitle("💊 ${m.name}").setContentText("${m.dose} — as your pharmacist/doctor advised.")
            .setContentIntent(PendingIntent.getActivity(ctx, id, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(0, "Taken ✓", pi(ACTION_TAKEN, id))
            .addAction(0, "Snooze 15 min", pi(ACTION_SNOOZE, id + 50_000))
            .setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).build()
        if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) runCatching { NotificationManagerCompat.from(ctx).notify(id, n) }
    }
}

class MedWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as NovaApp
        val m = app.repo.dao.med(inputData.getLong("med", -1)) ?: return Result.success()
        val slot = inputData.getString("slot") ?: return Result.success()
        val day = inputData.getString("day") ?: LocalDate.now().toString()
        val already = app.repo.dao.medLogsSinceNow(day).any { it.medId == m.id && it.day == day && it.slot == slot }
        if (m.active && !already) MedReminders.notify(applicationContext, m, slot, day)
        runCatching { MedReminders.schedule(applicationContext, m, LocalTime.parse(slot)) }
        return Result.success()
    }
}

class MedReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext as NovaApp
        val medId = intent.getLongExtra("med", -1); val slot = intent.getStringExtra("slot") ?: return
        val day = intent.getStringExtra("day") ?: LocalDate.now().toString()
        NotificationManagerCompat.from(ctx).cancel(intent.getIntExtra("id", 0))
        val pending = goAsync()
        app.appScope.launch {
            try {
                val m = app.repo.dao.med(medId) ?: return@launch
                when (intent.action) {
                    MedReminders.ACTION_TAKEN -> app.repo.dao.insertMedLog(MedLog(medId = medId, day = day, slot = slot, takenAt = System.currentTimeMillis()))
                    MedReminders.ACTION_SNOOZE -> MedReminders.schedule(ctx, m, LocalTime.parse(slot), LocalDateTime.now().plusMinutes(15))
                }
            } finally { pending.finish() }
        }
    }
}
