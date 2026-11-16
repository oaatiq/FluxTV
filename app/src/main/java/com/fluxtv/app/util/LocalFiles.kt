package com.fluxtv.app.util

import android.net.Uri
import java.io.File

/**
 * The file on the TV a playlist or guide [source] names: a path as it is written, or a file: address.
 *
 * Only "file://" used to be cut off a file: address, so its other forms named a file that is not
 * there: "file:/sdcard/tv.m3u" (one slash, as Java writes them) and an address with %-escapes
 * ("file:///sdcard/My%20TV.m3u"). Those are now read as addresses. One that named an existing
 * file the old way still names that file, so nothing that opened before stops opening.
 */
fun localFile(source: String): File {
    if (!source.startsWith("file:")) return File(source)
    val asBefore = File(source.removePrefix("file://"))
    if (asBefore.exists()) return asBefore
    val path = runCatching { Uri.parse(source).path }.getOrNull()
    return if (path.isNullOrEmpty()) asBefore else File(path)
}
