package com.fluxtv.app.data.guide

import android.content.Context
import android.os.Process
import android.util.AtomicFile
import android.util.Xml
import com.fluxtv.app.data.model.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

@Serializable
private class CachedGuideChannel(val keys: List<String>, val programmes: List<Programme>)

@Serializable
private class CachedGuide(
    val updatedAtMs: Long,
    val sources: List<String>,
    val playlistId: String? = null,
    val channels: List<CachedGuideChannel>,
    /** See [Guide.covers]; a guide saved before it was kept counts as covering no channel. */
    val wanted: IntArray = IntArray(0)
)

/** How loading the guide went. [problem] is in plain words, for Settings; [reason] is for the history. */
sealed class GuideOutcome {
    data class Loaded(val channelCount: Int, val brokenOff: Boolean) : GuideOutcome()
    data class Failed(val problem: String, val reason: String) : GuideOutcome()
}

/**
 * The programme guide (XMLTV) of the playlist in use, for the channel banner's Now / Next.
 *
 * Only what the banner needs is kept: the playlist's channels, from a few hours ago to a day and a
 * half ahead, titles only. That is saved to a file, so the banner has it straight after a restart,
 * and fetched again when it is half a day old. A guide that cannot be fetched leaves the one there
 * is in place: it still covers the next day or so.
 */
