package com.aethernova.nova.data

import java.time.Duration
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil

// v0.9 — pure logic for the Work tab and daily-life helpers. Everything here is unit-tested.

/** Leitner spaced repetition: 5 boxes, review gaps 1/2/4/8/16 days. */
object Leitner {
    val INTERVALS = intArrayOf(1, 2, 4, 8, 16)

    fun review(c: Flashcard, correct: Boolean, today: LocalDate): Flashcard {
        val box = if (correct) (c.box + 1).coerceAtMost(5) else 1
        return c.copy(box = box, dueDay = today.plusDays(INTERVALS[box - 1].toLong()).toString())
    }

    fun due(cards: List<Flashcard>, today: LocalDate): List<Flashcard> =
        cards.filter { it.dueDay <= today.toString() }.sortedWith(compareBy({ it.box }, { it.dueDay }))

    fun newCard(deck: String, front: String, back: String, today: LocalDate) =
        Flashcard(deck = deck.trim().ifBlank { "General" }.take(40), front = front.trim().take(300), back = back.trim().take(600), box = 1, dueDay = today.toString())

    /** Share of cards in boxes 4–5 — "you really know these". */
    fun mastery(cards: List<Flashcard>): Double = if (cards.isEmpty()) 0.0 else cards.count { it.box >= 4 }.toDouble() / cards.size
}

/** Dissertation pace: how many words a day you need to hit the target by the due date. */
object DissPace {
    data class Pace(val written: Int, val target: Int, val daysLeft: Long, val perDayNeeded: Int, val recentPerDay: Double?, val onTrack: Boolean)

    fun compute(logs: List<DissLog>, target: Int, due: LocalDate, today: LocalDate): Pace {
        val counts = logs.filter { it.kind == "words" }.sortedBy { it.day }
        val written = counts.lastOrNull()?.words ?: 0
        val daysLeft = ChronoUnit.DAYS.between(today, due).coerceAtLeast(0)
        val left = (target - written).coerceAtLeast(0)
        val perDay = if (left == 0) 0 else ceil(left / daysLeft.coerceAtLeast(1).toDouble()).toInt()
        // recent pace: growth over the last 14 days (needs a count from before that window or two points inside it)
        val from = today.minusDays(14).toString()
        val before = counts.lastOrNull { it.day <= from } ?: counts.firstOrNull { it.day > from }
        val recent = if (before != null && counts.last() !== before) {
            val days = ChronoUnit.DAYS.between(LocalDate.parse(before.day), LocalDate.parse(counts.last().day)).coerceAtLeast(1)
            (counts.last().words - before.words).toDouble() / days
        } else null
        val onTrack = left == 0 || (recent != null && recent >= perDay)
        return Pace(written, target, daysLeft, perDay, recent, onTrack)
    }

    /** Placeholder milestones spread between today and the due date — the user edits them to match their handbook. */
    fun defaultMilestones(due: LocalDate, today: LocalDate = LocalDate.now()): List<DissMilestone> {
        val span = ChronoUnit.DAYS.between(today, due).coerceAtLeast(14)
        val steps = listOf("Proposal & ethics approval" to 0.12, "Literature review draft" to 0.3, "Prototype / method working" to 0.5,
            "Evaluation & results" to 0.7, "Full draft to supervisor" to 0.85)
        return steps.map { (t, f) -> DissMilestone(title = t, dueDay = minOf(today.plusDays((span * f).toLong()), due.minusDays(7)).toString(), done = false) } +
            DissMilestone(title = "Final submission", dueDay = due.toString(), done = false)
    }
}

/** Shift pay: gross before tax/NI, and a payslip check. */
object ShiftPay {
    fun gross(shifts: List<Shift>): Double = shifts.sumOf { it.hours * it.rate }
    fun hours(shifts: List<Shift>): Double = shifts.sumOf { it.hours }
    fun inMonth(shifts: List<Shift>, month: java.time.YearMonth) = shifts.filter { it.day.startsWith(month.toString()) }
    fun byJob(shifts: List<Shift>): Map<String, Double> = shifts.groupBy { it.job }.mapValues { (_, v) -> gross(v) }

