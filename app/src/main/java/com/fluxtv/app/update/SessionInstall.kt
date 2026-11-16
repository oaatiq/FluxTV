package com.fluxtv.app.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

/**
 * Installs an update through Android's own installer (a PackageInstaller session), the way an app
 * store does. Android answers through [UpdateReceiver] with [ACTION_STATUS]:
 *
 * - On Android 12 and newer, an app updating itself is installed **without any question** once it
 *   may install apps ("Install unknown apps", allowed once by the administrator), targets a recent
 *   Android version and declares UPDATE_PACKAGES_WITHOUT_USER_ACTION: FluxTV does all three and asks
 *   for that ([PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED]).
 * - Otherwise Android answers that it needs the user (STATUS_PENDING_USER_ACTION), with the screen to
 *   show for it: Android 7 to 11 always ask before installing an app that is not a system app.
 */
object SessionInstall {

    const val ACTION_STATUS = "com.fluxtv.app.update.INSTALL_STATUS"
    const val EXTRA_VERSION_CODE = "com.fluxtv.app.update.VERSION_CODE"

    /**
     * Copies [apk] into a new session and commits it; returns the session's id. Earlier sessions of
     * FluxTV's that were never finished are dropped first, so they do not hold the TV's storage.
     */
    fun commit(context: Context, apk: File, versionCode: Long): Int {
        val installer = context.packageManager.packageInstaller
        abandonLeftovers(context)
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { out ->
                        input.copyTo(out, 64 * 1024)
                        session.fsync(out)
                    }
                }
                val intent = Intent(context, UpdateReceiver::class.java)
                    .setAction(ACTION_STATUS)
                    .putExtra(EXTRA_VERSION_CODE, versionCode)
                // Android fills in the answer, so the intent has to stay open to it.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
            }
        } catch (e: Exception) {
            abandon(context, id)
            throw e
        }
        return id
    }

    fun abandon(context: Context, sessionId: Int) {
        try {
            context.packageManager.packageInstaller.abandonSession(sessionId)
        } catch (_: Exception) {
        }
    }

    private fun abandonLeftovers(context: Context) {
        try {
            val installer = context.packageManager.packageInstaller
            for (session in installer.mySessions) {
                if (session.appPackageName == context.packageName) abandon(context, session.sessionId)
            }
        } catch (_: Exception) {
        }
    }
}
