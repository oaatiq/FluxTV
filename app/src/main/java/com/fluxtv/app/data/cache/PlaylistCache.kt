package com.fluxtv.app.data.cache

import android.content.Context
import com.fluxtv.app.data.model.Channel
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class CachedPlaylist(
    val channels: List<Channel>,
    val updatedAtMs: Long,
    val sourceName: String? = null
)

/**
 * Persists the last successfully parsed playlist to disk so FluxTV starts instantly and keeps
 * working offline / when a refresh fails (REQ-START-004, REQ-PL-005).
 */
class PlaylistCache(context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file = File(context.filesDir, "playlist_cache.json")

    fun load(): CachedPlaylist? = try {
        if (file.exists()) json.decodeFromString<CachedPlaylist>(file.readText()) else null
    } catch (_: Exception) {
        null
    }

    fun save(playlist: CachedPlaylist) {
        try {
            file.writeText(json.encodeToString(playlist))
        } catch (_: Exception) {
            // Non-fatal: an unwritable cache must never crash the app (REQ-REL).
        }
    }
}
