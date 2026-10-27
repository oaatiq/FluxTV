package com.fluxtv.app.data.repository

import com.fluxtv.app.data.cache.CachedPlaylist
import com.fluxtv.app.data.cache.PlaylistCache
import com.fluxtv.app.data.m3u.M3uEntry
import com.fluxtv.app.data.model.Channel
import com.fluxtv.app.data.remote.FetchResult
import com.fluxtv.app.data.remote.PlaylistFetcher
import com.fluxtv.app.data.settings.SavedPlaylist
import com.fluxtv.app.util.sha1Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed class RefreshOutcome {
    data class Success(val count: Int) : RefreshOutcome()

    /**
     * [tooLarge]: the playlist did not fit in the memory this TV gives FluxTV. [httpCode]: the
     * provider's server answered with this error. [noChannels]: it opened, but held no channels.
     */
    data class Failed(
        val reason: String,
        val keptExisting: Boolean,
        val tooLarge: Boolean = false,
        val httpCode: Int? = null,
        val noChannels: Boolean = false
    ) : RefreshOutcome()
}

/** A fetched playlist: its channels, or why there are none. */
private class Fetched(val channels: List<Channel>?, val failure: RefreshOutcome.Failed?)

/**
 * Single source of truth for the channel line-up, which is always one saved playlist's: channels of
 * different playlists are never combined.
 *
 * Refreshing never destroys a previously valid playlist on failure (REQ-PL-005) and preserves
 * channel numbering across refreshes by keying on [Channel.stableId] (REQ-PL-008). Another playlist
 * replaces the channels only once it has loaded.
 */
