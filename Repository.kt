package com.aethernova.nova.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.time.LocalDate

class Repository(val dao: NovaDao, private val settings: SettingsStore) {

    fun today(): String = LocalDate.now().toString()
    fun daysAgo(n: Long): String = LocalDate.now().minusDays(n).toString()

    /** Emits things worth celebrating (level-ups, goals hit) so the UI can animate Nova. */
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events

    /** Change listener so the home-screen widget can refresh. */
    var onDataChanged: (() -> Unit)? = null

    // ---------------- XP ----------------

    suspend fun award(kind: String) {
        val rule = Pet.rules[kind] ?: return
        val day = today()
        val already = dao.xpForDayKind(day, kind)
        val amount = minOf(rule.xp, rule.dailyCap - already)
        if (amount <= 0) return
        val before = Pet.level(dao.totalXpNow())
        dao.insertXp(XpEvent(timestamp = System.currentTimeMillis(), day = day, kind = kind, amount = amount))
        val after = Pet.level(dao.totalXpNow())
        if (after > before) {
            val unlocked = Pet.skins.firstOrNull { it.unlockLevel == after }
            _events.tryEmit("Level $after! " + (unlocked?.let { "New skin unlocked: ${it.name}." } ?: Pet.title(after)))
        }
    }

    // ---------------- Food ----------------

    suspend fun addFood(entries: List<FoodEntry>) {
        if (entries.isEmpty()) return
        val before = todayTotals().second
        entries.forEach { dao.insertFood(it) }
        award("food")
        val after = todayTotals().second
        val target = settings.current.proteinTarget
        if (before < target && after >= target) {
            award("protein_goal")
            _events.tryEmit("Protein goal smashed: ${after.toInt()} g!")
        }
        onDataChanged?.invoke()
    }

    suspend fun todayTotals(): Triple<Int, Double, List<FoodEntry>> {
        val food = dao.foodForDayNow(today())
        return Triple(food.sumOf { it.kcal }, food.sumOf { it.proteinG }, food)
    }

    // ---------------- Water ----------------

    suspend fun addWater(ml: Int) {
        val before = dao.waterForDayNow(today())
        dao.insertWater(WaterLog(timestamp = System.currentTimeMillis(), day = today(), ml = ml))
        val target = settings.current.waterTarget
        if (before < target && before + ml >= target) {
            award("water_goal")
            _events.tryEmit("Water goal done — ${target / 1000.0} L!")
        }
        onDataChanged?.invoke()
    }

    suspend fun undoLastWater() {
        dao.lastWater(today())?.let { dao.deleteWater(it) }
        onDataChanged?.invoke()
    }

    // ---------------- Body ----------------

    suspend fun logWeight(kg: Double) =
        dao.insertCheckIn(CheckIn(timestamp = System.currentTimeMillis(), day = today(), weightKg = kg))

    suspend fun logCheckIn(sleep: Double?, mood: Int?, energy: Int?, weight: Double?, note: String = "") {
        dao.insertCheckIn(
            CheckIn(
                timestamp = System.currentTimeMillis(), day = today(),
                sleepHours = sleep, mood = mood, energy = energy, weightKg = weight, note = note,
            )
        )
        award("checkin")
    }

    suspend fun logWorkout(kind: String, minutes: Int, note: String = "") {
        dao.insertWorkout(WorkoutLog(timestamp = System.currentTimeMillis(), day = today(), kind = kind, minutes = minutes, note = note))
        award(if (kind == "walk") "walk" else "session")
    }

    suspend fun logFocus(minutes: Int, label: String) {
        dao.insertFocus(FocusSession(timestamp = System.currentTimeMillis(), day = today(), minutes = minutes, label = label))
        award("focus")
    }

    // ---------------- Money ----------------

    suspend fun addExpense(amount: Double, category: String, note: String) {
        dao.insertExpense(Expense(timestamp = System.currentTimeMillis(), day = today(), amount = amount, category = category, note = note))
    }

    // ---------------- Weekly report ----------------

    suspend fun weekReport(): Report.Week {
        val s = settings.current
        val from = daysAgo(6)
        val food = dao.dailyTotalsSince(from)
        val workouts = dao.workoutsSinceNow(from)
        val water = dao.waterSince(from)
        val focus = dao.focusSinceNow(from)
        val weekAgoMs = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        val checks = dao.checkInsSince(weekAgoMs - 7L * 24 * 3600 * 1000)
        val moods = checks.filter { it.timestamp >= weekAgoMs }.mapNotNull { it.mood }
        val weights = checks.mapNotNull { c -> c.weightKg?.let { c.timestamp to it } }
        val weightChange = if (weights.size >= 2) weights.last().second - weights.first().second else null
        val days = food.size.coerceAtLeast(1)
        return Report.Week(
            avgProtein = food.sumOf { it.protein } / days, proteinTarget = s.proteinTarget,
            avgKcal = food.sumOf { it.kcal }.toDouble() / days, kcalTarget = s.kcalTarget,
            sessions = workouts.count { it.kind in listOf("A", "B", "C") },
            walkMin = workouts.filter { it.kind == "walk" }.sumOf { it.minutes },
            avgWaterMl = water.sumOf { it.ml } / 7.0, waterTarget = s.waterTarget,
            focusMin = focus.sumOf { it.minutes },
            avgMood = moods.takeIf { it.isNotEmpty() }?.average(),
            weightChange = weightChange,
            daysLogged = food.size,
        )
    }

    /** Placeholder dissertation milestones (only if none exist yet). */
    suspend fun seedMilestones(due: String) {
        if (dao.milestoneCount() > 0) return
        val d = runCatching { java.time.LocalDate.parse(due) }.getOrNull() ?: return
        DissPace.defaultMilestones(d).forEach { dao.insertMilestone(it) }
    }

    /** Starting weight from setup, so trends have a first point. */
    suspend fun logStartWeight(kg: Double) {
        if (kg in 30.0..300.0) dao.insertCheckIn(CheckIn(timestamp = System.currentTimeMillis(), day = today(), weightKg = kg, note = "Starting weight"))
    }
}
