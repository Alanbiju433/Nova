package com.aethernova.nova.data

import androidx.compose.ui.graphics.Color
import java.time.LocalDate
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Nova as a pet: every healthy action earns XP, XP makes levels, levels unlock skins,
 * consistent days make a streak, and Nova gets "hungry" when you stop logging.
 * Pure functions so they're unit-tested.
 */
object Pet {
    /** XP per action and the daily cap for that kind (so spamming a button doesn't farm XP). */
    data class Rule(val xp: Int, val dailyCap: Int)
    val rules = mapOf(
        "food" to Rule(5, 40),
        "checkin" to Rule(10, 10),
        "walk" to Rule(15, 30),
        "session" to Rule(30, 30),
        "water_goal" to Rule(15, 15),
        "protein_goal" to Rule(25, 25),
        "focus" to Rule(10, 60),
    )

    /** Level curve: L2 at 50 XP, L3 at 200, L4 at 450, L5 at 800 … (50·(L−1)²). */
    fun level(xp: Int): Int = floor(sqrt(xp.coerceAtLeast(0) / 50.0)).toInt() + 1
    fun xpForLevel(level: Int): Int = 50 * (level - 1) * (level - 1)
    fun progress(xp: Int): Float {
        val l = level(xp)
        val lo = xpForLevel(l); val hi = xpForLevel(l + 1)
        return ((xp - lo).toFloat() / (hi - lo)).coerceIn(0f, 1f)
    }

    /** A day counts towards the streak with at least 30 XP (e.g. check-in + walk, or a few meals + water). */
    const val ACTIVE_DAY_XP = 30

    fun streak(xpByDay: Map<String, Int>, today: LocalDate): Int {
        fun active(d: LocalDate) = (xpByDay[d.toString()] ?: 0) >= ACTIVE_DAY_XP
        // Today still in progress: the streak survives until today ends.
        var d = if (active(today)) today else today.minusDays(1)
        var n = 0
        while (active(d)) { n++; d = d.minusDays(1) }
        return n
    }

    /** 100 = just fed (logged food). Drops ~12 points an hour, but not while you sleep (22:30–05:00 frozen-ish). */
    fun fullness(lastFoodMillis: Long?, nowMillis: Long): Int {
        if (lastFoodMillis == null) return 20
        val hours = (nowMillis - lastFoodMillis) / 3_600_000.0
        return (100 - hours * 12).toInt().coerceIn(0, 100)
    }

    data class Skin(val name: String, val unlockLevel: Int, val body: Color, val trim: Color)
    val skins = listOf(
        Skin("Classic", 1, Color(0xFF2A3350), Color(0xFF4FD1C5)),
        Skin("Sunset", 3, Color(0xFF4A2F2A), Color(0xFFF6AD55)),
        Skin("Sakura", 5, Color(0xFF4A2A3E), Color(0xFFF687B3)),
        Skin("Galaxy", 8, Color(0xFF2B2150), Color(0xFFB794F4)),
        Skin("Kerala Gold", 12, Color(0xFF3A3020), Color(0xFFECC94B)),
    )
    fun skin(name: String, level: Int): Skin =
        skins.firstOrNull { it.name == name && it.unlockLevel <= level } ?: skins.first()

    /** Personality traits that unlock as Nova levels up (fed into the AI persona and shown on the Pet card). */
    fun personality(level: Int): String = listOfNotNull(
        "friendly and encouraging",
        if (level >= 3) "drops a light joke now and then" else null,
        if (level >= 5) "hype-man: celebrates wins BIG" else null,
        if (level >= 8) "brings back inside jokes and things the user told you before" else null,
        if (level >= 12) "Kerala Gold: extra warm — if Malayalam flavour is on, uses more Malayalam words and calls the user 'muthe' sometimes" else null,
    ).joinToString("; ")

    fun nextUnlock(level: Int): String? = when {
        level < 3 -> "Lv 3: jokes"; level < 5 -> "Lv 5: hype-man mode"; level < 8 -> "Lv 8: inside jokes"; level < 12 -> "Lv 12: Kerala Gold personality"; else -> null
    }

    fun title(level: Int) = when {
        level >= 12 -> "Legend"
        level >= 8 -> "Beast mode"
        level >= 5 -> "Consistent"
        level >= 3 -> "Getting there"
        else -> "Rookie"
    }
}

/** Weekly report card — pure calculation so it can be tested. */
object Report {
    data class Week(
        val avgProtein: Double, val proteinTarget: Int,
        val avgKcal: Double, val kcalTarget: Int,
        val sessions: Int, val walkMin: Int,
        val avgWaterMl: Double, val waterTarget: Int,
        val focusMin: Int, val avgMood: Double?, val weightChange: Double?,
        val daysLogged: Int,
    )

    /** 0–100 score; each area is capped so one great area can't hide a bad one. */
    fun score(w: Week): Int {
        val protein = (w.avgProtein / w.proteinTarget).coerceIn(0.0, 1.0) * 30
        val kcal = if (w.avgKcal <= 0) 0.0 else (1 - kotlin.math.abs(w.avgKcal - w.kcalTarget) / w.kcalTarget).coerceIn(0.0, 1.0) * 15
        val training = (w.sessions / 3.0).coerceIn(0.0, 1.0) * 20
        val walking = (w.walkMin / 210.0).coerceIn(0.0, 1.0) * 10       // 30 min × 7
        val water = (w.avgWaterMl / w.waterTarget).coerceIn(0.0, 1.0) * 10
        val consistency = (w.daysLogged / 7.0).coerceIn(0.0, 1.0) * 15
        return (protein + kcal + training + walking + water + consistency).toInt()
    }

    fun grade(score: Int) = when {
        score >= 85 -> "A"
        score >= 70 -> "B"
        score >= 55 -> "C"
        score >= 40 -> "D"
        else -> "E"
    }
}