    /** Shifts from the user's weekly routine for a date: hours from the block times minus any unpaid break. */
    fun planned(d: LocalDate): List<Shift> = Plan.blocksFor(d).filter { it.kind == Kind.WORK }.map { b ->
        val h = Duration.between(b.start, b.end).toMinutes() / 60.0 - b.breakMin / 60.0
        Shift(day = d.toString(), job = b.what, hours = h.coerceAtLeast(0.0), rate = b.rate, note = "planned")
    }

    /** Positive = payslip is short by that much. */
    fun payslipGap(logged: List<Shift>, payslipGross: Double) = gross(logged) - payslipGross

    fun label(job: String) = job.ifBlank { "Other" }.replaceFirstChar { it.uppercase() }
}

/** Mock interview: offline question bank and a simple STAR-structure scorer used when the AI is unavailable. */
object Interview {
    val ROLES = listOf("Data scientist / ML", "Software developer", "Data analyst", "Graduate scheme", "Part-time / hospitality")

    private val common = listOf(
        "Tell me about yourself.",
        "Why do you want this role, and why our company?",
        "Tell me about a time you worked in a team under pressure.",
        "Describe a mistake you made and what you learned.",
        "Where do you see yourself in three years?",
    )
    private val bank = mapOf(
        "Data scientist / ML" to listOf(
            "Walk me through an ML project from data to deployment.",
            "How do you handle an imbalanced dataset?",
            "Explain overfitting to a non-technical manager, and how you prevent it.",
            "Precision or recall — when would you prioritise each?",
            "Tell me about a time your model didn't work. What did you do?",
        ),
        "Software developer" to listOf(
            "Tell me about a project you built end to end. What would you change?",
            "How do you debug a crash you can't reproduce?",
            "Explain a REST API design you've made.",
            "How do you make sure your code is tested?",
            "Tell me about a time you disagreed with a teammate on a technical decision.",
        ),
        "Data analyst" to listOf(
            "Describe a time you found an insight in messy data.",
            "How would you explain a dashboard to a stakeholder who's in a hurry?",
            "Write in words a SQL query to find the top 5 customers by spend last month.",
            "How do you check your analysis is correct before sharing it?",
            "Tell me about a time data changed someone's decision.",
        ),
        "Graduate scheme" to listOf(
            "Tell me about a time you balanced several commitments at once.",
            "Describe a time you showed leadership without being the leader.",
            "Tell me about a time you had to learn something quickly.",
            "Give an example of great customer service you delivered.",
            "What's a recent tech trend you find interesting and why?",
        ),
        "Part-time / hospitality" to listOf(
            "How would you handle an angry customer during a rush?",
            "Tell me about a time you worked fast without losing accuracy.",
            "What does good teamwork on a busy shift look like?",
            "How do you manage shifts around your studies?",
            "Why do you want to work here?",
        ),
    )

    fun offlineQuestions(role: String): List<String> {
        val specific = bank[role] ?: bank.getValue("Graduate scheme")
        return listOf(common[0]) + specific.take(3) + common[2]
    }

    data class Feedback(val score: Int, val tips: List<String>)

    /** Heuristic: length, STAR cues, first person, numbers/results. */
    fun score(answer: String): Feedback {
        val a = answer.lowercase()
        val words = a.split(Regex("\\s+")).count { it.isNotBlank() }
        val tips = mutableListOf<String>()
        var s = 2
        when {
            words < 25 -> tips += "Too short — aim for 60–150 words (about a minute spoken)."
            words > 250 -> { s += 1; tips += "A bit long — trim to the key points." }
            else -> s += 3
        }
        val situation = listOf("when ", "at ", "during ", "in my ", "project", "shift", "module").any { it in a }
        val action = Regex("\\bi (did|built|made|led|decided|wrote|created|trained|fixed|organised|organized|spoke|asked|used|learned|learnt)\\b").containsMatchIn(a)
        val result = listOf("result", "so that", "which led", "outcome", "improved", "reduced", "increased", "saved", "learned", "learnt").any { it in a }
        val numbers = Regex("\\d").containsMatchIn(a)
        if (situation) s += 1 else tips += "Set the scene first (Situation/Task): where and when."
        if (action) s += 2 else tips += "Say what YOU did — \"I built…\", \"I decided…\" — not just \"we\"."
        if (result) s += 1 else tips += "Finish with the Result — what changed because of you."
        if (numbers) s += 1 else tips += "Add a number (time saved, accuracy, customers) to make it concrete."
        if (tips.isEmpty()) tips += "Strong, structured answer. Practise saying it out loud once more."
        return Feedback(s.coerceIn(1, 10), tips.take(3))
    }
}

