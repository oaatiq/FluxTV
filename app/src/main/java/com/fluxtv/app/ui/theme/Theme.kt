package com.fluxtv.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

// High-contrast palette for 10-foot viewing (REQ-SEN-012).
val FluxAccent = Color(0xFF2EC4F3)
val FluxBackground = Color(0xFF000000)
val FluxSurface = Color(0xFF121A26)
val FluxSurfaceVariant = Color(0xFF1E2A3A)
val FluxOnSurface = Color(0xFFFFFFFF)
val FluxMuted = Color(0xFFB7C2D0)
val FluxFocus = Color(0xFFFFFFFF)
val FluxWarning = Color(0xFFFFC53D)

@Composable
fun FluxTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = FluxAccent,
        onPrimary = Color(0xFF00131C),
        background = FluxBackground,
        onBackground = FluxOnSurface,
        surface = FluxSurface,
        onSurface = FluxOnSurface,
        surfaceVariant = FluxSurfaceVariant,
        onSurfaceVariant = FluxMuted
    )
    MaterialTheme(colorScheme = colors, content = content)
}
