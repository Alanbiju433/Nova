package com.aethernova.nova

import com.aethernova.nova.data.Emergency
import com.aethernova.nova.data.Kind
import com.aethernova.nova.data.Plan
import com.aethernova.nova.data.Routine
import com.aethernova.nova.data.Targets
import com.aethernova.nova.data.Weather
import com.aethernova.nova.reminders.NudgePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** v1.0 (Play release): user-entered routine, targets calculator, nothing personal in the code. */
class V10Test {

    @Test fun routineJsonRoundTrip() {
        val back = Routine.parse(Routine.EXAMPLE.toJson().toString())
        assertEquals(Routine.EXAMPLE, back)
        assertTrue(Routine.parse("not json").isEmpty)
        assertTrue(Routine.parse("").isEmpty)
    }

    @Test fun emptyRoutineOnlyHasBedtime() {
        val blocks = Plan.blocksFor(LocalDate.of(2026, 10, 6), Routine())
        assertEquals(listOf(Kind.BED), blocks.map { it.kind })
        Plan.routine = Routine()
        val n = NudgePlan.forDate(LocalDate.of(2026, 10, 6), false, name = "Sam")
        assertEquals(1, n.size); assertTrue(n.single().shout.contains("Sam"))
    }

    @Test fun weekOfMonthRules() {
        val r = Routine.EXAMPLE
        val church = LocalDate.of(2026, 10, 25)   // 4th Sunday
        val normal = LocalDate.of(2026, 10, 18)
        assertTrue(Plan.blocksFor(church, r).any { it.what == "Church" })
        assertTrue(Plan.blocksFor(church, r).none { it.what == "Café shift" })
        assertTrue(Plan.blocksFor(normal, r).none { it.what == "Church" })
        assertNull(Plan.sessionSlot(church, r))
        assertEquals("C", Plan.sessionSlot(normal, r)!!.second.code)
        assertEquals("A", Plan.sessionSlot(LocalDate.of(2026, 10, 19), r)!!.second.code)
    }

    @Test fun targetCalculator() {
        // 76 kg, 172 cm, 21 y male, moderate, recomp: BMR 1735 -> 1735*1.55*0.88 = 2366 -> 2350
        val t = Targets.compute("male", 76.0, 172, 21, Targets.Activity.MODERATE, Targets.Goal.RECOMP)
        assertEquals(1735, t.bmr); assertEquals(2350, t.kcal); assertEquals(135, t.protein); assertEquals(2750, t.waterMl)
        // never below the floor
        val tiny = Targets.compute("female", 45.0, 150, 60, Targets.Activity.LOW, Targets.Goal.LOSE)
        assertEquals(1400, tiny.kcal)
        // under 18 or missing data -> safe defaults, no BMR
        assertNull(Targets.compute("male", 70.0, 175, 16, Targets.Activity.LOW, Targets.Goal.LOSE).bmr)
    }

    @Test fun emergencyLineByCountry() {
        assertTrue(Emergency.line("GB").contains("999"))
        assertTrue(Emergency.line("IN").contains("112"))
        assertTrue(Emergency.line("US").contains("911"))
        assertTrue(Emergency.line("XX").contains("local emergency"))
    }

    @Test fun placeSearchParse() {
        val p = Weather.parsePlaces("""{"results":[{"name":"Leeds","admin1":"England","country":"United Kingdom","latitude":53.8,"longitude":-1.55}]}""").single()
        assertEquals("Leeds, England, United Kingdom", p.name); assertEquals(53.8, p.lat, 1e-9)
        assertTrue(Weather.parsePlaces("{}").isEmpty())
    }

    /** Guard for the public release: the developer's own schedule, employers and health data must not ship in the app. */
    @Test fun noPersonalDataInSources() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "src/main").exists() || File(it, "app/src/main").exists() }
        val main = if (File(root, "src/main").exists()) File(root, "src/main") else File(root, "app/src/main")
        val banned = listOf("Pizza Hut", "Chicking", "Northampton", "7.18", "Racecourse", "Beckets Park", "LH311")
        val hits = main.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
            .flatMap { f -> f.readLines().mapIndexedNotNull { i, l -> banned.firstOrNull { l.contains(it) }?.let { "${f.name}:${i + 1} $it" } } }.toList()
        assertTrue(hits.joinToString("\n"), hits.isEmpty())
    }
}
