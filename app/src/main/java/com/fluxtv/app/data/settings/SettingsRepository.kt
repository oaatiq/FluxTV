package com.fluxtv.app.data.settings

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fluxtv.app.data.remote.DEFAULT_USER_AGENT
import com.fluxtv.app.data.remote.headerValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.UUID

/** The name a playlist gets when the administrator gives it none. */
const val DEFAULT_PLAYLIST_NAME = "My Playlist"

/**
 * One playlist the administrator has saved. Its favorites and last channel belong to it, because
 * channel identities only mean something within their own playlist (REQ-PL-008): switching to
 * another playlist and back finds them as they were.
 */
@Serializable
data class SavedPlaylist(
    val id: String,
    val name: String,
    val url: String,
    val type: String = "url",
    val favorites: Set<String> = emptySet(),
    val lastChannelStableId: String? = null
)

fun newPlaylistId(): String = UUID.randomUUID().toString()

private object Keys {
    /** The saved playlists, as one JSON list. */
    val PLAYLISTS = stringPreferencesKey("playlists")
    /** The id of the one playlist in use. */
    val ACTIVE_PLAYLIST = stringPreferencesKey("active_playlist")
    val FAVORITES_ONLY = booleanPreferencesKey("favorites_only")
    val ADMIN_PIN = stringPreferencesKey("admin_pin")
    val MAX_VIDEO_HEIGHT = intPreferencesKey("max_video_height")
    val REFRESH_HOURS = intPreferencesKey("refresh_hours")
    val FRAME_RATE = booleanPreferencesKey("frame_rate_matching")
    val START_ON_BOOT = booleanPreferencesKey("start_on_boot")
    val USER_AGENT = stringPreferencesKey("user_agent")
}

/** Where versions before multiple playlists kept their one playlist; read once by [SinglePlaylistMigration]. */
private object LegacyKeys {
    val PLAYLIST_URL = stringPreferencesKey("playlist_url")
    val PLAYLIST_NAME = stringPreferencesKey("playlist_name")
    val PLAYLIST_TYPE = stringPreferencesKey("playlist_type")
    val LAST_CHANNEL = stringPreferencesKey("last_channel")
    val FAVORITES = stringSetPreferencesKey("favorites")
}

private val json = Json { ignoreUnknownKeys = true }
private val playlistsSerializer = ListSerializer(SavedPlaylist.serializer())

// An unreadable list reads as none, like an unreadable settings file: FluxTV then opens Settings.
private fun Preferences.playlists(): List<SavedPlaylist> {
    val raw = this[Keys.PLAYLISTS] ?: return emptyList()
    return try {
        json.decodeFromString(playlistsSerializer, raw)
    } catch (_: Exception) {
        emptyList()
    }
}

private fun MutablePreferences.setPlaylists(playlists: List<SavedPlaylist>) {
    this[Keys.PLAYLISTS] = json.encodeToString(playlistsSerializer, playlists)
}

/**
 * The one playlist of an earlier version becomes the first saved playlist, in use, with the favorites
 * and last channel it had. It runs before settings are first read after the update, so nothing is
 * set up again and TV starts on the same channel (REQ-UPD-001/002).
 */
private object SinglePlaylistMigration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        LegacyKeys.PLAYLIST_URL in currentData || LegacyKeys.PLAYLIST_NAME in currentData ||
            LegacyKeys.PLAYLIST_TYPE in currentData || LegacyKeys.LAST_CHANNEL in currentData ||
            LegacyKeys.FAVORITES in currentData

    override suspend fun migrate(currentData: Preferences): Preferences {
        val p = currentData.toMutablePreferences()
        val url = p[LegacyKeys.PLAYLIST_URL]
        if (!url.isNullOrBlank() && Keys.PLAYLISTS !in p) {
            val playlist = SavedPlaylist(
                id = newPlaylistId(),
                name = p[LegacyKeys.PLAYLIST_NAME]?.takeIf { it.isNotBlank() } ?: DEFAULT_PLAYLIST_NAME,
                url = url,
                type = p[LegacyKeys.PLAYLIST_TYPE] ?: "url",
                favorites = p[LegacyKeys.FAVORITES] ?: emptySet(),
                lastChannelStableId = p[LegacyKeys.LAST_CHANNEL]
            )
            p.setPlaylists(listOf(playlist))
            p[Keys.ACTIVE_PLAYLIST] = playlist.id
        }
        p.remove(LegacyKeys.PLAYLIST_URL)
        p.remove(LegacyKeys.PLAYLIST_NAME)
        p.remove(LegacyKeys.PLAYLIST_TYPE)
        p.remove(LegacyKeys.LAST_CHANNEL)
        p.remove(LegacyKeys.FAVORITES)
        return p.toPreferences()
    }

    override suspend fun cleanUp() = Unit
}

/**
 * A settings file that cannot be parsed (failing flash, a power cut at the wrong moment) made every
 * read throw, so FluxTV crashed at every start. It is replaced with empty settings instead: FluxTV
 * then opens Settings to be set up again, which is the most it can do with a file that is gone.
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "flux_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    produceMigrations = { listOf(SinglePlaylistMigration) }
)

/**
 * All persisted preferences. Stored in DataStore, which survives app restarts and updates
 * (REQ-REL-008, REQ-UPD-001, REQ-UPD-002).
 */
