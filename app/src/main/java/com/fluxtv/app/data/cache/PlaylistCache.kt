package com.fluxtv.app.data.cache

import android.content.Context
import android.util.AtomicFile
import com.fluxtv.app.data.model.Channel
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.File
import java.io.FileOutputStream

/**
 * [playlistId]: the saved playlist these channels are from. A cache written before there were several
 * playlists has none, and belongs to the playlist that was in use then, which is the one in use after
 * the update. [guideUrls]: the programme guides the playlist names.
 */
@Serializable
data class CachedPlaylist(
    val channels: List<Channel>,
    val updatedAtMs: Long,
    val sourceName: String? = null,
    val playlistId: String? = null,
    val guideUrls: List<String> = emptyList()
)

/**
 * Persists the last successfully parsed playlist to disk so FluxTV starts instantly and keeps
 * working offline / when a refresh fails (REQ-START-004, REQ-PL-005).
 *
 * TVs lose power mid-write (a power strip, a plug pulled), and a truncated cache would start the next
 * boot with no channels, so the file goes through [AtomicFile]: until a write has finished, a read
 * gets the previous playlist. It is streamed both ways, never held as one string, and reads and
 * writes take turns, because AtomicFile drops a write that a read runs into.
 */
@OptIn(ExperimentalSerializationApi::class)
class PlaylistCache(context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file = AtomicFile(File(context.filesDir, "playlist_cache.json"))

    @Synchronized
    fun load(): CachedPlaylist? = try {
        file.openRead().buffered().use { json.decodeFromStream<CachedPlaylist>(it) }
    } catch (_: Exception) {
        // No cache yet, or one that cannot be read: start without it.
        null
    }

    @Synchronized
    fun save(playlist: CachedPlaylist) {
        var stream: FileOutputStream? = null
        try {
            stream = file.startWrite()
            val out = stream.buffered()
            json.encodeToStream(playlist, out)
            out.flush()
            file.finishWrite(stream)
        } catch (_: Exception) {
            // Non-fatal: an unwritable cache must never crash the app (REQ-REL). The previous one stays.
            if (stream != null) file.failWrite(stream)
        }
    }
}
