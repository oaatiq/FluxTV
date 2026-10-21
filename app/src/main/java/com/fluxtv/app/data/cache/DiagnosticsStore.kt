package com.fluxtv.app.data.cache

import android.content.Context
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
}
