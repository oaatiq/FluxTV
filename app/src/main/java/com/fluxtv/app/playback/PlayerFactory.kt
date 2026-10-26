package com.fluxtv.app.playback

import android.content.Context
import android.os.Handler
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.DefaultMediaCodecAdapterFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * Builds a reliability-tuned ExoPlayer.
 *
 * Buffering favors uninterrupted viewing over low latency (REQ-BUF-001/002): a large 30–60s buffer,
 * with a modest pre-roll so startup stays fast (REQ-START-004). Adaptive track selection is on by
 * default and can be capped by the administrator (REQ-ADP). Decoder fallback is enabled so one
 * unsupported track doesn't kill playback (REQ-PLAY-003, REQ-REL-004).
 */
object PlayerFactory {

    data class Bundle(
        val player: ExoPlayer,
        val httpDataSourceFactory: DefaultHttpDataSource.Factory,
        val trackSelector: DefaultTrackSelector,
        val bandwidthMeter: DefaultBandwidthMeter,
        val channelSwitch: ChannelSwitchSignal
    )

    fun create(context: Context, maxVideoHeight: Int): Bundle {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 30_000,
                /* maxBufferMs = */ 60_000,
                /* bufferForPlaybackMs = */ 2_500,
                /* bufferForPlaybackAfterRebufferMs = */ 5_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .apply { if (maxVideoHeight > 0) setMaxVideoSize(Int.MAX_VALUE, maxVideoHeight) }
                    .build()
            )
        }

        val bandwidthMeter = DefaultBandwidthMeter.Builder(context).build()

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("FluxTV/1.0")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)

        val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        val channelSwitch = ChannelSwitchSignal()
        val renderersFactory = FreshCodecOnChannelSwitchRenderersFactory(context, channelSwitch)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

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

        return Bundle(player, httpDataSourceFactory, trackSelector, bandwidthMeter, channelSwitch)
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
 * Extension renderers are switched off for this app, so the video renderer is the only one added here.
 */
private class FreshCodecOnChannelSwitchRenderersFactory(
    context: Context,
    private val channelSwitch: ChannelSwitchSignal
) : DefaultRenderersFactory(context) {

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
