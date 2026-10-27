package com.fluxtv.app.data.remote

import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** The name FluxTV gives providers' servers unless Preferences or the playlist gives another. */
const val DEFAULT_USER_AGENT = "FluxTV/1.0"

/**
 * [value] without what an HTTP header cannot carry. OkHttp refuses a request with a control
 * character or anything outside ASCII in a header, so one stray character, typed in Preferences or
 * written in a playlist, would fail every request it went with.
 */
fun headerValue(value: String): String = value.filter { it == '\t' || it in ' '..'~' }.trim()

/** Whether [name] can be an HTTP header name (letters, digits and the punctuation HTTP allows). */
fun isHeaderName(name: String): Boolean =
    name.isNotEmpty() && name.all { it in '!'..'~' && it !in "\"(),/:;<=>?@[\\]{}" }

/**
 * The settings every connection FluxTV makes starts from: playlist downloads and channels alike, so
 * they share one pool of connections and threads. The timeouts catch a connection that has stalled
 * without cutting short a long download or a live stream, which never ends. IPTV servers are often
 * old, so older TLS versions and cipher suites are accepted after the modern ones rather than
 * failing those servers' channels.
 */
fun newHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT))
    .build()
