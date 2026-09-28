package com.aethernova.nova

import com.aethernova.nova.data.Breathing
import com.aethernova.nova.data.Cooking
import com.aethernova.nova.data.DissLog
import com.aethernova.nova.data.DissPace
import com.aethernova.nova.data.EyeBreaks
import com.aethernova.nova.data.Flashcard
import com.aethernova.nova.data.Interview
import com.aethernova.nova.data.Leitner
import com.aethernova.nova.data.MealPrep
import com.aethernova.nova.data.Shift
import com.aethernova.nova.data.ShiftPay
import com.aethernova.nova.reminders.NudgePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

class V09Test {
    @org.junit.Before fun exampleRoutine() { com.aethernova.nova.data.Plan.routine = com.aethernova.nova.data.Routine.EXAMPLE }

    private val today = LocalDate.of(2026, 10, 1)

    @Test fun leitnerMovesUpAndResets() {
        val c = Leitner.newCard("NLP", "Q", "A", today)
        val up = Leitner.review(c, true, today)
        assertEquals(2, up.box); assertEquals("2026-10-03", up.dueDay)
        val top = Leitner.review(up.copy(box = 5), true, today)
        assertEquals(5, top.box); assertEquals("2026-10-17", top.dueDay)
        val wrong = Leitner.review(top, false, today)
        assertEquals(1, wrong.box); assertEquals("2026-10-02", wrong.dueDay)
    }

    @Test fun leitnerDueAndMastery() {
        val cards = listOf(
            Flashcard(1, "a", "q1", "a", 3, "2026-09-30"),
            Flashcard(2, "a", "q2", "a", 1, "2026-10-01"),
            Flashcard(3, "a", "q3", "a", 5, "2026-10-05"),
        )
        assertEquals(listOf(2L, 1L), Leitner.due(cards, today).map { it.id })
        assertEquals(1.0 / 3, Leitner.mastery(cards), 1e-9)
    }

    @Test fun dissertationPace() {
        val logs = listOf(DissLog(day = "2026-09-10", kind = "words", words = 1000), DissLog(day = "2026-09-30", kind = "words", words = 2400),
            DissLog(day = "2026-09-20", kind = "meeting", text = "notes"))
        val p = DissPace.compute(logs.sortedBy { it.day }, 10000, LocalDate.of(2027, 5, 1), today)
        assertEquals(2400, p.written)
        assertEquals(212, p.daysLeft)
        assertEquals(36, p.perDayNeeded)   // ceil(7600 / 212)
        assertEquals(70.0, p.recentPerDay!!, 1e-9)  // (2400-1000)/20 days
        assertTrue(p.onTrack)
        val none = DissPace.compute(emptyList(), 10000, LocalDate.of(2027, 5, 1), today)
        assertEquals(0, none.written); assertNull(none.recentPerDay); assertFalse(none.onTrack)
    }

    @Test fun milestonesStayBeforeDue() {
        val ms = DissPace.defaultMilestones(LocalDate.of(2027, 3, 1), LocalDate.of(2026, 10, 1))
        assertEquals("2027-03-01", ms.last().dueDay)
        assertTrue(ms.dropLast(1).all { it.dueDay <= "2027-02-22" })
    }

    @Test fun shiftPayAndPlan() {
        val tue = LocalDate.of(2026, 9, 29)   // Chicking 11–17 (break) + Pizza Hut 17:30–21
        val planned = ShiftPay.planned(tue)
        assertEquals(setOf("Café shift", "Restaurant shift"), planned.map { it.job }.toSet())
        assertEquals(5.5, planned.first { it.job == "Café shift" }.hours, 1e-9)
        assertEquals(3.5, planned.first { it.job == "Restaurant shift" }.hours, 1e-9)
        assertEquals(5.5 * 11.5 + 3.5 * 12.71, ShiftPay.gross(planned), 1e-9)
        val shifts = planned + Shift(day = "2026-08-30", job = "pizzahut", hours = 4.0, rate = 12.71)
        assertEquals(2, ShiftPay.inMonth(shifts, YearMonth.of(2026, 9)).size)
        assertEquals(10.0, ShiftPay.payslipGap(listOf(Shift(day = "d", job = "x", hours = 10.0, rate = 12.0)), 110.0), 1e-9)
    }

