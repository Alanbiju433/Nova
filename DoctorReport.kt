package com.aethernova.nova.data

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.aethernova.nova.reminders.MedPlan
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Numbers for the doctor summary. Pure so it's unit-tested. */
object DoctorSummary {
    data class Summary(
        val from: LocalDate, val to: LocalDate,
        val weights: List<Pair<LocalDate, Double>>, val bmiNow: Double?,
        val daysLogged: Int, val avgKcal: Int?, val avgProtein: Int?,
        val walksPerWeek: Double, val walkMinPerWeek: Int, val sessions: Int,
        val avgSleep: Double?, val avgMood: Double?,
    )

    fun compute(
        to: LocalDate, days: Int, heightCm: Double,
        checkIns: List<CheckIn>, food: List<DayTotals>, workouts: List<WorkoutLog>, zone: ZoneId = ZoneId.systemDefault(),
    ): Summary {
        val from = to.minusDays(days.toLong() - 1)
        fun inRange(d: String) = runCatching { LocalDate.parse(d) }.getOrNull()?.let { !it.isBefore(from) && !it.isAfter(to) } ?: false
        val weights = checkIns.filter { it.weightKg != null && inRange(it.day) }
            .map { LocalDate.parse(it.day) to it.weightKg!! }.sortedBy { it.first }
        val f = food.filter { inRange(it.day) && it.kcal > 0 }
        val w = workouts.filter { inRange(it.day) }
        val weeks = days / 7.0
        val sleep = checkIns.filter { inRange(it.day) }.mapNotNull { it.sleepHours }
        val mood = checkIns.filter { inRange(it.day) }.mapNotNull { it.mood }
        val h = heightCm / 100
        return Summary(
            from, to, weights, weights.lastOrNull()?.second?.takeIf { h > 0.5 }?.let { it / (h * h) },
            f.size, f.takeIf { it.isNotEmpty() }?.map { it.kcal }?.average()?.toInt(), f.takeIf { it.isNotEmpty() }?.map { it.protein }?.average()?.toInt(),
            w.count { it.kind == "walk" } / weeks, (w.filter { it.kind == "walk" }.sumOf { it.minutes } / weeks).toInt(),
            w.count { it.kind != "walk" }, sleep.takeIf { it.isNotEmpty() }?.average(), mood.takeIf { it.isNotEmpty() }?.average(),
        )
    }
}

/** Renders a one/two-page PDF to show a GP or clinic, then opens the share sheet. */
object DoctorReport {
    private const val W = 595; private const val H = 842   // A4 in points

    suspend fun build(ctx: Context, repo: Repository, name: String): File {
        val s = (ctx.applicationContext as com.aethernova.nova.NovaApp).settings.current
        val today = LocalDate.now()
        val days = 90
        val since = today.minusDays(days.toLong()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val sum = DoctorSummary.compute(today, days, s.heightCm.toDouble(), repo.dao.checkInsFrom(since),
            repo.dao.dailyTotalsSince(today.minusDays(days.toLong()).toString()), repo.dao.workoutsSinceNow(today.minusDays(days.toLong()).toString()))
        val results = repo.dao.resultsSorted()
        val meds = repo.dao.activeMedsNow()
        val logs = repo.dao.medLogsSinceNow(today.minusDays(30).toString())
        val adherence = MedPlan.adherence(meds, logs, today.minusDays(29), today, LocalDateTime.now())
        val follow = repo.dao.openFollowupsNow()

        val doc = PdfDocument()
        var page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, 1).create())
        var c = page.canvas
        var y = 50f
        val title = Paint().apply { textSize = 20f; isFakeBoldText = true; color = Color.BLACK }
        val h2 = Paint().apply { textSize = 13f; isFakeBoldText = true; color = Color.rgb(20, 90, 90) }
        val body = Paint().apply { textSize = 10.5f; color = Color.DKGRAY }
        val small = Paint().apply { textSize = 8.5f; color = Color.GRAY }
        val fmt = DateTimeFormatter.ofPattern("d MMM yyyy")

