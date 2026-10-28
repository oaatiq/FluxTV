package com.fluxtv.app.data.guide

import org.xmlpull.v1.XmlPullParser

/** One channel of an XMLTV guide that the playlist has: the keys it is found by, and its programmes. */
class GuideChannel(val keys: List<String>, val programmes: List<Programme>)

/** What [XmltvParser.parse] read: the channels it kept, and whether the file ended early in an error. */
class XmltvResult(val channels: List<GuideChannel>, val brokenOff: Boolean)

/**
 * Reads an XMLTV programme guide (`<tv>`, its `<channel>`s, then its `<programme>`s) as it arrives.
 *
 * A provider's guide covers thousands of channels over a week or two and can run to hundreds of
 * megabytes, so nothing is held but what the TV will show: the programmes of the channels [wanted]
 * names (see [Guide.keysOf]) that end after [fromMs] and start before [toMs], with their titles only.
 * A guide that breaks off (a bad character, a dropped connection) keeps what was read before.
 */
object XmltvParser {

    /** Enough for a few thousand channels over a day and a half; a guide with more stops there. */
    const val MAX_PROGRAMMES = 250_000

    /**
     * [language]: the TV's language ("fr"); a programme with titles in several languages shows the
     * one in it, else its first.
     */
    fun parse(parser: XmlPullParser, wanted: Set<String>, fromMs: Long, toMs: Long, language: String): XmltvResult {
        // By the guide's own channel id: the keys of each channel the playlist has.
        val kept = HashMap<String, List<String>>()
        val programmes = HashMap<String, ArrayList<Programme>>()
        val titles = HashMap<String, String>()
        var total = 0
        var brokenOff = false
        // HTML names for letters ("M&eacute;t&eacute;o"), which XML does not know: the relaxed parser
        // would drop them, and the title with them.
        for ((name, text) in NAMED) {
            if (name !in XML_ENTITIES) runCatching { parser.defineEntityReplacementText(name, text) }
        }
        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "channel" -> {
                            val id = parser.getAttributeValue(null, "id")
                            val names = readDisplayNames(parser)
                            if (!id.isNullOrBlank()) {
                                val keys = (Guide.idKeys(id) + names.flatMap(Guide::nameKeys)).distinct()
                                if (keys.any { it in wanted }) kept[id] = keys
                            }
                        }
                        "programme" -> {
                            val id = parser.getAttributeValue(null, "channel")
                            // A guide that lists its programmes before (or without) its channels can
                            // still be matched on the id.
                            if (id != null && id !in kept) {
                                val keys = Guide.idKeys(id)
                                if (keys.any { it in wanted }) kept[id] = keys
                            }
                            if (id == null || id !in kept || total >= MAX_PROGRAMMES) {
                                skip(parser)
                            } else {
                                val start = parseTime(parser.getAttributeValue(null, "start"))
                                val stop = parseTime(parser.getAttributeValue(null, "stop"))
                                val title = readTitle(parser, language)
                                if (start != null && title != null && start < toMs && (stop == null || stop > fromMs)) {
                                    val shared = titles.getOrPut(title) { title }
                                    programmes.getOrPut(id) { ArrayList() }.add(Programme(start, stop ?: UNKNOWN_END, shared))
                                    total++
                                }
                            }
                        }
                    }
                }
                event = parser.next()
            }
        } catch (_: Exception) {
            brokenOff = true
        }
        val channels = programmes.mapNotNull { (id, list) ->
            val keys = kept[id] ?: return@mapNotNull null
            GuideChannel(keys, tidy(list, fromMs))
        }
        return XmltvResult(channels.filter { it.programmes.isNotEmpty() }, brokenOff)
    }

    private const val UNKNOWN_END = Long.MIN_VALUE

    /** A programme with no end runs to the next one's start, or two hours; repeats are dropped. */
    private fun tidy(list: ArrayList<Programme>, fromMs: Long): List<Programme> {
        list.sortBy { it.startMs }
        val out = ArrayList<Programme>(list.size)
        for (i in list.indices) {
            val p = list[i]
            if (out.isNotEmpty() && out.last().startMs == p.startMs) continue
            val end = if (p.endMs != UNKNOWN_END) p.endMs
            else list.getOrNull(i + 1)?.startMs?.takeIf { it > p.startMs } ?: (p.startMs + 2 * HOUR_MS)
            if (end > fromMs) out.add(if (end == p.endMs) p else p.copy(endMs = end))
        }
        out.trimToSize()
        return out
    }

    private const val HOUR_MS = 3_600_000L

    /** The `<display-name>`s of the `<channel>` the parser is on; leaves it at the channel's end tag. */
    private fun readDisplayNames(parser: XmlPullParser): List<String> {
        val names = ArrayList<String>(2)
        val depth = parser.depth
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) return names
            if (event == XmlPullParser.END_TAG && parser.depth == depth) return names
            if (event == XmlPullParser.START_TAG && parser.depth == depth + 1 && parser.name == "display-name") {
                parser.nextText().trim().takeIf { it.isNotEmpty() }?.let(names::add)
            }
        }
    }

    /** The `<title>` of the `<programme>` the parser is on; leaves it at the programme's end tag. */
    private fun readTitle(parser: XmlPullParser, language: String): String? {
        var first: String? = null
        var inLanguage: String? = null
        val depth = parser.depth
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            if (event == XmlPullParser.END_TAG && parser.depth == depth) break
            if (event == XmlPullParser.START_TAG && parser.depth == depth + 1 && parser.name == "title") {
                val lang = parser.getAttributeValue(null, "lang")
                val text = cleanTitle(parser.nextText()) ?: continue
                if (first == null) first = text
                if (inLanguage == null && lang != null && lang.startsWith(language, ignoreCase = true)) inLanguage = text
            }
        }
        return inLanguage ?: first
    }

    private val WHITESPACE = Regex("\\s+")
    private val ENTITY = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z][a-zA-Z0-9]{1,7});")

    private val XML_ENTITIES = setOf("amp", "lt", "gt", "quot", "apos")

    /** The HTML names a guide may use for a letter or sign, where XML only knows five. */
    private val NAMED: Map<String, String> = buildMap {
        put("amp", "&"); put("lt", "<"); put("gt", ">"); put("quot", "\""); put("apos", "'"); put("nbsp", " ")
        put("rsquo", "\u2019"); put("lsquo", "\u2018"); put("rdquo", "\u201D"); put("ldquo", "\u201C")
        put("laquo", "\u00AB"); put("raquo", "\u00BB"); put("hellip", "\u2026"); put("ndash", "\u2013")
        put("mdash", "\u2014"); put("euro", "\u20AC"); put("deg", "\u00B0"); put("middot", "\u00B7")
        put("bull", "\u2022"); put("copy", "\u00A9"); put("reg", "\u00AE"); put("iexcl", "\u00A1")
        put("iquest", "\u00BF"); put("szlig", "\u00DF")
        // Accented letters, in both cases: "eacute" is é, "Eacute" É.
        val letters = mapOf(
            "agrave" to 'à', "aacute" to 'á', "acirc" to 'â', "atilde" to 'ã', "auml" to 'ä', "aring" to 'å',
            "aelig" to 'æ', "ccedil" to 'ç', "egrave" to 'è', "eacute" to 'é', "ecirc" to 'ê', "euml" to 'ë',
            "igrave" to 'ì', "iacute" to 'í', "icirc" to 'î', "iuml" to 'ï', "ntilde" to 'ñ', "ograve" to 'ò',
            "oacute" to 'ó', "ocirc" to 'ô', "otilde" to 'õ', "ouml" to 'ö', "oslash" to 'ø', "oelig" to 'œ',
            "ugrave" to 'ù', "uacute" to 'ú', "ucirc" to 'û', "uuml" to 'ü', "yacute" to 'ý', "eth" to 'ð',
            "thorn" to 'þ'
        )
        for ((name, letter) in letters) {
            put(name, letter.toString())
            put(name.replaceFirstChar { it.uppercaseChar() }, letter.uppercaseChar().toString())
        }
        put("yuml", "\u00FF"); put("Yuml", "\u0178")
    }

    /**
     * A title as the banner can show it: on one line, without runs of spaces, and with the letters a
     * guide wrote as HTML entities ("M&eacute;t&eacute;o", or "&amp;eacute;" written twice over) as
     * the letters themselves. Those written once the parser already turns into letters (see [parse]);
     * those written twice over come out of it as "&eacute;", and are turned into letters here.
     */
    internal fun cleanTitle(raw: String): String? {
        var s = raw
        // Twice at most: an entity written over again ("&amp;eacute;") comes out of the first pass
        // as one ("&eacute;").
        repeat(2) {
            if ('&' !in s) return@repeat
            s = ENTITY.replace(s) { m -> decodeEntity(m.groupValues[1]) ?: m.value }
        }
        return s.replace(WHITESPACE, " ").trim().takeIf { it.isNotEmpty() }
    }

    private fun decodeEntity(body: String): String? {
        if (!body.startsWith('#')) return NAMED[body]
        val code = if (body.length > 1 && (body[1] == 'x' || body[1] == 'X')) body.substring(2).toIntOrNull(16)
        else body.substring(1).toIntOrNull()
        // No control characters, nor halves of a pair, nor what is past the last code point.
        if (code == null || code < 0x20 || code in 0xD800..0xDFFF || code > 0x10FFFF) return null
        return String(Character.toChars(code))
    }

    /** Moves past the element the parser is on, whatever it holds. */
    private fun skip(parser: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    /**
     * An XMLTV time, "20261027203000 +0100", in milliseconds since 1970. The seconds, and the offset
     * from UTC, can be missing: without an offset the time is taken as UTC, as the format says.
     */
    fun parseTime(value: String?): Long? {
        val s = value?.trim() ?: return null
        if (s.length < 12) return null
        fun digits(from: Int, count: Int): Int? {
            var n = 0
            for (i in from until from + count) {
                val c = s.getOrNull(i) ?: return null
                if (c !in '0'..'9') return null
                n = n * 10 + (c - '0')
            }
            return n
        }
        val year = digits(0, 4) ?: return null
        val month = digits(4, 2)?.takeIf { it in 1..12 } ?: return null
        val day = digits(6, 2)?.takeIf { it in 1..31 } ?: return null
        val hour = digits(8, 2)?.takeIf { it in 0..23 } ?: return null
        val minute = digits(10, 2)?.takeIf { it in 0..59 } ?: return null
        val second = digits(12, 2)?.takeIf { it in 0..60 }
        var rest = s.substring(if (second != null) 14 else 12).trim()
        // Some guides add fractions of a second.
        if (rest.startsWith('.')) rest = rest.dropWhile { it == '.' || it in '0'..'9' }.trim()
        val offsetMinutes = when {
            rest.isEmpty() || rest == "Z" || rest == "UTC" || rest == "GMT" -> 0
            (rest[0] == '+' || rest[0] == '-') && rest.length >= 5 -> {
                val hh = rest.substring(1, 3).toIntOrNull() ?: return null
                val mm = rest.substring(rest.length - 2).toIntOrNull() ?: return null
                (hh * 60 + mm) * if (rest[0] == '-') -1 else 1
            }
            else -> return null
        }
        val days = daysFromCivil(year, month, day)
        val seconds = ((days * 24 + hour) * 60 + minute) * 60 + (second ?: 0) - offsetMinutes * 60L
        return seconds * 1000
    }

    /** Days from 1970-01-01 to the given date of the proleptic Gregorian calendar. */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yearOfEra = y - era * 400
        val dayOfYear = (153 * (month + if (month > 2) -3 else 9) + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097L + dayOfEra - 719_468
    }
}