/** Hands-free cooking: built-in recipes and voice command parsing. */
object Cooking {
    data class Step(val text: String, val timerSec: Int = 0)
    data class Recipe(val name: String, val serves: Int, val kcalPerServe: Int, val proteinPerServe: Int, val ingredients: List<String>, val steps: List<Step>)

    val BUILT_IN = listOf(
        Recipe("Kerala egg roast (low oil)", 2, 380, 24,
            listOf("6 eggs", "2 onions, sliced", "2 tomatoes, chopped", "1 tsp ginger-garlic paste", "1 green chilli", "1 tsp chilli powder, ½ tsp turmeric, 1 tsp garam masala", "1 tsp coconut oil", "curry leaves"),
            listOf(Step("Boil the eggs for 10 minutes.", 600), Step("Cool the eggs in cold water, peel them and score them lightly with a knife."),
                Step("Heat 1 tsp coconut oil, add curry leaves and the onions. Cook with a pinch of salt until soft and brown, stirring.", 480),
                Step("Add ginger-garlic paste and green chilli. Cook 1 minute.", 60), Step("Add the spice powders with a splash of water. Cook 1 minute so they don't burn.", 60),
                Step("Add tomatoes, cover and cook until they turn mushy.", 300), Step("Add the eggs, coat them in the masala, cook 3 minutes. Serve with a wrap or a small scoop of rice.", 180))),
        Recipe("Meen curry (Kerala fish curry)", 2, 330, 34,
            listOf("400 g white fish or salmon, in pieces", "2 pieces kudampuli (or 1 tbsp tamarind)", "1 onion or 6 shallots", "1 tsp ginger-garlic paste", "2 green chillies", "1½ tbsp chilli powder, ½ tsp turmeric", "1 tsp coconut oil", "curry leaves"),
            listOf(Step("Soak the kudampuli in ½ cup warm water.", 300), Step("Heat 1 tsp oil in a clay pot or pan, add curry leaves, onion, ginger-garlic and chillies. Sauté until soft.", 240),
                Step("Lower the heat, add the spice powders and stir 30 seconds.", 30), Step("Add 1½ cups water and the kudampuli with its water. Bring to the boil.", 240),
                Step("Slide in the fish. Don't stir — swirl the pan. Simmer covered.", 600), Step("Rest 10 minutes off the heat. It tastes better the next day — perfect for meal prep.", 600))),
        Recipe("Chicken pepper fry (light)", 2, 360, 48,
            listOf("450 g chicken breast, cubed", "1 onion, sliced", "1 tbsp ginger-garlic paste", "1½ tsp crushed black pepper", "½ tsp turmeric, 1 tsp coriander powder", "2 tsp coconut oil", "curry leaves"),
            listOf(Step("Mix chicken with turmeric, half the pepper, salt and ginger-garlic. Rest 10 minutes.", 600),
                Step("Heat 2 tsp oil, add curry leaves and onion. Cook until golden.", 360), Step("Add the chicken on a high flame and sear.", 240),
                Step("Add coriander powder and a splash of water. Cover and cook.", 480), Step("Uncover, add the rest of the pepper and dry it out, stirring.", 180), Step("Done. Split into 2 boxes with veg for tomorrow."))),
        Recipe("Egg & oats dosa wrap", 1, 450, 32,
            listOf("40 g oats, ground", "3 eggs", "1 whole-wheat wrap", "onion, chilli, coriander, chopped", "½ tsp oil"),
            listOf(Step("Whisk the eggs with the ground oats, onion, chilli, coriander and salt."), Step("Heat a non-stick pan with ½ tsp oil. Pour the batter in and spread it thin.", 0),
                Step("Cook until the bottom sets, then flip.", 120), Step("Place the wrap on top for 30 seconds to warm it, then fold.", 30), Step("Eat with a glass of milk — about 40 g of protein for breakfast."))),
    )

