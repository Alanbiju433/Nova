package com.aethernova.nova.phone

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** A phone action Nova can perform, from the local parser or the AI's JSON. */
sealed interface Act {
    data class Alarm(val hour: Int, val minute: Int, val label: String = "") : Act
    data class Timer(val seconds: Int, val label: String = "") : Act
    data class OpenApp(val name: String) : Act
    data class Call(val who: String) : Act
    data class Text(val who: String, val message: String) : Act
    data class Torch(val on: Boolean) : Act
    data class Volume(val change: String) : Act          // up | down | mute | max | 0..100
    data class Dnd(val on: Boolean) : Act
    data class Navigate(val destination: String, val mode: String = "w") : Act   // w walk, d drive, r transit
    data class Music(val query: String) : Act
    data class Remind(val at: LocalDateTime, val text: String) : Act
    data class Setting(val which: String) : Act          // wifi | bluetooth | internet | battery | display
    data class Search(val query: String) : Act
}

/**
 * Understands everyday phone commands offline (no AI needed, instant, free):
 * "set an alarm for 6:30", "timer for 10 minutes", "open YouTube", "torch on", "volume up",
 * "call mum", "text Rahul I'm on my way", "navigate to the station", "play Malayalam songs",
 * "remind me at 9pm to take the bins out", "open wifi settings".
 */
object LocalCommands {
    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9:.' ]"), " ").replace(Regex("\\s+"), " ").trim()

