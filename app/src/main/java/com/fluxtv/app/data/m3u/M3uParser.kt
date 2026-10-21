package com.fluxtv.app.data.m3u

/** One parsed playlist entry before it is assigned a stable identity and channel number. */
data class M3uEntry(
    val name: String,
    val url: String,
    val logo: String? = null,
    val group: String? = null,
    val tvgId: String? = null,
    val tvgChno: Int? = null,
    val userAgent: String? = null,
    val referer: String? = null
)

/**
 * Tolerant M3U / M3U8 parser.
 *
 * It never throws on bad input: malformed or unparseable entries are skipped so a single bad line
 * cannot make the whole playlist unusable (REQ-PL-006, REQ-PL-007, REQ-REL-002).
 */
object M3uParser {
    private val ATTR = Regex("([A-Za-z0-9_-]+)=\"(.*?)\"")

    fun parse(content: String): List<M3uEntry> {
        val out = ArrayList<M3uEntry>()
        val lines = content.replace("\r\n", "\n").replace('\r', '\n').split('\n')

        var name: String? = null
        var logo: String? = null
        var group: String? = null
        var tvgId: String? = null
        var tvgChno: Int? = null
        var userAgent: String? = null
        var referer: String? = null

        fun reset() {
            name = null; logo = null; group = null; tvgId = null
            tvgChno = null; userAgent = null; referer = null
        }

        var first = true
        for (raw in lines) {
            var line = raw.trim()
            if (first) {
                line = line.removePrefix("﻿").trim()
                first = false
            }
            if (line.isEmpty()) continue
            try {
                when {
                    line.startsWith("#EXTM3U", ignoreCase = true) -> { /* header */ }

                    line.startsWith("#EXTINF", ignoreCase = true) -> {
                        val attrs = HashMap<String, String>()
                        for (m in ATTR.findAll(line)) {
                            attrs[m.groupValues[1].lowercase()] = m.groupValues[2]
                        }
                        tvgId = attrs["tvg-id"]?.ifBlank { null }
                        logo = attrs["tvg-logo"]?.ifBlank { null }
                        group = attrs["group-title"]?.ifBlank { null }
                        tvgChno = attrs["tvg-chno"]?.trim()?.toIntOrNull()
                        val comma = line.lastIndexOf(',')
                        val display = if (comma >= 0) line.substring(comma + 1).trim() else ""
                        name = when {
                            display.isNotBlank() -> display
                            !attrs["tvg-name"].isNullOrBlank() -> attrs["tvg-name"]
                            else -> null
                        }
                    }

                    line.startsWith("#EXTGRP", ignoreCase = true) -> {
                        val v = line.substringAfter(':', "").trim()
                        if (v.isNotEmpty()) group = v
                    }

                    line.startsWith("#EXTVLCOPT", ignoreCase = true) -> {
                        val v = line.substringAfter(':', "")
                        val key = v.substringBefore('=').trim().lowercase()
                        val value = v.substringAfter('=', "").trim()
                        when (key) {
                            "http-user-agent" -> userAgent = value.ifBlank { null }
                            "http-referrer", "http-referer" -> referer = value.ifBlank { null }
                        }
                    }

                    line.startsWith("#") -> { /* #KODIPROP and other directives ignored */ }

                    else -> {
                        val url = line
                        val entryName = name?.takeIf { it.isNotBlank() } ?: url
                        out.add(M3uEntry(entryName, url, logo, group, tvgId, tvgChno, userAgent, referer))
                        reset()
                    }
                }
            } catch (_: Exception) {
                reset()
            }
        }
        return out
    }
}
