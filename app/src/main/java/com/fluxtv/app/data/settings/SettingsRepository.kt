package com.fluxtv.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "flux_settings")

/**
 * All persisted preferences. Stored in DataStore, which survives app restarts and updates
 * (REQ-REL-008, REQ-UPD-001, REQ-UPD-002).
 */
data class FluxSettings(
    val playlistUrl: String? = null,
    val playlistName: String? = null,
    val playlistType: String = "url",
    val lastChannelStableId: String? = null,
    val favorites: Set<String> = emptySet(),
    val favoritesOnly: Boolean = false,
    val adminPin: String = "",
    val maxVideoHeight: Int = 0,
    val refreshIntervalHours: Int = 12,
    val frameRateMatching: Boolean = true,
    val startOnBoot: Boolean = true
) {
    val isConfigured: Boolean get() = !playlistUrl.isNullOrBlank()
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val PLAYLIST_URL = stringPreferencesKey("playlist_url")
        val PLAYLIST_NAME = stringPreferencesKey("playlist_name")
        val PLAYLIST_TYPE = stringPreferencesKey("playlist_type")
        val LAST_CHANNEL = stringPreferencesKey("last_channel")
        val FAVORITES = stringSetPreferencesKey("favorites")
        val FAVORITES_ONLY = booleanPreferencesKey("favorites_only")
        val ADMIN_PIN = stringPreferencesKey("admin_pin")
        val MAX_VIDEO_HEIGHT = intPreferencesKey("max_video_height")
        val REFRESH_HOURS = intPreferencesKey("refresh_hours")
        val FRAME_RATE = booleanPreferencesKey("frame_rate_matching")
        val START_ON_BOOT = booleanPreferencesKey("start_on_boot")
    }

    val settings: Flow<FluxSettings> = context.dataStore.data.map { p ->
        FluxSettings(
            playlistUrl = p[Keys.PLAYLIST_URL],
            playlistName = p[Keys.PLAYLIST_NAME],
            playlistType = p[Keys.PLAYLIST_TYPE] ?: "url",
            lastChannelStableId = p[Keys.LAST_CHANNEL],
            favorites = p[Keys.FAVORITES] ?: emptySet(),
            favoritesOnly = p[Keys.FAVORITES_ONLY] ?: false,
            adminPin = p[Keys.ADMIN_PIN] ?: "",
            maxVideoHeight = p[Keys.MAX_VIDEO_HEIGHT] ?: 0,
            refreshIntervalHours = p[Keys.REFRESH_HOURS] ?: 12,
            frameRateMatching = p[Keys.FRAME_RATE] ?: true,
            startOnBoot = p[Keys.START_ON_BOOT] ?: true
        )
    }

    suspend fun setPlaylist(url: String, name: String, type: String) {
        context.dataStore.edit {
            it[Keys.PLAYLIST_URL] = url
            it[Keys.PLAYLIST_NAME] = name
            it[Keys.PLAYLIST_TYPE] = type
        }
    }

    suspend fun setLastChannel(stableId: String) {
        context.dataStore.edit { it[Keys.LAST_CHANNEL] = stableId }
    }

    suspend fun toggleFavorite(stableId: String) {
        context.dataStore.edit {
            val current = it[Keys.FAVORITES]?.toMutableSet() ?: mutableSetOf()
            if (!current.add(stableId)) current.remove(stableId)
            it[Keys.FAVORITES] = current
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
}
