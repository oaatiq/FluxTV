package com.fluxtv.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fluxtv.app.AppContainer
import com.fluxtv.app.FluxTvApplication
import com.fluxtv.app.data.guide.Guide
import com.fluxtv.app.data.guide.GuideOutcome
import com.fluxtv.app.data.guide.GuideRepository
import com.fluxtv.app.data.model.Channel
import com.fluxtv.app.data.model.DiagnosticsEvent
import com.fluxtv.app.data.repository.RefreshOutcome
import com.fluxtv.app.data.settings.DEFAULT_PLAYLIST_NAME
import com.fluxtv.app.data.settings.FluxSettings
import com.fluxtv.app.data.settings.SavedPlaylist
import com.fluxtv.app.data.settings.newPlaylistId
import com.fluxtv.app.playback.FluxPlayerController
import com.fluxtv.app.playback.NetworkStatus
import com.fluxtv.app.playback.PlaybackPhase
import com.fluxtv.app.playback.PlaybackStatus
import com.fluxtv.app.playback.streamRequestFor
import com.fluxtv.app.setup.AddStatus
import com.fluxtv.app.setup.PhoneSetupServer
import com.fluxtv.app.setup.localNetworkAddress
import com.fluxtv.app.setup.tvDisplayName
import com.fluxtv.app.work.PlaylistRefreshWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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

/**
 * The channels Easy Mode steps through: the favorites in favorites-only mode, unless the playlist in
 * use has none. Favorites belong to their playlist, and a playlist just put in use with none of its
 * own would otherwise leave nothing on screen at all.
 */
private fun lineupOf(channels: List<Channel>, favorites: Set<String>, favoritesOnly: Boolean): List<Channel> =
    if (!favoritesOnly) channels else channels.filter { it.stableId in favorites }.ifEmpty { channels }

/**
 * The programme guides to load ([sources]: none, for no guide), the playlist they are for, and its
 * [channels]: compared by identity, as a playlist refresh hands over a new list.
 */
private class GuideRequest(val sources: List<String>, val playlistId: String?, val channels: List<Channel>) {
    fun sameAs(other: GuideRequest): Boolean =
        sources == other.sources && playlistId == other.playlistId && channels === other.channels
}

/**
 * The phone setup session, while Settings shows its QR code. [address] is what the code holds: null
 * while it starts, and when the TV has no network address or no port. [ended]: the session is over
 * (time ran out, or FluxTV left the screen) and the phone page no longer answers.
 */
