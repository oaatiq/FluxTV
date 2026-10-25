package com.fluxtv.app

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Starts FluxTV, or brings it back to the front if it is already running: how it gets on screen
 * without anyone pressing a button, after a boot ([BootReceiver]) and when the TV wakes from standby
 * ([StandbyWatcher]).
 *
 * Best effort — a platform that refuses the start must never take the app down with it — so
 * [isFluxTvOnScreen] a few seconds later is what says whether it actually worked.
 */
internal fun launchFluxTv(context: Context, fromBoot: Boolean) {
    try {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(BootTrace.EXTRA_FROM_BOOT, fromBoot)
        )
    } catch (_: Exception) {
    }
}

/** True while FluxTV has something on screen, so it does not need starting or bringing forward. */
internal fun isFluxTvOnScreen(): Boolean {
    val info = ActivityManager.RunningAppProcessInfo()
    ActivityManager.getMyMemoryState(info)
    return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
}

/**
 * Whether FluxTV is allowed to open itself while none of it is on screen.
 *
 * Android 10 and newer refuse that for ordinary apps, which is exactly what starting after a boot
 * or a wake asks for. "Display over other apps" is the exemption the platform documents for it and
 * the only one an administrator can grant with the remote, so FluxTV asks for the permission in the
 * manifest and leaves it to them (Settings → Preferences). Android 9 and older never blocked it.
 *
 * False does not make a start impossible — Android also lets an app through if it still owns a task
 * on the Recents screen, which FluxTV often does — so it reads as "this may be refused", and the
 * Diagnostics history records what actually happened.
 */
internal fun canStartItself(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(context)

/** Opens Android's "Display over other apps" screen for FluxTV, where that permission is granted. */
internal fun openSelfStartPermission(context: Context) {
    val perApp = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(perApp)
    } catch (_: Exception) {
        // Not every box exposes the per-app screen; the full list is the fallback.
        try {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
        }
    }
}

/**
 * Opens the TV's own launcher, for an administrator who has made FluxTV the Home app and needs the
 * app row or the TV's settings back. FluxTV has to name the other launcher explicitly: a plain Home
 * intent would land on FluxTV again. False when the TV has no other launcher to open.
 */
internal fun openOtherLauncher(context: Context): Boolean {
    val pm = context.packageManager
    val homes = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
    val other = homes.firstOrNull { it.activityInfo.packageName != context.packageName } ?: return false
    return try {
        context.startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setClassName(other.activityInfo.packageName, other.activityInfo.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (_: Exception) {
        false
    }
}

/** Opens Android's screen for choosing the default Home app, where FluxTV is picked or dropped. */
internal fun openHomeAppSettings(context: Context): Boolean = try {
    context.startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: Exception) {
    false
}
