package com.aethernova.nova.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aethernova.nova.data.DissMilestone
import com.aethernova.nova.data.DissPace
import com.aethernova.nova.data.Interview
import com.aethernova.nova.data.JobApp
import com.aethernova.nova.data.Leitner
import com.aethernova.nova.data.ShiftPay
import com.aethernova.nova.data.Kind
import com.aethernova.nova.data.Money
import com.aethernova.nova.data.Plan
import java.time.LocalDate
import java.time.YearMonth

/** v0.9 Work tab: career, dissertation, revision and pay. */
@Composable
fun WorkScreen(vm: NovaViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Work & study", color = C.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        DissertationCard(vm)
        FlashcardsCard(vm)
        JobsCard(vm)
        ShiftsCard(vm)
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------- Jobs ----------------

private val STATUSES = listOf("saved", "applied", "interview", "offer", "rejected")

@Composable
private fun JobsCard(vm: NovaViewModel) {
    val jobs by vm.jobs.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var practise by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<JobApp?>(null) }
    NovaCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SectionTitle("Job applications")
                val active = jobs.count { it.status in listOf("applied", "interview") }
                Text("${jobs.size} tracked · $active in progress · ${jobs.count { it.status == "offer" }} offers", color = C.muted, fontSize = 12.sp)
            }
            TextButton(onClick = { adding = true }) { Text("+ Add") }
        }
        jobs.forEach { j ->
            Row(Modifier.fillMaxWidth().clickable { open = j }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${j.role.ifBlank { "Role" }} · ${j.company}", color = C.text, fontSize = 14.sp)
                    val dl = j.deadline.takeIf { it.isNotBlank() }?.let { d ->
                        val left = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(d))
                        if (left < 0) "closed $d" else "deadline $d ($left d)"
                    }
                    Text(listOfNotNull(j.status, dl).joinToString(" · "), color = statusColor(j.status), fontSize = 12.sp)
                }
            }
        }
        if (jobs.isEmpty()) Text("Add grad schemes and placements you're eyeing. I'll remind you 7 days, 2 days and on the day of each deadline.", color = C.muted, fontSize = 13.sp)
        Button(onClick = { practise = true }, Modifier.padding(top = 8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = C.teal, contentColor = C.bg)) { Text("🎤 Practise an interview") }
    }
    if (adding) AddJobDialog(onDismiss = { adding = false }) { c, r, d, l -> vm.addJob(c, r, d, l); adding = false }
    if (practise) PractiseDialog(jobs, onDismiss = { practise = false }) { role, company ->
        practise = false; vm.startInterview(role, company); vm.open("interview:$role")
    }
    open?.let { j ->
        var notes by remember(j.id) { mutableStateOf(j.notes) }
        AlertDialog(onDismissRequest = { vm.updateJobNotes(j, notes); open = null },
            title = { Text("${j.role} · ${j.company}") },
            text = {
                Column {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        STATUSES.forEach { st -> FilterChip(selected = j.status == st, onClick = { vm.setJobStatus(j, st); open = j.copy(status = st) }, label = { Text(st) }) }
                    }
                    if (j.link.isNotBlank()) Text(j.link, color = C.teal, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Notes (contacts, questions asked…)") }, minLines = 3)
                }
            },
            confirmButton = { TextButton(onClick = {
                vm.updateJobNotes(j, notes); open = null; vm.startInterview(j.role.ifBlank { "Graduate scheme" }, j.company); vm.open("interview:${j.role}")
            }) { Text("Practise for this") } },
            dismissButton = { Row {
                TextButton(onClick = { vm.deleteJob(j); open = null }) { Text("Delete", color = C.red) }
                TextButton(onClick = { vm.updateJobNotes(j, notes); open = null }) { Text("Close") }
            } })
    }
}

private fun statusColor(s: String) = when (s) { "offer" -> C.green; "interview" -> C.amber; "rejected" -> C.red; "applied" -> C.teal; else -> C.muted }