    @Test fun interviewFallbackAndScorer() {
        assertEquals(5, Interview.offlineQuestions("Data scientist / ML").size)
        assertEquals(5, Interview.offlineQuestions("unknown role").size)
        val weak = Interview.score("I am good at teamwork.")
        assertTrue(weak.score <= 4)
        val strong = Interview.score("During my second-year NLP module project, our team had two days left and the model was overfitting. " +
            "I decided to add dropout and I built a proper validation split, then I led a short review with the team. " +
            "As a result accuracy improved from 71% to 84% and we submitted a day early, which led to the top mark in our group.")
        assertTrue("was ${strong.score}", strong.score >= 8)
    }

    @Test fun cookingCommands() {
        assertEquals(Cooking.Cmd.Next, Cooking.parse("okay next"))
        assertEquals(Cooking.Cmd.Back, Cooking.parse("go back"))
        assertEquals(Cooking.Cmd.Repeat, Cooking.parse("say that again"))
        assertEquals(Cooking.Cmd.Timer(300), Cooking.parse("set a timer for 5 minutes"))
        assertEquals(Cooking.Cmd.Timer(600), Cooking.parse("timer ten minutes"))
        assertEquals(Cooking.Cmd.Timer(90), Cooking.parse("one and a half minutes timer"))
        assertEquals(Cooking.Cmd.Timer(0), Cooking.parse("start the timer"))
        assertEquals(Cooking.Cmd.Ingredients, Cooking.parse("what do I need"))
        assertNull(Cooking.parse("the onions are browning nicely"))
        assertTrue(Cooking.BUILT_IN.all { r -> r.steps.isNotEmpty() && r.ingredients.isNotEmpty() })
    }

    @Test fun mealPrepTemplateAndJsonRoundTrip() {
        val t = MealPrep.template()
        assertEquals(2, t.days.size)
        t.days.forEach { d -> assertTrue("${d.label} ${d.kcal}", d.kcal in 1600..1950); assertTrue(d.protein >= 120) }
        val back = MealPrep.parse(MealPrep.toJson(t))
        assertEquals(t, back)
    }

    @Test fun breathingPhases() {
        val (l0, _, f0) = Breathing.at(Breathing.CALM, 0)
        assertEquals("Breathe in", l0); assertEquals(0f, f0, 1e-6f)
        val (l1, _, f1) = Breathing.at(Breathing.CALM, 4000)
        assertEquals("Breathe out", l1); assertEquals(1f, f1, 1e-6f)
        val (l2, _, f2) = Breathing.at(Breathing.BOX, 6000)   // hold after inhale keeps lungs full
        assertEquals("Hold", l2); assertEquals(1f, f2, 1e-6f)
        assertEquals("Breathe in", Breathing.at(Breathing.CALM, 10_000).first)  // wraps
    }

    @Test fun eyeBreakOffsets() {
        assertEquals(listOf(20), EyeBreaks.offsets(25))
        assertEquals(listOf(20, 40), EyeBreaks.offsets(50))
        assertEquals(emptyList<Int>(), EyeBreaks.offsets(20))
    }

    @Test fun windDownIsSingleStage() {
        val tue = LocalDate.of(2026, 9, 29)
        val wd = NudgePlan.forDate(tue, false).single { it.key.endsWith("winddown") }
        assertFalse(wd.twoStage); assertFalse(wd.loud); assertEquals("breathe", wd.opens)
        assertEquals(LocalDateTime.of(2026, 9, 29, 21, 30), wd.gentleAt)
        assertTrue(NudgePlan.forDate(tue, false, windDown = false).none { it.key.endsWith("winddown") })
        // at 21:29 the next event is the wind-down gentle; after it there is no "check" phase for it
        val (n, ph) = NudgePlan.next(LocalDateTime.of(2026, 9, 29, 21, 29), false)!!
        assertEquals(wd.key, n.key); assertEquals("gentle", ph)
        val (n2, _) = NudgePlan.next(LocalDateTime.of(2026, 9, 29, 21, 31), false)!!
        assertTrue(n2.key != wd.key)
    }
}
