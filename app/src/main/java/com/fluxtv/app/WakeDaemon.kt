package com.fluxtv.app

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import java.lang.reflect.Method
import kotlin.system.exitProcess

/**
 * Starts FluxTV when the TV wakes from standby while FluxTV is not running at all — the one case
 * nothing inside the app can cover. After a force-stop Android keeps the app in its "stopped" state:
 * no process, no broadcasts, so [StandbyWatcher] and [BootReceiver] have nobody to run in, and a
 * wake does not send Home either.
 *
 * Shizuku can run this class in a process of its own as the shell user ([ShizukuWake] asks for it).
 * That process belongs to Shizuku, not to FluxTV, so a force-stop of FluxTV does not touch it and it
 * keeps watching. When the screen comes on and FluxTV has no process, it starts the app the way
 * `adb shell am start` does, which also takes FluxTV out of the stopped state. While FluxTV is
 * running, [StandbyWatcher] does this job and the helper stays out of its way.
 *
 * The process has no Context and cannot register a receiver, so it polls the power service instead
 * (a binder call every [POLL_MS], nothing measurable). A suspend it never saw begin still counts as a
 * wake: the clock that keeps running through suspend moves ahead of the one that stops.
 *
 * It lives until Shizuku stops (on a TV that is not rooted, that is the next restart). FluxTV binds
 * it again whenever it runs and Shizuku is up, and the next app update replaces it ([ShizukuWake]
 * versions it by install time), so it never runs stale code. Its log lines carry FluxTV's `FluxTV`
 * logcat tag.
 */
class WakeDaemon : Binder() {

    @Volatile
    private var enabled = true

    init {
        Thread(::watch, "fluxtv-wake").apply { isDaemon = true }.start()
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = when (code) {
        TX_SET_ENABLED -> {
            enabled = data.readInt() != 0
            Log.i(TAG, "[wake-helper] start on wake is ${if (enabled) "on" else "off"}")
            reply?.writeNoException()
            true
        }
        // What Shizuku sends when the app asks for this service to be replaced or removed.
        TX_DESTROY -> {
            Log.i(TAG, "[wake-helper] stopping")
            exitProcess(0)
        }
        else -> super.onTransact(code, data, reply, flags)
    }

    private fun watch() {
        val isInteractive = interactiveCheck() ?: return
        Log.i(TAG, "[wake-helper] watching for the TV to wake (pid ${android.os.Process.myPid()})")
        var wasOn = isInteractive() ?: true
        var lastBoot = SystemClock.elapsedRealtime()
        var lastUptime = SystemClock.uptimeMillis()
        var lastStartMs = -RESTART_GAP_MS
        while (true) {
            try {
                Thread.sleep(POLL_MS)
            } catch (_: InterruptedException) {
                return
            }
            val boot = SystemClock.elapsedRealtime()
            val uptime = SystemClock.uptimeMillis()
            val suspended = (boot - lastBoot) - (uptime - lastUptime) > SUSPEND_GAP_MS
            lastBoot = boot
            lastUptime = uptime

            val on = isInteractive() ?: continue
            val woke = on && (!wasOn || suspended)
            wasOn = on
            if (!woke || !enabled || boot - lastStartMs < RESTART_GAP_MS) continue

            if (fluxTvRunning()) {
                Log.i(TAG, "[wake-helper] the TV woke and FluxTV is running: leaving it to FluxTV")
            } else {
                lastStartMs = boot
                Log.i(TAG, "[wake-helper] the TV woke and FluxTV is not running: starting it")
                startFluxTv()
            }
        }
    }

    /** Looks the power service up once; the returned check is null while the service says nothing. */
    private fun interactiveCheck(): (() -> Boolean?)? = try {
        val getService = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
        val binder = getService.invoke(null, "power") as IBinder
        val power = Class.forName("android.os.IPowerManager\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        val isInteractive: Method = Class.forName("android.os.IPowerManager").getMethod("isInteractive")
        val check = { try { isInteractive.invoke(power) as Boolean } catch (_: Throwable) { null } }
        check
    } catch (e: Throwable) {
        Log.e(TAG, "[wake-helper] cannot reach the power service, so it cannot watch for a wake", e)
        null
    }

    private fun fluxTvRunning(): Boolean = try {
        val process = ProcessBuilder("/system/bin/pidof", PACKAGE).redirectErrorStream(true).start()
        process.inputStream.readBytes()
        process.waitFor() == 0
    } catch (_: Throwable) {
        // Not knowing is no reason to leave the TV on the wrong screen; a start of a running app is harmless.
        false
    }

    private fun startFluxTv() {
        try {
            val process = ProcessBuilder(
                "/system/bin/am", "start", "-n", "$PACKAGE/.MainActivity",
                "--ez", EXTRA_STARTED_BY_HELPER, "true"
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText().trim().replace('\n', ' ')
            Log.i(TAG, "[wake-helper] am start exited ${process.waitFor()}: $output")
        } catch (e: Throwable) {
            Log.e(TAG, "[wake-helper] could not start FluxTV", e)
        }
    }

    companion object {
        private const val TAG = "FluxTV"
        private const val PACKAGE = "com.fluxtv.app"
        private const val POLL_MS = 500L

        /** A suspend is when the boot-time clock gains this much on the uptime clock between two polls. */
        private const val SUSPEND_GAP_MS = 3_000L

        /** One start per wake, however the signals of a slow wake line up. */
        private const val RESTART_GAP_MS = 10_000L

        /** `setEnabled(Boolean)`: whether the "Start FluxTV when the TV turns on" preference is on. */
        const val TX_SET_ENABLED = 1

        /** Shizuku's "destroy" call for a user service (16777114 in AIDL, plus one). */
        private const val TX_DESTROY = 16777115

        /** Set on the intent the helper starts FluxTV with, so the app can say so in its history. */
        const val EXTRA_STARTED_BY_HELPER = "com.fluxtv.app.STARTED_BY_WAKE_HELPER"
    }
}
