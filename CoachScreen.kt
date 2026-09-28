package com.aethernova.nova.vision

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.aethernova.nova.ai.Mood
import com.aethernova.nova.data.Pet
import com.aethernova.nova.ui.BodyAction
import com.aethernova.nova.ui.C
import com.aethernova.nova.ui.NovaBody
import com.aethernova.nova.ui.NovaViewModel
import kotlinx.coroutines.runBlocking

private val malayalamNumbers = listOf("onnu", "randu", "moonu", "naalu", "anju", "aaru", "ezhu", "ettu", "onpathu", "pathu")

/**
 * Camera coach: counts squats and push-ups from your pose and gives one form cue at a time,
 * or guards your posture while you study. All pose tracking runs on the phone.
 */
@Composable
fun CoachScreen(vm: NovaViewModel, startWith: Exercise = Exercise.SQUAT, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val s by vm.settings.collectAsState()
    val mood by vm.mood.collectAsState()
    val action by vm.action.collectAsState()
    val speaking by vm.speaker.speaking.collectAsState()
    val xp by vm.totalXp.collectAsState()

    var camOk by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val askCam = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { camOk = it }

    var exercise by remember { mutableStateOf(startWith) }
    var modelReady by remember { mutableStateOf(ModelStore.isReady(ctx, ModelStore.Model.POSE)) }
    var progress by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    var reps by remember { mutableIntStateOf(0) }
    var cue by remember { mutableStateOf("") }
    var seen by remember { mutableStateOf(false) }
    var startedAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var counter by remember { mutableStateOf(RepCounter.forExercise(startWith)) }
    var lastCueAt by remember { mutableLongStateOf(0L) }
    var lastSeenAt by remember { mutableLongStateOf(0L) }

    var baseline by remember { mutableStateOf<Double?>(null) }
    var calibrating by remember { mutableStateOf(false) }
    val calSamples = remember { mutableListOf<Double>() }
    var monitor by remember { mutableStateOf<PostureMonitor?>(null) }
    var slouching by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!modelReady) runCatching { ModelStore.load(ctx, ModelStore.Model.POSE) { progress = it } }
            .onSuccess { modelReady = true }.onFailure { error = "Couldn't download the pose model: ${it.message}" }
    }

    fun resetFor(e: Exercise) {
        exercise = e; reps = 0; cue = ""; counter = RepCounter.forExercise(e); startedAt = System.currentTimeMillis()
    }

    fun say(text: String) { vm.speakLine(text) }
    fun cueOnce(text: String) {
        val now = System.currentTimeMillis()
        if (now - lastCueAt > 6000) { lastCueAt = now; cue = text; say(text) }
    }

    fun onPose(lm: List<P>?) {
        val now = System.currentTimeMillis()
        if (lm == null || lm.size < 33) { seen = now - lastSeenAt < 2000; return }
        lastSeenAt = now; seen = true
        when (exercise) {
            Exercise.SQUAT, Exercise.PUSHUP -> {
                val a = (if (exercise == Exercise.SQUAT) Coach.kneeAngle(lm) else Coach.elbowAngle(lm)) ?: return
                // form checks while moving
                if (exercise == Exercise.SQUAT && a < 110 && Coach.torsoLean(lm) > 50) cueOnce("Chest up, don't fold forward")
                if (exercise == Exercise.PUSHUP) Coach.bodyLine(lm)?.let { if (it < 150) cueOnce("Keep your hips in line with your shoulders") }
                when (val ev = counter.feed(a)) {
                    is RepCounter.Event.Rep -> {
                        reps = ev.count
                        val n = ev.count
                        say(if (s.manglish && n <= 10) malayalamNumbers[n - 1] else n.toString())
                        if (n % 5 == 0) vm.react(Mood.PROUD, BodyAction.CHEER, null, 1200) else vm.react(Mood.HAPPY, BodyAction.IDLE, null, 400)
                    }
                    is RepCounter.Event.Partial -> cueOnce(if (exercise == Exercise.SQUAT) "Go a bit deeper — thighs to parallel" else "Lower your chest a bit more")
                    null -> Unit
                }
            }
            Exercise.POSTURE -> {
                val r = Coach.postureRatio(lm) ?: return
                if (calibrating) {
                    calSamples += r
                    if (calSamples.size >= 15) {
                        baseline = calSamples.average(); monitor = PostureMonitor(baseline!!)
                        calibrating = false; calSamples.clear()
                        vm.react(Mood.HAPPY, BodyAction.IDLE, "Got it. I'll tell you if you slouch.")
                    }
                } else monitor?.let { m ->
                    slouching = m.isSlouching(r)
                    if (m.feed(r, now)) vm.react(Mood.WORRIED, BodyAction.IDLE, if (s.manglish) "Machane, sit up straight! Neck ne sookshikku." else "Sit up straight — your neck will thank you.", 2500)
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = C.text) }
            Text("Camera coach", color = C.text, fontSize = 20.sp, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Exercise.entries.forEach { e -> FilterChip(exercise == e, { resetFor(e) }, label = { Text(e.label) }) }
        }

        Box(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            when {
                !camOk -> Button(onClick = { askCam.launch(Manifest.permission.CAMERA) }, Modifier.align(Alignment.Center)) { Text("Allow camera") }
                error != null -> Text(error!!, color = C.red)
                !modelReady -> Column(Modifier.fillMaxWidth()) {
                    Text("Downloading the pose model (one time, ~6 MB)… $progress%", color = C.muted)
                    LinearProgressIndicator(progress = { progress / 100f }, Modifier.fillMaxWidth(), color = C.teal, trackColor = C.surface2)
                }
                else -> {
                    val main = ContextCompat.getMainExecutor(ctx)
                    LiveCamera(
                        visible = true,
                        modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(18.dp)),
                        minGapMs = 60,
                        create = { c ->
                            val model = runBlocking { ModelStore.load(c, ModelStore.Model.POSE) }
                            PoseEngine(c, model) { lm -> main.execute { onPose(lm) } }
                        },
                        onError = { error = it },
                    )
                    NovaBody(mood, action, speaking, false, Pet.skin(s.skin, Pet.level(xp)),
                        Modifier.align(Alignment.TopStart).width(120.dp), height = 150.dp)
                    if (exercise != Exercise.POSTURE) Text("$reps", color = C.teal, fontSize = 72.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.TopEnd).padding(12.dp))
                }
            }
        }

        if (!seen && modelReady && camOk) Text("Step back so I can see your whole body. Side-on works best for squats and push-ups.", color = C.amber, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        if (cue.isNotBlank()) Text(cue, color = C.amber, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))

        when (exercise) {
            Exercise.POSTURE -> Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Text(
                    when { calibrating -> "Hold still, sitting up straight…"; baseline == null -> "Prop the phone in front of you, sit upright, then tap Calibrate."
                        slouching -> "Slouching 😬"; else -> "Posture OK ✅" },
                    color = if (slouching) C.amber else C.text, fontSize = 16.sp,
                )
                Button(onClick = { calibrating = true; calSamples.clear() }, Modifier.padding(top = 6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = C.teal, contentColor = C.bg)) { Text(if (baseline == null) "Calibrate" else "Re-calibrate") }
                Text("Nova warns after 8 s of slouching, at most once a minute. Keep this screen open while you study (it uses the camera, so plug in for long sessions).",
                    color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
            else -> Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val minutes = ((System.currentTimeMillis() - startedAt) / 60_000).toInt()
                    if (reps > 0) vm.logCoachSession(exercise.label, reps, minutes)
                    resetFor(exercise)
                }, enabled = reps > 0, colors = ButtonDefaults.buttonColors(containerColor = C.teal, contentColor = C.bg)) { Text("Finish & log") }
                OutlinedButton(onClick = { resetFor(exercise) }) { Text("Reset") }
            }
        }
        Text(
            when (exercise) {
                Exercise.SQUAT -> "Place the phone on the floor about 2 m away, side-on. A rep counts when your knees bend past ~100° and you stand back up."
                Exercise.PUSHUP -> "Phone on the floor, side-on, about 1.5 m away. A rep counts when your elbows bend past ~95° and you push back up."
                Exercise.POSTURE -> "Uses how high your head sits above your shoulders compared with when you calibrated."
            },
            color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp),
        )
    }
}
