package com.fluxtv.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.fluxtv.app.data.model.DiagnosticsEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Starts FluxTV straight into live TV once the TV has finished booting, so a Viewer never has to
 * find and open the app (REQ-SEN-003: 0 actions to watch). Honors the "Start FluxTV when the TV
 * turns on" preference; if the setting cannot be read quickly it defaults to on.
 *
 * Android 10+ blocks activities started from the background, so on those versions this only works
 * where the platform exempts the app (e.g. the "Display over other apps" permission); the MiTV
 * (Android 9) is not restricted.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != ACTION_QUICKBOOT_POWERON) return

        val app = context.applicationContext as FluxTvApplication
        val container = app.container
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = withTimeoutOrNull(SETTINGS_TIMEOUT_MS) { container.settingsRepository.settings.first() }
                val enabled = settings?.startOnBoot ?: true
                if (enabled) {
                    context.startActivity(
                        Intent(context, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra(BootTrace.EXTRA_FROM_BOOT, true)
                    )
                }
                container.diagnosticsStore.append(
                    BootTrace.event(if (enabled) "Boot completed, starting FluxTV" else "Boot completed, start on boot is off")
                )
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
        const val SETTINGS_TIMEOUT_MS = 3_000L
    }
}

/**
 * Records how long FluxTV takes to get going after a boot, in the admin Diagnostics history: boot
 * completed, FluxTV on screen, and the first picture playing, each stamped with the seconds since the
 * TV powered on so the gaps between them are the start-up delay.
 */
object BootTrace {
    const val EXTRA_FROM_BOOT = "com.fluxtv.app.FROM_BOOT"

    @Volatile
    var awaitingOnScreen = false

    @Volatile
    var awaitingFirstPlay = false

    /** Called when an activity launched by [BootReceiver] is created. */
    fun markLaunchedByBoot() {
        awaitingOnScreen = true
        awaitingFirstPlay = true
    }

    fun stamp(message: String): String {
        val seconds = SystemClock.elapsedRealtime() / 1000.0
        return String.format(Locale.US, "%s (+%.1f s since power-on)", message, seconds)
    }

    fun event(message: String) = DiagnosticsEvent(System.currentTimeMillis(), null, "boot", stamp(message))
}