@OptIn(ExperimentalSerializationApi::class)
class GuideRepository(
    context: Context,
    private val baseClient: () -> OkHttpClient
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val file = AtomicFile(File(context.filesDir, "guide_cache.json"))

    // A guide is often far larger than the playlist; like the playlist, it may take minutes.
    private val client by lazy { baseClient().newBuilder().callTimeout(10, TimeUnit.MINUTES).build() }

    private val _guide = MutableStateFlow(Guide.EMPTY)
    val guide: StateFlow<Guide> = _guide.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** Why the last try to load the guide failed, in plain words; null once one has loaded. */
    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()

    private val lock = Mutex()

    /** Starts from the saved guide, if it was made for [playlistId]. */
    suspend fun loadFromCache(playlistId: String?) = withContext(Dispatchers.IO) {
        lock.withLock {
            val cached = try {
                file.openRead().buffered().use { json.decodeFromStream<CachedGuide>(it) }
            } catch (_: Exception) {
                null
            } ?: return@withLock
            if (cached.playlistId != playlistId || _guide.value.updatedAtMs > cached.updatedAtMs) return@withLock
            val channels = cached.channels.map { GuideChannel(it.keys, it.programmes) }
            _guide.value = guideOf(channels, cached.updatedAtMs, cached.sources, playlistId, cached.wanted)
        }
    }

    /** Whether the guide there is was made from [sources] for [playlistId] less than [maxAgeMs] ago. */
    fun isCurrent(sources: List<String>, playlistId: String?, maxAgeMs: Long): Boolean {
        val g = _guide.value
        val age = System.currentTimeMillis() - g.updatedAtMs
        // A TV's clock can be wrong until it has synced after power-on: an age below zero is unknown.
        return g.sources == sources && g.playlistId == playlistId && age in 0 until maxAgeMs
    }

    /**
     * Whether the guide there is was cut down for all of [channels] (see [Guide.covers]). Working that
     * out takes every channel's name apart, so it is done away from the main thread.
     */
    suspend fun covers(channels: List<Channel>): Boolean {
        val guide = _guide.value
        return withContext(Dispatchers.Default) { guide.covers(channels) }
    }

    /** The playlist in use names no guide, and none is set in Settings: there is nothing to show. */
    suspend fun clear() = lock.withLock {
        _guide.value = Guide.EMPTY
        _problem.value = null
        withContext(Dispatchers.IO) { file.delete() }
    }

    /**
     * Fetches the guides at [sources] (the first one that has a channel wins it) and keeps what
     * [channels] need. [userAgent]: the player name the provider gets, as for the playlist.
     */
    suspend fun refresh(
        sources: List<String>,
        playlistId: String?,
        channels: List<Channel>,
        userAgent: String
    ): GuideOutcome = lock.withLock {
        _loading.value = true
        try {
            val outcome = withContext(Dispatchers.IO) { load(sources, playlistId, channels, userAgent) }
            _problem.value = (outcome as? GuideOutcome.Failed)?.problem
            outcome
        } finally {
            _loading.value = false
        }
    }

    private fun load(sources: List<String>, playlistId: String?, channels: List<Channel>, userAgent: String): GuideOutcome {
        val wanted = HashSet<String>(channels.size * 3)
        for (c in channels) wanted.addAll(Guide.keysOf(c))
        val now = System.currentTimeMillis()
        val from = now - KEEP_BEFORE_MS
        val to = now + KEEP_AFTER_MS
        val language = Locale.getDefault().language

        // Reading and sorting a large guide is a lot of work for a TV box's processor; it gives way to
        // the picture and to the remote.
        val thread = Process.myTid()
        val priority = Process.getThreadPriority(thread)
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        try {
            val all = ArrayList<GuideChannel>()
            var brokenOff = false
            var failure: GuideOutcome.Failed? = null
            for (source in sources.take(MAX_SOURCES)) {
                try {
                    val result = open(source, userAgent) { stream ->
                        val parser = Xml.newPullParser()
                        // Guides are not always well-formed XML (an "&" left as it is, an HTML entity):
                        // the relaxed parser reads on where a strict one would stop.
                        runCatching { parser.setFeature(FEATURE_RELAXED, true) }
                        parser.setInput(stream, null)
                        XmltvParser.parse(parser, wanted, from, to, language)
                    }
                    if (result.brokenOff && result.channels.isEmpty()) {
                        // An error page sent as if it were the guide, say: not a guide at all.
                        failure = failure ?: GuideOutcome.Failed(
                            "The programme guide link opened, but what it sent is not a programme guide.",
                            "not an XMLTV guide"
                        )
                    } else {
                        all.addAll(result.channels)
                        brokenOff = brokenOff || result.brokenOff
                    }
                } catch (e: GuideHttpException) {
                    failure = failure ?: GuideOutcome.Failed(httpProblem(e.code), "HTTP ${e.code}")
                } catch (_: OutOfMemoryError) {
                    all.clear()
                    failure = GuideOutcome.Failed(
                        "The programme guide is too large for this TV's memory.",
                        "too large for the memory available"
                    )
                    break
                } catch (e: Exception) {
                    failure = failure ?: GuideOutcome.Failed(
                        "The TV could not open the programme guide. Check its link and the internet connection.",
                        e.message ?: e.javaClass.simpleName
                    )
                }
            }
            if (all.isEmpty() && failure != null) {
                // Every guide failed: the one there is stays, unless it was another playlist's.
                if (_guide.value.playlistId != playlistId) _guide.value = Guide.EMPTY
                return failure
            }

            val wantedHashes = wanted.map { it.hashCode() }.distinct().sorted().toIntArray()
            val guide = guideOf(all, now, sources, playlistId, wantedHashes)
            _guide.value = guide
            save(CachedGuide(now, sources, playlistId, all.map { CachedGuideChannel(it.keys, it.programmes) }, wantedHashes))
            return GuideOutcome.Loaded(guide.channelCount, brokenOff)
        } finally {
            Process.setThreadPriority(thread, priority)
        }
    }

    /** Opens [source] (a link, or a file on the TV), unpacking it when it is gzipped (".xml.gz"). */
    private fun <T> open(source: String, userAgent: String, read: (InputStream) -> T): T {
        if (source.startsWith("/") || source.startsWith("file:")) {
            return FileInputStream(source.removePrefix("file://")).use { read(unpacked(it)) }
        }
        val request = Request.Builder().url(source).header("User-Agent", userAgent).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw GuideHttpException(resp.code)
            val body = resp.body ?: throw GuideHttpException(resp.code)
            return body.byteStream().use { read(unpacked(it)) }
        }
    }

    /** [stream] as it is, or ungzipped when it starts as a gzip file does. */
    private fun unpacked(stream: InputStream): InputStream {
        val buffered = BufferedInputStream(stream, 64 * 1024)
        buffered.mark(2)
        val first = buffered.read()
        val second = buffered.read()
        buffered.reset()
        return if (first == 0x1f && second == 0x8b) BufferedInputStream(GZIPInputStream(buffered, 64 * 1024), 64 * 1024)
        else buffered
    }

    private fun save(cached: CachedGuide) {
        var stream: FileOutputStream? = null
        try {
            stream = file.startWrite()
            val out = stream.buffered()
            json.encodeToStream(cached, out)
            out.flush()
            file.finishWrite(stream)
        } catch (_: Exception) {
            // Non-fatal: without a saved guide the banner waits for the next download after a restart.
            if (stream != null) file.failWrite(stream)
        }
    }

    private fun guideOf(
        channels: List<GuideChannel>,
        updatedAtMs: Long,
        sources: List<String>,
        playlistId: String?,
        wantedHashes: IntArray
    ): Guide {
        val byKey = HashMap<String, List<Programme>>(channels.size * 3)
        for (c in channels) for (key in c.keys) byKey.putIfAbsent(key, c.programmes)
        return Guide(byKey, updatedAtMs, sources, playlistId, channels.size, wantedHashes)
    }

    private fun httpProblem(code: Int): String = when (code) {
        401, 403 -> "The provider refused the programme guide link. Check the user name and password in it."
        404, 410 -> "The provider has no programme guide at this link."
        else -> "The provider's server did not send the programme guide (error $code). FluxTV tries again later."
    }

    private class GuideHttpException(val code: Int) : Exception("HTTP $code")

    companion object {
        /** How old the guide may get before it is fetched again. */
        const val MAX_AGE_MS = 12 * 3_600_000L

        private const val KEEP_BEFORE_MS = 3 * 3_600_000L
        private const val KEEP_AFTER_MS = 36 * 3_600_000L

        /** A playlist naming more guides than this has the rest left out. */
        private const val MAX_SOURCES = 3

        private const val FEATURE_RELAXED = "http://xmlpull.org/v1/doc/features.html#relaxed"
    }
}