data class FluxSettings(
    val playlists: List<SavedPlaylist> = emptyList(),
    val activePlaylistId: String? = null,
    val favoritesOnly: Boolean = false,
    val adminPin: String = "",
    val maxVideoHeight: Int = 0,
    val refreshIntervalHours: Int = 12,
    val frameRateMatching: Boolean = true,
    val startOnBoot: Boolean = true,
    /** The player name (User-Agent) sent to the provider; blank for FluxTV's own. */
    val userAgent: String = ""
) {
    /** The User-Agent for the playlists and for channels whose playlist entry names none. */
    val effectiveUserAgent: String get() = headerValue(userAgent).ifBlank { DEFAULT_USER_AGENT }

    /** The one playlist Easy Mode shows. Channels of different playlists are never combined. */
    val activePlaylist: SavedPlaylist? = playlists.firstOrNull { it.id == activePlaylistId }

    /** The active playlist's own favorites and last channel. */
    val favorites: Set<String> = activePlaylist?.favorites.orEmpty()
    val lastChannelStableId: String? = activePlaylist?.lastChannelStableId

    val isConfigured: Boolean get() = !activePlaylist?.url.isNullOrBlank()
}

class SettingsRepository(private val context: Context) {

    // A file that cannot be read at all reads as the defaults, rather than throwing in every caller.
    val settings: Flow<FluxSettings> = context.dataStore.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }.map { p ->
        FluxSettings(
            playlists = p.playlists(),
            activePlaylistId = p[Keys.ACTIVE_PLAYLIST],
            favoritesOnly = p[Keys.FAVORITES_ONLY] ?: false,
            adminPin = p[Keys.ADMIN_PIN] ?: "",
            maxVideoHeight = p[Keys.MAX_VIDEO_HEIGHT] ?: 0,
            refreshIntervalHours = p[Keys.REFRESH_HOURS] ?: 12,
            frameRateMatching = p[Keys.FRAME_RATE] ?: true,
            startOnBoot = p[Keys.START_ON_BOOT] ?: true,
            userAgent = p[Keys.USER_AGENT] ?: ""
        )
    }

    /** Saves [playlist] as a new one. Only with [activate] does it become the playlist in use. */
    suspend fun addPlaylist(playlist: SavedPlaylist, activate: Boolean) {
        context.dataStore.edit {
            it.setPlaylists(it.playlists() + playlist)
            if (activate) it[Keys.ACTIVE_PLAYLIST] = playlist.id
        }
    }

    suspend fun setActivePlaylist(id: String) {
        context.dataStore.edit { if (it.playlists().any { p -> p.id == id }) it[Keys.ACTIVE_PLAYLIST] = id }
    }

    suspend fun renamePlaylist(id: String, name: String) = editPlaylist(id) { it.copy(name = name) }

    /** A new address for the same provider's playlist keeps its favorites and last channel. */
    suspend fun setPlaylistLink(id: String, url: String, type: String) =
        editPlaylist(id) { it.copy(url = url, type = type) }

    /** Deletes a saved playlist, never the one in use: false if [id] is in use (or already gone). */
    suspend fun deletePlaylist(id: String): Boolean {
        var deleted = false
        context.dataStore.edit {
            if (it[Keys.ACTIVE_PLAYLIST] == id) return@edit
            val all = it.playlists()
            val rest = all.filterNot { p -> p.id == id }
            if (rest.size != all.size) {
                it.setPlaylists(rest)
                deleted = true
            }
        }
        return deleted
    }

    suspend fun setLastChannel(playlistId: String, stableId: String) =
        editPlaylist(playlistId) { it.copy(lastChannelStableId = stableId) }

    suspend fun toggleFavorite(playlistId: String, stableId: String) = editPlaylist(playlistId) {
        it.copy(favorites = if (stableId in it.favorites) it.favorites - stableId else it.favorites + stableId)
    }

    private suspend fun editPlaylist(id: String, change: (SavedPlaylist) -> SavedPlaylist) {
        context.dataStore.edit {
            val all = it.playlists()
            if (all.none { p -> p.id == id }) return@edit
            it.setPlaylists(all.map { p -> if (p.id == id) change(p) else p })
        }
    }

    suspend fun setFavoritesOnly(value: Boolean) {
        context.dataStore.edit { it[Keys.FAVORITES_ONLY] = value }
    }

    suspend fun setAdminPin(pin: String) {
        context.dataStore.edit { it[Keys.ADMIN_PIN] = pin }
    }

    suspend fun setMaxVideoHeight(height: Int) {
        context.dataStore.edit { it[Keys.MAX_VIDEO_HEIGHT] = height }
    }

    suspend fun setRefreshHours(hours: Int) {
        context.dataStore.edit { it[Keys.REFRESH_HOURS] = hours }
    }

    suspend fun setFrameRateMatching(value: Boolean) {
        context.dataStore.edit { it[Keys.FRAME_RATE] = value }
    }

    suspend fun setStartOnBoot(value: Boolean) {
        context.dataStore.edit { it[Keys.START_ON_BOOT] = value }
    }

    suspend fun setUserAgent(value: String) {
        context.dataStore.edit { it[Keys.USER_AGENT] = value.trim() }
    }
}
