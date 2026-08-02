// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.gpodder

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.drehtuer.podsilo.core.model.port.NextcloudCredentials
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

private const val CALLER_THREAD = "fake-main"

/**
 * The login client must do its blocking I/O **off the calling thread**.
 *
 * This exists because of a crash every other test in the project was structurally unable to see:
 * `OkHttpClient.execute()` blocks, `ConnectViewModel` calls these `suspend` functions from
 * `viewModelScope.launch` — which is `Dispatchers.Main.immediate` — and there was no `withContext`.
 * On a JVM that is merely impolite. On Android, StrictMode kills the process with
 * `NetworkOnMainThreadException`, which is exactly what happened the first time S5 was tapped on a
 * device.
 *
 * A JVM test cannot reproduce StrictMode, so it asserts the property StrictMode exists to enforce:
 * **the HTTP call does not run on the thread that called the suspend function** (CLAUDE.md §8 — no
 * blocking calls on the main dispatcher, and inject the dispatcher so this is testable at all).
 */
class LoginFlowDispatcherTest {
    private lateinit var server: MockWebServer

    /** Stands in for `Dispatchers.Main`: single-threaded, and identifiable by name. */
    private val callerExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, CALLER_THREAD) }
    private val callerDispatcher = callerExecutor.asCoroutineDispatcher()

    /** Records the thread OkHttp actually executed on — the one thing under test. */
    private val executedOn = AtomicReference<String>()
    private val threadRecorder =
        Interceptor { chain ->
            executedOn.set(Thread.currentThread().name)
            chain.proceed(chain.request())
        }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        callerExecutor.shutdownNow()
    }

    private fun client() =
        RetrofitNextcloudLoginFlowClient(
            httpClient = OkHttpClient.Builder().addInterceptor(threadRecorder).build(),
        )

    @Test
    fun `start does its blocking work off the calling thread`() {
        server.enqueue(
            MockResponse().setBody(
                """{"poll":{"token":"tok","endpoint":"${server.url("/poll")}"},"login":"https://example.org/flow"}""",
            ),
        )
        val callerSeen = AtomicReference<String>()

        runBlocking {
            withContext(callerDispatcher) {
                callerSeen.set(Thread.currentThread().name)
                client().start(server.url("/").toString())
            }
        }

        // Prefix, not equality: the coroutines debug agent appends " @coroutine#N" to thread names.
        assertTrue("the test did not run on the fake main thread", callerSeen.get().startsWith(CALLER_THREAD))
        assertNotNull("OkHttp never ran", executedOn.get())
        assertFalse(
            "blocking HTTP ran on the calling thread — on Android that is NetworkOnMainThreadException",
            executedOn.get().startsWith(CALLER_THREAD),
        )
    }

    @Test
    fun `verifyGpodderSync does its blocking work off the calling thread too`() {
        // The same rule at a different entry point: S5 calls this one the moment the poll returns.
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        val callerSeen = AtomicReference<String>()

        runBlocking {
            withContext(callerDispatcher) {
                callerSeen.set(Thread.currentThread().name)
                client().verifyGpodderSync(
                    NextcloudCredentials(
                        serverUrl = server.url("/").toString(),
                        username = "author",
                        appPassword = "app-password",
                    ),
                )
            }
        }

        assertTrue(callerSeen.get().startsWith(CALLER_THREAD))
        assertFalse(executedOn.get().startsWith(CALLER_THREAD))
    }
}