@Composable
private fun AddJobDialog(onDismiss: () -> Unit, onAdd: (String, String, String, String) -> Unit) {
    var company by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("") }
    var deadline by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add application") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(company, { company = it }, label = { Text("Company") }, singleLine = true)
            OutlinedTextField(role, { role = it }, label = { Text("Role") }, singleLine = true)
            OutlinedTextField(deadline, { deadline = it }, label = { Text("Deadline (yyyy-mm-dd, optional)") }, singleLine = true)
            OutlinedTextField(link, { link = it }, label = { Text("Link (optional)") }, singleLine = true)
        } },
        confirmButton = { TextButton(onClick = { onAdd(company, role, deadline, link) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun PractiseDialog(jobs: List<JobApp>, onDismiss: () -> Unit, onStart: (String, String) -> Unit) {
    var role by remember { mutableStateOf(Interview.ROLES.first()) }
    var company by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Mock interview") },
        text = { Column {
            Text("5 questions, spoken out loud. Answer by voice or typing; I score each answer and give tips.", color = C.muted, fontSize = 13.sp)
            (Interview.ROLES + jobs.filter { it.status != "rejected" }.map { it.role }.filter { it.isNotBlank() }).distinct().forEach { r ->
                Row(Modifier.fillMaxWidth().clickable { role = r; company = jobs.firstOrNull { it.role == r }?.company ?: company }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.RadioButton(role == r, { role = r })
                    Text(r, color = C.text, fontSize = 14.sp)
                }
            }
            OutlinedTextField(company, { company = it }, label = { Text("Company (optional)") }, singleLine = true)
        } },
        confirmButton = { TextButton(onClick = { onStart(role, company) }) { Text("Start") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

// ---------------- Dissertation ----------------

@Composable
private fun DissertationCard(vm: NovaViewModel) {
    val s by vm.settings.collectAsState()
    val ms by vm.milestones.collectAsState()
    val logs by vm.dissLogs.collectAsState()
    val parsed = runCatching { LocalDate.parse(s.dissertationDue) }.getOrNull()
    if (parsed == null) {
        var d by remember { mutableStateOf("") }
        var w by remember { mutableStateOf("") }
        NovaCard {
            SectionTitle("Dissertation / big project")
            Text("Writing a dissertation or final project? Add the due date and I'll track your pace and milestones.", color = C.muted, fontSize = 13.sp)
            OutlinedTextField(d, { d = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("Due date (yyyy-mm-dd)") }, singleLine = true)
            OutlinedTextField(w, { w = it.filter(Char::isDigit).take(6) }, Modifier.fillMaxWidth(), label = { Text("Word target (e.g. 10000)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            TextButton(onClick = { vm.setupDissertation(d, w.toIntOrNull() ?: 10000) }) { Text("Start tracking") }
        }
        return
    }
    val due = parsed
    val pace = DissPace.compute(logs, s.dissWords, due, LocalDate.now())
    var words by remember { mutableStateOf("") }
    var edit by remember { mutableStateOf<DissMilestone?>(null) }
    var addMs by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var showNotes by remember { mutableStateOf(false) }

    NovaCard {
        SectionTitle("Dissertation")
        Text("${pace.daysLeft} days to ${s.dissertationDue}", color = C.text, fontSize = 14.sp)
        LinearProgressIndicator(progress = { (pace.written.toFloat() / pace.target).coerceIn(0f, 1f) },
            Modifier.fillMaxWidth().padding(vertical = 8.dp), color = C.teal, trackColor = C.surface2)
        Text("${pace.written} / ${pace.target} words" + if (pace.perDayNeeded > 0) " · need ~${pace.perDayNeeded}/day" else " · target reached 🎉",
            color = C.muted, fontSize = 12.sp)
        pace.recentPerDay?.let {
            Text("Last 2 weeks: ~${"%.0f".format(it)} words/day — ${if (pace.onTrack) "on track ✅" else "behind, add a focus block or two"}",
                color = if (pace.onTrack) C.green else C.amber, fontSize = 12.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            OutlinedTextField(words, { words = it.filter(Char::isDigit).take(6) }, Modifier.weight(1f), singleLine = true,
                label = { Text("Current total word count") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            TextButton(onClick = { words.toIntOrNull()?.let { vm.logWords(it) }; words = "" }) { Text("Log") }
        }

        Text("Milestones", color = C.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
        Text("Placeholders — tap one to match your module handbook dates.", color = C.muted, fontSize = 11.sp)
        ms.forEach { m ->
            val late = !m.done && m.dueDay < LocalDate.now().toString()
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { edit = m }) {
                Checkbox(m.done, { vm.toggleMilestone(m) })
                Text(m.title, Modifier.weight(1f), color = if (m.done) C.muted else C.text, fontSize = 13.sp)
                Text(m.dueDay, color = if (late) C.red else C.muted, fontSize = 12.sp)
            }
        }
        Row {
            TextButton(onClick = { addMs = true }) { Text("+ Milestone") }
            TextButton(onClick = { showNotes = !showNotes }) { Text(if (showNotes) "Hide supervisor notes" else "Supervisor notes (${logs.count { it.kind == "meeting" }})") }
        }
        if (showNotes) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(note, { note = it }, Modifier.weight(1f), label = { Text("Meeting notes / action points") }, minLines = 2)
                TextButton(onClick = { vm.addMeetingNote(note); note = "" }) { Text("Save") }
            }
            logs.filter { it.kind == "meeting" }.reversed().forEach { l ->
                Row(Modifier.padding(top = 6.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(l.day, color = C.muted, fontSize = 11.sp)
                        Text(l.text, color = C.text, fontSize = 13.sp)
                    }
                    IconButton(onClick = { vm.deleteDissLog(l) }) { Icon(Icons.Default.Delete, "Delete", tint = C.muted) }
                }
            }
        }
    }
    if (addMs || edit != null) {
        val m = edit
        var title by remember(m) { mutableStateOf(m?.title ?: "") }
        var d by remember(m) { mutableStateOf(m?.dueDay ?: LocalDate.now().plusWeeks(2).toString()) }
        AlertDialog(onDismissRequest = { addMs = false; edit = null }, title = { Text(if (m == null) "New milestone" else "Edit milestone") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true)
                OutlinedTextField(d, { d = it }, label = { Text("Due (yyyy-mm-dd)") }, singleLine = true)
            } },
            confirmButton = { TextButton(onClick = { vm.saveMilestone(m, title, d); addMs = false; edit = null }) { Text("Save") } },
            dismissButton = { Row {
                if (m != null) TextButton(onClick = { vm.deleteMilestone(m); edit = null }) { Text("Delete", color = C.red) }
                TextButton(onClick = { addMs = false; edit = null }) { Text("Cancel") }
            } })
    }
}

// ---------------- Flashcards ----------------

@Composable
private fun FlashcardsCard(vm: NovaViewModel) {
    val cards by vm.cards.collectAsState()
    val busy by vm.cardBusy.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val due = Leitner.due(cards, LocalDate.now())
    val decks = cards.map { it.deck }.distinct()
    var adding by remember { mutableStateOf(false) }
    var deck by remember { mutableStateOf(decks.firstOrNull() ?: "") }
    var photoUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val cam = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.TakePicture()) { ok ->
        if (ok) photoUri?.let { u -> loadBitmap(ctx, u)?.let { vm.cardsFromPhoto(it, deck.ifBlank { "General" }) } }
    }
    val gallery = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { u ->
        u?.let { loadBitmap(ctx, it)?.let { b -> vm.cardsFromPhoto(b, deck.ifBlank { "General" }) } }
    }
    NovaCard {
        SectionTitle("Flashcards")
        Text("${cards.size} cards in ${decks.size} decks · ${due.size} due today · ${"%.0f".format(Leitner.mastery(cards) * 100)}% mastered",
            color = C.muted, fontSize = 12.sp)
        if (decks.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            decks.forEach { d -> FilterChip(selected = deck == d, onClick = { deck = d },
                label = { Text("$d (${due.count { it.deck == d }})") }) }
        }
        OutlinedTextField(deck, { deck = it.take(40) }, Modifier.fillMaxWidth().padding(top = 6.dp), singleLine = true,
            label = { Text("Deck / module (e.g. NLP)") })
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.open("flash") }, enabled = due.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = C.teal, contentColor = C.bg)) { Text("Review ${due.size}") }
            AssistChip(onClick = {
                val dir = java.io.File(ctx.cacheDir, "photos").apply { mkdirs() }
                val u = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", java.io.File(dir, "slide.jpg"))
                photoUri = u; cam.launch(u)
            }, label = { Text(if (busy) "Reading…" else "📷 Slide photo") }, enabled = !busy)
            AssistChip(onClick = { gallery.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                label = { Text("🖼 From gallery") }, enabled = !busy)
            AssistChip(onClick = { adding = true }, label = { Text("+ Card") })
        }
        if (cards.isEmpty()) Text("Snap a lecture slide and I'll turn it into cards. Right answers move a card to a later box (1, 2, 4, 8, 16 days); wrong ones go back to box 1.",
            color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
    }
    if (adding) {
        var front by remember { mutableStateOf("") }
        var back by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { adding = false }, title = { Text("New card · ${deck.ifBlank { "General" }}") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(front, { front = it }, label = { Text("Question") })
                OutlinedTextField(back, { back = it }, label = { Text("Answer") }, minLines = 2)
            } },
            confirmButton = { TextButton(onClick = { vm.addCard(deck, front, back); front = ""; back = "" }) { Text("Add & next") } },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Done") } })
    }
}

// ---------------- Shift pay ----------------

@Composable
private fun ShiftsCard(vm: NovaViewModel) {
    val s by vm.settings.collectAsState()
    val shifts by vm.shifts.collectAsState()
    val month = YearMonth.now()
    val thisMonth = ShiftPay.inMonth(shifts, month)
    val lastMonth = ShiftPay.inMonth(shifts, month.minusMonths(1))
    var adding by remember { mutableStateOf(false) }
    var payslip by remember { mutableStateOf("") }
    NovaCard {
        SectionTitle("Shift pay")
        Text("${Money.sym}${"%.2f".format(ShiftPay.gross(thisMonth))}", color = C.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("${month.month.name.lowercase().replaceFirstChar { it.uppercase() }} so far · ${"%.1f".format(ShiftPay.hours(thisMonth))} h · before tax",
            color = C.muted, fontSize = 12.sp)
        ShiftPay.byJob(thisMonth).forEach { (job, g) -> Text("${ShiftPay.label(job)}: ${Money.sym}${"%.2f".format(g)}", color = C.muted, fontSize = 12.sp) }
        val jobs = Plan.routine.items.filter { it.kind == Kind.WORK }
        if (jobs.isEmpty()) Text("Add your shifts in Settings → My week and \"Log today's shifts\" fills them in for you.", color = C.muted, fontSize = 12.sp)
        else if (jobs.any { it.rate == 0.0 }) Text("Some shifts have no hourly rate yet — add it in Settings → My week.", color = C.amber, fontSize = 12.sp)
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.logPlannedShifts() }, colors = ButtonDefaults.buttonColors(containerColor = C.teal, contentColor = C.bg)) { Text("Log today's shifts") }
            OutlinedButton(onClick = { adding = true }) { Text("+ Other") }
        }
        if (lastMonth.isNotEmpty()) {
            Text("Payslip check — ${month.minusMonths(1).month.name.lowercase().replaceFirstChar { it.uppercase() }}: logged ${Money.sym}${"%.2f".format(ShiftPay.gross(lastMonth))}",
                color = C.text, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(payslip, { payslip = it.filter { c -> c.isDigit() || c == '.' }.take(8) }, Modifier.weight(1f), singleLine = true,
                    label = { Text("Payslip gross ${Money.sym}") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
            payslip.toDoubleOrNull()?.let { p ->
                val gap = ShiftPay.payslipGap(lastMonth, p)
                Text(when {
                    kotlin.math.abs(gap) < 1.0 -> "Matches ✅"
                    gap > 0 -> "Payslip is ${Money.sym}${"%.2f".format(gap)} SHORT of your logged hours — check with your manager. (Pay periods may not match calendar months.)"
                    else -> "Payslip is ${Money.sym}${"%.2f".format(-gap)} more than logged — maybe a shift you didn't log, holiday pay or a different pay period."
                }, color = if (gap > 1.0) C.amber else C.green, fontSize = 12.sp)
            }
        }
        thisMonth.take(8).forEach { x ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${x.day.substring(5)}  ${ShiftPay.label(x.job)}  ${"%.1f".format(x.hours)} h × ${Money.sym}${"%.2f".format(x.rate)}", Modifier.weight(1f), color = C.muted, fontSize = 12.sp)
                IconButton(onClick = { vm.deleteShift(x) }) { Icon(Icons.Default.Delete, "Delete", tint = C.muted) }
            }
        }
    }
    if (adding) {
        val names = (Plan.routine.items.filter { it.kind == Kind.WORK }.map { it.label } + "Other").distinct()
        var job by remember { mutableStateOf(names.first()) }
        var hours by remember { mutableStateOf("") }
        var day by remember { mutableStateOf(LocalDate.now().toString()) }
        var rate by remember(job) { mutableStateOf((Plan.routine.items.firstOrNull { it.label == job }?.rate ?: 0.0).let { if (it == 0.0) "" else "%.2f".format(it) }) }
        AlertDialog(onDismissRequest = { adding = false }, title = { Text("Log a shift") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    names.forEach { j -> FilterChip(job == j, { job = j }, label = { Text(j) }) }
                }
                OutlinedTextField(day, { day = it }, label = { Text("Date (yyyy-mm-dd)") }, singleLine = true)
                OutlinedTextField(hours, { hours = it }, label = { Text("Hours") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(rate, { rate = it }, label = { Text("${Money.sym} per hour") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            } },
            confirmButton = { TextButton(onClick = {
                vm.addShift(day, job, hours.toDoubleOrNull() ?: 0.0, rate.toDoubleOrNull() ?: 0.0, ""); adding = false
            }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } })
    }
}
