package com.fluxtv.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fluxtv.app.AppContainer
import com.fluxtv.app.FluxTvApplication
import com.fluxtv.app.data.model.Channel
import com.fluxtv.app.data.model.DiagnosticsEvent
import com.fluxtv.app.data.model.DiagnosticsSnapshot
import com.fluxtv.app.data.repository.RefreshOutcome
import com.fluxtv.app.data.settings.FluxSettings
import com.fluxtv.app.playback.FluxPlayerController
import com.fluxtv.app.playback.PlaybackPhase
import com.fluxtv.app.playback.PlaybackStatus
import com.fluxtv.app.work.PlaylistRefreshWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

enum class AppMode { STARTING, EASY, ADMIN }
enum class EasyOverlay { NONE, CHANNEL_LIST, NUMBER_ENTRY }
/** CATEGORIES is the index of every category; the others list channels. */
enum class ListTab { CATEGORIES, CATEGORY, ALL, FAVORITES }

data class CategoryInfo(val name: String, val count: Int)

data class UiState(
    val mode: AppMode = AppMode.STARTING,
    val overlay: EasyOverlay = EasyOverlay.NONE,
    val channels: List<Channel> = emptyList(),
    val favorites: Set<String> = emptySet(),
    val favoritesOnly: Boolean = false,
    val currentChannel: Channel? = null,
    val playback: PlaybackStatus = PlaybackStatus(),
    val showInfoBanner: Boolean = false,
    val numberEntry: String = "",
    val transientMessage: String? = null,
    val channelListTab: ListTab = ListTab.ALL,
    /** Category picked in the Categories view; null means the one being watched. */
    val selectedGroup: String? = null,
    val settings: FluxSettings = FluxSettings(),
    val startupMessage: String = "Starting television…",
    val diagnostics: DiagnosticsSnapshot = DiagnosticsSnapshot(),
    val diagnosticsEvents: List<DiagnosticsEvent> = emptyList(),
    val lastUpdatedMs: Long = 0L,
    val isRefreshing: Boolean = false,
    val lastRefreshMessage: String? = null
) {
    /** Channels the Viewer may reach in Easy Mode (favorites-only aware), ordered by number. */
    val lineup: List<Channel>
        get() = if (favoritesOnly) channels.filter { it.stableId in favorites } else channels

    val favoriteChannels: List<Channel>
        get() = channels.filter { it.stableId in favorites }

    /** Category (playlist group) of the channel being watched, if it has one. */
    val currentGroup: String?
        get() = currentChannel?.group?.takeIf { it.isNotBlank() }

    /** Category shown in the list's category tab: the picked one, else the one being watched. */
    val categoryName: String?
        get() = selectedGroup ?: currentGroup

    val categoryChannels: List<Channel>
        get() = categoryName?.let { group -> channels.filter { it.group == group } } ?: emptyList()

    /** Every category with its channel count, in playlist order. */
    val categories: List<CategoryInfo>
        get() = channels
            .mapNotNull { it.group?.takeIf { group -> group.isNotBlank() } }
            .groupingBy { it }
            .eachCount()
            .map { (name, count) -> CategoryInfo(name, count) }
}

/**
 * Single source of UI truth and the Easy Mode state machine (§25).
 *
 * STARTING -> restore last channel -> WATCHING; OK opens the CHANNEL LIST and Back opens it on the
 * current channel's category; recovery returns to WATCHING. Everything the Viewer can do routes through here so each remote button maps to one
 * consistent action (REQ-REM-001/002).
 */
