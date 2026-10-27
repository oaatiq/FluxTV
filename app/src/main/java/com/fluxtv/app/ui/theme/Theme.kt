package com.fluxtv.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
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

/**
 * How many times the normal size TV's text is (Preferences → Text size): 1, 1.25 or 1.5. Text
 * follows it by itself; layouts read it for the widths that have to grow with the text.
 */
val LocalTextScale = staticCompositionLocalOf { 1f }

/**
 * [content] with all its text [scale] times its normal size, for Viewers who see poorly. Everything
 * in sp grows, on top of the TV's own font size setting; sizes in dp stay as they are.
 */
@Composable
fun ScaledText(scale: Float, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, density.fontScale * scale),
        LocalTextScale provides scale,
        content = content
    )
}
