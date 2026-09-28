package com.aethernova.nova.vision

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.sqrt

/**
 * Listens to the mic for a steady beat (music) and calls [onMusic] when it starts.
 * Only loudness is analysed on the phone — no audio is recorded, stored or uploaded.
 */
class MusicListener(private val onMusic: () -> Unit) {
    private val rate = 16_000
    private val frameMs = 50
    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission") // caller checks RECORD_AUDIO first
    fun start() {
        if (running) return
        running = true
        thread = Thread {
            val frame = rate * frameMs / 1000
            val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = runCatching {
                AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, frame * 4))
            }.getOrNull() ?: return@Thread
            if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return@Thread }
            val buf = ShortArray(frame)
            val window = ArrayDeque<Double>()
            var lastCheck = 0L
            var lastFired = 0L
            try {
                rec.startRecording()
                while (running) {
                    val n = rec.read(buf, 0, frame)
                    if (n <= 0) continue
                    var sum = 0.0
                    for (i in 0 until n) { val v = buf[i] / 32768.0; sum += v * v }
                    window.addLast(sqrt(sum / n))
                    while (window.size > 8000 / frameMs) window.removeFirst()
                    val now = System.currentTimeMillis()
                    if (now - lastCheck > 1000 && window.size >= 4000 / frameMs) {
                        lastCheck = now
                        if (now - lastFired > 20_000 && Beat.isMusic(window.toList(), frameMs)) {
                            lastFired = now
                            onMusic()
                        }
                    }
                }
            } finally {
                runCatching { rec.stop() }
                rec.release()
            }
        }.apply { isDaemon = true; start() }
    }

    fun stop() { running = false; thread = null }
}
