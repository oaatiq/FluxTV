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
 * Which mode is a match, and which of several is best, is [RefreshRateMatch]'s choice.
 */
class FrameRateController(private val activity: Activity) {

    private var lastAppliedModeId = -1

    /** The refresh rate the display had before FluxTV first matched one: the TV's own choice. */
    private var homeRefreshRate = 0f

    fun apply(frameRate: Float) {
        try {
            if (frameRate <= 0f || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
            val display = currentDisplay() ?: return
            val current = display.mode ?: return
            val modes = display.supportedModes ?: return
            if (homeRefreshRate <= 0f) homeRefreshRate = current.refreshRate

            val sameResolution = modes.filter {
                it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
            }
            val chosen = RefreshRateMatch.choose(
                frameRate,
                sameResolution.map { it.refreshRate },
                currentIndex = sameResolution.indexOfFirst { it.modeId == current.modeId },
                homeRefreshRate = homeRefreshRate
            ) ?: return
            val best = sameResolution[chosen]
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
}

/**
 * Which refresh rate shows a frame rate smoothly: one that is a whole multiple of it (up to
 * [MAX_MULTIPLE] times), so that every frame stays on screen for the same number of refreshes.
 */
internal object RefreshRateMatch {

    /** Up to four refreshes per frame: 24 fps at 96 Hz, 25 at 100, 30 at 120. */
    private const val MAX_MULTIPLE = 4

    /**
     * How far off a multiple a refresh rate may be and still count, as a share of it: the 0.1% of a
     * 59.94 Hz mode for 30 fps, or a 60 Hz one for 29.97, where one frame in every thousand is shown
     * once more or once less. It used to be 1 Hz either way, which let 25 fps go to a 24 Hz mode,
     * 4% off: a frame left out every second, a stutter the Viewer sees every second.
     */
    private const val MAX_ERROR = 0.005f

    /** Errors closer than this to the best one count as just as good. */
    private const val SAME_ERROR = 0.0005f

    /**
     * The index in [refreshRates] of the one to show [frameRate] with, or null when none is close
     * enough to a multiple of it (the mode is then left as it is). Of the ones that match best, the
     * one in use ([currentIndex], -1 for none) is kept, so the TV does not go dark to switch to
     * another just as good. Else it is the one nearest [homeRefreshRate], the TV's own: 50 Hz for
     * 25 fps on a 60 Hz TV, not 25 Hz, which would make the screen's own movement (menus, the
     * channel list) as jerky as 25 pictures a second. It used to be whichever the TV listed first.
     */
    fun choose(frameRate: Float, refreshRates: List<Float>, currentIndex: Int, homeRefreshRate: Float): Int? {
        if (frameRate <= 0f || refreshRates.isEmpty()) return null
        val errors = refreshRates.map { error(it, frameRate) }
        val least = errors.min()
        if (least > MAX_ERROR) return null
        val best = refreshRates.indices.filter { errors[it] <= least + SAME_ERROR }
        if (currentIndex in best) return currentIndex
        return best.minWith(compareBy<Int> { abs(refreshRates[it] - homeRefreshRate) }.thenByDescending { refreshRates[it] })
    }

    /** How far [refreshRate] is from the nearest multiple of [frameRate], as a share of it. */
    private fun error(refreshRate: Float, frameRate: Float): Float {
        if (refreshRate <= 0f) return Float.MAX_VALUE
        var nearest = Float.MAX_VALUE
        for (k in 1..MAX_MULTIPLE) nearest = minOf(nearest, abs(refreshRate - k * frameRate))
        return nearest / refreshRate
    }
}
