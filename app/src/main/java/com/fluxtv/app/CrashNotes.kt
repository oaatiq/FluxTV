package com.fluxtv.app

import android.content.Context
import com.fluxtv.app.data.cache.DiagnosticsStore
import com.fluxtv.app.data.model.DiagnosticsEvent
import java.io.File

/**
 * Leaves a note in the Diagnostics history when FluxTV crashes: what failed, where in FluxTV, and on
 * which channel. A crash used to leave no trace a TV could show. The history writes in the
 * background and the process is gone a moment after a crash, so the crashing thread writes the note
 * to a file of its own, and the next start moves it into the history.
 */
object CrashNotes {
    private const val FILE = "last-crash.txt"

    /** [channel]: the channel playing at the time, if any. */
    fun install(context: Context, channel: () -> String?) {
        val file = File(context.filesDir, FILE)
        val android = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val playing = runCatching { channel() }.getOrNull().orEmpty().replace('\n', ' ')
                file.writeText("${System.currentTimeMillis()}\n$playing\n${describe(thread, error)}")
            } catch (_: Throwable) {
                // Nothing may stop Android's own handler below from running.
            }
            android?.uncaughtException(thread, error)
        }
    }

    /** Moves the note the last crash left, if any, into the history. */
    fun collect(context: Context, store: DiagnosticsStore) {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        file.delete()
        val time = lines.getOrNull(0)?.toLongOrNull() ?: System.currentTimeMillis()
        val channel = lines.getOrNull(1)?.ifBlank { null }
        val what = lines.drop(2).joinToString(" ").ifBlank { "no details" }
        store.append(DiagnosticsEvent(time, channel, "crash", "FluxTV stopped: $what"))
    }

    /** The error and its first cause, each with the place in FluxTV's code it came from. */
    private fun describe(thread: Thread, error: Throwable): String {
        val root = generateSequence(error) { it.cause }.take(20).last()
        fun one(t: Throwable): String {
            val where = t.stackTrace.firstOrNull { it.className.startsWith("com.fluxtv.") } ?: t.stackTrace.firstOrNull()
            return "${t.javaClass.name}: ${t.message.orEmpty()}".replace('\n', ' ').take(300) +
                (where?.let { " at $it" } ?: "")
        }
        return one(error) + (if (root !== error) " — caused by " + one(root) else "") + " (thread ${thread.name})"
    }
}
