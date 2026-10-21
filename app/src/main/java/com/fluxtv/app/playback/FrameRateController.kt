package com.fluxtv.app.playback

import android.app.Activity
import android.os.Build
import android.view.Display
import kotlin.math.abs

/**
 * Matches the display refresh rate to the source frame rate when the platform allows it (§14).
 *
 * Only switches when a genuinely better matching mode exists at the *same resolution*, so resolution
 * changes alone never trigger a mode switch (REQ-FPS-004) and unnecessary switches are avoided
 * (REQ-FPS-003). If no good match exists, it does nothing and playback continues (REQ-FPS-005).
 */
class FrameRateController(private val activity: Activity) {

    private var lastAppliedModeId = -1

    fun apply(frameRate: Float) {
        try {
            if (frameRate <= 0f || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
            val display = currentDisplay() ?: return
            val current = display.mode ?: return
            val modes = display.supportedModes ?: return

            val sameResolution = modes.filter {
                it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
            }
            if (sameResolution.isEmpty()) return

            val best = sameResolution.minByOrNull { refreshDistance(it.refreshRate, frameRate) } ?: return
            // Require a close match (within 1 Hz of an integer multiple); otherwise leave the mode alone.
            if (refreshDistance(best.refreshRate, frameRate) > 1.0f) return
            if (best.modeId == current.modeId || best.modeId == lastAppliedModeId) return

            val params = activity.window.attributes
            params.preferredDisplayModeId = best.modeId
            activity.window.attributes = params
            lastAppliedModeId = best.modeId
        } catch (_: Exception) {
            // Never let an optional display tweak affect playback.
        }
    }

    fun reset() {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
            val params = activity.window.attributes
            params.preferredDisplayModeId = 0
            activity.window.attributes = params
            lastAppliedModeId = -1
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun currentDisplay(): Display? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display
        else activity.windowManager.defaultDisplay

    private fun refreshDistance(refreshRate: Float, fps: Float): Float {
        var best = Float.MAX_VALUE
        for (k in 1..4) best = minOf(best, abs(refreshRate - k * fps))
        return best
    }
}
