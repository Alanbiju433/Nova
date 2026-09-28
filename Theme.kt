package com.aethernova.nova.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object C {
    val bg = Color(0xFF0B0F1A)
    val surface = Color(0xFF141B2D)
    val surface2 = Color(0xFF1C2540)
    val teal = Color(0xFF4FD1C5)
    val amber = Color(0xFFF6AD55)
    val red = Color(0xFFFC8181)
    val green = Color(0xFF68D391)
    val text = Color(0xFFE2E8F0)
    val muted = Color(0xFF94A3B8)
}

@Composable
fun NovaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.teal, onPrimary = C.bg,
            secondary = C.amber, background = C.bg, onBackground = C.text,
            surface = C.surface, onSurface = C.text, surfaceVariant = C.surface2,
            onSurfaceVariant = C.muted, error = C.red,
        ),
        content = content,
    )
}
