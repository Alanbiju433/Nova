package com.aethernova.nova.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aethernova.nova.data.Pet
import com.aethernova.nova.data.Report
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@Composable
fun LifeScreen(vm: NovaViewModel) {
    val s by vm.settings.collectAsState()
    val xp by vm.totalXp.collectAsState()
    val xpDays by vm.xpByDay.collectAsState()
    val lastFood by vm.lastFood.collectAsState()
    val water by vm.waterToday.collectAsState()
    val focusEnds by vm.focusEndsAt.collectAsState()
    val isBreak by vm.focusIsBreak.collectAsState()
    val weekFocus by vm.weekFocus.collectAsState()
    val report by vm.report.collectAsState()
    val moods by vm.moodEntries.collectAsState()

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { vm.refreshReport(); while (true) { now = System.currentTimeMillis(); delay(1000) } }

    val level = Pet.level(xp)
    val streak = Pet.streak(xpDays.associate { it.day to it.xp }, LocalDate.now())
    val fullness = Pet.fullness(lastFood, now)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

        // ---------- Pet ----------
        NovaCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${s.buddyName} · Level $level", color = C.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(Pet.title(level), color = C.teal, fontSize = 13.sp)
                }
                Text("🔥 $streak", color = C.amber, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Text("XP $xp / ${Pet.xpForLevel(level + 1)}", color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            LinearProgressIndicator(progress = { Pet.progress(xp) }, Modifier.fillMaxWidth(), color = C.teal, trackColor = C.surface2)
            Text(
                "Tummy ${fullness}% " + when { fullness < 25 -> "— I'm starving for data, log a meal!"; fullness < 60 -> "— snack log soon?"; else -> "— happy and fed" },
                color = if (fullness < 25) C.amber else C.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
            )
            LinearProgressIndicator(progress = { fullness / 100f }, Modifier.fillMaxWidth(), color = if (fullness < 25) C.amber else C.green, trackColor = C.surface2)
            Text("Personality: " + Pet.personality(level) + (Pet.nextUnlock(level)?.let { "  ·  Next: $it" } ?: ""), color = C.teal, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            Text("Streak = days with 30+ XP. XP: meal +5 · check-in +10 · walk +15 · session +30 · water goal +15 · protein goal +25 · focus block +10",
                color = C.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            Text("Skins", color = C.text, modifier = Modifier.padding(top = 10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pet.skins.forEach { sk ->
                    val unlocked = level >= sk.unlockLevel
                    FilterChip(
                        selected = s.skin == sk.name, enabled = unlocked,
                        onClick = { vm.saveSettings(s.copy(skin = sk.name)) },
                        label = { Text(if (unlocked) sk.name else "🔒 ${sk.name} · Lv ${sk.unlockLevel}") },
                    )
                }
            }
        }

        // ---------- Water ----------
        NovaCard {
            SectionTitle("Water")
            Text("${water} / ${s.waterTarget} ml", color = C.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            LinearProgressIndicator(progress = { (water.toFloat() / s.waterTarget).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().padding(vertical = 6.dp),
                color = androidx.compose.ui.graphics.Color(0xFF63B3ED), trackColor = C.surface2)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.addWater(250) }, colors = ButtonDefaults.buttonColors(containerColor = C.teal, contentColor = C.bg)) { Text("+250") }
                Button(onClick = { vm.addWater(500) }, colors = ButtonDefaults.buttonColors(containerColor = C.teal, contentColor = C.bg)) { Text("+500") }
                TextButton(onClick = { vm.undoWater() }) { Text("Undo") }
            }
        }

        // ---------- Study buddy ----------
        NovaCard {
            SectionTitle("Study buddy")
            val due = runCatching { LocalDate.parse(s.dissertationDue) }.getOrNull()
            if (due != null) {
                val days = ChronoUnit.DAYS.between(LocalDate.now(), due)
                Text(if (days >= 0) "Dissertation due in $days days (${days / 7} weeks)" else "Dissertation date passed — update it in Settings",
                    color = if (days in 0..30) C.amber else C.text, fontWeight = FontWeight.Medium)
            }
            val weekMin = weekFocus.sumOf { it.minutes }
            Text("Focus this week: ${weekMin / 60} h ${weekMin % 60} min · ${weekFocus.size} blocks", color = C.muted, fontSize = 13.sp)
            if (focusEnds != null) {
                val left = ((focusEnds!! - now) / 1000).coerceAtLeast(0)
                Text("%02d:%02d".format(left / 60, left % 60), color = if (isBreak) C.green else C.teal, fontSize = 44.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 6.dp))
                Text(if (isBreak) "Break — stretch, water, eyes off screen" else "Focus — phone face down, ${s.buddyName} is watching 👀", color = C.muted, fontSize = 13.sp)
                OutlinedButton(onClick = { vm.stopFocus() }, Modifier.padding(top = 6.dp)) { Text("Stop") }
            } else {
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.open("coach:POSTURE") }) { Text("🪑 Posture guard") }
                    OutlinedButton(onClick = { vm.open("breathe") }) { Text("🫁 Wind down") }
                }
                if (s.eyeBreaks) Text("Eye + stretch breaks every 20 min during focus blocks.", color = C.muted, fontSize = 11.sp)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.startFocus(s.focusMinutes) }, colors = ButtonDefaults.buttonColors(containerColor = C.teal, contentColor = C.bg)) { Text("Focus ${s.focusMinutes}") }
                    OutlinedButton(onClick = { vm.startFocus(50) }) { Text("Deep 50") }
                    OutlinedButton(onClick = { vm.startFocus(5, isBreak = true) }) { Text("Break 5") }
                }
            }
        }

        CoachCard(vm)

        // ---------- Money ----------
        MoneyCard(vm)

        // ---------- Football ----------
        FootballCard(vm)

        // ---------- Weekly report ----------
        NovaCard {
            SectionTitle("Weekly report card")
            report?.let { w ->
                val score = Report.score(w)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Report.grade(score), color = when (Report.grade(score)) { "A", "B" -> C.green; "C" -> C.teal; else -> C.amber },
                        fontSize = 48.sp, fontWeight = FontWeight.Bold)
                    Column(Modifier.padding(start = 16.dp)) {
                        Text("$score / 100", color = C.text, fontWeight = FontWeight.Medium)
                        Text("Last 7 days", color = C.muted, fontSize = 12.sp)
                    }
                }
                ReportRow("Protein avg", "${w.avgProtein.toInt()} / ${w.proteinTarget} g")
                ReportRow("Calories avg", "${w.avgKcal.toInt()} / ${w.kcalTarget} kcal")
                ReportRow("Workout sessions", "${w.sessions} / 3")
                ReportRow("Walking", "${w.walkMin} min")
                ReportRow("Water avg", "${w.avgWaterMl.toInt()} / ${w.waterTarget} ml")
                ReportRow("Focus", "${w.focusMin / 60} h ${w.focusMin % 60} min")
                ReportRow("Mood avg", w.avgMood?.let { "%.1f / 5".format(it) } ?: "—")
                ReportRow("Weight (2 wks)", w.weightChange?.let { "%+.1f kg".format(it) } ?: "—")
                ReportRow("Days with food logged", "${w.daysLogged} / 7")
            } ?: Text("Crunching numbers…", color = C.muted)
            TextButton(onClick = { vm.refreshReport() }) { Text("Refresh") }
        }

        MemoryCard(vm)

        ChallengeCard(vm)

        // ---------- Mood journal ----------
        NovaCard {
            SectionTitle("Mood journal")
            val ordered = moods.reversed()
            LineChart(ordered.mapNotNull { it.mood?.toDouble() }, C.teal, height = 90.dp)
            moods.filter { it.note.isNotBlank() }.take(6).forEach {
                Column(Modifier.padding(top = 8.dp)) {
                    Text("${it.day} · mood ${it.mood}/5", color = C.muted, fontSize = 11.sp)
                    Text(it.note, color = C.text, fontSize = 13.sp)
                }
            }
            if (moods.none { it.note.isNotBlank() }) Text("Add a line in the morning check-in (Body tab) and it shows up here.", color = C.muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun MoneyCard(vm: NovaViewModel) {
    val s by vm.settings.collectAsState()
    val expenses by vm.monthExpenses.collectAsState()
    var amount by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var cat by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("food") }
    val m = com.aethernova.nova.data.Budget.month(expenses, s.monthlyBudget, LocalDate.now())
    NovaCard {
        SectionTitle("Money this month")
        if (s.monthlyBudget > 0) {
            Text("${com.aethernova.nova.data.Money.sym}${"%.2f".format(m.spent)} / ${com.aethernova.nova.data.Money.sym}${s.monthlyBudget}", color = if (m.left < 0) C.red else C.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            LinearProgressIndicator(progress = { (m.spent / s.monthlyBudget).toFloat().coerceIn(0f, 1f) }, Modifier.fillMaxWidth().padding(vertical = 6.dp),
                color = if (m.left < 0) C.red else C.amber, trackColor = C.surface2)
        } else Text("${com.aethernova.nova.data.Money.sym}${"%.2f".format(m.spent)} spent", color = C.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(com.aethernova.nova.data.Budget.comment(m), color = C.muted, fontSize = 13.sp)
        val shifts by vm.shifts.collectAsState()
        val earned = com.aethernova.nova.data.ShiftPay.gross(com.aethernova.nova.data.ShiftPay.inMonth(shifts, java.time.YearMonth.now()))
        if (earned > 0) Text("Earned from shifts: ${com.aethernova.nova.data.Money.sym}${"%.2f".format(earned)} (before tax) — see Work tab", color = C.green, fontSize = 12.sp)
        if (m.byCategory.isNotEmpty()) Text(m.byCategory.entries.sortedByDescending { it.value }.joinToString(" · ") { "${it.key} ${com.aethernova.nova.data.Money.sym}${"%.0f".format(it.value)}" },
            color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.OutlinedTextField(amount, { amount = it }, Modifier.weight(1f), label = { Text("${com.aethernova.nova.data.Money.sym}") }, singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
            TextButton(onClick = { amount.toDoubleOrNull()?.let { vm.addExpense(it, cat, "") }; amount = "" }) { Text("Add") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            com.aethernova.nova.data.Budget.categories.forEach { c -> FilterChip(cat == c, { cat = c }, label = { Text(c) }) }
        }
        expenses.take(5).forEach { e ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${e.day.substring(5)}  ${com.aethernova.nova.data.Money.sym}${"%.2f".format(e.amount)} · ${e.category} ${e.note}", color = C.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.deleteExpense(e) }) { Text("undo", fontSize = 11.sp) }
            }
        }
        Text("Or just tell ${s.buddyName}: \"spent 4.50 on lunch\". Stays on this phone.", color = C.muted, fontSize = 11.sp)
    }
}

@Composable
private fun FootballCard(vm: NovaViewModel) {
    val s by vm.settings.collectAsState()
    val fb by vm.football.collectAsState()
    NovaCard {
        SectionTitle(if (s.favouriteTeam.isBlank()) "Football" else "⚽ ${s.favouriteTeam}")
        if (s.teamId.isBlank()) Text("Pick your team in Settings → Money & football.", color = C.muted, fontSize = 13.sp)
        else if (fb == null) Text("Loading…", color = C.muted)
        else {
            fb!!.first.forEach { Text("${it.date}  ${it.scoreLine}", color = C.text, fontSize = 13.sp) }
            if (fb!!.second.isNotEmpty()) Text("Next", color = C.teal, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            fb!!.second.forEach { Text("${it.date} ${it.time}  ${it.title}", color = C.text, fontSize = 13.sp) }
            TextButton(onClick = { vm.refreshFootball() }) { Text("Refresh") }
        }
    }
}

@Composable
fun MemoryCard(vm: NovaViewModel) {
    val facts by vm.facts.collectAsState()
    NovaCard {
        SectionTitle("What ${vm.settings.collectAsState().value.buddyName} remembers")
        if (facts.isEmpty()) Text("Nothing yet. Tell Nova about your life — exams, friends, plans — and it'll remember and follow up.", color = C.muted, fontSize = 13.sp)
        facts.forEach { f ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("• ${f.text}", color = C.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.forget(f) }) { Text("forget", fontSize = 11.sp) }
            }
        }
    }
}

@Composable
private fun ReportRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = C.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = C.text, fontSize = 13.sp)
    }
}
