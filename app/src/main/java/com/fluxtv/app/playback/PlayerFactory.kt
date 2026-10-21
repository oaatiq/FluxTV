package com.fluxtv.app.playback

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter

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
        val bandwidthMeter: DefaultBandwidthMeter
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

        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

        val player = ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setBandwidthMeter(bandwidthMeter)
            .setMediaSourceFactory(mediaSourceFactory)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS)
                setWakeMode(C.WAKE_MODE_NETWORK)
                setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ true)
            }

        return Bundle(player, httpDataSourceFactory, trackSelector, bandwidthMeter)
    }
}
