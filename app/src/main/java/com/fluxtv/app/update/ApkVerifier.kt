package com.fluxtv.app.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.security.MessageDigest

/**
 * Checks a downloaded build before it is installed, so that only a genuine, newer FluxTV is ever
 * handed to Android:
 *
 * 1. its SHA-256 is the one the release published (the file is whole and unchanged since);
 * 2. it is an Android app, and it is FluxTV (same package);
 * 3. it is the build the release announced, and newer than the one running;
 * 4. it is signed with the same key as the FluxTV on this TV.
 *
 * The last one is what makes it safe even if the release's address were taken over: nobody without
 * FluxTV's key can produce a file that passes it. Android checks the key again when it installs, and
 * an update signed with another key could only be installed by removing FluxTV first, which would
 * lose its settings, so FluxTV never tries one.
 */
object ApkVerifier {

    /** Null when [apk] may be installed over this FluxTV; else why not, in words. */
    fun check(context: Context, apk: File, info: UpdateInfo): String? {
        if (!apk.exists()) return "The downloaded update is gone"
        if (info.size > 0 && apk.length() != info.size) return "The downloaded update has the wrong size"
        if (sha256Hex(apk) != info.sha256) return "The downloaded update is damaged (its checksum does not match)"

        val pm = context.packageManager
        val archive = archiveInfo(pm, apk) ?: return "The downloaded file is not an Android app"
        if (archive.packageName != context.packageName) {
            return "The downloaded app is not FluxTV (${archive.packageName})"
        }
        val code = PackageInfoCompat.getLongVersionCode(archive)
        if (code != info.versionCode) return "The downloaded app is build $code, not build ${info.versionCode} as announced"
        val installed = installedInfo(context) ?: return "FluxTV could not read its own version"
        if (code <= PackageInfoCompat.getLongVersionCode(installed)) return "The downloaded app is not newer than this FluxTV"

        val ours = currentSigners(installed)
        val theirs = allSigners(archive)
        // An Android that cannot read the archive's signers leaves the check to the installation.
        if (ours.isNotEmpty() && theirs.isNotEmpty() && !theirs.containsAll(ours)) {
            return "The downloaded app is not signed with this FluxTV's key"
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, apk: File): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        return try {
            pm.getPackageArchiveInfo(apk.path, flags)
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    fun installedInfo(context: Context): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        return try {
            context.packageManager.getPackageInfo(context.packageName, flags)
        } catch (_: Exception) {
            null
        }
    }

    /** The key(s) a package is signed with now. */
    @Suppress("DEPRECATION")
    private fun currentSigners(info: PackageInfo): Set<String> {
        val signatures: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo ?: return emptySet()
            if (signing.hasMultipleSigners()) signing.apkContentsSigners
            else signing.signingCertificateHistory?.lastOrNull()?.let { arrayOf(it) }
        } else {
            info.signatures
        }
        return signatures.orEmpty().map(::digest).toSet()
    }

    /** The key(s) a package is signed with, and those it proves it took over from (key rotation). */
    @Suppress("DEPRECATION")
    private fun allSigners(info: PackageInfo): Set<String> {
        val signatures: List<Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo ?: return emptySet()
            signing.apkContentsSigners.orEmpty().toList() + signing.signingCertificateHistory.orEmpty().toList()
        } else {
            info.signatures.orEmpty().toList()
        }
        return signatures.map(::digest).toSet()
    }

    private fun digest(signature: Signature): String =
        MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
}
