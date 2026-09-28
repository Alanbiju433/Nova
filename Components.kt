package com.aethernova.nova.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun NovaCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.surface).padding(16.dp),
        content = content,
    )
}

@Composable
fun SectionTitle(text: String) =
    Text(text, color = C.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp))

@Composable
fun Ring(label: String, value: Double, target: Double, unit: String, color: Color, size: Dp = 78.dp) {
    val frac = if (target <= 0) 0f else (value / target).toFloat().coerceIn(0f, 1f)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size)) {
            Canvas(Modifier.size(size)) {
                val stroke = Stroke(9.dp.toPx(), cap = StrokeCap.Round)
                drawArc(C.surface2, -90f, 360f, false, style = stroke)
                drawArc(color, -90f, 360f * frac, false, style = stroke)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(fmtNum(value), color = C.text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("/${fmtNum(target)}$unit", color = C.muted, fontSize = 10.sp)
            }
        }
        Text(label, color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

fun fmtNum(v: Double): String = if (v >= 1000) "%.1fk".format(v / 1000) else "%.0f".format(v)

/** Simple line chart for weight or lab-marker trends. */
@Composable
fun LineChart(points: List<Double>, color: Color = C.teal, modifier: Modifier = Modifier, height: Dp = 120.dp) {
    if (points.size < 2) {
        Text("Need at least 2 entries for a trend.", color = C.muted, fontSize = 13.sp)
        return
    }
    val min = points.min()
    val max = points.max()
    val range = (max - min).takeIf { it > 0.0001 } ?: 1.0
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val stepX = size.width / (points.size - 1)
            fun y(v: Double) = (size.height - ((v - min) / range * (size.height - 16f)) - 8f).toFloat()
            val path = Path()
            points.forEachIndexed { i, v -> if (i == 0) path.moveTo(0f, y(v)) else path.lineTo(i * stepX, y(v)) }
            drawPath(path, color, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            points.forEachIndexed { i, v -> drawCircle(color, 4.dp.toPx(), Offset(i * stepX, y(v))) }
            drawRect(C.surface2, Offset(0f, size.height - 1f), Size(size.width, 1f))
        }
        Text("${"%.1f".format(min)} – ${"%.1f".format(max)}", color = C.muted, fontSize = 11.sp)
    }
}
