package com.fluxtv.app.data.remote

import android.content.Context
import android.net.Uri
import com.fluxtv.app.data.m3u.M3uEntry
import com.fluxtv.app.data.m3u.M3uParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

sealed class FetchResult {
    data class Success(val entries: List<M3uEntry>) : FetchResult()
    /** [httpCode]: the provider's server answered, with this error. */
    data class Failure(val reason: String, val httpCode: Int? = null) : FetchResult()
}

/**
 * Reads an M3U playlist from a remote URL, a local file path, or a content:// URI (REQ-PL-002).
 *
 * The playlist is parsed line by line as it arrives instead of being read into one string first: a
 * provider's list can run to tens of megabytes, and that string plus its copies could take more
 * memory than a TV box gives an app, while a channel is playing. An empty playlist comes back as an
 * empty list, which the repository reports like any other playlist with no channels.
 */
class PlaylistFetcher(private val context: Context) {

    // Built on first use, which is a refresh on a background thread. Built with the class it set up
    // TLS on the main thread in Application.onCreate, at every start, before FluxTV showed anything.
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // A stall is caught here, between reads. The whole call gets far longer: it includes
            // reading the playlist, and at 60 s a large list over a slow line could never finish.
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun fetch(source: String, type: String): FetchResult {
        return try {
            when {
                source.startsWith("content:") -> {
                    val stream = context.contentResolver.openInputStream(Uri.parse(source))
                        ?: return FetchResult.Failure("empty content")
                    FetchResult.Success(stream.bufferedReader().use { M3uParser.parse(it) })
                }

                type == "file" || source.startsWith("/") || source.startsWith("file:") -> {
                    val path = source.removePrefix("file://")
                    FetchResult.Success(File(path).bufferedReader().use { M3uParser.parse(it) })
                }

                else -> {
                    val request = Request.Builder()
                        .url(source)
                        .header("User-Agent", "FluxTV/1.0")
                        .build()
                    client.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) return FetchResult.Failure("HTTP ${resp.code}", resp.code)
                        val body = resp.body ?: return FetchResult.Failure("empty response")
                        // charStream() decodes with the response's charset and drops a BOM, as string() did.
                        FetchResult.Success(body.charStream().buffered().use { M3uParser.parse(it) })
                    }
                }
            }
        } catch (e: Exception) {
            FetchResult.Failure(e.message ?: e.javaClass.simpleName)
        }
    }
}