    sealed interface Cmd { object Next : Cmd; object Back : Cmd; object Repeat : Cmd; object Ingredients : Cmd; object Stop : Cmd; data class Timer(val seconds: Int) : Cmd }

    private val numberWords = mapOf("one" to 1, "a" to 1, "an" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8,
        "nine" to 9, "ten" to 10, "fifteen" to 15, "twenty" to 20, "thirty" to 30, "forty" to 40, "half" to -1)

    fun parse(text: String): Cmd? {
        val t = text.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return null
        if (Regex("\\b(timer|set|start)\\b").containsMatchIn(t) || Regex("\\d+ ?(min|minute|sec|second)").containsMatchIn(t)) {
            val m = Regex("\\b(\\d+|${numberWords.keys.joinToString("|")}) ?(and a half )?(hour|hr|min|minute|sec|second)s?").find(t)
            if (m != null) {
                val raw = m.groupValues[1]
                val n = raw.toIntOrNull() ?: numberWords[raw] ?: 1
                val unit = m.groupValues[3]
                val base = when { unit.startsWith("h") -> 3600; unit.startsWith("m") -> 60; else -> 1 }
                val secs = if (n == -1) base / 2 else n * base + if (m.groupValues[2].isNotEmpty()) base / 2 else 0
                return Cmd.Timer(secs.coerceIn(5, 4 * 3600))
            }
            if ("timer" in t) return Cmd.Timer(0)  // "start timer" = use this step's timer
        }
        return when {
            Regex("\\b(next|done|ok next|continue|go on|finished)\\b").containsMatchIn(t) -> Cmd.Next
            Regex("\\b(back|previous|go back|last step)\\b").containsMatchIn(t) -> Cmd.Back
            Regex("\\b(ingredients|what do i need)\\b").containsMatchIn(t) -> Cmd.Ingredients
            Regex("\\b(repeat|again|say that|pardon|sorry)\\b").containsMatchIn(t) -> Cmd.Repeat
            Regex("\\b(stop|exit|quit|close)\\b").containsMatchIn(t) -> Cmd.Stop
            else -> null
        }
    }
}

/** 2-day meal prep (he cooks for max 2 days at a time). Offline template sized to ~1,800 kcal / 130 g protein. */
object MealPrep {
    data class Meal(val slot: String, val name: String, val kcal: Int, val protein: Int)
    data class Day(val label: String, val meals: List<Meal>) { val kcal get() = meals.sumOf { it.kcal }; val protein get() = meals.sumOf { it.protein } }
    data class Plan2(val days: List<Day>, val shopping: List<Pair<String, String>>, val prepSteps: List<String>)

