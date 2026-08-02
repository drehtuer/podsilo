// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.gpodder

import kotlinx.coroutines.test.runTest
import net.drehtuer.podsilo.core.model.port.LoginFlow
import net.drehtuer.podsilo.core.model.port.LoginFlowException
import net.drehtuer.podsilo.core.model.port.LoginFlowFailure
import net.drehtuer.podsilo.core.model.port.NextcloudCredentials
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * Login Flow v2 against MockWebServer. The distinctions under test are the ones S5 renders as
 * different sentences (`docs/UI.md` §8) — "check the spelling" and "this Nextcloud has no GPodder
 * Sync app" are different problems, and a single "login failed" would hide both.
 */
class RetrofitNextcloudLoginFlowClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun client() =
        RetrofitNextcloudLoginFlowClient(
            // No sleeping in tests (CLAUDE.md §7): the poll interval is injected, not wall-clock.
            pollInterval = 1.milliseconds,
            maxPollAttempts = 5,
        )

    private fun baseUrl(): String = server.url("/").toString().removeSuffix("/")

    @Test
    fun `start posts to the login v2 endpoint and returns the server's own URLs`() =
        runTest {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {"poll":{"token":"tok-123","endpoint":"https://cloud.example.org/index.php/login/v2/poll"},
                     "login":"https://cloud.example.org/index.php/login/v2/flow/tok-123"}
                    """.trimIndent(),
                ),
            )

            val flow = client().start(baseUrl()).getOrThrow()

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/index.php/login/v2", request.path)
            assertEquals("tok-123", flow.token)
            // Taken verbatim from the response: a Nextcloud behind a reverse proxy can legitimately
            // answer on a different host than the one the user typed.
            assertEquals("https://cloud.example.org/index.php/login/v2/poll", flow.pollEndpoint)
            assertEquals("https://cloud.example.org/index.php/login/v2/flow/tok-123", flow.loginUrl)
        }

    @Test
    fun `a server that is not a Nextcloud is reported as such, not as a network problem`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(404).setBody("<html>Not found</html>"))

            val failure = client().start(baseUrl()).exceptionOrNull() as LoginFlowException

            assertEquals(LoginFlowFailure.NOT_NEXTCLOUD, failure.failure)
        }

    @Test
    fun `an unreachable address is a network problem, not a wrong-server problem`() =
        runTest {
            server.shutdown()

            val failure = client().start(baseUrl()).exceptionOrNull() as LoginFlowException

            assertEquals(LoginFlowFailure.UNREACHABLE, failure.failure)
        }

    @Test
    fun `polling keeps waiting through 404s and returns the app password once granted`() =
        runTest {
            // 404 is Nextcloud's documented "not granted yet". Treating it as an error would abandon
            // every flow on the first poll, before the user had a chance to press Grant access.
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"server":"https://cloud.example.org","loginName":"drehtuer","appPassword":"app-pw-xyz"}""",
                ),
            )

            val result = client().poll(flowAt("/poll")).getOrThrow()

            assertEquals(3, server.requestCount)
            assertEquals("drehtuer", result.loginName)
            assertEquals("app-pw-xyz", result.appPassword)
            assertEquals("https://cloud.example.org", result.serverUrl)
            assertEquals("app-pw-xyz", result.credentials.appPassword)
        }

    @Test
    fun `the poll token is sent as a form field`() =
        runTest {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"server":"https://cloud.example.org","loginName":"u","appPassword":"p"}""",
                ),
            )

            client().poll(flowAt("/poll")).getOrThrow()

            assertEquals("token=tok-123", server.takeRequest().body.readUtf8())
        }

    @Test
    fun `a flow never granted is abandoned rather than polled forever`() =
        runTest {
            repeat(5) { server.enqueue(MockResponse().setResponseCode(404)) }

            val failure = client().poll(flowAt("/poll")).exceptionOrNull() as LoginFlowException

            assertEquals(LoginFlowFailure.ABANDONED, failure.failure)
        }

    @Test
    fun `verification only succeeds on a 200 from the gpoddersync path`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"add":[],"remove":[],"timestamp":1}"""))

            val result = client().verifyGpodderSync(credentials())

            assertTrue(result.isSuccess)
            val request = server.takeRequest()
            assertEquals("/index.php/apps/gpoddersync/subscriptions", request.path)
            assertTrue(request.getHeader("Authorization").orEmpty().startsWith("Basic "))
        }

    @Test
    fun `a Nextcloud without the gpoddersync app is refused, not accepted`() =
        runTest {
            // The reason verification exists at all: a completed login flow proves the password
            // works, not that the app is installed. Storing credentials here would leave the user
            // with something that looks connected and silently syncs nothing.
            server.enqueue(MockResponse().setResponseCode(404))

            val failure = client().verifyGpodderSync(credentials()).exceptionOrNull() as LoginFlowException

            assertEquals(LoginFlowFailure.NO_GPODDERSYNC, failure.failure)
        }

    @Test
    fun `a 401 during verification is an authorization problem`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(401))

            val failure = client().verifyGpodderSync(credentials()).exceptionOrNull() as LoginFlowException

            assertEquals(LoginFlowFailure.UNAUTHORIZED, failure.failure)
        }

    @Test
    fun `no failure message ever carries the app password`() =
        runTest {
            // CLAUDE.md §5: these messages reach the error log and the screen. A stray credential in
            // an exception message is the classic way one escapes.
            server.enqueue(MockResponse().setResponseCode(401))

            val failure = client().verifyGpodderSync(credentials()).exceptionOrNull() as LoginFlowException

            assertFalse(failure.message.orEmpty().contains("app-pw-xyz"))
            assertFalse(failure.message.orEmpty().contains("Basic "))
        }

    @Test
    fun `a bare host defaults to https, and a subdirectory install survives`() {
        assertEquals("https://cloud.example.org/", "cloud.example.org".normalisedRoot().toString())
        assertEquals("https://cloud.example.org/", "https://cloud.example.org".normalisedRoot().toString())
        assertEquals("https://cloud.example.org/", "  cloud.example.org/  ".normalisedRoot().toString())
        // Nextcloud in a subdirectory is common enough that rejecting a path would be wrong.
        assertEquals("https://example.org/nextcloud/", "https://example.org/nextcloud".normalisedRoot().toString())
    }

    @Test
    fun `an explicitly typed scheme is honoured rather than rewritten`() {
        // Upgrading this to https would connect to an endpoint the user did not name and fail
        // confusingly; the https default belongs in S5's field prefix, not in a silent rewrite here.
        assertEquals("http://192.168.1.10/", "http://192.168.1.10".normalisedRoot().toString())
    }

    @Test
    fun `an address that cannot be a URL is rejected before any request`() {
        assertNull("".normalisedRoot())
        assertNull("   ".normalisedRoot())
        assertNull("not a host".normalisedRoot())
    }

    private fun flowAt(path: String) =
        LoginFlow(
            loginUrl = server.url("/flow").toString(),
            pollEndpoint = server.url(path).toString(),
            token = "tok-123",
        )

    private fun credentials() =
        NextcloudCredentials(
            serverUrl = baseUrl(),
            username = "drehtuer",
            appPassword = "app-pw-xyz",
        )
}
