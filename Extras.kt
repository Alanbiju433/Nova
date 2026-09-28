package com.aethernova.nova.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.MonthDay
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

private val http = OkHttpClient.Builder().readTimeout(20, TimeUnit.SECONDS).build()

private suspend fun getJson(url: String): String? = withContext(Dispatchers.IO) {
    runCatching {
        http.newCall(Request.Builder().url(url).header("User-Agent", "Nova-HealthBuddy/0.5").build()).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }
    }.getOrNull()
}

// ======================= Weather (Open-Meteo, free, no key) =======================

object Weather {
    data class Hour(val time: LocalDateTime, val tempC: Double, val rainPct: Int, val code: Int)

    /** Forecast for the user's town (set in setup). Null when no location is set or offline. */
    suspend fun today(lat: Double, lon: Double): List<Hour>? {
        if (lat == 0.0 && lon == 0.0) return null
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&hourly=temperature_2m,precipitation_probability,weather_code&forecast_days=2&timezone=auto"
        return getJson(url)?.let { runCatching { parse(it) }.getOrNull() }
    }

    data class Place(val name: String, val lat: Double, val lon: Double)

    /** Town search (Open-Meteo geocoding, free, no key). Only the town name leaves the phone. */
    suspend fun findPlace(query: String): List<Place> {
        val url = "https://geocoding-api.open-meteo.com/v1/search?count=5&format=json&name=" + java.net.URLEncoder.encode(query.trim(), "UTF-8")
        return getJson(url)?.let { runCatching { parsePlaces(it) }.getOrNull() }.orEmpty()
    }

    fun parsePlaces(json: String): List<Place> {
        val arr = JSONObject(json).optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Place(listOf(o.optString("name"), o.optString("admin1"), o.optString("country")).filter { it.isNotBlank() }.distinct().joinToString(", "),
                o.getDouble("latitude"), o.getDouble("longitude"))
        }
    }

    fun parse(json: String): List<Hour> {
        val h = JSONObject(json).getJSONObject("hourly")
        val t = h.getJSONArray("time"); val temp = h.getJSONArray("temperature_2m")
        val rain = h.getJSONArray("precipitation_probability"); val code = h.getJSONArray("weather_code")
        return (0 until t.length()).map {
            Hour(LocalDateTime.parse(t.getString(it)), temp.optDouble(it, 0.0), rain.optInt(it, 0), code.optInt(it, 0))
        }
    }

    fun describe(code: Int) = when (code) {
        0 -> "clear"; 1, 2 -> "partly cloudy"; 3 -> "cloudy"; 45, 48 -> "foggy"
        in 51..57 -> "drizzly"; in 61..67, in 80..82 -> "rainy"; in 71..77, 85, 86 -> "snowy"; in 95..99 -> "stormy"
        else -> "mixed"
    }

    /** Short summary for a time window, e.g. the morning walk (05–06) or the walk home from work. */
    fun window(hours: List<Hour>, date: LocalDate, fromHour: Int, toHour: Int): Hour? {
        val w = hours.filter { it.time.toLocalDate() == date && it.time.hour in fromHour..toHour }
        if (w.isEmpty()) return null
        return Hour(w.first().time, w.minOf { it.tempC }, w.maxOf { it.rainPct }, w.maxByOrNull { it.rainPct }!!.code)
    }
}

/** Walk advice from the weather, the light and the day. */
object WalkAdvisor {
    /** Rough: before ~6 AM it's dark outside May–July in northern latitudes. */
    fun isDarkEarly(date: LocalDate) = date.monthValue !in 5..7

    fun advise(date: LocalDate, rainPct: Int?, tempC: Double?): String {
        val weekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
        val why = when {
            (rainPct ?: 0) >= 60 -> "Rain likely (${rainPct}%) — pick a short, well-lit loop you can cut short, or do 20 minutes of indoor marching."
            isDarkEarly(date) -> "It may still be dark early — stick to lit streets and wear something bright."
            weekend -> "Weekend and dry — a longer walk somewhere green would be perfect."
            else -> "Dry out — a brisk loop, phone in your pocket."
        }
        val cold = if (tempC != null && tempC < 4) " It's ${tempC.toInt()}°C: layers, hat and gloves." else ""
        return "$why$cold"
    }
}

