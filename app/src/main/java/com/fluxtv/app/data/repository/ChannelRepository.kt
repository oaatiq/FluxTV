package com.fluxtv.app.data.repository

import com.fluxtv.app.data.cache.CachedPlaylist
import com.fluxtv.app.data.cache.PlaylistCache
import com.fluxtv.app.data.m3u.M3uEntry
import com.fluxtv.app.data.m3u.M3uParser
import com.fluxtv.app.data.model.Channel
import com.fluxtv.app.data.remote.FetchResult
import com.fluxtv.app.data.remote.PlaylistFetcher
import com.fluxtv.app.util.sha1Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

sealed class RefreshOutcome {
    data class Success(val count: Int) : RefreshOutcome()
    data class Failed(val reason: String, val keptExisting: Boolean) : RefreshOutcome()
}

/**
 * Single source of truth for the channel line-up.
 *
 * Refreshing never destroys a previously valid playlist on failure (REQ-PL-005) and preserves
 * channel numbering across refreshes by keying on [Channel.stableId] (REQ-PL-008).
 */
class ChannelRepository(
    private val cache: PlaylistCache,
    private val fetcher: PlaylistFetcher
) {
    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    private val _lastUpdatedMs = MutableStateFlow(0L)
    val lastUpdatedMs: StateFlow<Long> = _lastUpdatedMs.asStateFlow()

    fun loadFromCache() {
        val cached = cache.load() ?: return
        _channels.value = cached.channels
        _lastUpdatedMs.value = cached.updatedAtMs
    }

    suspend fun refresh(source: String, type: String, sourceName: String?): RefreshOutcome =
        withContext(Dispatchers.IO) {
            when (val result = fetcher.fetch(source, type)) {
                is FetchResult.Failure ->
                    RefreshOutcome.Failed(result.reason, keptExisting = _channels.value.isNotEmpty())

                is FetchResult.Success -> {
                    val entries = M3uParser.parse(result.content)
                    if (entries.isEmpty()) {
                        return@withContext RefreshOutcome.Failed(
                            "no channels parsed",
                            keptExisting = _channels.value.isNotEmpty()
                        )
                    }
                    val merged = merge(_channels.value, entries)
                    _channels.value = merged
                    val now = System.currentTimeMillis()
                    _lastUpdatedMs.value = now
                    cache.save(CachedPlaylist(merged, now, sourceName))
                    RefreshOutcome.Success(merged.size)
                }
            }
        }

    private fun stableId(e: M3uEntry): String =
        if (!e.tvgId.isNullOrBlank()) "id:${e.tvgId}" else "u:" + sha1Hex("${e.name}|${e.url}")

    private fun merge(old: List<Channel>, parsed: List<M3uEntry>): List<Channel> {
        val oldByStable = old.associateBy { it.stableId }
        var maxNumber = old.maxOfOrNull { it.number } ?: 0
        val seen = HashSet<String>(parsed.size)
        val result = ArrayList<Channel>(parsed.size)
        for (e in parsed) {
            val id = stableId(e)
            if (!seen.add(id)) continue // REQ-PL-007: drop duplicates
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
