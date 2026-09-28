package com.aethernova.nova.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Listens in a loop and hands every phrase to [onText] — no wake word. Used by the cooking guide
 * ("next", "repeat", "timer 5 minutes") while the screen is on. Pauses while Nova is talking.
 */
class ContinuousListener(
    private val context: Context,
    private val language: String,
    private val isSpeaking: () -> Boolean,
    private val onText: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var running = false

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
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            runCatching { recognizer?.startListening(i) }.onFailure { listen(1500) }
        }, delayMs)
    }

    private val listener = object : RecognitionListener {
        override fun onResults(b: Bundle?) {
            b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(onText)
            listen()
        }
        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> stop()
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> listen(1200)
                else -> listen(300)
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
