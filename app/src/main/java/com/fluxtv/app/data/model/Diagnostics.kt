package com.fluxtv.app.data.model

import kotlinx.serialization.Serializable

/** Live technical snapshot of the current playback, shown only in the admin Diagnostics screen (§21). */
data class DiagnosticsSnapshot(
    val channelName: String? = null,
    val channelUrl: String? = null,
    val reachable: Boolean? = null,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val frameRate: Float = 0f,
    val videoBitrate: Int = 0,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val videoDecoder: String? = null,
    val hardwareDecoding: Boolean? = null,
    val bufferedMs: Long = 0L,
    val bandwidthBps: Long = 0L,
    val droppedFrames: Int = 0,
    val bufferingEvents: Int = 0,
    val recoveryCount: Int = 0,
    val startupTimeMs: Long = 0L,
    val playbackState: String = "IDLE"
)

/** A persisted diagnostic history entry (REQ-DIA-004: investigate intermittent problems). */
@Serializable
data class DiagnosticsEvent(
    val timestampMs: Long,
    val channelName: String?,
    val category: String,
    val message: String
)
