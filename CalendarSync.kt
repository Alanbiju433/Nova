package com.aethernova.nova.data

import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Puts your routine (shifts, classes, workouts, other events) into a phone calendar — e.g.
 * your Google Calendar — for the next two weeks. Nova-made events are tagged "[Nova]" so a re-sync
 * replaces them without touching anything else.
 */
object CalendarSync {
    const val TAG = "[Nova]"
    private val zone = ZoneId.of("Europe/London")

    data class Cal(val id: Long, val name: String, val account: String)
    data class Ev(val title: String, val start: LocalDateTime, val end: LocalDateTime)

    /** Which routine blocks become calendar events (skip the walk and bedtime). */
    fun eventsFor(from: LocalDate, days: Int): List<Ev> = (0 until days).flatMap { i ->
        val d = from.plusDays(i.toLong())
        Plan.blocksFor(d).filter { it.kind != Kind.WALK && it.kind != Kind.BED }
            .map { Ev(it.what.replaceFirstChar { c -> c.uppercase() }, d.atTime(it.start), d.atTime(it.end)) }
    }

    fun calendars(ctx: Context): List<Cal> {
        val out = mutableListOf<Cal>()
        ctx.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?", arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()), null,
        )?.use { c -> while (c.moveToNext()) out += Cal(c.getLong(0), c.getString(1) ?: "Calendar", c.getString(2) ?: "") }
        return out
    }

    /** Replaces future Nova events in [calId] with the next [days] days of routine. Returns how many were added. */
    fun sync(ctx: Context, calId: Long, days: Int = 14): Int {
        val cr = ctx.contentResolver
        val nowMs = System.currentTimeMillis()
        cr.delete(
            CalendarContract.Events.CONTENT_URI,
            "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.DESCRIPTION} LIKE ? AND ${CalendarContract.Events.DTSTART} >= ?",
            arrayOf(calId.toString(), "%$TAG%", nowMs.toString()),
        )
        var n = 0
        for (e in eventsFor(LocalDate.now(zone), days)) {
            val start = e.start.atZone(zone).toInstant().toEpochMilli()
            if (start < nowMs) continue
            val v = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.TITLE, e.title)
                put(CalendarContract.Events.DESCRIPTION, "$TAG routine — re-sync from Nova to update")
                put(CalendarContract.Events.DTSTART, start)
                put(CalendarContract.Events.DTEND, e.end.atZone(zone).toInstant().toEpochMilli())
                put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
            }
            if (cr.insert(CalendarContract.Events.CONTENT_URI, v) != null) n++
        }
        return n
    }
}
