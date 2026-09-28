package com.aethernova.nova.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ============================ Liver score ============================

/**
 * Green / amber / red for how kind a food is to a (mildly) fatty liver: oil, sugar, refined carbs
 * and alcohol push it to red; lean protein, vegetables, fruit, oats and nuts are green.
 * The AI gives its own rating for photos/chat; this is the offline fallback.
 */
object LiverScore {
    private val red = listOf("fried", "fry", "pakora", "pakoda", "bajji", "samosa", "chips", "fries", "crisps", "parotta", "porotta", "puri",
        "bhatura", "pizza", "burger", "nugget", "kfc", "doughnut", "donut", "cake", "chocolate", "biscuit", "sweet", "payasam", "halwa", "ladoo",
        "jalebi", "ice cream", "coke", "cola", "soda", "juice", "beer", "wine", "whisky", "alcohol", "energy drink", "mayo")
    private val amber = listOf("biryani", "curry", "rice", "choru", "pickle", "achar", "white bread", "naan", "noodles", "pasta", "sausage", "bacon",
        "cheese", "butter", "ghee", "beef", "mutton", "cream", "wrap", "chapati", "chapathi", "whole milk", "milk")
    private val green = listOf("egg", "chicken breast", "grilled", "boiled", "steamed", "fish", "squid", "skyr", "yogurt", "curd", "oats", "salad",
        "vegetable", "veg", "spinach", "broccoli", "dal", "lentil", "chickpea", "beans", "apple", "pear", "orange", "banana", "berries", "almond",
        "cashew", "nuts", "atta dosa", "idli", "sprouts", "tofu", "paneer", "water", "black coffee", "tea")

    fun local(name: String): String {
        val n = name.lowercase()
        return when {
            red.any { it in n } -> "red"
            green.any { it in n } && amber.none { it in n && it != "milk" } -> "green"
            amber.any { it in n } -> "amber"
            green.any { it in n } -> "green"
            else -> "amber"
        }
    }

    fun normalise(ai: String?, name: String) = ai?.lowercase()?.trim()?.takeIf { it in listOf("green", "amber", "red") } ?: local(name)

    /** Share of green / red entries for a set of foods. */
    data class Mix(val green: Int, val amber: Int, val red: Int) {
        val total get() = green + amber + red
        val greenPct get() = if (total == 0) 0 else green * 100 / total
    }
    fun mix(food: List<FoodEntry>) = Mix(
        food.count { (it.liver.ifBlank { local(it.name) }) == "green" },
        food.count { (it.liver.ifBlank { local(it.name) }) == "amber" },
        food.count { (it.liver.ifBlank { local(it.name) }) == "red" },
    )
}

// ============================ Sleep debt ============================

object SleepDebt {
    const val TARGET = 7.5

    /** Last reported sleep for each date. */
    fun nights(checkIns: List<CheckIn>): Map<LocalDate, Double> = checkIns.filter { it.sleepHours != null }
        .sortedBy { it.timestamp }.associate { LocalDate.parse(it.day) to it.sleepHours!! }

    /** Hours short over the last [days] logged nights (surplus nights pay back up to 1 h each). */
    fun debt(nights: Map<LocalDate, Double>, today: LocalDate, days: Int = 7): Double {
        var d = 0.0
        for (i in 0 until days) {
            val h = nights[today.minusDays(i.toLong())] ?: continue
            d += if (h < TARGET) TARGET - h else -minOf(h - TARGET, 1.0)
        }
        return maxOf(0.0, d)
    }

    fun comment(debt: Double) = when {
        debt < 1 -> "Sleep's on track."
        debt < 4 -> "You're ${"%.1f".format(debt)} h short this week — an earlier night or a 20-minute nap would help."
        else -> "Sleep debt ${"%.1f".format(debt)} h — that's hurting recovery and focus. Skip the late phone and go to bed early tonight."
    }
}

// ============================ Insights ============================

object Insights {
    data class Insight(val text: String, val strength: Int)   // strength: 1 weak .. 3 strong

