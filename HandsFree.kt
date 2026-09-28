package com.aethernova.nova.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Finds "hey nova" (or the buddy's name) in a phrase and returns what came after it. */
object WakeWord {
    /** null = no wake word; "" = wake word only; otherwise the command after it. */
    fun extract(text: String, name: String = "nova"): String? {
        val t = text.lowercase().replace(Regex("[^a-z0-9 ']"), " ").replace(Regex("\\s+"), " ").trim()
        val n = name.lowercase().trim().ifBlank { "nova" }
        // Speech engines often hear "nova" as these
        val variants = listOf(n, "nova", "noah", "nora", "no va").distinct()
        for (v in variants) {
            val m = Regex("""(?:^|\b)(?:hey |hi |ok |okay |hello )?$v\b""").find(t) ?: continue
            return t.substring(m.range.last + 1).trim().removePrefix(",").trim()
        }
        return null
    }
}

/**
 * "Hey Nova" while the app is open (desk mode). Uses Android's speech recogniser in a loop,
 * preferring on-device recognition. It pauses while Nova is talking so it doesn't hear itself.
 * Android doesn't allow an always-on wake word for normal apps with the screen off, so this only
 * runs while Nova is on screen.
 */
class HandsFree(
    private val context: Context,
    private val language: () -> String,
    private val buddyName: () -> String,
    private val isSpeaking: () -> Boolean,
    private val onWake: () -> Unit,
    private val onCommand: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var awaitingCommand = false
    private var awaitingUntil = 0L

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        if (running || !available) return
        running = true
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply { setRecognitionListener(listener) }
        listen()
    }

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        runCatching { recognizer?.cancel(); recognizer?.destroy() }
        recognizer = null
    }

    private fun listen(delayMs: Long = 250) {
        if (!running) return
        main.postDelayed({
            if (!running) return@postDelayed
            if (isSpeaking()) { listen(400); return@postDelayed }
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language())
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            runCatching { recognizer?.startListening(i) }.onFailure { listen(1500) }
        }, delayMs)
    }

    private fun handle(results: List<String>) {
        val now = System.currentTimeMillis()
        if (awaitingCommand && now < awaitingUntil) {
            val text = results.firstOrNull()?.trim().orEmpty()
            if (text.isNotEmpty()) { awaitingCommand = false; onCommand(text); return }
        }
        for (r in results) {
            val cmd = WakeWord.extract(r, buddyName()) ?: continue
            if (cmd.isEmpty()) { awaitingCommand = true; awaitingUntil = now + 8000; onWake() }
            else { awaitingCommand = false; onCommand(cmd) }
            return
        }
    }

    private val listener = object : RecognitionListener {
        override fun onResults(b: Bundle?) {
            handle(b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty())
            listen()
        }
        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> stop()
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> listen(1200)
                else -> listen(300)   // no match / timeout are normal in a listening loop
            }
        }
        override fun onReadyForSpeech(p: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(v: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(b: Bundle?) {}
        override fun onEvent(t: Int, b: Bundle?) {}
    }
}
