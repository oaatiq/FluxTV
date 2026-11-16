package com.fluxtv.app.playback

import com.fluxtv.app.data.model.Channel

/** Coarse playback phase that drives everything the Viewer sees. */
enum class PlaybackPhase { IDLE, PREPARING, PLAYING, BUFFERING, RECOVERING, FAILED }

/**
 * What the Easy Mode UI needs to know about playback.
 *
 * [userMessage] is already a simple, non-technical sentence (§20) or null when nothing should be
 * shown. [showRecoveryPrompt] is true only after automatic recovery has been exhausted (REQ-REC-005).
 * [problem] says why, while FluxTV retries and on the prompt: for the icon beside the message and
 * the prompt's reason.
 *
 * The rest is what can go wrong while a channel plays at all, each shown on screen for as long as it
 * lasts and gone again by itself: [networkLost], Android reports no network (the channel may still
 * be playing what it buffered); [slowConnection], the channel keeps stopping to load; and
 * [pictureProblem], the sound plays but the picture does not ([PlaybackProblem.FROZEN],
 * [PlaybackProblem.SOUND_ONLY]).
 */
data class PlaybackStatus(
    val phase: PlaybackPhase = PlaybackPhase.IDLE,
    val channel: Channel? = null,
    val userMessage: String? = null,
    val showRecoveryPrompt: Boolean = false,
    val recoveryAttempt: Int = 0,
    val problem: PlaybackProblem? = null,
    val networkLost: Boolean = false,
    val slowConnection: Boolean = false,
    val pictureProblem: PlaybackProblem? = null
)
