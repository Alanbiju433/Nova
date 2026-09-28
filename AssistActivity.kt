package com.aethernova.nova.assist

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.aethernova.nova.NovaApp
import com.aethernova.nova.ai.Mood
import com.aethernova.nova.ai.Vitals
import com.aethernova.nova.data.Pet
import com.aethernova.nova.phone.Act
import com.aethernova.nova.phone.LocalCommands
import com.aethernova.nova.phone.PhoneActions
import com.aethernova.nova.ui.BodyAction
import com.aethernova.nova.ui.C
import com.aethernova.nova.ui.NovaBody
import com.aethernova.nova.ui.NovaTheme
import com.aethernova.nova.voice.Speaker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Siri-style pop-up: opens from holding the power button (when Nova is your default assistant),
 * from the wake word, or from the lock screen. Listens once, answers, acts, then closes.
 * On a locked phone it only does harmless things (timer, alarm, torch, volume) until you unlock —
 * so nobody can ask it about your health or money from your lock screen.
 */
class AssistActivity : ComponentActivity() {
    private val app get() = application as NovaApp
    private lateinit var speaker: Speaker
    private var recognizer: SpeechRecognizer? = null

    private val status = mutableStateOf("Listening…")
    private val heard = mutableStateOf("")
    private val reply = mutableStateOf("")
    private val mood = mutableStateOf(Mood.SURPRISED)
    private val action = mutableStateOf(BodyAction.WAVE)
    private val listening = mutableStateOf(false)
    private var closeJob: kotlinx.coroutines.Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        open = true
        WakeService.pause(this)
        speaker = Speaker(this).also { it.setLanguage(app.settings.current.voiceLang) }

        setContent {
            NovaTheme {
                val speaking by speaker.speaking.collectAsState()
                val s = app.settings.current
                Box(Modifier.fillMaxSize().background(Color(0xCC000000)).clickable { finish() }) {
                    Column(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)
                            .background(C.bg, RoundedCornerShape(28.dp)).clickable(enabled = false) {}.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(Modifier.fillMaxWidth()) {
                            IconButton(onClick = { finish() }, Modifier.align(Alignment.TopEnd)) { Icon(Icons.Default.Close, "Close", tint = C.muted) }
                        }
                        NovaBody(mood.value, action.value, speaking, status.value.startsWith("Thinking"),
                            Pet.skin(s.skin, Pet.level(0)), height = 170.dp)
                        Text(status.value, color = C.teal, fontSize = 14.sp)
                        if (heard.value.isNotBlank()) Text("“${heard.value}”", color = C.muted, fontSize = 15.sp, textAlign = TextAlign.Center)
                        if (reply.value.isNotBlank()) Text(reply.value, color = C.text, fontSize = 17.sp, textAlign = TextAlign.Center)
                        FilledIconButton(onClick = { listen() }, enabled = !listening.value,
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = C.teal)) { Icon(Icons.Default.Mic, "Talk", tint = C.bg) }
                    }
                }
            }
        }
        listen()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        listen()
    }

    private val locked get() = getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun listen() {
        closeJob?.cancel()
        speaker.stop()
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { status.value = "No speech recogniser on this phone"; return }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) { listening.value = true; status.value = "Listening…"; mood.value = Mood.NEUTRAL; action.value = BodyAction.IDLE }
                override fun onResults(b: Bundle?) {
                    listening.value = false
                    val text = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (text.isBlank()) { status.value = "Didn't catch that — tap the mic"; scheduleClose(6000) } else handle(text)
                }
                override fun onError(error: Int) {
                    listening.value = false
                    status.value = if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) "Allow the microphone for Nova in Settings" else "Didn't catch that — tap the mic"
                    mood.value = Mood.SLEEPY
                    scheduleClose(6000)
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() { status.value = "Thinking…" }
                override fun onPartialResults(b: Bundle?) {}
                override fun onEvent(t: Int, b: Bundle?) {}
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, app.settings.current.voiceLang))
        }
    }

    /** Harmless on a locked phone: nothing personal is read or opened. */
    private fun safeWhenLocked(a: Act) = a is Act.Timer || a is Act.Alarm || a is Act.Torch || a is Act.Volume

    private fun handle(text: String) {
        heard.value = text
        val local = LocalCommands.parse(text)
        if (locked && (local == null || !safeWhenLocked(local))) {
            status.value = "Unlock to continue"
            getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { answer(text) }
                override fun onDismissCancelled() { status.value = "Locked — I only do timers, alarms, torch and volume here"; scheduleClose(4000) }
                override fun onDismissError() { status.value = "Couldn't unlock"; scheduleClose(4000) }
            })
            return
        }
        answer(text)
    }

    private fun answer(text: String) {
        status.value = "Thinking…"; mood.value = Mood.THINKING
        lifecycleScope.launch {
            val r = app.brain.chat(text, Vitals()) { act -> PhoneActions.run(this@AssistActivity, act) }
            reply.value = if (r.pending != null) r.text + " Open Nova to check and save it." else r.text
            mood.value = r.mood; action.value = if (r.mood == Mood.PROUD || r.mood == Mood.HAPPY) BodyAction.CHEER else BodyAction.IDLE
            status.value = "Tap the mic to say more"
            if (app.settings.current.speakReplies) speaker.speak(r.text)
            // wait for speech to finish, then close
            delay(600)
            while (speaker.speaking.value) delay(200)
            scheduleClose(5000)
        }
    }

    private fun scheduleClose(ms: Long) {
        closeJob?.cancel()
        closeJob = lifecycleScope.launch { delay(ms); if (!listening.value && !speaker.speaking.value) finish() }
    }

    override fun onDestroy() {
        open = false
        recognizer?.destroy()
        speaker.shutdown()
        WakeService.resume(this)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FROM_WAKE = "from_wake"
        @Volatile var open = false
    }
}