data class PhoneSetup(
    val address: String? = null,
    val tvName: String,
    val tvCode: String,
    val starting: Boolean = true,
    val ended: Boolean = false
)

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
    val lastRefreshMessage: String? = null,
    /** A playlist is set but none could be loaded, and none was saved before: FluxTV keeps trying. */
    val firstLoadFailed: Boolean = false,
    /** How adding the last playlist went (Settings, and the phone page). */
    val addStatus: AddStatus? = null,
    val phoneSetup: PhoneSetup? = null
) {
    /** Channels the Viewer may reach in Easy Mode (favorites-only aware), ordered by number. */
    val lineup: List<Channel>
        get() = lineupOf(channels, favorites, favoritesOnly)

    /** Category (playlist group) of the channel being watched, if it has one. */
    val currentGroup: String?
        get() = currentChannel?.group?.takeIf { it.isNotBlank() }

    /**
     * Category the channel list opens on: Favorites in favorites-only mode (if this playlist has any),
     * else the watched channel's.
     */
    val browseStartKey: String
        get() = if (favoritesOnly && channels.any { it.stableId in favorites }) BROWSE_FAVORITES
        else currentGroup ?: BROWSE_ALL
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

    /**
     * The programme guide, for the banner's Now / Next. Like the diagnostics, it is not copied into
     * [UiState]: the banner and Settings collect it themselves.
     */
    val guide: StateFlow<Guide> get() = container.guideRepository.guide
    val guideLoading: StateFlow<Boolean> get() = container.guideRepository.loading
    val guideProblem: StateFlow<String?> get() = container.guideRepository.problem

    /** The programme guides the playlist in use names, for Settings to say whether it names any. */
    val playlistGuideUrls: StateFlow<List<String>> get() = container.channelRepository.guideUrls

    private var bannerJob: Job? = null
    private var guideJob: Job? = null
    private var firstLoadJob: Job? = null
    private var lastChannelJob: Job? = null
    private var numberJob: Job? = null
    private var transientJob: Job? = null
    private var currentChannelId: String? = null
    private var previousChannelId: String? = null
    private var started = false
    private var resumed = false
    private var videoSurfaceReady = false
    private var retuneWhenOnScreen = false

    /** Set while a playlist is being added, so a phone cannot send a second one meanwhile. */
    private val adding = AtomicBoolean(false)
    private var phoneServer: PhoneSetupServer? = null
    private var phoneSessionJob: Job? = null

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
                container.playerController.setUserAgent(s.effectiveUserAgent)
                container.playerController.setEvenVolume(s.evenVolume)
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
            withContext(Dispatchers.IO) { container.channelRepository.loadFromCache(s.activePlaylistId) }
            // The saved guide is read alongside: the banner of the first channel can do without it.
            launch { container.guideRepository.loadFromCache(s.activePlaylistId) }
            keepGuideCurrent()

            val playlist = s.activePlaylist?.takeIf { s.isConfigured }
            if (playlist == null) {
                // First run: an administrator must add a playlist before there is anything to watch.
                _state.update { it.copy(mode = AppMode.ADMIN) }
                return@launch
            }

            val startedFromCache = container.channelRepository.channels.value.isNotEmpty()
            var firstLoad: RefreshOutcome? = null
            if (!startedFromCache) {
                _state.update { it.copy(startupMessage = "Preparing television…") }
                // A first download can take minutes over a slow line. It is waited for a bounded time
                // only (REQ-START-006/007); then Easy Mode opens, with Settings reachable, while it
                // carries on, and the channel is tuned when it lands. Awaiting it, rather than the
                // download itself, is what lets the wait end on time: the download cannot be cut short.
                val loading = async { container.channelRepository.refresh(playlist, s.effectiveUserAgent) }
                firstLoad = withTimeoutOrNull(FIRST_LOAD_WAIT_MS) { loading.await() }
                if (firstLoad == null) {
                    _state.update { it.copy(mode = AppMode.EASY, isRefreshing = true) }
                    firstLoad = loading.await()
                    _state.update { it.copy(isRefreshing = false) }
                }
            }

            val sorted = withContext(Dispatchers.Default) { container.channelRepository.channels.value.sortedBy { it.number } }

            // Settings may have been opened during a long first load; it is left open.
            _state.update { if (it.mode == AppMode.STARTING) it.copy(mode = AppMode.EASY) else it }
            if (sorted.isEmpty()) {
                // Nothing saved from before, and the download failed. Settings says why.
                firstLoad?.let { outcome -> _state.update { it.copy(lastRefreshMessage = describe(outcome)) } }
                recoverFirstLoad()
                return@launch
            }
            tuneStartChannel(s, sorted)

            // Started from the cache: pick up provider updates in the background. After a first load
            // the playlist has just been fetched, and is not fetched twice.
            if (startedFromCache) launch { refreshAfterStart(s.refreshIntervalHours) }
        }
    }

    /**
     * Tunes the channel FluxTV starts on: the one last watched in the playlist in use, else the first
     * of the lineup. Each playlist remembers its own.
     */
    private fun tuneStartChannel(s: FluxSettings, sorted: List<Channel>) {
        val lineup = lineupOf(sorted, s.favorites, s.favoritesOnly)
        if (lineup.isEmpty()) return
        val target = lineup.firstOrNull { it.stableId == s.lastChannelStableId } ?: lineup.first()
        if (resumed && _state.value.mode == AppMode.EASY) tuneTo(target) else tuneWhenBack(target)
    }

    /**
     * A playlist is set, but none could be loaded and none was saved before: typically the TV started
     * FluxTV before its network was up. Easy Mode used to say "add your playlist", though one was set,
     * and nothing tried again until the periodic job, hours later. Now it says FluxTV is trying, tries
     * again as soon as the network is back and at growing intervals meanwhile, and tunes in once a
     * playlist has loaded, whichever way it came (these tries, the periodic job, or Settings).
     */
    private fun recoverFirstLoad() {
        if (firstLoadJob?.isActive == true) return
        firstLoadJob = viewModelScope.launch {
            _state.update { it.copy(firstLoadFailed = true) }
            val retries = launch {
                var waitMs = FIRST_LOAD_RETRY_MS
                while (true) {
                    awaitRetry(waitMs)
                    waitMs = (waitMs * 2).coerceAtMost(FIRST_LOAD_RETRY_MAX_MS)
                    // The playlist in use, then: never another one in its place (REQ-NET, Easy Mode
                    // protection), however long this lasts.
                    val s = container.settingsRepository.settings.first()
                    if (s.isConfigured) s.activePlaylist?.let { container.channelRepository.refresh(it, s.effectiveUserAgent) }
                }
            }
            val loaded = _state.first { it.channels.isNotEmpty() }.channels
            retries.cancel()
            _state.update {
                it.copy(
                    firstLoadFailed = false,
                    // The failure Settings has been showing is over, whichever way the playlist came.
                    lastRefreshMessage = if (it.isRefreshing) it.lastRefreshMessage
                    else describe(RefreshOutcome.Success(loaded.size))
                )
            }
            // Leaving Settings tunes a channel itself if the playlist came in while it was open.
            if (_state.value.currentChannel == null) {
                tuneStartChannel(container.settingsRepository.settings.first(), loaded)
            }
        }
    }

    /** Waits [ms] before the next try, or less if the network comes back meanwhile. */
    private suspend fun awaitRetry(ms: Long) {
        val network = container.networkMonitor.status
        val cameBack = withTimeoutOrNull(ms) {
            network.first { it == NetworkStatus.UNAVAILABLE }
            network.first { it == NetworkStatus.AVAILABLE }
        }
        // A network that has only just connected can need a moment more before addresses resolve.
        if (cameBack != null) delay(NETWORK_SETTLE_MS)
    }

    /**
     * The start-up refresh. It no longer runs the moment the first channel is tuned: the download and
     * the parse took the network and the CPU from that channel's start on a weak box. A cache younger
     * than the refresh interval is left to the periodic job; an older one is refreshed once the
     * channel plays (or has had a minute to), and a little after.
     */
    private suspend fun refreshAfterStart(intervalHours: Int) {
        val ageMs = System.currentTimeMillis() - container.channelRepository.lastUpdatedMs.value
        // A TV's clock can be wrong until it has synced after power-on: an age below zero is unknown.
        if (ageMs in 0 until intervalHours * HOUR_MS) return
        withTimeoutOrNull(FIRST_PLAY_WAIT_MS) {
            container.playerController.status.first { it.phase == PlaybackPhase.PLAYING }
        }
        delay(STARTUP_REFRESH_DELAY_MS)
        // Read again: Settings may have changed the playlist's link meanwhile, or put another in use.
        val settings = container.settingsRepository.settings.first()
        val playlist = settings.activePlaylist ?: return
        container.channelRepository.refresh(playlist, settings.effectiveUserAgent)
    }

    // ---------- programme guide ----------

    /**
     * Keeps the programme guide of the playlist in use loaded: the one Settings sets for it, else the
     * ones the playlist names. It starts once the first channel plays (or has had a minute to), so a
     * large guide does not hold up the picture, loads the guide when it is missing, made for another
     * playlist or link, or half a day old, and looks again every hour. A guide that would not load is
     * tried again then too.
     *
     * A playlist switch hands over its channels and its guide links one after the other, so for a
     * moment the new playlist looks as if it had the old one's guide. A guide download cannot be
     * stopped once it runs, so a change is only acted on once nothing else has changed for a moment.
     */
    private fun keepGuideCurrent() {
        if (guideJob != null) return
        guideJob = viewModelScope.launch {
            withTimeoutOrNull(FIRST_PLAY_WAIT_MS) {
                container.playerController.status.first { it.phase == PlaybackPhase.PLAYING }
            }
            delay(GUIDE_START_DELAY_MS)
            val repository = container.channelRepository
            combine(repository.guideUrls, repository.channels, container.settingsRepository.settings) { fromPlaylist, channels, s ->
                // The playlist the channels are from, which during a switch can be ahead of Settings.
                val id = repository.playlistId
                val set = s.playlists.firstOrNull { it.id == id }?.guideUrl.orEmpty()
                GuideRequest(if (set.isNotBlank()) listOf(set) else fromPlaylist, id, channels)
            }
                .distinctUntilChanged { old, new -> old.sameAs(new) }
                .collectLatest { request ->
                    delay(GUIDE_SETTLE_MS)
                    while (true) {
                        updateGuide(request)
                        delay(GUIDE_CHECK_MS)
                    }
                }
        }
    }

    private suspend fun updateGuide(request: GuideRequest) {
        val guides = container.guideRepository
        if (request.sources.isEmpty()) {
            if (guides.guide.value.updatedAtMs != 0L) guides.clear()
            return
        }
        val channels = request.channels
        if (channels.isEmpty()) return
        if (guides.isCurrent(request.sources, request.playlistId, GuideRepository.MAX_AGE_MS)) {
            // Channels the playlist gained since the guide was fetched have no programmes in it. A
            // guide fetched within the hour is left as it is, so that a playlist that keeps changing
            // does not have the guide fetched over and over; the hourly look comes back to it.
            val age = System.currentTimeMillis() - guides.guide.value.updatedAtMs
            if (age < GUIDE_CHECK_MS || guides.covers(channels)) return
        }
        val outcome = guides.refresh(request.sources, request.playlistId, channels, _state.value.settings.effectiveUserAgent)
        // Links are not logged: a guide's link can carry the provider's user name and password.
        val message = when (outcome) {
            is GuideOutcome.Loaded ->
                "Programme guide loaded: ${outcome.channelCount} channels" +
                    if (outcome.brokenOff) " (the guide broke off part-way)" else ""
            is GuideOutcome.Failed -> "Could not load the programme guide: ${outcome.reason}"
        }
        container.diagnosticsStore.append(DiagnosticsEvent(System.currentTimeMillis(), null, "guide", message))
    }

    // ---------- tuning ----------

    fun tuneTo(channel: Channel) {
        if (currentChannelId != null && currentChannelId != channel.stableId) previousChannelId = currentChannelId
        currentChannelId = channel.stableId
        numberJob?.cancel()
        _state.update {
            it.copy(
                currentChannel = channel,
                overlay = EasyOverlay.NONE,
                numberEntry = "",
                transientMessage = null
            )
        }
        container.playerController.playChannel(channel)
        saveLastChannel(LAST_CHANNEL_SAVE_DELAY_MS)
        showBanner(channel)
    }

    /**
     * The channel banner, for a while: longer when it has the guide's Now / Next to read, which takes
     * longer than a channel's name. Whether it has them is asked when it would go, not when it comes:
     * just after start-up the saved guide can arrive while the first banner is on screen.
     */
    private fun showBanner(channel: Channel) {
        _state.update { it.copy(showInfoBanner = true) }
        bannerJob?.cancel()
        bannerJob = viewModelScope.launch {
            delay(BANNER_MS)
            val hasGuide = container.guideRepository.guide.value.nowAndNext(channel, System.currentTimeMillis()) != null
            if (hasGuide) delay(BANNER_WITH_GUIDE_MS - BANNER_MS)
            _state.update { it.copy(showInfoBanner = false) }
        }
    }

    /**
     * The remote's Info or Guide key: the banner of the channel on screen, with what is on now and
     * next; pressed again while it shows, it goes.
     */
    fun toggleChannelInfo() {
        val st = _state.value
        val channel = st.currentChannel ?: return
        if (st.overlay != EasyOverlay.NONE) return
        if (st.showInfoBanner) {
            bannerJob?.cancel()
            _state.update { it.copy(showInfoBanner = false) }
        } else {
            showBanner(channel)
        }
    }

    /**
     * Remembers the current channel for the next start, after [delayMs]. Saved straight away on every
     * zap, each step through the channels rewrote (and synced) the settings file and sent the
     * settings round the app again; a channel only zapped past is not worth either.
     */
    private fun saveLastChannel(delayMs: Long) {
        val id = currentChannelId ?: return
        // Kept with the playlist the channel is from, even if another is put in use meanwhile.
        val playlistId = container.channelRepository.playlistId ?: return
        lastChannelJob?.cancel()
        lastChannelJob = viewModelScope.launch {
            delay(delayMs)
            container.settingsRepository.setLastChannel(playlistId, id)
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
            // Choosing the channel already on screen is a way to see what is on it.
            showBanner(channel)
        } else {
            tuneTo(channel)
        }
    }

    fun channelUp() = step(1)
    fun channelDown() = step(-1)

    /** The remote's last-channel key: back to the channel watched before this one, and again forth. */
    fun previousChannel() {
        val id = previousChannelId ?: return
        _state.value.channels.firstOrNull { it.stableId == id }?.let(::tuneTo)
    }

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

    /** Favorites belong to the playlist the channel is from. */
    fun toggleFavorite(channel: Channel) {
        val playlistId = container.channelRepository.playlistId ?: return
        viewModelScope.launch { container.settingsRepository.toggleFavorite(playlistId, channel.stableId) }
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
        stopPhoneSetup()
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

    // ---------- playlists (Settings → Playlists) ----------

    /** A screen of Settings → Playlists has opened: what the last one said is not carried over. */
    fun clearPlaylistMessages() = _state.update { st ->
        st.copy(lastRefreshMessage = null, addStatus = st.addStatus.takeIf { it == AddStatus.Checking })
    }

    /** A link (or a file path) typed on the TV. */
    fun addTypedPlaylist(link: String, name: String, type: String) {
        startAdd(link.trim(), name, type, fromPhone = false)
    }

    /**
     * Checks [link] and saves it as a new playlist. Called on the phone page's thread too: false when
     * another link is still being checked. The result goes to [UiState.addStatus], which the phone
     * reads, and [AddStatus.Checking] is there before this returns, for the phone's next request.
     */
    private fun startAdd(link: String, name: String, type: String, fromPhone: Boolean): Boolean {
        if (!adding.compareAndSet(false, true)) return false
        _state.update { it.copy(addStatus = AddStatus.Checking) }
        viewModelScope.launch {
            try {
                val result = addPlaylist(link, name, type, fromPhone)
                _state.update { it.copy(addStatus = result) }
            } finally {
                adding.set(false)
            }
        }
        return true
    }

    /**
     * A playlist is saved only once it has loaded, so a link that does not work changes nothing. It is
     * not put in use either, unless it is the first: then there is nothing it could replace, and TV
     * starts on it. Replacing a playlist in use takes "Use this playlist".
     */
    private suspend fun addPlaylist(link: String, name: String, type: String, fromPhone: Boolean): AddStatus {
        if (link.isEmpty()) return AddStatus.Failed("Enter the playlist link first.")
        if (fromPhone && !link.startsWith("http://", ignoreCase = true) && !link.startsWith("https://", ignoreCase = true)) {
            return AddStatus.Failed("That is not a playlist link: a playlist link starts with http:// or https://.")
        }
        val s = container.settingsRepository.settings.first()
        s.playlists.firstOrNull { it.url == link }?.let { return AddStatus.AlreadySaved(it.id, it.name) }

        val playlist = SavedPlaylist(
            id = newPlaylistId(),
            name = name.trim().ifBlank { defaultName(link, s.playlists) },
            url = link,
            type = type
        )
        val first = s.activePlaylist == null
        val outcome = if (first) container.channelRepository.activate(playlist, userAgent = s.effectiveUserAgent)
        else container.channelRepository.check(link, type, s.effectiveUserAgent)
        if (outcome !is RefreshOutcome.Success) {
            val problem = describeProblem(outcome as RefreshOutcome.Failed)
            log("Could not add “${playlist.name}”${if (fromPhone) " from a phone" else ""}: ${outcome.reason}")
            return AddStatus.Failed(problem)
        }
        container.settingsRepository.addPlaylist(playlist, activate = first)
        log(
            "Added “${playlist.name}”${if (fromPhone) " from a phone" else ""} (${outcome.count} channels)" +
                if (first) ", now in use" else ""
        )
        if (first) {
            _state.update { it.copy(firstLoadFailed = false, lastRefreshMessage = null) }
            tuneActivePlaylist()
        }
        return AddStatus.Added(playlist.id, playlist.name, outcome.count, inUse = first)
    }

    /** The provider's address ("provider.tv"), which tells playlists apart better than a number. */
    private fun defaultName(link: String, saved: List<SavedPlaylist>): String {
        val host = runCatching { Uri.parse(link).host }.getOrNull()?.removePrefix("www.")
        val base = host?.takeIf { it.isNotBlank() } ?: DEFAULT_PLAYLIST_NAME
        if (saved.none { it.name == base }) return base
        var n = 2
        while (saved.any { it.name == "$base ($n)" }) n++
        return "$base ($n)"
    }

    /**
     * "Use this playlist": it is loaded first, and only once it has loaded does it replace the one in
     * use, so a playlist that will not load leaves TV as it was. Back in Easy Mode, TV resumes this
     * playlist's own last channel, else its first.
     */
    fun usePlaylist(id: String) {
        val s = _state.value.settings
        val playlist = s.playlists.firstOrNull { it.id == id } ?: return
        // Busy, here, before anything is launched: OK pressed twice must not switch twice.
        if (_state.value.isRefreshing || playlist.id == s.activePlaylistId) return
        _state.update { it.copy(isRefreshing = true, lastRefreshMessage = null) }
        viewModelScope.launch {
            val outcome = container.channelRepository.activate(playlist, userAgent = s.effectiveUserAgent)
            val message = when (outcome) {
                is RefreshOutcome.Success -> {
                    container.settingsRepository.setActivePlaylist(playlist.id)
                    log("Now using “${playlist.name}” (${outcome.count} channels)")
                    tuneActivePlaylist()
                    "Now using “${playlist.name}”: ${outcome.count} channels."
                }
                is RefreshOutcome.Failed -> {
                    log("Could not switch to “${playlist.name}”: ${outcome.reason}")
                    describeProblem(outcome) + (s.activePlaylist?.let { " Still using “${it.name}”." } ?: "")
                }
            }
            _state.update { it.copy(isRefreshing = false, lastRefreshMessage = message) }
        }
    }

    /** Chooses the channel to resume once Settings is left: the one last watched in the playlist in use. */
    private suspend fun tuneActivePlaylist() {
        val s = container.settingsRepository.settings.first()
        val sorted = withContext(Dispatchers.Default) { container.channelRepository.channels.value.sortedBy { it.number } }
        tuneStartChannel(s, sorted)
    }

    fun renamePlaylist(id: String, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch { container.settingsRepository.renamePlaylist(id, clean) }
    }

    /**
     * A new link for a saved playlist, kept only once it has loaded. For the playlist in use, its
     * channels are replaced there and then; another one is only checked.
     */
    fun editPlaylistLink(id: String, link: String, type: String) {
        val newLink = link.trim()
        val s = _state.value.settings
        val playlist = s.playlists.firstOrNull { it.id == id } ?: return
        if (newLink.isEmpty() || _state.value.isRefreshing) return
        if (newLink == playlist.url && type == playlist.type) return
        s.playlists.firstOrNull { it.id != id && it.url == newLink }?.let { other ->
            _state.update { it.copy(lastRefreshMessage = "This link is already saved, as “${other.name}”.") }
            return
        }
        _state.update { it.copy(isRefreshing = true, lastRefreshMessage = null) }
        viewModelScope.launch {
            val inUse = id == s.activePlaylistId
            val edited = playlist.copy(url = newLink, type = type)
            // The same provider's list at a new link: its channels keep their numbers.
            val outcome = if (inUse) container.channelRepository.activate(edited, keepNumbers = true, userAgent = s.effectiveUserAgent)
            else container.channelRepository.check(newLink, type, s.effectiveUserAgent)
            val message = when (outcome) {
                is RefreshOutcome.Success -> {
                    container.settingsRepository.setPlaylistLink(id, newLink, type)
                    log("New link for “${playlist.name}” (${outcome.count} channels)")
                    "Link saved: ${outcome.count} channels."
                }
                is RefreshOutcome.Failed -> describeProblem(outcome) + " The link was not changed."
            }
            _state.update { it.copy(isRefreshing = false, lastRefreshMessage = message) }
        }
    }

    /** Deletes a saved playlist; the one in use cannot be, until another is put in use. */
    fun deletePlaylist(id: String) {
        viewModelScope.launch {
            val name = container.settingsRepository.settings.first().playlists.firstOrNull { it.id == id }?.name
            if (container.settingsRepository.deletePlaylist(id)) {
                log("Deleted “$name”")
                _state.update { it.copy(lastRefreshMessage = "Deleted “$name”.") }
            }
        }
    }

    fun refreshNow() {
        val playlist = _state.value.settings.activePlaylist ?: return
        if (_state.value.isRefreshing) return
        viewModelScope.launch {
            _state.update { it.copy(isRefreshing = true, lastRefreshMessage = null) }
            val outcome = container.channelRepository.refresh(playlist, _state.value.settings.effectiveUserAgent)
            _state.update { it.copy(isRefreshing = false, lastRefreshMessage = describe(outcome)) }
            recoverIfNothingLoaded(outcome)
        }
    }

    // ---------- phone setup ----------

    /**
     * Opens the setup session behind the QR code: the phone page answers until [stopPhoneSetup], for
     * at most [PHONE_SETUP_MS], and while FluxTV stays on screen.
     */
    fun startPhoneSetup() {
        stopPhoneSetup()
        val app = getApplication<Application>()
        val tvName = tvDisplayName(app)
        val server = PhoneSetupServer(
            tvName = tvName,
            status = { _state.value.addStatus },
            submit = { link, name -> startAdd(link, name, "url", fromPhone = true) }
        )
        phoneServer = server
        _state.update {
            it.copy(
                phoneSetup = PhoneSetup(tvName = tvName, tvCode = server.tvCode),
                addStatus = it.addStatus.takeIf { status -> status == AddStatus.Checking }
            )
        }
        phoneSessionJob = viewModelScope.launch {
            val address = withContext(Dispatchers.IO) {
                val host = localNetworkAddress(app)
                val port = if (host != null) server.start() else null
                if (host != null && port != null) "http://$host:$port/${server.token}" else null
            }
            _state.update { it.copy(phoneSetup = it.phoneSetup?.copy(address = address, starting = false)) }
            if (address == null) {
                server.stop()
                phoneServer = null
                return@launch
            }
            log("Phone setup started")
            delay(PHONE_SETUP_MS)
            endPhoneSetup()
        }
    }

    /** The session is over, but its screen stays, saying so, with a way to start a new one. */
    private fun endPhoneSetup() {
        val server = phoneServer ?: return
        phoneSessionJob?.cancel()
        server.stop()
        phoneServer = null
        _state.update { it.copy(phoneSetup = it.phoneSetup?.copy(ended = true)) }
        log("Phone setup ended")
    }

    /** The QR screen has been left: the phone page stops answering. */
    fun stopPhoneSetup() {
        phoneSessionJob?.cancel()
        phoneSessionJob = null
        phoneServer?.stop()
        phoneServer = null
        _state.update { it.copy(phoneSetup = null) }
    }

    private fun log(message: String) {
        // Playlist names only: a link can hold the provider's user name and password.
        container.diagnosticsStore.append(DiagnosticsEvent(System.currentTimeMillis(), null, "playlist", message))
    }

    /** A playlist entered in Settings that would not load, with no channels to fall back on, is retried. */
    private fun recoverIfNothingLoaded(outcome: RefreshOutcome) {
        if (outcome is RefreshOutcome.Failed && container.channelRepository.channels.value.isEmpty()) {
            recoverFirstLoad()
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

    /** The player name sent to the provider; blank goes back to FluxTV's own. */
    fun setUserAgent(value: String) {
        viewModelScope.launch { container.settingsRepository.setUserAgent(value) }
    }

    fun setTextSize(percent: Int) {
        viewModelScope.launch { container.settingsRepository.setTextSize(percent) }
    }

    fun setEvenVolume(value: Boolean) {
        viewModelScope.launch { container.settingsRepository.setEvenVolume(value) }
    }

    fun setProgrammeInList(value: Boolean) {
        viewModelScope.launch { container.settingsRepository.setProgrammeInList(value) }
    }

    /** A programme guide link for a saved playlist; blank goes back to the ones the playlist names. */
    fun setGuideUrl(id: String, url: String) {
        viewModelScope.launch { container.settingsRepository.setGuideUrl(id, url) }
    }

    fun refreshDiagnostics() = container.playerController.refreshDiagnostics()

    fun clearDiagnostics() = container.playerController.clearDiagnostics()

    /** Asks for [channel] the way the player does: same address, player name and headers. */
    suspend fun checkReachable(channel: Channel): Boolean = withContext(Dispatchers.IO) {
        try {
            val client = container.httpClient.newBuilder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .callTimeout(10, TimeUnit.SECONDS)
                .build()
            val stream = streamRequestFor(channel, _state.value.settings.effectiveUserAgent)
            val request = Request.Builder()
                .url(stream.address)
                .apply { stream.headers.forEach { (name, value) -> header(name, value) } }
                .header("User-Agent", stream.userAgent)
                .header("Range", "bytes=0-1")
                .build()
            client.newCall(request).execute().use { it.code < 400 }
        } catch (_: Exception) {
            false
        }
    }

    /** Why a playlist would not load, in plain words, for Settings and the phone page alike. */
    private fun describeProblem(outcome: RefreshOutcome.Failed): String = when {
        outcome.tooLarge -> "The playlist is too large for this TV's memory."
        outcome.noChannels -> "The link opened, but there are no channels in it. Check that it is an M3U playlist link."
        outcome.httpCode == 401 || outcome.httpCode == 403 ->
            "The provider refused the link. Check the user name and password in it, and that the subscription is active."
        outcome.httpCode == 404 || outcome.httpCode == 410 ->
            "The provider has no playlist at this link. Check that the link is complete."
        outcome.httpCode != null ->
            "The provider's server did not send the playlist (error ${outcome.httpCode}). Try again later."
        else -> "The TV could not open the link. Check that it is complete, and that the TV is connected to the internet."
    }

    private fun describe(outcome: RefreshOutcome): String = when (outcome) {
        is RefreshOutcome.Success -> "Loaded ${outcome.count} channels."
        is RefreshOutcome.Failed -> when {
            outcome.tooLarge ->
                "The playlist is too large for this TV's memory." +
                    if (outcome.keptExisting) " Keeping the existing channels." else ""
            outcome.keptExisting -> "Could not update. Keeping the existing channels."
            else -> "Could not load the playlist. Check the address and your connection."
        }
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
        // A setup session lasts only while its screen is on the TV.
        endPhoneSetup()
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
        stopPhoneSetup()
        bannerJob?.cancel()
        guideJob?.cancel()
        numberJob?.cancel()
        transientJob?.cancel()
        container.networkMonitor.stop()
        container.playerController.release()
    }

    companion object {
        /** How long a first playlist download holds the start-up screen before Easy Mode opens. */
        private const val FIRST_LOAD_WAIT_MS = 20_000L

        /** After a failed first load: the gap before the first retry, doubled each time up to the max. */
        private const val FIRST_LOAD_RETRY_MS = 15_000L
        private const val FIRST_LOAD_RETRY_MAX_MS = 15 * 60_000L

        /** How long a network that has just come back gets before the playlist is tried again. */
        private const val NETWORK_SETTLE_MS = 3_000L

        /** The start-up refresh waits this long for the first channel to play, then this much more. */
        private const val FIRST_PLAY_WAIT_MS = 60_000L
        private const val STARTUP_REFRESH_DELAY_MS = 30_000L

        private const val HOUR_MS = 3_600_000L

        /** How long the channel banner shows after a channel change, and with the guide's Now / Next. */
        private const val BANNER_MS = 4_500L
        private const val BANNER_WITH_GUIDE_MS = 7_000L

        /** The guide is first looked at this long after the first channel plays, then every hour. */
        private const val GUIDE_START_DELAY_MS = 20_000L
        private const val GUIDE_CHECK_MS = HOUR_MS

        /** How long the playlist and the guide link have to stay as they are before a guide is fetched. */
        private const val GUIDE_SETTLE_MS = 2_000L

        /** How long a channel has to stay on before it is remembered for the next start. */
        private const val LAST_CHANNEL_SAVE_DELAY_MS = 3_000L

        /** How long the phone page answers after the QR code is shown. */
        private const val PHONE_SETUP_MS = 15 * 60_000L

        fun factory(app: FluxTvApplication) = viewModelFactory {
            initializer { AppViewModel(app, app.container) }
        }
    }
}
