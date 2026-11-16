package com.fluxtv.app

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import com.fluxtv.app.data.model.DiagnosticsEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.File

/**
 * Where FluxTV uses Shizuku, when the TV has it, to start itself after a force-stop.
 *
 * A force-stopped app is not run again by anything but a launch: no boot signal, no wake signal, no
 * process, and Android sends no Home when the TV wakes. What survives a force-stop is a process that
 * is not FluxTV's, so with Shizuku's permission this starts [WakeDaemon] in one of Shizuku's own
 * (daemon mode: it runs until Shizuku stops, not until FluxTV does) and tells it whether "Start
 * FluxTV when the TV turns on" is on.
 *
 * The same helper installs FluxTV's updates without asking anyone, on any Android version ([install]).
 *
 * Everything here is best effort and quiet: without Shizuku, or without its permission, FluxTV
 * behaves as before and Settings → Preferences says what is missing. Shizuku starts the helper
 * again after a restart of its own by telling FluxTV, which is why this listens from
 * [FluxTvApplication] for as long as the process lives.
 */
class ShizukuWake(context: Context, private val container: AppContainer) {

    enum class State {
        /** No Shizuku on this TV, or it is not running. */
        NOT_RUNNING,

        /** Shizuku is running and FluxTV has not been allowed to use it. */
        NEEDS_PERMISSION,

        /** Allowed; the helper is being started. */
        STARTING,

        /** The helper is running and watching. */
        ACTIVE
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(State.NOT_RUNNING)
    val state: StateFlow<State> = mutableState

    @Volatile
    private var helper: IBinder? = null

    @Volatile
    private var bound = false

    @Volatile
    private var startOnWake = true

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            helper = binder
            mutableState.value = State.ACTIVE
            scope.launch {
                pushPreference()
                record("Shizuku helper running: FluxTV will start when the TV wakes, even after a force-stop")
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            helper = null
            bound = false
            scope.launch { refresh() }
        }
    }

    fun register() {
        Shizuku.addBinderReceivedListenerSticky { scope.launch { refresh() } }
        Shizuku.addBinderDeadListener {
            helper = null
            bound = false
            mutableState.value = State.NOT_RUNNING
        }
        Shizuku.addRequestPermissionResultListener { _, _ -> scope.launch { refresh() } }
        scope.launch {
            container.settingsRepository.settings.map { it.startOnBoot }.distinctUntilChanged().collect {
                startOnWake = it
                pushPreference()
            }
        }
    }

    /** What came of handing an update to the helper. */
    sealed class HelperInstall {
        /** Android installed it (FluxTV is normally closed before this can be seen). */
        data object Installed : HelperInstall()

        /** No helper, or one from before it could install: another way has to be used. */
        data object Unavailable : HelperInstall()

        /** [message]: what `pm` (or the helper) said. */
        data class Failed(val message: String) : HelperInstall()
    }

    /** Whether Shizuku itself is running on this TV, whatever FluxTV may do with it. */
    fun shizukuRunning(): Boolean = try {
        Shizuku.pingBinder() && !Shizuku.isPreV11()
    } catch (_: Throwable) {
        false
    }

    /**
     * Waits a little for the helper when Shizuku runs and FluxTV may use it, as it is started
     * anew with each start of FluxTV's process: an update found ready at the start would otherwise
     * take another way, or none. True once the helper is there.
     */
    suspend fun awaitHelper(timeoutMs: Long): Boolean {
        if (state.value == State.ACTIVE) return true
        if (!shizukuRunning()) return false
        val granted = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
        if (!granted) return false
        return withTimeoutOrNull(timeoutMs) { state.first { it == State.ACTIVE } } != null
    }

