package com.fluxtv.app.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

sealed class DownloadResult {
    data object Done : DownloadResult()
    data class Failed(val reason: String) : DownloadResult()
}

/** The SHA-256 of [file], in lower-case hex, as update.json writes it. */
fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * Downloads a build of FluxTV to the TV, in the background:
 *
 * - **It continues where it stopped.** The file grows as `….apk.part`, and a download cut short (the
 *   network went, the TV went into standby, the job's time ran out) asks the server for the rest
 *   next time (an HTTP Range request) instead of starting over, which matters on a slow line.
 * - **It leaves the channel its bandwidth.** [bytesPerSecond] is asked as it goes: while a channel
 *   plays it is a fraction of the line, so the picture does not stall for an update; off screen it
 *   is unlimited.
 * - **Only a whole, intact file counts.** The file is renamed to [target] only once its size and
 *   SHA-256 match what the release says. A damaged one is deleted and fetched again later.
 */
class UpdateDownloader(
    private val client: () -> OkHttpClient,
    private val userAgent: String
) {

    suspend fun download(
        info: UpdateInfo,
        target: File,
        bytesPerSecond: () -> Long,
        onProgress: (done: Long, total: Long) -> Unit
    ): DownloadResult = withContext(Dispatchers.IO) {
        val dir = target.parentFile ?: return@withContext DownloadResult.Failed("No folder for the download")
        dir.mkdirs()
        if (target.exists()) {
            if (sha256Hex(target) == info.sha256) return@withContext DownloadResult.Done
            target.delete()
        }
        val part = File(dir, target.name + ".part")
        var have = if (part.exists()) part.length() else 0L
        if (info.size > 0 && have > info.size) {
            part.delete()
            have = 0L
        }

        if (info.size <= 0 || have < info.size) {
            val result = fetch(info, part, have, dir, bytesPerSecond, onProgress)
            if (result != null) return@withContext result
        }

        if (info.size > 0 && part.length() != info.size) {
            return@withContext DownloadResult.Failed("The download stopped before the end; it goes on from there next time")
        }
        if (sha256Hex(part) != info.sha256) {
            part.delete()
            return@withContext DownloadResult.Failed("The downloaded file is damaged (its checksum does not match); it is fetched again")
        }
        if (!part.renameTo(target)) {
            part.delete()
            return@withContext DownloadResult.Failed("The downloaded file could not be stored")
        }
        DownloadResult.Done
    }

    /** Fetches the file into [part] from byte [start]; null once the server has sent all it has. */
    private suspend fun fetch(
        info: UpdateInfo,
        part: File,
        start: Long,
        dir: File,
        bytesPerSecond: () -> Long,
        onProgress: (done: Long, total: Long) -> Unit
    ): DownloadResult? {
        var have = start
        val remaining = if (info.size > 0) info.size - have else UpdateManifest.MAX_APK_BYTES / 4
        val usable = dir.usableSpace
        if (usable > 0 && usable < remaining + SPACE_MARGIN_BYTES) {
            val mb = (remaining + SPACE_MARGIN_BYTES) / (1024 * 1024)
            return DownloadResult.Failed("Not enough free space on this TV for the update (it needs about $mb MB)")
        }

        val request = Request.Builder()
            .url(info.apkUrl)
            .header("User-Agent", userAgent)
            .apply { if (have > 0) header("Range", "bytes=$have-") }
            .build()
        val call = client().newCall(request)
        // A blocking read does not see the coroutine being cancelled; cancelling the call ends it.
        val cancelOnStop = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { response ->
                val append = when {
                    response.code == 206 && have > 0 -> true
                    response.isSuccessful -> false
                    response.code == 416 -> {
                        // What was kept does not fit the file on the server any more: start over.
                        part.delete()
                        return DownloadResult.Failed("The download has to start over; it does next time")
                    }
                    else -> return DownloadResult.Failed("The update could not be downloaded (HTTP ${response.code})")
                }
                if (!append) have = 0L
                val body = response.body ?: return DownloadResult.Failed("The update server sent nothing")
                val limit = if (info.size > 0) info.size else UpdateManifest.MAX_APK_BYTES
                FileOutputStream(part, append).use { out ->
                    val input = body.byteStream()
                    val buffer = ByteArray(32 * 1024)
                    var rate = bytesPerSecond()
                    var windowStartNs = System.nanoTime()
                    var windowBytes = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        have += n
                        if (have > limit) {
                            out.close()
                            part.delete()
                            return DownloadResult.Failed("The server sent more than the update's size; it is fetched again")
                        }
                        out.write(buffer, 0, n)
                        onProgress(have, info.size)

                        // Keep to the rate allowed now, which changes as FluxTV comes and goes from the screen.
                        val allowed = bytesPerSecond()
                        if (allowed != rate) {
                            rate = allowed
                            windowStartNs = System.nanoTime()
                            windowBytes = 0L
                        }
                        windowBytes += n
                        if (rate in 1 until Long.MAX_VALUE) {
                            val dueNs = windowBytes * 1_000_000_000L / rate
                            val aheadNs = dueNs - (System.nanoTime() - windowStartNs)
                            if (aheadNs > 1_000_000L) delay(aheadNs / 1_000_000L)
                        }
                    }
                    out.flush()
                    out.fd.sync()
                }
            }
        } catch (e: IOException) {
            coroutineContext.ensureActive()
            return DownloadResult.Failed(
                "The download was interrupted (${e.message ?: e.javaClass.simpleName}); it goes on from there next time"
            )
        } finally {
            cancelOnStop?.dispose()
        }
        return null
    }

    private companion object {
        /** Room left free beside the update: the installation needs about as much again. */
        const val SPACE_MARGIN_BYTES = 64L * 1024 * 1024
    }
}
