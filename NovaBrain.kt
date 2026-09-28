package com.aethernova.nova.ai

import android.graphics.Bitmap
import com.aethernova.nova.data.ChatMessage
import com.aethernova.nova.data.FoodEntry
import com.aethernova.nova.data.LocalFood
import com.aethernova.nova.data.Plan
import com.aethernova.nova.data.Repository
import com.aethernova.nova.data.SettingsStore
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

enum class Mood { NEUTRAL, HAPPY, PROUD, WORRIED, SLEEPY, THINKING, CHEEKY,
    // play-mode only (never chosen by the AI)
    SURPRISED, DEAD, DIZZY, LOVE, FOCUS;
    companion object {
        fun from(s: String?) = entries.firstOrNull { it.name.equals(s?.trim(), true) } ?: NEUTRAL
    }
}

/** [pending] = food matched offline that the user must check before it is saved (never auto-logged). */
data class BrainReply(val text: String, val mood: Mood, val logged: List<String> = emptyList(), val pending: FoodAnalysis? = null)
data class FoodAnalysis(val items: List<FoodEntry>, val comment: String, val mood: Mood)

/** Live phone data passed in by the UI (from Health Connect). */
data class Vitals(val stepsToday: Long? = null, val sleepLastNightH: Double? = null)

class NovaBrain(
    private val repo: Repository,
    private val settings: SettingsStore,
) {
    // No consent = no key: nothing is ever sent to Google until the user switches the AI on.
    private val gemini = Gemini { settings.current.let { if (it.aiConsent) it.apiKey else "" } }

    // ---------------- Context ----------------

    private suspend fun context(vitals: Vitals): String {
        val s = settings.current
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        val (kcal, protein, food) = repo.todayTotals()
        val week = Plan.weekNumber(s.planStart, today)
        val session = Plan.sessionFor(today)
        val last7 = repo.dao.dailyTotalsSince(repo.daysAgo(7)).filter { it.day != repo.today() }
        val workouts = repo.dao.workoutsSinceNow(repo.daysAgo(7))
        val checkIns = repo.dao.recentCheckIns(10)
        val weights = checkIns.filter { it.weightKg != null }
        val latestCheck = checkIns.firstOrNull { it.mood != null || it.sleepHours != null }

        return buildString {
            appendLine("NOW: ${now.format(DateTimeFormatter.ofPattern("EEEE d MMM yyyy, HH:mm"))}. ${Plan.nowDescription(now)}")
            appendLine("TODAY'S ROUTINE: ${Plan.todaySummary(today)}")
            appendLine("TARGETS: ${s.kcalTarget} kcal, ${s.proteinTarget} g protein, ${s.stepsTarget} steps.")
            appendLine("EATEN TODAY: ${kcal} kcal, ${"%.0f".format(protein)} g protein. Remaining: ${s.kcalTarget - kcal} kcal, ${"%.0f".format(s.proteinTarget - protein)} g protein.")
            if (food.isNotEmpty()) appendLine("FOOD LOG TODAY: " + food.joinToString("; ") { "${it.name} (${it.portion}) ${it.kcal} kcal ${"%.0f".format(it.proteinG)} g P" })
            vitals.stepsToday?.let { appendLine("STEPS TODAY: $it") }
            vitals.sleepLastNightH?.let { appendLine("SLEEP LAST NIGHT (watch/phone): ${"%.1f".format(it)} h") }
            appendLine("PLAN WEEK: $week. ${Plan.dosage(week)}")
            appendLine("TODAY'S WORKOUT: " + (session?.let { "${it.title}: " + it.exercises.joinToString(", ") { e -> e.name } } ?: "rest day (walk only)"))
            if (last7.isNotEmpty()) appendLine("LAST 7 DAYS: " + last7.joinToString("; ") { "${it.day} ${it.kcal} kcal ${"%.0f".format(it.protein)} g P" })
            appendLine("WORKOUTS LAST 7 DAYS: " + if (workouts.isEmpty()) "none logged" else workouts.joinToString("; ") { "${it.day} ${it.kind} ${it.minutes} min" })
            if (weights.isNotEmpty()) appendLine("RECENT WEIGHTS: " + weights.joinToString("; ") { "${it.day} ${it.weightKg} kg" })
            latestCheck?.let { appendLine("LATEST CHECK-IN (${it.day}): sleep ${it.sleepHours ?: "?"} h, mood ${it.mood ?: "?"}/5, energy ${it.energy ?: "?"}/5 ${it.note}") }
            if (s.shareResults) {
                val results = repo.dao.resultsNow()
                if (results.isNotEmpty()) appendLine("HEALTH RESULTS: " + results.joinToString("; ") {
                    "${it.day} ${it.marker}: ${it.value ?: ""} ${it.unit} ${it.flag} ${it.note}".trim()
                })
            }
            appendLine("THEIR FOOD RULES: " + rulesLine(s))
            val facts = repo.dao.factsNow()
            if (facts.isNotEmpty()) appendLine("THINGS THEY TOLD YOU BEFORE (use naturally, follow up when relevant): " + facts.take(40).joinToString("; ") { it.text })
            if (s.monthlyBudget > 0) {
                val m = com.aethernova.nova.data.Budget.month(repo.dao.expensesSinceNow(today.withDayOfMonth(1).toString()), s.monthlyBudget, today)
                appendLine("SPENDING THIS MONTH: ${s.currency}${"%.2f".format(m.spent)} of ${s.currency}${s.monthlyBudget} budget, ${m.daysLeft} days left.")
            }
            com.aethernova.nova.data.Festivals.today(today, s.manglish)?.let { appendLine("TODAY IS SPECIAL: $it") }
        }
    }

    /** Everything the user chose to tell Nova about themselves (from setup), for prompts. */
    private fun profile(s: com.aethernova.nova.data.NovaSettings): String = listOfNotNull(
        "Name: ${s.userName.ifBlank { "(not given)" }}.",
        s.about.takeIf { it.isNotBlank() }?.let { "About them: $it." },
        if (s.age > 0) "Age: ${s.age}." else null,
        if (s.heightCm > 0) "Height: ${s.heightCm} cm." else null,
        s.goal.takeIf { it.isNotBlank() }?.let { "Their goals: $it." },
        s.cuisine.takeIf { it.isNotBlank() }?.let { "Food culture / cuisine: $it." },
        s.foodLikes.takeIf { it.isNotBlank() }?.let { "Foods they like: $it." },
        s.healthNotes.takeIf { it.isNotBlank() }?.let { "Health notes they chose to share (always respect these): $it." },
        s.city.takeIf { it.isNotBlank() }?.let { "Lives in: $it." },
    ).joinToString("\n")

    private fun rulesLine(s: com.aethernova.nova.data.NovaSettings) =
        Plan.rules(s).joinToString(" ").ifBlank { "No special food rules set." }

    private fun persona(level: Int = 1): String {
        val s = settings.current
        val unlocked = com.aethernova.nova.data.Pet.personality(level)
        val flavour = if (s.manglish)
            "Speak mostly English but sprinkle a few natural Malayalam words (machane, da, alle, sheri, adipoli, poli) like a Kerala friend would. Don't overdo it."
        else "Speak plain English."
        return """
You are ${s.buddyName}, ${s.callName}'s AI health buddy living in their phone — a friend first, like JARVIS crossed with EMO.
ABOUT THE USER:
${profile(s)}

PERSONALITY: cheeky but honest friend. Warm, short, specific. Tease lightly, never shame. Celebrate real wins.
Unlocked personality at level $level: $unlocked
Call out slips directly with one practical fix. Use the live data below — mention actual numbers.
$flavour
Replies are spoken aloud, so keep "reply" to 1–3 short sentences unless they ask for detail. No markdown, no emojis, no lists.

SAFETY (never break):
- You are not a doctor. Never diagnose, never give medicine or supplement doses. Explain what a result means in plain words,
  then send decisions to their doctor or a pharmacist.
- Chest pain, fainting, severe breathlessness, blood in stool/vomit, yellow skin/eyes, severe tummy pain, thoughts of self-harm:
  tell them to ${com.aethernova.nova.data.Emergency.line()}.
- No crash diets. Never suggest going below ${s.kcalFloor} kcal a day, skipping meals to "make up" for food,
  or anything that sounds like disordered eating; if they mention bingeing, purging or extreme restriction, respond kindly and
  suggest talking to a GP or an eating-disorder helpline.
- Respect their food rules and health notes; never suggest something they said they can't have. No alcohol suggestions.

OUTPUT: reply with ONLY a JSON object:
{"reply": string, "mood": "neutral|happy|proud|worried|sleepy|thinking|cheeky",
 "actions": [ ... zero or more of:
   {"type":"log_food","items":[{"name":string,"portion":string,"kcal":int,"protein_g":number,"carbs_g":number,"fat_g":number,"liver":"green|amber|red"}]},
   {"type":"log_weight","kg":number},
   {"type":"log_workout","kind":"walk|A|B|C|other","minutes":int},
   {"type":"checkin","sleep_hours":number|null,"mood":1-5|null,"energy":1-5|null},
   {"type":"log_expense","amount":number,"category":"food|groceries|transport|bills|fun|family|other","note":string},
   {"type":"remember","fact":string},
   Phone control (the app carries these out):
   {"type":"set_alarm","hour":0-23,"minute":0-59,"label":string}, {"type":"set_timer","seconds":int,"label":string},
   {"type":"open_app","name":string}, {"type":"call","who":string}, {"type":"text","who":string,"message":string},
   {"type":"torch","on":bool}, {"type":"volume","change":"up|down|mute|max|0-100"}, {"type":"dnd","on":bool},
   {"type":"navigate","destination":string,"mode":"w|d|r"}, {"type":"play_music","query":string},
   {"type":"remind","at":"yyyy-MM-ddTHH:mm","text":string}, {"type":"open_setting","which":"wifi|bluetooth|internet|battery|display"},
   {"type":"web_search","query":string}
 ]}
For calls and texts the app only opens the dialler / SMS app pre-filled; the user presses call/send themselves.
Use "remember" for durable personal things they share (people, plans, likes, exams, events) — short third-person facts.
Never store bank/card numbers, passwords, health diagnoses they didn't ask you to keep, or anything they ask you to forget.
If they ask for a joke, tell a short clean one.
Only add an action when they clearly say they ALREADY ate/did/weighed something (not plans or questions).
Estimate food using typical home portions for their cuisine; if they give amounts, use them. Mention what you logged in the reply.
""".trimIndent()
    }

    // ---------------- Chat ----------------

    /**
     * [exec] performs phone actions (alarms, apps, calls…) and returns a line to say; it is null when
     * there is no screen to act from.
     */
    suspend fun chat(userText: String, vitals: Vitals, exec: ((com.aethernova.nova.phone.Act) -> String)? = null): BrainReply {
        val now = System.currentTimeMillis()
        repo.dao.insertChat(ChatMessage(timestamp = now, role = "user", text = userText))

        // Everyday phone commands are handled instantly on the phone — no AI call, works offline.
        if (exec != null) com.aethernova.nova.phone.LocalCommands.parse(userText)?.let { act ->
            val line = exec(act)
            repo.dao.insertChat(ChatMessage(timestamp = System.currentTimeMillis(), role = "nova", text = line))
            return BrainReply(line, Mood.HAPPY)
        }
        currentExec = exec

        val reply = try {
            val history = repo.dao.recentChatNow(12).reversed()
            val turns = history.dropLast(1).map { Gemini.Turn(if (it.role == "user") "user" else "model", it.text) } +
                Gemini.Turn("user", "LIVE DATA:\n${context(vitals)}\n\nUSER SAYS: $userText")
            val level = com.aethernova.nova.data.Pet.level(repo.dao.totalXpNow())
            val raw = callWithFallback(settings.current.chatModel, persona(level), mergeTurns(turns))
            handleChatJson(raw)
        } catch (e: NoKeyException) {
            offlineReply(userText, "I'm in offline mode — add a Gemini key and switch the AI on in Settings so I can really talk.")
        } catch (e: QuotaException) {
            offlineReply(userText, "I've used today's free AI chats. I can still log food offline.")
        } catch (e: Exception) {
            offlineReply(userText, if (GeminiRetry.isTransient(e)) "Google's AI is busy right now (tried 3 times)." else "Couldn't reach my brain (${e.message?.take(60)}).")
        }

        repo.dao.insertChat(ChatMessage(timestamp = System.currentTimeMillis(), role = "nova", text = reply.text))
        return reply
    }

    /** Gemini needs alternating roles; merge consecutive same-role turns. */
    private fun mergeTurns(turns: List<Gemini.Turn>): List<Gemini.Turn> {
        val out = mutableListOf<Gemini.Turn>()
        for (t in turns) {
            if (out.isNotEmpty() && out.last().role == t.role) out[out.lastIndex] = Gemini.Turn(t.role, out.last().text + "\n" + t.text)
            else out += t
        }
        while (out.isNotEmpty() && out.first().role != "user") out.removeAt(0)
        return out
    }

    private suspend fun callWithFallback(model: String, system: String, turns: List<Gemini.Turn>, image: Bitmap? = null): String {
        val other = if (model == settings.current.chatModel) settings.current.visionModel else settings.current.chatModel
        return GeminiRetry.run(model, other) { m -> gemini.generate(m, system, turns, image) }
    }

    private var currentExec: ((com.aethernova.nova.phone.Act) -> String)? = null

    private suspend fun handleChatJson(raw: String): BrainReply {
        val obj = runCatching { JSONObject(raw.cleanJson()) }.getOrNull()
            ?: return BrainReply(raw.take(400), Mood.NEUTRAL)
        val logged = mutableListOf<String>()
        val phoneLines = mutableListOf<String>()
        val actions = obj.optJSONArray("actions") ?: JSONArray()
        for (i in 0 until actions.length()) {
            val a = actions.optJSONObject(i) ?: continue
            when (a.optString("type")) {
                "log_food" -> {
                    val items = parseItems(a.optJSONArray("items"), "chat")
                    repo.addFood(items)
                    if (items.isNotEmpty()) logged += "Logged " + items.joinToString { it.name }
                }
                "log_weight" -> a.optDouble("kg").takeIf { !it.isNaN() && it in 30.0..250.0 }?.let {
                    repo.logWeight(it); logged += "Weight $it kg"
                }
                "log_workout" -> {
                    val kind = a.optString("kind", "other")
                    val min = a.optInt("minutes", 30)
                    repo.logWorkout(kind, min); logged += "Workout $kind $min min"
                }
                "set_alarm", "set_timer", "open_app", "call", "text", "torch", "volume", "dnd", "navigate",
                "play_music", "remind", "open_setting", "web_search" -> {
                    val act = com.aethernova.nova.phone.LocalCommands.fromJson(a)
                    val ex = currentExec
                    if (act != null && ex != null) phoneLines += ex(act)
                }
                "log_expense" -> {
                    val amt = a.optDouble("amount")
                    if (!amt.isNaN() && amt > 0 && amt < 5000) {
                        val cat = a.optString("category", "other").takeIf { it in com.aethernova.nova.data.Budget.categories } ?: "other"
                        repo.addExpense(amt, cat, a.optString("note").take(60)); logged += "Spent ${com.aethernova.nova.data.Money.sym}${"%.2f".format(amt)} ($cat)"
                    }
                }
                "remember" -> a.optString("fact").trim().takeIf { it.length in 3..200 }?.let {
                    repo.dao.insertFact(com.aethernova.nova.data.MemoryFact(text = it)); logged += "Remembered: $it"
                }
                "checkin" -> {
                    val sleep = a.optDouble("sleep_hours").takeIf { !it.isNaN() }
                    val mood = a.optInt("mood", 0).takeIf { it in 1..5 }
                    val energy = a.optInt("energy", 0).takeIf { it in 1..5 }
                    if (sleep != null || mood != null || energy != null) {
                        repo.logCheckIn(sleep, mood, energy, null); logged += "Check-in saved"
                    }
                }
            }
        }
        val text = (listOf(obj.optString("reply", "…")) + phoneLines).joinToString(" ").trim()
        return BrainReply(text, Mood.from(obj.optString("mood")), logged)
    }

    private fun parseItems(arr: JSONArray?, source: String): List<FoodEntry> {
        if (arr == null) return emptyList()
        val now = System.currentTimeMillis()
        val day = LocalDate.now().toString()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            FoodEntry(
                timestamp = now, day = day,
                name = o.optString("name", "Food").take(60),
                portion = o.optString("portion", "").take(60),
                kcal = o.optInt("kcal", 0).coerceIn(0, 3000),
                proteinG = o.optDouble("protein_g", 0.0).let { if (it.isNaN()) 0.0 else it.coerceIn(0.0, 200.0) },
                carbsG = o.optDouble("carbs_g", 0.0).let { if (it.isNaN()) 0.0 else it },
                fatG = o.optDouble("fat_g", 0.0).let { if (it.isNaN()) 0.0 else it },
                source = source,
                liver = com.aethernova.nova.data.LiverScore.normalise(o.optString("liver"), o.optString("name")),
            )
        }
    }

    private suspend fun offlineReply(text: String, prefix: String): BrainReply {
        val lower = text.lowercase()
        val looksLikeEating = listOf("had", "ate", "eaten", "kazhichu", "drank", "log").any { it in lower }
        val detail = LocalFood.parseDetailed(text)
        if (looksLikeEating && (detail.found.isNotEmpty() || detail.unmatched.isNotEmpty())) {
            // Offline guesses are never saved silently: a wrong total is worse than no total.
            val now = System.currentTimeMillis()
            val entries = detail.found.map { (item, q) -> LocalFood.toEntry(item, q, "chat", repo.today(), now) }
            val missing = detail.unmatched.takeIf { it.isNotEmpty() }?.let { " I couldn't count: ${it.joinToString(", ")} — add those by hand or send them again when I'm back online." } ?: ""
            val comment = "Offline match with standard portions — fix the numbers if yours were different.$missing"
            return BrainReply("$prefix I matched this offline, so check it before I save it.$missing", Mood.THINKING,
                pending = if (entries.isNotEmpty()) FoodAnalysis(entries, comment, Mood.THINKING) else null)
        }
        if ("joke" in lower) return BrainReply(com.aethernova.nova.data.Jokes.random(), Mood.CHEEKY)
        val (kcal, protein, _) = repo.todayTotals()
        return BrainReply("$prefix Today so far: ${"%.0f".format(protein)} g protein, $kcal kcal.", Mood.SLEEPY)
    }

    // ---------------- Photos ----------------

    suspend fun analyzeFood(photo: Bitmap, note: String): FoodAnalysis {
        val system = """
You are a nutrition estimator. The user's cuisine: ${settings.current.cuisine.ifBlank { "not specified" }}. Identify each food in the photo and estimate portion,
kcal and macros. The user's note is the truth for amounts when given (e.g. "3 dosas, half bowl curry").
Hidden oil and ghee matter. When unsure, estimate HIGH rather than low — undercounting hurts a fat-loss goal.
Calibration: restaurant/takeaway biryani 180–200 kcal per 100 g (a takeaway plate is often 400–500 g);
home curries count 1–2 tbsp oil per serving (+120–240 kcal); fried items count absorbed oil.
For drinks/shakes, add up each ingredient (whole milk 250 ml ≈ 160 kcal, 8 g protein).
If unsure, say so in the comment.
Their food rules: ${rulesLine(settings.current)}
"liver" rates each item for a mildly fatty liver: green = lean protein/veg/fruit/oats/nuts, amber = oily curry/white rice/red meat,
red = fried, sugary, sweets, juice/soda, alcohol, very oily/ghee-heavy.
Return ONLY JSON: {"items":[{"name":string,"portion":string,"kcal":int,"protein_g":number,"carbs_g":number,"fat_g":number,"liver":"green|amber|red"}],
"comment": "one or two short friendly sentences on how this fits their plan", "mood":"happy|proud|worried|cheeky|neutral"}
If the photo is not food, return {"items":[],"comment":"that's not food!","mood":"cheeky"}.
""".trimIndent()
        val (kcal, protein, _) = repo.todayTotals()
        val s = settings.current
        val prompt = "Note from the user: ${note.ifBlank { "(none)" }}\nSo far today: $kcal kcal, ${"%.0f".format(protein)} g protein of ${s.proteinTarget} g."
        val raw = callWithFallback(s.visionModel, system, listOf(Gemini.Turn("user", prompt)), photo)
        val obj = JSONObject(raw.cleanJson())
        return FoodAnalysis(parseItems(obj.optJSONArray("items"), "photo"), obj.optString("comment"), Mood.from(obj.optString("mood")))
    }

    suspend fun outfitCheck(photo: Bitmap, note: String): BrainReply {
        val s = settings.current
        val system = """
You are ${s.buddyName}, ${s.callName}'s cheeky-but-kind friend. Look at their outfit photo and give honest, specific style feedback:
fit, colour combination, what works, one change that would level it up. Consider the occasion if they mention it
(${Plan.nowDescription(LocalDateTime.now())}). Keep it to 2–3 spoken sentences, no markdown.
Return ONLY JSON: {"reply": string, "mood":"happy|proud|cheeky|thinking|neutral"}
""".trimIndent()
        val raw = callWithFallback(s.visionModel, system, listOf(Gemini.Turn("user", "Occasion/note: ${note.ifBlank { "everyday" }}")), photo)
        val obj = JSONObject(raw.cleanJson())
        val reply = BrainReply(obj.optString("reply"), Mood.from(obj.optString("mood")))
        repo.dao.insertChat(ChatMessage(timestamp = System.currentTimeMillis(), role = "user", text = "[outfit photo] $note".trim()))
        repo.dao.insertChat(ChatMessage(timestamp = System.currentTimeMillis(), role = "nova", text = reply.text))
        return reply
    }

    // ---------------- Food smarts ----------------

    /** Photo of the fridge/cupboard -> 3 meal ideas that fit the plan and today's remaining protein. */
    suspend fun fridgeIdeas(photo: Bitmap): BrainReply {
        val s = settings.current
        val (kcal, protein, _) = repo.todayTotals()
        val system = """
You are ${s.buddyName}, ${s.callName}'s food buddy. Look at the photo of their fridge/cupboard and suggest 3 quick meals they can make
with what's visible (assume basic staples and spices too). Their cuisine: ${s.cuisine.ifBlank { "any" }}.
Rules: high protein, go easy on oil and sugar. Their food rules: ${rulesLine(s)}
Health notes to respect: ${s.healthNotes.ifBlank { "none" }}
Today so far: $kcal kcal, ${"%.0f".format(protein)} g protein of ${s.proteinTarget} g target — favour meals that close the protein gap.
Return ONLY JSON: {"reply": "3 ideas, each one short sentence with rough protein, spoken style, no markdown", "mood":"happy|thinking|cheeky"}
""".trimIndent()
        val raw = callWithFallback(s.visionModel, system, listOf(Gemini.Turn("user", "What can I make?")), photo)
        val obj = JSONObject(raw.cleanJson())
        val reply = BrainReply(obj.optString("reply"), Mood.from(obj.optString("mood")))
        repo.dao.insertChat(ChatMessage(timestamp = System.currentTimeMillis(), role = "user", text = "[fridge photo] what can I make?"))
        repo.dao.insertChat(ChatMessage(timestamp = System.currentTimeMillis(), role = "nova", text = reply.text))
        return reply
    }

    /** Builds a weekly grocery list from the plan, their foods and what they actually logged recently. */
    suspend fun groceryList(): List<Pair<String, String>> {
        val s = settings.current
        val recent = repo.dao.dailyTotalsSince(repo.daysAgo(7))
        val system = """
You plan a week of budget-friendly groceries for ${s.callName}${if (s.city.isNotBlank()) " in ${s.city}" else ""}.
Cuisine: ${s.cuisine.ifBlank { "any" }}. Protein target ${s.proteinTarget} g/day, ~${s.kcalTarget} kcal.
Foods they like: ${s.foodLikes.ifBlank { "not specified — choose common high-protein staples" }}.
Their food rules: ${rulesLine(s)} Health notes to respect: ${s.healthNotes.ifBlank { "none" }}
Recent days logged: ${recent.joinToString { "${it.day}: ${"%.0f".format(it.protein)} g protein" }.ifBlank { "none" }}.
Return ONLY JSON: {"items":[{"name": string, "qty": string}]} with 12–18 items for one week.
""".trimIndent()
        val raw = callWithFallback(s.chatModel, system, listOf(Gemini.Turn("user", "Make this week's list.")))
        val arr = JSONObject(raw.cleanJson()).optJSONArray("items") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { it.optString("name").take(40) to it.optString("qty").take(30) }
        }.filter { it.first.isNotBlank() }
    }

    // ---------------- Weekly coach review ----------------

    /** AI review of the week with conservative, clamped target changes. Falls back to rules offline. */
    suspend fun weeklyReview(w: com.aethernova.nova.data.Report.Week, insights: List<String>, slope: Double?): com.aethernova.nova.data.WeeklyRules.Proposal {
        val rules = com.aethernova.nova.data.WeeklyRules.propose(w, slope)
        val s = settings.current
        val system = """
You are ${s.buddyName}, ${s.callName}'s coach. Review their week and suggest at most small changes for next week.
Their goals: ${s.goal.ifBlank { "healthy fat loss while keeping muscle" }}. If losing weight, aim for 0.3–0.7 kg/week; protein first;
never below ${s.kcalFloor} kcal. Their food rules: ${rulesLine(s)}
Return ONLY JSON: {"summary": "3 short spoken sentences: one win, one problem, one focus for next week",
 "kcal": int|null (new daily target, change by at most 150 from ${s.kcalTarget}), "protein": int|null (50–220),
 "workout": "same|harder|easier"}
""".trimIndent()
        val data = "Week: avg ${w.avgKcal.toInt()} kcal (target ${w.kcalTarget}), avg protein ${w.avgProtein.toInt()} g (target ${w.proteinTarget}), " +
            "${w.sessions}/3 workouts, ${w.walkMin} min walking, water ${w.avgWaterMl.toInt()} ml/day, ${w.daysLogged}/7 days logged, " +
            "mood ${w.avgMood?.let { "%.1f".format(it) } ?: "?"}, weight trend ${slope?.let { "%+.2f kg/week".format(it) } ?: "unknown"}. " +
            "Insights: ${insights.joinToString(" ")}"
        return try {
            val o = JSONObject(gemini.generate(s.chatModel, system, listOf(Gemini.Turn("user", data))).cleanJson())
            val kcal = o.optInt("kcal", 0).takeIf { it > 0 }?.coerceIn(maxOf(s.kcalFloor, s.kcalTarget - 150), minOf(4000, s.kcalTarget + 150))
            val protein = o.optInt("protein", 0).takeIf { it > 0 }?.coerceIn(50, 220)
            com.aethernova.nova.data.WeeklyRules.Proposal(kcal?.takeIf { it != s.kcalTarget }, protein?.takeIf { it != s.proteinTarget },
                o.optString("workout", "same").takeIf { it in listOf("same", "harder", "easier") } ?: "same", o.optString("summary", rules.summary))
        } catch (e: Exception) { rules }
    }

    // ---------------- v0.9: career, study, cooking ----------------

    /** 5 interview questions for a role (and optional company). Falls back to the offline bank. */
    suspend fun interviewQuestions(role: String, company: String): List<String> {
        val s = settings.current
        val system = """
You are a friendly but realistic interviewer. Candidate background: ${s.about.ifBlank { "early-career candidate" }}. Role: $role${if (company.isNotBlank()) " at $company" else ""}.
Write 5 interview questions: 1 opener, 2 role/technical (entry level), 2 behavioural (STAR). Keep each under 25 words.
Return ONLY JSON: {"questions": [string, string, string, string, string]}
""".trimIndent()
        return try {
            val arr = JSONObject(callWithFallback(s.chatModel, system, listOf(Gemini.Turn("user", "Start the interview."))).cleanJson()).optJSONArray("questions") ?: JSONArray()
            (0 until arr.length()).map { arr.optString(it).take(200) }.filter { it.isNotBlank() }.take(5).takeIf { it.size >= 3 }
                ?: com.aethernova.nova.data.Interview.offlineQuestions(role)
        } catch (e: Exception) { com.aethernova.nova.data.Interview.offlineQuestions(role) }
    }

    /** Scores one answer out of 10 with 2–3 tips. Falls back to the offline STAR scorer. */
    suspend fun interviewFeedback(role: String, question: String, answer: String): com.aethernova.nova.data.Interview.Feedback {
        val s = settings.current
        val offline = com.aethernova.nova.data.Interview.score(answer)
        if (answer.isBlank()) return offline
        val system = """
You are an interview coach. Role: $role. Judge the candidate's spoken answer (speech-to-text, ignore small transcription errors).
Be honest and specific; for behavioural questions check STAR (Situation, Task, Action, Result).
Return ONLY JSON: {"score": 1-10, "tips": ["max 3 short, concrete tips; first one = the single biggest improvement"]}
""".trimIndent()
        return try {
            val o = JSONObject(callWithFallback(s.chatModel, system, listOf(Gemini.Turn("user", "Question: $question\nAnswer: $answer"))).cleanJson())
            val tips = o.optJSONArray("tips")?.let { a -> (0 until a.length()).map { a.optString(it).take(200) }.filter { it.isNotBlank() } }.orEmpty()
            com.aethernova.nova.data.Interview.Feedback(o.optInt("score", offline.score).coerceIn(1, 10), tips.take(3).ifEmpty { offline.tips })
        } catch (e: Exception) { offline }
    }

    /** Flashcards from a photo of lecture slides or notes. */
    suspend fun flashcardsFromPhoto(photo: Bitmap, deck: String): List<Pair<String, String>> {
        val s = settings.current
        val system = """
You make revision flashcards for a university AI & Data Science student (module/deck: "$deck").
Read the slide/notes in the photo and write 4–8 cards testing the key ideas, definitions and formulas. Questions must be answerable
from the slide; answers short (max 40 words). Skip titles, logos and admin text.
Return ONLY JSON: {"cards": [{"front": string, "back": string}]}
""".trimIndent()
        val arr = JSONObject(callWithFallback(s.visionModel, system, listOf(Gemini.Turn("user", "Make flashcards from this.")), photo).cleanJson()).optJSONArray("cards") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { it.optString("front").take(300) to it.optString("back").take(600) } }
            .filter { it.first.isNotBlank() && it.second.isNotBlank() }.take(12)
    }

    /** A recipe for the cooking guide, built from what the user asks for. */
    suspend fun recipe(ask: String): com.aethernova.nova.data.Cooking.Recipe {
        val s = settings.current
        val system = """
You write simple, high-protein home recipes${if (s.cuisine.isNotBlank()) " in ${s.cuisine} style" else ""} for someone cooking in a small kitchen.
Rules: low oil (max 2 tsp), no added sugar, no deep frying. Their food rules: ${rulesLine(s)} Health notes to respect: ${s.healthNotes.ifBlank { "none" }}. Steps short and speakable (max 25 words each).
Put a timer in seconds on steps that involve waiting (boiling, simmering, resting), else 0.
Return ONLY JSON: {"name": string, "serves": int, "kcal": int (per serving), "protein": int (g per serving),
 "ingredients": [string], "steps": [{"text": string, "timer": int}]}
""".trimIndent()
        val o = JSONObject(callWithFallback(s.chatModel, system, listOf(Gemini.Turn("user", ask))).cleanJson())
        val ing = o.optJSONArray("ingredients")?.let { a -> (0 until a.length()).map { a.optString(it).take(80) } }.orEmpty()
        val steps = o.optJSONArray("steps")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let {
            com.aethernova.nova.data.Cooking.Step(it.optString("text").take(250), it.optInt("timer", 0).coerceIn(0, 3 * 3600)) } } }.orEmpty()
            .filter { it.text.isNotBlank() }
        require(steps.isNotEmpty()) { "Empty recipe" }
        return com.aethernova.nova.data.Cooking.Recipe(o.optString("name", ask).take(60), o.optInt("serves", 2).coerceIn(1, 6),
            o.optInt("kcal", 0).coerceIn(0, 2000), o.optInt("protein", 0).coerceIn(0, 200), ing.take(20), steps.take(20))
    }

    /** 2-day meal prep plan hitting their targets. */
    suspend fun mealPrep(note: String): com.aethernova.nova.data.MealPrep.Plan2 {
        val s = settings.current
        val system = """
You plan 2 days of meal prep for ${s.callName}: ${s.cuisine.ifBlank { "any cuisine" }}, ~${s.kcalTarget} kcal and ${s.proteinTarget} g protein per day.
Likes: ${s.foodLikes.ifBlank { "not specified" }}. Food rules: ${rulesLine(s)} Health notes to respect: ${s.healthNotes.ifBlank { "none" }}.
Low oil, no added sugar, cook in one session for both days. Their routine today: ${Plan.todaySummary(LocalDate.now())} (pack food for long shifts).
Extra request: ${note.ifBlank { "none" }}.
Return ONLY JSON: {"days":[{"label":"Day 1","meals":[{"slot":string,"name":string,"kcal":int,"protein":int}]}, {"label":"Day 2", ...}],
 "shopping":[{"name":string,"qty":string}], "prep":["4–6 short batch-cooking steps"]}
""".trimIndent()
        val o = JSONObject(callWithFallback(s.chatModel, system, listOf(Gemini.Turn("user", "Plan my next 2 days."))).cleanJson())
        return com.aethernova.nova.data.MealPrep.parse(o)
    }

    // ---------------- Proactive lines (used by reminders) ----------------

    suspend fun eveningLine(): String {
        val (kcal, protein, _) = repo.todayTotals()
        val s = settings.current
        val gap = s.proteinTarget - protein
        return when {
            kcal == 0 -> "Nothing logged today${if (s.manglish) ", machane" else ""}. Snap your dinner so I can keep count."
            gap > 25 -> "You're ${"%.0f".format(gap)} g short on protein. Dinner idea: eggs, chicken or skyr — not more rice."
            kcal > s.kcalTarget + 200 -> "Bit over today ($kcal kcal). Light dinner, lots of water, no stress — tomorrow's a new day."
            else -> "Solid day: ${"%.0f".format(protein)} g protein, $kcal kcal. Log dinner and sleep by 22:15."
        }
    }
}
