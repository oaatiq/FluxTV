package com.fluxtv.app.playback

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.DefaultMediaCodecAdapterFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.fluxtv.app.data.remote.DEFAULT_USER_AGENT
import okhttp3.Call
import okhttp3.OkHttpClient

/**
 * Builds a reliability-tuned ExoPlayer.
 *
 * Buffering favors uninterrupted viewing over low latency (REQ-BUF-001/002): a large 30–60s buffer,
 * capped by size on low-memory boxes, with a short pre-roll (1.5 s) so channels start fast
 * (REQ-START-004).
 * Adaptive track selection is on by default and can be capped by the administrator (REQ-ADP).
 * Decoder fallback is enabled so one unsupported track doesn't kill playback (REQ-PLAY-003,
 * REQ-REL-004). The sound goes through [EvenVolumeAudioProcessor], which evens out the volume between
 * channels when Preferences has it on.
 */
object PlayerFactory {

    /** At or below this Java heap per app (ActivityManager.memoryClass), the buffer is capped by size. */
    private const val LOW_MEMORY_HEAP_MB = 192

    data class Bundle(
        val player: ExoPlayer,
        val httpDataSourceFactory: OkHttpDataSource.Factory,
        val trackSelector: DefaultTrackSelector,
        val bandwidthMeter: DefaultBandwidthMeter,
        val channelSwitch: ChannelSwitchSignal,
        val evenVolume: EvenVolumeAudioProcessor
    )

    /** [httpClient]: the client the playlist downloads use too (see AppContainer). */
    fun create(context: Context, maxVideoHeight: Int, httpClient: () -> OkHttpClient): Bundle {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 30_000,
                /* maxBufferMs = */ 60_000,
                // A channel starts once 1.5 s is in. At 2.5 s, one the server sends at the live rate
                // (plain MPEG-TS) took up to a second longer to start. After a stall it waits for 5 s,
                // so a weak connection does not stall again straight away.
                /* bufferForPlaybackMs = */ 1_500,
                /* bufferForPlaybackAfterRebufferMs = */ 5_000
            )
            .apply {
                val heapMb = context.getSystemService(ActivityManager::class.java)?.memoryClass ?: 0
                if (heapMb in 1..LOW_MEMORY_HEAP_MB) {
                    // The buffer is held on the app's Java heap. Kept by time alone (30 s at least),
                    // a high-bitrate or on-demand stream can fill about 100 MB, too much where apps get
                    // 192 MB or less; there a size cap, a quarter of the heap, wins over the time.
                    setTargetBufferBytes(heapMb / 4 * 1024 * 1024)
                    setPrioritizeTimeOverSizeThresholds(false)
                } else {
                    setPrioritizeTimeOverSizeThresholds(true)
                }
            }
            .build()

        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .apply { if (maxVideoHeight > 0) setMaxVideoSize(Int.MAX_VALUE, maxVideoHeight) }
                    .build()
            )
        }

        val bandwidthMeter = DefaultBandwidthMeter.Builder(context).build()

        // Channels go through the same OkHttp client as the playlist: one pool of connections to the
        // provider, and its timeouts (15 s to connect, 30 s between reads). Redirects between http and
        // https are followed, as before. The client is asked for at the first request, which Media3
        // makes on its loading thread, so a first tune does not build it on the main thread.
        val httpDataSourceFactory = OkHttpDataSource.Factory(Call.Factory { httpClient().newCall(it) })
            .setUserAgent(DEFAULT_USER_AGENT)

        val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        val channelSwitch = ChannelSwitchSignal()
        val evenVolume = EvenVolumeAudioProcessor()
        // Extension renderers on: FFmpeg decodes the sound formats the TV has no decoder of its own
        // for (AC-3, E-AC-3, MP2, DTS), which played with picture and no sound. A format the TV can
        // decode, or pass through to the TV set or an amplifier, still goes to the TV, which comes first.
        val renderersFactory = FreshCodecOnChannelSwitchRenderersFactory(context, channelSwitch, evenVolume)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        val player = ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setBandwidthMeter(bandwidthMeter)
            .setMediaSourceFactory(mediaSourceFactory)
            // Off on purpose. Android counts HDMI among the outputs whose loss makes audio "noisy", so on
            // a TV box, switching the TV off or to another input could pause live TV, which then stayed
            // frozen with nothing on screen to start it again.
            .setHandleAudioBecomingNoisy(false)
            .build()
            .apply {
                setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS)
                setWakeMode(C.WAKE_MODE_NETWORK)
                setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ true)
            }

        return Bundle(player, httpDataSourceFactory, trackSelector, bandwidthMeter, channelSwitch, evenVolume)
    }
}

