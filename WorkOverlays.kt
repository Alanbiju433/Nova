package com.aethernova.nova.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.aethernova.nova.data.Breathing
import com.aethernova.nova.data.Cooking
import com.aethernova.nova.data.Leitner
import com.aethernova.nova.voice.ContinuousListener
import kotlinx.coroutines.delay
import java.time.LocalDate

@Composable
private fun Header(title: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) =
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = C.text) }
        Text(title, color = C.text, fontSize = 20.sp, modifier = Modifier.weight(1f))
        trailing()
    }

/** Keeps the screen awake while this composable is shown. */
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
}

// ======================= Mock interview =======================

@Composable
fun InterviewScreen(vm: NovaViewModel, role: String, onBack: () -> Unit) {
    val st by vm.interview.collectAsState()
    var answer by remember { mutableStateOf("") }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val heard = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            answer = (answer + " " + heard).trim()
        }
    }
    fun listen() {
        vm.speaker.stop()
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GB")
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Answer out loud — tap again to add more")
        runCatching { speech.launch(i) }.onFailure { vm.toast.value = "No speech recogniser on this phone" }
    }
    val back = { vm.endInterview(); onBack() }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Header("Mock interview", back)
        val s = st
        if (s == null || s.busy && s.questions.isEmpty()) {
            Text("Preparing questions for $role…", color = C.muted, modifier = Modifier.padding(16.dp)); return@Column
        }
        Text("${s.role}${if (s.company.isNotBlank()) " · ${s.company}" else ""}", color = C.muted, fontSize = 13.sp)
        if (s.done) {
            val avg = s.feedback.map { it.score }.average()
            NovaCard(Modifier.padding(top = 12.dp)) {
                SectionTitle("Result: ${"%.1f".format(avg)} / 10")
                Text(when { avg >= 8 -> "Interview-ready. Practise once more the day before."; avg >= 6 -> "Good base. Work on the tips below and go again."
                    else -> "Keep practising — structure (STAR) and concrete numbers will lift you fast." }, color = C.text, fontSize = 14.sp)
                s.questions.forEachIndexed { i, q ->
                    Text("Q${i + 1}. $q", color = C.text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
                    Text("${s.feedback[i].score}/10 · " + s.feedback[i].tips.joinToString(" "), color = C.muted, fontSize = 12.sp)
                }
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.startInterview(s.role, s.company) }, colors = ButtonDefaults.buttonColors(containerColor = C.teal, contentColor = C.bg)) { Text("Again") }
                OutlinedButton(onClick = back) { Text("Done") }
            }
            return@Column
        }
        val answered = s.feedback.size > s.index
        Text("Question ${s.index + 1} of ${s.questions.size}", color = C.teal, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
        Text(s.current.orEmpty(), color = C.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 8.dp))
        TextButton(onClick = { vm.speakAlways(s.current.orEmpty()) }) { Text("🔊 Hear it again") }
        if (!answered) {
            OutlinedTextField(answer, { answer = it }, Modifier.fillMaxWidth(), minLines = 5, label = { Text("Your answer (speak or type)") })
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { listen() }) { Text("🎤 Speak") }
                Button(onClick = { vm.answerInterview(answer) }, enabled = !s.busy && answer.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = C.teal, contentColor = C.bg)) { Text(if (s.busy) "Scoring…" else "Submit") }
            }
            Text("Tip: Situation → Task → what I did (Action) → Result with a number. About a minute.", color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        } else {
            val fb = s.feedback[s.index]
            NovaCard(Modifier.padding(top = 8.dp)) {
                Text("${fb.score} / 10", color = if (fb.score >= 7) C.green else if (fb.score >= 5) C.amber else C.red, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                fb.tips.forEach { Text("• $it", color = C.text, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp)) }
            }
            Button(onClick = { answer = ""; vm.nextQuestion() }, Modifier.padding(top = 12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = C.teal, contentColor = C.bg)) { Text(if (s.index + 1 < s.questions.size) "Next question" else "See results") }
        }
    }
}

// ======================= Flashcard review =======================

