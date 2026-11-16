package com.fluxtv.app.update

import android.util.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

/**
 * An installation as it was started: what it installs, over what, how, and whether FluxTV was on
 * screen. Written before Android replaces FluxTV, so that the new version can say how it went and
 * come back on screen if it was there.
 */
@Serializable
data class InstallAttempt(
    val versionCode: Long,
    val versionName: String,
    val fromVersionCode: Long,
    val fromVersionName: String,
    val route: InstallRoute,
    val relaunch: Boolean,
    val startedMs: Long
)

/**
 * What FluxTV knows about its updates, kept across restarts and across the update itself (in the
 * app's no-backup files, next to the downloaded build).
 */
@Serializable
data class UpdateRecord(
    val lastCheckMs: Long = 0,
    /** Why the last look for updates failed; null once one works. */
    val checkProblem: String? = null,
    /** The newest build the last look found, if it is newer than this one. */
    val latest: UpdateInfo? = null,
    /** A verified download of this build is ready to install. */
    val readyVersionCode: Long = 0,
    val readySinceMs: Long = 0,
    /** Failed tries at [latest] (download or installation), and when the next one may be. */
    val failures: Int = 0,
    val nextTryMs: Long = 0,
    /** A build Android will never install over this FluxTV: not tried again by itself. */
    val blockedVersionCode: Long = 0,
    /** The last thing that went wrong, in words, until something works. */
    val problem: String? = null,
    /** When Android last asked about an update, and whether it asked where it should not have. */
    val lastAskedMs: Long = 0,
    val androidAsks: Boolean = false,
    val installing: InstallAttempt? = null,
    /** The build that ran last, to notice a new one, however it was installed. */
    val lastRunVersionCode: Long = 0
)

/** [UpdateRecord] in a file; a power cut while writing leaves the previous one. */
class UpdateStore(dir: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val file = AtomicFile(File(dir, "update-state.json"))

    @Synchronized
    fun read(): UpdateRecord = try {
        json.decodeFromString(UpdateRecord.serializer(), String(file.readFully(), Charsets.UTF_8))
    } catch (_: Exception) {
        UpdateRecord()
    }

    @Synchronized
    fun write(record: UpdateRecord) {
        var stream: FileOutputStream? = null
        try {
            stream = file.startWrite()
            stream.write(json.encodeToString(UpdateRecord.serializer(), record).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (_: Exception) {
            if (stream != null) file.failWrite(stream)
        }
    }
}
