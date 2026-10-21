package com.fluxtv.app

import android.content.Context
import com.fluxtv.app.data.cache.DiagnosticsStore
import com.fluxtv.app.data.cache.PlaylistCache
import com.fluxtv.app.data.remote.PlaylistFetcher
import com.fluxtv.app.data.repository.ChannelRepository
import com.fluxtv.app.data.settings.SettingsRepository
import com.fluxtv.app.playback.FluxPlayerController
import com.fluxtv.app.playback.NetworkMonitor

/**
 * Tiny manual dependency container held by the Application. Avoids an annotation-processing DI
 * framework to keep the build simple and robust. All members are process-scoped singletons.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val settingsRepository = SettingsRepository(appContext)
    val playlistCache = PlaylistCache(appContext)
    val diagnosticsStore = DiagnosticsStore(appContext)
    private val playlistFetcher = PlaylistFetcher(appContext)
    val channelRepository = ChannelRepository(playlistCache, playlistFetcher)
    val networkMonitor = NetworkMonitor(appContext)
    val playerController = FluxPlayerController(appContext, diagnosticsStore, networkMonitor)
}
