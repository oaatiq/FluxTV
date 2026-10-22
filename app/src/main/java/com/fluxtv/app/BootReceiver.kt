package com.fluxtv.app

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import com.fluxtv.app.data.model.DiagnosticsEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Starts FluxTV straight into live TV once the TV has finished booting, so a Viewer never has to
 * find and open the app (REQ-SEN-003: 0 actions to watch). Honors the "Start FluxTV when the TV
 * turns on" preference; if the setting cannot be read quickly it defaults to on.
 *
 * Android delivers BOOT_COMPLETED to every installed app in turn, which on a slow TV can take a
 * minute, so the receiver asks for the highest allowed priority (see the manifest). The first
 * signal starts the app; a later one (e.g. a QUICKBOOT_POWERON after BOOT_COMPLETED) only brings it
 * back to the front if something else has covered it. Each signal is logged with the time since
 * power-on, and the first with where its start-up time went.
 *
 * Android 10+ blocks activities started from the background, so on those versions this only works
 * where the platform exempts the app (e.g. the "Display over other apps" permission); the MiTV
 * (Android 9) is not restricted.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val enteredMs = SystemClock.elapsedRealtime()
        val action = intent.action ?: return
        if (action !in START_ACTIONS) return

        val container = (context.applicationContext as FluxTvApplication).container
        val firstSignal = BootTrace.claimStart()
        val signal = action.substringAfterLast('.')
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = withTimeoutOrNull(SETTINGS_TIMEOUT_MS) { container.settingsRepository.settings.first() }
                val settingReadMs = SystemClock.elapsedRealtime() - enteredMs
                val message = when {
                    settings?.startOnBoot == false -> "$signal: start on boot is off"
                    firstSignal -> {
                        launchApp(context, fromBoot = true)
                        "$signal: starting FluxTV ${BootTrace.timings(enteredMs, settingReadMs)}"
                    }
                    !isInForeground() -> {
                        launchApp(context, fromBoot = false)
                        "$signal: bringing FluxTV to the front"
                    }
                    else -> "$signal: FluxTV is already on screen"
                }
                container.diagnosticsStore.append(BootTrace.event(message))
            } finally {
                pending.finish()
            }
        }
    }

    private fun launchApp(context: Context, fromBoot: Boolean) {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(BootTrace.EXTRA_FROM_BOOT, fromBoot)
        )
    }

    private fun isInForeground(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private companion object {
        const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
        val START_ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, ACTION_QUICKBOOT_POWERON)
        const val SETTINGS_TIMEOUT_MS = 3_000L
    }
}

/**
 * Records how long FluxTV takes to get going after a boot, in the admin Diagnostics history: the boot
 * signal, FluxTV on screen, and the first picture playing, each stamped with the seconds since the
 * TV powered on so the gaps between them are the start-up delay.
 */
object BootTrace {
    const val EXTRA_FROM_BOOT = "com.fluxtv.app.FROM_BOOT"

    private val started = AtomicBoolean(false)

    @Volatile
    var awaitingOnScreen = false

    @Volatile
    var awaitingFirstPlay = false

    @Volatile
    var applicationStartedMs = 0L

    @Volatile
    var applicationReadyMs = 0L

    /** True for the first boot signal this process sees; that one starts the app. */
    fun claimStart(): Boolean = started.compareAndSet(false, true)

    /** Called when an activity launched for a boot start is created or brought forward. */
    fun markLaunchedByBoot() {
        awaitingOnScreen = true
        awaitingFirstPlay = true
    }

    fun stamp(message: String): String {
        val seconds = SystemClock.elapsedRealtime() / 1000.0
        return String.format(Locale.US, "%s (+%.1f s since power-on)", message, seconds)
    }

    fun event(message: String) = DiagnosticsEvent(System.currentTimeMillis(), null, "boot", stamp(message))

    /** Where the start-up time went, all in seconds since power-on unless marked ms. */
    fun timings(receiverEnteredMs: Long, settingReadMs: Long): String = String.format(
        Locale.US,
        "[process started +%.1f s, app init %d ms, receiver entered +%.1f s, setting read %d ms]",
        Process.getStartElapsedRealtime() / 1000.0,
        applicationReadyMs - applicationStartedMs,
        receiverEnteredMs / 1000.0,
        settingReadMs
    )
}
