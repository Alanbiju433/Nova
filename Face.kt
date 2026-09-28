package com.aethernova.nova.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aethernova.nova.ai.Mood
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** EMO-style face: two glowing eyes that blink, look around and change shape with mood. */
@Composable
fun NovaFace(mood: Mood, speaking: Boolean, thinking: Boolean, modifier: Modifier = Modifier, height: Dp = 170.dp) {
    var blink by remember { mutableFloatStateOf(1f) }
    var lookX by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(2500, 5000))
            blink = 0.1f; delay(110); blink = 1f
            if (Random.nextFloat() < 0.4f) lookX = Random.nextFloat() * 2f - 1f
        }
    }
    val openness by animateFloatAsState(blink, tween(90), label = "blink")
    val look by animateFloatAsState(if (thinking || mood == Mood.THINKING) 0.7f else lookX, tween(400), label = "look")
    // "dead" droops the whole face; surprised pops the eyes bigger
    val droop by animateFloatAsState(if (mood == Mood.DEAD) 1f else 0f, spring(dampingRatio = 0.4f), label = "droop")
    val pop by animateFloatAsState(if (mood == Mood.SURPRISED) 1.25f else 1f, spring(dampingRatio = 0.35f), label = "pop")
    val inf = rememberInfiniteTransition(label = "inf")
    val mouth by inf.animateFloat(0.2f, 1f, infiniteRepeatable(tween(160), RepeatMode.Reverse), label = "mouth")
    val pulse by inf.animateFloat(0.85f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
    val spin by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "spin")

    val eyeColor = when (mood) {
        Mood.WORRIED -> C.amber
        Mood.PROUD -> C.green
        Mood.DEAD -> C.muted
        Mood.LOVE -> Color(0xFFF687B3)
        else -> C.teal
    }

    Canvas(modifier.fillMaxWidth().height(height)) {
        val cx = size.width / 2
        val cy = size.height * 0.45f + droop * size.height * 0.08f
        val eyeW = size.height * 0.32f * pop
        val eyeH = size.height * 0.42f * pop
        val gap = size.height * 0.28f
        val shift = look * eyeW * 0.25f
        val glow = eyeColor.copy(alpha = 0.18f * (if (thinking) pulse else 1f))

        rotate(droop * 8f, Offset(cx, cy)) {
            listOf(-1, 1).forEach { side ->
                val ex = cx + side * (gap / 2 + eyeW / 2) + shift
                val topLeft = Offset(ex - eyeW / 2, cy - eyeH / 2)
                if (mood != Mood.DEAD) drawRoundRect(glow, topLeft - Offset(10f, 10f), Size(eyeW + 20f, eyeH + 20f), CornerRadius(eyeW * 0.45f))
                when {
                    mood == Mood.HAPPY || mood == Mood.PROUD -> arcEye(ex, cy, eyeW, eyeColor)
                    mood == Mood.CHEEKY && side == 1 -> arcEye(ex, cy, eyeW, eyeColor)
                    mood == Mood.DEAD -> xEye(ex, cy, eyeW * 0.8f, eyeColor)
                    mood == Mood.DIZZY -> spiralEye(ex, cy, eyeW * 0.5f, if (side == -1) spin else -spin, eyeColor)
                    mood == Mood.LOVE -> heartEye(ex, cy, eyeW * (0.9f + 0.1f * pulse), eyeColor)
                    mood == Mood.SURPRISED -> {
                        drawCircle(eyeColor, eyeW * 0.5f, Offset(ex, cy))
                        drawCircle(C.bg, eyeW * 0.14f, Offset(ex + eyeW * 0.12f, cy - eyeW * 0.12f))
                    }
                    mood == Mood.SLEEPY -> drawRoundRect(eyeColor, Offset(topLeft.x, cy), Size(eyeW, eyeH * 0.22f * openness), CornerRadius(eyeW * 0.3f))
                    else -> {
                        val h = eyeH * openness * (when (mood) { Mood.WORRIED -> 0.8f; Mood.FOCUS -> 0.5f; else -> 1f })
                        drawRoundRect(eyeColor, Offset(topLeft.x, cy - h / 2), Size(eyeW, h.coerceAtLeast(4f)), CornerRadius(eyeW * 0.35f))
                        if (mood == Mood.WORRIED) {
                            val p = Path().apply {
                                moveTo(ex - eyeW / 2 - 4, cy - h / 2 - 4)
                                lineTo(ex + eyeW / 2 + 4, cy - h / 2 - 4)
                                lineTo(if (side == -1) ex + eyeW / 2 + 4 else ex - eyeW / 2 - 4, cy - h / 2 + eyeH * 0.28f)
                                close()
                            }
                            drawPath(p, C.bg)
                        }
                    }
                }
            }
            val my = cy + size.height * 0.21f + size.height * 0.14f
            val mw = size.height * 0.32f
            when {
                speaking -> {
                    val mh = size.height * 0.09f * mouth
                    drawRoundRect(eyeColor, Offset(cx - mw * 0.35f, my - mh / 2), Size(mw * 0.7f, mh.coerceAtLeast(4f)), CornerRadius(12f))
                }
                mood == Mood.SURPRISED -> drawCircle(eyeColor, mw * 0.14f, Offset(cx, my), style = Stroke(7f))
                mood == Mood.DEAD -> drawLine(eyeColor, Offset(cx - mw * 0.3f, my), Offset(cx + mw * 0.3f, my + 6f), 7f, StrokeCap.Round)
                mood == Mood.DIZZY -> {
                    val p = Path().apply {
                        moveTo(cx - mw * 0.35f, my)
                        for (i in 1..4) quadraticTo(cx - mw * 0.35f + (i - 0.5f) * mw * 0.175f, my + if (i % 2 == 0) 10f else -10f, cx - mw * 0.35f + i * mw * 0.175f, my)
                    }
                    drawPath(p, eyeColor, style = Stroke(6f, cap = StrokeCap.Round))
                }
                mood == Mood.HAPPY || mood == Mood.PROUD || mood == Mood.CHEEKY || mood == Mood.LOVE ->
                    drawArc(eyeColor, 20f, 140f, false, Offset(cx - mw * 0.35f, my - mw * 0.35f), Size(mw * 0.7f, mw * 0.5f), style = Stroke(8f, cap = StrokeCap.Round))
            }
        }
    }
}