// ======================= Festivals =======================

object Festivals {
    private val onam = mapOf(
        2026 to LocalDate.of(2026, 8, 26), 2027 to LocalDate.of(2027, 9, 12), 2028 to LocalDate.of(2028, 9, 1),
        2029 to LocalDate.of(2029, 8, 22), 2030 to LocalDate.of(2030, 9, 9),
    )

    /** Western Easter Sunday (Anonymous Gregorian algorithm). */
    fun easter(year: Int): LocalDate {
        val a = year % 19; val b = year / 100; val c = year % 100
        val d = b / 4; val e = b % 4; val f = (b + 8) / 25; val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30; val i = c / 4; val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7; val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31; val day = ((h + l - 7 * m + 114) % 31) + 1
        return LocalDate.of(year, month, day)
    }

    /** Greeting for today, or null. */
    fun today(date: LocalDate, manglish: Boolean): String? {
        val md = MonthDay.from(date)
        val e = easter(date.year)
        return when {
            onam[date.year] == date && manglish -> "Onashamsakal machane! Happy Onam — enjoy the sadya, but go easy on the payasam."
            md == MonthDay.of(4, 14) && manglish -> "Vishu ashamsakal! Hope your Vishukkani was a good one."
            md == MonthDay.of(11, 1) && manglish -> "Happy Kerala Piravi! Kerala turns ${date.year - 1956} today."
            md == MonthDay.of(12, 25) -> if (manglish) "Happy Christmas machane! Enjoy church and the food — tomorrow we walk it off." else "Happy Christmas! Enjoy the day and the food."
            md == MonthDay.of(12, 24) -> "Christmas Eve! Carols tonight?"
            date == e -> "Happy Easter! Enjoy the feast today."
            date == e.minusDays(2) -> "Good Friday today — a quiet day. Take care of yourself."
            md == MonthDay.of(1, 1) -> "Happy New Year! New year, same streak — let's keep it going."
            else -> null
        }
    }

    fun onamDate(year: Int) = onam[year]
}

// ======================= Jokes =======================

object Jokes {
    val list = listOf(
        "Why did the dosa go to the gym? It wanted to be a little less flat.",
        "I asked the protein shake for help. It said: have you tried eggs?",
        "My Malayalam teacher said I talk too much. I said, athu njan alla, it's the Manglish.",
        "Why don't parottas ever win arguments? They always fold under pressure.",
        "The chicken crossed the road. Then it saw your protein target and kept running.",
        "What does a Kerala uncle say at the gym? Ee machine okke ente kaalathu undayirunnengil...",
        "I tried to count my calories. Lost count at the second plate of biryani.",
        "Why did the coconut get promoted? Because it was outstanding in its field.",
        "My fitness app says I walked 10 km. My legs say it was 50.",
        "Why was the rice cooker so calm? It had seen things boil over before.",
        "What's a robot's favourite Kerala dish? Micro-chips with sambar.",
        "Why did the student bring a ladder to the dissertation meeting? To reach the higher marks.",
        "I told my alarm I'd wake up at 5. It laughed. Then it rang at 5. Now we're not friends.",
        "Why did the banana go to the doctor? It wasn't peeling well.",
        "The fish curry and the fish fry had an argument. The curry won — it was more saucy.",
    )
    fun random() = list.random()
}

// ======================= Football (TheSportsDB free API) =======================

object Football {
    private const val KEY = "123"   // TheSportsDB public free key

    data class Match(val title: String, val date: String, val time: String, val home: String, val away: String, val homeScore: Int?, val awayScore: Int?, val league: String) {
        val scoreLine get() = if (homeScore != null && awayScore != null) "$home $homeScore–$awayScore $away" else title
    }

    suspend fun findTeam(name: String): Pair<String, String>? {
        val json = getJson("https://www.thesportsdb.com/api/v1/json/$KEY/searchteams.php?t=" + URLEncoder.encode(name, "UTF-8")) ?: return null
        val teams = JSONObject(json).optJSONArray("teams") ?: return null
        val soccer = (0 until teams.length()).map { teams.getJSONObject(it) }.firstOrNull { it.optString("strSport") == "Soccer" } ?: teams.optJSONObject(0) ?: return null
        return soccer.optString("idTeam") to soccer.optString("strTeam")
    }

