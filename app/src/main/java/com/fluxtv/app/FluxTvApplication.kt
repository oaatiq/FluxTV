package com.fluxtv.app

import android.app.Application
import android.os.SystemClock
import com.fluxtv.app.work.PlaylistRefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class FluxTvApplication : Application() {

    lateinit var container: AppContainer
        private set

    private lateinit var standbyWatcher: StandbyWatcher

    lateinit var shizukuWake: ShizukuWake
        private set

    /** Start-up work that must not hold up the main thread, for as long as the process lives. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        BootTrace.applicationStartedMs = SystemClock.elapsedRealtime()
        // First, so that a crash anywhere after this leaves a note in the Diagnostics history.
        CrashNotes.install(this) {
            if (::container.isInitialized) container.playerController.status.value.channel?.name else null
        }
        container = AppContainer(this)
        appScope.launch { CrashNotes.collect(this@FluxTvApplication, container.diagnosticsStore) }
        // Coming back from standby is announced to receivers registered in code only, so FluxTV
        // listens for it from here, for as long as the process lives.
        standbyWatcher = StandbyWatcher(this, container).also { it.register() }
        // With Shizuku on the TV, a helper that outlives a force-stop of FluxTV starts it on a wake.
        shizukuWake = ShizukuWake(this, container).also { it.register() }
        // Periodic, unattended playlist refresh (REQ-PL-004), at the interval chosen in Preferences.
        // Scheduling it with the 12 h default here, on every start, overwrote that choice.
        appScope.launch {
            val hours = runCatching { container.settingsRepository.settings.first().refreshIntervalHours }
                .getOrDefault(12)
            PlaylistRefreshWorker.schedule(this@FluxTvApplication, hours)
        }
        BootTrace.applicationReadyMs = SystemClock.elapsedRealtime()
    }
}
