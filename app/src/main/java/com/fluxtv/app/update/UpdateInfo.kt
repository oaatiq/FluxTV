package com.fluxtv.app.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI

/**
 * One build of FluxTV, as the release's `update.json` describes it (written by
 * tools/make_update_manifest.py):
 *
 * ```json
 * {
 *   "versionCode": 70,
 *   "versionName": "1.2.0",
 *   "apkUrl": "https://github.com/oaatiq/FluxTV/releases/download/v1.2.0/FluxTV-1.2.0.apk",
 *   "sha256": "3f5c…(64 hex digits)",
 *   "size": 24117248,
 *   "minSdk": 24
 * }
 * ```
 *
 * [apkUrl] may also be written relative to the address of `update.json` itself. [sha256] and [size]
 * are what the downloaded file must match before anything is installed; [minSdk] keeps a TV too old
 * for a build from downloading it at all.
 */
@Serializable
data class UpdateInfo(
    val versionCode: Long,
    val versionName: String = "",
    val apkUrl: String,
    val sha256: String,
    val size: Long = 0,
    val minSdk: Int = 0,
    val notes: String = ""
) {
    /** How the build is named to the administrator: "1.2.0 (build 70)". */
    val label: String get() = versionLabel(versionName, versionCode)
}

fun versionLabel(name: String, code: Long): String =
    if (name.isBlank()) "build $code" else "$name (build $code)"

/** Why an update.json could not be used, in words for Settings → Updates. */
class ManifestException(message: String) : Exception(message)

object UpdateManifest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Largest APK FluxTV agrees to download: well above its own size, far below a TV's free space. */
    const val MAX_APK_BYTES = 300L * 1024 * 1024

    private val HEX_64 = Regex("^[0-9a-f]{64}$")

    /**
     * Reads [text] fetched from [manifestUrl]. The APK's address comes back absolute (resolved
     * against [manifestUrl] if written relative to it) and the checksum in lower case.
     */
    fun parse(text: String, manifestUrl: String): UpdateInfo {
        val raw = try {
            json.decodeFromString(UpdateInfo.serializer(), text)
        } catch (e: Exception) {
            throw ManifestException("The update description could not be read (${e.javaClass.simpleName})")
        }
        if (raw.versionCode <= 0) throw ManifestException("The update description has no build number")
        val sha = raw.sha256.trim().lowercase()
        if (!HEX_64.matches(sha)) throw ManifestException("The update description has no valid SHA-256 checksum")
        if (raw.size < 0 || raw.size > MAX_APK_BYTES) throw ManifestException("The update's size (${raw.size} bytes) is not plausible")
        val apk = resolve(manifestUrl, raw.apkUrl.trim())
        return raw.copy(apkUrl = apk, sha256 = sha, versionName = raw.versionName.trim())
    }

    private fun resolve(base: String, reference: String): String {
        if (reference.isEmpty()) throw ManifestException("The update description names no APK")
        val uri = try {
            URI(base).resolve(URI(reference.replace(" ", "%20")))
        } catch (_: Exception) {
            throw ManifestException("The update's APK address is not valid")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") throw ManifestException("The update's APK address must be http or https")
        return uri.toString()
    }
}
