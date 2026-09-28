package com.aethernova.nova.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/** Text-to-speech wrapper. Tries Malayalam when chosen, falls back to British English. */
class Speaker(context: Context) {
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking
    private var ready = false
    private var pendingLang = "en-GB"

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) applyLang(pendingLang)
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { _speaking.value = true }
            override fun onDone(id: String?) { _speaking.value = false }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { _speaking.value = false }
        })
    }

    fun setLanguage(tag: String) {
        pendingLang = tag
        if (ready) applyLang(tag)
    }

    /** Returns false if the requested language isn't installed on the phone. */
    private fun applyLang(tag: String): Boolean {
        val r = tts.setLanguage(Locale.forLanguageTag(tag))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.UK)
            return false
        }
        tts.setSpeechRate(1.05f)
        tts.setPitch(1.1f)
        return true
    }

    fun speak(text: String) {
        if (!ready || text.isBlank()) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nova-${System.nanoTime()}")
    }

    fun stop() { tts.stop(); _speaking.value = false }
    fun shutdown() = tts.shutdown()
}
