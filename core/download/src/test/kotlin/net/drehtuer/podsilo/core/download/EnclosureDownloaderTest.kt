// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.download

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The download state machine CLAUDE.md §7 item 4 asks for: resume, cancel, disk full, 404,
 * redirect, and a server that doesn't support range requests. All against MockWebServer -- no real
 * network, no emulator.
 */
class EnclosureDownloaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val downloader = EnclosureDownloader()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun url() = server.url("/ep1.mp3").toString()

    private fun destination(name: String = "cache"): File = File(temporaryFolder.newFolder(name), "episode.partial")

    private fun partialFileContaining(text: String): File =
        destination().apply {
            parentFile.mkdirs()
            writeText(text)
        }

    @Test
    fun `downloads a whole file and reports its Content-Type`() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "audio/mp4")
                    .setBody("podcast bytes"),
            )
            val file = destination()

            val result = downloader.download(url(), file)

            val completed = result as EnclosureDownloadResult.Completed
            assertEquals("audio/mp4", completed.contentType)
            assertEquals("podcast bytes", file.readText())
        }

    @Test
    fun `resumes from a partial file with a Range request`() =
        runBlocking {
            val file = partialFileContaining("podcast ")
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 8-12/13")
                    .setBody("bytes"),
            )

            val result = downloader.download(url(), file)

            assertTrue("expected Completed, got $result", result is EnclosureDownloadResult.Completed)
            assertEquals("podcast bytes", file.readText())
            assertEquals("bytes=8-", server.takeRequest().getHeader("Range"))
        }

    @Test
    fun `a server that ignores Range restarts from zero instead of appending`() =
        runBlocking {
            val file = partialFileContaining("stale prefix")
            // 200, not 206: the body is the whole file. Appending it would corrupt the download.
            server.enqueue(MockResponse().setResponseCode(200).setBody("podcast bytes"))

            val result = downloader.download(url(), file)

            assertTrue("expected Completed, got $result", result is EnclosureDownloadResult.Completed)
            assertEquals("podcast bytes", file.readText())
        }

    @Test
    fun `a 416 discards the stale partial and restarts once, without the Range header`() =
        runBlocking {
            val file = partialFileContaining("prefix of a file that has since been replaced")
            server.enqueue(MockResponse().setResponseCode(416))
            server.enqueue(MockResponse().setResponseCode(200).setBody("brand new bytes"))

            val result = downloader.download(url(), file)

            assertTrue("expected Completed, got $result", result is EnclosureDownloadResult.Completed)
            assertEquals("brand new bytes", file.readText())
            assertEquals("bytes=45-", server.takeRequest().getHeader("Range"))
            // Carrying the Range over would 416 again, forever.
            assertNull(server.takeRequest().getHeader("Range"))
        }

    @Test
    fun `a 404 is reported as an HTTP error`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(404))

            val result = downloader.download(url(), destination())

            assertEquals(404, (result as EnclosureDownloadResult.HttpError).code)
        }

    @Test
    fun `a 500 is reported as an HTTP error too - retry classification is the caller's`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(500))

            val result = downloader.download(url(), destination())

            assertEquals(500, (result as EnclosureDownloadResult.HttpError).code)
        }

    @Test
    fun `redirects are followed`() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", server.url("/real.mp3").toString()),
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody("podcast bytes"))
            val file = destination()

            val result = downloader.download(url(), file)

            assertTrue("expected Completed, got $result", result is EnclosureDownloadResult.Completed)
            assertEquals("podcast bytes", file.readText())
        }

    @Test
    fun `a truncated body is a retryable network error and keeps what arrived`() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(Buffer().writeUtf8("half"))
                    // After setBody, which sets its own Content-Length — a body shorter than the
                    // length it advertises is exactly what a dropped connection looks like.
                    .setHeader("Content-Length", "64")
                    .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END),
            )
            val file = destination()

            val result = downloader.download(url(), file)

            assertTrue("expected a NetworkError, got $result", result is EnclosureDownloadResult.NetworkError)
            // The prefix survives, so the next attempt resumes rather than starting over.
            assertTrue(file.length() > 0)
        }

    @Test
    fun `an empty body is not treated as a finished download`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(200))

            val result = downloader.download(url(), destination())

            assertTrue("expected a NetworkError, got $result", result is EnclosureDownloadResult.NetworkError)
        }

    @Test
    fun `an unwritable cache path is a write error, not a network error`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(200).setBody("podcast bytes"))
            // Stands in for disk-full: a directory where the file should go makes the sink fail to
            // open in exactly the place a full disk would fail to write.
            val blocked = temporaryFolder.newFolder("cache2", "episode.partial")

            val result = downloader.download(url(), blocked)

            assertTrue("expected a WriteError, got $result", result is EnclosureDownloadResult.WriteError)
        }

    @Test
    fun `cancellation propagates and leaves the partial file for the next attempt`() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(Buffer().write(ByteArray(512 * 1024)))
                    .throttleBody(8 * 1024, 100, TimeUnit.MILLISECONDS),
            )
            val file = destination()

            // withTimeoutOrNull cancels the coroutine mid-copy, exactly as WorkManager stopping the
            // worker does; download() must let that propagate rather than swallowing it as a result.
            val outcome = withTimeoutOrNull(500) { downloader.download(url(), file) }

            assertNull("a cancelled download must not report an outcome", outcome)
            assertTrue("a cancelled download must leave its bytes behind to resume from", file.length() > 0)
        }
}
