package com.fluxtv.app.playback

import androidx.media3.common.PlaybackException

enum class ErrorKind { NETWORK, SOURCE, DECODER, DRM, NO_NETWORK, UNKNOWN }

data class MappedError(
    val kind: ErrorKind,
    val userMessage: String,
    val recoverable: Boolean,
    val technical: String
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

    fun map(error: PlaybackException, hasNetwork: Boolean): MappedError {
        val technical = "${error.errorCodeName}: ${error.message ?: ""}".trim()

        if (!hasNetwork) {
            return MappedError(ErrorKind.NO_NETWORK, NO_NETWORK, recoverable = true, technical = technical)
        }

        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_TIMEOUT ->
                MappedError(ErrorKind.NETWORK, CONNECTION_INTERRUPTED, recoverable = true, technical = technical)

            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
                MappedError(ErrorKind.SOURCE, CHANNEL_UNAVAILABLE, recoverable = true, technical = technical)

            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
                MappedError(ErrorKind.DECODER, CANNOT_PLAY, recoverable = false, technical = technical)

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
                MappedError(ErrorKind.DRM, CANNOT_PLAY, recoverable = false, technical = technical)

            else ->
                MappedError(ErrorKind.UNKNOWN, CHANNEL_UNAVAILABLE, recoverable = true, technical = technical)
        }
    }
}