/**
 * Raised by [FluxPlayerController] when it loads a new channel and cleared once playback is ready, so
 * the video renderer can tell a channel switch from an in-stream (adaptive bitrate) resolution change.
 */
class ChannelSwitchSignal {
    @Volatile
    var pending = false
}

/**
 * ExoPlayer normally keeps the hardware codec when the next channel has a different resolution.
 * The MiTV's MStar decoder (OMX.MS.AVC.Decoder) then keeps its old output scaling, so after zapping
 * from a larger stream to a smaller one the picture comes out zoomed in. Refusing to reuse the codec
 * when a channel switch changes the resolution makes ExoPlayer build a fresh one. Same-resolution zaps
 * still reuse it and stay fast, and resolution changes inside one stream are left alone. A software
 * decoder (the fallback when the hardware one failed to start) is never carried to the next channel.
 *
 * This replaces the default video renderers, so the one here is the only video renderer and no
 * extension adds one: FFmpeg, which extension renderers are switched on for, only ever decodes sound.
 *
 * Its audio sink is the default one with [evenVolume] added to the sound's path, for the TV's own
 * decoders and FFmpeg alike.
 */
private class FreshCodecOnChannelSwitchRenderersFactory(
    context: Context,
    private val channelSwitch: ChannelSwitchSignal,
    private val evenVolume: EvenVolumeAudioProcessor
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink = DefaultAudioSink.Builder(context)
        .setEnableFloatOutput(enableFloatOutput)
        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        .setAudioProcessors(arrayOf(evenVolume))
        .build()

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>
    ) {
        out.add(
            object : MediaCodecVideoRenderer(
                context,
                DefaultMediaCodecAdapterFactory(context),
                mediaCodecSelector,
                allowedVideoJoiningTimeMs,
                enableDecoderFallback,
                eventHandler,
                eventListener,
                DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY
            ) {
                override fun canReuseCodec(
                    codecInfo: MediaCodecInfo,
                    oldFormat: Format,
                    newFormat: Format
                ): DecoderReuseEvaluation {
                    val evaluation = super.canReuseCodec(codecInfo, oldFormat, newFormat)
                    if (!channelSwitch.pending || evaluation.result == DecoderReuseEvaluation.REUSE_RESULT_NO) {
                        return evaluation
                    }
                    val discardReason = when {
                        oldFormat.width != newFormat.width || oldFormat.height != newFormat.height ->
                            DecoderReuseEvaluation.DISCARD_REASON_VIDEO_RESOLUTION_CHANGED
                        // A software decoder usually means the hardware one failed to start (often
                        // because it was still busy); kept, it would decode every channel after it on
                        // the CPU. A new channel builds a fresh one, which tries hardware first. Where
                        // a format only has a software decoder, that costs one decoder start per zap.
                        codecInfo.softwareOnly -> DecoderReuseEvaluation.DISCARD_REASON_APP_OVERRIDE
                        else -> return evaluation
                    }
                    return DecoderReuseEvaluation(
                        codecInfo.name,
                        oldFormat,
                        newFormat,
                        DecoderReuseEvaluation.REUSE_RESULT_NO,
                        discardReason
                    )
                }
            }
        )
    }
}
