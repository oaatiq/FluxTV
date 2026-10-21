package com.fluxtv.app

import android.app.Application
import com.fluxtv.app.work.PlaylistRefreshWorker

class FluxTvApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Periodic, unattended playlist refresh (REQ-PL-004).
        PlaylistRefreshWorker.schedule(this)
    }
}
