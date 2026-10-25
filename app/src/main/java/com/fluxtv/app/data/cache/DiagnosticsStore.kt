package com.fluxtv.app.data.cache

import android.content.Context
import android.util.Log
import com.fluxtv.app.data.model.DiagnosticsEvent
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Bounded, persisted ring buffer of diagnostic events for administrator troubleshooting (§21). */
class DiagnosticsStore(context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val file = File(context.filesDir, "diagnostics.json")
    private val max = 300

    @Synchronized
    fun load(): List<DiagnosticsEvent> = try {
        if (file.exists()) json.decodeFromString<List<DiagnosticsEvent>>(file.readText()) else emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    @Synchronized
    fun append(event: DiagnosticsEvent): List<DiagnosticsEvent> {
        if (event.category in LOGCAT_CATEGORIES) Log.i(LOG_TAG, "[${event.category}] ${event.message}")
        val list = (load() + event).takeLast(max)
        try {
            file.writeText(json.encodeToString(list))
        } catch (_: Exception) {
        }
        return list
    }

    @Synchronized
    fun clear() {
        try {
            file.delete()
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val LOG_TAG = "FluxTV"

        /**
         * Turning on, going into standby and coming back are the events that have to be followed
         * from outside the app — they happen while nobody can open Settings — so these categories
         * are mirrored to logcat (`adb logcat -s FluxTV:I`). The rest stay in the file only: a
         * playback line carries the channel's URL, and a playlist URL can carry credentials.
         */
        val LOGCAT_CATEGORIES = setOf("boot", "wake", "standby")
    }
}
