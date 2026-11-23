package com.fluxtv.app

import android.content.Context
import com.fluxtv.app.data.cache.DiagnosticsStore
import com.fluxtv.app.data.cache.PlaylistCache
import com.fluxtv.app.data.guide.GuideRepository
import com.fluxtv.app.data.remote.PlaylistFetcher
import com.fluxtv.app.data.remote.newHttpClient
import com.fluxtv.app.data.repository.ChannelRepository
import com.fluxtv.app.data.settings.SettingsRepository
import com.fluxtv.app.playback.FluxPlayerController
import com.fluxtv.app.playback.NetworkMonitor
import com.fluxtv.app.update.UpdateManager
import okhttp3.OkHttpClient

/**
 * Tiny manual dependency container held by the Application. Avoids an annotation-processing DI
 * framework to keep the build simple and robust. All members are process-scoped singletons.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val settingsRepository = SettingsRepository(appContext)
    val playlistCache = PlaylistCache(appContext)
    val diagnosticsStore = DiagnosticsStore(appContext)

    /**
     * The HTTP client the playlist downloads and the channels share. Built on first use, which is a
     * download on a background thread: built with the container, it set up TLS on the main thread
     * in Application.onCreate, at every start, before FluxTV showed anything.
     */
    val httpClient: OkHttpClient by lazy { newHttpClient() }

    private val playlistFetcher = PlaylistFetcher(appContext) { httpClient }
    val channelRepository = ChannelRepository(playlistCache, playlistFetcher)
    val guideRepository = GuideRepository(appContext) { httpClient }
    val networkMonitor = NetworkMonitor(appContext)
    /** A TV and its remote, or a phone or tablet worked by touch (see [isTelevision]). */
    val isTelevision: Boolean = isTelevision(appContext)

    val playerController = FluxPlayerController(
        appContext,
        diagnosticsStore,
        networkMonitor,
        pauseOnNoisyAudio = !isTelevision
    ) { httpClient }

    /** FluxTV's own updates (Settings → Updates). Downloads keep to a share of the line the player measures. */
    val updateManager = UpdateManager(
        appContext,
        settingsRepository,
        diagnosticsStore,
        { httpClient },
        isTelevision,
        lineBitsPerSecond = { playerController.diagnostics.value.bandwidthBps },
        playingWithScreenOff = { playerController.playingWithScreenOff.value }
    )
}
