package com.fluxtv.app

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.lang.reflect.Method
import java.security.MessageDigest
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
 *
 * It also installs FluxTV's own updates, the one way to do that silently on every Android version
 * (see [com.fluxtv.app.update.UpdateManager]): the shell user may install apps, as `adb install`
 * does. FluxTV, having checked the download, sends it over in pieces ([TX_INSTALL_BEGIN],
 * [TX_INSTALL_WRITE], [TX_INSTALL_FINISH]); the helper keeps it where `adb install` keeps one
 * (/data/local/tmp), checks its size and SHA-256 against what FluxTV announced, has `pm` install it
 * over FluxTV (which keeps FluxTV's data), and, if FluxTV was on screen, starts it again: Android
 * closes an app it updates, and the shell user may start one at any time. Only FluxTV holds this
 * binder (Shizuku hands it to the app that started the helper).
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
        TX_INSTALL_BEGIN -> {
            val size = data.readLong()
            val sha256 = data.readString().orEmpty()
            answer(reply, beginInstall(size, sha256))
            true
        }
        TX_INSTALL_WRITE -> {
            answer(reply, writeInstall(data.createByteArray() ?: ByteArray(0)))
            true
        }
        TX_INSTALL_FINISH -> {
            val relaunch = data.readInt() != 0
            val output = finishInstall(relaunch)
            reply?.writeNoException()
            reply?.writeInt(if (output.contains("Success")) 1 else 0)
            reply?.writeString(output)
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

    // ---------- installing FluxTV's updates ----------

    private val installLock = Any()
    private var staged: FileOutputStream? = null
    private var stagedDigest: MessageDigest? = null
    private var stagedSize = 0L
    private var stagedSha256 = ""
    private var stagedBytes = 0L

    /** Null when it went well, else why not; the answer's first int says which. */
    private fun answer(reply: Parcel?, error: String?) {
        reply?.writeNoException()
        reply?.writeInt(if (error == null) 1 else 0)
        reply?.writeString(error)
    }

    private fun beginInstall(size: Long, sha256: String): String? = synchronized(installLock) {
        dropStaged()
        if (size <= 0 || sha256.length != 64) return "FluxTV announced no size or checksum"
        return try {
            staged = FileOutputStream(STAGED_APK)
            stagedDigest = MessageDigest.getInstance("SHA-256")
            stagedSize = size
            stagedSha256 = sha256.lowercase()
            stagedBytes = 0L
            Log.i(TAG, "[update-helper] receiving an update of $size bytes")
            null
        } catch (e: Throwable) {
            dropStaged()
            "The helper cannot write ${STAGED_APK.path}: ${e.message}"
        }
    }

    private fun writeInstall(bytes: ByteArray): String? = synchronized(installLock) {
        val out = staged ?: return "No update is being received"
        if (stagedBytes + bytes.size > stagedSize) {
            dropStaged()
            return "FluxTV sent more than it announced"
        }
        return try {
            out.write(bytes)
            stagedDigest?.update(bytes)
            stagedBytes += bytes.size
            null
        } catch (e: Throwable) {
            dropStaged()
            "The helper could not store the update: ${e.message}"
        }
    }

    /**
     * Installs what was received, and answers with what `pm` said ("Success", or "Failure […]").
     * On success Android closes FluxTV before the answer can reach it, so the start that follows is
     * the helper's own doing.
     */
    private fun finishInstall(relaunch: Boolean): String = synchronized(installLock) {
        val out = staged ?: return "Failure [FluxTV: no update was received]"
        try {
            out.fd.sync()
            out.close()
        } catch (_: Throwable) {
        }
        staged = null
        val sha = stagedDigest?.digest()?.joinToString("") { "%02x".format(it) }
        stagedDigest = null
        if (stagedBytes != stagedSize || sha != stagedSha256) {
            STAGED_APK.delete()
            return "Failure [FluxTV: the update arrived damaged at the helper]"
        }
        Log.i(TAG, "[update-helper] installing the update")
        val output = run("/system/bin/pm", "install", "-r", STAGED_APK.path)
        STAGED_APK.delete()
        Log.i(TAG, "[update-helper] pm install: $output")
        if (output.contains("Success") && relaunch) {
            Log.i(TAG, "[update-helper] starting FluxTV again on the new version")
            startFluxTv(EXTRA_RELAUNCHED_AFTER_UPDATE)
        }
        return output
    }

    private fun dropStaged() {
        try {
            staged?.close()
        } catch (_: Throwable) {
        }
        staged = null
        stagedDigest = null
        STAGED_APK.delete()
    }

    /** Runs a command and returns what it printed (or why it could not run), on one line. */
    private fun run(vararg command: String): String = try {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        process.outputStream.close()
        val text = readAll(process.inputStream)
        val code = process.waitFor()
        "$text (exit $code)".replace('\n', ' ').trim()
    } catch (e: Throwable) {
        "Failure [FluxTV: ${command.first()} could not run: ${e.message}]"
    }

    private fun readAll(input: InputStream): String {
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            bytes.write(buffer, 0, n)
        }
        return bytes.toString("UTF-8")
    }

    private fun startFluxTv(extra: String = EXTRA_STARTED_BY_HELPER) {
        try {
            val process = ProcessBuilder(
                "/system/bin/am", "start", "-n", "$PACKAGE/.MainActivity",
                "--ez", extra, "true"
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

        /** An update of `size` (long) bytes with this SHA-256 (string) is about to be sent. */
        const val TX_INSTALL_BEGIN = 2

        /** The next piece of the update (byte array). */
        const val TX_INSTALL_WRITE = 3

        /** Install what was sent; then start FluxTV again if the int is 1. Answers what `pm` said. */
        const val TX_INSTALL_FINISH = 4

        /** Where the update waits for `pm`: the shell user's own folder, as for `adb install`. */
        private val STAGED_APK = File("/data/local/tmp/fluxtv-update.apk")

        /** Shizuku's "destroy" call for a user service (16777114 in AIDL, plus one). */
        private const val TX_DESTROY = 16777115

        /** Set on the intent the helper starts FluxTV with, so the app can say so in its history. */
        const val EXTRA_STARTED_BY_HELPER = "com.fluxtv.app.STARTED_BY_WAKE_HELPER"

        /** Set on the intent the helper starts FluxTV with after installing an update while it was on screen. */
        const val EXTRA_RELAUNCHED_AFTER_UPDATE = "com.fluxtv.app.RELAUNCHED_AFTER_UPDATE"
    }
}