    private val numbers = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8,
        "nine" to 9, "ten" to 10, "fifteen" to 15, "twenty" to 20, "thirty" to 30, "forty five" to 45, "a" to 1, "an" to 1, "half an" to 30)

    /** "6:30", "6.30", "630", "6 30 am", "18:45", "9pm", "7 o'clock" -> LocalTime (am/pm aware). */
    fun parseTime(t: String): LocalTime? {
        val m = Regex("""\b(\d{1,2})(?:[:.](\d{2})|\s(\d{2})(?=\s*(?:am|pm)\b))?\s*(am|pm|o'clock)?\b""").find(t) ?: return null
        var h = m.groupValues[1].toInt()
        val min = (m.groupValues[2].ifBlank { m.groupValues[3] }).ifBlank { "0" }.toInt()
        when (m.groupValues[4]) { "pm" -> if (h < 12) h += 12; "am" -> if (h == 12) h = 0 }
        if (h > 23 || min > 59) return null
        return LocalTime.of(h, min)
    }

    private fun duration(t: String): Int? {
        var total = 0; var found = false
        Regex("""(\d+|one|two|three|four|five|six|seven|eight|nine|ten|fifteen|twenty|thirty|forty five|half an|an|a)\s*(hours?|hrs?|minutes?|mins?|seconds?|secs?)""").findAll(t).forEach {
            val n = it.groupValues[1].toIntOrNull() ?: numbers[it.groupValues[1]] ?: return@forEach
            val unit = it.groupValues[2]
            total += when {
                unit.startsWith("h") -> if (it.groupValues[1] == "half an") 1800 else n * 3600
                unit.startsWith("m") -> n * 60
                else -> n
            }
            found = true
        }
        return if (found && total > 0) total else null
    }

    fun parse(raw: String, now: LocalDateTime = LocalDateTime.now()): Act? {
        val t = norm(raw)
        val afterVerb = { verbs: List<String> -> verbs.firstNotNullOfOrNull { v -> Regex("""\b$v\s+(.+)""").find(t)?.groupValues?.get(1)?.trim() } }
        return when {
            Regex("""\b(set|make|create|put)\b.*\balarm\b|\bwake me\b|^alarm\b""").containsMatchIn(t) ->
                parseTime(t)?.let { Act.Alarm(it.hour, it.minute) }
            Regex("""\b(set|start|put)\b.*\btimer\b|^timer\b|\btimer for\b|\b(min|mins|minute|minutes|second|seconds)\s+timer\b""").containsMatchIn(t) ->
                duration(t)?.let { Act.Timer(it) }
            Regex("""\bremind me\b""").containsMatchIn(t) -> {
                val what = Regex("""\bto (.+)""").find(t.substringAfter("remind me"))?.groupValues?.get(1)?.trim() ?: "reminder"
                val inDur = Regex("""\bin (.+?)(?: to |$)""").find(t)?.groupValues?.get(1)?.let { duration(it) }
                when {
                    inDur != null -> Act.Remind(now.plusSeconds(inDur.toLong()), what.substringBefore(" in "))
                    else -> Regex("""\bat ([0-9: .]+(?:am|pm)?)""").find(t)?.groupValues?.get(1)?.let { parseTime(it) }?.let { time ->
                        var at = now.toLocalDate().atTime(time)
                        if (at.isBefore(now)) at = at.plusDays(1)
                        if (t.contains("tomorrow")) at = now.toLocalDate().plusDays(1).atTime(time)
                        Act.Remind(at, what.replace(Regex("""\s*(tomorrow|at [0-9: .]+(am|pm)?)\s*"""), " ").trim())
                    }
                }
            }
            Regex("""\b(torch|flashlight|flash light)\b""").containsMatchIn(t) -> Act.Torch(!Regex("""\b(off|stop|disable)\b""").containsMatchIn(t))
            Regex("""\b(volume|mute|unmute|louder|quieter)\b""").containsMatchIn(t) -> Act.Volume(when {
                "unmute" in t -> "up"
                "mute" in t || "silent" in t -> "mute"
                "max" in t || "full" in t -> "max"
                Regex("""\b(up|louder|increase|raise)\b""").containsMatchIn(t) -> "up"
                Regex("""\b(down|quieter|decrease|lower)\b""").containsMatchIn(t) -> "down"
                else -> Regex("""(\d{1,3})\s*(%|percent)""").find(t)?.groupValues?.get(1) ?: "up"
            })
            Regex("""\b(do not disturb|dnd)\b""").containsMatchIn(t) -> Act.Dnd(!Regex("""\b(off|disable|stop)\b""").containsMatchIn(t))
            Regex("""\b(wifi|wi fi|bluetooth|mobile data|internet)\b""").containsMatchIn(t) && Regex("""\b(open|turn|switch|settings|on|off)\b""").containsMatchIn(t) ->
                Act.Setting(when { "bluetooth" in t -> "bluetooth"; "wifi" in t || "wi fi" in t -> "wifi"; else -> "internet" })
            Regex("""^(?:please )?(?:call|ring|phone)\s+(.+)""").find(t) != null ->
                Act.Call(Regex("""^(?:please )?(?:call|ring|phone)\s+(.+)""").find(t)!!.groupValues[1].removeSuffix(" please"))
            Regex("""^(?:please )?(?:text|message|sms)\s+(\S+)\s+(?:saying |that )?(.+)""").find(t) != null -> {
                val m = Regex("""^(?:please )?(?:text|message|sms)\s+(\S+)\s+(?:saying |that )?(.+)""").find(t)!!
                Act.Text(m.groupValues[1], m.groupValues[2])
            }
            Regex("""\b(navigate|directions|take me)\b""").containsMatchIn(t) -> afterVerb(listOf("navigate to", "directions to", "take me to", "navigate"))?.let {
                Act.Navigate(it.replace(Regex("""\s*(by car|driving|by bus|walking|on foot)$"""), ""), when {
                    Regex("""\b(car|driving|drive)\b""").containsMatchIn(t) -> "d"; Regex("""\b(bus|train|transit)\b""").containsMatchIn(t) -> "r"; else -> "w" })
            }
            Regex("""^(?:please )?play\s+(.+)""").find(t) != null -> Act.Music(Regex("""^(?:please )?play\s+(.+)""").find(t)!!.groupValues[1].removeSuffix(" on spotify").removeSuffix(" on youtube"))
            Regex("""^(?:please )?(?:open|launch|start)\s+(.+)""").find(t) != null ->
                Act.OpenApp(Regex("""^(?:please )?(?:open|launch|start)\s+(.+)""").find(t)!!.groupValues[1].removeSuffix(" app"))
            Regex("""^(?:search|google|look up)\s+(?:for\s+)?(.+)""").find(t) != null -> Act.Search(Regex("""^(?:search|google|look up)\s+(?:for\s+)?(.+)""").find(t)!!.groupValues[1])
            else -> null
        }
    }

    /** From the AI's JSON action object. */
    fun fromJson(o: JSONObject, now: LocalDateTime = LocalDateTime.now()): Act? = when (o.optString("type")) {
        "set_alarm" -> o.optInt("hour", -1).takeIf { it in 0..23 }?.let { Act.Alarm(it, o.optInt("minute", 0).coerceIn(0, 59), o.optString("label")) }
        "set_timer" -> o.optInt("seconds", 0).takeIf { it > 0 }?.let { Act.Timer(it, o.optString("label")) }
        "open_app" -> o.optString("name").takeIf { it.isNotBlank() }?.let { Act.OpenApp(it) }
        "call" -> o.optString("who").takeIf { it.isNotBlank() }?.let { Act.Call(it) }
        "text" -> o.optString("who").takeIf { it.isNotBlank() }?.let { Act.Text(it, o.optString("message")) }
        "torch" -> Act.Torch(o.optBoolean("on", true))
        "volume" -> Act.Volume(o.optString("change", "up"))
        "dnd" -> Act.Dnd(o.optBoolean("on", true))
        "navigate" -> o.optString("destination").takeIf { it.isNotBlank() }?.let { Act.Navigate(it, o.optString("mode", "w").take(1)) }
        "play_music" -> o.optString("query").takeIf { it.isNotBlank() }?.let { Act.Music(it) }
        "remind" -> runCatching { LocalDateTime.parse(o.optString("at")) }.getOrNull()?.takeIf { it.isAfter(now) }?.let { Act.Remind(it, o.optString("text", "reminder")) }
        "open_setting" -> Act.Setting(o.optString("which", "internet"))
        "web_search" -> o.optString("query").takeIf { it.isNotBlank() }?.let { Act.Search(it) }
        else -> null
    }
}

