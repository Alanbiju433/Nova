package com.aethernova.nova.play

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.aethernova.nova.vision.FrameConsumer
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import kotlin.math.hypot

enum class PlayGesture(val label: String) {
    FINGER_GUN("Finger gun"), WAVE("Open palm"), THUMB_UP("Thumbs up"), THUMB_DOWN("Thumbs down"),
    PEACE("Peace"), LOVE("I love you"), FIST("Fist"), POINT_UP("Pointing up"),
}

/**
 * Runs Google's MediaPipe gesture model fully on the phone (no internet, no AI quota).
 * The built-in model knows 7 gestures; the finger gun is detected from the 21 hand landmarks.
 * [onGesture] fires for held gestures (with a cooldown); [onRaw] gets every frame's reading, for games.
 */
class GestureEngine(
    context: Context,
    private val onGesture: (PlayGesture) -> Unit,
    private val onRaw: (PlayGesture?) -> Unit = {},
) : FrameConsumer {

    private val recognizer: GestureRecognizer = GestureRecognizer.createFromOptions(
        context,
        GestureRecognizer.GestureRecognizerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("gesture_recognizer.task").build())
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, _ -> runCatching { handle(result) } }
            .setErrorListener { }
            .build()
    )

    private var candidate: PlayGesture? = null
    private var streak = 0
    private var lastFired = 0L

    override fun process(frame: Bitmap, timestampMs: Long) {
        recognizer.recognizeAsync(BitmapImageBuilder(frame).build(), timestampMs)
    }

    private fun classify(result: GestureRecognizerResult): PlayGesture? {
        val hand = result.landmarks().firstOrNull() ?: return null
        if (isFingerGun(hand)) return PlayGesture.FINGER_GUN
        val top = result.gestures().firstOrNull()?.firstOrNull() ?: return null
        if (top.score() < 0.6f) return null
        return when (top.categoryName()) {
            "Open_Palm" -> PlayGesture.WAVE
            "Thumb_Up" -> PlayGesture.THUMB_UP
            "Thumb_Down" -> PlayGesture.THUMB_DOWN
            "Victory" -> PlayGesture.PEACE
            "ILoveYou" -> PlayGesture.LOVE
            "Closed_Fist" -> PlayGesture.FIST
            "Pointing_Up" -> PlayGesture.POINT_UP
            else -> null
        }
    }

    private fun handle(result: GestureRecognizerResult) {
        val g = classify(result)
        onRaw(g)
        if (g == null) { candidate = null; streak = 0; return }
        if (g == candidate) streak++ else { candidate = g; streak = 1 }
        val now = SystemClock.uptimeMillis()
        val needed = if (g == PlayGesture.FINGER_GUN) 3 else 4
        if (streak >= needed && now - lastFired > 2500) {
            lastFired = now
            streak = 0
            onGesture(g)
        }
    }

    override fun close() = recognizer.close()

    companion object {
        private fun d(a: NormalizedLandmark, b: NormalizedLandmark) = hypot(a.x() - b.x(), a.y() - b.y())

        /** Index straight, middle/ring/pinky curled, thumb sticking out = 👉 finger gun. */
        fun isFingerGun(h: List<NormalizedLandmark>): Boolean {
            if (h.size < 21) return false
            val wrist = h[0]
            val size = d(wrist, h[9]).takeIf { it > 1e-4f } ?: return false
            val indexStraight = d(wrist, h[8]) > d(wrist, h[6]) * 1.15f && d(h[5], h[8]) > size * 0.6f
            fun curled(tip: Int, pip: Int) = d(wrist, h[tip]) < d(wrist, h[pip]) * 1.05f
            val othersCurled = curled(12, 10) && curled(16, 14) && curled(20, 18)
            val thumbOut = d(h[4], h[5]) > size * 0.45f
            return indexStraight && othersCurled && thumbOut
        }
    }
}
