package com.fluxtv.app.playback

/**
 * Bounded exponential backoff for automatic recovery.
 *
 * Attempts are capped and delays grow, so recovery can never become an uncontrolled retry loop
 * (REQ-REC-002, REQ-REC-007, REQ-BUF-007). The counter is reset by the controller after a channel
 * has played stably for a while, restoring a full budget for genuinely new disturbances.
 */
class RecoveryPolicy(
    private val maxAttempts: Int = 6,
    private val baseDelayMs: Long = 1_000L,
    private val maxDelayMs: Long = 15_000L
) {
    var attempt: Int = 0
        private set

    fun reset() {
        attempt = 0
    }

    fun canRetry(): Boolean = attempt < maxAttempts

    /** Returns the delay to wait before the next attempt and advances the counter. */
    fun nextDelayMs(): Long {
        val shift = attempt.coerceAtMost(4)
        val delay = (baseDelayMs shl shift).coerceAtMost(maxDelayMs)
        attempt++
        return delay
    }
}
