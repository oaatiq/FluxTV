package com.fluxtv.app.playback

import com.fluxtv.app.data.model.Channel
import com.fluxtv.app.data.remote.headerValue
import com.fluxtv.app.data.remote.isHeaderName
import java.io.ByteArrayOutputStream

/** What is asked for to play a channel: the address, and the headers sent along with it. */
data class StreamRequest(val address: String, val userAgent: String, val headers: Map<String, String>)

/**
 * The address a channel is opened at. Playlists made for Kodi add HTTP headers to the address itself,
 * after a "|" (`http://host/live.m3u8|User-Agent=VLC%2F3.0&Referer=http%3A%2F%2Fhost%2F`). Sent as
 * written, the whole text was taken for the address, which no server knows, so those channels never
 * played; the address is the part before the "|".
 */
fun streamAddress(url: String): String = url.substringBefore('|').trim()

/**
 * The request for [channel]. Headers after the "|" in its address are read the way Kodi reads them
 * (pairs joined by "&", percent-encoded, "+" for a space). The User-Agent and Referer come from there
 * first, then from the playlist's #EXTVLCOPT lines, and the User-Agent last from [defaultUserAgent]
 * (Preferences). A header HTTP cannot carry is left out rather than failing the channel.
 */
fun streamRequestFor(channel: Channel, defaultUserAgent: String): StreamRequest {
    var userAgent = channel.userAgent?.let(::headerValue)?.takeIf { it.isNotEmpty() } ?: defaultUserAgent
    val headers = LinkedHashMap<String, String>()
    channel.referer?.let(::headerValue)?.takeIf { it.isNotEmpty() }?.let { headers["Referer"] = it }
    val options = channel.url.substringAfter('|', "")
    for (pair in options.split('&')) {
        val name = kodiDecode(pair.substringBefore('=')).trim()
        val value = headerValue(kodiDecode(pair.substringAfter('=', "")))
        if (!isHeaderName(name) || value.isEmpty()) continue
        when {
            name.equals("User-Agent", ignoreCase = true) -> userAgent = value
            name.equals("Referer", ignoreCase = true) || name.equals("Referrer", ignoreCase = true) ->
                headers["Referer"] = value
            else -> headers[name] = value
        }
    }
    return StreamRequest(streamAddress(channel.url), userAgent, headers)
}

/** Kodi's decoding of these values: "%XX" is a byte (UTF-8), "+" a space, a broken escape stays. */
private fun kodiDecode(s: String): String {
    if ('%' !in s && '+' !in s) return s
    val out = StringBuilder(s.length)
    val escaped = ByteArrayOutputStream()
    fun flush() {
        if (escaped.size() == 0) return
        out.append(String(escaped.toByteArray(), Charsets.UTF_8))
        escaped.reset()
    }
    var i = 0
    while (i < s.length) {
        val hi = if (s[i] == '%' && i + 2 < s.length) Character.digit(s[i + 1], 16) else -1
        val lo = if (hi >= 0) Character.digit(s[i + 2], 16) else -1
        if (lo >= 0) {
            escaped.write(hi * 16 + lo)
            i += 3
        } else {
            flush()
            out.append(if (s[i] == '+') ' ' else s[i])
            i++
        }
    }
    flush()
    return out.toString()
}
