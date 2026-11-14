package com.fluxtv.app

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build

@Volatile
private var television: Boolean? = null

/**
 * Whether FluxTV runs on a television (an Android TV, or a TV box driven by a remote) rather than on
 * a phone or tablet, which is driven by touch. Worked out once per process.
 *
 * A TV says so through its UI mode or the leanback feature. Some boxes built from plain Android (the
 * MINIX, for one) say neither, but they have no touchscreen either, and a phone always has one: a
 * device without one is run as a TV. Mistaking a TV for a phone would take the remote's controls
 * away, which is the mistake this errs against.
 *
 * On a TV, FluxTV keeps everything it does there: the remote's controls, starting when the TV turns
 * on, and matching the TV's refresh rate. On a phone it does none of that: it never opens itself, and
 * Easy Mode is worked by touch.
 */
fun isTelevision(context: Context): Boolean {
    television?.let { return it }
    val answer = detectTelevision(context.applicationContext)
    television = answer
    return answer
}

private fun detectTelevision(context: Context): Boolean {
    val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    if (uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
    val pm = context.packageManager
    if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return true
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK_ONLY)) {
        return true
    }
    @Suppress("DEPRECATION")
    if (pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION)) return true
    // What the input devices actually are, not what the build claims: a box with only a remote reads
    // NOTOUCH here even when its system image declares a touchscreen.
    return context.resources.configuration.touchscreen == Configuration.TOUCHSCREEN_NOTOUCH
}
