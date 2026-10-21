package com.fluxtv.app.util

import java.security.MessageDigest

/** Stable, dependency-free hash used to derive channel identities that survive playlist refreshes. */
fun sha1Hex(input: String): String {
    val md = MessageDigest.getInstance("SHA-1")
    val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}
