package com.fluxtv.app.ui.startup

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fluxtv.app.AppName
import com.fluxtv.app.ui.easymode.WaitingDots
import com.fluxtv.app.ui.theme.FluxAccent
import com.fluxtv.app.ui.theme.FluxOnSurface

/**
 * What the Viewer sees for the few seconds FluxTV installs its own update while on screen, made to
 * be understood without reading, like the reconnecting screen: an arrow coming down into a tray (a
 * new version arriving), the same slowly moving dots that always mean "wait, FluxTV is on it", and
 * a few words for those who read. Nothing to press: FluxTV closes and comes back by itself on the
 * new version, on the same channel.
 */
@Composable
fun UpdatingScreen() {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(AppName, style = TextStyle(color = FluxAccent, fontSize = 56.sp, fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(36.dp))
            DownloadArrow(Modifier.size(72.dp), FluxOnSurface)
            Spacer(Modifier.height(32.dp))
            WaitingDots(20.dp)
            Spacer(Modifier.height(24.dp))
            BasicText("Updating ${AppName}…", style = TextStyle(color = FluxOnSurface, fontSize = 28.sp))
        }
    }
}

/** An arrow pointing down into an open tray. */
@Composable
private fun DownloadArrow(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val w = size.width
        val stroke = w * 0.09f
        fun at(x: Float, y: Float) = Offset(w * x, w * y)
        // The arrow.
        drawLine(color, at(0.5f, 0.08f), at(0.5f, 0.62f), stroke, StrokeCap.Round)
        drawLine(color, at(0.28f, 0.42f), at(0.5f, 0.64f), stroke, StrokeCap.Round)
        drawLine(color, at(0.72f, 0.42f), at(0.5f, 0.64f), stroke, StrokeCap.Round)
        // The tray.
        drawLine(color, at(0.12f, 0.66f), at(0.12f, 0.9f), stroke, StrokeCap.Round)
        drawLine(color, at(0.12f, 0.9f), at(0.88f, 0.9f), stroke, StrokeCap.Round)
        drawLine(color, at(0.88f, 0.9f), at(0.88f, 0.66f), stroke, StrokeCap.Round)
    }
}
