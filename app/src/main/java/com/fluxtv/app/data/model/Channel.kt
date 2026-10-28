package com.fluxtv.app.data.model

import kotlinx.serialization.Serializable

/**
 * A single live television channel.
 *
 * [stableId] is derived from the provider's tvg-id when available, otherwise from a hash of the
 * name + URL. It stays constant across playlist refreshes so favorites, numbering and the
 * last-watched channel survive provider changes (REQ-PL-008, REQ-UPD-003).
 *
 * [tvgId] is the provider's programme-guide key for the channel, as the playlist gives it. A playlist
 * cached before it was kept has none until its next refresh; the guide then goes by the name.
 * [tvgName]: the playlist's tvg-name, when it differs from [name], which the guide is also searched
 * by. [guideShiftMin]: the playlist's tvg-shift, in minutes, by which the guide's times are moved.
 */
@Serializable
data class Channel(
    val stableId: String,
    val number: Int,
    val name: String,
    val url: String,
    val logoUrl: String? = null,
    val group: String? = null,
    val userAgent: String? = null,
    val referer: String? = null,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val guideShiftMin: Int = 0
)
