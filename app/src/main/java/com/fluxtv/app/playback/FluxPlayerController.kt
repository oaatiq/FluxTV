package com.fluxtv.app.playback

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.fluxtv.app.BootTrace
import com.fluxtv.app.data.cache.DiagnosticsStore
import com.fluxtv.app.data.model.Channel
import com.fluxtv.app.data.model.DiagnosticsEvent
import com.fluxtv.app.data.model.DiagnosticsSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
    private val networkMonitor: NetworkMonitor
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var bundle: PlayerFactory.Bundle? = null
    val player: ExoPlayer? get() = bundle?.player

    private var currentChannel: Channel? = null
    private var maxVideoHeight = 0

    /**
     * Whether FluxTV is on screen to be watched: set by tuning and [onResume], cleared by [onPause]
     * and [onStop]. Automatic recovery only starts playback while it is set, so a retry or a network
     * coming back never plays a channel over Settings, another app, or a TV in standby.
     */
    private var onScreen = false

    private val recovery = RecoveryPolicy()
    private var lastLiveRejoinMs = -LIVE_REJOIN_MIN_GAP_MS
    private var prepareStartMs = 0L
    private var bufferingEvents = 0
    private var recoveryCount = 0
    private var droppedFramesTotal = 0
    private var lastVideoDecoder: String? = null

    private var retryJob: Job? = null
    private var bufferingWatchdog: Job? = null
    private var slowRetryJob: Job? = null
    private var stabilityJob: Job? = null

    private val _status = MutableStateFlow(PlaybackStatus())
    val status: StateFlow<PlaybackStatus> = _status.asStateFlow()

    private val _diagnostics = MutableStateFlow(DiagnosticsSnapshot())
    val diagnostics: StateFlow<DiagnosticsSnapshot> = _diagnostics.asStateFlow()

    /** The diagnostics history, including the lines the boot and wake receivers add themselves. */
    val events: StateFlow<List<DiagnosticsEvent>> = diagnosticsStore.events

    private val _videoFrameRate = MutableStateFlow(0f)
    val videoFrameRate: StateFlow<Float> = _videoFrameRate.asStateFlow()

    private val _playerReady = MutableStateFlow(false)
    val playerReady: StateFlow<Boolean> = _playerReady.asStateFlow()

    init {
        scope.launch {
            networkMonitor.status.collect { status ->
                if (status == NetworkStatus.AVAILABLE) onNetworkRestored()
            }
        }
    }

    // ---- public control ----

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
        recovery.reset()
        bufferingEvents = 0
        droppedFramesTotal = 0
        stabilityJob?.cancel()
        prepareStartMs = SystemClock.elapsedRealtime()

        val factory = bundle!!.httpDataSourceFactory
        factory.setUserAgent(channel.userAgent ?: "FluxTV/1.0")
        val headers = HashMap<String, String>()
        channel.referer?.let { headers["Referer"] = it }
        factory.setDefaultRequestProperties(headers)

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

    /** User pressed "Try again" on the recovery prompt (REQ-REC-005). Resets the budget. */
    fun retryNow() {
        slowRetryJob?.cancel()
        recovery.reset()
        updateStatus(PlaybackPhase.PREPARING, message = null, prompt = false)
        reprepareCurrent("manual retry")
    }

    fun onResume() {
        onScreen = true
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
        onScreen = false
        val p = bundle?.player ?: return
        retryJob?.cancel()
        bufferingWatchdog?.cancel()
        slowRetryJob?.cancel()
        stabilityJob?.cancel()
        p.stop()
        if (currentChannel != null) logEvent("standby", "FluxTV left the screen: playback stopped")
    }

    fun release() {
        retryJob?.cancel()
        bufferingWatchdog?.cancel()
        slowRetryJob?.cancel()
        bundle?.player?.let {
            it.removeListener(playerListener)
            it.removeAnalyticsListener(analyticsListener)
            it.release()
        }
        stabilityJob?.cancel()
        bundle = null
        _playerReady.value = false
        // The scope stays alive on purpose: this controller is a process-wide singleton that outlives
        // the activity (Exit closes the activity, the process may be reused), and recovery retries,
        // logging and the network-restore collector all run in it. The next playChannel() builds a
        // fresh player.
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
        val created = PlayerFactory.create(context, maxVideoHeight)
        created.player.addListener(playerListener)
        created.player.addAnalyticsListener(analyticsListener)
        bundle = created
        _playerReady.value = true
    }

    private fun onNetworkRestored() {
        // Off screen (Settings, another app, standby) nobody is watching, and the way back re-tunes or
        // retries anyway; re-preparing here started the channel's sound over whatever was on screen.
        if (!onScreen) return
        val phase = _status.value.phase
        if (currentChannel != null && (phase == PlaybackPhase.RECOVERING || phase == PlaybackPhase.FAILED)) {
            slowRetryJob?.cancel()
            recovery.reset()
            updateStatus(PlaybackPhase.PREPARING, message = null, prompt = false)
            reprepareCurrent("network restored")
        }
    }

    private fun reprepareCurrent(reason: String) {
        val p = bundle?.player ?: return
        val channel = currentChannel ?: return
        recoveryCount++
        prepareStartMs = SystemClock.elapsedRealtime()
        logEvent("recovery", "Recovery attempt ${recovery.attempt} for ${channel.name}: $reason")
        if (load(p, channel)) startBufferingWatchdog(START_TIMEOUT_MS)
    }

    /**
     * Hands [channel] to the player. For a stream type this build has no module for (RTSP, Smooth
     * Streaming), Media3 throws straight out of setMediaItem, which used to close the whole app on a
     * channel change: such a channel is reported as one that cannot be played instead.
     */
    private fun load(p: ExoPlayer, channel: Channel): Boolean = try {
        p.setMediaItem(MediaItem.fromUri(channel.url))
        p.prepare()
        // A retry while FluxTV is off screen reconnects, and only plays once FluxTV is back.
        p.playWhenReady = onScreen
        true
    } catch (e: RuntimeException) {
        // setMediaItem threw before replacing anything: stop the previous channel rather than leave it
        // playing under this one's name.
        p.stop()
        bundle?.channelSwitch?.pending = false
        logEvent("error", "Cannot open ${channel.name}: $e")
        updateStatus(PlaybackPhase.FAILED, ErrorMapper.CANNOT_PLAY, prompt = true)
        false
    }

    private fun scheduleRetry(message: String, reason: String) {
        if (!recovery.canRetry()) {
            updateStatus(PlaybackPhase.FAILED, message, prompt = true)
            logEvent("error", "Recovery exhausted for ${currentChannel?.name}: $reason")
            startSlowAutoRecovery()
            return
        }
        val delayMs = recovery.nextDelayMs()
        updateStatus(PlaybackPhase.RECOVERING, message, prompt = false, attempt = recovery.attempt)
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(delayMs)
            reprepareCurrent(reason)
        }
    }

    /**
     * After bounded recovery is exhausted, keep trying slowly in the background so the TV heals
     * itself once the network returns, without the Viewer pressing anything (REQ-BUF-006,
     * REQ-REC-003). A 30s heartbeat is deliberately not a tight retry loop (REQ-REC-007).
     */
    private fun startSlowAutoRecovery() {
        if (slowRetryJob?.isActive == true) return
        slowRetryJob = scope.launch {
            while (true) {
                delay(30_000)
                val channel = currentChannel ?: break
                if (onScreen && networkMonitor.hasNetwork()) {
                    logEvent("recovery", "Background auto-retry after outage for ${channel.name}")
                    recovery.reset()
                    reprepareCurrent("auto-retry after outage")
                    break
                }
            }
        }
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
                logEvent("buffering", "Prolonged buffering on ${currentChannel?.name}")
                scheduleRetry(ErrorMapper.CONNECTION_INTERRUPTED, "prolonged buffering")
            }
        }
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
            if (recovery.attempt > 0) {
                recovery.reset()
                updateStatus(_status.value.phase, _status.value.userMessage, _status.value.showRecoveryPrompt)
                logEvent("recovery", "Playback stable for ${STABLE_PLAYBACK_MS / 1000} s: recovery budget restored")
            }
        }
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
        attempt: Int = recovery.attempt
    ) {
        _status.value = _status.value.copy(
            phase = phase,
            channel = currentChannel,
            userMessage = message,
            showRecoveryPrompt = prompt,
            recoveryAttempt = attempt
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
            playbackState = stateName(p.playbackState)
        )
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
     * vendor/hardware decoder (e.g. "c2.amlogic.*", "OMX.amlogic.*" on TV boxes). (REQ-HW-006)
     */
    private fun isSoftwareDecoderName(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.startsWith("omx.ffmpeg.")
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    bufferingEvents++
                    stabilityJob?.cancel()
                    startBufferingWatchdog()
                    if (_status.value.phase == PlaybackPhase.PLAYING) {
                        // Short transient disturbances don't get an intrusive message (REQ-BUF-003).
                        updateStatus(PlaybackPhase.BUFFERING, message = null, prompt = false)
                    }
                }

                Player.STATE_READY -> {
                    bundle?.channelSwitch?.pending = false
                    bufferingWatchdog?.cancel()
                    slowRetryJob?.cancel()
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
                    updateStatus(PlaybackPhase.PLAYING, message = null, prompt = false)
                }

                Player.STATE_ENDED ->
                    scheduleRetry(ErrorMapper.CONNECTION_INTERRUPTED, "stream ended")

                Player.STATE_IDLE -> { }
            }
            refreshDiagnostics()
        }

        override fun onPlayerError(error: PlaybackException) {
            bufferingWatchdog?.cancel()
            stabilityJob?.cancel()
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && rejoinLive()) return
            val hasNetwork = networkMonitor.hasNetwork()
            val mapped = ErrorMapper.map(error, hasNetwork)
            logEvent("error", mapped.technical)
            if (mapped.recoverable) {
                scheduleRetry(mapped.userMessage, mapped.technical)
            } else {
                updateStatus(PlaybackPhase.FAILED, mapped.userMessage, prompt = true)
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
            if (previous != null && previous != decoderName && !isSoftwareDecoderName(previous) && !hardware) {
                logEvent("decoder", "Fell back to software decoder: $decoderName")
            } else {
                logEvent("decoder", "Video decoder: $decoderName (${if (hardware) "hardware" else "software"})")
            }
        }
    }

    private companion object {
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
    }
}
