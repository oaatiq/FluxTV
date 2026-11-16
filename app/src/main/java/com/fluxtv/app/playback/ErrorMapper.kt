package com.fluxtv.app.playback

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.HttpDataSource

enum class ErrorKind { NETWORK, SOURCE, DECODER, DRM, NO_NETWORK, UNKNOWN }

/**
 * Why a channel could not be brought back, as the recovery prompt says it: [title] is its heading,
 * [hint] the line under it. Each says what went wrong in the Viewer's words, so the prompt itself
 * tells a TV that has no network from a provider that refuses the channel or a TV that cannot decode
 * it; until these, every case had the same heading, "This channel is temporarily unavailable."
 */
enum class PlaybackProblem(val title: String, val hint: String) {
    NO_NETWORK(
        "No network connection.",
        "Check this TV's internet connection. The channel comes back on its own when it returns."
    ),
    CONNECTION(
        "The connection to this channel keeps failing.",
        "The provider's server is not answering, or the connection is too slow. FluxTV keeps trying."
    ),
    REFUSED(
        "The provider refused this channel.",
        "Another TV may be using the same subscription, or the subscription has ended."
    ),
    GONE(
        "The provider does not have this channel.",
        "It may have moved, or been removed from the playlist."
    ),
    UNAVAILABLE(
        "This channel is temporarily unavailable.",
        "The provider is not sending it right now. FluxTV keeps trying."
    ),
    CANNOT_PLAY(
        "This TV can't play this channel.",
        "It is protected, or in a format this TV cannot decode."
    )
}

data class MappedError(
    val kind: ErrorKind,
    val userMessage: String,
    val recoverable: Boolean,
    val technical: String,
    val problem: PlaybackProblem
)

/**
 * Translates a technical [PlaybackException] into the Viewer-facing message table from §20.
 * The raw technical string is preserved separately for the admin Diagnostics screen only.
 */
object ErrorMapper {

    const val CONNECTION_INTERRUPTED = "Connection interrupted. Trying again…"
    const val CHANNEL_UNAVAILABLE = "Channel temporarily unavailable."
    const val CANNOT_PLAY = "Unable to play this channel."
    const val NO_NETWORK = "No network connection."

    /**
     * HTTP answers with which a provider refuses this TV rather than the channel being missing:
     * wrong or expired credentials (401, 402), a ban or too many TVs on one subscription (403, 429,
     * and 458 and 509, which some IPTV panels send for "maximum connections reached").
     */
    private val REFUSED_STATUSES = setOf(401, 402, 403, 429, 458, 509)

    /** The message Media3 gives every source error; it says nothing the error code does not. */
    private const val GENERIC_SOURCE_MESSAGE = "Source error"

    fun map(error: PlaybackException, hasNetwork: Boolean): MappedError {
        val technical = technicalText(error)

        if (!hasNetwork) {
            return MappedError(ErrorKind.NO_NETWORK, NO_NETWORK, recoverable = true, technical, PlaybackProblem.NO_NETWORK)
        }

        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_TIMEOUT ->
                MappedError(ErrorKind.NETWORK, CONNECTION_INTERRUPTED, recoverable = true, technical, PlaybackProblem.CONNECTION)

            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
                val problem = when (httpStatus(error)) {
                    in REFUSED_STATUSES -> PlaybackProblem.REFUSED
                    404, 410 -> PlaybackProblem.GONE
                    else -> PlaybackProblem.UNAVAILABLE
                }
                MappedError(ErrorKind.SOURCE, CHANNEL_UNAVAILABLE, recoverable = true, technical, problem)
            }

            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
                MappedError(ErrorKind.SOURCE, CHANNEL_UNAVAILABLE, recoverable = true, technical, PlaybackProblem.UNAVAILABLE)

            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
                MappedError(ErrorKind.DECODER, CANNOT_PLAY, recoverable = false, technical, PlaybackProblem.CANNOT_PLAY)

            // FluxTV has no DRM, so a protected channel will not play however often it is retried;
            // these used to fall through to "temporarily unavailable" and be retried forever.
            PlaybackException.ERROR_CODE_DRM_UNSPECIFIED,
            PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED,
            PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR,
            PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
            PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION,
            PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR,
            PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED,
            PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED ->
                MappedError(ErrorKind.DRM, CANNOT_PLAY, recoverable = false, technical, PlaybackProblem.CANNOT_PLAY)

            else ->
                MappedError(ErrorKind.UNKNOWN, CHANNEL_UNAVAILABLE, recoverable = true, technical, PlaybackProblem.UNAVAILABLE)
        }
    }

    /**
     * The error for the Diagnostics history, with what caused it. Media3 calls every network and
     * stream error just "Source error", so the history used to read "ERROR_CODE_IO_BAD_HTTP_STATUS:
     * Source error", which does not tell a refusal (403) from a missing channel (404) or a failing
     * server (5xx). It now reads "ERROR_CODE_IO_BAD_HTTP_STATUS: HTTP 403 Forbidden", and for other
     * errors names the exception underneath: a certificate the TV does not trust, a name that does
     * not resolve, a timeout.
     */
    fun technicalText(error: PlaybackException): String {
        val detail = listOfNotNull(
            error.message?.trim()?.takeUnless { it.isEmpty() || it == GENERIC_SOURCE_MESSAGE },
            causeDetail(error)
        ).joinToString(" — ")
        return if (detail.isEmpty()) error.errorCodeName else "${error.errorCodeName}: $detail"
    }

    /** The status a server answered with, when that is what failed. */
    private fun httpStatus(error: PlaybackException): Int? =
        causes(error).filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode

    private fun causeDetail(error: PlaybackException): String? {
        val chain = causes(error)
        chain.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.let { http ->
            val reason = http.responseMessage?.trim().orEmpty()
            return if (reason.isEmpty()) "HTTP ${http.responseCode}" else "HTTP ${http.responseCode} $reason"
        }
        // Media3's own data source exceptions only wrap the one that says what happened.
        val cause = chain.firstOrNull { !(it is DataSourceException && it.cause != null) } ?: return null
        val message = cause.message?.trim().orEmpty()
        val name = cause.javaClass.simpleName.ifEmpty { cause.javaClass.name }
        return if (message.isEmpty()) name else "$name: $message"
    }

    /** The causes behind [error], outermost first, each once. */
    private fun causes(error: Throwable): List<Throwable> {
        val chain = ArrayList<Throwable>()
        var cause = error.cause
        while (cause != null && cause !in chain) {
            chain.add(cause)
            cause = cause.cause
        }
        return chain
    }
}