class ChannelRepository(
    private val cache: PlaylistCache,
    private val fetcher: PlaylistFetcher
) {
    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    private val _lastUpdatedMs = MutableStateFlow(0L)
    val lastUpdatedMs: StateFlow<Long> = _lastUpdatedMs.asStateFlow()

    /** The saved playlist [channels] are from, which favorites and the last channel are kept for. */
    @Volatile
    var playlistId: String? = null
        private set

    /**
     * One refresh at a time. The start-up refresh, the periodic job (overdue after the TV was off, it
     * can run at boot too) and Settings can otherwise overlap: each holds a whole playlist in memory
     * while it works, and each merges into the list the other one is replacing. Checking a playlist
     * and switching to one hold it too.
     */
    private val refreshLock = Mutex()

    /**
     * Starts from the saved channels if they are [activeId]'s. A switch to another playlist that the
     * power cut short can leave the other one's channels in the cache, and those are not shown.
     */
    fun loadFromCache(activeId: String?) {
        if (activeId == null) return
        val cached = cache.load() ?: return
        if (cached.playlistId != null && cached.playlistId != activeId) return
        playlistId = activeId
        _channels.value = sharingRepeats(cached.channels)
        _lastUpdatedMs.value = cached.updatedAtMs
    }

    /**
     * The cache is read back with a copy of every value on every channel, while the parser has
     * channels share one copy of a repeated group title, logo, user agent or referer. Shared here too,
     * the list FluxTV starts from, and keeps until the next refresh, is as small as a refreshed one.
     */
    private fun sharingRepeats(channels: List<Channel>): List<Channel> {
        val seen = HashMap<String, String>()
        fun shared(s: String?): String? = if (s == null) null else seen.getOrPut(s) { s }
        return channels.map {
            it.copy(
                logoUrl = shared(it.logoUrl),
                group = shared(it.group),
                userAgent = shared(it.userAgent),
                referer = shared(it.referer)
            )
        }
    }

    /**
     * Fetches [playlist] again and merges it into the channels, keeping their numbers. Only the
     * playlist the channels are from is refreshed (or any, before one is loaded): a refresh asked for
     * before another playlist was put in use must not bring the old one's channels back.
     */
    suspend fun refresh(playlist: SavedPlaylist): RefreshOutcome = refreshLock.withLock {
        val loaded = playlistId
        if (loaded != null && loaded != playlist.id) {
            return@withLock RefreshOutcome.Failed("another playlist is in use", keptExisting = true)
        }
        load(playlist, keepNumbers = true)
    }

    /**
     * Puts [playlist] in place of the channels there are, once it has loaded: one that cannot be
     * loaded leaves them as they are. Its channels are numbered afresh unless [keepNumbers] (the same
     * playlist at a new address), not after the last number of another provider's list.
     */
    suspend fun activate(playlist: SavedPlaylist, keepNumbers: Boolean = false): RefreshOutcome =
        refreshLock.withLock { load(playlist, keepNumbers) }

    /** Fetches [source] to see that it loads, and how many channels it holds, without using it. */
    suspend fun check(source: String, type: String): RefreshOutcome = refreshLock.withLock {
        withContext(Dispatchers.IO) {
            val fetched = fetch(source, type, emptyList())
            fetched.channels?.let { RefreshOutcome.Success(it.size) } ?: fetched.failure!!
        }
    }

    private suspend fun load(playlist: SavedPlaylist, keepNumbers: Boolean): RefreshOutcome =
        withContext(Dispatchers.IO) {
            val fetched = fetch(playlist.url, playlist.type, if (keepNumbers) _channels.value else emptyList())
            val merged = fetched.channels ?: return@withContext fetched.failure!!
            playlistId = playlist.id
            _channels.value = merged
            val now = System.currentTimeMillis()
            _lastUpdatedMs.value = now
            cache.save(CachedPlaylist(merged, now, playlist.name, playlist.id))
            RefreshOutcome.Success(merged.size)
        }

    /** Fetches and parses [source], numbering its channels after those of [old] that it still has. */
    private fun fetch(source: String, type: String, old: List<Channel>): Fetched {
        val keptExisting = _channels.value.isNotEmpty()
        return try {
            when (val result = fetcher.fetch(source, type)) {
                is FetchResult.Failure ->
                    Fetched(null, RefreshOutcome.Failed(result.reason, keptExisting, httpCode = result.httpCode))

                is FetchResult.Success ->
                    if (result.entries.isEmpty()) {
                        Fetched(null, RefreshOutcome.Failed("no channels parsed", keptExisting, noChannels = true))
                    } else {
                        Fetched(merge(old, result.entries), null)
                    }
            }
        } catch (_: OutOfMemoryError) {
            // A playlist too large for this TV ends the refresh rather than FluxTV: what was read of it
            // is let go, and the channels already loaded stay.
            Fetched(
                null,
                RefreshOutcome.Failed("playlist too large for the memory available", keptExisting, tooLarge = true)
            )
        }
    }

    /**
     * The identity of [e] in this playlist, or null when it repeats an entry already in [taken]
     * (REQ-PL-007).
     *
     * A tvg-id is an EPG key, not a channel key: providers often give the HD, FHD and SD versions of
     * a channel the same one, and keying on it alone dropped every version but the first. The first
     * entry with a tvg-id still gets "id:<tvg-id>", so the identities favorites, numbers and the last
     * channel are stored under do not change; a later one with another name or address gets that id
     * qualified by a hash of them. The same tvg-id, name and address again is a duplicate.
     */
    private fun stableId(e: M3uEntry, taken: MutableSet<String>): String? {
        val nameAndUrl = sha1Hex("${e.idName ?: e.name}|${e.url}")
        if (e.tvgId.isNullOrBlank()) return "u:$nameAndUrl".takeIf { taken.add(it) }
        // Registered for the first entry too, so that an exact repeat of it is recognised.
        val variant = "id:${e.tvgId}|$nameAndUrl"
        if (!taken.add(variant)) return null
        val primary = "id:${e.tvgId}"
        return if (taken.add(primary)) primary else variant
    }

    private fun merge(old: List<Channel>, parsed: List<M3uEntry>): List<Channel> {
        val oldByStable = old.associateBy { it.stableId }
        var maxNumber = old.maxOfOrNull { it.number } ?: 0
        val taken = HashSet<String>(parsed.size * 2)
        val result = ArrayList<Channel>(parsed.size)
        for (e in parsed) {
            val id = stableId(e, taken) ?: continue // REQ-PL-007: drop duplicates
            val number = e.tvgChno ?: oldByStable[id]?.number ?: (++maxNumber)
            if (number > maxNumber) maxNumber = number
            result.add(
                Channel(
                    stableId = id,
                    number = number,
                    name = e.name,
                    url = e.url,
                    logoUrl = e.logo,
                    group = e.group,
                    userAgent = e.userAgent,
                    referer = e.referer
                )
            )
        }
        return result
    }
}
