package com.fluxtv.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManifestTest {

    private val sha = "3F5C".padEnd(64, 'A')
    private val base = "https://github.com/oaatiq/FluxTV/releases/latest/download/update.json"

    private fun manifest(apk: String = "https://example.com/FluxTV-1.2.0.apk", extra: String = "") = """
        {
          "versionCode": 70,
          "versionName": " 1.2.0 ",
          "apkUrl": "$apk",
          "sha256": "$sha",
          "size": 24117248,
          "minSdk": 24$extra
        }
    """.trimIndent()

    @Test
    fun readsAReleaseAndNormalisesTheChecksum() {
        val info = UpdateManifest.parse(manifest(), base)
        assertEquals(70L, info.versionCode)
        assertEquals("1.2.0", info.versionName)
        assertEquals("https://example.com/FluxTV-1.2.0.apk", info.apkUrl)
        assertEquals(sha.lowercase(), info.sha256)
        assertEquals(24117248L, info.size)
        assertEquals(24, info.minSdk)
        assertEquals("1.2.0 (build 70)", info.label)
    }

    @Test
    fun ignoresFieldsItDoesNotKnow() {
        val info = UpdateManifest.parse(manifest(extra = ",\n\"channel\": \"stable\", \"notes\": \"Faster start\""), base)
        assertEquals("Faster start", info.notes)
    }

    @Test
    fun resolvesAnApkWrittenRelativeToTheManifest() {
        val info = UpdateManifest.parse(manifest(apk = "FluxTV 1.2.0.apk"), "https://tv.example.org/fluxtv/update.json")
        assertEquals("https://tv.example.org/fluxtv/FluxTV%201.2.0.apk", info.apkUrl)
        val up = UpdateManifest.parse(manifest(apk = "../builds/a.apk"), "https://tv.example.org/fluxtv/update.json")
        assertEquals("https://tv.example.org/builds/a.apk", up.apkUrl)
    }

    @Test
    fun refusesWhatCannotBeChecked() {
        fun refused(text: String) = assertThrows(ManifestException::class.java) { UpdateManifest.parse(text, base) }
        refused("not json")
        refused(manifest().replace(sha, "1234"))
        refused(manifest().replace("\"versionCode\": 70", "\"versionCode\": 0"))
        refused(manifest().replace("24117248", "999999999999"))
        refused(manifest(apk = "ftp://example.com/a.apk"))
        refused(manifest(apk = "file:///sdcard/a.apk"))
        refused(manifest(apk = ""))
        // No APK address at all.
        refused("""{"versionCode": 3, "sha256": "$sha"}""")
    }

    @Test
    fun labelsABuildWithoutAName() {
        assertEquals("build 12", versionLabel("", 12))
        assertTrue(versionLabel("1.1.64", 64).contains("build 64"))
    }
}