@Composable
fun FlashReviewScreen(vm: NovaViewModel, onBack: () -> Unit) {
    val all by vm.cards.collectAsState()
    // snapshot the due list once so cards don't jump while reviewing
    val queue = remember { Leitner.due(all, LocalDate.now()).toMutableList() }
    var i by remember { mutableIntStateOf(0) }
    var flipped by remember { mutableStateOf(false) }
    var right by remember { mutableIntStateOf(0) }
    val finish = { vm.finishedReview(i); onBack() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Header("Review", finish) { Text("${i}/${queue.size}", color = C.muted) }
        if (i >= queue.size) {
            Spacer(Modifier.height(40.dp))
            Text("All done! $right / ${queue.size} right.", color = C.text, fontSize = 22.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Text("Wrong ones come back tomorrow; right ones move further out.", color = C.muted, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), textAlign = TextAlign.Center)
            Button(onClick = finish, Modifier.align(Alignment.CenterHorizontally).padding(top = 24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = C.teal, contentColor = C.bg)) { Text("Finish") }
            return@Column
        }
        val c = queue[i]
        Text("${c.deck} · box ${c.box}", color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        Box(Modifier.fillMaxWidth().weight(1f).padding(vertical = 16.dp).clip(RoundedCornerShape(20.dp))
            .background(if (flipped) C.surface2 else C.surface).clickable { flipped = !flipped }.padding(24.dp),
            contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(c.front, color = C.text, fontSize = 20.sp, textAlign = TextAlign.Center)
                if (flipped) Text(c.back, color = C.teal, fontSize = 17.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 20.dp))
                else Text("Tap to show answer", color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 20.dp))
            }
        }
        if (flipped) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { vm.reviewCard(c, false); flipped = false; i++ }, Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = C.red, contentColor = C.bg)) { Text("Didn't know") }
            Button(onClick = { vm.reviewCard(c, true); right++; flipped = false; i++ }, Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = C.green, contentColor = C.bg)) { Text("Knew it") }
        }
        TextButton(onClick = { vm.deleteCard(c); flipped = false; i++ }) { Text("Delete this card", color = C.muted) }
    }
}

// ======================= Hands-free cooking =======================

