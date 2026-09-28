package com.aethernova.nova.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "food")
data class FoodEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val day: String,          // yyyy-MM-dd
    val name: String,
    val portion: String,
    val kcal: Int,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val source: String,       // photo | chat | manual | quick
    @androidx.room.ColumnInfo(defaultValue = "")
    val liver: String = "",   // green | amber | red — how kind the meal is to the liver (v0.8, optional rating)
)

@Entity(tableName = "checkin")
data class CheckIn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val day: String,
    val sleepHours: Double? = null,
    val mood: Int? = null,      // 1..5
    val energy: Int? = null,    // 1..5
    val weightKg: Double? = null,
    val note: String = "",
)

@Entity(tableName = "workout")
data class WorkoutLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val day: String,
    val kind: String,          // walk | A | B | C | other
    val minutes: Int,
    val note: String = "",
)

@Entity(tableName = "health_result")
data class HealthResult(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val day: String,
    val marker: String,
    val value: Double?,        // null for text-only results such as a scan
    val unit: String,
    val refLow: Double? = null,
    val refHigh: Double? = null,
    val note: String = "",
) {
    val flag: String
        get() = when {
            value == null -> ""
            refLow != null && value < refLow -> "LOW"
            refHigh != null && value > refHigh -> "HIGH"
            else -> "OK"
        }
}

@Entity(tableName = "chat")
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val role: String,          // user | nova
    val text: String,
)

data class DayTotals(
    val day: String,
    val kcal: Int,
    val protein: Double,
)

// ---------- v2 (Nova 0.3) ----------

@Entity(tableName = "xp_event")
data class XpEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val day: String,
    val kind: String,          // food | checkin | walk | session | water_goal | protein_goal | focus
    val amount: Int,
)

@Entity(tableName = "water")
data class WaterLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val day: String,
    val ml: Int,
)

@Entity(tableName = "focus_session")
data class FocusSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val day: String,
    val minutes: Int,
    val label: String = "",
)

data class DayXp(val day: String, val xp: Int)
data class DayWater(val day: String, val ml: Int)

// ---------- v3 (Nova 0.4) ----------

@Entity(tableName = "grocery_item")
data class GroceryItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val qty: String = "",
    val done: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "progress_photo")
data class ProgressPhoto(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val day: String,
    val path: String,          // app-private file, never uploaded
    val weightKg: Double? = null,
)

// ---------- v4 (Nova 0.5) ----------

@Entity(tableName = "expense")
data class Expense(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val day: String,
    val amount: Double,        // GBP
    val category: String,      // food | groceries | transport | bills | fun | family | other
    val note: String = "",
)

/** Things the user tells Nova about their life, so it can remember and follow up. Viewable and deletable. */
@Entity(tableName = "memory_fact")
data class MemoryFact(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
)

// ---------- v5 (Nova 0.7) ----------

/** A supplement or medicine exactly as prescribed / advised by a pharmacist or doctor. Nova never suggests doses. */
@Entity(tableName = "med")
data class Med(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val dose: String,            // free text as written on the box / by the pharmacist
    val times: String,           // "08:00,20:00"
    val weekday: Int = 0,        // 0 = every day, 1..7 = Mon..Sun (weekly doses)
    val startDay: String,
    val endDay: String = "",     // "" = ongoing
    val active: Boolean = true,
)

@Entity(tableName = "med_log")
data class MedLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val medId: Long,
    val day: String,
    val slot: String,            // "08:00"
    val takenAt: Long,
)

/** Planned tests / appointments to remember (e.g. a blood re-test). */
@Entity(tableName = "followup")
data class Followup(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val dueDay: String,
    val note: String = "",
    val done: Boolean = false,
)

// ---------- v6 (Nova 0.8) ----------

@Entity(tableName = "gym_set")
data class GymSet(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val day: String,
    val exercise: String,
    val weightKg: Double,
    val reps: Int,
)

// ---------- v7 (Nova 0.9) ----------

@Entity(tableName = "job_app")
data class JobApp(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val company: String,
    val role: String,
    val status: String = "saved",   // saved | applied | interview | offer | rejected
    val deadline: String = "",      // yyyy-MM-dd or ""
    val link: String = "",
    val notes: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "diss_milestone")
data class DissMilestone(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val dueDay: String,
    val done: Boolean = false,
)

/** kind = "words" (value = total word count) or "meeting" (supervisor notes in text). */
@Entity(tableName = "diss_log")
data class DissLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val day: String,
    val kind: String,
    val words: Int = 0,
    val text: String = "",
)

@Entity(tableName = "flashcard")
data class Flashcard(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deck: String,
    val front: String,
    val back: String,
    val box: Int = 1,           // Leitner box 1..5
    val dueDay: String,
)

@Entity(tableName = "shift")
data class Shift(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val day: String,
    val job: String,            // job name from the routine (or "Other")
    val hours: Double,
    val rate: Double,           // money per hour
    val note: String = "",
)