class AppViewModel(
    application: Application,
    private val container: AppContainer
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _state.asStateFlow()

    val controller: FluxPlayerController get() = container.playerController

    private var bannerJob: Job? = null
    private var numberJob: Job? = null
    private var transientJob: Job? = null
    private var currentChannelId: String? = null
    private var started = false

    init {
        container.networkMonitor.start()
        viewModelScope.launch {
            container.channelRepository.channels.collect { chs ->
                _state.update { it.copy(channels = chs.sortedBy { c -> c.number }) }
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
        viewModelScope.launch {
            container.playerController.diagnostics.collect { d -> _state.update { it.copy(diagnostics = d) } }
        }
        viewModelScope.launch {
            container.playerController.events.collect { e -> _state.update { it.copy(diagnosticsEvents = e) } }
        }
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

            if (container.channelRepository.channels.value.isEmpty()) {
                _state.update { it.copy(startupMessage = "Preparing television…") }
                // Bounded wait so we never hang on a loading screen (REQ-START-006/007).
                withTimeoutOrNull(20_000) {
                    container.channelRepository.refresh(s.playlistUrl!!, s.playlistType, s.playlistName)
                }
            }

            val sorted = container.channelRepository.channels.value.sortedBy { it.number }
            val lineup = if (s.favoritesOnly) sorted.filter { it.stableId in s.favorites } else sorted

            _state.update { it.copy(mode = AppMode.EASY) }
            if (lineup.isNotEmpty()) {
                val target = lineup.firstOrNull { it.stableId == s.lastChannelStableId } ?: lineup.first()
                tuneTo(target)
            }

            // Fire-and-forget refresh to pick up provider updates when we started from cache.
            if (container.channelRepository.channels.value.isNotEmpty()) {
                launch { container.channelRepository.refresh(s.playlistUrl!!, s.playlistType, s.playlistName) }
            }
        }
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
        viewModelScope.launch { container.settingsRepository.setLastChannel(channel.stableId) }
        bannerJob?.cancel()
        bannerJob = viewModelScope.launch {
            delay(4500)
            _state.update { it.copy(showInfoBanner = false) }
        }
    }

    /** Picked in the channel list: the channel already on screen just closes the list instead of restarting. */
    fun selectChannel(channel: Channel) {
        val phase = _state.value.playback.phase
        val showing = phase == PlaybackPhase.PLAYING || phase == PlaybackPhase.BUFFERING || phase == PlaybackPhase.PREPARING
        if (channel.stableId == currentChannelId && showing) closeOverlay() else tuneTo(channel)
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

    fun openChannelList() {
        if (_state.value.channels.isEmpty()) return
        val tab = if (_state.value.favoritesOnly) ListTab.FAVORITES else ListTab.ALL
        _state.update { it.copy(overlay = EasyOverlay.CHANNEL_LIST, channelListTab = tab, selectedGroup = null) }
    }

    /** Back while watching: the first step back, the list scoped to the channel being watched's category. */
    fun openCategoryList() {
        val state = _state.value
        if (state.channels.isEmpty()) return
        if (state.currentGroup == null) {
            openChannelList()
            return
        }
        _state.update {
            it.copy(overlay = EasyOverlay.CHANNEL_LIST, channelListTab = ListTab.CATEGORY, selectedGroup = null)
        }
    }

    /** Picked from the Categories view: show that category's channels. */
    fun selectCategory(name: String) {
        _state.update { it.copy(selectedGroup = name, channelListTab = ListTab.CATEGORY) }
    }

    fun closeOverlay() {
        numberJob?.cancel()
        _state.update { it.copy(overlay = EasyOverlay.NONE, numberEntry = "") }
    }

    fun setTab(tab: ListTab) = _state.update { it.copy(channelListTab = tab) }

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
        container.playerController.onResume()
        if (_state.value.currentChannel == null) {
            val lineup = _state.value.lineup
            if (lineup.isNotEmpty()) tuneTo(lineup.first())
        }
    }

    fun savePlaylist(url: String, name: String, type: String) {
        viewModelScope.launch {
            val cleanName = name.trim().ifBlank { "My Playlist" }
            container.settingsRepository.setPlaylist(url.trim(), cleanName, type)
            _state.update { it.copy(isRefreshing = true, lastRefreshMessage = null) }
            val outcome = container.channelRepository.refresh(url.trim(), type, cleanName)
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

    fun clearDiagnostics() {
        viewModelScope.launch(Dispatchers.IO) { container.playerController.clearDiagnostics() }
    }

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
        if (_state.value.mode == AppMode.EASY) container.playerController.onResume()
    }

    fun onActivityPause() {
        container.playerController.onPause()
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
        fun factory(app: FluxTvApplication) = viewModelFactory {
            initializer { AppViewModel(app, app.container) }
        }
    }
}
