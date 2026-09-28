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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aethernova.nova.Legal
import com.aethernova.nova.data.Kind
import com.aethernova.nova.data.Routine
import com.aethernova.nova.data.RoutineItem
import com.aethernova.nova.data.Targets
import com.aethernova.nova.data.Weather
import com.aethernova.nova.data.WorkoutSlot
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

private val num = KeyboardOptions(keyboardType = KeyboardType.Number)
private val dec = KeyboardOptions(keyboardType = KeyboardType.Decimal)

@Composable
private fun Row2(label: String, value: Boolean, onChange: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = C.text, fontSize = 14.sp)
        Switch(value, onChange)
    }

/**
 * First-run setup (and "Edit profile" later). Nothing personal is built into the app — everything
 * Nova knows about the user comes from here.
 */
@Composable
fun SetupScreen(vm: NovaViewModel, editing: Boolean, onDone: () -> Unit) {
    val saved by vm.settings.collectAsState()
    var s by remember { mutableStateOf(saved) }
    var routine by remember { mutableStateOf(Routine.parse(saved.routineJson)) }
    var step by remember { mutableIntStateOf(if (editing) 1 else 0) }
    var weight by remember { mutableStateOf(vm.weights.value.lastOrNull()?.weightKg?.toString() ?: "") }
    var activity by remember { mutableStateOf(Targets.Activity.MODERATE) }
    var goal by remember { mutableStateOf(Targets.Goal.RECOMP) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val steps = 6

    fun finish() {
        vm.finishSetup(s.copy(onboarded = true, routineJson = routine.toJson().toString()), weight.toDoubleOrNull().takeIf { !editing || it != vm.weights.value.lastOrNull()?.weightKg })
        onDone()
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (step > 0 || editing) IconButton(onClick = { if (step > (if (editing) 1 else 0)) step-- else onDone() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = C.text)
            }
            Text(if (editing) "Your profile" else "Welcome to ${s.buddyName}", color = C.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        LinearProgressIndicator(progress = { (step + 1f) / steps }, Modifier.fillMaxWidth().padding(vertical = 8.dp), color = C.teal, trackColor = C.surface2)

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (step) {
                0 -> {
                    Text("Your AI health buddy: logs food from photos or voice, builds reminders around your real week, " +
                        "coaches workouts with the camera, and helps with study and work — mostly offline, with your data kept on this phone.",
                        color = C.text, fontSize = 15.sp)
                    NovaCard {
                        SectionTitle("Before we start")
                        Text("• ${s.buddyName} is a wellbeing and habit tool, not a medical device. It doesn't diagnose or treat anything, " +
                            "and it never gives medicine or supplement doses. Talk to a doctor or pharmacist about your health.", color = C.muted, fontSize = 13.sp)
                        Text("• In an emergency, call your local emergency number.", color = C.muted, fontSize = 13.sp)
                        Text("• If you have (or had) an eating disorder, please use calorie tracking only with your care team's OK.", color = C.muted, fontSize = 13.sp)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp).clickable { s = s.copy(adultConfirmed = !s.adultConfirmed) }) {
                            Checkbox(s.adultConfirmed, { s = s.copy(adultConfirmed = it) })
                            Text("I'm 18 or older and I understand the above.", color = C.text, fontSize = 14.sp)
                        }
                        TextButton(onClick = { runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(Legal.PRIVACY_URL))) } }) {
                            Text("Read the privacy policy")
                        }
                    }
                    OutlinedButton(onClick = { vm.setDemo(true) }) { Text("Just looking? Try demo mode") }
                }
                1 -> {
                    SectionTitle("About you")
                    OutlinedTextField(s.userName, { s = s.copy(userName = it.take(40)) }, Modifier.fillMaxWidth(), label = { Text("Your first name") }, singleLine = true)
                    OutlinedTextField(s.buddyName, { s = s.copy(buddyName = it.take(20)) }, Modifier.fillMaxWidth(), label = { Text("Buddy's name") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(if (s.age == 0) "" else s.age.toString(), { v -> s = s.copy(age = v.filter(Char::isDigit).take(3).toIntOrNull() ?: 0) },
                            Modifier.weight(1f), label = { Text("Age") }, keyboardOptions = num, singleLine = true)
                        OutlinedTextField(if (s.heightCm == 0) "" else s.heightCm.toString(), { v -> s = s.copy(heightCm = v.filter(Char::isDigit).take(3).toIntOrNull() ?: 0) },
                            Modifier.weight(1f), label = { Text("Height cm") }, keyboardOptions = num, singleLine = true)
                        OutlinedTextField(weight, { weight = it.filter { c -> c.isDigit() || c == '.' }.take(5) }, Modifier.weight(1f), label = { Text("Weight kg") }, keyboardOptions = dec, singleLine = true)
                    }
                    if (s.age in 1..17) Text("Sorry — ${s.buddyName} is for adults (18+).", color = C.red, fontSize = 13.sp)
                    Text("Sex (only used to estimate calories)", color = C.muted, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("male" to "Male", "female" to "Female", "" to "Prefer not to say").forEach { (k, l) -> FilterChip(s.sex == k, { s = s.copy(sex = k) }, label = { Text(l) }) }
                    }
                    OutlinedTextField(s.about, { s = s.copy(about = it.take(300)) }, Modifier.fillMaxWidth(), minLines = 2,
                        label = { Text("A line about you (optional)") }, placeholder = { Text("e.g. final-year student, works evening shifts, back to training after a break") })
                }
                2 -> {
                    SectionTitle("Your goal & targets")
                    Column { Targets.Goal.entries.forEach { g -> Row(Modifier.fillMaxWidth().clickable { goal = g }, verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(goal == g, { goal = g }); Text(g.label, color = C.text, fontSize = 14.sp) } } }
                    Text("How active are you?", color = C.muted, fontSize = 12.sp)
                    Column { Targets.Activity.entries.forEach { a -> Row(Modifier.fillMaxWidth().clickable { activity = a }, verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(activity == a, { activity = a }); Text(a.label, color = C.text, fontSize = 14.sp) } } }
                    val r = Targets.compute(s.sex, weight.toDoubleOrNull(), s.heightCm, s.age, activity, goal)
                    OutlinedButton(onClick = {
                        s = s.copy(kcalTarget = r.kcal, proteinTarget = r.protein, waterTarget = r.waterMl,
                            goal = if (s.goal.isBlank() || Targets.Goal.entries.any { s.goal.startsWith(it.label) }) goal.label else s.goal)
                    }) { Text("Suggest: ${r.kcal} kcal · ${r.protein} g protein · ${r.waterMl} ml water") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(s.kcalTarget.toString(), { v -> v.toIntOrNull()?.let { s = s.copy(kcalTarget = it) } }, Modifier.weight(1f), label = { Text("kcal") }, keyboardOptions = num, singleLine = true)
                        OutlinedTextField(s.proteinTarget.toString(), { v -> v.toIntOrNull()?.let { s = s.copy(proteinTarget = it) } }, Modifier.weight(1f), label = { Text("Protein g") }, keyboardOptions = num, singleLine = true)
                        OutlinedTextField(s.stepsTarget.toString(), { v -> v.toIntOrNull()?.let { s = s.copy(stepsTarget = it) } }, Modifier.weight(1f), label = { Text("Steps") }, keyboardOptions = num, singleLine = true)
                    }
                    if (s.kcalTarget < s.kcalFloor) Text("${s.buddyName} won't go below ${s.kcalFloor} kcal — it'll use that instead.", color = C.amber, fontSize = 12.sp)
                    OutlinedTextField(s.goal, { s = s.copy(goal = it.take(300)) }, Modifier.fillMaxWidth(), label = { Text("Your goal in your words") })
                    Text("Estimates to start with; the weekly review adjusts them slowly from your real data.", color = C.muted, fontSize = 11.sp)
                }
                3 -> {
                    SectionTitle("Food & health")
                    OutlinedTextField(s.cuisine, { s = s.copy(cuisine = it.take(80)) }, Modifier.fillMaxWidth(), label = { Text("Food you usually eat") },
                        placeholder = { Text("e.g. Kerala home cooking, British, Punjabi, vegetarian…") }, singleLine = true)
                    OutlinedTextField(s.foodLikes, { s = s.copy(foodLikes = it.take(500)) }, Modifier.fillMaxWidth(), minLines = 2, label = { Text("Foods you like (optional)") })
                    OutlinedTextField(s.dietRules, { s = s.copy(dietRules = it.take(1000)) }, Modifier.fillMaxWidth(), minLines = 3,
                        label = { Text("Your food rules, one per line (optional)") }, placeholder = { Text("No sugary drinks\nFried food max once a week\nVegetarian on Fridays") })
                    OutlinedTextField(s.healthNotes, { s = s.copy(healthNotes = it.take(1000)) }, Modifier.fillMaxWidth(), minLines = 2,
                        label = { Text("Health notes to respect (optional)") }, placeholder = { Text("e.g. lactose intolerant, no whey, doctor said watch salt") })
                    Text("Only share what you're comfortable with. It stays on this phone, and goes to the AI only if you switch the AI on.", color = C.muted, fontSize = 11.sp)
                    Row2("Liver-friendly food rating (green/amber/red)", s.liverScore) { s = s.copy(liverScore = it) }
                    Row2("Malayalam flavour (Manglish words, Kerala festivals)", s.manglish) { s = s.copy(manglish = it) }
                }
                4 -> {
                    SectionTitle("Your week")
                    Text("Add the things that repeat — shifts, classes, clubs, worship. ${s.buddyName} reminds you before each one " +
                        "(gently first, louder only if you don't start), and fits food and workouts around them.", color = C.muted, fontSize = 13.sp)
                    RoutineEditor(routine) { routine = it }
                }
                5 -> {
                    SectionTitle("Weather & money")
                    var q by remember { mutableStateOf(s.city) }
                    var results by remember { mutableStateOf<List<Weather.Place>>(emptyList()) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(q, { q = it }, Modifier.weight(1f), label = { Text("Your town (for walk weather)") }, singleLine = true)
                        TextButton(onClick = { scope.launch { results = Weather.findPlace(q); if (results.isEmpty()) vm.toast.value = "No match (or offline)" } }) { Text("Find") }
                    }
                    results.forEach { p -> Text(p.name, color = C.teal, fontSize = 14.sp, modifier = Modifier.fillMaxWidth()
                        .clickable { s = s.copy(city = p.name, lat = p.lat, lon = p.lon); q = p.name; results = emptyList() }.padding(vertical = 6.dp)) }
                    if (s.hasLocation) Text("✓ ${s.city}", color = C.green, fontSize = 13.sp)
                    OutlinedTextField(s.currency, { s = s.copy(currency = it.take(4)) }, Modifier.width(140.dp), label = { Text("Currency") }, singleLine = true)

                    SectionTitle("AI (optional)")
                    Text("Chat, food photos, flashcards from slides and meal plans use Google Gemini with your own free key " +
                        "(aistudio.google.com → Get API key). When the AI is on, your messages, photos and the data needed to answer " +
                        "(today's food, targets, routine, profile) are sent to Google. Everything else works without it.", color = C.muted, fontSize = 12.sp)
                    Row2("Switch the AI on", s.aiConsent) { s = s.copy(aiConsent = it) }
                    if (s.aiConsent) OutlinedTextField(s.apiKey, { s = s.copy(apiKey = it.trim()) }, Modifier.fillMaxWidth(), label = { Text("Gemini API key") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation())
                }
            }
        }

        val canNext = when (step) { 0 -> s.adultConfirmed; 1 -> s.age == 0 || s.age >= 18; else -> true }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
            if (step in 1 until steps - 1) TextButton(onClick = { step++ }) { Text("Skip") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { if (step < steps - 1) step++ else finish() }, enabled = canNext,
                colors = ButtonDefaults.buttonColors(containerColor = C.teal, contentColor = C.bg)) {
                Text(if (step < steps - 1) "Next" else if (editing) "Save" else "Start")
            }
        }
    }
}