@Composable
fun CookScreen(vm: NovaViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val recipe by vm.cookRecipe.collectAsState()
    val busy by vm.cookBusy.collectAsState()
    var ask by remember { mutableStateOf("") }
    val r = recipe
    if (r == null) {
        Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
            Header("Cooking guide", onBack)
            Text("Pick a recipe. I'll read each step out loud; say \"next\", \"back\", \"repeat\", \"ingredients\" or \"timer 5 minutes\" — no need to touch the phone with messy hands.",
                color = C.muted, fontSize = 13.sp)
            Cooking.BUILT_IN.forEach { rec ->
                NovaCard(Modifier.padding(top = 10.dp).clickable { vm.cookRecipe.value = rec }) {
                    Text(rec.name, color = C.text, fontWeight = FontWeight.SemiBold)
                    Text("Serves ${rec.serves} · ~${rec.kcalPerServe} kcal · ${rec.proteinPerServe} g protein each · ${rec.steps.size} steps", color = C.muted, fontSize = 12.sp)
                }
            }
            Text("Or ask for something", color = C.text, modifier = Modifier.padding(top = 16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(ask, { ask = it }, Modifier.weight(1f), placeholder = { Text("e.g. squid roast with what I have") }, singleLine = true)
                TextButton(onClick = { vm.aiRecipe(ask) }, enabled = !busy) { Text(if (busy) "…" else "Make") }
            }
        }
        return
    }

    KeepScreenOn()
    var step by remember(r) { mutableIntStateOf(0) }
    var timerEnd by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var heard by remember { mutableStateOf("") }
    var micOk by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micOk = it }

    fun speakStep(n: Int) { vm.speakAlways("Step ${n + 1}. ${r.steps[n].text}") }
    fun startTimer(sec: Int) {
        val s = if (sec > 0) sec else r.steps[step].timerSec
        if (s <= 0) { vm.speakAlways("This step has no timer. Say, timer 5 minutes."); return }
        timerEnd = System.currentTimeMillis() + s * 1000L
        vm.speakAlways("Timer set for ${if (s >= 60) "${s / 60} minutes" else "$s seconds"}.")
    }
    val handle = rememberUpdatedState { text: String ->
        heard = text
        when (val c = Cooking.parse(text)) {
            Cooking.Cmd.Next -> if (step < r.steps.lastIndex) { step++; speakStep(step) } else vm.speakAlways("That was the last step. Enjoy your food!")
            Cooking.Cmd.Back -> if (step > 0) { step--; speakStep(step) }
            Cooking.Cmd.Repeat -> speakStep(step)
            Cooking.Cmd.Ingredients -> vm.speakAlways("You need: " + r.ingredients.joinToString(", "))
            Cooking.Cmd.Stop -> { timerEnd = 0L; vm.speaker.stop() }
            is Cooking.Cmd.Timer -> startTimer(c.seconds)
            null -> {}
        }
    }
    LaunchedEffect(r) { speakStep(0) }
    LaunchedEffect(timerEnd) {
        while (timerEnd > 0) {
            now = System.currentTimeMillis()
            if (now >= timerEnd) {
                timerEnd = 0L
                runCatching {
                    android.media.RingtoneManager.getRingtone(ctx, android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION))?.play()
                }
                vm.speakAlways("Time's up! Step ${step + 1}: check it now.")
            }
            delay(500)
        }
    }
    DisposableEffect(micOk) {
        vm.pauseHandsFree()
        val l = if (micOk) ContinuousListener(ctx, "en-GB", { vm.speaker.speaking.value }) { handle.value(it) }.also { it.start() } else null
        onDispose { l?.stop(); vm.speaker.stop(); vm.resumeHandsFree(micOk) }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Header(r.name, { vm.cookRecipe.value = null })
        Text("Step ${step + 1} of ${r.steps.size}", color = C.teal, fontSize = 14.sp)
        Text(r.steps[step].text, color = C.text, fontSize = 26.sp, lineHeight = 34.sp, modifier = Modifier.weight(1f).padding(vertical = 16.dp)
            .verticalScroll(rememberScrollState()))
        if (timerEnd > 0) {
            val left = ((timerEnd - now) / 1000).coerceAtLeast(0)
            Text("⏱ %d:%02d".format(left / 60, left % 60), color = C.amber, fontSize = 40.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
        if (!micOk) TextButton(onClick = { askMic.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Allow microphone for voice control") }
        else Text(if (heard.isBlank()) "🎤 Listening for next / back / repeat / timer…" else "🎤 heard: \"$heard\"", color = C.muted, fontSize = 12.sp)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { handle.value("back") }, enabled = step > 0, modifier = Modifier.weight(1f)) { Text("Back") }
            if (r.steps[step].timerSec > 0) OutlinedButton(onClick = { startTimer(0) }, modifier = Modifier.weight(1f)) {
                Text("⏱ ${r.steps[step].timerSec / 60}:%02d".format(r.steps[step].timerSec % 60)) }
            Button(onClick = { handle.value("next") }, Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = C.teal, contentColor = C.bg)) { Text("Next") }
        }
        TextButton(onClick = { handle.value("ingredients") }) { Text("Ingredients (${r.ingredients.size})") }
    }
}

// ======================= 2-day meal prep =======================

