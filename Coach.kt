package com.aethernova.nova.vision

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/** A body landmark in normalised image coordinates (0..1), y grows downwards. */
data class P(val x: Float, val y: Float, val vis: Float = 1f)

/**
 * Exercise maths for the camera coach. Pure Kotlin so it's unit-tested without a phone.
 * Landmark indices follow MediaPipe Pose (33 points).
 */
object Coach {
    const val NOSE = 0; const val L_EAR = 7; const val R_EAR = 8
    const val L_SHOULDER = 11; const val R_SHOULDER = 12; const val L_ELBOW = 13; const val R_ELBOW = 14
    const val L_WRIST = 15; const val R_WRIST = 16; const val L_HIP = 23; const val R_HIP = 24
    const val L_KNEE = 25; const val R_KNEE = 26; const val L_ANKLE = 27; const val R_ANKLE = 28

    /** Angle ABC at B, in degrees. */
    fun angle(a: P, b: P, c: P): Double {
        val v1x = a.x - b.x; val v1y = a.y - b.y
        val v2x = c.x - b.x; val v2y = c.y - b.y
        val n = hypot(v1x, v1y) * hypot(v2x, v2y)
        if (n < 1e-6f) return 180.0
        val cos = ((v1x * v2x + v1y * v2y) / n).toDouble().coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cos))
    }

    /** Uses whichever side of the body the camera sees better. */
    private fun bestSide(lm: List<P>, left: IntArray, right: IntArray): IntArray {
        val l = left.sumOf { lm[it].vis.toDouble() }; val r = right.sumOf { lm[it].vis.toDouble() }
        return if (l >= r) left else right
    }

    fun kneeAngle(lm: List<P>): Double? {
        val s = bestSide(lm, intArrayOf(L_HIP, L_KNEE, L_ANKLE), intArrayOf(R_HIP, R_KNEE, R_ANKLE))
        if (s.any { lm[it].vis < 0.5f }) return null
        return angle(lm[s[0]], lm[s[1]], lm[s[2]])
    }

    fun elbowAngle(lm: List<P>): Double? {
        val s = bestSide(lm, intArrayOf(L_SHOULDER, L_ELBOW, L_WRIST), intArrayOf(R_SHOULDER, R_ELBOW, R_WRIST))
        if (s.any { lm[it].vis < 0.5f }) return null
        return angle(lm[s[0]], lm[s[1]], lm[s[2]])
    }

    /** Shoulder–hip–ankle: ~180 is a straight plank; lower means hips sagging or piking. */
    fun bodyLine(lm: List<P>): Double? {
        val s = bestSide(lm, intArrayOf(L_SHOULDER, L_HIP, L_ANKLE), intArrayOf(R_SHOULDER, R_HIP, R_ANKLE))
        if (s.any { lm[it].vis < 0.5f }) return null
        return angle(lm[s[0]], lm[s[1]], lm[s[2]])
    }

    /** Torso lean from vertical, degrees (0 = upright). */
    fun torsoLean(lm: List<P>): Double {
        val sx = (lm[L_SHOULDER].x + lm[R_SHOULDER].x) / 2; val sy = (lm[L_SHOULDER].y + lm[R_SHOULDER].y) / 2
        val hx = (lm[L_HIP].x + lm[R_HIP].x) / 2; val hy = (lm[L_HIP].y + lm[R_HIP].y) / 2
        return abs(Math.toDegrees(atan2((sx - hx).toDouble(), (hy - sy).toDouble())))
    }

    /** Head height above shoulders relative to shoulder width — drops when you slouch towards the screen. */
    fun postureRatio(lm: List<P>): Double? {
        val sw = hypot(lm[L_SHOULDER].x - lm[R_SHOULDER].x, lm[L_SHOULDER].y - lm[R_SHOULDER].y)
        if (sw < 0.05f || lm[L_SHOULDER].vis < 0.5f || lm[R_SHOULDER].vis < 0.5f) return null
        val earY = (lm[L_EAR].y + lm[R_EAR].y) / 2
        val shY = (lm[L_SHOULDER].y + lm[R_SHOULDER].y) / 2
        return ((shY - earY) / sw).toDouble()
    }
}

enum class Exercise(val label: String) { SQUAT("Squats"), PUSHUP("Push-ups"), POSTURE("Posture guard") }

/**
 * Counts reps with hysteresis: a rep is going below [down] then back above [up].
 * A dip that never reaches [down] but comes back up is reported as a partial rep (with a cue).
 */
class RepCounter(private val down: Double, private val up: Double, private val partialStart: Double) {
    var count = 0; private set
    private var inRep = false
    private var reachedBottom = false
    var minThisRep = 180.0; private set

    sealed interface Event {
        data class Rep(val count: Int, val depth: Double) : Event
        data class Partial(val depth: Double) : Event
    }

    fun feed(angle: Double): Event? {
        if (!inRep && angle < partialStart) { inRep = true; reachedBottom = false; minThisRep = angle }
        if (inRep) {
            minThisRep = minOf(minThisRep, angle)
            if (angle < down) reachedBottom = true
            if (angle > up) {
                inRep = false
                return if (reachedBottom) { count++; Event.Rep(count, minThisRep) } else Event.Partial(minThisRep)
            }
        }
        return null
    }

    companion object {
        fun forExercise(e: Exercise) = when (e) {
            Exercise.SQUAT -> RepCounter(down = 100.0, up = 160.0, partialStart = 140.0)
            else -> RepCounter(down = 95.0, up = 155.0, partialStart = 135.0)
        }
    }
}

/** Warns when you slouch below 80% of your calibrated upright posture for [holdMs]. */
class PostureMonitor(private val baseline: Double, private val holdMs: Long = 8000, private val cooldownMs: Long = 60_000) {
    private var badSince: Long? = null
    private var lastAlert = -cooldownMs

    fun isSlouching(ratio: Double) = ratio < baseline * 0.8

    fun feed(ratio: Double, nowMs: Long): Boolean {
        if (!isSlouching(ratio)) { badSince = null; return false }
        val since = badSince ?: nowMs.also { badSince = it }
        if (nowMs - since >= holdMs && nowMs - lastAlert >= cooldownMs) {
            lastAlert = nowMs; badSince = null
            return true
        }
        return false
    }
}

/**
 * Music detector for "dance to music": looks for a steady beat in mic loudness.
 * Pure function over a window of RMS values sampled every [frameMs].
 */
object Beat {
    fun peaks(rms: List<Double>, frameMs: Int): List<Int> {
        val out = mutableListOf<Int>()
        for (i in 1 until rms.size - 1) {
            val from = maxOf(0, i - 20); val local = rms.subList(from, i).average().takeIf { !it.isNaN() } ?: 0.0
            if (rms[i] > rms[i - 1] && rms[i] >= rms[i + 1] && rms[i] > local * 1.35 && rms[i] > 0.02) {
                if (out.isEmpty() || (i - out.last()) * frameMs >= 250) out += i
            }
        }
        return out
    }

    /** True when there are enough evenly spaced peaks (60–200 BPM) to call it music. */
    fun isMusic(rms: List<Double>, frameMs: Int): Boolean {
        val p = peaks(rms, frameMs)
        if (p.size < 6) return false
        val gaps = p.zipWithNext { a, b -> (b - a) * frameMs.toDouble() }
        val mean = gaps.average()
        if (mean < 300 || mean > 1000) return false
        val sd = sqrt(gaps.sumOf { (it - mean) * (it - mean) } / gaps.size)
        return sd / mean < 0.25
    }
}
