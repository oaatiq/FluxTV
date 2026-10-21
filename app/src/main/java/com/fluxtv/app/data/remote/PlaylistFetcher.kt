package com.fluxtv.app.data.remote

import android.content.Context
import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

sealed class FetchResult {
    data class Success(val content: String) : FetchResult()
    data class Failure(val reason: String) : FetchResult()
}

/** Fetches raw M3U content from a remote URL, a local file path, or a content:// URI (REQ-PL-002). */
class PlaylistFetcher(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun fetch(source: String, type: String): FetchResult {
        return try {
            when {
                source.startsWith("content:") -> {
                    val content = context.contentResolver.openInputStream(Uri.parse(source))
                        ?.bufferedReader()?.use { it.readText() }
                    if (content.isNullOrBlank()) FetchResult.Failure("empty content") else FetchResult.Success(content)
                }

                type == "file" || source.startsWith("/") || source.startsWith("file:") -> {
                    val path = source.removePrefix("file://")
                    val content = File(path).readText()
                    if (content.isBlank()) FetchResult.Failure("empty file") else FetchResult.Success(content)
                }

                else -> {
                    val request = Request.Builder()
                        .url(source)
                        .header("User-Agent", "FluxTV/1.0")
                        .build()
                    client.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) return FetchResult.Failure("HTTP ${resp.code}")
                        val body = resp.body?.string()
                        if (body.isNullOrBlank()) FetchResult.Failure("empty response") else FetchResult.Success(body)
                    }
                }
            }
        } catch (e: Exception) {
            FetchResult.Failure(e.message ?: e.javaClass.simpleName)
        }
    }
}
