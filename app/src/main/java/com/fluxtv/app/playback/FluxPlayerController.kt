package com.fluxtv.app.playback

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import com.fluxtv.app.BootTrace
import com.fluxtv.app.data.cache.DiagnosticsStore
import com.fluxtv.app.data.model.Channel
import com.fluxtv.app.data.model.DiagnosticsEvent
import com.fluxtv.app.data.model.DiagnosticsSnapshot
import com.fluxtv.app.data.remote.DEFAULT_USER_AGENT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.Locale

/**
 * Owns a single, reused ExoPlayer and implements the spec's reliability philosophy:
 * "an IPTV stream may fail; FluxTV itself should not" (§16).
 *
 * Detect -> Recover -> Inform only if necessary (§17): errors and prolonged buffering trigger
 * bounded automatic recovery; the Viewer only sees a prompt once recovery is genuinely exhausted.
 * One ExoPlayer instance is reused across channels so repeated switching never exhausts resources
 * (REQ-REL-005, REQ-REL-007). All callbacks are handled on the main thread.
 */
class FluxPlayerController(
    private val context: Context,
    private val diagnosticsStore: DiagnosticsStore,
    private val networkMonitor: NetworkMonitor,
    /**
     * Pause when the sound loses its output, such as headphones being unplugged: what a phone
     * expects. Never on a TV, where Android counts losing HDMI as the same thing (see PlayerFactory).
     */
    private val pauseOnNoisyAudio: Boolean = false,
    private val httpClient: () -> OkHttpClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var bundle: PlayerFactory.Bundle? = null
    val player: ExoPlayer? get() = bundle?.player

    private var currentChannel: Channel? = null
    private var maxVideoHeight = 0
    private var defaultUserAgent = DEFAULT_USER_AGENT
    private var evenVolume = true

    /**
     * Whether FluxTV is on screen to be watched: set by tuning and [onResume], cleared by [onPause]
     * and [onStop]. Automatic recovery only starts playback while it is set, so a retry or a network
     * coming back never plays a channel over Settings, another app, or a TV in standby. Also set while
     * a phone plays on with its screen off ([continueWithScreenOff]), which is listened to.
     */
    private var onScreen = false

    /**
     * The video tracks are switched off: a phone plays the channel's sound alone while its screen is
     * off, and decoding a picture nobody sees would only spend the battery. Until the next tune,
     * which builds the picture again on the new surface.
     */
    private var soundOnly = false

    private val recovery = RecoveryPolicy()
    private var lastLiveRejoinMs = -LIVE_REJOIN_MIN_GAP_MS
    private var prepareStartMs = 0L
    private var bufferingEvents = 0
    private var recoveryCount = 0
    private var droppedFramesTotal = 0
    private var lastVideoDecoder: String? = null

    /** Whether this start of the channel has had its one retry after a decoder error. */
    private var decoderRetried = false

    /** Whether this start of the channel has had its one try as HLS, see [tryAsHls]. */
    private var hlsTried = false

    /** Whether this start of the channel has opened its address as it is, see [tryAsPlain]. */
    private var plainTried = false

    /** Whether the channel is loaded as HLS, whatever its address looks like. */
    private var loadAsHls = false

    /** Addresses that played as HLS: opened that way from the start, until FluxTV is closed. */
    private val knownHls = HashSet<String>()

    /** ".m3u" addresses that turned out not to be HLS: opened as they are from the start. */
    private val knownPlain = HashSet<String>()

    private var retryJob: Job? = null
    private var bufferingWatchdog: Job? = null
    private var slowRetryJob: Job? = null
    private var stabilityJob: Job? = null
    private var frozenPictureWatch: Job? = null

    /** How often this start of the channel has been restarted for a frozen picture. */
    private var frozenRestarts = 0

    /**
     * Background healing once the quick retries are used up (see [scheduleHealRound]): the rounds
     * this start of the channel has had, and the failure last written to the history, so that one
     * repeated every round is written once.
     */
    private var healRound = 0
    private var lastHealFailure: String? = null

    /** When a playlist reload was last asked for, see [requestPlaylistReload]. */
    private var lastPlaylistReloadMs = -PLAYLIST_RELOAD_MIN_GAP_MS

    /**
     * When Android last reported the network gone, [NO_LOSS] while it reports one; and whether data
     * that came in for the channel since then, over a request made after it, proved that report
     * wrong (see [watchNetwork]).
     */
    private var networkLostAtMs = NO_LOSS
    private var networkLossDisproved = false

    /**
     * When the stall under way started, 0 when there is none; and when the noticeable stalls of the
     * channel on screen happened lately, for [PlaybackStatus.slowConnection] (see [noteStall]).
     */
    private var stallStartedMs = 0L
    private val recentStalls = ArrayDeque<Long>()
    private var slowConnectionJob: Job? = null

    private val _status = MutableStateFlow(PlaybackStatus())
    val status: StateFlow<PlaybackStatus> = _status.asStateFlow()

    private val _diagnostics = MutableStateFlow(DiagnosticsSnapshot())
    val diagnostics: StateFlow<DiagnosticsSnapshot> = _diagnostics.asStateFlow()

    /** The diagnostics history, including the lines the boot and wake receivers add themselves. */
    val events: StateFlow<List<DiagnosticsEvent>> = diagnosticsStore.events

    private val _videoFrameRate = MutableStateFlow(0f)
    val videoFrameRate: StateFlow<Float> = _videoFrameRate.asStateFlow()

    /**
     * The player the video view shows: none until the first tune, and a new one each time healing
     * rebuilds it ([rebuildPlayer]), which the view then binds to in place of the old one.
     */
    private val _activePlayer = MutableStateFlow<ExoPlayer?>(null)
    val activePlayer: StateFlow<ExoPlayer?> = _activePlayer.asStateFlow()

    /**
     * Emits when the channel on screen keeps being refused or not found. The provider may have moved
     * it to a new address or renewed the access token in it, which only a fresh copy of the playlist
     * has, so the app reloads the playlist in use; [updateChannel] then hands the new entry over.
     */
    private val _playlistReloadWanted = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val playlistReloadWanted: SharedFlow<Unit> = _playlistReloadWanted.asSharedFlow()

    /**
     * Emits when the channel plays its sound but not one frame of its picture has reached the
     * screen: the video view has no surface (Android never gave it one back after a standby), so a
     * new decoder alone cannot help. The app rebuilds the video view, and the channel is tuned again
     * on its new surface. At most once per tune, so a view that cannot be fixed this way never loops.
     */
    private val _noPictureOnScreen = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val noPictureOnScreen: SharedFlow<Unit> = _noPictureOnScreen.asSharedFlow()
    private var noPictureReported = false

    /**
     * Android paused the channel on its own: another app took the sound for good, or, on a phone,
     * the headphones were unplugged. Nothing resumes it by itself, so the phone layout offers Play:
     * retries and healing rounds reconnect the channel paused (see [load]). Cleared as soon as it
     * plays again, including by [onResume], a new channel and Try again.
     */
    private val _pausedBySystem = MutableStateFlow(false)
    val pausedBySystem: StateFlow<Boolean> = _pausedBySystem.asStateFlow()

    /**
     * A phone's screen went off with the channel playing, and the channel plays on, sound only
     * ([continueWithScreenOff]). Never on a TV. Cleared when FluxTV is back on screen, when the sound
     * is stopped ([endScreenOffPlayback]), and when Android pauses it for good.
     */
    private val _playingWithScreenOff = MutableStateFlow(false)
    val playingWithScreenOff: StateFlow<Boolean> = _playingWithScreenOff.asStateFlow()

    init {
        scope.launch { watchNetwork() }
    }

    // ---- public control ----

    /** The player name (User-Agent) for channels whose playlist entry names none (Preferences). */
    fun setUserAgent(value: String) {
        defaultUserAgent = value
    }

    /** Preferences → Even out the volume between channels. Takes effect at once, on the channel playing. */
    fun setEvenVolume(value: Boolean) {
        evenVolume = value
        bundle?.evenVolume?.leveler?.enabled = value
    }

    fun setMaxVideoHeight(height: Int) {
        maxVideoHeight = height
        val b = bundle ?: return
        b.trackSelector.setParameters(
            b.trackSelector.buildUponParameters()
                .apply { if (height > 0) setMaxVideoSize(Int.MAX_VALUE, height) else clearVideoSizeConstraints() }
                .build()
        )
    }

    fun playChannel(channel: Channel) {
        ensurePlayer()
        val p = bundle!!.player
        retryJob?.cancel()
        bufferingWatchdog?.cancel()
        slowRetryJob?.cancel()
        currentChannel = channel
        onScreen = true
        // A tune is FluxTV back on screen: the picture again, from the new decoder.
        _playingWithScreenOff.value = false
        setSoundOnly(p, false)
        recovery.reset()
        decoderRetried = false
        loadAsHls = firstTryAsHls(channel.url)
        hlsTried = loadAsHls
        plainTried = !loadAsHls
        frozenRestarts = 0
        noPictureReported = false
        frozenPictureWatch?.cancel()
        healRound = 0
        lastHealFailure = null
        stallStartedMs = 0L
        // Stalls and the picture belong to the channel they happened on; the network does not.
        recentStalls.clear()
        slowConnectionJob?.cancel()
        _status.value = _status.value.copy(slowConnection = false, pictureProblem = null)
        // Choosing a channel is the Viewer asking for it to play, whatever Android paused before.
        _pausedBySystem.value = false
        bufferingEvents = 0
        droppedFramesTotal = 0
        stabilityJob?.cancel()
        prepareStartMs = SystemClock.elapsedRealtime()

        // Reported before the player is handed the channel, so that a channel it cannot open at all
        // ends on the failure that load() reports rather than on "preparing".
        _diagnostics.value = DiagnosticsSnapshot(
            channelName = channel.name,
            channelUrl = channel.url,
            // The codec is usually reused across channels, so no "decoder initialized" event follows;
            // keep showing it until a new decoder replaces it.
            videoDecoder = lastVideoDecoder,
            hardwareDecoding = lastVideoDecoder?.let { !isSoftwareDecoderName(it) },
            playbackState = "PREPARING"
        )
        updateStatus(PlaybackPhase.PREPARING, message = null, prompt = false)
        logEvent("startup", "Preparing ${channel.name} (${channel.url})")

        bundle!!.channelSwitch.pending = true
        if (load(p, channel)) startBufferingWatchdog(START_TIMEOUT_MS)
    }

    /**
     * The playlist was refreshed. If [channel] is the one being played, retries and re-tunes use this
     * entry from now on: a provider can move a channel to a new address. Playback goes on as it is.
     * A channel being healed in the background that now has a new address tries it at once.
     */
    fun updateChannel(channel: Channel) {
        val previous = currentChannel ?: return
        if (previous.stableId != channel.stableId) return
        currentChannel = channel
        if (channel.url != previous.url && slowRetryJob?.isActive == true) {
            logEvent("recovery", "The reloaded playlist has a new address for ${channel.name}: trying it now")
            scheduleHealRound(delayMs = 0)
        }
    }

    /** User pressed "Try again" on the recovery prompt (REQ-REC-005). Resets the budget. */
    fun retryNow() {
        slowRetryJob?.cancel()
        recovery.reset()
        healRound = 0
        lastHealFailure = null
        decoderRetried = false
        hlsTried = loadAsHls
        plainTried = !loadAsHls
        _pausedBySystem.value = false
        updateStatus(PlaybackPhase.PREPARING, message = null, prompt = false)
        reprepareCurrent("manual retry")
    }

    fun onResume() {
        onScreen = true
        // Back on screen: the sound goes on, and the picture comes back with the re-tune that follows
        // once the new video surface is in place (AppViewModel.retuneIfReturning).
        _playingWithScreenOff.value = false
        val p = bundle?.player ?: return
        if (currentChannel == null) return
        p.play()
        // A stall the watchdog let pass while FluxTV was off screen gets its turn now.
        if (p.playbackState == Player.STATE_BUFFERING && bufferingWatchdog?.isActive != true) {
            startBufferingWatchdog()
        }
    }

    fun onPause() {
        onScreen = false
        bundle?.player?.pause()
    }

    /**
     * FluxTV is no longer on screen: the TV has gone into standby, or another app has taken over.
     * The video surface goes away with it, so playback is stopped rather than paused, which releases
     * the decoder.
     *
     * Keeping the decoder across a standby is what loses the picture on wake: handed the surface
     * the TV built on the way back, plenty of TV decoders accept it and then only ever produce
     * sound, which is the channel playing with a black screen. Which ones do that is device
     * specific — Media3 carries its own blocklist of them — so FluxTV does not rely on the swap
     * anywhere. A live stream is stale after a standby anyway, so the caller re-tunes from the live
     * edge once the new surface is in place, which also builds a fresh decoder for it.
     */
    fun onStop() {
        if (stopPlayback() && currentChannel != null) logEvent("standby", "FluxTV left the screen: playback stopped")
    }

    /** Stops the channel off screen; false if there was no player to stop. */
    private fun stopPlayback(): Boolean {
        onScreen = false
        _playingWithScreenOff.value = false
        val p = bundle?.player ?: return false
        retryJob?.cancel()
        bufferingWatchdog?.cancel()
        slowRetryJob?.cancel()
        stabilityJob?.cancel()
        frozenPictureWatch?.cancel()
        stallStartedMs = 0L
        p.stop()
        setSoundOnly(p, false)
        return true
    }

    /**
     * A phone's screen has gone off with the channel playing, and "Keep playing with the screen off"
     * is on: the channel plays on, sound only, instead of stopping as in [onStop]. Recovery keeps
     * working as on screen, so a channel that drops comes back by itself. FluxTV coming back on
     * screen re-tunes it as usual, with the picture. Only ever called on a phone (AppViewModel);
     * false when there is nothing to play on.
     */
    fun continueWithScreenOff(): Boolean {
        val p = bundle?.player ?: return false
        val channel = currentChannel ?: return false
        onScreen = true
        setSoundOnly(p, true)
        frozenPictureWatch?.cancel()
        _playingWithScreenOff.value = true
        // The activity's pause may have paused it on the way.
        p.play()
        if (p.playbackState == Player.STATE_BUFFERING && bufferingWatchdog?.isActive != true) startBufferingWatchdog()
        logEvent("standby", "The phone's screen went off: ${channel.name} plays on, sound only")
        return true
    }

    /**
     * Ends [continueWithScreenOff]: Stop on the phone's lock screen or notification, the phone unlocked
     * into another app, or Android pausing the sound for good. The channel stops as if FluxTV had
     * left the screen, and is tuned again when FluxTV is next on screen.
     */
    fun endScreenOffPlayback(reason: String) {
        if (!_playingWithScreenOff.value) return
        logEvent("standby", "$reason: playback stopped")
        stopPlayback()
    }

    private fun setSoundOnly(p: ExoPlayer, on: Boolean) {
        if (soundOnly == on) return
        soundOnly = on
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, on)
            .build()
    }

    fun release() {
        _playingWithScreenOff.value = false
        soundOnly = false
        retryJob?.cancel()
        bufferingWatchdog?.cancel()
        slowRetryJob?.cancel()
        frozenPictureWatch?.cancel()
        slowConnectionJob?.cancel()
        bundle?.player?.let {
            it.removeListener(playerListener)
            it.removeAnalyticsListener(analyticsListener)
            it.release()
        }
        stabilityJob?.cancel()
        bundle = null
        _activePlayer.value = null
        // The scope stays alive on purpose: this controller is a process-wide singleton that outlives
        // the activity (Exit closes the activity, the process may be reused), and recovery retries,
        // logging and the network-restore collector all run in it. The next playChannel() builds a
        // fresh player.
    }

    /**
     * [channel] has been chosen but waits for the video surface before it is played (see
     * AppViewModel.tuneTo): the screen shows the moving dots meanwhile, as for any start. Nothing
     * is played, and nothing that would retry the channel before keeps running and says otherwise.
     */
    fun showWaitingForPicture(channel: Channel) {
        retryJob?.cancel()
        bufferingWatchdog?.cancel()
        slowRetryJob?.cancel()
        frozenPictureWatch?.cancel()
        _pausedBySystem.value = false
        _status.value = _status.value.copy(
            phase = PlaybackPhase.PREPARING,
            channel = channel,
            userMessage = null,
            showRecoveryPrompt = false,
            problem = null,
            pictureProblem = null
        )
    }

    /** Recorded by the UI when FluxTV is back on screen and re-tunes to rebuild the decoder. */
    fun logReturnToScreen() {
        logEvent("standby", "Back on screen with a new video surface: re-tuning ${currentChannel?.name}")
    }

    fun clearDiagnostics() {
        diagnosticsStore.clear()
    }

    // ---- internals ----

    private fun ensurePlayer() {
        if (bundle != null) return
        val created = PlayerFactory.create(context, maxVideoHeight, pauseOnNoisyAudio, httpClient)
        created.evenVolume.leveler.enabled = evenVolume
        created.player.addListener(playerListener)
        created.player.addAnalyticsListener(analyticsListener)
        // A player rebuilt while a phone plays with its screen off stays sound only.
        if (soundOnly) {
            created.player.trackSelectionParameters = created.player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .build()
        }
        bundle = created
        _activePlayer.value = created.player
    }

    /**
     * Lets the player go and builds a new one in its place, which the video view binds to through
     * [activePlayer]. Nothing of the failed attempts carries over: not a decoder still held, nor a
     * player stuck in a call to a TV's media stack (releasing gives up on that after half a second).
     */
    private fun rebuildPlayer() {
        val old = bundle ?: return
        old.player.removeListener(playerListener)
        old.player.removeAnalyticsListener(analyticsListener)
        old.player.release()
        bundle = null
        ensurePlayer()
    }

    /**
     * Follows what Android says about the network. A network that comes back restarts a channel that
     * failed meanwhile ([onNetworkRestored]). One that goes is said on screen
     * ([PlaybackStatus.networkLost]) once it has been gone for [NETWORK_LOSS_GRACE_MS] — a Wi-Fi that
     * drops for a moment as it reconnects is not worth a word — and for as long as it stays gone,
     * from before the picture stops: a channel plays on for up to a minute from what it has
     * buffered, and the Viewer then already knows why it stops, and that it is not the TV.
     *
     * A TV can report no network wrongly. A stream load that started after the report and completed
     * proves the network works (an HLS or DASH channel makes one every few seconds), and keeps the
     * notice off until Android reports a new loss (see the analytics listener's onLoadCompleted).
     */
    private suspend fun watchNetwork() {
        networkMonitor.status.collectLatest { status ->
            if (status == NetworkStatus.AVAILABLE) {
                networkLostAtMs = NO_LOSS
                if (_status.value.networkLost) {
                    logEvent("network", "Network connection back")
                    setNetworkLost(false)
                }
                onNetworkRestored()
            } else {
                networkLostAtMs = SystemClock.elapsedRealtime()
                networkLossDisproved = false
                delay(NETWORK_LOSS_GRACE_MS)
                if (!networkLossDisproved) {
                    logEvent("network", "Android reports no network connection: said on screen until it is back")
                    setNetworkLost(true)
                }
            }
        }
    }

    private fun setNetworkLost(lost: Boolean) {
        if (_status.value.networkLost != lost) _status.value = _status.value.copy(networkLost = lost)
    }

    private fun onNetworkRestored() {
        // Off screen (Settings, another app, standby) nobody is watching, and the way back re-tunes or
        // retries anyway; re-preparing here started the channel's sound over whatever was on screen.
        if (!onScreen) return
        val phase = _status.value.phase
        if (currentChannel != null && (phase == PlaybackPhase.RECOVERING || phase == PlaybackPhase.FAILED)) {
            slowRetryJob?.cancel()
            recovery.reset()
            healRound = 0
            lastHealFailure = null
            updateStatus(PlaybackPhase.PREPARING, message = null, prompt = false)
            reprepareCurrent("network restored")
        }
    }

    /** [reason] is written to the history; null for a try that has written its own line. */
    private fun reprepareCurrent(reason: String?) {
        val p = bundle?.player ?: return
        val channel = currentChannel ?: return
        recoveryCount++
        prepareStartMs = SystemClock.elapsedRealtime()
        if (reason != null) logEvent("recovery", "Recovery attempt ${recovery.attempt} for ${channel.name}: $reason")
        if (load(p, channel)) startBufferingWatchdog(START_TIMEOUT_MS)
    }

    /**
     * Hands [channel] to the player. For a stream type this build has no module for (RTSP, Smooth
     * Streaming), Media3 throws straight out of setMediaItem, which used to close the whole app on a
     * channel change: such a channel is reported as one that cannot be played instead.
     */
    private fun load(p: ExoPlayer, channel: Channel): Boolean = try {
        // Set on every load, not only on a tune: a refresh can give the channel being retried new ones.
        val request = streamRequestFor(channel, defaultUserAgent)
        bundle?.httpDataSourceFactory?.apply {
            setUserAgent(request.userAgent)
            setDefaultRequestProperties(request.headers)
        }
        p.setMediaItem(
            MediaItem.Builder()
                .setUri(request.address)
                .apply { if (loadAsHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
                .setLiveConfiguration(STEADY_LIVE_SPEED)
                .build()
        )
        p.prepare()
        // A retry while FluxTV is off screen reconnects, and only plays once FluxTV is back. One
        // while Android has the channel paused (headphones unplugged, another app's sound) stays
        // paused: it used to start the sound again, out of the phone's speaker or over that app.
        p.playWhenReady = onScreen && !_pausedBySystem.value
        true
    } catch (e: RuntimeException) {
        // setMediaItem threw before replacing anything: cannotPlay() stops the previous channel rather
        // than leave it playing under this one's name.
        bundle?.channelSwitch?.pending = false
        cannotPlay("cannot open this stream type: $e")
        false
    }

    /** The channel cannot play on this TV at all: stop it and say so once, without retrying it. */
    private fun cannotPlay(reason: String) {
        retryJob?.cancel()
        bufferingWatchdog?.cancel()
        slowRetryJob?.cancel()
        stabilityJob?.cancel()
        frozenPictureWatch?.cancel()
        bundle?.player?.stop()
        logEvent("error", "Cannot play ${currentChannel?.name}: $reason")
        updateStatus(PlaybackPhase.FAILED, ErrorMapper.CANNOT_PLAY, prompt = true, problem = PlaybackProblem.CANNOT_PLAY)
    }

    /**
     * A decoder error gets one more try, a moment later and without a message, before the channel
     * is declared unplayable: a TV's hardware decoder can still be held by whatever used it last
     * (just after a boot or a wake, or a quick zap) and be free again a second later.
     */
    private fun retryDecoderOnce() {
        decoderRetried = true
        updateStatus(PlaybackPhase.RECOVERING, message = null, prompt = false)
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(DECODER_RETRY_DELAY_MS)
            reprepareCurrent("decoder error, trying once more")
        }
    }

    /**
     * Some IPTV addresses serve an HLS playlist without ".m3u8" in their path. The player then takes
     * the stream for a single file, finds no format it can read in it, and the channel never played,
     * however often it was retried. Such a channel gets one try as HLS, straight away and without
     * using the retry budget; one that plays that way is loaded as HLS until FluxTV is closed.
     */
    private fun tryAsHls(): Boolean {
        val channel = currentChannel ?: return false
        if (hlsTried || loadAsHls) return false
        // An address that does say what it is (.m3u8, .mpd) was opened as that already.
        if (Util.inferContentType(Uri.parse(streamAddress(channel.url))) != C.CONTENT_TYPE_OTHER) return false
        hlsTried = true
        loadAsHls = true
        logEvent("recovery", "Stream format of ${channel.name} not recognised: trying it as HLS")
        reprepareCurrent("trying the stream as HLS")
        return true
    }

    /**
     * Whether a channel's first try opens it as HLS: one that played that way before, and one whose
     * address ends in ".m3u", the older name of an HLS playlist. Media3 only takes ".m3u8" for HLS and
     * would open ".m3u" as a single file, which fails before [tryAsHls] gets its turn.
     */
    private fun firstTryAsHls(url: String): Boolean {
        if (url in knownHls) return true
        if (url in knownPlain) return false
        return streamAddress(url).substringBefore('?').substringBefore('#').endsWith(".m3u", ignoreCase = true)
    }

    /**
     * Opened as HLS on its first try, and the answer is not an HLS playlist: the address is opened as
     * it is, straight away and without a message, and from the start next time. One that has played
     * as HLS before stays HLS, and its retries go on that way.
     */
    private fun tryAsPlain(): Boolean {
        val channel = currentChannel ?: return false
        if (!loadAsHls || channel.url in knownHls) return false
        loadAsHls = false
        knownPlain.add(channel.url)
        if (plainTried) return false  // opened as it is already, this start: the error stands
        plainTried = true
        logEvent("recovery", "${channel.name} is not an HLS stream: opening its address as it is")
        reprepareCurrent("opening the address as it is")
        return true
    }

    /** The address answered with something that does not read as an HLS playlist at all. */
    private fun isNotHlsPlaylist(error: PlaybackException) =
        error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED

    /** [problem]: why, for the recovery prompt's heading if this was the last retry. */
    private fun scheduleRetry(message: String, reason: String, problem: PlaybackProblem) {
        if (!recovery.canRetry()) {
            failAndKeepTrying(message, reason, problem)
            return
        }
        val delayMs = recovery.nextDelayMs()
        // The problem goes with the message, for its icon: why, recognisable without reading.
        updateStatus(PlaybackPhase.RECOVERING, message, prompt = false, attempt = recovery.attempt, problem = problem)
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(delayMs)
            reprepareCurrent(reason)
        }
    }

    /**
     * The channel did not come back: the prompt says why (REQ-REC-005), and FluxTV goes on trying in
     * the background without the Viewer pressing anything, however long it takes (REQ-BUF-006,
     * REQ-REC-003). A channel the provider keeps refusing or no longer has gets the playlist
     * reloaded too, in case it moved.
     */
    private fun failAndKeepTrying(message: String, reason: String, problem: PlaybackProblem) {
        updateStatus(PlaybackPhase.FAILED, message, prompt = true, problem = problem)
        when {
            healRound == 0 -> logEvent("error", "Recovery exhausted for ${currentChannel?.name}: $reason")
            reason != lastHealFailure ->
                logEvent("error", "Still not playing ${currentChannel?.name} after $healRound background tries: $reason")
        }
        lastHealFailure = reason
        if (problem == PlaybackProblem.REFUSED || problem == PlaybackProblem.GONE) requestPlaylistReload()
        scheduleHealRound()
    }

    /**
     * One round of background healing, [delayMs] from now; a round that fails schedules the next.
     * Each round is one try, not another series of quick retries, so the prompt stays steady rather
     * than coming and going, and a provider that refuses the channel gets one connection every
     * [HEAL_ROUND_MS] (REQ-REC-007): a flood of reconnects can get a subscription blocked.
     *
     * Each round starts the channel afresh: new connections to the provider in place of ones that may
     * have died quietly, and a new player ([rebuildPlayer]). It tries whatever Android says about the
     * network: a TV that wrongly reports none would otherwise never heal, and a network that is
     * really down costs one failed connection per round. While FluxTV is off screen the round waits
     * for it to come back; leaving the screen for good (onStop) or a new tune cancels it.
     */
    private fun scheduleHealRound(delayMs: Long = HEAL_ROUND_MS) {
        slowRetryJob?.cancel()
        slowRetryJob = scope.launch {
            delay(delayMs)
            while (!onScreen) delay(HEAL_ROUND_MS)
            val channel = currentChannel ?: return@launch
            healRound++
            // Written for the first rounds and then every tenth, so a long outage does not push
            // everything else out of the history.
            if (healRound <= 3 || healRound % 10 == 0) {
                val network = if (networkMonitor.hasNetwork()) "" else " (Android reports no network: trying anyway)"
                logEvent("recovery", "Background try $healRound for ${channel.name}: new player and connections$network")
            }
            // Only connections lying idle are closed, never a playlist or guide download under way.
            withContext(Dispatchers.IO) { runCatching { httpClient().connectionPool.evictAll() } }
            rebuildPlayer()
            // The video view binds to the new player on its next frame. Started before that, the
            // decoder could get its surface late, which some TV decoders answer with sound and no
            // picture (see onStop); so the round waits for the surface, a moment at most.
            bundle?.player?.let { p -> withTimeoutOrNull(SURFACE_WAIT_MS) { while (p.surfaceSize.width <= 0) delay(20) } }
            reprepareCurrent(reason = null)
        }
    }

    /**
     * Asks the app to reload the playlist ([playlistReloadWanted]), at most once every
     * [PLAYLIST_RELOAD_MIN_GAP_MS]: a large playlist is a heavy download for a TV and the provider.
     */
    private fun requestPlaylistReload() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPlaylistReloadMs < PLAYLIST_RELOAD_MIN_GAP_MS) return
        lastPlaylistReloadMs = now
        logEvent("recovery", "${currentChannel?.name} keeps being refused or not found: reloading the playlist in case it moved")
        _playlistReloadWanted.tryEmit(Unit)
    }

    /** Recorded by the app once the reload [playlistReloadWanted] asked for has finished. */
    fun logPlaylistReload(result: String) {
        logEvent("recovery", result)
    }

    /**
     * Re-prepares a channel that is still buffering after [timeoutMs]. Every prepare arms it, not only
     * the player reporting that it started buffering: Media3 reports no change when a channel is
     * loaded while the player is already buffering (zapping on before the last channel started, or
     * this watchdog's own retry), which left a channel that never started with no recovery at all.
     */
    private fun startBufferingWatchdog(timeoutMs: Long = STALL_TIMEOUT_MS) {
        bufferingWatchdog?.cancel()
        bufferingWatchdog = scope.launch {
            delay(timeoutMs)
            val p = bundle?.player ?: return@launch
            // Off screen nobody is watching; onResume() arms it again if the stall is still there.
            if (p.playbackState == Player.STATE_BUFFERING && onScreen) {
                if (stallStartedMs > 0L) {
                    stallStartedMs = 0L
                    noteStall()
                }
                logEvent("buffering", "Prolonged buffering on ${currentChannel?.name}")
                scheduleRetry(ErrorMapper.CONNECTION_INTERRUPTED, "prolonged buffering", PlaybackProblem.CONNECTION)
            }
        }
    }

    /**
     * A stall the Viewer noticed (it lasted [NOTICEABLE_STALL_MS] or more). [SLOW_STALLS] of them
     * within [SLOW_WINDOW_MS] are said on screen ([PlaybackStatus.slowConnection]): the connection is
     * too slow for this channel, which is why it keeps stopping, and not the TV. The adaptive
     * quality goes on lowering the picture by itself meanwhile (REQ-ADP). The notice goes once the
     * channel has played for a whole window without a noticeable stall.
     */
    private fun noteStall() {
        val now = SystemClock.elapsedRealtime()
        recentStalls.addLast(now)
        while (now - recentStalls.first() > SLOW_WINDOW_MS) recentStalls.removeFirst()
        if (recentStalls.size >= SLOW_STALLS && !_status.value.slowConnection) {
            logEvent(
                "buffering",
                "${currentChannel?.name} stopped to load ${recentStalls.size} times in ${SLOW_WINDOW_MS / 60_000} min: " +
                    "slow connection said on screen"
            )
            _status.value = _status.value.copy(slowConnection = true)
        }
        if (!_status.value.slowConnection) return
        slowConnectionJob?.cancel()
        slowConnectionJob = scope.launch {
            delay(SLOW_WINDOW_MS)
            recentStalls.clear()
            _status.value = _status.value.copy(slowConnection = false)
        }
    }

    /** [problem] said in the middle of the picture, or null; [only]: change it only from this one. */
    private fun setPictureProblem(problem: PlaybackProblem?, only: PlaybackProblem? = null) {
        val current = _status.value.pictureProblem
        if (current == problem || (only != null && current != only)) return
        _status.value = _status.value.copy(pictureProblem = problem)
    }

    /**
     * A stall that recovered must not count against the next one: once playback has run without
     * interruption for a while the retry budget is restored (any stall or error cancels the timer),
     * so a long session never ends on the failure prompt after a series of recovered stalls.
     */
    private fun startStabilityTimer() {
        stabilityJob?.cancel()
        stabilityJob = scope.launch {
            delay(STABLE_PLAYBACK_MS)
            decoderRetried = false
            if (recovery.attempt > 0) {
                recovery.reset()
                updateStatus(
                    _status.value.phase,
                    _status.value.userMessage,
                    _status.value.showRecoveryPrompt,
                    problem = _status.value.problem
                )
                logEvent("recovery", "Playback stable for ${STABLE_PLAYBACK_MS / 1000} s: recovery budget restored")
            }
        }
    }

    /**
     * Some TV decoders stop putting out pictures while the sound plays on: the picture freezes and
     * nothing reports an error. While a channel plays on screen with its picture, this checks every
     * few seconds that new frames keep coming. When none has come for [FROZEN_PICTURE_MS] while
     * playback moved on, the decoder is let go and the channel prepared again, which builds a new
     * one: straight away the first [MAX_FROZEN_RESTARTS] times per tune. After those, the picture
     * that stops again is said on screen ([PlaybackProblem.FROZEN]) and restarted once it has stayed
     * stopped for [HEAL_ROUND_MS], as background healing does, so a decoder that always freezes can
     * neither loop nor be left frozen for good. Pictures that start coming again by themselves take
     * the notice away. A picture that was never coming in steadily, such as a radio station's still
     * image, is left alone.
     *
     * So is a decoder that is still handing over frames the renderer then drops for arriving too
     * late to show ([MIN_DROPPED_FRAMES_FOR_LATENESS]): that is a box with no power to spare, not a
     * stuck decoder, and restarting it would only cost it its progress and add the load of a fresh
     * start — the stutter's opposite cure.
     */
    private fun startFrozenPictureWatch() {
        frozenPictureWatch?.cancel()
        frozenPictureWatch = scope.launch {
            var lastFrames = -1
            var framesSeen = 0
            var lastFrameAtMs = 0L
            var positionAtLastFrame = 0L
            var droppedAtLastFrame = 0
            var droppingForLatenessLogged = false
            while (true) {
                delay(FROZEN_CHECK_MS)
                val p = bundle?.player ?: return@launch
                val counters = p.videoDecoderCounters
                if (!onScreen || !p.isPlaying || counters == null || !p.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)) {
                    lastFrames = -1 // paused, buffering, sound only: start counting again afterwards
                    // Whatever stopped playback now says so itself (the dots of a stall, say).
                    setPictureProblem(null, only = PlaybackProblem.FROZEN)
                    continue
                }
                counters.ensureUpdated()
                val frames = counters.renderedOutputBufferCount
                val now = SystemClock.elapsedRealtime()
                if (lastFrames < 0 || frames != lastFrames) {
                    if (lastFrames in 0 until frames) {
                        framesSeen += frames - lastFrames
                        setPictureProblem(null, only = PlaybackProblem.FROZEN) // moving again
                    }
                    lastFrames = frames
                    lastFrameAtMs = now
                    positionAtLastFrame = p.currentPosition
                    droppedAtLastFrame = counters.droppedBufferCount
                    droppingForLatenessLogged = false
                    continue
                }
                val frozenFor = now - lastFrameAtMs
                val playedOn = p.currentPosition - positionAtLastFrame
                // Sound for a good while and never a single frame: the picture has nowhere to go.
                if (frames == 0 && framesSeen == 0 && !noPictureReported &&
                    frozenFor >= NO_PICTURE_MS && playedOn >= NO_PICTURE_MS * 2 / 3
                ) {
                    noPictureReported = true
                    logEvent(
                        "decoder",
                        "${currentChannel?.name} plays its sound, but no picture has reached the screen: " +
                            "rebuilding the video view"
                    )
                    _noPictureOnScreen.tryEmit(Unit)
                    return@launch
                }
                if (frozenFor < FROZEN_PICTURE_MS || playedOn < FROZEN_PICTURE_MS * 2 / 3 ||
                    framesSeen < MIN_FRAMES_BEFORE_FREEZE
                ) continue
                // The decoder is alive, only behind: it is still handing the renderer frames, which
                // are being dropped for arriving too late to show (droppedBufferCount climbs). That is
                // a box with no power to spare — a Raspberry Pi 5 decoding in software while something
                // else, such as a screen share, takes the cores — not a decoder that has stuck, and
                // the two want opposite care. A restart frees a stuck decoder, but throws away a busy
                // one's progress and adds the load of building a fresh decoder, making the stutter
                // worse. A stuck TV decoder emits nothing, so it drops nothing: climbing drops tell
                // the two apart. Leave it to catch up (the dropped-frame count is in Diagnostics), and
                // measure the next window afresh so a real freeze after this is still caught.
                val droppedSince = counters.droppedBufferCount - droppedAtLastFrame
                if (droppedSince >= MIN_DROPPED_FRAMES_FOR_LATENESS) {
                    if (!droppingForLatenessLogged) {
                        droppingForLatenessLogged = true
                        logEvent(
                            "decoder",
                            "${currentChannel?.name}: the picture is behind and frames are being dropped " +
                                "(this box is busy), not frozen — the decoder is left to catch up"
                        )
                    }
                    setPictureProblem(null, only = PlaybackProblem.FROZEN)
                    lastFrameAtMs = now
                    positionAtLastFrame = p.currentPosition
                    droppedAtLastFrame = counters.droppedBufferCount
                    continue
                }
                if (frozenRestarts < MAX_FROZEN_RESTARTS || frozenFor >= FROZEN_PICTURE_MS + HEAL_ROUND_MS) {
                    restartFrozenPicture()
                    return@launch
                }
                if (_status.value.pictureProblem != PlaybackProblem.FROZEN) {
                    if (frozenRestarts == MAX_FROZEN_RESTARTS) {
                        logEvent(
                            "decoder",
                            "The picture of ${currentChannel?.name} keeps freezing on this TV: said on screen, " +
                                "and restarted every ${HEAL_ROUND_MS / 1000} s while it stays stopped"
                        )
                    }
                    setPictureProblem(PlaybackProblem.FROZEN)
                }
            }
        }
    }

    private fun restartFrozenPicture() {
        val p = bundle?.player ?: return
        val channel = currentChannel ?: return
        frozenRestarts++
        val quick = frozenRestarts <= MAX_FROZEN_RESTARTS
        if (quick) logEvent("decoder", "The picture of ${channel.name} froze while the sound played on: restarting its decoder")
        setPictureProblem(null, only = PlaybackProblem.FROZEN)
        // The moving dots while it restarts, as for any wait; nor does it count as a stall.
        updateStatus(PlaybackPhase.RECOVERING, message = null, prompt = false)
        p.stop() // lets the decoder go: prepared again, the channel gets a new one
        // The later restarts were announced once, when they began (see startFrozenPictureWatch).
        reprepareCurrent(if (quick) "picture froze" else null)
    }

    /**
     * A live stream the player fell behind (the TV was busy, or playback stalled for a long time) is
     * not a failure: jump back to the live edge. It shows no message and uses no retry budget. If it
     * happens again straight away it is treated as a normal error instead, so this can never loop.
     */
    private fun rejoinLive(): Boolean {
        val p = bundle?.player ?: return false
        val now = SystemClock.elapsedRealtime()
        if (now - lastLiveRejoinMs < LIVE_REJOIN_MIN_GAP_MS) return false
        lastLiveRejoinMs = now
        logEvent("recovery", "Fell behind the live stream; rejoining the live edge")
        p.seekToDefaultPosition()
        p.prepare()
        startBufferingWatchdog(START_TIMEOUT_MS)
        return true
    }

    private fun updateStatus(
        phase: PlaybackPhase,
        message: String?,
        prompt: Boolean,
        attempt: Int = recovery.attempt,
        problem: PlaybackProblem? = null
    ) {
        _status.value = _status.value.copy(
            phase = phase,
            channel = currentChannel,
            userMessage = message,
            showRecoveryPrompt = prompt,
            recoveryAttempt = attempt,
            problem = problem
        )
    }

    private fun logEvent(category: String, message: String) {
        diagnosticsStore.append(DiagnosticsEvent(System.currentTimeMillis(), currentChannel?.name, category, message))
    }

    /** Re-reads the live figures (buffer, bandwidth, state); the Diagnostics page calls this once a second. */
    fun refreshDiagnostics() {
        val p = bundle?.player ?: return
        _diagnostics.value = _diagnostics.value.copy(
            channelName = currentChannel?.name,
            channelUrl = currentChannel?.url,
            bufferedMs = p.totalBufferedDuration,
            bandwidthBps = bundle?.bandwidthMeter?.bitrateEstimate ?: 0L,
            bufferingEvents = bufferingEvents,
            recoveryCount = recoveryCount,
            droppedFrames = droppedFramesTotal,
            volumeLevelling = levellingLabel(),
            playbackState = stateName(p.playbackState)
        )
    }

    private fun levellingLabel(): String {
        val processor = bundle?.evenVolume ?: return "—"
        return when {
            !evenVolume -> "Off"
            // Sound passed through undecoded, to the TV set or an amplifier, cannot be levelled.
            !processor.isActive -> "Not possible for this sound"
            else -> String.format(Locale.US, "%+.1f dB", processor.leveler.gainDb)
        }
    }

    private fun stateName(state: Int): String = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "UNKNOWN"
    }

    /**
     * Heuristic classification of a MediaCodec decoder name. Android software video decoders are
     * named "OMX.google.*" (legacy) or "c2.android.*" (Codec2); everything else is a
     * vendor/hardware decoder (e.g. "c2.amlogic.*", "OMX.amlogic.*" on TV boxes). FFmpeg decoders
     * are software even where the ROM lists them as hardware-accelerated ("c2.ffmpeg.*" on the
     * Raspberry Pi 5 does, for H.264 too, which that board has no hardware for); the exception is
     * HEVC there, which the FFmpeg component hands to the board's V4L2 HEVC block. (REQ-HW-006)
     */
    private fun isSoftwareDecoderName(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.startsWith("omx.ffmpeg.") ||
            (n.startsWith("c2.ffmpeg.") && !n.startsWith("c2.ffmpeg.hevc."))
    }

    /** The codec a decoder name is for, or null when the name does not say, to tell a fallback from a zap. */
    private fun decoderCodec(name: String): String? {
        val n = name.lowercase()
        return when {
            "avc" in n || "h264" in n -> "avc"
            "hevc" in n || "h265" in n -> "hevc"
            "mpeg2" in n -> "mpeg2"
            "mpeg4" in n -> "mpeg4"
            "vp8" in n -> "vp8"
            "vp9" in n -> "vp9"
            "av1" in n || "av01" in n -> "av1"
            else -> null
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            _pausedBySystem.value = !playWhenReady && (
                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ||
                    reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY
                )
            // With the screen off there is no Play to press: headphones unplugged or another app's
            // sound ends the listening, rather than leave the phone holding a paused channel.
            if (_pausedBySystem.value && _playingWithScreenOff.value) {
                endScreenOffPlayback(
                    if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) "Headphones unplugged with the screen off"
                    else "Another app took the sound with the screen off"
                )
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    bufferingEvents++
                    stabilityJob?.cancel()
                    startBufferingWatchdog()
                    if (_status.value.phase == PlaybackPhase.PLAYING) {
                        // Short transient disturbances don't get an intrusive message (REQ-BUF-003):
                        // the screen shows only the moving dots, once it has lasted a moment.
                        updateStatus(PlaybackPhase.BUFFERING, message = null, prompt = false)
                        if (onScreen) stallStartedMs = SystemClock.elapsedRealtime()
                    }
                }

                Player.STATE_READY -> {
                    bundle?.channelSwitch?.pending = false
                    bufferingWatchdog?.cancel()
                    slowRetryJob?.cancel()
                    if (stallStartedMs > 0L) {
                        if (SystemClock.elapsedRealtime() - stallStartedMs >= NOTICEABLE_STALL_MS) noteStall()
                        stallStartedMs = 0L
                    }
                    if (loadAsHls) currentChannel?.let { knownHls.add(it.url); knownPlain.remove(it.url) }
                    if (healRound > 0) {
                        val tries = if (healRound == 1) "1 background try" else "$healRound background tries"
                        logEvent("recovery", "${currentChannel?.name} is playing again after $tries")
                        healRound = 0
                        lastHealFailure = null
                    }
                    if (prepareStartMs > 0L) {
                        val startup = SystemClock.elapsedRealtime() - prepareStartMs
                        prepareStartMs = 0L
                        _diagnostics.value = _diagnostics.value.copy(startupTimeMs = startup)
                        logEvent("startup", "Ready in $startup ms")
                    }
                    if (BootTrace.awaitingFirstPlay) {
                        BootTrace.awaitingFirstPlay = false
                        logEvent("boot", BootTrace.stamp("Picture playing"))
                    }
                    startStabilityTimer()
                    startFrozenPictureWatch()
                    updateStatus(PlaybackPhase.PLAYING, message = null, prompt = false)
                }

                Player.STATE_ENDED ->
                    scheduleRetry(ErrorMapper.CONNECTION_INTERRUPTED, "stream ended", PlaybackProblem.CONNECTION)

                Player.STATE_IDLE -> { }
            }
            refreshDiagnostics()
        }

        override fun onPlayerError(error: PlaybackException) {
            bufferingWatchdog?.cancel()
            stabilityJob?.cancel()
            stallStartedMs = 0L // an error is not a stall: it is said as itself
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && rejoinLive()) return
            if (error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED && tryAsHls()) return
            // Not an HLS playlist after all: the address is opened as it is again, now or by the retries.
            if (isNotHlsPlaylist(error) && tryAsPlain()) return
            val hasNetwork = networkMonitor.hasNetwork()
            val mapped = ErrorMapper.map(error, hasNetwork)
            // A background round that fails as the last one did is not written again (see failAndKeepTrying).
            if (healRound == 0 || mapped.technical != lastHealFailure) logEvent("error", mapped.technical)
            when {
                mapped.recoverable -> scheduleRetry(mapped.userMessage, mapped.technical, mapped.problem)
                mapped.kind == ErrorKind.DECODER && !decoderRetried -> retryDecoderOnce()
                mapped.keepTrying -> failAndKeepTrying(mapped.userMessage, mapped.technical, mapped.problem)
                else -> updateStatus(PlaybackPhase.FAILED, mapped.userMessage, prompt = true, problem = mapped.problem)
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            if (tracks.isEmpty) return
            val video = tracks.isTypeSupported(C.TRACK_TYPE_VIDEO, /* allowExceedsCapabilities = */ true)
            val audio = tracks.isTypeSupported(C.TRACK_TYPE_AUDIO, /* allowExceedsCapabilities = */ true)
            if (!video && !audio) {
                // DRM-protected (FluxTV has no DRM) or only in formats this TV cannot decode. The
                // player need not raise an error for that and may just show nothing, so FluxTV says it.
                cannotPlay("nothing in this stream that this TV can play (DRM-protected, or unsupported formats)")
            } else if (!video && tracks.containsType(C.TRACK_TYPE_VIDEO)) {
                // A black screen with sound looks broken: it is said in the middle of the picture.
                if (_status.value.pictureProblem != PlaybackProblem.SOUND_ONLY) {
                    logEvent("decoder", "This TV cannot decode the picture of ${currentChannel?.name}: sound only")
                }
                setPictureProblem(PlaybackProblem.SOUND_ONLY)
            } else {
                setPictureProblem(null, only = PlaybackProblem.SOUND_ONLY)
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            _diagnostics.value = _diagnostics.value.copy(
                videoWidth = videoSize.width,
                videoHeight = videoSize.height
            )
        }
    }

    private val analyticsListener = object : AnalyticsListener {
        /**
         * A load of the stream (an HLS or DASH segment or playlist) that started after Android
         * reported the network gone, and completed: the network works, whatever Android says, so
         * it is not said on screen (see [watchNetwork]).
         */
        override fun onLoadCompleted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData
        ) {
            if (networkLostAtMs == NO_LOSS || networkLossDisproved) return
            if (loadEventInfo.elapsedRealtimeMs - loadEventInfo.loadDurationMs <= networkLostAtMs) return
            networkLossDisproved = true
            logEvent("network", "Android reports no network, but the channel's data still comes in: not said on screen")
            setNetworkLost(false)
        }

        override fun onDroppedVideoFrames(
            eventTime: AnalyticsListener.EventTime,
            droppedFrames: Int,
            elapsedMs: Long
        ) {
            droppedFramesTotal += droppedFrames
            _diagnostics.value = _diagnostics.value.copy(droppedFrames = droppedFramesTotal)
        }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?
        ) {
            val fps = if (format.frameRate > 0f) format.frameRate else 0f
            if (fps > 0f) _videoFrameRate.value = fps
            _diagnostics.value = _diagnostics.value.copy(
                frameRate = fps,
                videoBitrate = if (format.bitrate != Format.NO_VALUE) format.bitrate else _diagnostics.value.videoBitrate,
                videoCodec = format.codecs ?: format.sampleMimeType,
                videoWidth = if (format.width != Format.NO_VALUE) format.width else _diagnostics.value.videoWidth,
                videoHeight = if (format.height != Format.NO_VALUE) format.height else _diagnostics.value.videoHeight
            )
        }

        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?
        ) {
            _diagnostics.value = _diagnostics.value.copy(audioCodec = format.codecs ?: format.sampleMimeType)
        }

        override fun onBandwidthEstimate(
            eventTime: AnalyticsListener.EventTime,
            totalLoadTimeMs: Int,
            totalBytesLoaded: Long,
            bitrateEstimate: Long
        ) {
            _diagnostics.value = _diagnostics.value.copy(bandwidthBps = bitrateEstimate)
        }

        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long
        ) {
            val hardware = !isSoftwareDecoderName(decoderName)
            val previous = lastVideoDecoder
            lastVideoDecoder = decoderName
            _diagnostics.value = _diagnostics.value.copy(
                videoDecoder = decoderName,
                hardwareDecoding = hardware
            )
            // Record decoder selection, and flag a hardware -> software fallback explicitly (REQ-HW-006).
            // A channel in another codec that only has a software decoder is not a fallback.
            if (previous != null && previous != decoderName && !isSoftwareDecoderName(previous) && !hardware &&
                decoderCodec(previous) == decoderCodec(decoderName)
            ) {
                logEvent("decoder", "Fell back to software decoder: $decoderName")
            } else {
                logEvent("decoder", "Video decoder: $decoderName (${if (hardware) "hardware" else "software"})")
            }
        }
    }

    private companion object {
        /**
         * Live channels play at their own pace, never faster or slower. Left to itself, Media3 keeps
         * a live DASH channel (and an HLS one whose playlist sets a hold-back) at a set distance
         * behind live by playing it up to 3% fast or slow, retuned every second. The sound was
         * stretched to match, which wavers audibly, and each retune drained and restarted the sound
         * path, a small break in it each time, which also set the volume levelling off at its
         * fast pace again. The picture's frames came at 3% off the rate the TV had been switched to
         * for them, so some were shown twice or not at all: a regular stutter. A channel that falls
         * too far behind live is brought back to it by [rejoinLive] instead. The media item's
         * setting wins over anything a manifest asks for.
         */
        val STEADY_LIVE_SPEED: MediaItem.LiveConfiguration =
            MediaItem.LiveConfiguration.Builder().setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f).build()

        /** Uninterrupted playback after which the retry budget is restored. */
        const val STABLE_PLAYBACK_MS = 20_000L

        /** Minimum gap between silent live-edge rejoins before an error is treated as a real one. */
        const val LIVE_REJOIN_MIN_GAP_MS = 10_000L

        /**
         * How long a channel may take from a prepare to its first picture before it is prepared
         * again. Longer than a stall: a slow server or a TV just out of boot needs a few seconds more
         * to start, and restarting it then only throws that start away.
         */
        const val START_TIMEOUT_MS = 15_000L

        /** How long playback that had started may stay buffering before it is prepared again (REQ-BUF-003). */
        const val STALL_TIMEOUT_MS = 8_000L

        /** The pause before the one retry a decoder error gets, for the decoder to be let go. */
        const val DECODER_RETRY_DELAY_MS = 1_500L

        /** How often the frozen-picture watch looks, and how long a picture may stand still. */
        const val FROZEN_CHECK_MS = 2_000L
        const val FROZEN_PICTURE_MS = 6_000L

        /** Frames that must have come in steadily before a stop counts as a freeze (2 s at 25 fps). */
        const val MIN_FRAMES_BEFORE_FREEZE = 50

        /**
         * Frames dropped for lateness within a freeze window that mean the decoder is alive but
         * behind — a box too busy to keep up — rather than stuck. At or above this, the picture is
         * left to catch up instead of being restarted; a stuck decoder emits, and so drops, nothing.
         * Low because a busy decoder drops many frames in a [FROZEN_PICTURE_MS] window while a stuck
         * one drops essentially none, so the two are far apart.
         */
        const val MIN_DROPPED_FRAMES_FOR_LATENESS = 3

        /** How long sound may play without a single frame on screen before the view is rebuilt. */
        const val NO_PICTURE_MS = 10_000L

        /**
         * Restarts for a frozen picture straight away, per tune; after them, one at most every
         * [HEAL_ROUND_MS], so a decoder that always freezes cannot loop.
         */
        const val MAX_FROZEN_RESTARTS = 3

        /** The pause between background healing rounds, see [scheduleHealRound]. */
        const val HEAL_ROUND_MS = 30_000L

        /** How long a healing round waits for the video view to take the new player. */
        const val SURFACE_WAIT_MS = 2_000L

        /** The least time between two playlist reloads asked for by healing. */
        const val PLAYLIST_RELOAD_MIN_GAP_MS = 30 * 60_000L

        /** [networkLostAtMs] while Android reports a network. */
        const val NO_LOSS = Long.MAX_VALUE

        /** How long the network has to be gone before the screen says so, see [watchNetwork]. */
        const val NETWORK_LOSS_GRACE_MS = 3_000L

        /** A stall at least this long is one the Viewer saw, and counts towards a slow connection. */
        const val NOTICEABLE_STALL_MS = 1_000L

        /** This many noticeable stalls within the window are said on screen as a slow connection. */
        const val SLOW_STALLS = 3
        const val SLOW_WINDOW_MS = 2 * 60_000L
    }
}
