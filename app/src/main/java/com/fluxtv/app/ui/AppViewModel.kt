package com.fluxtv.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fluxtv.app.AppContainer
import com.fluxtv.app.FluxTvApplication
import com.fluxtv.app.data.model.Channel
import com.fluxtv.app.data.repository.RefreshOutcome
import com.fluxtv.app.data.settings.FluxSettings
import com.fluxtv.app.playback.FluxPlayerController
import com.fluxtv.app.playback.PlaybackPhase
import com.fluxtv.app.playback.PlaybackStatus
import com.fluxtv.app.work.PlaylistRefreshWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

enum class AppMode { STARTING, EASY, ADMIN }
enum class EasyOverlay { NONE, CHANNEL_LIST, NUMBER_ENTRY }
/** Which column of the two-pane channel list has the D-pad focus. */
enum class BrowsePane { CATEGORIES, CHANNELS }

/** Keys of the two built-in entries at the top of the category column; real categories use their own name. */
const val BROWSE_FAVORITES = "\u0000favorites"
const val BROWSE_ALL = "\u0000all"

/**
 * The real categories (playlist groups) of [sorted], in the order they first appear, each with its
 * channels. Worked out once per playlist change, off the main thread, rather than by the channel
 * list each time it opens.
 */
private fun groupsOf(sorted: List<Channel>): Map<String, List<Channel>> =
    sorted.filter { !it.group.isNullOrBlank() }.groupBy { it.group!! }

data class UiState(
    val mode: AppMode = AppMode.STARTING,
    val overlay: EasyOverlay = EasyOverlay.NONE,
    val channels: List<Channel> = emptyList(),
    /** The playlist's categories in first-appearance order, each with its channels (see [groupsOf]). */
    val groups: Map<String, List<Channel>> = emptyMap(),
    val favorites: Set<String> = emptySet(),
    val favoritesOnly: Boolean = false,
    val currentChannel: Channel? = null,
    val playback: PlaybackStatus = PlaybackStatus(),
    val showInfoBanner: Boolean = false,
    val numberEntry: String = "",
    val transientMessage: String? = null,
    val browsePane: BrowsePane = BrowsePane.CHANNELS,
    val settings: FluxSettings = FluxSettings(),
    val startupMessage: String = "Starting television…",
    val lastUpdatedMs: Long = 0L,
    val isRefreshing: Boolean = false,
    val lastRefreshMessage: String? = null
) {
    /** Channels the Viewer may reach in Easy Mode (favorites-only aware), ordered by number. */
    val lineup: List<Channel>
        get() = if (favoritesOnly) channels.filter { it.stableId in favorites } else channels

    /** Category (playlist group) of the channel being watched, if it has one. */
    val currentGroup: String?
        get() = currentChannel?.group?.takeIf { it.isNotBlank() }

    /** Category the channel list opens on: Favorites in favorites-only mode, else the watched channel's. */
    val browseStartKey: String
        get() = if (favoritesOnly) BROWSE_FAVORITES else currentGroup ?: BROWSE_ALL
}

/**
 * Single source of UI truth and the Easy Mode state machine (§25).
 *
 * STARTING -> restore last channel -> WATCHING; OK or Back opens the two-pane CHANNEL LIST on the
 * current channel's category; recovery returns to WATCHING. Everything the Viewer can do routes
 * through here so each remote button maps to one consistent action (REQ-REM-001/002).
 */
