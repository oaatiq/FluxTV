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
) {
    fun shifted(byMs: Long): Programme = if (byMs == 0L) this else copy(startMs = startMs + byMs, endMs = endMs + byMs)
}

/** What a channel shows now and next; either can be missing (a gap in the guide, or its end). */
class NowNext(val now: Programme?, val next: Programme?)

/**
 * The programme guide of the playlist in use, cut down to its channels and to the hours around now.
 *
 * Each channel's programmes are sorted by start and filed under every key it can be found by: its
 * tvg-id ([idKeys]) and each name the guide gives it ([nameKeys]). A playlist entry is looked up by its
 * own tvg-id first, then by its name and tvg-name, since many playlists have no tvg-ids, or ones the
 * guide does not use. [sources] and [playlistId] say what the guide was made for, so that a changed link or another
 * playlist in use loads it again. [wantedHashes]: the hash codes of the keys of the playlist channels
 * it was cut down for, sorted, so that channels the playlist has gained since can be told apart.
 */
class Guide(
    private val byKey: Map<String, List<Programme>>,
    val updatedAtMs: Long,
    val sources: List<String>,
    val playlistId: String?,
    /** How many of the guide's channels are this playlist's. */
    val channelCount: Int,
    private val wantedHashes: IntArray = IntArray(0)
) {
    /**
     * Whether the guide was cut down for every channel of [channels]: one the playlist has gained since
     * was left out of it, and has no programmes in it whatever the guide holds for it.
     */
    fun covers(channels: List<Channel>): Boolean =
        channels.all { c -> keysOf(c).all { wantedHashes.binarySearch(it.hashCode()) >= 0 } }

    fun programmesFor(channel: Channel): List<Programme>? {
        for (key in idKeysOf(channel)) byKey[key]?.let { return it }
        for (key in nameKeysOf(channel)) byKey[key]?.let { return it }
        return null
    }

    /**
     * The programme on [channel] at [nowMs] and the one after it, or null when the guide has neither.
     * A channel whose playlist moves the guide's times (tvg-shift) gets them moved.
     */
    fun nowAndNext(channel: Channel, nowMs: Long): NowNext? {
        val list = programmesFor(channel) ?: return null
        val shift = channel.guideShiftMin * 60_000L
        val at = nowMs - shift
        // The last programme that has started by now.
        var lo = 0
        var hi = list.size - 1
        var started = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].startMs <= at) {
                started = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        val now = list.getOrNull(started)?.takeIf { it.endMs > at }
        val next = list.getOrNull(started + 1)
        return if (now == null && next == null) null else NowNext(now?.shifted(shift), next?.shifted(shift))
    }

    companion object {
        val EMPTY = Guide(emptyMap(), 0L, emptyList(), null, 0)

        /**
         * The keys of a tvg-id: the id itself, then the channel it names without its feed.
         * iptv-org ids end in the feed ("AlAoula.ma@SD", "2MMonde.ma@Plus1") and playlists often
         * leave it off, or name another one. The exact id is a key of its own, and comes first,
         * so "2MMonde.ma" finds 2M Monde and never its feed an hour late, while "AlAoula.ma"
         * still finds a guide's "AlAoula.ma@SD".
         */
        fun idKeys(tvgId: String): List<String> {
            val id = tvgId.trim().lowercase()
            if (id.isEmpty()) return emptyList()
            val channel = id.substringBefore('@').trim()
            return if (channel.isEmpty()) listOf("i:$id") else listOf("i:$id", "b:$channel")
        }

        private fun idKeysOf(channel: Channel): List<String> = channel.tvgId?.let(::idKeys).orEmpty()

        /**
         * The keys of a channel name: the whole name, then the name without a tag in front of it
         * ("FR - TF1", "|FR| TF1"), when it has one. Both are kept, so that "RTL-TVI" still matches a
         * guide's "RTL TVI" as well as its "RTL-TVI"; none for a name with nothing to match on.
         */
        fun nameKeys(name: String): List<String> {
            val whole = normalizeName(name)
            val untagged = normalizeName(name, dropTag = true)
            return when {
                whole == null -> listOfNotNull(untagged?.let { "n:$it" })
                untagged == null || untagged == whole -> listOf("n:$whole")
                else -> listOf("n:$whole", "n:$untagged")
            }
        }

        /** The name keys of a playlist channel, most trusted first: whole names before untagged ones. */
        private fun nameKeysOf(channel: Channel): List<String> {
            val own = nameKeys(channel.name)
            val tvgName = channel.tvgName ?: return own
            val other = nameKeys(tvgName)
            return (own.take(1) + other.take(1) + own.drop(1) + other.drop(1)).distinct()
        }

        /** The keys a playlist channel can be found under, for the guide to keep its programmes. */
        fun keysOf(channel: Channel): List<String> = idKeysOf(channel) + nameKeysOf(channel)

        /** Words that only say which version of a channel this is, and that guides leave out. */
        private val VERSION_WORDS = setOf(
            "hd", "fhd", "uhd", "sd", "4k", "8k", "hq", "lq", "hevc", "h264", "h265", "1080p", "1080i",
            "720p", "576p", "480p", "50fps", "60fps", "backup", "raw"
        )

        /** "FR: TF1 HD" or "AR | MBC 1 (backup)": the country before the name, and anything in brackets. */
        private val COUNTRY_PREFIX = Regex("^[\\p{L}]{2,3}\\s*[:|]\\s*")

        /** "FR - TF1", "|FR| TF1", "FR• TF1": two or three letters set off by a sign, not only a space. */
        private val TAG_PREFIX = Regex("^[^\\p{L}\\p{N}]*\\p{L}{2,3}\\s*[^\\p{L}\\p{N}\\s]+\\s*")
        private val BRACKETS = Regex("[(\\[{][^)\\]}]*[)\\]}]")
        private val DIACRITICS = Regex("\\p{Mn}+")
        private val SEPARATORS = Regex("[^\\p{L}\\p{N}+]+")

        /**
         * A channel name as both the playlist and the guide can be expected to have it: lower case,
         * without accents, the country prefix, bracketed notes, version words, spaces or punctuation.
         * "FR: TF1 HD", "TF1 (backup)" and "tf1" are all "tf1"; decorated letters ("ᴴᴰ") count as
         * the plain ones. With [dropTag], any short tag set off in front goes too (see [nameKeys]).
         */
        fun normalizeName(name: String, dropTag: Boolean = false): String? {
            var s = Normalizer.normalize(name, Normalizer.Form.NFKD).lowercase().replace(DIACRITICS, "")
            if (dropTag) s = s.replace(TAG_PREFIX, "")
            s = s.replace(COUNTRY_PREFIX, "").replace(BRACKETS, " ")
            val words = s.split(SEPARATORS).filter { it.isNotEmpty() && it !in VERSION_WORDS }
            return words.joinToString("").takeIf { it.isNotEmpty() }
        }
    }
}
