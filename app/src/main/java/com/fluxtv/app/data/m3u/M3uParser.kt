package com.fluxtv.app.data.m3u

import java.io.BufferedReader

/** One parsed playlist entry before it is assigned a stable identity and channel number. */
data class M3uEntry(
    val name: String,
    val url: String,
    val logo: String? = null,
    val group: String? = null,
    val tvgId: String? = null,
    val tvgChno: Int? = null,
    val userAgent: String? = null,
    val referer: String? = null,
    /**
     * The name the entry's identity is worked out from, when it differs from [name]. Names used to be
     * cut at their last comma ("Sport 1, HD" came out as "HD"); identities still use the cut form, so
     * the favorites, numbers and last channel stored under them stay where they were.
     */
    val idName: String? = null
)

/**
 * Tolerant M3U / M3U8 parser.
 *
 * It never throws on bad input: malformed or unparseable entries are skipped so a single bad line
 * cannot make the whole playlist unusable (REQ-PL-006, REQ-PL-007, REQ-REL-002).
 */
object M3uParser {
    private val ATTR = Regex("([A-Za-z0-9_-]+)=\"(.*?)\"")

    /**
     * Reads the playlist one line at a time, straight from the download or file. A provider's list
     * can run to tens of megabytes, and holding it as one string, plus the copies that splitting it
     * into lines makes, could run a TV box out of memory. Lines end at \n, \r or \r\n, as before.
     */
    fun parse(reader: BufferedReader): List<M3uEntry> {
        val out = ArrayList<M3uEntry>()

        var name: String? = null
        var idName: String? = null
        var logo: String? = null
        var group: String? = null
        var tvgId: String? = null
        var tvgChno: Int? = null
        var userAgent: String? = null
        var referer: String? = null

        fun reset() {
            name = null; idName = null; logo = null; group = null; tvgId = null
            tvgChno = null; userAgent = null; referer = null
        }

        // A group title repeats for every channel of its group, and a logo or tvg-id for every
        // version of a channel (HD, FHD, SD), but each line brought its own copy. Entries share one
        // copy each instead: a refresh holds two playlists at once, the one on screen and the new one.
        val seen = HashMap<String, String>()
        fun shared(s: String?): String? = if (s == null) null else seen.getOrPut(s) { s }

        var first = true
        for (raw in reader.lineSequence()) {
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
                        val tvgName = attrs["tvg-name"]?.takeIf { it.isNotBlank() }
                        name = titleAt(line, titleComma(line)) ?: tvgName
                        idName = titleAt(line, line.lastIndexOf(',')) ?: tvgName
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
                        val entryIdName = idName?.takeIf { it.isNotBlank() } ?: url
                        out.add(
                            M3uEntry(
                                entryName, url, shared(logo), shared(group), shared(tvgId), tvgChno,
                                shared(userAgent), shared(referer),
                                idName = entryIdName.takeIf { it != entryName }
                            )
                        )
                        reset()
                    }
                }
            } catch (_: Exception) {
                reset()
            }
        }
        return out
    }

    /**
     * Where an #EXTINF line's title starts: the first comma outside a quoted attribute value. Values
     * and titles can both hold commas of their own (group-title="News, World", "Sport 1, HD"), so
     * neither the first comma nor the last will do. Should attributes follow that comma, it was in
     * an unquoted value or the quotes do not pair up, and the last comma is taken, as it always was.
     */
    private fun titleComma(line: String): Int {
        var quoted = false
        for (i in line.indices) {
            when (line[i]) {
                '"' -> quoted = !quoted
                ',' -> if (!quoted) return if (ATTR.find(line, i + 1) == null) i else line.lastIndexOf(',')
            }
        }
        return line.lastIndexOf(',')
    }

    /** The title that starts after the comma at [comma], or null if there is none. */
    private fun titleAt(line: String, comma: Int): String? =
        if (comma < 0) null else line.substring(comma + 1).trim().takeIf { it.isNotBlank() }
}
