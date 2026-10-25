package com.fluxtv.app

import android.app.Application
import android.os.SystemClock
import com.fluxtv.app.work.PlaylistRefreshWorker

class FluxTvApplication : Application() {

    lateinit var container: AppContainer
        private set

    private lateinit var standbyWatcher: StandbyWatcher

    override fun onCreate() {
        super.onCreate()
        BootTrace.applicationStartedMs = SystemClock.elapsedRealtime()
        container = AppContainer(this)
        // Coming back from standby is announced to receivers registered in code only, so FluxTV
        // listens for it from here, for as long as the process lives.
        standbyWatcher = StandbyWatcher(this, container).also { it.register() }
        // Periodic, unattended playlist refresh (REQ-PL-004).
        PlaylistRefreshWorker.schedule(this)
        BootTrace.applicationReadyMs = SystemClock.elapsedRealtime()
    }
}