private fun DrawScope.arcEye(ex: Float, cy: Float, w: Float, color: Color) {
    drawArc(color, 200f, 140f, false, Offset(ex - w / 2, cy - w * 0.35f), Size(w, w * 0.8f), style = Stroke(w * 0.22f, cap = StrokeCap.Round))
}

private fun DrawScope.xEye(ex: Float, cy: Float, w: Float, color: Color) {
    val h = w / 2
    drawLine(color, Offset(ex - h, cy - h), Offset(ex + h, cy + h), w * 0.2f, StrokeCap.Round)
    drawLine(color, Offset(ex + h, cy - h), Offset(ex - h, cy + h), w * 0.2f, StrokeCap.Round)
}

private fun DrawScope.spiralEye(ex: Float, cy: Float, r: Float, angle: Float, color: Color) {
    val p = Path()
    val turns = 3
    val steps = 60
    for (i in 0..steps) {
        val t = i / steps.toFloat()
        val a = Math.toRadians((angle + t * 360f * turns).toDouble())
        val rr = r * t
        val x = ex + (rr * cos(a)).toFloat()
        val y = cy + (rr * sin(a)).toFloat()
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    drawPath(p, color, style = Stroke(r * 0.16f, cap = StrokeCap.Round))
}

private fun DrawScope.heartEye(ex: Float, cy: Float, w: Float, color: Color) {
    val s = w / 2
    val p = Path().apply {
        moveTo(ex, cy + s * 0.8f)
        cubicTo(ex - s * 1.3f, cy - s * 0.1f, ex - s * 0.6f, cy - s * 1.0f, ex, cy - s * 0.35f)
        cubicTo(ex + s * 0.6f, cy - s * 1.0f, ex + s * 1.3f, cy - s * 0.1f, ex, cy + s * 0.8f)
        close()
    }
    drawPath(p, color)
}
