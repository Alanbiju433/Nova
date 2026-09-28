package com.aethernova.nova.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.DayOfWeek.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/** What a block in the weekly routine is — decides how Nova reminds you about it. */
enum class Kind(val label: String) {
    WORK("Work shift"), CLASS("Class / lecture"), ONLINE("Online class"), WORSHIP("Worship"),
    SOCIAL("Social"), OTHER("Other"), WALK("Walk"), WORKOUT("Workout"), BED("Bedtime");
    companion object { fun from(s: String?) = entries.firstOrNull { it.name == s } ?: OTHER }
}

/**
 * One repeating thing in the week, set up by the user in the schedule builder.
 * [onlyWeek]/[skipWeek] (1–5) handle "only / not on the 4th Sunday of the month" style rules; 0 = every week.
 */
data class RoutineItem(
    val days: Set<DayOfWeek>, val start: LocalTime, val end: LocalTime, val kind: Kind, val label: String,
    val travelMin: Int = 15, val breakMin: Int = 0, val rate: Double = 0.0, val onlyWeek: Int = 0, val skipWeek: Int = 0,
) {
    fun activeOn(d: LocalDate): Boolean {
        if (d.dayOfWeek !in days) return false
        val w = Plan.weekOfMonth(d)
        return (onlyWeek == 0 || w == onlyWeek) && (skipWeek == 0 || w != skipWeek)
    }
}

data class WorkoutSlot(val day: DayOfWeek, val time: LocalTime, val skipWeek: Int = 0)

/** The user's week. Stored as JSON in settings; empty until they fill it in during setup. */
data class Routine(
    val walkAt: LocalTime? = null, val walkMin: Int = 30, val bedtime: LocalTime = LocalTime.of(23, 0),
    val items: List<RoutineItem> = emptyList(), val workouts: List<WorkoutSlot> = emptyList(),
) {
    val isEmpty get() = items.isEmpty() && workouts.isEmpty() && walkAt == null

    fun toJson(): JSONObject = JSONObject()
        .put("walkAt", walkAt?.toString() ?: "").put("walkMin", walkMin).put("bedtime", bedtime.toString())
        .put("items", JSONArray(items.map { i ->
            JSONObject().put("days", JSONArray(i.days.sorted().map { it.name })).put("start", i.start.toString()).put("end", i.end.toString())
                .put("kind", i.kind.name).put("label", i.label).put("travel", i.travelMin).put("break", i.breakMin).put("rate", i.rate)
                .put("onlyWeek", i.onlyWeek).put("skipWeek", i.skipWeek)
        }))
        .put("workouts", JSONArray(workouts.map { JSONObject().put("day", it.day.name).put("time", it.time.toString()).put("skipWeek", it.skipWeek) }))

    companion object {
        private fun t(s: String) = LocalTime.parse(s)

        fun parse(json: String?): Routine {
            if (json.isNullOrBlank()) return Routine()
            return runCatching {
                val o = JSONObject(json)
                val items = o.optJSONArray("items") ?: JSONArray()
                val wos = o.optJSONArray("workouts") ?: JSONArray()
                Routine(
                    walkAt = o.optString("walkAt").takeIf { it.isNotBlank() }?.let { t(it) },
                    walkMin = o.optInt("walkMin", 30).coerceIn(5, 180),
                    bedtime = o.optString("bedtime").takeIf { it.isNotBlank() }?.let { t(it) } ?: LocalTime.of(23, 0),
                    items = (0 until items.length()).mapNotNull { k ->
                        val i = items.optJSONObject(k) ?: return@mapNotNull null
                        val days = i.optJSONArray("days")?.let { a -> (0 until a.length()).mapNotNull { runCatching { DayOfWeek.valueOf(a.getString(it)) }.getOrNull() } }.orEmpty().toSet()
                        RoutineItem(days, t(i.getString("start")), t(i.getString("end")), Kind.from(i.optString("kind")), i.optString("label").take(60),
                            i.optInt("travel", 15).coerceIn(0, 180), i.optInt("break", 0).coerceIn(0, 180), i.optDouble("rate", 0.0).takeIf { !it.isNaN() } ?: 0.0,
                            i.optInt("onlyWeek", 0).coerceIn(0, 5), i.optInt("skipWeek", 0).coerceIn(0, 5))
                    }.filter { it.days.isNotEmpty() && it.end > it.start },
                    workouts = (0 until wos.length()).mapNotNull { k ->
                        val w = wos.optJSONObject(k) ?: return@mapNotNull null
                        runCatching { WorkoutSlot(DayOfWeek.valueOf(w.getString("day")), t(w.getString("time")), w.optInt("skipWeek", 0).coerceIn(0, 5)) }.getOrNull()
                    }.distinctBy { it.day }.sortedBy { it.day },
                )
            }.getOrDefault(Routine())
        }

        /** A sample student-with-part-time-jobs week, used for demo mode and tests. Not anyone's real schedule. */
        val EXAMPLE = Routine(
            walkAt = t("05:00"), walkMin = 45, bedtime = t("22:15"),
            items = listOf(
                RoutineItem(setOf(MONDAY), t("16:00"), t("18:00"), Kind.CLASS, "Project seminar", travelMin = 20),
                RoutineItem(setOf(MONDAY), t("20:00"), t("21:00"), Kind.SOCIAL, "Society meet-up", travelMin = 15),
                RoutineItem(setOf(TUESDAY, WEDNESDAY, SATURDAY), t("11:00"), t("17:00"), Kind.WORK, "Café shift", travelMin = 35, breakMin = 30, rate = 11.5),
                RoutineItem(setOf(SUNDAY), t("11:00"), t("17:00"), Kind.WORK, "Café shift", travelMin = 35, breakMin = 30, rate = 11.5, skipWeek = 4),
                RoutineItem(setOf(TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY), t("17:30"), t("21:00"), Kind.WORK, "Restaurant shift", travelMin = 25, rate = 12.71),
                RoutineItem(setOf(THURSDAY), t("13:00"), t("16:00"), Kind.CLASS, "Lab practical", travelMin = 30),
                RoutineItem(setOf(FRIDAY), t("09:00"), t("12:00"), Kind.CLASS, "Lab practical", travelMin = 30),
                RoutineItem(setOf(FRIDAY), t("13:00"), t("14:00"), Kind.ONLINE, "Online lecture"),
                RoutineItem(setOf(SUNDAY), t("14:00"), t("18:30"), Kind.WORSHIP, "Church", travelMin = 20, onlyWeek = 4),
            ),
            workouts = listOf(WorkoutSlot(MONDAY, t("11:00")), WorkoutSlot(THURSDAY, t("10:30")), WorkoutSlot(SUNDAY, t("18:30"), skipWeek = 4)),
        )
    }
}

