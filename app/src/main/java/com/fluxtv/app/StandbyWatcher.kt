package com.fluxtv.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.fluxtv.app.data.model.DiagnosticsEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Brings FluxTV back on screen when the TV wakes up, so the remote's power button leads to live TV
 * the same way a restart does (REQ-SEN-003: 0 actions to watch).
 *
 * Power off on a TV remote does not switch an Android TV off, it suspends it, so the next press is
 * not a boot: no BOOT_COMPLETED is sent, [BootReceiver] never runs, and whatever the TV puts on
 * screen when it wakes stays there. The platform announces the wake with ACTION_SCREEN_ON, which
 * Android only ever delivers to receivers registered in code (never from the manifest), so this one
 * is registered for as long as the process lives.
 *
 * It honors the same "Start FluxTV when the TV turns on" preference as the boot path and does
 * nothing while FluxTV is already the app on screen. Two limits are worth knowing: it can only work
 * while the process is alive — Android normally keeps it cached through a standby, but a process the
 * TV has killed has nothing left to hear the wake — and, as with the boot path, Android 10+ blocks
 * apps from opening themselves in the background unless the platform exempts them (see
 * [canStartItself]). Each wake is logged with what came of it, so a start the TV refused shows up in
 * the Diagnostics history instead of looking like a wake that never arrived.
 */
class StandbyWatcher(context: Context, private val container: AppContainer) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Guards against the handful of wake signals Android sends within a second of each other. */
    private var lastWakeMs = -WAKE_MIN_GAP_MS

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            when (action) {
                Intent.ACTION_SCREEN_OFF -> record("Screen off")
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> onWake(action)
            }
        }
    }

    fun register() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun onWake(action: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWakeMs < WAKE_MIN_GAP_MS) return
        lastWakeMs = now
        val signal = action.substringAfterLast('.')

        if (isFluxTvOnScreen()) {
            record("$signal: FluxTV is already on screen")
            return
        }
        scope.launch {
            // As on the boot path, a setting that cannot be read quickly counts as on.
            val settings = try {
                withTimeoutOrNull(SETTINGS_TIMEOUT_MS) { container.settingsRepository.settings.first() }
            } catch (_: Exception) {
                null
            }
            if (settings?.startOnBoot == false) {
                record("$signal: start when the TV turns on is off")
                return@launch
            }
            launchFluxTv(appContext, fromBoot = false)
            record(
                if (canStartItself(appContext)) "$signal: bringing FluxTV to the front"
                else "$signal: bringing FluxTV to the front (without \"Display over other apps\", " +
                    "this Android version may refuse it)"
            )
            // Android can refuse the start without saying so, so the history records whether FluxTV
            // actually came up: that is the difference between "the TV never told us it woke" and
            // "it told us and would not let us open".
            delay(LAUNCH_CHECK_MS)
            record(
                if (isFluxTvOnScreen()) "$signal: FluxTV is on screen"
                else "$signal: FluxTV did not come up — allow it to open itself in " +
                    "Settings → Preferences"
            )
        }
    }

    /**
     * Adds a line to the history. It does not wait for the disk, so the broadcast thread may call it
     * too, and lines from one thread keep the order they were recorded in.
     */
    private fun record(message: String) {
        container.diagnosticsStore.append(DiagnosticsEvent(System.currentTimeMillis(), null, "wake", message))
    }

    private companion object {
        const val WAKE_MIN_GAP_MS = 5_000L
        const val SETTINGS_TIMEOUT_MS = 3_000L

        /** Long enough for a slow TV to put the activity on screen before the start is judged. */
        const val LAUNCH_CHECK_MS = 6_000L
    }
}
