package com.fluxtv.app.data.repository

import com.fluxtv.app.data.cache.CachedPlaylist
import com.fluxtv.app.data.cache.PlaylistCache
import com.fluxtv.app.data.m3u.M3uEntry
import com.fluxtv.app.data.model.Channel
import com.fluxtv.app.data.remote.DEFAULT_USER_AGENT
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

/** A fetched playlist: its channels and the programme guides it names, or why there are none. */
private class Fetched(
    val channels: List<Channel>?,
    val failure: RefreshOutcome.Failed?,
    val guideUrls: List<String> = emptyList()
)

/** How many old channels of one name a channel at a new address is compared with, at most. */
private const val MAX_SAME_NAME_LOOKED_AT = 500

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

    /** The programme guides (XMLTV) the playlist of [channels] names in its first line. */
    private val _guideUrls = MutableStateFlow<List<String>>(emptyList())
    val guideUrls: StateFlow<List<String>> = _guideUrls.asStateFlow()

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
        _guideUrls.value = cached.guideUrls
    }

    /**
     * The cache is read back with a copy of every value on every channel, while the parser has
     * channels share one copy of a repeated group title, logo, tvg-id, user agent or referer. Shared
     * here too, the list FluxTV starts from, and keeps until the next refresh, is as small as a
     * refreshed one.
     */
    private fun sharingRepeats(channels: List<Channel>): List<Channel> {
        val seen = HashMap<String, String>()
        fun shared(s: String?): String? = if (s == null) null else seen.getOrPut(s) { s }
        return channels.map {
            it.copy(
                logoUrl = shared(it.logoUrl),
                group = shared(it.group),
                userAgent = shared(it.userAgent),
                referer = shared(it.referer),
                tvgId = shared(it.tvgId),
                tvgName = shared(it.tvgName)
            )
        }
    }

    /**
     * Fetches [playlist] again and merges it into the channels, keeping their numbers. Only the
     * playlist the channels are from is refreshed (or any, before one is loaded): a refresh asked for
     * before another playlist was put in use must not bring the old one's channels back.
     */
    suspend fun refresh(playlist: SavedPlaylist, userAgent: String = DEFAULT_USER_AGENT): RefreshOutcome =
        refreshLock.withLock {
            val loaded = playlistId
            if (loaded != null && loaded != playlist.id) {
                return@withLock RefreshOutcome.Failed("another playlist is in use", keptExisting = true)
            }
            load(playlist, keepNumbers = true, userAgent)
        }

    /**
     * Puts [playlist] in place of the channels there are, once it has loaded: one that cannot be
     * loaded leaves them as they are. Its channels are numbered afresh, not after the last number of
     * another provider's list, unless [keepNumbers]: the same playlist at a new link, whose channels
     * keep their numbers. A new link to a list that is mostly another one is numbered afresh anyway.
     */
    suspend fun activate(
        playlist: SavedPlaylist,
        keepNumbers: Boolean = false,
        userAgent: String = DEFAULT_USER_AGENT
    ): RefreshOutcome = refreshLock.withLock {
        load(playlist, keepNumbers, userAgent, renumberIfMostlyNew = keepNumbers)
    }

    /** Fetches [source] to see that it loads, and how many channels it holds, without using it. */
    suspend fun check(source: String, type: String, userAgent: String = DEFAULT_USER_AGENT): RefreshOutcome =
        refreshLock.withLock {
            withContext(Dispatchers.IO) {
                val fetched = fetch(source, type, emptyList(), userAgent)
                fetched.channels?.let { RefreshOutcome.Success(it.size) } ?: fetched.failure!!
            }
        }

    /** [userAgent]: the player name the provider gets (FluxSettings.effectiveUserAgent). */
    private suspend fun load(
        playlist: SavedPlaylist,
        keepNumbers: Boolean,
        userAgent: String,
        renumberIfMostlyNew: Boolean = false
    ): RefreshOutcome =
        withContext(Dispatchers.IO) {
            val old = if (keepNumbers) _channels.value else emptyList()
            val fetched = fetch(playlist.url, playlist.type, old, userAgent, renumberIfMostlyNew)
            val merged = fetched.channels ?: return@withContext fetched.failure!!
            playlistId = playlist.id
            _channels.value = merged
            _guideUrls.value = fetched.guideUrls
            val now = System.currentTimeMillis()
            _lastUpdatedMs.value = now
            cache.save(CachedPlaylist(merged, now, playlist.name, playlist.id, fetched.guideUrls))
            RefreshOutcome.Success(merged.size)
        }

    /** Fetches and parses [source], numbering its channels after those of [old] that it still has. */
    private fun fetch(
        source: String,
        type: String,
        old: List<Channel>,
        userAgent: String,
        renumberIfMostlyNew: Boolean = false
    ): Fetched {
        val keptExisting = _channels.value.isNotEmpty()
        return try {
            when (val result = fetcher.fetch(source, type, userAgent)) {
                is FetchResult.Failure ->
                    Fetched(null, RefreshOutcome.Failed(result.reason, keptExisting, httpCode = result.httpCode))

                is FetchResult.Success ->
                    if (result.entries.isEmpty()) {
                        Fetched(null, RefreshOutcome.Failed("no channels parsed", keptExisting, noChannels = true))
                    } else {
                        Fetched(merge(old, result.entries, renumberIfMostlyNew), null, result.guideUrls)
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

    /**
     * The channels of [parsed], numbered against [old], the list loaded before: a channel it still
     * has keeps its identity and number, so its favorite and last-channel mark too, and a new one
     * comes after the last number. With [renumberIfMostlyNew] (a new link), another list is numbered
     * afresh: one with fewer than half of the old channels, and made of fewer than half old ones.
     */
    private fun merge(old: List<Channel>, parsed: List<M3uEntry>, renumberIfMostlyNew: Boolean = false): List<Channel> {
        val oldByStable = old.associateBy { it.stableId }
        val taken = HashSet<String>(parsed.size * 2)
        val ids = arrayOfNulls<String>(parsed.size)
        var kept = 0
        var found = 0
        for (i in parsed.indices) {
            val id = stableId(parsed[i], taken) ?: continue // REQ-PL-007: drop duplicates
            ids[i] = id
            kept++
            if (id in oldByStable) found++
        }
        if (found < old.size && found < kept) found += recognizeMoved(old, parsed, ids, oldByStable, taken)
        val renumber = renumberIfMostlyNew && 2 * found < old.size && 2 * found < kept
        var maxNumber = if (renumber) 0 else old.maxOfOrNull { it.number } ?: 0
        val result = ArrayList<Channel>(kept)
        for (i in parsed.indices) {
            val id = ids[i] ?: continue
            val e = parsed[i]
            val number = e.tvgChno ?: (if (renumber) null else oldByStable[id]?.number) ?: (++maxNumber)
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
                    referer = e.referer,
                    tvgId = e.tvgId,
                    tvgName = e.tvgName,
                    guideShiftMin = e.guideShiftMin ?: 0
                )
            )
        }
        return result
    }

    /**
     * Gives each entry whose identity [old] does not have the identity of the old channel it is, at a
     * new address, and returns how many it gave. A channel without a tvg-id is known by its name and
     * address, so when the provider moves server (a new link, or new addresses in the same one) it
     * would lose its number, favorite and last-channel mark. In the playlist's order, an entry takes
     * the first old channel left with the same name and the same address apart from the server;
     * failing that, the same name and group (new account details in the address, say). [ids] gets the
     * old identities, none of which another entry has: the old channels left are those whose identity
     * is not in [taken], which holds every entry's (and the tvg-id variant of each first entry too).
     */
    private fun recognizeMoved(
        old: List<Channel>,
        parsed: List<M3uEntry>,
        ids: Array<String?>,
        oldByStable: Map<String, Channel>,
        taken: Set<String>
    ): Int {
        val left = old.filter { it.stableId !in taken }
        if (left.isEmpty()) return 0
        var unknownCount = 0
        for (id in ids) if (id != null && id !in oldByStable) unknownCount++
        // The old channels left, by name: the first of each name, and from each the next one.
        val firstByName = HashMap<String, Int>(left.size * 2)
        val next = IntArray(left.size)
        for (j in left.indices.reversed()) {
            next[j] = firstByName[left[j].name] ?: -1
            firstByName[left[j].name] = j
        }
        // Takes the first one of [e]'s name that is [same] out of them. Only so many are looked at, so
        // that a list with thousands of channels of one name ("E01", say) does not take minutes.
        fun take(e: M3uEntry, same: (Channel) -> Boolean): Channel? {
            var before = -1
            var j = firstByName[e.name] ?: return null
            var looked = 0
            while (j >= 0 && looked++ < MAX_SAME_NAME_LOOKED_AT) {
                if (same(left[j])) {
                    when {
                        before >= 0 -> next[before] = next[j]
                        next[j] >= 0 -> firstByName[e.name] = next[j]
                        else -> firstByName.remove(e.name)
                    }
                    return left[j]
                }
                before = j
                j = next[j]
            }
            return null
        }
        val unknown = IntArray(unknownCount)
        var count = 0
        for (i in ids.indices) ids[i]?.let { if (it !in oldByStable) unknown[count++] = i }
        var matched = 0
        for (byAddress in listOf(true, false)) {
            var still = 0
            for (k in 0 until count) {
                val i = unknown[k]
                val e = parsed[i]
                val c = if (byAddress) take(e) { sameApartFromServer(it.url, e.url) } else take(e) { it.group == e.group }
                if (c == null) unknown[still++] = i else { ids[i] = c.stableId; matched++ }
            }
            count = still
        }
        return matched
    }

    /** Whether [a] and [b] are the same address but for the server ("http://host:port"). */
    private fun sameApartFromServer(a: String, b: String): Boolean {
        val i = afterServer(a)
        val j = afterServer(b)
        return a.length - i == b.length - j && a.regionMatches(i, b, j, a.length - i)
    }

    /** Where [url] goes on after "scheme://server"; 0 for an address without a scheme. */
    private fun afterServer(url: String): Int {
        val scheme = url.indexOf("://")
        if (scheme <= 0) return 0
        for (k in scheme + 3 until url.length) {
            val ch = url[k]
            if (ch == '/' || ch == '?' || ch == '#' || ch == '|') return k
        }
        return url.length
    }
}
