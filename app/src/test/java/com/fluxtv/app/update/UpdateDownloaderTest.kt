package com.fluxtv.app.update

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

class UpdateDownloaderTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val apk = Random(7).nextBytes(300_000)
    private val apkSha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
    private val downloader = UpdateDownloader({ OkHttpClient() }, "FluxTV/test")
    private val requests = mutableListOf<RecordedRequest>()

    /** Serves [apk], honouring Range requests the way GitHub's download servers do. */
    private val servesApk = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            requests += request
            val range = request.getHeader("Range")
            if (range != null) {
                val from = range.removePrefix("bytes=").removeSuffix("-").toInt()
                if (from >= apk.size) return MockResponse().setResponseCode(416)
                return MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes $from-${apk.size - 1}/${apk.size}")
                    .setBody(Buffer().write(apk, from, apk.size - from))
            }
            return MockResponse().setBody(Buffer().write(apk))
        }
    }

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun info(sha: String = apkSha, size: Long = apk.size.toLong()) =
        UpdateInfo(versionCode = 70, versionName = "1.2.0", apkUrl = server.url("/FluxTV.apk").toString(), sha256 = sha, size = size)

    private fun download(info: UpdateInfo, target: File, rate: Long = Long.MAX_VALUE) = runBlocking {
        downloader.download(info, target, { rate }) { _, _ -> }
    }

    @Test
    fun downloadsAndChecksTheWholeFile() {
        server.dispatcher = servesApk
        val target = File(folder.root, "FluxTV-70.apk")
        assertEquals(DownloadResult.Done, download(info(), target))
        assertArrayEquals(apk, target.readBytes())
        assertFalse(File(target.path + ".part").exists())
        assertEquals("FluxTV/test", requests.single().getHeader("User-Agent"))
        // Already there and intact: nothing is fetched again.
        assertEquals(DownloadResult.Done, download(info(), target))
        assertEquals(1, requests.size)
    }

    @Test
    fun goesOnFromWhereAnInterruptedDownloadStopped() {
        val target = File(folder.root, "FluxTV-70.apk")
        // The first try is cut off a third of the way through.
        server.enqueue(
            MockResponse().setBody(Buffer().write(apk)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )
        val first = download(info(), target)
        assertTrue(first is DownloadResult.Failed)
        val kept = File(target.path + ".part").length()
        assertTrue("kept $kept bytes", kept in 1 until apk.size)

        server.dispatcher = servesApk
        assertEquals(DownloadResult.Done, download(info(), target))
        assertEquals("bytes=$kept-", requests.last().getHeader("Range"))
        assertArrayEquals(apk, target.readBytes())
    }

    @Test
    fun startsOverWhenTheServerIgnoresTheRange() {
        val target = File(folder.root, "FluxTV-70.apk")
        File(target.path + ".part").writeBytes(apk.copyOf(1000))
        // A plain 200 with the whole file: what was kept must not be added to.
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        assertEquals(DownloadResult.Done, download(info(), target))
        assertArrayEquals(apk, target.readBytes())
    }

    @Test
    fun aDamagedFileIsThrownAwayAndNeverCounts() {
        server.dispatcher = servesApk
        val target = File(folder.root, "FluxTV-70.apk")
        val result = download(info(sha = "0".repeat(64)), target)
        assertTrue(result is DownloadResult.Failed)
        assertTrue((result as DownloadResult.Failed).reason.contains("damaged"))
        assertFalse(target.exists())
        assertFalse(File(target.path + ".part").exists())
    }

    @Test
    fun refusesMoreThanTheAnnouncedSize() {
        server.dispatcher = servesApk
        val target = File(folder.root, "FluxTV-70.apk")
        val result = download(info(size = 1000), target)
        assertTrue(result is DownloadResult.Failed)
        assertFalse(target.exists())
        assertFalse(File(target.path + ".part").exists())
    }

    @Test
    fun saysWhatTheServerAnswered() {
        server.enqueue(MockResponse().setResponseCode(404))
        val result = download(info(), File(folder.root, "FluxTV-70.apk"))
        assertTrue((result as DownloadResult.Failed).reason.contains("HTTP 404"))
    }

    @Test
    fun keepsToTheRateItIsGiven() {
        server.dispatcher = servesApk
        val target = File(folder.root, "FluxTV-70.apk")
        val started = System.nanoTime()
        // 300 kB at 600 kB/s: about half a second, where unlimited takes a few milliseconds.
        assertEquals(DownloadResult.Done, download(info(), target, rate = 600_000))
        val seconds = (System.nanoTime() - started) / 1e9
        assertTrue("took $seconds s", seconds >= 0.4)
        assertArrayEquals(apk, target.readBytes())
    }

    @Test
    fun hashesLikeTheReleaseTool() {
        val file = folder.newFile("x.apk").apply { writeBytes(apk) }
        assertEquals(apkSha, sha256Hex(file))
    }
}
