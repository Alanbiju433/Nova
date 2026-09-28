package com.aethernova.nova.data

import kotlin.math.roundToInt

/**
 * Starting targets from the user's own numbers (Mifflin–St Jeor + activity factor).
 * These are estimates to begin with; the weekly review nudges them by at most ±150 kcal.
 */
object Targets {
    enum class Goal(val label: String, val kcalFactor: Double, val proteinPerKg: Double) {
        LOSE("Lose fat", 0.80, 1.8), RECOMP("Lose fat & build muscle", 0.88, 1.8), MUSCLE("Build muscle", 1.08, 1.8),
        MAINTAIN("Stay as I am", 1.0, 1.4), FIT("Get fitter / more energy", 1.0, 1.4),
    }

    enum class Activity(val label: String, val factor: Double) {
        LOW("Mostly sitting", 1.2), LIGHT("On my feet a bit / light walks", 1.375),
        MODERATE("Active job or daily walks", 1.55), HIGH("Very active / physical job + training", 1.725),
    }

    data class Result(val kcal: Int, val protein: Int, val waterMl: Int, val bmr: Int?)

    fun bmr(sex: String, kg: Double, cm: Int, age: Int): Double =
        10 * kg + 6.25 * cm - 5 * age + when (sex) { "male" -> 5.0; "female" -> -161.0; else -> -78.0 }

    fun compute(sex: String, kg: Double?, cm: Int, age: Int, activity: Activity, goal: Goal): Result {
        val floor = if (sex == "female") 1400 else 1600
        if (kg == null || kg < 30 || cm < 120 || age < 18) return Result(2000.coerceAtLeast(floor), 100, 2000, null)
        val b = bmr(sex, kg, cm, age)
        val kcal = ((b * activity.factor * goal.kcalFactor) / 50).roundToInt() * 50
        val protein = ((kg * goal.proteinPerKg) / 5).roundToInt() * 5
        val water = ((kg * 35) / 250).roundToInt() * 250
        return Result(kcal.coerceIn(floor, 4000), protein.coerceIn(60, 220), water.coerceIn(1500, 4000), b.roundToInt())
    }
}
