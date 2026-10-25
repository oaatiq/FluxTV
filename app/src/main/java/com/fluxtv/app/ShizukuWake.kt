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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/**
 * Where FluxTV uses Shizuku, when the TV has it, to start itself after a force-stop.
 *
 * A force-stopped app is not run again by anything but a launch: no boot signal, no wake signal, no
 * process. Making FluxTV the Home app covers a restart, and Android treats a wake as no start at
 * all. What survives a force-stop is a process that is not FluxTV's, so with Shizuku's permission
 * this starts [WakeDaemon] in one of Shizuku's own (daemon mode: it runs until Shizuku stops, not
 * until FluxTV does) and tells it whether "Start FluxTV when the TV turns on" is on.
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
    }
}