/** "Edit my week" page opened from Settings. */
@Composable
fun RoutineScreen(vm: NovaViewModel, onBack: () -> Unit) {
    val saved by vm.settings.collectAsState()
    var routine by remember { mutableStateOf(Routine.parse(saved.routineJson)) }
    var io by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = C.text) }
            Text("My week", color = C.text, fontSize = 20.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { io = true }) { Text("Import / export") }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { RoutineEditor(routine) { routine = it } }
        Button(onClick = { vm.saveRoutine(routine); onBack() }, Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = C.teal, contentColor = C.bg)) { Text("Save my week") }
    }
    if (io) {
        var text by remember { mutableStateOf(routine.toJson().toString(2)) }
        val clip = androidx.compose.ui.platform.LocalClipboardManager.current
        AlertDialog(onDismissRequest = { io = false }, title = { Text("Routine as text") },
            text = { Column {
                Text("Copy this to move your week to another phone, or paste one in.", color = C.muted, fontSize = 12.sp)
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().height(260.dp))
            } },
            confirmButton = { TextButton(onClick = {
                val r = Routine.parse(text)
                if (r.isEmpty) vm.toast.value = "That doesn't look like a routine" else { routine = r; io = false }
            }) { Text("Use pasted") } },
            dismissButton = { TextButton(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(text)); vm.toast.value = "Copied" }) { Text("Copy") } })
    }
}

