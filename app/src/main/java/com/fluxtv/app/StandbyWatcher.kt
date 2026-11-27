package com.fluxtv.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
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
 * TV has killed has nothing left to hear the wake, and Android 14+ holds the broadcast back from one
 * it has frozen in the background, which [WakeListenerService] prevents — and, as with the boot
 * path, Android 10+ blocks apps from opening themselves in the background unless the platform
 * exempts them (see [canStartItself]). Each wake is logged with what came of it, so a start the TV refused shows up in
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
                Intent.ACTION_SCREEN_OFF -> {
                    record("Screen off")
                    // Standby is when nobody watches: the moment for an update that was waiting.
                    container.updateManager.onLeftScreen()
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> onWake(action)
            }
        }
    }

    fun register() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        // USER_PRESENT comes from SystemUI, not the system: below Android 13 a not-exported receiver
        // is guarded by a permission only FluxTV holds, so SystemUI's broadcast was refused and never
        // arrived. It is a protected broadcast no app can send, so it is safe to take from outside.
        ContextCompat.registerReceiver(
            appContext, receiver, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_EXPORTED
        )
    }

    /**
     * Whether the TV is really awake now. Android queues the screen broadcasts and sends them in
     * order, so on a slow box a SCREEN_ON can arrive after the TV has already gone back to sleep (the
     * TV switched on and straight off again, two presses of power). Acted on, it opened FluxTV on a
     * sleeping TV, which Android undid at once, and Android 9 then never gave the video view a
     * surface again on the next real wake: the picture stayed frozen or black until FluxTV was
     * reopened by hand.
     */
    private fun isAwake(): Boolean {
        val power = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (power?.isInteractive == false) return false
        val display = (appContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
        return display == null || display.state == Display.STATE_ON || display.state == Display.STATE_UNKNOWN
    }

    private fun onWake(action: String) {
        val signal = action.substringAfterLast('.')
        if (isAwake()) {
            onAwake(signal)
            return
        }
        // Not awake yet is not the same as asleep: the broadcast can arrive while the display is still
        // coming on (on a Raspberry Pi 5 the HDMI output waits for the TV, and FluxTV used to ignore
        // that wake). A signal that arrived after the TV went back to sleep stays asleep, so only a
        // display that is still off after the wait is taken for one.
        scope.launch {
            val startMs = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - startMs < AWAKE_WAIT_MS) {
                delay(AWAKE_POLL_MS)
                if (isAwake()) {
                    record("$signal: the display came on after ${SystemClock.elapsedRealtime() - startMs} ms")
                    onAwake(signal)
                    return@launch
                }
            }
            record("$signal: the TV is not awake, ignored")
        }
    }

    /**
     * True for the first wake signal in [WAKE_MIN_GAP_MS]. Signals can now be handled from the
     * broadcast thread and from the wait in [onWake] at the same time, so the check and the update
     * happen together.
     */
    @Synchronized
    private fun claimWake(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWakeMs < WAKE_MIN_GAP_MS) return false
        lastWakeMs = now
        return true
    }

    private fun onAwake(signal: String) {
        // Checked after the TV is known to be awake, so a late signal does not hold back the real wake
        // after it.
        if (!claimWake()) return

        if (isFluxTvOnScreen()) {
            record("$signal: ${AppName} is already on screen")
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
            // Reading the settings can take a moment, in which the TV may have gone back to sleep.
            if (!isAwake()) {
                record("$signal: the TV went back to sleep, ${AppName} left as it was")
                return@launch
            }
            if (isFluxTvOnScreen()) {
                record("$signal: ${AppName} is already on screen")
                return@launch
            }
            launchFluxTv(appContext, fromBoot = false)
            record(
                if (canStartItself(appContext)) "$signal: bringing ${AppName} to the front"
                else "$signal: bringing ${AppName} to the front (without \"Display over other apps\", " +
                    "this Android version may refuse it)"
            )
            // Android can refuse the start without saying so, so the history records whether FluxTV
            // actually came up: that is the difference between "the TV never told us it woke" and
            // "it told us and would not let us open".
            delay(LAUNCH_CHECK_MS)
            record(
                if (isFluxTvOnScreen()) "$signal: ${AppName} is on screen"
                else "$signal: ${AppName} did not come up — allow it to open itself in " +
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

        /** How long a wake signal waits for the display to come on before it is taken for a late one. */
        const val AWAKE_WAIT_MS = 5_000L
        const val AWAKE_POLL_MS = 250L
        const val SETTINGS_TIMEOUT_MS = 3_000L

        /** Long enough for a slow TV to put the activity on screen before the start is judged. */
        const val LAUNCH_CHECK_MS = 6_000L
    }
}
