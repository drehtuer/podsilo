// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.gpodder

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.drehtuer.podsilo.core.model.port.LoginFlow
import net.drehtuer.podsilo.core.model.port.LoginFlowException
import net.drehtuer.podsilo.core.model.port.LoginFlowFailure
import net.drehtuer.podsilo.core.model.port.LoginResult
import net.drehtuer.podsilo.core.model.port.NextcloudCredentials
import net.drehtuer.podsilo.core.model.port.NextcloudLoginFlowClient
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val LOGIN_FLOW_PATH = "index.php/login/v2"
private const val HTTP_NOT_FOUND = 404
private const val HTTP_OK = 200

private val loginJson = Json { ignoreUnknownKeys = true }

/**
 * Nextcloud **Login Flow v2** (`docs/UI.md` §8). The only way this app ever obtains credentials:
 * the user authenticates in a browser against their own server, and what comes back is an **app
 * password**, never their account password (CLAUDE.md §5).
 *
 * Stays in the JVM module (`docs/decisions/0007`) — nothing here touches an Android API. Opening
 * the browser is the UI's job, delivered as a one-shot effect; this client only starts the flow,
 * polls it, and verifies the result.
 *
 * @property pollInterval how long to wait between polls. Injected so the tests do not sleep
 *   (CLAUDE.md §7 forbids `Thread.sleep` for synchronisation).
 */
class RetrofitNextcloudLoginFlowClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val pollInterval: Duration = 3.seconds,
    private val maxPollAttempts: Int = MAX_POLL_ATTEMPTS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : NextcloudLoginFlowClient {
    override suspend fun start(baseUrl: String): Result<LoginFlow> =
        runCatchingRequest(ioDispatcher) {
            val root =
                baseUrl.normalisedRoot()
                    ?: throw LoginFlowException(LoginFlowFailure.NOT_NEXTCLOUD, "'$baseUrl' is not a usable address")

            val request =
                Request
                    .Builder()
                    .url(root.newBuilder().addPathSegments(LOGIN_FLOW_PATH).build())
                    // Nextcloud requires a POST with no body here; an empty one keeps OkHttp happy.
                    .post(ByteArray(0).toRequestBody())
                    .header("User-Agent", USER_AGENT)
                    .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    // A Nextcloud always answers this path. Anything else — a 404, an HTML error
                    // page from a reverse proxy, someone's blog — means the address is wrong, which
                    // is a different message to the user than a refused authorization.
                    throw LoginFlowException(LoginFlowFailure.NOT_NEXTCLOUD, "HTTP ${response.code}")
                }
                val body: String = response.body.string()
                val dto = loginJson.decodeFromString(LoginFlowStartDto.serializer(), body)
                LoginFlow(
                    loginUrl = dto.login,
                    // Both URLs come from the server rather than being rebuilt locally: a Nextcloud
                    // behind a reverse proxy can legitimately answer on a different host.
                    pollEndpoint = dto.poll.endpoint,
                    token = dto.poll.token,
                )
            }
        }

    override suspend fun poll(flow: LoginFlow): Result<LoginResult> =
        runCatchingRequest(ioDispatcher) {
            repeat(maxPollAttempts) {
                val request =
                    Request
                        .Builder()
                        .url(flow.pollEndpoint)
                        .post(FormBody.Builder().add("token", flow.token).build())
                        .header("User-Agent", USER_AGENT)
                        .build()

                httpClient.newCall(request).execute().use { response ->
                    when (response.code) {
                        // 404 is Nextcloud's "not granted yet" — the documented pending state, not
                        // an error. Treating it as one would abandon every flow on the first poll.
                        HTTP_NOT_FOUND -> Unit
                        HTTP_OK -> {
                            val body: String = response.body.string()
                            val dto = loginJson.decodeFromString(LoginPollDto.serializer(), body)
                            return@runCatchingRequest LoginResult(
                                serverUrl = dto.server,
                                loginName = dto.loginName,
                                appPassword = dto.appPassword,
                            )
                        }
                        else -> throw LoginFlowException(LoginFlowFailure.ABANDONED, "HTTP ${response.code}")
                    }
                }
                delay(pollInterval)
            }
            throw LoginFlowException(LoginFlowFailure.ABANDONED, "authorization was not completed in time")
        }

    override suspend fun verifyGpodderSync(credentials: NextcloudCredentials): Result<Unit> =
        runCatchingRequest(ioDispatcher) {
            val root =
                credentials.serverUrl.normalisedRoot()
                    ?: throw LoginFlowException(LoginFlowFailure.NOT_NEXTCLOUD, "unusable server URL")

            val request =
                Request
                    .Builder()
                    .url(root.newBuilder().addPathSegments("index.php/apps/gpoddersync/subscriptions").build())
                    .header("Authorization", Credentials.basic(credentials.username, credentials.appPassword))
                    .header("User-Agent", USER_AGENT)
                    .build()

            httpClient.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> Unit
                    // A completed login flow proves the server is a Nextcloud and the password
                    // works. It says nothing about gpoddersync being installed — and connecting
                    // without it would leave the user with an app that silently syncs nothing.
                    response.code == HTTP_NOT_FOUND ->
                        throw LoginFlowException(LoginFlowFailure.NO_GPODDERSYNC, "HTTP 404 at the gpoddersync path")
                    response.code == HTTP_UNAUTHORIZED ->
                        throw LoginFlowException(LoginFlowFailure.UNAUTHORIZED, "HTTP 401")
                    else -> throw LoginFlowException(LoginFlowFailure.NOT_NEXTCLOUD, "HTTP ${response.code}")
                }
            }
        }

    companion object {
        const val MAX_POLL_ATTEMPTS: Int = 200
        private const val HTTP_UNAUTHORIZED = 401
        private const val USER_AGENT = "Podsilo"
    }
}

