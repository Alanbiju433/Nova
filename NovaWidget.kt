package com.aethernova.nova.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.aethernova.nova.MainActivity
import com.aethernova.nova.NovaApp
import com.aethernova.nova.R
import com.aethernova.nova.data.Pet
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

/** Home-screen widget: Nova's face, today's protein/kcal/water, and a +250 ml water button. */
class NovaWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_WATER) {
            val pending = goAsync()
            val app = context.applicationContext as NovaApp
            app.appScope.launch {
                try { app.repo.addWater(250) } finally { pending.finish() }
            }
        }
    }

    companion object {
        const val ACTION_WATER = "com.aethernova.nova.ADD_WATER"

        fun faceFor(proteinFrac: Double, fullness: Int, now: LocalTime): String = when {
            now >= LocalTime.of(22, 30) || now < LocalTime.of(5, 0) -> "😴"
            fullness < 25 -> "🥺"
            proteinFrac >= 1.0 -> "🤩"
            proteinFrac >= 0.7 -> "😄"
            proteinFrac >= 0.4 -> "🙂"
            else -> "😐"
        }

        fun refresh(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, NovaWidget::class.java))
            if (ids.isEmpty()) return
            val app = context.applicationContext as NovaApp
            app.appScope.launch {
                runCatching {
                    val s = app.settings.current
                    val today = LocalDate.now().toString()
                    val food = app.repo.dao.foodForDayNow(today)
                    val protein = food.sumOf { it.proteinG }
                    val kcal = food.sumOf { it.kcal }
                    val water = app.repo.dao.waterForDayNow(today)
                    val xp = app.repo.dao.totalXpNow()
                    val streak = Pet.streak(app.repo.dao.xpByDay().associate { it.day to it.xp }, LocalDate.now())
                    val lastFood = food.maxOfOrNull { it.timestamp }
                    val face = faceFor(protein / s.proteinTarget, Pet.fullness(lastFood, System.currentTimeMillis()), LocalTime.now())

                    val views = RemoteViews(context.packageName, R.layout.widget_nova).apply {
                        setTextViewText(R.id.widget_face, face)
                        setTextViewText(R.id.widget_title, "${s.buddyName} · Lv ${Pet.level(xp)}" + if (streak > 0) " · 🔥$streak" else "")
                        setTextViewText(R.id.widget_protein, "Protein ${protein.toInt()} / ${s.proteinTarget} g")
                        setTextViewText(R.id.widget_line2, "$kcal / ${s.kcalTarget} kcal · 💧 $water ml")
                        setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(
                            context, 10, Intent(context, MainActivity::class.java),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                        setOnClickPendingIntent(R.id.widget_water, PendingIntent.getBroadcast(
                            context, 11, Intent(context, NovaWidget::class.java).setAction(ACTION_WATER),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                    }
                    mgr.updateAppWidget(ids, views)
                }
            }
        }
    }
}