    /**
     * Has the helper install [apk] (already checked by FluxTV, with this [sha256]) over FluxTV, and
     * start FluxTV again afterwards if [relaunch]. Blocks until `pm` has answered, which on success
     * is never seen here: Android closes FluxTV to replace it. Called off the main thread.
     */
    fun install(apk: File, sha256: String, relaunch: Boolean): HelperInstall {
        val binder = helper ?: return HelperInstall.Unavailable
        return try {
            val begin = call(binder, WakeDaemon.TX_INSTALL_BEGIN) {
                writeLong(apk.length())
                writeString(sha256)
            } ?: return HelperInstall.Unavailable
            if (!begin.first) return HelperInstall.Failed(begin.second)
            apk.inputStream().use { input ->
                val buffer = ByteArray(INSTALL_CHUNK_BYTES)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    val sent = call(binder, WakeDaemon.TX_INSTALL_WRITE) { writeByteArray(buffer, 0, n) }
                        ?: return HelperInstall.Unavailable
                    if (!sent.first) return HelperInstall.Failed(sent.second)
                }
            }
            val done = call(binder, WakeDaemon.TX_INSTALL_FINISH) { writeInt(if (relaunch) 1 else 0) }
                ?: return HelperInstall.Unavailable
            if (done.first) HelperInstall.Installed else HelperInstall.Failed(done.second)
        } catch (e: Exception) {
            // Includes the helper going away (DeadObjectException) and a file that cannot be read.
            HelperInstall.Failed("The Shizuku helper could not install it: ${e.javaClass.simpleName} ${e.message ?: ""}".trim())
        }
    }

    /** One call to the helper: whether it went well and what it said, or null if it does not know the call. */
    private inline fun call(binder: IBinder, code: Int, write: Parcel.() -> Unit): Pair<Boolean, String>? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.write()
            if (!binder.transact(code, data, reply, 0)) return null
            reply.readException()
            val ok = reply.readInt() == 1
            return ok to reply.readString().orEmpty()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Asks Shizuku to let FluxTV use it; Shizuku shows its own dialog for the administrator. */
    fun requestPermission() {
        try {
            if (Shizuku.pingBinder() && !Shizuku.isPreV11()) Shizuku.requestPermission(REQUEST_CODE)
        } catch (_: Throwable) {
        }
    }

    /** Runs off the main thread: it writes to the history, and Shizuku's calls wait on its server. */
    private fun refresh() {
        try {
            when {
                !Shizuku.pingBinder() || Shizuku.isPreV11() -> mutableState.value = State.NOT_RUNNING
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> {
                    if (mutableState.value != State.NEEDS_PERMISSION) {
                        record("Shizuku is running, but FluxTV is not allowed to use it yet")
                    }
                    mutableState.value = State.NEEDS_PERMISSION
                }
                helper != null -> mutableState.value = State.ACTIVE
                !bound -> {
                    bound = true
                    mutableState.value = State.STARTING
                    record("Starting the Shizuku helper")
                    Shizuku.bindUserService(serviceArgs(), connection)
                }
            }
        } catch (e: Throwable) {
            bound = false
            mutableState.value = State.NOT_RUNNING
            record("Could not start the Shizuku helper: ${e.javaClass.simpleName} ${e.message ?: ""}".trim())
        }
    }

    /**
     * The helper is versioned by FluxTV's install time, so an updated app replaces the one still
     * running the old code (Shizuku stops the old one when the version differs).
     */
    private fun serviceArgs(): Shizuku.UserServiceArgs {
        val installed = try {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).lastUpdateTime / 1000
        } catch (_: Exception) {
            1L
        }
        return Shizuku.UserServiceArgs(ComponentName(appContext.packageName, WakeDaemon::class.java.name))
            .processNameSuffix("wake")
            .tag("fluxtv-wake")
            .version(installed.toInt())
            .daemon(true)
    }

    private fun pushPreference() {
        val binder = helper ?: return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInt(if (startOnWake) 1 else 0)
            binder.transact(WakeDaemon.TX_SET_ENABLED, data, reply, 0)
        } catch (_: RemoteException) {
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun record(message: String) {
        container.diagnosticsStore.append(DiagnosticsEvent(System.currentTimeMillis(), null, "wake", message))
    }

    private companion object {
        const val REQUEST_CODE = 4801

        /** Well under the 1 MB a process has for all of its binder calls at once. */
        const val INSTALL_CHUNK_BYTES = 128 * 1024
    }
}