    suspend fun next(teamId: String) = getJson("https://www.thesportsdb.com/api/v1/json/$KEY/eventsnext.php?id=$teamId")?.let { parse(it, "events") }.orEmpty()
    suspend fun last(teamId: String) = getJson("https://www.thesportsdb.com/api/v1/json/$KEY/eventslast.php?id=$teamId")?.let { parse(it, "results") }.orEmpty()

    fun parse(json: String, key: String): List<Match> {
        val arr = JSONObject(json).optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
            fun score(k: String) = o.optString(k).toIntOrNull()
            Match(o.optString("strEvent"), o.optString("dateEvent"), o.optString("strTime").take(5),
                o.optString("strHomeTeam"), o.optString("strAwayTeam"), score("intHomeScore"), score("intAwayScore"), o.optString("strLeague"))
        }
    }
}

// ======================= Budget =======================

object Budget {
    val categories = listOf("food", "groceries", "transport", "bills", "fun", "family", "other")

    data class Month(val spent: Double, val budget: Int, val daysLeft: Int, val byCategory: Map<String, Double>) {
        val left get() = budget - spent
        val perDayLeft get() = if (daysLeft > 0) left / daysLeft else left
    }

    fun month(expenses: List<Expense>, budget: Int, today: LocalDate): Month {
        val first = today.withDayOfMonth(1)
        val inMonth = expenses.filter { runCatching { !LocalDate.parse(it.day).isBefore(first) }.getOrDefault(false) }
        val daysLeft = ChronoUnit.DAYS.between(today, first.plusMonths(1)).toInt()
        return Month(inMonth.sumOf { it.amount }, budget, daysLeft, inMonth.groupBy { it.category }.mapValues { e -> e.value.sumOf { it.amount } })
    }

    fun comment(m: Month): String = when {
        m.budget <= 0 -> "Set a monthly spending budget in Settings and I'll keep an eye on it."
        m.left < 0 -> "You're ${Money.sym}${"%.2f".format(-m.left)} over this month's budget. Let's pause takeaways until the 1st."
        m.perDayLeft < 5 -> "Tight month: ${Money.sym}${"%.2f".format(m.perDayLeft)} a day left. Cook at home."
        else -> "On track: ${Money.sym}${"%.2f".format(m.left)} left, about ${Money.sym}${"%.2f".format(m.perDayLeft)} a day."
    }
}

// ======================= Morning briefing =======================

object Briefing {
    fun build(
        date: LocalDate, name: String, manglish: Boolean,
        walkWeather: Weather.Hour?, eveningWeather: Weather.Hour?,
        workout: String?, proteinTarget: Int, dissertationDays: Long?, festival: String?, nextMatch: String?,
    ): String = buildString {
        append(if (manglish) "Good morning machane! " else "Good morning $name! ")
        festival?.let { append(it).append(' ') }
        walkWeather?.let { append("Walk time: ${it.tempC.toInt()}°C and ${Weather.describe(it.code)}. ") }
        if (Plan.routine.walkAt != null) append("Walk: ").append(WalkAdvisor.advise(date, walkWeather?.rainPct, walkWeather?.tempC)).append(' ')
        val blocks = Plan.blocksFor(date).filter { it.kind != Kind.WALK && it.kind != Kind.BED && it.kind != Kind.WORKOUT }
        if (blocks.isNotEmpty()) append("Today: " + blocks.joinToString(", ") { "${it.what} at ${it.start}" } + ". ")
        eveningWeather?.let { if (it.rainPct >= 50) append("Rain likely around your walk home (${it.rainPct}%) — take a jacket. ") }
        append(workout?.let { "It's a workout day: $it. " } ?: "Rest day — keep moving with a walk. ")
        append("Protein target $proteinTarget grams — a protein-rich breakfast gets you started. ")
        dissertationDays?.let { if (it in 0..400) append("Dissertation due in $it days. ") }
        nextMatch?.let { append("Football: $it. ") }
    }.trim()
}
