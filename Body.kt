package com.aethernova.nova.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aethernova.nova.ai.Mood
import com.aethernova.nova.data.Pet
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/** Whole-body actions layered on top of the face mood. */
enum class BodyAction { IDLE, WAVE, DANCE, CHEER, WALK, STRETCH }

/**
 * EMO-style little robot: a screen head (the existing face), neck, torso with a chest light,
 * two arms and two legs. Idles, wanders around when [roam] is on, waves, dances, cheers,
 * sits down when sleepy, wobbles when dizzy and topples over when "shot".
 */
@Composable
fun NovaBody(
    mood: Mood,
    action: BodyAction,
    speaking: Boolean,
    thinking: Boolean,
    skin: Pet.Skin,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp,
    roam: Boolean = false,
    onTap: () -> Unit = {},
    onLongPress: () -> Unit = {},
) {
    val density = LocalDensity.current
    val bodyW = height * 0.72f
    BoxWithConstraints(modifier.fillMaxWidth().height(height)) {
        val maxRoamPx = with(density) { ((maxWidth - bodyW) / 2).toPx() }.coerceAtLeast(0f)
        val x = remember { Animatable(0f) }
        var walking by remember { mutableStateOf(false) }
        // Latest values for the long-running wander loop (otherwise it would see stale ones).
        val curAction by rememberUpdatedState(action)
        val curMood by rememberUpdatedState(mood)
        val curSpeaking by rememberUpdatedState(speaking)

        // Wander: every so often, stroll to a new spot (only when calm and idle).
        LaunchedEffect(roam, maxRoamPx) {
            if (!roam || maxRoamPx < 10f) { x.animateTo(0f, tween(600)); return@LaunchedEffect }
            while (true) {
                delay(Random.nextLong(7000, 15000))
                if (curAction != BodyAction.IDLE || curMood in setOf(Mood.DEAD, Mood.SLEEPY, Mood.FOCUS) || curSpeaking) continue
                val target = (Random.nextFloat() * 2f - 1f) * maxRoamPx
                val ms = (abs(target - x.value) / maxRoamPx * 2200).toInt().coerceIn(500, 2600)
                walking = true
                x.animateTo(target, tween(ms, easing = LinearEasing))
                walking = false
            }
        }

        val act = if (walking && action == BodyAction.IDLE) BodyAction.WALK else action

        val inf = rememberInfiniteTransition(label = "body")
        val slow by inf.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "slow")
        val fast by inf.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(520, easing = LinearEasing)), label = "fast")
        val pulse by inf.animateFloat(0.55f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "pulse")

        // Posture
        val fall by animateFloatAsState(if (mood == Mood.DEAD) 1f else 0f, spring(dampingRatio = 0.45f, stiffness = 120f), label = "fall")
        val sit by animateFloatAsState(if (mood == Mood.SLEEPY) 1f else 0f, tween(600), label = "sit")

        val bob = when (act) {
            BodyAction.DANCE -> -abs(sin(fast)) * 0.06f
            BodyAction.CHEER -> -abs(sin(fast)) * 0.04f
            BodyAction.WALK -> -abs(sin(fast)) * 0.015f
            else -> sin(slow) * 0.01f
        }
        val sway = when {
            act == BodyAction.DANCE -> sin(fast) * 8f
            act == BodyAction.STRETCH -> sin(slow) * 12f
            mood == Mood.DIZZY -> sin(fast * 2) * 10f
            else -> 0f
        }
        val headTilt = when {
            act == BodyAction.DANCE -> -sin(fast) * 10f
            mood == Mood.THINKING || thinking -> 10f
            mood == Mood.LOVE -> -8f
            mood == Mood.CHEEKY -> 6f
            else -> sin(slow) * 3f
        }
        // Arm angles in degrees from hanging straight down; positive = outward/up.
        val (lTarget, rTarget) = when {
            mood == Mood.DEAD -> 70f to 70f
            act == BodyAction.WAVE -> 12f to (122f + sin(fast * 2) * 20f)
            act == BodyAction.STRETCH -> (165f + sin(slow) * 10f) to (165f - sin(slow) * 10f)
            act == BodyAction.DANCE -> (80f + sin(fast) * 55f) to (80f - sin(fast) * 55f)
            act == BodyAction.CHEER || mood == Mood.PROUD -> (138f + sin(fast) * 8f) to (138f - sin(fast) * 8f)
            act == BodyAction.WALK -> (15f + sin(fast) * 22f) to (15f - sin(fast) * 22f)
            mood == Mood.SURPRISED -> 105f to 105f
            mood == Mood.WORRIED -> 120f to 120f
            mood == Mood.DIZZY -> (60f + sin(fast * 2) * 30f) to (60f - sin(fast * 2) * 30f)
            mood == Mood.LOVE -> -35f to -35f
            mood == Mood.FOCUS -> (-25f + sin(fast * 3) * 5f) to (-25f - sin(fast * 3) * 5f)
            mood == Mood.SLEEPY -> 4f to 4f
            speaking -> (18f + sin(fast) * 12f) to (18f - sin(fast) * 6f)
            else -> (12f + sin(slow) * 4f) to (12f - sin(slow) * 4f)
        }
        val lArm by animateFloatAsState(lTarget, spring(stiffness = 300f), label = "l")
        val rArm by animateFloatAsState(rTarget, spring(stiffness = 300f), label = "r")
        val glow = when (mood) {
            Mood.WORRIED -> C.amber; Mood.DEAD -> C.muted; Mood.LOVE -> androidx.compose.ui.graphics.Color(0xFFF687B3)
            Mood.PROUD -> C.green; else -> skin.trim
        }

        Box(
            Modifier
                .align(Alignment.Center)
                .size(bodyW, height)
                .graphicsLayer {
                    // when fallen, shift left so Nova lies across the middle instead of off-screen
                    translationX = x.value - fall * size.height * 0.4f
                    translationY = (bob + sit * 0.05f - fall * 0.05f) * size.height
                    rotationZ = sway + fall * 78f
                    transformOrigin = TransformOrigin(0.5f, 0.95f)
                }
                .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }, onLongPress = { onLongPress() }) },
        ) {
            // Body parts under the head
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height; val cx = w / 2
                // shadow
                drawOval(C.surface2.copy(alpha = 0.6f), Offset(cx - w * 0.28f, h * 0.955f), Size(w * 0.56f, h * 0.035f))
                // legs + feet
                val hipY = h * 0.78f
                val footY = h * (0.93f - sit * 0.05f)
                listOf(-1, 1).forEach { side ->
                    val lift = if (act == BodyAction.WALK) max(0f, sin(fast + if (side == 1) PI.toFloat() else 0f)) * h * 0.035f
                    else if (act == BodyAction.DANCE) max(0f, sin(fast + if (side == 1) PI.toFloat() else 0f)) * h * 0.02f else 0f
                    val lx = cx + side * w * 0.1f
                    drawLine(skin.body, Offset(lx, hipY), Offset(lx, footY - lift), w * 0.07f, StrokeCap.Round)
                    drawRoundRect(skin.trim, Offset(lx - w * 0.07f, footY - lift - h * 0.012f), Size(w * 0.14f, h * 0.04f), CornerRadius(w * 0.05f))
                }
                // neck
                drawRect(skin.body, Offset(cx - w * 0.05f, h * 0.44f), Size(w * 0.1f, h * 0.07f))
                // torso
                val tTop = h * 0.5f; val tH = h * 0.3f; val tW = w * 0.5f
                drawRoundRect(skin.body, Offset(cx - tW / 2, tTop), Size(tW, tH), CornerRadius(tW * 0.35f))
                drawRoundRect(skin.trim.copy(alpha = 0.35f), Offset(cx - tW / 2, tTop), Size(tW, tH), CornerRadius(tW * 0.35f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(w * 0.012f))
                // chest light
                drawCircle(glow.copy(alpha = 0.25f * pulse), w * 0.075f, Offset(cx, tTop + tH * 0.38f))
                drawCircle(glow.copy(alpha = pulse), w * 0.04f, Offset(cx, tTop + tH * 0.38f))
            }
            // Head = screen with the existing animated face
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .size(bodyW * 0.86f, height * 0.46f)
                    .graphicsLayer { rotationZ = headTilt; transformOrigin = TransformOrigin(0.5f, 1f) }
                    .background(skin.body, RoundedCornerShape(26.dp))
                    .border(2.dp, skin.trim.copy(alpha = 0.5f), RoundedCornerShape(26.dp))
                    .padding(7.dp)
                    .background(C.bg, RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center,
            ) {
                NovaFace(mood, speaking, thinking, Modifier.offset(y = 2.dp), height = height * 0.38f)
            }
            // Arms are drawn last so a wave or cheer shows in front of the head, like EMO's little arms.
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height; val cx = w / 2
                val tTop = h * 0.5f; val tH = h * 0.3f; val tW = w * 0.5f
                // arms
                val shoulderY = tTop + tH * 0.18f
                val armLen = h * 0.24f
                listOf(-1 to lArm, 1 to rArm).forEach { (side, deg) ->
                    val sx = cx + side * tW * 0.46f
                    val a = Math.toRadians(deg.toDouble())
                    val hx = sx + side * (armLen * sin(a)).toFloat()
                    val hy = shoulderY + (armLen * cos(a)).toFloat()
                    drawLine(skin.body, Offset(sx, shoulderY), Offset(hx, hy), w * 0.065f, StrokeCap.Round)
                    drawCircle(skin.trim, w * 0.048f, Offset(hx, hy))
                }
            }
        }
    }
}