    private fun mean(xs: List<Double>) = if (xs.isEmpty()) Double.NaN else xs.average()
    fun pearson(xs: List<Double>, ys: List<Double>): Double? {
        if (xs.size != ys.size || xs.size < 5) return null
        val mx = xs.average(); val my = ys.average()
        val num = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) }
        val den = sqrt(xs.sumOf { (it - mx) * (it - mx) } * ys.sumOf { (it - my) * (it - my) })
        return if (den == 0.0) null else num / den
    }

    /** Least-squares slope of weight in kg per week. */
    fun weightSlopePerWeek(points: List<Pair<LocalDate, Double>>): Double? {
        if (points.size < 3) return null
        val x0 = points.first().first.toEpochDay()
        val xs = points.map { (it.first.toEpochDay() - x0).toDouble() }; val ys = points.map { it.second }
        val mx = xs.average(); val my = ys.average()
        val den = xs.sumOf { (it - mx) * (it - mx) }
        if (den == 0.0) return null
        return xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / den * 7
    }

    private fun isLateShiftDay(d: LocalDate) = Plan.lateWorkDay(d)
    private fun isLongShiftDay(d: LocalDate) = Plan.longWorkDay(d)

    fun compute(
        today: LocalDate, food: List<DayTotals>, checkIns: List<CheckIn>, workouts: List<WorkoutLog>, proteinTarget: Int, foods: List<FoodEntry> = emptyList(), liver: Boolean = true,
    ): List<Insight> {
        val out = mutableListOf<Insight>()
        val nights = SleepDebt.nights(checkIns)

        // 1. Sleep after late shifts (sleep logged the next morning belongs to the previous night)
        val afterShift = nights.filterKeys { isLateShiftDay(it.minusDays(1)) }.values.toList()
        val otherNights = nights.filterKeys { !isLateShiftDay(it.minusDays(1)) }.values.toList()
        if (afterShift.size >= 3 && otherNights.size >= 2) {
            val diff = mean(otherNights) - mean(afterShift)
            if (abs(diff) >= 0.4) out += Insight(
                if (diff > 0) "You sleep about ${(diff * 60).roundToInt()} min less after late shifts (${"%.1f".format(mean(afterShift))} h vs ${"%.1f".format(mean(otherNights))} h). A 20-minute wind-down when you get home could help."
                else "Interesting — you sleep ${(-diff * 60).roundToInt()} min MORE after late shifts. Tiredness helps!", if (afterShift.size >= 6) 3 else 2)
        }

        // 2. Protein on long day shifts
        val byDay = food.filter { it.kcal > 0 }.associateBy { LocalDate.parse(it.day) }
        val chick = byDay.filterKeys { isLongShiftDay(it) }.values.map { it.protein }
        val rest = byDay.filterKeys { !isLongShiftDay(it) }.values.map { it.protein }
        if (chick.size >= 3 && rest.size >= 3) {
            val diff = mean(rest) - mean(chick)
            if (diff >= 10) out += Insight("Protein drops by ${diff.roundToInt()} g on long shift days. Pack 2 boiled eggs or a yogurt for your break.", 2)
        }

        // 3. Weakest weekday for protein
        if (byDay.size >= 14) {
            val perDow = byDay.entries.groupBy { it.key.dayOfWeek }.mapValues { e -> e.value.map { it.value.protein }.average() }
            perDow.minByOrNull { it.value }?.let { (dow, p) ->
                if (p < proteinTarget * 0.8) out += Insight("${dow.getDisplayName(TextStyle.FULL, Locale.getDefault())}s are your lowest protein day (~${p.roundToInt()} g).", 1)
            }
        }

        // 4. Mood vs sleep
        val pairs = checkIns.filter { it.mood != null && it.sleepHours != null }
        pearson(pairs.map { it.sleepHours!! }, pairs.map { it.mood!!.toDouble() })?.let { r ->
            if (r >= 0.35) out += Insight("Your mood is clearly better after longer sleep (correlation ${"%.2f".format(r)} over ${pairs.size} days).", if (pairs.size >= 14) 3 else 2)
        }

        // 5. Walk days vs protein / mood
        val walkDays = workouts.filter { it.kind == "walk" }.map { LocalDate.parse(it.day) }.toSet()
        val moodWalk = checkIns.filter { it.mood != null && LocalDate.parse(it.day) in walkDays }.map { it.mood!!.toDouble() }
        val moodNo = checkIns.filter { it.mood != null && LocalDate.parse(it.day) !in walkDays }.map { it.mood!!.toDouble() }
        if (moodWalk.size >= 3 && moodNo.size >= 3 && mean(moodWalk) - mean(moodNo) >= 0.5)
            out += Insight("On days you walk, your mood averages ${"%.1f".format(mean(moodWalk))}/5 vs ${"%.1f".format(mean(moodNo))} without. The walks are doing their job.", 2)

        // 6. Weight trend over the last 4 weeks
        val weights = checkIns.filter { it.weightKg != null && !LocalDate.parse(it.day).isBefore(today.minusDays(28)) }
            .map { LocalDate.parse(it.day) to it.weightKg!! }
        weightSlopePerWeek(weights)?.let { s ->
            out += Insight(when {
                s < -0.9 -> "Losing ${"%.1f".format(-s)} kg/week — faster than ideal for keeping muscle. Eat a bit more (+100–150 kcal)."
                s <= -0.25 -> "Losing ${"%.1f".format(-s)} kg/week — right in the healthy range for losing fat while keeping muscle."
                s < 0.15 -> "Weight is flat over 4 weeks (${"%+.1f".format(s)} kg/week). If that's not the plan, trim 100 kcal or add 10 min to walks."
                else -> "Weight is going up ${"%.1f".format(s)} kg/week. If that's not the plan, check portions of rice, bread and snacks."
            }, if (weights.size >= 6) 3 else 1)
        }

        // 7. Liver-friendly food this week vs last
        if (liver && foods.isNotEmpty()) {
            val thisWeek = LiverScore.mix(foods.filter { !LocalDate.parse(it.day).isBefore(today.minusDays(6)) })
            val lastWeek = LiverScore.mix(foods.filter { LocalDate.parse(it.day).let { d -> d.isBefore(today.minusDays(6)) && !d.isBefore(today.minusDays(13)) } })
            if (thisWeek.total >= 8) out += Insight("${thisWeek.greenPct}% of this week's meals were liver-friendly (green)" +
                (if (lastWeek.total >= 8) ", vs ${lastWeek.greenPct}% last week." else ".") + if (thisWeek.red > 3) " ${thisWeek.red} red items — the easiest wins are there." else "", 2)
        }
        return out.sortedByDescending { it.strength }
    }
}

