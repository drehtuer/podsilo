// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.feed

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import net.drehtuer.podsilo.core.model.Episode
import net.drehtuer.podsilo.core.model.Feed
import net.drehtuer.podsilo.core.model.port.EpisodeRepository
import net.drehtuer.podsilo.core.model.port.FeedRefreshMetadata
import net.drehtuer.podsilo.core.model.port.FeedRepository
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private const val NOW_MILLIS = 1_784_019_600_000

/**
 * [FeedRefreshWorker] against MockWebServer + in-memory ports. Robolectric because rssparser's
 * Android target resolves `XmlPullParserFactory` at runtime (`docs/decisions/0005`) — headless, no
 * emulator.
 */
@RunWith(RobolectricTestRunner::class)
class FeedRefreshWorkerTest {
    private lateinit var server: MockWebServer
    private lateinit var context: Context
    private val feeds = RecordingFeedRepository()
    private val episodes = RecordingEpisodeRepository()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        context = RuntimeEnvironment.getApplication()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun feedXml(itemTitles: List<String>): String =
        buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><rss version=\"2.0\"><channel>")
            append("<title>Der Podcast</title>")
            itemTitles.forEachIndexed { index, title ->
                append("<item><title>$title</title><guid>guid-$index</guid>")
                append("<enclosure url=\"https://example.org/ep$index.mp3\" type=\"audio/mpeg\"/></item>")
            }
            append("</channel></rss>")
        }

    private fun feed(
        path: String = "/feed.xml",
        title: String = "placeholder",
        etag: String? = null,
    ) = Feed(
        url = server.url(path).toString(),
        title = title,
        imageUrl = null,
        firstSeenAt = 0,
        lastRefreshedAt = null,
        httpEtag = etag,
        httpLastModified = null,
    )

    private fun buildWorker(): FeedRefreshWorker {
        val factory =
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker =
                    FeedRefreshWorker(
                        appContext = appContext,
                        workerParameters = workerParameters,
                        feedRefresher =
                            FeedRefresher(
                                feedRepository = feeds,
                                episodeRepository = episodes,
                                feedFetcher = FeedFetcher(),
                                feedXmlParser = FeedXmlParser(),
                                clock = Clock.fixed(Instant.ofEpochMilli(NOW_MILLIS), ZoneOffset.UTC),
                            ),
                    )
            }
        return TestListenableWorkerBuilder<FeedRefreshWorker>(context).setWorkerFactory(factory).build()
    }

    @Test
    fun `a 200 replaces the feed's episodes and records its title and validators`() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("ETag", "\"v1\"")
                    .setHeader("Last-Modified", "Tue, 14 Jul 2026 09:00:00 GMT")
                    .setBody(feedXml(listOf("Folge 1", "Folge 2"))),
            )
            feeds.seed(feed())

            val result = buildWorker().doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            assertEquals(
                2,
                episodes.stored.values
                    .single()
                    .size,
            )
            val refreshed = checkNotNull(feeds.get(feed().url))
            assertEquals("Der Podcast", refreshed.title)
            assertEquals("\"v1\"", refreshed.httpEtag)
            assertEquals("Tue, 14 Jul 2026 09:00:00 GMT", refreshed.httpLastModified)
            assertEquals(NOW_MILLIS, refreshed.lastRefreshedAt)
        }

    @Test
    fun `a 304 sends the stored validators and leaves the cached episodes alone`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(304))
            feeds.seed(feed(title = "Der Podcast", etag = "\"v1\""))

            val result = buildWorker().doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            assertEquals("\"v1\"", server.takeRequest().getHeader("If-None-Match"))
            assertTrue("a 304 must not rewrite the episode cache", episodes.stored.isEmpty())
            assertEquals(0, feeds.metadataUpdates)
        }

    @Test
    fun `one feed failing transiently does not stop the others, and asks for a retry`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setResponseCode(200).setBody(feedXml(listOf("Folge 1"))))
            feeds.seed(feed("/broken.xml"), feed("/working.xml"))

            val result = buildWorker().doWork()

            assertEquals(ListenableWorker.Result.retry(), result)
            assertEquals(setOf(feed("/working.xml").url), episodes.stored.keys)
        }

    @Test
    fun `a permanently gone feed is not retried and is not unsubscribed either`() =
        runBlocking {
            // Subscriptions belong to the server; a 404 here is not ours to act on (CLAUDE.md §1).
            server.enqueue(MockResponse().setResponseCode(404))
            feeds.seed(feed())

            val result = buildWorker().doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            assertEquals(1, feeds.getAll().size)
        }

    @Test
    fun `unparseable XML keeps the previous cache instead of wiping it`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(200).setBody("this is not XML at all"))
            feeds.seed(feed())

            val result = buildWorker().doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            assertTrue(episodes.stored.isEmpty())
        }

    @Test
    fun `refreshing a large feed writes episodes and nothing else`() =
        runBlocking {
            // The no-auto-download invariant at the refresh layer (CLAUDE.md §7 item 6): a feed with
            // hundreds of untouched episodes must produce exactly zero downloads and zero actions.
            // Structurally guaranteed — this worker has no ledger, client, or download dependency at
            // all — and asserted here so a future edit that adds one fails loudly.
            server.enqueue(MockResponse().setResponseCode(200).setBody(feedXml((1..500).map { "Folge $it" })))
            feeds.seed(feed())

            buildWorker().doWork()

            assertEquals(
                500,
                episodes.stored.values
                    .single()
                    .size,
            )
        }
}

private class RecordingFeedRepository : FeedRepository {
    private val feeds = mutableListOf<Feed>()
    var metadataUpdates: Int = 0
        private set

    fun seed(vararg seeded: Feed) {
        feeds += seeded
    }

    override fun observeAll(): Flow<List<Feed>> = MutableStateFlow(feeds.toList())

    override suspend fun getAll(): List<Feed> = feeds.toList()

    override suspend fun get(url: String): Feed? = feeds.firstOrNull { it.url == url }

    override suspend fun replaceAll(feeds: List<Feed>) = error("the refresh worker never writes the subscription list")

    override suspend fun updateRefreshMetadata(
        feedUrl: String,
        metadata: FeedRefreshMetadata,
    ) {
        metadataUpdates++
        val index = feeds.indexOfFirst { it.url == feedUrl }
        if (index < 0) return
        feeds[index] =
            feeds[index].copy(
                title = metadata.title,
                imageUrl = metadata.imageUrl,
                httpEtag = metadata.httpEtag,
                httpLastModified = metadata.httpLastModified,
                lastRefreshedAt = metadata.refreshedAt,
            )
    }
}

private class RecordingEpisodeRepository : EpisodeRepository {
    val stored = mutableMapOf<String, List<Episode>>()

    override fun observeForFeed(feedUrl: String): Flow<List<Episode>> = MutableStateFlow(stored[feedUrl].orEmpty())

    override suspend fun get(episodeKey: String): Episode? =
        stored.values.flatten().firstOrNull {
            it.episodeKey ==
                episodeKey
        }

    override suspend fun replaceForFeed(
        feedUrl: String,
        episodes: List<Episode>,
    ) {
        stored[feedUrl] = episodes
    }

    override suspend fun deleteForFeed(feedUrl: String) {
        stored.remove(feedUrl)
    }
}