class AppViewModel(
    application: Application,
    private val container: AppContainer
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _state.asStateFlow()

    val controller: FluxPlayerController get() = container.playerController

    private var bannerJob: Job? = null
    private var lastChannelJob: Job? = null
    private var numberJob: Job? = null
    private var transientJob: Job? = null
    private var currentChannelId: String? = null
    private var started = false
    private var resumed = false
    private var videoSurfaceReady = false
    private var retuneWhenOnScreen = false

    init {
        container.networkMonitor.start()
        viewModelScope.launch {
            container.channelRepository.channels
                // Sorting and grouping tens of thousands of channels is kept off the main thread.
                .map { chs -> chs.sortedBy { c -> c.number }.let { sorted -> sorted to groupsOf(sorted) } }
                .flowOn(Dispatchers.Default)
                .collect { (sorted, groups) ->
                    // The channel on screen keeps up with the playlist: a refresh can move it to a new
                    // address, which retries and re-tunes should then use.
                    val current = _state.value.currentChannel?.let { cur -> sorted.firstOrNull { it.stableId == cur.stableId } }
                    _state.update { it.copy(channels = sorted, groups = groups, currentChannel = current ?: it.currentChannel) }
                    current?.let { container.playerController.updateChannel(it) }
                }
        }
        viewModelScope.launch {
            container.channelRepository.lastUpdatedMs.collect { t -> _state.update { it.copy(lastUpdatedMs = t) } }
        }
        viewModelScope.launch {
            container.settingsRepository.settings.collect { s ->
                _state.update { it.copy(settings = s, favorites = s.favorites, favoritesOnly = s.favoritesOnly) }
                container.playerController.setMaxVideoHeight(s.maxVideoHeight)
            }
        }
        viewModelScope.launch {
            container.playerController.status.collect { st -> _state.update { it.copy(playback = st) } }
        }
        // The diagnostics figures and history are not copied in here: they change many times per
        // channel change, and every change would hand Easy Mode a new state for data only the
        // Diagnostics screen shows. That screen collects them from the player itself.
        start()
    }

    // ---------- startup (§4) ----------

    private fun start() {
        if (started) return
        started = true
        viewModelScope.launch {
            val s = container.settingsRepository.settings.first()
            _state.update {
                it.copy(
                    settings = s,
                    favorites = s.favorites,
                    favoritesOnly = s.favoritesOnly,
                    startupMessage = "Starting television…"
                )
            }
            withContext(Dispatchers.IO) { container.channelRepository.loadFromCache() }

            if (!s.isConfigured) {
                // First run: an administrator must add a playlist before there is anything to watch.
                _state.update { it.copy(mode = AppMode.ADMIN) }
                return@launch
            }

            val startedFromCache = container.channelRepository.channels.value.isNotEmpty()
            if (!startedFromCache) {
                _state.update { it.copy(startupMessage = "Preparing television…") }
                // A first download can take minutes over a slow line. It is waited for a bounded time
                // only (REQ-START-006/007); then Easy Mode opens, with Settings reachable, while it
                // carries on, and the channel is tuned when it lands. Awaiting it, rather than the
                // download itself, is what lets the wait end on time: the download cannot be cut short.
                val firstLoad = async {
                    container.channelRepository.refresh(s.playlistUrl!!, s.playlistType, s.playlistName)
                }
                if (withTimeoutOrNull(FIRST_LOAD_WAIT_MS) { firstLoad.await() } == null) {
                    _state.update { it.copy(mode = AppMode.EASY, isRefreshing = true) }
                    firstLoad.await()
                    _state.update { it.copy(isRefreshing = false) }
                }
            }

            val sorted = withContext(Dispatchers.Default) { container.channelRepository.channels.value.sortedBy { it.number } }
            val lineup = if (s.favoritesOnly) sorted.filter { it.stableId in s.favorites } else sorted

            // Settings may have been opened during a long first load; it is left open.
            _state.update { if (it.mode == AppMode.STARTING) it.copy(mode = AppMode.EASY) else it }
            if (lineup.isNotEmpty()) {
                val target = lineup.firstOrNull { it.stableId == s.lastChannelStableId } ?: lineup.first()
                if (resumed && _state.value.mode == AppMode.EASY) tuneTo(target) else tuneWhenBack(target)
            }

            // Started from the cache: pick up provider updates in the background. After a first load
            // the playlist has just been fetched, and is not fetched twice.
            if (startedFromCache) launch { refreshAfterStart(s) }
        }
    }

    /**
     * The start-up refresh. It no longer runs the moment the first channel is tuned: the download and
     * the parse took the network and the CPU from that channel's start on a weak box. A cache younger
     * than the refresh interval is left to the periodic job; an older one is refreshed once the
     * channel plays (or has had a minute to), and a little after.
     */
    private suspend fun refreshAfterStart(s: FluxSettings) {
        val ageMs = System.currentTimeMillis() - container.channelRepository.lastUpdatedMs.value
        // A TV's clock can be wrong until it has synced after power-on: an age below zero is unknown.
        if (ageMs in 0 until s.refreshIntervalHours * HOUR_MS) return
        withTimeoutOrNull(FIRST_PLAY_WAIT_MS) {
            container.playerController.status.first { it.phase == PlaybackPhase.PLAYING }
        }
        delay(STARTUP_REFRESH_DELAY_MS)
        container.channelRepository.refresh(s.playlistUrl!!, s.playlistType, s.playlistName)
    }

    // ---------- tuning ----------

    fun tuneTo(channel: Channel) {
        currentChannelId = channel.stableId
        numberJob?.cancel()
        _state.update {
            it.copy(
                currentChannel = channel,
                overlay = EasyOverlay.NONE,
                numberEntry = "",
                showInfoBanner = true,
                transientMessage = null
            )
        }
        container.playerController.playChannel(channel)
        saveLastChannel(LAST_CHANNEL_SAVE_DELAY_MS)
        bannerJob?.cancel()
        bannerJob = viewModelScope.launch {
            delay(4500)
            _state.update { it.copy(showInfoBanner = false) }
        }
    }

    /**
     * Remembers the current channel for the next start, after [delayMs]. Saved straight away on every
     * zap, each step through the channels rewrote (and synced) the settings file and sent the
     * settings round the app again; a channel only zapped past is not worth either.
     */
    private fun saveLastChannel(delayMs: Long) {
        val id = currentChannelId ?: return
        lastChannelJob?.cancel()
        lastChannelJob = viewModelScope.launch {
            delay(delayMs)
            container.settingsRepository.setLastChannel(id)
        }
    }

    /**
     * Picked in the channel list: the channel already on screen just closes the list instead of
     * restarting, and plays on if something had paused it (e.g. another app taking the sound).
     */
    fun selectChannel(channel: Channel) {
        val phase = _state.value.playback.phase
        val showing = phase == PlaybackPhase.PLAYING || phase == PlaybackPhase.BUFFERING || phase == PlaybackPhase.PREPARING
        if (channel.stableId == currentChannelId && showing) {
            closeOverlay()
            container.playerController.onResume()
        } else {
            tuneTo(channel)
        }
    }

    fun channelUp() = step(1)
    fun channelDown() = step(-1)

    private fun step(direction: Int) {
        val lineup = _state.value.lineup
        if (lineup.isEmpty()) return
        val index = lineup.indexOfFirst { it.stableId == currentChannelId }
        val next = if (index < 0) lineup.first()
        else lineup[((index + direction) % lineup.size + lineup.size) % lineup.size]
        tuneTo(next)
    }

    // ---------- overlays ----------

    /** OK or Back while watching: the two-pane list, on the watched channel's category, focus on its channels. */
    fun openChannelList() {
        if (_state.value.channels.isEmpty()) return
        _state.update { it.copy(overlay = EasyOverlay.CHANNEL_LIST, browsePane = BrowsePane.CHANNELS) }
    }

    fun setBrowsePane(pane: BrowsePane) = _state.update { it.copy(browsePane = pane) }

    fun closeOverlay() {
        numberJob?.cancel()
        _state.update { it.copy(overlay = EasyOverlay.NONE, numberEntry = "") }
    }

    // ---------- number entry (REQ-REM-005) ----------

    fun onDigit(digit: Int) {
        val current = _state.value
        val base = if (current.overlay == EasyOverlay.NUMBER_ENTRY) current.numberEntry else ""
        val entry = (base + digit.toString()).take(4)
        _state.update { it.copy(overlay = EasyOverlay.NUMBER_ENTRY, numberEntry = entry, showInfoBanner = false) }
        numberJob?.cancel()
        numberJob = viewModelScope.launch {
            delay(2500)
            confirmNumberEntry()
        }
    }

    fun confirmNumberEntry() {
        numberJob?.cancel()
        val number = _state.value.numberEntry.toIntOrNull()
        if (number == null) {
            closeOverlay()
            return
        }
        val target = _state.value.lineup.firstOrNull { it.number == number }
            ?: _state.value.channels.firstOrNull { it.number == number }
        if (target != null) {
            tuneTo(target)
        } else {
            _state.update { it.copy(overlay = EasyOverlay.NONE, numberEntry = "") }
            showTransient("No channel $number")
        }
    }

    private fun showTransient(message: String) {
        _state.update { it.copy(transientMessage = message) }
        transientJob?.cancel()
        transientJob = viewModelScope.launch {
            delay(2500)
            _state.update { it.copy(transientMessage = null) }
        }
    }

    // ---------- favorites ----------

    fun toggleFavorite(channel: Channel) {
        viewModelScope.launch { container.settingsRepository.toggleFavorite(channel.stableId) }
    }

    // ---------- recovery (REQ-REC-005) ----------

    fun retryCurrent() = container.playerController.retryNow()

    // ---------- admin ----------

    fun requestAdmin() {
        numberJob?.cancel()
        _state.update { it.copy(mode = AppMode.ADMIN, overlay = EasyOverlay.NONE) }
        container.playerController.onPause()
    }

    fun exitAdmin() {
        _state.update { it.copy(mode = AppMode.EASY) }
        if (retuneIfReturning()) return
        // Settings took the video surface away, so the channel is re-tuned once the new one is in
        // place; resuming the paused stream before that only played a moment of stale sound.
        if (!retuneWhenOnScreen) container.playerController.onResume()
        if (_state.value.currentChannel == null) {
            val lineup = _state.value.lineup
            if (lineup.isNotEmpty()) tuneTo(lineup.first())
        }
    }

    fun savePlaylist(url: String, name: String, type: String) {
        viewModelScope.launch {
            val cleanName = name.trim().ifBlank { "My Playlist" }
            // Another provider's channels are numbered from scratch, not after the old list's last one.
            val sameSource = url.trim() == _state.value.settings.playlistUrl
            container.settingsRepository.setPlaylist(url.trim(), cleanName, type)
            _state.update { it.copy(isRefreshing = true, lastRefreshMessage = null) }
            val outcome = container.channelRepository.refresh(url.trim(), type, cleanName, keepNumbers = sameSource)
            _state.update { it.copy(isRefreshing = false, lastRefreshMessage = describe(outcome)) }
        }
    }

    fun refreshNow() {
        val s = _state.value.settings
        if (!s.isConfigured) return
        viewModelScope.launch {
            _state.update { it.copy(isRefreshing = true, lastRefreshMessage = null) }
            val outcome = container.channelRepository.refresh(s.playlistUrl!!, s.playlistType, s.playlistName)
            _state.update { it.copy(isRefreshing = false, lastRefreshMessage = describe(outcome)) }
        }
    }

    fun setFavoritesOnly(value: Boolean) {
        viewModelScope.launch { container.settingsRepository.setFavoritesOnly(value) }
    }

    fun setMaxVideoHeight(height: Int) {
        viewModelScope.launch { container.settingsRepository.setMaxVideoHeight(height) }
    }

    fun setAdminPin(pin: String) {
        viewModelScope.launch { container.settingsRepository.setAdminPin(pin.trim()) }
    }

    fun setRefreshHours(hours: Int) {
        viewModelScope.launch {
            container.settingsRepository.setRefreshHours(hours)
            PlaylistRefreshWorker.schedule(getApplication(), hours)
        }
    }

    fun setFrameRateMatching(value: Boolean) {
        viewModelScope.launch { container.settingsRepository.setFrameRateMatching(value) }
    }

    fun setStartOnBoot(value: Boolean) {
        viewModelScope.launch { container.settingsRepository.setStartOnBoot(value) }
    }

    fun refreshDiagnostics() = container.playerController.refreshDiagnostics()

    fun clearDiagnostics() = container.playerController.clearDiagnostics()

    suspend fun checkReachable(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .callTimeout(10, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "FluxTV/1.0")
                .header("Range", "bytes=0-1")
                .build()
            client.newCall(request).execute().use { it.code < 400 }
        } catch (_: Exception) {
            false
        }
    }

    private fun describe(outcome: RefreshOutcome): String = when (outcome) {
        is RefreshOutcome.Success -> "Loaded ${outcome.count} channels."
        is RefreshOutcome.Failed ->
            if (outcome.keptExisting) "Could not update. Keeping the existing channels."
            else "Could not load the playlist. Check the address and your connection."
    }

    // ---------- lifecycle ----------

    fun onActivityResume() {
        resumed = true
        if (retuneIfReturning()) return
        if (_state.value.mode == AppMode.EASY) container.playerController.onResume()
    }

    fun onActivityPause() {
        resumed = false
        container.playerController.onPause()
    }

    /**
     * FluxTV has left the screen: the TV went into standby, or another app took over. Playback is
     * stopped (not just paused) because the video surface goes away with it, and the channel is
     * re-tuned on the way back instead of resumed, rather than handing the decoder that was playing
     * a surface built after the standby — see [FluxPlayerController.onStop].
     */
    fun onActivityStop() {
        resumed = false
        if (_state.value.currentChannel != null) retuneWhenOnScreen = true
        container.playerController.onStop()
        // Standby can be followed by the power going off: a channel still waiting to be saved is
        // saved now.
        if (lastChannelJob?.isActive == true) saveLastChannel(0)
    }

    /** The video surface has been (re)created, so the player has somewhere to put the picture. */
    fun onVideoSurfaceReady() {
        videoSurfaceReady = true
        retuneIfReturning()
    }

    /** The video surface has gone: standby, or Easy Mode making way for the Settings screen. */
    fun onVideoSurfaceLost() {
        videoSurfaceReady = false
        // The decoder in use was built around this surface and cannot be handed the replacement, so
        // the channel is re-tuned rather than resumed once a new surface arrives.
        if (_state.value.currentChannel != null) retuneWhenOnScreen = true
    }

    /**
     * Re-tunes after FluxTV comes back, but only once it is both resumed and holding a live video
     * surface: tuning any earlier would build the decoder around the surface that is on its way out,
     * which is exactly the black picture this avoids. Either event can be the last to arrive, so
     * both call in here, as does leaving Settings — the surface is new on the way back from there too.
     */
    private fun retuneIfReturning(): Boolean {
        if (!retuneWhenOnScreen || !resumed || !videoSurfaceReady) return false
        if (_state.value.mode != AppMode.EASY) return false
        val channel = channelToResume() ?: return false
        retuneWhenOnScreen = false
        container.playerController.logReturnToScreen()
        tuneTo(channel)
        return true
    }

    /**
     * The channel to put back on screen: the current one as the playlist now has it, or the first of
     * the lineup when the playlist no longer has it at all (another provider was loaded in Settings).
     */
    private fun channelToResume(): Channel? {
        val st = _state.value
        val current = st.currentChannel ?: return null
        if (st.channels.isEmpty() || st.channels.any { it.stableId == current.stableId }) return current
        return st.lineup.firstOrNull() ?: current
    }

    /**
     * Chooses [channel] without playing it yet, because FluxTV is not on screen (covered or left while
     * it was still starting) or Settings is open. [retuneIfReturning] tunes it once FluxTV is back in
     * Easy Mode with a video surface. Playing it now would put the sound behind another app, and the
     * picture would come back on a decoder that never had a surface: the black-picture case.
     */
    private fun tuneWhenBack(channel: Channel) {
        currentChannelId = channel.stableId
        _state.update { it.copy(currentChannel = channel) }
        retuneWhenOnScreen = true
    }

    override fun onCleared() {
        super.onCleared()
        bannerJob?.cancel()
        numberJob?.cancel()
        transientJob?.cancel()
        container.networkMonitor.stop()
        container.playerController.release()
    }

    companion object {
        /** How long a first playlist download holds the start-up screen before Easy Mode opens. */
        private const val FIRST_LOAD_WAIT_MS = 20_000L

        /** The start-up refresh waits this long for the first channel to play, then this much more. */
        private const val FIRST_PLAY_WAIT_MS = 60_000L
        private const val STARTUP_REFRESH_DELAY_MS = 30_000L

        private const val HOUR_MS = 3_600_000L

        /** How long a channel has to stay on before it is remembered for the next start. */
        private const val LAST_CHANNEL_SAVE_DELAY_MS = 3_000L

        fun factory(app: FluxTvApplication) = viewModelFactory {
            initializer { AppViewModel(app, app.container) }
        }
    }
}
