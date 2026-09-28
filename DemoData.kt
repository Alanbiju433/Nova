package com.aethernova.nova.data

import android.content.Context
import android.content.Intent
import com.aethernova.nova.MainActivity
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/**
 * Portfolio / demo mode: a separate database full of realistic fake data (60 days), so you can
 * screenshot, screen-record or demo Nova in an interview without showing your real health data.
 * Your real data stays untouched in its own database.
 */
object DemoData {
    private const val PREFS = "nova_mode"
    const val DB_NAME = "nova_demo.db"

    fun isDemo(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("demo", false)

    fun switch(ctx: Context, demo: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("demo", demo).commit()
        ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        Runtime.getRuntime().exit(0)
    }

    suspend fun fillIfEmpty(dao: NovaDao) {
        if (dao.resultCount() > 0) return
        val r = Random(42)
        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()
        fun ts(d: LocalDate, h: Int, m: Int = 0) = d.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
        val meals = listOf(
            listOf("egg" to 3.0, "milk" to 1.0, "banana" to 1.0),
            listOf("dosa" to 3.0, "fish curry" to 1.0),
            listOf("chicken" to 1.5, "rice" to 1.0),
            listOf("skyr" to 1.0, "almond" to 2.0),
            listOf("wrap" to 2.0, "chicken" to 1.0),
            listOf("oats" to 1.0, "milk" to 1.0, "apple" to 1.0),
        )
        for (i in 59 downTo 0) {
            val d = today.minusDays(i.toLong())
            val day = d.toString()
            // food
            meals.shuffled(r).take(3 + r.nextInt(2)).forEachIndexed { k, meal ->
                meal.forEach { (key, qty) ->
                    val item = LocalFood.items.first { key in it.keys }
                    dao.insertFood(LocalFood.toEntry(item, qty, "demo", day, ts(d, 8 + k * 4)))
                }
            }
            if (r.nextInt(7) == 0) dao.insertFood(FoodEntry(timestamp = ts(d, 20), day = day, name = "Buffalo biryani", portion = "1 plate",
                kcal = 650, proteinG = 32.0, carbsG = 75.0, fatG = 22.0, source = "demo", liver = "amber"))
            // body
            val weight = 76.0 - (59 - i) * 0.055 + (r.nextDouble() - 0.5) * 0.6
            val sleep = listOf(6.0, 6.5, 7.0, 7.5, 8.0)[r.nextInt(5)]
            dao.insertCheckIn(CheckIn(timestamp = ts(d, 5, 40), day = day, sleepHours = sleep, mood = (2 + sleep.toInt() / 2 + r.nextInt(2)).coerceIn(1, 5),
                energy = 3 + r.nextInt(3), weightKg = if (i % 3 == 0) Math.round(weight * 10) / 10.0 else null))
            if (r.nextInt(10) < 8) dao.insertWorkout(WorkoutLog(timestamp = ts(d, 5, 45), day = day, kind = "walk", minutes = 30 + r.nextInt(20)))
            Plan.sessionFor(d)?.let { if (r.nextInt(10) < 7) dao.insertWorkout(WorkoutLog(timestamp = ts(d, 11), day = day, kind = it.code, minutes = 35)) }
            dao.insertWater(WaterLog(timestamp = ts(d, 15), day = day, ml = 1500 + r.nextInt(6) * 250))
            dao.insertXp(XpEvent(timestamp = ts(d, 21), day = day, kind = "demo", amount = 30 + r.nextInt(60)))
            if (r.nextInt(3) == 0) dao.insertExpense(Expense(timestamp = ts(d, 13), day = day, amount = 3.0 + r.nextInt(12), category = listOf("food", "groceries", "transport", "fun")[r.nextInt(4)]))
            if (i % 2 == 0) dao.insertFocus(FocusSession(timestamp = ts(d, 14), day = day, minutes = 25, label = "focus"))
        }
        dao.insertResult(HealthResult(day = today.minusDays(58).toString(), marker = "Vitamin D (25-OH)", value = 9.0, unit = "ng/mL", refLow = 30.0, refHigh = 100.0))
        dao.insertResult(HealthResult(day = today.minusDays(3).toString(), marker = "Vitamin D (25-OH)", value = 31.0, unit = "ng/mL", refLow = 30.0, refHigh = 100.0))
        dao.insertResult(HealthResult(day = today.minusDays(58).toString(), marker = "ALT", value = 52.0, unit = "U/L", refHigh = 40.0))
        dao.insertResult(HealthResult(day = today.minusDays(3).toString(), marker = "ALT", value = 36.0, unit = "U/L", refHigh = 40.0))
        dao.insertMed(Med(name = "Vitamin D3", dose = "1 capsule (as advised)", times = "08:00", startDay = today.minusDays(56).toString()))
        dao.insertFollowup(Followup(title = "Liver ultrasound re-scan", dueDay = today.plusDays(20).toString()))
        listOf("Supports Arsenal", "Has a coding interview next Friday", "Loves his mum's fish curry").forEach { dao.insertFact(MemoryFact(text = it)) }
        dao.insertChat(ChatMessage(timestamp = System.currentTimeMillis(), role = "nova", text = "Demo mode: this is sample data for showing Nova off. Your real data is safe in its own database."))
    }

    /** v0.9 demo content for the Work tab (added separately so older demo databases get it too). */
    suspend fun fillWorkIfEmpty(dao: NovaDao) {
        if (dao.milestoneCount() > 0) return
        val today = LocalDate.now()
        DissPace.defaultMilestones(today.plusMonths(7), today.minusMonths(1)).forEachIndexed { i, m -> dao.insertMilestone(m.copy(done = i == 0)) }
        listOf(35 to 1200, 21 to 2100, 10 to 3050, 2 to 3900).forEach { (ago, w) -> dao.insertDissLog(DissLog(day = today.minusDays(ago.toLong()).toString(), kind = "words", words = w)) }
        dao.insertDissLog(DissLog(day = today.minusDays(9).toString(), kind = "meeting", text = "Narrow the scope to one dataset. Add a baseline model before tuning."))
        dao.insertJob(JobApp(company = "Acme Analytics", role = "Graduate data scientist", status = "interview", deadline = today.plusDays(12).toString()))
        dao.insertJob(JobApp(company = "Northwind Bank", role = "Data analyst placement", status = "applied", deadline = today.plusDays(3).toString()))
        dao.insertJob(JobApp(company = "Globex", role = "ML engineer intern", status = "saved", deadline = today.plusDays(25).toString()))
        dao.insertCards(listOf(
            Flashcard(deck = "NLP", front = "What does TF-IDF down-weight?", back = "Words that appear in many documents (low inverse document frequency).", box = 2, dueDay = today.toString()),
            Flashcard(deck = "NLP", front = "What is a transformer's self-attention computing?", back = "Weighted sums of value vectors, weights = softmax(QKᵀ/√d).", box = 1, dueDay = today.toString()),
            Flashcard(deck = "ML", front = "Precision formula", back = "TP / (TP + FP)", box = 4, dueDay = today.plusDays(5).toString()),
        ))
        for (i in 1..20L) {
            val d = today.minusDays(i)
            ShiftPay.planned(d).forEach { dao.insertShift(it.copy(note = "")) }
        }
    }
}