private fun dayShort(d: DayOfWeek) = d.getDisplayName(TextStyle.SHORT, Locale.getDefault())
private fun parseTime(s: String): LocalTime? = runCatching { LocalTime.parse(s.trim().padStart(5, '0')) }.getOrNull()

/** Walk, bedtime, workout days and the list of weekly items. */
@Composable
fun RoutineEditor(r: Routine, onChange: (Routine) -> Unit) {
    var editing by remember { mutableStateOf<Int?>(null) }   // index, or -1 for new
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NovaCard {
            Row2("Daily walk", r.walkAt != null) { on -> onChange(r.copy(walkAt = if (on) LocalTime.of(6, 30) else null)) }
            if (r.walkAt != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimeField("Walk at", r.walkAt, Modifier.weight(1f)) { onChange(r.copy(walkAt = it)) }
                OutlinedTextField(r.walkMin.toString(), { v -> v.toIntOrNull()?.let { onChange(r.copy(walkMin = it.coerceIn(5, 180))) } },
                    Modifier.weight(1f), label = { Text("Minutes") }, keyboardOptions = num, singleLine = true)
            }
            TimeField("Bedtime", r.bedtime, Modifier.fillMaxWidth()) { onChange(r.copy(bedtime = it)) }
        }
        NovaCard {
            SectionTitle("Workout days (30 min, sessions A → B → C)")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DayOfWeek.entries.forEach { d ->
                    val on = r.workouts.any { it.day == d }
                    FilterChip(on, { onChange(r.copy(workouts = if (on) r.workouts.filter { it.day != d } else (r.workouts + WorkoutSlot(d, LocalTime.of(18, 0))).sortedBy { it.day })) },
                        label = { Text(dayShort(d)) })
                }
            }
            r.workouts.forEach { w ->
                TimeField("${dayShort(w.day)} workout at", w.time, Modifier.fillMaxWidth()) { t -> onChange(r.copy(workouts = r.workouts.map { if (it.day == w.day) it.copy(time = t) else it })) }
            }
        }
        SectionTitle("Every week")
        r.items.forEachIndexed { i, it ->
            Row(Modifier.fillMaxWidth().clickable { editing = i }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(it.label, color = C.text, fontSize = 14.sp)
                    Text("${it.kind.label} · ${it.days.sorted().joinToString(" ") { d -> dayShort(d) }} · ${it.start}–${it.end}" +
                        (if (it.onlyWeek > 0) " · only week ${it.onlyWeek}" else "") + (if (it.skipWeek > 0) " · not week ${it.skipWeek}" else "") +
                        (if (it.kind == Kind.WORK && it.rate > 0) " · ${com.aethernova.nova.data.Money.sym}${"%.2f".format(it.rate)}/h" else ""),
                        color = C.muted, fontSize = 12.sp)
                }
                IconButton(onClick = { onChange(r.copy(items = r.items.filterIndexed { j, _ -> j != i })) }) { Icon(Icons.Default.Delete, "Delete", tint = C.muted) }
            }
        }
        OutlinedButton(onClick = { editing = -1 }) { Text("+ Add shift, class or event") }
    }
    editing?.let { idx ->
        val orig = r.items.getOrNull(idx)
        ItemDialog(orig, onDismiss = { editing = null }) { item ->
            onChange(r.copy(items = if (orig == null) r.items + item else r.items.mapIndexed { j, x -> if (j == idx) item else x }))
            editing = null
        }
    }
}

