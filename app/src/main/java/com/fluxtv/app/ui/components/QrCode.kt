package com.fluxtv.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import kotlin.math.floor

/** The empty border a QR code needs around it to be found, in modules (the standard's minimum). */
private const val QUIET_ZONE = 4

/**
 * [text] as a QR code: black on white whatever the theme, since phone cameras look for dark modules
 * on a light background. Each module is a whole number of pixels, so its edges stay sharp.
 */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) { runCatching { Encoder.encode(text, ErrorCorrectionLevel.M).matrix }.getOrNull() }
    Canvas(modifier.aspectRatio(1f)) {
        drawRect(Color.White)
        val m = matrix ?: return@Canvas
        val cells = m.width + 2 * QUIET_ZONE
        val cell = floor(size.minDimension / cells).coerceAtLeast(1f)
        val origin = (size.minDimension - cell * m.width) / 2
        for (y in 0 until m.height) {
            for (x in 0 until m.width) {
                if (m.get(x, y).toInt() == 1) {
                    drawRect(Color.Black, Offset(origin + x * cell, origin + y * cell), Size(cell, cell))
                }
            }
        }
    }
}
