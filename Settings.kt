package com.aethernova.nova.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate
import java.util.Locale

/** Currency symbol shown in the UI (set from settings). */
object Money {
    @Volatile var sym: String = "£"
    fun defaultSymbol(): String = runCatching {
        if (Locale.getDefault().country == "GB") "£" else java.util.Currency.getInstance(Locale.getDefault()).getSymbol(Locale.getDefault())
    }.getOrDefault("£")
}

/** Emergency advice matched to the phone's country. */
object Emergency {
    fun line(country: String = Locale.getDefault().country): String = when (country.uppercase(Locale.ROOT)) {
        "GB" -> "call 999 now (or NHS 111 if it's urgent but not an emergency)"
        "US", "CA" -> "call 911 now"
        "IN" -> "call 112 now"
        "AU" -> "call 000 now"
        "NZ" -> "call 111 now"
        else -> "call your local emergency number (112 in most of Europe) now"
    }
}

data class NovaSettings(
    val apiKey: String = "",
    val buddyName: String = "Nova",
    val visionModel: String = "gemini-flash-latest",
    val chatModel: String = "gemini-flash-lite-latest",
    val voiceLang: String = "en-GB",        // en-GB or ml-IN
    val speakReplies: Boolean = true,
    val manglish: Boolean = false,          // sprinkle Malayalam words into English replies
    val kcalTarget: Int = 2000,
    val proteinTarget: Int = 110,
    val stepsTarget: Int = 9000,
    val shareResults: Boolean = true,       // send test *numbers* (never images) to the AI
    val reminders: Boolean = true,
    val planStart: String = LocalDate.now().toString(),
    val updateRepo: String = "Alanbiju433/nova",
    val waterTarget: Int = 2500,
    val skin: String = "Classic",
    val dissertationDue: String = "",        // yyyy-MM-dd, blank = no dissertation
    val focusMinutes: Int = 25,
    val roam: Boolean = true,               // Nova wanders around the screen when idle
    val monthlyBudget: Int = 0,             // spending money per month after fixed bills (0 = not set)
    val favouriteTeam: String = "",
    val teamId: String = "",
    val handsFree: Boolean = false,         // "Hey Nova" while the app is open
    val calendarId: Long = -1L,
    val nudges: Boolean = true,             // two-stage schedule reminders
    val loudNudges: Boolean = true,         // second stage uses the alarm sound + Nova shouting
    val dissWords: Int = 10000,             // dissertation word target (check the module handbook)
    val windDown: Boolean = true,           // gentle breathing nudge after late shifts
    val eyeBreaks: Boolean = true,          // 20-20-20 prompts during focus blocks
    // ---- v1.0: everything personal is entered by the user in setup ----
    val onboarded: Boolean = false,
    val adultConfirmed: Boolean = false,    // Nova is for adults (18+)
    val aiConsent: Boolean = false,         // agreed that chats/photos go to Google Gemini when AI is used
    val userName: String = "",
    val about: String = "",                 // e.g. "22, student, works evening shifts"
    val goal: String = "",
    val age: Int = 0,
    val sex: String = "",                   // male | female | "" (only used by the target calculator)
    val heightCm: Int = 0,
    val cuisine: String = "",
    val foodLikes: String = "",
    val dietRules: String = "",
    val healthNotes: String = "",           // things the buddy must respect (allergies, conditions the user chose to share)
    val city: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val currency: String = Money.defaultSymbol(),
    val routineJson: String = "",
    val liverScore: Boolean = false,        // show the fatty-liver-friendly food rating
) {
    /** Lowest daily calorie target Nova will ever suggest. */
    val kcalFloor get() = if (sex == "female") 1400 else 1600
    val hasLocation get() = city.isNotBlank() && !(lat == 0.0 && lon == 0.0)
    /** How Nova addresses the user in English. */
    val callName get() = userName.trim().split(" ").firstOrNull()?.takeIf { it.isNotBlank() } ?: "friend"
}

class SettingsStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("nova_settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<NovaSettings> = _state
    val current: NovaSettings get() = _state.value

    private fun load(): NovaSettings {
        val d = NovaSettings()
        if (!prefs.contains("planStart")) prefs.edit().putString("planStart", d.planStart).apply()
        return NovaSettings(
            apiKey = Secure.get(context, "gemini_key"),
            buddyName = prefs.getString("buddyName", d.buddyName)!!,
            visionModel = prefs.getString("visionModel", d.visionModel)!!,
            chatModel = prefs.getString("chatModel", d.chatModel)!!,
            voiceLang = prefs.getString("voiceLang", d.voiceLang)!!,
            speakReplies = prefs.getBoolean("speakReplies", d.speakReplies),
            manglish = prefs.getBoolean("manglish", d.manglish),
            kcalTarget = prefs.getInt("kcalTarget", d.kcalTarget),
            proteinTarget = prefs.getInt("proteinTarget", d.proteinTarget),
            stepsTarget = prefs.getInt("stepsTarget", d.stepsTarget),
            shareResults = prefs.getBoolean("shareResults", d.shareResults),
            reminders = prefs.getBoolean("reminders", d.reminders),
            planStart = prefs.getString("planStart", d.planStart)!!,
            updateRepo = prefs.getString("updateRepo", d.updateRepo)!!,
            waterTarget = prefs.getInt("waterTarget", d.waterTarget),
            skin = prefs.getString("skin", d.skin)!!,
            dissertationDue = prefs.getString("dissertationDue", d.dissertationDue)!!,
            focusMinutes = prefs.getInt("focusMinutes", d.focusMinutes),
            roam = prefs.getBoolean("roam", d.roam),
            monthlyBudget = prefs.getInt("monthlyBudget", d.monthlyBudget),
            favouriteTeam = prefs.getString("favouriteTeam", d.favouriteTeam)!!,
            teamId = prefs.getString("teamId", d.teamId)!!,
            handsFree = prefs.getBoolean("handsFree", d.handsFree),
            calendarId = prefs.getLong("calendarId", d.calendarId),
            nudges = prefs.getBoolean("nudges", d.nudges),
            loudNudges = prefs.getBoolean("loudNudges", d.loudNudges),
            dissWords = prefs.getInt("dissWords", d.dissWords),
            windDown = prefs.getBoolean("windDown", d.windDown),
            eyeBreaks = prefs.getBoolean("eyeBreaks", d.eyeBreaks),
            onboarded = prefs.getBoolean("onboarded", d.onboarded),
            adultConfirmed = prefs.getBoolean("adultConfirmed", d.adultConfirmed),
            aiConsent = prefs.getBoolean("aiConsent", d.aiConsent),
            userName = prefs.getString("userName", d.userName)!!,
            about = prefs.getString("about", d.about)!!,
            goal = prefs.getString("goal", d.goal)!!,
            age = prefs.getInt("age", d.age),
            sex = prefs.getString("sex", d.sex)!!,
            heightCm = prefs.getInt("heightCm", d.heightCm),
            cuisine = prefs.getString("cuisine", d.cuisine)!!,
            foodLikes = prefs.getString("foodLikes", d.foodLikes)!!,
            dietRules = prefs.getString("dietRules", d.dietRules)!!,
            healthNotes = prefs.getString("healthNotes", d.healthNotes)!!,
            city = prefs.getString("city", d.city)!!,
            lat = prefs.getFloat("lat", 0f).toDouble(),
            lon = prefs.getFloat("lon", 0f).toDouble(),
            currency = prefs.getString("currency", d.currency)!!,
            routineJson = prefs.getString("routineJson", d.routineJson)!!,
            liverScore = prefs.getBoolean("liverScore", d.liverScore),
        ).also { apply(it) }
    }

    /** Pushes settings that pure helpers read without a Context. */
    private fun apply(s: NovaSettings) {
        Money.sym = s.currency.ifBlank { "£" }
        Plan.routine = if (DemoData.isDemo(context)) Routine.EXAMPLE else Routine.parse(s.routineJson)
    }

    fun save(s: NovaSettings) {
        Secure.put(context, "gemini_key", s.apiKey.trim())
        prefs.edit()
            .putString("buddyName", s.buddyName.ifBlank { "Nova" })
            .putString("visionModel", s.visionModel.trim())
            .putString("chatModel", s.chatModel.trim())
            .putString("voiceLang", s.voiceLang)
            .putBoolean("speakReplies", s.speakReplies)
            .putBoolean("manglish", s.manglish)
            .putInt("kcalTarget", s.kcalTarget)
            .putInt("proteinTarget", s.proteinTarget)
            .putInt("stepsTarget", s.stepsTarget)
            .putBoolean("shareResults", s.shareResults)
            .putBoolean("reminders", s.reminders)
            .putString("planStart", s.planStart)
            .putString("updateRepo", s.updateRepo.trim())
            .putInt("waterTarget", s.waterTarget)
            .putString("skin", s.skin)
            .putString("dissertationDue", s.dissertationDue.trim())
            .putInt("focusMinutes", s.focusMinutes)
            .putBoolean("roam", s.roam)
            .putInt("monthlyBudget", s.monthlyBudget)
            .putString("favouriteTeam", s.favouriteTeam.trim())
            .putString("teamId", s.teamId)
            .putBoolean("handsFree", s.handsFree)
            .putLong("calendarId", s.calendarId)
            .putBoolean("nudges", s.nudges)
            .putBoolean("loudNudges", s.loudNudges)
            .putInt("dissWords", s.dissWords.coerceIn(1000, 50000))
            .putBoolean("windDown", s.windDown)
            .putBoolean("eyeBreaks", s.eyeBreaks)
            .putBoolean("onboarded", s.onboarded)
            .putBoolean("adultConfirmed", s.adultConfirmed)
            .putBoolean("aiConsent", s.aiConsent)
            .putString("userName", s.userName.trim().take(40))
            .putString("about", s.about.trim().take(300))
            .putString("goal", s.goal.trim().take(300))
            .putInt("age", s.age.coerceIn(0, 120))
            .putString("sex", s.sex)
            .putInt("heightCm", s.heightCm.coerceIn(0, 250))
            .putString("cuisine", s.cuisine.trim().take(80))
            .putString("foodLikes", s.foodLikes.trim().take(500))
            .putString("dietRules", s.dietRules.trim().take(1000))
            .putString("healthNotes", s.healthNotes.trim().take(1000))
            .putString("city", s.city.trim().take(80))
            .putFloat("lat", s.lat.toFloat())
            .putFloat("lon", s.lon.toFloat())
            .putString("currency", s.currency.trim().take(4).ifBlank { "£" })
            .putString("routineJson", s.routineJson)
            .putBoolean("liverScore", s.liverScore)
            .apply()
        _state.value = load()
    }
}
