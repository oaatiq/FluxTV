package com.fluxtv.app.data.model

import kotlinx.serialization.Serializable

/**
 * A single live television channel.
 *
 * [stableId] is derived from the provider's tvg-id when available, otherwise from a hash of the
 * name + URL. It stays constant across playlist refreshes so favorites, numbering and the
 * last-watched channel survive provider changes (REQ-PL-008, REQ-UPD-003).
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
    val referer: String? = null
)
