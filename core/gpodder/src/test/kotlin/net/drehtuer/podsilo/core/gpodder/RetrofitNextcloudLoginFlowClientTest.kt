// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.gpodder

import kotlinx.coroutines.test.runTest
import net.drehtuer.podsilo.core.model.port.LoginFlow
import net.drehtuer.podsilo.core.model.port.LoginFlowException
import net.drehtuer.podsilo.core.model.port.LoginFlowFailure
import net.drehtuer.podsilo.core.model.port.NextcloudCredentials
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.toJavaDuration

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

    /**
     * The bug this separates out: a *correct* address on a *slow* server was reported as
     * "can't reach that address, check the spelling", which sends the user to fix something that was
     * never wrong. Nextcloud's bruteforce protection delays repeated authorization attempts, so this
     * is the normal shape of "I tried to log in a few times in a row".
     */
    @Test
    fun `a server that answers too slowly is a timeout, not an unreachable address`() =
        runTest {
            // NO_RESPONSE holds the connection open and never answers, which is what a throttled
            // Nextcloud looks like to the client. The socket connects fine — only the read expires.
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

            val slowClient =
                RetrofitNextcloudLoginFlowClient(
                    httpClient =
                        OkHttpClient
                            .Builder()
                            .readTimeout(250.milliseconds.toJavaDuration())
                            .build(),
                )

            val failure = slowClient.start(baseUrl()).exceptionOrNull() as LoginFlowException

            assertEquals(LoginFlowFailure.TIMED_OUT, failure.failure)
        }

    /**
     * The reported failure: browser says "access granted", app says "can't reach that address".
     *
     * A Nextcloud behind a TLS-terminating proxy without `overwriteprotocol` reports its own URLs as
     * `http`, and the client is obliged to use them. Android refuses the cleartext connection, so the
     * flow dies *after* the grant on a URL the user never typed. Following the scheme verbatim would
     * be worse than failing — `server` is persisted, so it would mean the app password in plaintext
     * on every later sync.
     */
    @Test
    fun `http URLs from an https server are upgraded rather than followed`() {
        assertEquals(
            "https://cloud.example.org/index.php/login/v2/poll",
            "http://cloud.example.org/index.php/login/v2/poll".keepingSchemeAtLeastAsSecureAs(secure = true),
        )
        // Already secure: untouched.
        assertEquals(
            "https://cloud.example.org",
            "https://cloud.example.org".keepingSchemeAtLeastAsSecureAs(secure = true),
        )
        // A deliberately typed http:// instance is the user's call, not ours to rewrite. Android
        // then refuses it, and that arrives as CLEARTEXT_BLOCKED rather than as a wrong address.
        assertEquals(
            "http://nextcloud.lan",
            "http://nextcloud.lan".keepingSchemeAtLeastAsSecureAs(secure = false),
        )
        // Never a downgrade, whatever the flag says.
        assertEquals(
            "https://cloud.example.org",
            "https://cloud.example.org".keepingSchemeAtLeastAsSecureAs(secure = false),
        )
    }

    /**
     * The upgrade *through* `poll` and `start` is not asserted here, deliberately: MockWebServer
     * serves plain http, so `secure` is false for every request in this file and the branch cannot
     * be reached. Rather than an https MockWebServer purely to re-check one boolean, the rule itself
     * is tested above and the two call sites pass it by construction.
     */
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
