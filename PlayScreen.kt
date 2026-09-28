package com.aethernova.nova.play

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import com.aethernova.nova.vision.FaceEngine
import com.aethernova.nova.vision.FrameConsumer
import com.aethernova.nova.vision.LiveCamera
import com.aethernova.nova.vision.ModelStore
import com.aethernova.nova.vision.MusicListener
import com.aethernova.nova.vision.TakeTurns
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.math.sqrt
import kotlin.random.Random

/** Latest per-frame gesture, written from the camera thread and read by the games. */
private object Latest { @Volatile var gesture: PlayGesture? = null; @Volatile var at = 0L }

private enum class Mode { FREE, RPS, REACTION }

/**
 * EMO-style play mode: gestures, games, face reading and dancing to music.
 * The camera only runs while this screen is open, and all recognition happens on the phone.
 */
@Composable
fun PlayScreen(vm: NovaViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val mood by vm.mood.collectAsState()
    val action by vm.action.collectAsState()
    val speaking by vm.speaker.speaking.collectAsState()
    val gesture by vm.lastGesture.collectAsState()
    val s by vm.settings.collectAsState()
    val xp by vm.totalXp.collectAsState()

    fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    var camOk by remember { mutableStateOf(granted(Manifest.permission.CAMERA)) }
    var micOk by remember { mutableStateOf(granted(Manifest.permission.RECORD_AUDIO)) }
    var gesturesOn by remember { mutableStateOf(!com.aethernova.nova.CrashLog.cameraCrashedLastTime(ctx)) }
    var faceOn by remember { mutableStateOf(false) }
    var musicOn by remember { mutableStateOf(false) }
    var showPreview by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var mode by remember { mutableStateOf(Mode.FREE) }
    val currentMode by rememberUpdatedState(mode)
    var status by remember { mutableStateOf("") }
    var me by remember { mutableIntStateOf(0) }
    var nova by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val prefs = remember { ctx.getSharedPreferences("nova_games", Context.MODE_PRIVATE) }
    var best by remember { mutableIntStateOf(prefs.getInt("reaction_best", 0)) }

    val askCam = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { camOk = it }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micOk = it; if (it) musicOn = true }

    ShakeListener { vm.onShake() }

    if (musicOn && micOk) DisposableEffect(Unit) {
        val main = ContextCompat.getMainExecutor(ctx)
        val l = MusicListener { main.execute { vm.dance() } }
        l.start()
        onDispose { l.stop() }
    }

    // ---- games ----
    fun rpsRound() = scope.launch {
        if (busy) return@launch
        busy = true
        for (w in listOf("Rock…", "Paper…", "Scissors…")) { status = w; vm.react(Mood.THINKING, BodyAction.IDLE, w.trimEnd('…')); delay(750) }
        status = "SHOOT!"
        val t0 = System.currentTimeMillis()
        delay(900)
        val g = Latest.gesture.takeIf { Latest.at >= t0 - 300 }
        val mine = when (g) { PlayGesture.FIST -> 0; PlayGesture.WAVE -> 1; PlayGesture.PEACE -> 2; else -> -1 }
        val hers = Random.nextInt(3)
        val names = listOf("✊ rock", "✋ paper", "✌️ scissors")
        if (mine < 0) {
            status = "I couldn't see your hand — keep it in the camera."
            vm.react(Mood.WORRIED, BodyAction.IDLE, "Show me your hand, da!")
        } else {
            val outcome = (mine - hers + 3) % 3  // 0 tie, 1 you win, 2 Nova wins
            status = "You: ${names[mine]}  ·  Nova: ${names[hers]}"
            when (outcome) {
                0 -> vm.react(Mood.THINKING, BodyAction.IDLE, "Tie! Again?")
                1 -> { me++; vm.react(Mood.WORRIED, BodyAction.IDLE, if (s.manglish) "Ayyo, you win this one." else "You win this one.") }
                else -> { nova++; vm.react(Mood.CHEEKY, BodyAction.DANCE, "I win! Hehe.") }
            }
            if (me == 3 || nova == 3) {
                delay(1500)
                if (me == 3) vm.react(Mood.PROUD, BodyAction.CHEER, "You beat me $me to $nova. Respect${if (s.manglish) ", machane" else ""}.")
                else vm.react(Mood.CHEEKY, BodyAction.DANCE, "Champion Nova wins $nova to $me!")
                me = 0; nova = 0
            }
        }
        busy = false
    }

    fun reactionRound() = scope.launch {
        if (busy) return@launch
        busy = true
        status = "Wait for it… (show ✋ when I say NOW)"
        vm.react(Mood.FOCUS, BodyAction.IDLE, null)
        val wait = Random.nextLong(2000, 5000)
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < wait) {
            if (Latest.gesture == PlayGesture.WAVE && Latest.at > start + 300) {
                status = "Too early! 😅"; vm.react(Mood.CHEEKY, BodyAction.IDLE, "Too early, da!"); busy = false; return@launch
            }
            delay(30)
        }
        status = "✋ NOW!"
        vm.react(Mood.SURPRISED, BodyAction.WAVE, null)
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 3000) {
            if (Latest.gesture == PlayGesture.WAVE && Latest.at > t0) {
                val ms = (Latest.at - t0).toInt()
                val isBest = best == 0 || ms < best
                if (isBest) { best = ms; prefs.edit().putInt("reaction_best", ms).apply() }
                status = "$ms ms" + if (isBest) " — new best! 🏆" else "  (best $best ms)"
                vm.react(Mood.PROUD, BodyAction.CHEER, if (isBest) "New record! $ms milliseconds." else "$ms milliseconds.")
                busy = false; return@launch
            }
            delay(15)
        }
        status = "Too slow — 3 seconds passed 😴"
        vm.react(Mood.SLEEPY, BodyAction.IDLE, "Were you asleep?")
        busy = false
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = C.text) }
                Text("Play mode", color = C.text, fontSize = 20.sp, modifier = Modifier.weight(1f))
                Text("Camera", color = C.muted, fontSize = 12.sp)
                Switch(showPreview, { showPreview = it }, Modifier.padding(start = 6.dp))
            }

            NovaBody(
                mood, action, speaking, thinking = false,
                skin = Pet.skin(s.skin, Pet.level(xp)),
                modifier = Modifier.padding(top = 4.dp), height = 260.dp, roam = false,
                onTap = { vm.onFaceTap() }, onLongPress = { vm.onFacePat() },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(mode == Mode.FREE, { mode = Mode.FREE; status = "" }, label = { Text("Free play") })
                FilterChip(mode == Mode.RPS, { mode = Mode.RPS; status = "First to 3 wins"; me = 0; nova = 0 }, label = { Text("Rock-paper-scissors") })
                FilterChip(mode == Mode.REACTION, { mode = Mode.REACTION; status = if (best > 0) "Best: $best ms" else "" }, label = { Text("Reaction") })
            }

            when (mode) {
                Mode.FREE -> {
                    Text(gesture?.let { "Saw: $it" } ?: "Show me a hand gesture 👋", color = C.teal, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.dance() }) { Text("Dance 💃") }
                        OutlinedButton(onClick = { vm.wave() }) { Text("Wave 👋") }
                        OutlinedButton(onClick = { vm.tellJoke() }) { Text("Joke 😂") }
                    }
                }
                Mode.RPS -> {
                    Text("You $me – $nova Nova", color = C.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                    Text(status, color = C.teal, fontSize = 15.sp)
                    Button(onClick = { rpsRound() }, enabled = !busy && camOk && gesturesOn, modifier = Modifier.padding(top = 6.dp)) { Text("Play round") }
                    Text("✊ rock · ✋ paper · ✌️ scissors — hold it up on SHOOT", color = C.muted, fontSize = 12.sp)
                }
                Mode.REACTION -> {
                    Text(status, color = C.teal, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                    Button(onClick = { reactionRound() }, enabled = !busy && camOk && gesturesOn, modifier = Modifier.padding(top = 6.dp)) { Text("Start") }
                    Text("Show an open palm ✋ as fast as you can when Nova says NOW.", color = C.muted, fontSize = 12.sp)
                }
            }
            error?.let { Text(it, color = C.red, fontSize = 13.sp) }

            Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                ToggleRow("Read my face (smile, tired, surprised)", faceOn) { faceOn = it }
                ToggleRow("Dance when music plays (mic)", musicOn && micOk) {
                    if (it && !micOk) askMic.launch(Manifest.permission.RECORD_AUDIO) else musicOn = it
                }
                Text("Music detection only measures loudness on the phone — nothing is recorded or uploaded. Face reading downloads a 4 MB model the first time.",
                    color = C.muted, fontSize = 11.sp)
            }

            if (mode == Mode.FREE) Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                listOf(
                    "👉 finger gun — try it 😵", "✋ open palm — hello", "👍 / 👎 — thumbs", "✌️ peace", "🤟 love you",
                    "✊ fist bump", "☝️ point up", "Tap — tickle · hold — head pat · shake phone — dizzy",
                ).forEach { Text(it, color = C.muted, fontSize = 13.sp) }
            }
            if (!camOk) Button(onClick = { askCam.launch(Manifest.permission.CAMERA) }, Modifier.padding(top = 16.dp)) { Text("Allow camera for gestures") }
            else if (!gesturesOn) {
                Text("Gestures crashed last time — touch and shake still work.", color = C.amber, fontSize = 13.sp, modifier = Modifier.padding(top = 16.dp))
                Button(onClick = { gesturesOn = true }, Modifier.padding(top = 8.dp)) { Text("Try gestures again") }
            }
        }

        if (camOk && gesturesOn) {
            // Re-create the camera pipeline when face reading is switched on/off.
            androidx.compose.runtime.key(faceOn) {
                LiveCamera(
                    visible = showPreview,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).size(width = 100.dp, height = 136.dp).clip(RoundedCornerShape(16.dp)),
                    create = { c ->
                        val main = ContextCompat.getMainExecutor(c)
                        val consumers = mutableListOf<FrameConsumer>(
                            GestureEngine(c, onGesture = { g -> main.execute { if (currentMode == Mode.FREE) vm.onGesture(g) } },
                                onRaw = { g -> Latest.gesture = g; Latest.at = System.currentTimeMillis() })
                        )
                        if (faceOn) {
                            val model = runBlocking { ModelStore.load(c, ModelStore.Model.FACE) }
                            consumers += FaceEngine(c, model) { sig -> main.execute { vm.onFaceSignal(sig) } }
                        }
                        TakeTurns(consumers)
                    },
                    onError = { error = it },
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = C.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(value, onChange)
    }
}

@Composable
private fun ShakeListener(onShake: () -> Unit) {
    val ctx = LocalContext.current
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        var last = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val (x, y, z) = Triple(e.values[0], e.values[1], e.values[2])
                val g = sqrt(x * x + y * y + z * z)
                val now = System.currentTimeMillis()
                if (g > 25f && now - last > 3000) { last = now; onShake() }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
        onDispose { sm.unregisterListener(listener) }
    }
}