/**
 * The user's plan and weekly routine in one place, so the buddy can reason about "where you are right now".
 * [routine] is loaded from settings (see SettingsStore) — nothing personal is hard-coded.
 */
object Plan {

    data class Block(val start: LocalTime, val end: LocalTime, val what: String, val kind: Kind = Kind.OTHER,
                     val travelMin: Int = 0, val breakMin: Int = 0, val rate: Double = 0.0)

    @Volatile var routine: Routine = Routine()

    fun weekOfMonth(d: LocalDate) = (d.dayOfMonth - 1) / 7 + 1
    fun isFourthSunday(date: LocalDate) = date.dayOfWeek == SUNDAY && weekOfMonth(date) == 4

    fun blocksFor(date: LocalDate, r: Routine = routine): List<Block> {
        val out = mutableListOf<Block>()
        r.walkAt?.let { out += Block(it, it.plusMinutes(r.walkMin.toLong()), "morning walk", Kind.WALK) }
        r.items.filter { it.activeOn(date) }.forEach { out += Block(it.start, it.end, it.label, it.kind, it.travelMin, it.breakMin, it.rate) }
        sessionSlot(date, r)?.let { (slot, s) -> out += Block(slot.time, slot.time.plusMinutes(30), s.title, Kind.WORKOUT) }
        out += Block(r.bedtime, LocalTime.of(23, 59).coerceAtLeast(r.bedtime), "bedtime", Kind.BED)
        return out.sortedBy { it.start }
    }

    fun nowDescription(now: LocalDateTime): String {
        val tm = now.toLocalTime()
        val blocks = blocksFor(now.toLocalDate())
        val current = blocks.firstOrNull { tm >= it.start && tm < it.end }
        val next = blocks.firstOrNull { it.start > tm }
        return buildString {
            append(current?.let { "Right now: ${it.what}." } ?: "Right now: free time.")
            next?.let { append(" Next: ${it.what} at ${it.start}.") }
            if (routine.isEmpty) append(" (The user hasn't set up their weekly routine yet.)")
        }
    }

