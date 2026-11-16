package com.fluxtv.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Opens Android's screen where FluxTV is allowed to install apps ("Install unknown apps", Android 8+;
 * one switch for all apps, under Security, before that). Needed once, for Android's installer to
 * take FluxTV's updates; the Shizuku helper does without it. By adb instead:
 * `adb shell appops set com.fluxtv.app REQUEST_INSTALL_PACKAGES allow`.
 */
fun openInstallPermission(context: Context) {
    val perApp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
    } else {
        Intent(Settings.ACTION_SECURITY_SETTINGS)
    }
    try {
        context.startActivity(perApp.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        // Not every box has the per-app screen; the list of all apps is the fallback.
        try {
            val all = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            } else {
                Intent(Settings.ACTION_SETTINGS)
            }
            context.startActivity(all.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }
}
