package com.fluxtv.app.data.cache

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.fluxtv.app.data.model.DiagnosticsEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Bounded, persisted ring buffer of diagnostic events for administrator troubleshooting (§21).
 *
 * The history lives in memory: every writer in the process (the player, the boot and wake receivers,
 * the Shizuku helper) adds to the same list, and the Diagnostics screen watches it. On disk each
 * event is one line appended to the file, which is rewritten to the latest [MAX] only once it holds
 * twice that many; it used to be read, parsed, re-encoded and rewritten whole for every event, three
 * or four times per channel change. One thread does all of it, so events keep the order they were
 * added in and the file never has two writers.
 */
class DiagnosticsStore(context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val file = AtomicFile(File(context.filesDir, "diagnostics.jsonl"))

    /** The earlier format, one JSON array rewritten for every event: read once, then replaced. */
    private val legacyFile = File(context.filesDir, "diagnostics.json")

    @OptIn(ExperimentalCoroutinesApi::class) // limitedParallelism, stable since kotlinx.coroutines 1.9
    private val owner = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + owner)

    private val _events = MutableStateFlow<List<DiagnosticsEvent>>(emptyList())
    val events: StateFlow<List<DiagnosticsEvent>> = _events.asStateFlow()

    /** Lines in the file, which is compacted at twice [MAX]. Only the owner thread touches it. */
    private var linesOnDisk = 0

    init {
        // The owner thread's first task, so everything added later goes after the saved history.
        scope.launch { load() }
    }

    /** Adds [event] without waiting for the disk: cheap enough for the main thread. */
    fun append(event: DiagnosticsEvent) {
        if (event.category in LOGCAT_CATEGORIES) Log.i(LOG_TAG, "[${event.category}] ${event.message}")
        scope.launch {
            _events.value = (_events.value + event).takeLast(MAX)
            try {
                FileOutputStream(file.baseFile, true).use { it.write((json.encodeToString(event) + "\n").toByteArray()) }
                if (++linesOnDisk >= 2 * MAX) rewrite()
            } catch (_: Exception) {
            }
        }
    }

    /** Returns once every event added before it is on disk, for a caller whose process may end next. */
    suspend fun flush() = withContext(owner) {}

    fun clear() {
        scope.launch {
            _events.value = emptyList()
            linesOnDisk = 0
            file.delete()
            legacyFile.delete()
        }
    }

    private fun load() {
        val saved = ArrayList<DiagnosticsEvent>()
        var clean = true
        try {
            file.openRead().bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    linesOnDisk++
                    val event = try {
                        json.decodeFromString<DiagnosticsEvent>(line)
                    } catch (_: Exception) {
                        null
                    }
                    if (event != null) saved += event else clean = false
                }
            }
            // A power cut can end the file inside a line, and the next event would be glued onto it.
            if (!endsWithNewline(file.baseFile)) clean = false
        } catch (_: FileNotFoundException) {
            // No history in this format yet.
        } catch (_: Exception) {
            clean = false
        }
        if (legacyFile.exists()) {
            if (saved.isEmpty()) {
                try {
                    saved += json.decodeFromString<List<DiagnosticsEvent>>(legacyFile.readText())
                } catch (_: Exception) {
                }
            }
            clean = false
        }
        _events.value = saved.takeLast(MAX)
        if (!clean && rewrite()) legacyFile.delete()
    }

    /** Replaces the file with the events in memory; a power cut on the way leaves the previous file. */
    private fun rewrite(): Boolean {
        var stream: FileOutputStream? = null
        return try {
            stream = file.startWrite()
            val out = stream.bufferedWriter()
            val events = _events.value
            for (e in events) {
                out.write(json.encodeToString(e))
                out.write("\n")
            }
            out.flush()
            file.finishWrite(stream)
            linesOnDisk = events.size
            true
        } catch (_: Exception) {
            if (stream != null) file.failWrite(stream)
            false
        }
    }

    private fun endsWithNewline(f: File): Boolean = try {
        RandomAccessFile(f, "r").use { raf ->
            raf.length() == 0L || raf.run { seek(length() - 1); read() } == '\n'.code
        }
    } catch (_: Exception) {
        false
    }

    private companion object {
        const val LOG_TAG = "FluxTV"
        const val MAX = 300

        /**
         * Turning on, going into standby and coming back are the events that have to be followed
         * from outside the app — they happen while nobody can open Settings — so these categories
         * are mirrored to logcat (`adb logcat -s FluxTV:I`). The rest stay in the file only: a
         * playback line carries the channel's URL, and a playlist URL can carry credentials.
         */
        val LOGCAT_CATEGORIES = setOf("boot", "wake", "standby")
    }
}