        fun newPageIfNeeded(space: Float) {
            if (y + space > H - 50) { doc.finishPage(page); page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, doc.pages.size + 1).create()); c = page.canvas; y = 50f }
        }
        fun line(text: String, p: Paint = body, gap: Float = 15f) { newPageIfNeeded(gap); c.drawText(text, 40f, y, p); y += gap }
        fun section(t: String) { y += 8; line(t, h2, 18f) }

        line("Health summary — $name", title, 26f)
        line("Period: ${sum.from.format(fmt)} – ${sum.to.format(fmt)} (last 90 days)   ·   Generated ${today.format(fmt)}", small, 12f)
        line("Self-recorded in the Nova app (food, weight, activity, check-ins). Not a medical record; figures are estimates.", small, 16f)

        section("Body")
        if (sum.weights.isNotEmpty()) {
            val first = sum.weights.first(); val last = sum.weights.last()
            line((if (s.heightCm > 0) "Height ${s.heightCm} cm · " else "") + "Weight ${"%.1f".format(first.second)} kg (${first.first.format(fmt)}) → ${"%.1f".format(last.second)} kg (${last.first.format(fmt)}) " +
                "· change ${"%+.1f".format(last.second - first.second)} kg" + (sum.bmiNow?.let { " · BMI now ${"%.1f".format(it)}" } ?: ""))
            if (sum.weights.size >= 2) {
                newPageIfNeeded(110f)
                val top = y; val left = 40f; val cw = W - 80f; val ch = 90f
                val axis = Paint().apply { color = Color.LTGRAY; strokeWidth = 1f }
                c.drawLine(left, top + ch, left + cw, top + ch, axis)
                val min = sum.weights.minOf { it.second } - 0.5; val max = sum.weights.maxOf { it.second } + 0.5
                val span = (sum.weights.last().first.toEpochDay() - sum.weights.first().first.toEpochDay()).coerceAtLeast(1)
                val pen = Paint().apply { color = Color.rgb(20, 140, 140); strokeWidth = 2f; isAntiAlias = true }
                var prev: Pair<Float, Float>? = null
                for ((d, kg) in sum.weights) {
                    val px = left + cw * (d.toEpochDay() - sum.weights.first().first.toEpochDay()) / span
                    val py = (top + ch - (kg - min) / (max - min) * ch).toFloat()
                    prev?.let { c.drawLine(it.first, it.second, px, py, pen) }
                    c.drawCircle(px, py, 2.5f, pen); prev = px to py
                }
                c.drawText("%.1f kg".format(max), left, top + 8, small); c.drawText("%.1f kg".format(min), left, top + ch - 2, small)
                y += ch + 14
            }
        } else line("No weights logged in this period.")

        section("Diet (logged days only)")
        line("Days with food logged: ${sum.daysLogged} / 90" +
            (sum.avgKcal?.let { " · average $it kcal/day" } ?: "") + (sum.avgProtein?.let { " · average $it g protein/day" } ?: ""))
        com.aethernova.nova.data.Plan.rules(s).takeIf { it.isNotEmpty() }?.let { line("Food rules followed: " + it.joinToString("; ").take(300)) }

        section("Activity")
        line("Walks: ${"%.1f".format(sum.walksPerWeek)} per week, ~${sum.walkMinPerWeek} min/week · Strength sessions logged: ${sum.sessions}")
        line("Sleep (self-reported): " + (sum.avgSleep?.let { "%.1f h/night avg".format(it) } ?: "—") +
            "   ·   Mood (1–5): " + (sum.avgMood?.let { "%.1f avg".format(it) } ?: "—"))

        section("Test results")
        if (results.isEmpty()) line("None recorded.")
        results.groupBy { it.marker }.forEach { (marker, list) ->
            val vals = list.joinToString("  ·  ") { r -> "${r.day}: ${r.value?.let { v -> "$v ${r.unit}" } ?: "—"}${if (r.flag.isNotEmpty() && r.flag != "OK") " (${r.flag})" else ""}" }
            val ref = list.last().let { r -> listOfNotNull(r.refLow?.let { "≥$it" }, r.refHigh?.let { "≤$it" }).joinToString(" ") }
            line("$marker${if (ref.isNotEmpty()) "  [ref $ref]" else ""}:  $vals")
            list.last().note.takeIf { it.isNotBlank() }?.let { line("    Note: $it", small, 12f) }
        }

        section("Supplements / medicines (as prescribed)")
        if (meds.isEmpty()) line("None recorded.")
        meds.forEach { m -> line("${m.name} — ${m.dose} at ${m.times}${if (m.weekday != 0) " (weekly)" else ""}, since ${m.startDay}") }
        adherence?.let { line("Doses taken (last 30 days): $it%") }

        if (follow.isNotEmpty()) {
            section("Planned follow-ups")
            follow.forEach { line("${it.dueDay}: ${it.title}${if (it.note.isNotBlank()) " — ${it.note}" else ""}") }
        }
        y += 10
        line("Questions I'd like to ask:", h2, 16f)
        repeat(3) { line("• ______________________________________________________________", body, 18f) }

        doc.finishPage(page)
        val dir = File(ctx.cacheDir, "reports").apply { mkdirs() }
        val file = File(dir, "Nova-health-summary-${today}.pdf")
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }

    fun share(ctx: Context, file: File) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share health summary")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @Suppress("unused") private fun Long.toDate() = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()
}