    fun todaySummary(date: LocalDate) = blocksFor(date).joinToString("; ") { "${it.start}-${it.end} ${it.what}" }

    /** Evening shift ending late (for sleep insights and the wind-down prompt). */
    fun lateWorkDay(d: LocalDate) = blocksFor(d).any { it.kind == Kind.WORK && it.end >= LocalTime.of(20, 0) }
    /** Long daytime shift (protein often drops on these days). */
    fun longWorkDay(d: LocalDate) = blocksFor(d).any { it.kind == Kind.WORK && it.start < LocalTime.of(13, 0) && ChronoUnit.MINUTES.between(it.start, it.end) >= 300 }

    // ---------- Workouts ----------

    data class Exercise(val name: String, val cue: String)
    data class Session(val code: String, val title: String, val exercises: List<Exercise>)

    /** Beginner re-entry programme; sessions rotate A → B → C over the user's workout days. */
    val sessionTemplates = listOf(
        Session("A", "Session A — legs & core", listOf(
            Exercise("Box squat to chair → bodyweight squat (wk3+)", "Feet shoulder-width, chest up, knees track over toes"),
            Exercise("Glute bridge", "Squeeze glutes at top for 2 s, ribs down"),
            Exercise("Reverse lunge (wk2+)", "Step back, both knees ~90°, push through front heel"),
            Exercise("Forearm plank", "Straight line head to heels, brace like a punch is coming"),
            Exercise("Dead bug", "Lower back stays glued to the floor, move slowly"),
        )),
        Session("B", "Session B — push, pull & core", listOf(
            Exercise("Incline push-up (wk1–2) → floor push-up (wk3+)", "Hands under shoulders, body rigid, chest to edge"),
            Exercise("Towel door row / table row", "Pull elbows back, squeeze shoulder blades"),
            Exercise("Pike push-up (wk3+)", "Hips high, head goes between hands"),
            Exercise("Superman hold", "Lift chest and legs gently, look at floor"),
            Exercise("Side plank (knees → full)", "Hips high, body in one line"),
        )),
        Session("C", "Session C — full body & abs", listOf(
            Exercise("Bodyweight squat", "Slow down (3 s), normal speed up"),
            Exercise("Push-up (your current level)", "Stop 2 reps before failure"),
            Exercise("Glute bridge march", "Hips level while you lift each foot"),
            Exercise("Bicycle crunch", "Slow, shoulder towards opposite knee, no neck pulling"),
            Exercise("Slow mountain climbers", "Hands under shoulders, knees drive in controlled"),
        )),
    )

    /** Sets/reps by week, repeating week 4 after the first month. */
    fun dosage(week: Int): String = when (week) {
        1 -> "Week 1: 2 sets × 8–10 reps, planks 20 s, rest 90 s. Learn the form."
        2 -> "Week 2: 3 sets × 10 reps, planks 30 s, rest 75 s."
        3 -> "Week 3: 3 sets × 12 reps (harder variations), planks 40 s, rest 60 s."
        else -> "Week 4+: 3 sets × 12–15 reps, planks 45 s, rest 60 s. Ready for the gym soon."
    }

    fun weekNumber(planStart: String, today: LocalDate = LocalDate.now()): Int {
        val start = runCatching { LocalDate.parse(planStart) }.getOrDefault(today)
        return (ChronoUnit.DAYS.between(start, today) / 7 + 1).toInt().coerceAtLeast(1)
    }

    fun sessionSlot(date: LocalDate, r: Routine = routine): Pair<WorkoutSlot, Session>? {
        val slots = r.workouts.sortedBy { it.day }
        val i = slots.indexOfFirst { it.day == date.dayOfWeek }
        if (i < 0) return null
        val slot = slots[i]
        if (slot.skipWeek != 0 && weekOfMonth(date) == slot.skipWeek) return null
        return slot to sessionTemplates[i % sessionTemplates.size]
    }

    fun sessionFor(date: LocalDate): Session? = sessionSlot(date, routine)?.second

    /** Food rules come from the user's own settings (diet notes), not from code. */
    fun rules(s: NovaSettings): List<String> = s.dietRules.lines().map { it.trim().removePrefix("-").trim() }.filter { it.isNotEmpty() }
}
