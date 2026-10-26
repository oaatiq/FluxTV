package com.fluxtv.app.util

import java.security.MessageDigest

private val HEX_DIGITS = "0123456789abcdef".toCharArray()

/**
 * One digest per thread: looking SHA-1 up again for every channel costs more than hashing it, and a
 * refresh hashes every channel without a tvg-id. (ThreadLocal.withInitial needs API 26.)
 */
private val sha1 = object : ThreadLocal<MessageDigest>() {
    override fun initialValue(): MessageDigest = MessageDigest.getInstance("SHA-1")
}

/**
 * Stable, dependency-free hash used to derive channel identities that survive playlist refreshes.
 *
 * The output is lowercase hex, two digits per byte, exactly what the earlier String.format version
 * produced, so the identities stored with favorites and the last channel keep matching. Formatting
 * each byte through a Formatter was the slow part: 50,000 channels took about a second on a desktop
 * JVM that way, against tens of milliseconds with the table, and a TV's CPU is several times slower.
 */
fun sha1Hex(input: String): String {
    val bytes = sha1.get().digest(input.toByteArray(Charsets.UTF_8))
    val out = CharArray(bytes.size * 2)
    for (i in bytes.indices) {
        val v = bytes[i].toInt() and 0xff
        out[i * 2] = HEX_DIGITS[v ushr 4]
        out[i * 2 + 1] = HEX_DIGITS[v and 0x0f]
    }
    return String(out)
}
