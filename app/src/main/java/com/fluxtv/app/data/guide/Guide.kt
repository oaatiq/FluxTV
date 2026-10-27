package com.fluxtv.app.data.guide

import com.fluxtv.app.data.model.Channel
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.Normalizer

/** One programme of the guide: when it starts and ends, and its title. Short names keep the cache small. */
@Serializable
data class Programme(
    @SerialName("s") val startMs: Long,
    @SerialName("e") val endMs: Long,
    @SerialName("t") val title: String
)

/** What a channel shows now and next; either can be missing (a gap in the guide, or its end). */
class NowNext(val now: Programme?, val next: Programme?)

/**
 * The programme guide of the playlist in use, cut down to its channels and to the hours around now.
 *
 * Each channel's programmes are sorted by start and filed under every key it can be found by: its
 * tvg-id ([idKey]) and each name the guide gives it ([nameKey]). A playlist entry is looked up by its
 * own tvg-id first, then by its name, since many playlists have no tvg-ids, or ones the guide does not
 * use. [sources] and [playlistId] say what the guide was made for, so that a changed link or another
 * playlist in use loads it again.
 */
class Guide(
    private val byKey: Map<String, List<Programme>>,
    val updatedAtMs: Long,
    val sources: List<String>,
    val playlistId: String?,
    /** How many of the guide's channels are this playlist's. */
    val channelCount: Int
) {
    fun programmesFor(channel: Channel): List<Programme>? =
        channel.tvgId?.let { byKey[idKey(it)] } ?: nameKey(channel.name)?.let { byKey[it] }

    /** The programme on [channel] at [nowMs] and the one after it, or null when the guide has neither. */
    fun nowAndNext(channel: Channel, nowMs: Long): NowNext? {
        val list = programmesFor(channel) ?: return null
        // The last programme that has started by now.
        var lo = 0
        var hi = list.size - 1
        var started = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].startMs <= nowMs) {
                started = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        val now = list.getOrNull(started)?.takeIf { it.endMs > nowMs }
        val next = list.getOrNull(started + 1)
        return if (now == null && next == null) null else NowNext(now, next)
    }

    companion object {
        val EMPTY = Guide(emptyMap(), 0L, emptyList(), null, 0)

        fun idKey(tvgId: String): String = "i:" + tvgId.trim().lowercase()

        /** The key of a channel name, or null for a name with nothing to match on. */
        fun nameKey(name: String): String? = normalizeName(name)?.let { "n:$it" }

        /** The keys a playlist channel can be found under, for the guide to keep its programmes. */
        fun keysOf(channel: Channel): List<String> =
            listOfNotNull(channel.tvgId?.takeIf { it.isNotBlank() }?.let(::idKey), nameKey(channel.name))

        /** Words that only say which version of a channel this is, and that guides leave out. */
        private val VERSION_WORDS = setOf(
            "hd", "fhd", "uhd", "sd", "4k", "8k", "hq", "lq", "hevc", "h264", "h265", "1080p", "1080i",
            "720p", "576p", "480p", "50fps", "60fps", "backup", "raw"
        )

        /** "FR: TF1 HD" or "AR | MBC 1 (backup)": the country before the name, and anything in brackets. */
        private val COUNTRY_PREFIX = Regex("^[\\p{L}]{2,3}\\s*[:|]\\s*")
        private val BRACKETS = Regex("[(\\[{][^)\\]}]*[)\\]}]")
        private val DIACRITICS = Regex("\\p{Mn}+")
        private val SEPARATORS = Regex("[^\\p{L}\\p{N}+]+")

        /**
         * A channel name as both the playlist and the guide can be expected to have it: lower case,
         * without accents, the country prefix, bracketed notes, version words, spaces or punctuation.
         * "FR: TF1 HD", "TF1 (backup)" and "tf1" are all "tf1"; decorated letters ("ᴴᴰ") count as
         * the plain ones.
         */
        fun normalizeName(name: String): String? {
            var s = Normalizer.normalize(name, Normalizer.Form.NFKD).lowercase().replace(DIACRITICS, "")
            s = s.replace(COUNTRY_PREFIX, "").replace(BRACKETS, " ")
            val words = s.split(SEPARATORS).filter { it.isNotEmpty() && it !in VERSION_WORDS }
            return words.joinToString("").takeIf { it.isNotEmpty() }
        }
    }
}