/**
 * Accepts what a person actually types: a bare host, an explicit scheme, or a subdirectory install
 * — Nextcloud in a subdirectory is common enough that rejecting a path would be wrong. Returns
 * `null` for anything OkHttp cannot make a URL of.
 *
 * **A bare host defaults to `https://`; an explicitly typed scheme is honoured, not rewritten.**
 * Silently upgrading `http://192.168.1.10` to HTTPS would connect to a different endpoint than the
 * user named and fail with a confusing error, and silently *downgrading* would put an app password
 * on the wire in plaintext. Neither is ours to decide here: S5's field renders a fixed `https://`
 * prefix (`docs/UI.md` §8), so the UI is where the default is made visible, and this stays
 * consistent with `RetrofitGpodderClient`, which honours the stored URL's scheme too.
 */
internal fun String.normalisedRoot(): HttpUrl? {
    val trimmed = trim()
    if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
    val withScheme =
        if (trimmed.startsWith("http://") ||
            trimmed.startsWith("https://")
        ) {
            trimmed
        } else {
            "https://$trimmed"
        }
    return withScheme.trimEnd('/').plus("/").toHttpUrlOrNull()
}

/**
 * Runs [block] **off the calling thread**, then maps what it throws onto a [LoginFlowFailure].
 *
 * The `withContext` is not tidiness. `OkHttpClient.execute()` blocks, these are `suspend` functions,
 * and `viewModelScope.launch` runs on `Dispatchers.Main.immediate` — so without it, S5 tapping
 * *Request authorization* performs a DNS lookup on the main thread and Android's StrictMode kills the
 * app with `NetworkOnMainThreadException`. Every JVM test passed regardless, because a JVM has no
 * main-thread policy (CLAUDE.md §8: no blocking calls on the main dispatcher, inject the dispatcher).
 */
private suspend fun <T> runCatchingRequest(
    dispatcher: CoroutineDispatcher,
    block: suspend () -> T,
): Result<T> =
    withContext(dispatcher) {
        try {
            Result.success(block())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (typed: LoginFlowException) {
            Result.failure(typed)
        } catch (tls: javax.net.ssl.SSLException) {
            Result.failure(LoginFlowException(LoginFlowFailure.TLS, tls.message ?: "the certificate isn't trusted"))
        } catch (timeout: SocketTimeoutException) {
            // BEFORE the IOException branch, which it is a subclass of, and separate from it because
            // the two need different words. A timeout was once reported as "can't reach that address,
            // check the spelling" — advice that sends the user to fix a host name that was right all
            // along. Nextcloud's bruteforce protection delays repeated authorization attempts from
            // the same address, so a *correct* server answering slowly is a normal case here.
            val why = timeout.message ?: "the server did not answer"
            Result.failure(LoginFlowException(LoginFlowFailure.TIMED_OUT, why))
        } catch (io: IOException) {
            // DNS failure, connection refused, no route — genuinely "can't reach that address".
            Result.failure(LoginFlowException(LoginFlowFailure.UNREACHABLE, io.message ?: "could not reach the server"))
        }
    }

@Serializable
private data class LoginFlowStartDto(
    val poll: LoginFlowPollDto,
    val login: String,
)

@Serializable
private data class LoginFlowPollDto(
    val token: String,
    val endpoint: String,
)

@Serializable
private data class LoginPollDto(
    val server: String,
    @SerialName("loginName") val loginName: String,
    @SerialName("appPassword") val appPassword: String,
)
