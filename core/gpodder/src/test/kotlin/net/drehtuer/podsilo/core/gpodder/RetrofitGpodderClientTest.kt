// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.gpodder

import kotlinx.coroutines.runBlocking
import net.drehtuer.podsilo.core.model.port.EpisodeAction
import net.drehtuer.podsilo.core.model.port.EpisodeActionType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class RetrofitGpodderClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: RetrofitGpodderClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = newClient()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun newClient(readTimeoutMillis: Long = 5_000): RetrofitGpodderClient {
        val okHttpClient =
            OkHttpClient
                .Builder()
                .readTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
                .build()
        return RetrofitGpodderClient.create(
            baseUrl = server.url("/").toString().trimEnd('/'),
            credentials = GpodderCredentials(username = "alice", password = "app-password"),
            okHttpClient = okHttpClient,
        )
    }

    private fun enqueueJson(
        body: String,
        code: Int = 200,
    ) {
        server.enqueue(
            MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setBody(body),
        )
    }

    private fun RecordedRequest.pathOnly() = requireNotNull(path).substringBefore('?')

    private fun RecordedRequest.queryOf(name: String) = requestUrl?.queryParameter(name)

    // --- request shape -------------------------------------------------------------------------

    @Test
    fun `subscriptions request hits the gpoddersync path with basic auth and no since param`() =
        runBlocking {
            enqueueJson("""{"add":[],"remove":[],"timestamp":0}""")

            client.fetchSubscriptions(since = null)

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/index.php/apps/gpoddersync/subscriptions", request.pathOnly())
            // Omitted entirely, not sent as since=null or since=0 — a `since` of 0 would still be a
            // full list here, but only by luck of the server's epoch handling.
            assertNull(request.queryOf("since"))
            // "alice:app-password" base64-encoded.
            assertEquals("Basic YWxpY2U6YXBwLXBhc3N3b3Jk", request.getHeader("Authorization"))
            assertEquals("application/json", request.getHeader("Accept"))
        }

    @Test
    fun `a base url with a trailing slash does not double up the path`() =
        runBlocking {
            val trailingSlashClient =
                RetrofitGpodderClient.create(
                    baseUrl = server.url("/").toString(),
                    credentials = GpodderCredentials("alice", "app-password"),
                )
            enqueueJson("""{"add":[],"remove":[],"timestamp":0}""")

            trailingSlashClient.fetchSubscriptions(since = null)

            assertEquals("/index.php/apps/gpoddersync/subscriptions", server.takeRequest().pathOnly())
        }

    @Test
    fun `episode actions request sends since as unix seconds`() =
        runBlocking {
            enqueueJson("""{"actions":[],"timestamp":0}""")

            client.fetchEpisodeActions(since = 1_752_480_000L)

            val request = server.takeRequest()
            assertEquals("/index.php/apps/gpoddersync/episode_action", request.pathOnly())
            assertEquals("1752480000", request.queryOf("since"))
        }

    @Test
    fun `posted actions are a bare json array with the exact wire field names`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(200))

            val action =
                EpisodeAction(
                    podcast = "https://example.com/feed.xml",
                    episode = "https://example.com/ep1.mp3",
                    guid = "guid-1",
                    action = EpisodeActionType.PLAY,
                    timestamp = "2026-07-14T09:00:00",
                    started = 0,
                    position = 1800,
                    total = 1800,
                )

            client.postEpisodeActions(listOf(action))

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/index.php/apps/gpoddersync/episode_action/create", request.pathOnly())

            val body = request.body.readUtf8()
            assertTrue("body must be a bare JSON array, was: $body", body.trimStart().startsWith("["))
            assertTrue(body.contains(""""podcast":"https://example.com/feed.xml""""))
            assertTrue(body.contains(""""episode":"https://example.com/ep1.mp3""""))
            assertTrue(body.contains(""""guid":"guid-1""""))
            assertTrue(body.contains(""""action":"PLAY""""))
            assertTrue(body.contains(""""timestamp":"2026-07-14T09:00:00""""))
            assertTrue(body.contains(""""started":0"""))
            assertTrue(body.contains(""""position":1800"""))
            assertTrue(body.contains(""""total":1800"""))
        }

    @Test
    fun `absent optional fields are omitted from the body, not sent as null`() =
        runBlocking {
            // nextcloud-gpodder reads posted fields with isset()-style checks, so an explicit null is
            // not equivalent to omitting the key.
            server.enqueue(MockResponse().setResponseCode(200))

            val downloadAction =
                EpisodeAction(
                    podcast = "https://example.com/feed.xml",
                    episode = "https://example.com/ep1.mp3",
                    guid = null,
                    action = EpisodeActionType.DOWNLOAD,
                    timestamp = "2026-07-14T09:00:00",
                )

            client.postEpisodeActions(listOf(downloadAction))

            val body = server.takeRequest().body.readUtf8()
            assertFalse("must not contain nulls, was: $body", body.contains("null"))
            assertFalse(body.contains("\"guid\""))
            assertFalse(body.contains("\"started\""))
        }

    @Test
    fun `subscription_change create is never requested`() =
        runBlocking {
            // Structural: GpodderService has no such method at all (CLAUDE.md §1). This asserts the
            // observable consequence — a full client exercise touches only the three allowed paths.
            enqueueJson("""{"add":["https://example.com/feed.xml"],"remove":[],"timestamp":1}""")
            enqueueJson("""{"actions":[],"timestamp":2}""")
            server.enqueue(MockResponse().setResponseCode(200))

            client.fetchSubscriptions(null)
            client.fetchEpisodeActions(0)
            client.postEpisodeActions(
                listOf(
                    EpisodeAction(
                        podcast = "p",
                        episode = "e",
                        guid = null,
                        action = EpisodeActionType.DOWNLOAD,
                        timestamp = "2026-07-14T09:00:00",
                    ),
                ),
            )

            val paths = (1..server.requestCount).map { server.takeRequest().pathOnly() }
            assertTrue(paths.none { it.contains("subscription_change") })
        }

    // --- response parsing ----------------------------------------------------------------------

    @Test
    fun `subscriptions response maps add, remove and timestamp`() =
        runBlocking {
            enqueueJson(
                """{"add":["https://a.example/feed.xml","https://b.example/feed.xml"],
               |"remove":["https://old.example/feed.xml"],"timestamp":1752483600}
                """.trimMargin(),
            )

            val delta = client.fetchSubscriptions(null)

            assertEquals(listOf("https://a.example/feed.xml", "https://b.example/feed.xml"), delta.add)
            assertEquals(listOf("https://old.example/feed.xml"), delta.remove)
            assertEquals(1_752_483_600L, delta.timestamp)
        }

    @Test
    fun `unknown response fields are ignored -- opodsync sends update_urls`() =
        runBlocking {
            enqueueJson("""{"add":[],"remove":[],"timestamp":5,"update_urls":[]}""")

            assertEquals(5L, client.fetchSubscriptions(null).timestamp)
        }

    @Test
    fun `uppercase action types from nextcloud-gpodder are parsed`() =
        runBlocking {
            enqueueJson(
                """{"actions":[{"podcast":"p","episode":"e","guid":"g","action":"DOWNLOAD",
               |"timestamp":"2026-07-14T09:00:00+00:00","started":-1,"position":-1,"total":-1}],
               |"timestamp":9}
                """.trimMargin(),
            )

            val page = client.fetchEpisodeActions(0)

            assertEquals(EpisodeActionType.DOWNLOAD, page.actions.single().action)
            assertEquals(9L, page.timestamp)
        }

    @Test
    fun `lowercase action types from opodsync are parsed`() =
        runBlocking {
            enqueueJson(
                """{"actions":[{"podcast":"p","episode":"e","action":"play",
               |"timestamp":"2026-07-14T09:00:00Z"}],"timestamp":9}
                """.trimMargin(),
            )

            assertEquals(
                EpisodeActionType.PLAY,
                client
                    .fetchEpisodeActions(0)
                    .actions
                    .single()
                    .action,
            )
        }

    @Test
    fun `the -1 absent-value sentinel is normalised to null`() =
        runBlocking {
            // nextcloud-gpodder writes -1 rather than omitting started/position/total.
            enqueueJson(
                """{"actions":[{"podcast":"p","episode":"e","action":"DOWNLOAD",
               |"timestamp":"2026-07-14T09:00:00+00:00","started":-1,"position":-1,"total":-1}],
               |"timestamp":9}
                """.trimMargin(),
            )

            val action = client.fetchEpisodeActions(0).actions.single()

            assertNull(action.started)
            assertNull(action.position)
            assertNull(action.total)
        }

    @Test
    fun `real playback values survive normalisation`() =
        runBlocking {
            enqueueJson(
                """{"actions":[{"podcast":"p","episode":"e","action":"PLAY",
               |"timestamp":"2026-07-14T09:00:00+00:00","started":0,"position":1800,"total":1800}],
               |"timestamp":9}
                """.trimMargin(),
            )

            val action = client.fetchEpisodeActions(0).actions.single()

            assertEquals(0, action.started)
            assertEquals(1800, action.position)
            assertEquals(1800, action.total)
        }

    @Test
    fun `a missing guid stays null rather than failing the page`() =
        runBlocking {
            enqueueJson(
                """{"actions":[{"podcast":"p","episode":"e","action":"PLAY",
               |"timestamp":"2026-07-14T09:00:00Z"}],"timestamp":9}
                """.trimMargin(),
            )

            assertNull(
                client
                    .fetchEpisodeActions(0)
                    .actions
                    .single()
                    .guid,
            )
        }

    @Test
    fun `an unrecognised action type is dropped without failing the whole page`() =
        runBlocking {
            enqueueJson(
                """{"actions":[
               |{"podcast":"p","episode":"e","action":"FLAG","timestamp":"2026-07-14T09:00:00Z"},
               |{"podcast":"p","episode":"e2","action":"PLAY","timestamp":"2026-07-14T09:00:00Z"}],
               |"timestamp":9}
                """.trimMargin(),
            )

            val actions = client.fetchEpisodeActions(0).actions

            assertEquals(1, actions.size)
            assertEquals("e2", actions.single().episode)
        }

    // --- failure paths -------------------------------------------------------------------------

    @Test
    fun `a 401 on post yields a failed Result carrying the status code`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(401))

            val result =
                client.postEpisodeActions(
                    listOf(
                        EpisodeAction("p", "e", null, EpisodeActionType.PLAY, "2026-07-14T09:00:00"),
                    ),
                )

            assertTrue(result.isFailure)
            assertEquals(401, (result.exceptionOrNull() as GpodderHttpException).code)
        }

    @Test
    fun `a 500 on post yields a failed Result rather than throwing`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(500))

            val result =
                client.postEpisodeActions(
                    listOf(
                        EpisodeAction("p", "e", null, EpisodeActionType.PLAY, "2026-07-14T09:00:00"),
                    ),
                )

            assertTrue(result.isFailure)
            assertEquals(500, (result.exceptionOrNull() as GpodderHttpException).code)
        }

    @Test
    fun `an empty action list still posts an empty array rather than skipping the call`() =
        runBlocking {
            // Whether to skip is SyncOrchestrator's decision (it already does); the client stays dumb.
            server.enqueue(MockResponse().setResponseCode(200))

            val result = client.postEpisodeActions(emptyList())

            assertTrue(result.isSuccess)
            assertEquals("[]", server.takeRequest().body.readUtf8())
        }

    @Test
    fun `a 401 on a GET throws, so SyncOrchestrator can classify it`() {
        server.enqueue(MockResponse().setResponseCode(401))

        val thrown = runCatching { runBlocking { client.fetchSubscriptions(null) } }.exceptionOrNull()

        assertTrue("expected an IOException-family failure, got $thrown", thrown is Exception)
    }

    @Test
    fun `a malformed response body throws rather than yielding silently empty data`() {
        enqueueJson("this is not json at all")

        val thrown = runCatching { runBlocking { client.fetchSubscriptions(null) } }.exceptionOrNull()

        assertTrue("expected a parse failure, got $thrown", thrown != null)
    }

    @Test
    fun `a read timeout surfaces as a SocketTimeoutException`() {
        val impatient = newClient(readTimeoutMillis = 250)
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"add":[],"remove":[],"timestamp":0}""")
                .setBodyDelay(2, TimeUnit.SECONDS),
        )

        val thrown = runCatching { runBlocking { impatient.fetchSubscriptions(null) } }.exceptionOrNull()

        assertTrue("expected SocketTimeoutException, got $thrown", thrown is SocketTimeoutException)
    }
}