@Composable
fun MealPrepScreen(vm: NovaViewModel, onBack: () -> Unit) {
    val plan by vm.mealPlan.collectAsState()
    val busy by vm.mealBusy.collectAsState()
    val s by vm.settings.collectAsState()
    var note by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Header("2-day meal prep", onBack)
        Text("One cooking session, two days of food, around ${s.kcalTarget} kcal and ${s.proteinTarget} g protein a day.", color = C.muted, fontSize = 13.sp)
        OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true,
            placeholder = { Text("Anything special? e.g. use the squid, no rice") })
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.generateMealPrep(note) }, enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = C.teal, contentColor = C.bg)) { Text(if (busy) "Planning…" else "Plan with AI") }
            OutlinedButton(onClick = { vm.generateMealPrep("", useAi = false) }, enabled = !busy) { Text("Standard plan") }
        }
        val p = plan ?: run {
            Text("No plan yet.", color = C.muted, modifier = Modifier.padding(top = 16.dp)); return@Column
        }
        p.days.forEach { d ->
            NovaCard(Modifier.padding(top = 12.dp)) {
                SectionTitle("${d.label} · ${d.kcal} kcal · ${d.protein} g protein")
                d.meals.forEach { m ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text(m.slot, Modifier.padding(end = 8.dp), color = C.teal, fontSize = 12.sp)
                        Text(m.name, Modifier.weight(1f), color = C.text, fontSize = 13.sp)
                        Text("${m.protein} g", color = C.muted, fontSize = 12.sp)
                    }
                }
                if (d.protein < s.proteinTarget - 15) Text("A bit low on protein — add a glass of milk or 2 eggs.", color = C.amber, fontSize = 12.sp)
            }
        }
        if (p.prepSteps.isNotEmpty()) NovaCard(Modifier.padding(top = 12.dp)) {
            SectionTitle("Batch cook")
            p.prepSteps.forEachIndexed { i, t -> Text("${i + 1}. $t", color = C.text, fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp)) }
        }
        if (p.shopping.isNotEmpty()) NovaCard(Modifier.padding(top = 12.dp)) {
            SectionTitle("Shopping")
            p.shopping.forEach { Text("• ${it.first}${if (it.second.isNotBlank()) " — ${it.second}" else ""}", color = C.text, fontSize = 13.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = { vm.mealPrepToGroceries() }, colors = ButtonDefaults.buttonColors(containerColor = C.teal, contentColor = C.bg)) { Text("Add to grocery list") }
                OutlinedButton(onClick = { vm.open("cook") }) { Text("Cook with me") }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ======================= Wind-down breathing =======================

@Composable
fun BreatheScreen(vm: NovaViewModel, onBack: () -> Unit) {
    KeepScreenOn()
    var pattern by remember { mutableStateOf(Breathing.CALM) }
    var minutes by remember { mutableIntStateOf(3) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(0L) }
    val running = startedAt > 0
    LaunchedEffect(startedAt) {
        var last = ""
        while (startedAt > 0) {
            now = System.currentTimeMillis()
            val el = now - startedAt
            if (el >= minutes * 60_000L) { vm.breathingDone(minutes); startedAt = 0L; break }
            val label = Breathing.at(pattern, el).first
            if (label != last) { last = label; vm.speakAlways(label) }
            delay(50)
        }
    }
    val finish = { if (running) vm.breathingDone(((now - startedAt) / 60_000L).toInt()); vm.speaker.stop(); onBack() }

    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(C.bg, C.surface))).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Header("Wind down", finish)
        val (label, _, fill) = if (running) Breathing.at(pattern, now - startedAt) else Triple("Ready when you are", 0f, 0.35f)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(280.dp)) {
                val maxR = size.minDimension / 2
                val r = maxR * (0.35f + 0.6f * fill)
                drawCircle(Brush.radialGradient(listOf(C.teal.copy(alpha = 0.55f), C.teal.copy(alpha = 0.08f)), center, r), r, center)
                drawCircle(C.teal.copy(alpha = 0.4f), maxR * 0.95f, center, style = Stroke(2.dp.toPx()))
                drawCircle(C.teal, 5.dp.toPx(), Offset(center.x, center.y - r))
            }
            Text(label, color = C.text, fontSize = 24.sp)
        }
        if (running) {
            val left = (minutes * 60 - (now - startedAt) / 1000).coerceAtLeast(0)
            Text("%d:%02d left".format(left / 60, left % 60), color = C.muted)
            TextButton(onClick = finish) { Text("Stop") }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Breathing.ALL.forEach { p -> FilterChip(pattern == p, { pattern = p }, label = { Text(p.name) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                listOf(2, 3, 5).forEach { m -> FilterChip(minutes == m, { minutes = m }, label = { Text("$m min") }) }
            }
            Text("Slow breathing with a longer out-breath helps you settle after a busy shift.", color = C.muted, fontSize = 12.sp,
                textAlign = TextAlign.Center, modifier = Modifier.padding(12.dp))
            Button(onClick = { startedAt = System.currentTimeMillis() }, Modifier.padding(bottom = 24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = C.teal, contentColor = C.bg)) { Text("Start") }
        }
    }
}