/** Carries out an [Act] with standard Android intents. Returns a short line for Nova to say. */
object PhoneActions {
    private fun start(ctx: Context, i: Intent): Boolean = runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)

    fun run(ctx: Context, act: Act): String = when (act) {
        is Act.Alarm -> {
            val ok = start(ctx, Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, act.hour).putExtra(AlarmClock.EXTRA_MINUTES, act.minute)
                .putExtra(AlarmClock.EXTRA_MESSAGE, act.label.ifBlank { "Nova alarm" }).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
            if (ok) "Alarm set for %02d:%02d.".format(act.hour, act.minute) else "Couldn't reach the clock app."
        }
        is Act.Timer -> {
            val ok = start(ctx, Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, act.seconds)
                .putExtra(AlarmClock.EXTRA_MESSAGE, act.label.ifBlank { "Nova timer" }).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
            val m = act.seconds / 60; val s = act.seconds % 60
            if (ok) "Timer set for " + (if (m > 0) "$m minute${if (m > 1) "s" else ""}" else "") + (if (s > 0) " $s seconds" else "") + "." else "Couldn't set a timer."
        }
        is Act.OpenApp -> {
            val app = findApp(ctx, act.name)
            if (app != null && start(ctx, ctx.packageManager.getLaunchIntentForPackage(app.second)!!)) "Opening ${app.first}." else "I couldn't find an app called ${act.name}."
        }
        is Act.Call -> {
            val num = findNumber(ctx, act.who) ?: act.who.filter { it.isDigit() || it == '+' }.takeIf { it.length >= 5 }
            // Opens the dialler with the number filled in — you press call. Nova never calls on its own.
            if (num != null && start(ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:$num")))) "Dialler ready for ${act.who} — tap call." else "I couldn't find ${act.who} in your contacts."
        }
        is Act.Text -> {
            val num = findNumber(ctx, act.who)
            if (num != null && start(ctx, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$num")).putExtra("sms_body", act.message)))
                "Message to ${act.who} is ready — check it and hit send." else "I couldn't find ${act.who} in your contacts."
        }
        is Act.Torch -> runCatching {
            val cm = ctx.getSystemService(CameraManager::class.java)
            val id = cm.cameraIdList.first { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            cm.setTorchMode(id, act.on); if (act.on) "Torch on." else "Torch off."
        }.getOrDefault("This phone's torch isn't available.")
        is Act.Volume -> {
            val am = ctx.getSystemService(AudioManager::class.java)
            val stream = AudioManager.STREAM_MUSIC
            val max = am.getStreamMaxVolume(stream)
            when (act.change) {
                "up" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                "down" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                "mute" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                "max" -> am.setStreamVolume(stream, max, AudioManager.FLAG_SHOW_UI)
                else -> act.change.toIntOrNull()?.let { am.setStreamVolume(stream, (it.coerceIn(0, 100) * max / 100), AudioManager.FLAG_SHOW_UI) }
            }
            "Volume ${act.change}."
        }
        is Act.Dnd -> {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (!nm.isNotificationPolicyAccessGranted) {
                start(ctx, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                "Allow Nova under Do Not Disturb access once, then ask again."
            } else {
                nm.setInterruptionFilter(if (act.on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
                if (act.on) "Do Not Disturb on. Focus time." else "Do Not Disturb off."
            }
        }
        is Act.Navigate -> {
            val ok = start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(act.destination + ((ctx.applicationContext as? com.aethernova.nova.NovaApp)?.settings?.current?.city?.takeIf { it.isNotBlank() && !act.destination.contains(",") }?.let { ", $it" } ?: "")) + "&mode=${act.mode}")))
                || start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(act.destination))))
            if (ok) "Directions to ${act.destination}." else "No maps app found."
        }
        is Act.Music -> {
            val ok = start(ctx, Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).putExtra(android.app.SearchManager.QUERY, act.query))
                || start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=" + Uri.encode(act.query))))
            if (ok) "Playing ${act.query}." else "No music app found."
        }
        is Act.Remind -> {
            com.aethernova.nova.reminders.Reminders.scheduleNote(ctx, act.at, act.text)
            "I'll remind you ${act.at.format(DateTimeFormatter.ofPattern("EEE HH:mm"))}: ${act.text}."
        }
        is Act.Setting -> {
            val action = when (act.which) {
                "wifi" -> Settings.Panel.ACTION_WIFI
                "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
                "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
                "display" -> Settings.ACTION_DISPLAY_SETTINGS
                else -> Settings.Panel.ACTION_INTERNET_CONNECTIVITY
            }
            if (start(ctx, Intent(action))) "Here are your ${act.which} settings — Android only lets you flip that switch yourself." else "Couldn't open settings."
        }
        is Act.Search -> if (start(ctx, Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, act.query))
            || start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(act.query))))) "Searching ${act.query}." else "No browser found."
    }

    /** Best match among launchable apps by label ("whatsapp", "insta" -> Instagram). */
    fun findApp(ctx: Context, name: String): Pair<String, String>? {
        val pm = ctx.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        return AppMatch.best(name, apps)
    }

    private fun findNumber(ctx: Context, who: String): String? {
        if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val q = who.trim()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$q%"), null,
        )?.use { c ->
            val rows = mutableListOf<Pair<String, String>>()
            while (c.moveToNext()) rows += (c.getString(0) ?: "") to (c.getString(1) ?: "")
            return rows.minByOrNull { if (it.first.equals(q, true)) 0 else it.first.length }?.second
        }
        return null
    }
}

/** Fuzzy app-name matching, kept pure for tests. */
object AppMatch {
    private val aliases = mapOf("insta" to "instagram", "yt" to "youtube", "wa" to "whatsapp", "maps" to "maps", "gmail" to "gmail",
        "camera" to "camera", "spotify" to "spotify", "chrome" to "chrome", "snap" to "snapchat", "fb" to "facebook")

    fun best(query: String, apps: List<Pair<String, String>>): Pair<String, String>? {
        val q = query.lowercase().trim().let { aliases[it] ?: it }
        return apps.firstOrNull { it.first.equals(q, true) }
            ?: apps.filter { it.first.lowercase().startsWith(q) }.minByOrNull { it.first.length }
            ?: apps.filter { it.first.lowercase().contains(q) }.minByOrNull { it.first.length }
            ?: apps.filter { it.second.lowercase().contains(q.replace(" ", "")) }.minByOrNull { it.first.length }
    }
}