@Composable
private fun TimeField(label: String, value: LocalTime, modifier: Modifier, onChange: (LocalTime) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(text, { t -> text = t; parseTime(t)?.let(onChange) }, modifier, label = { Text("$label (HH:mm)") }, singleLine = true,
        isError = parseTime(text) == null)
}

@Composable
private fun ItemDialog(orig: RoutineItem?, onDismiss: () -> Unit, onSave: (RoutineItem) -> Unit) {
    var label by remember { mutableStateOf(orig?.label ?: "") }
    var kind by remember { mutableStateOf(orig?.kind ?: Kind.WORK) }
    var days by remember { mutableStateOf(orig?.days ?: emptySet()) }
    var start by remember { mutableStateOf(orig?.start?.toString() ?: "09:00") }
    var end by remember { mutableStateOf(orig?.end?.toString() ?: "17:00") }
    var travel by remember { mutableStateOf((orig?.travelMin ?: 15).toString()) }
    var brk by remember { mutableStateOf((orig?.breakMin ?: 0).toString()) }
    var rate by remember { mutableStateOf(orig?.rate?.takeIf { it > 0 }?.let { "%.2f".format(it) } ?: "") }
    var weekRule by remember { mutableStateOf(when { (orig?.onlyWeek ?: 0) > 0 -> "only"; (orig?.skipWeek ?: 0) > 0 -> "skip"; else -> "every" }) }
    var weekN by remember { mutableIntStateOf(maxOf(orig?.onlyWeek ?: 0, orig?.skipWeek ?: 0).coerceAtLeast(1)) }
    val st = parseTime(start); val en = parseTime(end)
    val ok = label.isNotBlank() && days.isNotEmpty() && st != null && en != null && en > st
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (orig == null) "Add to your week" else "Edit") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(label, { label = it.take(60) }, label = { Text("Name (e.g. Café shift, Maths lecture)") }, singleLine = true)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(Kind.WORK, Kind.CLASS, Kind.ONLINE, Kind.WORSHIP, Kind.SOCIAL, Kind.OTHER).forEach { k -> FilterChip(kind == k, { kind = k }, label = { Text(k.label) }) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DayOfWeek.entries.forEach { d -> FilterChip(d in days, { days = if (d in days) days - d else days + d }, label = { Text(dayShort(d)) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(start, { start = it }, Modifier.weight(1f), label = { Text("Start") }, singleLine = true, isError = st == null)
                OutlinedTextField(end, { end = it }, Modifier.weight(1f), label = { Text("End") }, singleLine = true, isError = en == null || (st != null && en <= st))
            }
            if (kind != Kind.ONLINE) OutlinedTextField(travel, { travel = it.filter(Char::isDigit).take(3) }, label = { Text("Travel time (min)") }, keyboardOptions = num, singleLine = true)
            if (kind == Kind.WORK) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(rate, { rate = it.filter { c -> c.isDigit() || c == '.' }.take(6) }, Modifier.weight(1f), label = { Text("Pay per hour") }, keyboardOptions = dec, singleLine = true)
                OutlinedTextField(brk, { brk = it.filter(Char::isDigit).take(3) }, Modifier.weight(1f), label = { Text("Unpaid break min") }, keyboardOptions = num, singleLine = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("every" to "Every week", "only" to "Only week…", "skip" to "Not week…").forEach { (k, l) -> FilterChip(weekRule == k, { weekRule = k }, label = { Text(l) }) }
            }
            if (weekRule != "every") Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (1..5).forEach { n -> FilterChip(weekN == n, { weekN = n }, label = { Text("$n") }) }
            }
            if (weekRule != "every") Text("Week $weekN of the month = days ${(weekN - 1) * 7 + 1}–${minOf(weekN * 7, 31)}.", color = C.muted, fontSize = 11.sp)
        } },
        confirmButton = { TextButton(enabled = ok, onClick = {
            onSave(RoutineItem(days, st!!, en!!, kind, label.trim(), travel.toIntOrNull() ?: 0, brk.toIntOrNull() ?: 0, rate.toDoubleOrNull() ?: 0.0,
                if (weekRule == "only") weekN else 0, if (weekRule == "skip") weekN else 0))
        }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
