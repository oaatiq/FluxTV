package com.fluxtv.app.playback

import com.fluxtv.app.data.model.Channel

/** Coarse playback phase that drives everything the Viewer sees. */
enum class PlaybackPhase { IDLE, PREPARING, PLAYING, BUFFERING, RECOVERING, FAILED }

/**
 * What the Easy Mode UI needs to know about playback.
 *
 * [userMessage] is already a simple, non-technical sentence (§20) or null when nothing should be
 * shown. [showRecoveryPrompt] is true only after automatic recovery has been exhausted (REQ-REC-005).
 */
data class PlaybackStatus(
    val phase: PlaybackPhase = PlaybackPhase.IDLE,
    val channel: Channel? = null,
    val userMessage: String? = null,
    val showRecoveryPrompt: Boolean = false,
    val recoveryAttempt: Int = 0
)