// ============================ Weekly review (rules fallback) ============================

object WeeklyRules {
    data class Proposal(val kcal: Int?, val protein: Int?, val workout: String, val summary: String)

    /** Safe, conservative adjustments when the AI isn't available. */
    fun propose(w: Report.Week, slopeKgPerWeek: Double?): Proposal {
        var kcal: Int? = null
        val notes = mutableListOf<String>()
        when {
            slopeKgPerWeek != null && slopeKgPerWeek < -0.9 -> { kcal = (w.kcalTarget + 150).coerceAtMost(2400); notes += "Losing too fast — adding 150 kcal to protect muscle." }
            slopeKgPerWeek != null && slopeKgPerWeek > -0.1 && w.daysLogged >= 5 && w.avgKcal <= w.kcalTarget * 1.05 -> {
                kcal = (w.kcalTarget - 100).coerceAtLeast(1600); notes += "No loss with good logging — trimming 100 kcal."
            }
        }
        if (w.avgProtein < w.proteinTarget * 0.8) notes += "Protein averaged ${w.avgProtein.toInt()} g — focus: an egg or skyr with every meal."
        val workout = when {
            w.sessions >= 3 -> { notes += "All 3 sessions done — stepping workouts up a level."; "harder" }
            w.sessions <= 1 -> { notes += "Only ${w.sessions} session(s) — keeping the same level; aim for 3."; "same" }
            else -> "same"
        }
        return Proposal(kcal, null, workout, notes.joinToString(" ").ifBlank { "Steady week. Keep the same plan." })
    }
}

// ============================ Gym progression ============================

object Progression {
    val exercises = listOf("Squat", "Bench press", "Deadlift", "Overhead press", "Lat pulldown", "Seated cable row",
        "Leg press", "Romanian deadlift", "Dumbbell curl", "Tricep pushdown", "Plank (sec)")

    fun epley1RM(weight: Double, reps: Int) = if (reps <= 1) weight else weight * (1 + reps / 30.0)

    /**
     * Double progression, 8–12 reps: when every working set of the last session hit 12, add weight
     * (5 kg lower body, 2.5 kg upper). If the last two sessions both had a set under 8, drop 10%.
     */
    fun suggest(history: List<GymSet>, exercise: String): String {
        val sessions = history.filter { it.exercise == exercise }.groupBy { it.day }.toSortedMap().values.toList()
        if (sessions.isEmpty()) return "First time: pick a weight you can lift 12 times with good form, do 3 sets."
        val last = sessions.last()
        val top = last.maxOf { it.weightKg }
        val working = last.filter { it.weightKg == top }
        val step = if (exercise in listOf("Squat", "Deadlift", "Leg press", "Romanian deadlift")) 5.0 else 2.5
        val lastTwoFailed = sessions.takeLast(2).size == 2 && sessions.takeLast(2).all { s -> s.any { it.reps < 8 } }
        return when {
            lastTwoFailed -> "Two tough sessions — deload to ${fmt(top * 0.9)} kg and build back up."
            working.size >= 2 && working.all { it.reps >= 12 } -> "Nailed it! Go up to ${fmt(top + step)} kg next time."
            else -> "Stay at ${fmt(top)} kg and add 1 rep per set (aim 3×12)."
        }
    }

    private fun fmt(d: Double) = if (d % 1.0 == 0.0) d.toInt().toString() else "%.1f".format(d)
}