    fun parse(o: org.json.JSONObject): Plan2 {
        val days = o.optJSONArray("days") ?: org.json.JSONArray()
        val out = (0 until days.length()).mapNotNull { i ->
            val d = days.optJSONObject(i) ?: return@mapNotNull null
            val meals = d.optJSONArray("meals") ?: return@mapNotNull null
            Day(d.optString("label", "Day ${i + 1}").take(20), (0 until meals.length()).mapNotNull { j ->
                meals.optJSONObject(j)?.let { Meal(it.optString("slot").take(20), it.optString("name").take(120), it.optInt("kcal").coerceIn(0, 2000), it.optInt("protein").coerceIn(0, 150)) }
            }.filter { it.name.isNotBlank() })
        }.filter { it.meals.isNotEmpty() }.take(2)
        val shop = o.optJSONArray("shopping")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { x -> x.optString("name").take(40) to x.optString("qty").take(30) } } }.orEmpty().filter { it.first.isNotBlank() }
        val prep = (o.optJSONArray("prep") ?: o.optJSONArray("prepSteps"))?.let { a -> (0 until a.length()).map { a.optString(it).take(200) } }.orEmpty().filter { it.isNotBlank() }
        require(out.isNotEmpty()) { "No days in plan" }
        return Plan2(out, shop.take(25), prep.take(8))
    }

    fun toJson(p: Plan2): org.json.JSONObject = org.json.JSONObject()
        .put("days", org.json.JSONArray(p.days.map { d -> org.json.JSONObject().put("label", d.label).put("meals", org.json.JSONArray(d.meals.map {
            org.json.JSONObject().put("slot", it.slot).put("name", it.name).put("kcal", it.kcal).put("protein", it.protein) })) }))
        .put("shopping", org.json.JSONArray(p.shopping.map { org.json.JSONObject().put("name", it.first).put("qty", it.second) }))
        .put("prep", org.json.JSONArray(p.prepSteps))

    fun template(): Plan2 = Plan2(
        days = listOf(
            Day("Day 1", listOf(
                Meal("Breakfast", "3-egg omelette in a whole-wheat wrap + 250 ml milk", 540, 33),
                Meal("Lunch", "Chicken pepper fry + 60 g rice + salad", 520, 50),
                Meal("Snack", "Skyr 150 g + banana", 195, 17),
                Meal("Dinner", "Meen curry + 1 wrap + greens", 430, 38),
                Meal("Nuts", "15 almonds", 100, 4),
            )),
            Day("Day 2", listOf(
                Meal("Breakfast", "Egg & oats dosa wrap + 250 ml milk", 610, 40),
                Meal("Lunch", "Meen curry + 60 g rice + salad", 480, 38),
                Meal("Snack", "Skyr 150 g + apple", 170, 16),
                Meal("Dinner", "Chicken pepper fry + 1 wrap + veg", 520, 50),
            )),
        ),
        shopping = listOf("Chicken breast" to "450 g", "White fish or salmon" to "400 g", "Eggs" to "6", "Whole milk" to "1 L",
            "Skyr / Greek yogurt" to "2 pots", "Whole-wheat wraps" to "4", "Rice" to "120 g", "Oats" to "40 g",
            "Onions, tomatoes, chillies" to "", "Salad & greens" to "1 bag", "Banana, apple" to "1 each", "Almonds" to "15"),
        prepSteps = listOf("Start rice and boil any eggs first.", "Cook chicken pepper fry while the fish curry simmers (both ~25 min).",
            "Cool 15 minutes, then box: 2 chicken, 2 fish, portioned rice.", "Fridge only — 2 days max. Reheat until piping hot."),
    )
}

/** Guided breathing: phases with durations; pure so the animation can be tested. */
object Breathing {
    data class Pattern(val name: String, val phases: List<Pair<String, Int>>) { val cycleSec get() = phases.sumOf { it.second } }

    val CALM = Pattern("Calm 4-6", listOf("Breathe in" to 4, "Breathe out" to 6))
    val BOX = Pattern("Box 4-4-4-4", listOf("Breathe in" to 4, "Hold" to 4, "Breathe out" to 4, "Hold" to 4))
    val ALL = listOf(CALM, BOX)

    /** (phase label, 0..1 progress within the phase, lung fill 0..1). */
    fun at(p: Pattern, elapsedMs: Long): Triple<String, Float, Float> {
        var t = (elapsedMs % (p.cycleSec * 1000L)).toFloat() / 1000f
        var fill = 0f
        for ((label, sec) in p.phases) {
            if (t < sec) {
                val prog = t / sec
                val lung = when { label.endsWith("in") -> prog; label.endsWith("out") -> 1 - prog; else -> fill }
                return Triple(label, prog, lung)
            }
            fill = if (label.endsWith("in")) 1f else if (label.endsWith("out")) 0f else fill
            t -= sec
        }
        return Triple(p.phases.first().first, 0f, 0f)
    }
}

/** 20-20-20 eye breaks during focus blocks. */
object EyeBreaks {
    /** Minutes into a block when to prompt: every 20 min, never in the last 3 minutes (the block-end break covers it). */
    fun offsets(focusMinutes: Int): List<Int> = (20 until focusMinutes - 2 step 20).toList()
    const val TEXT = "20-20-20: look at something 6 metres away for 20 seconds, blink, roll your shoulders back, sit tall."
}
