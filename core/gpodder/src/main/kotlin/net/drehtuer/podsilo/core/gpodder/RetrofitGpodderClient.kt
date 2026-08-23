// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.gpodder

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import net.drehtuer.podsilo.core.model.port.EpisodeAction
import net.drehtuer.podsilo.core.model.port.EpisodeActionPage
import net.drehtuer.podsilo.core.model.port.GpodderClient
import net.drehtuer.podsilo.core.model.port.GpodderException
import net.drehtuer.podsilo.core.model.port.GpodderFailure
import net.drehtuer.podsilo.core.model.port.SubscriptionDelta
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.net.SocketTimeoutException

/** Path the gpoddersync app is mounted at, appended to the user's Nextcloud root URL. */
private const val GPODDERSYNC_PATH = "index.php/apps/gpoddersync/"

private val JSON_MEDIA_TYPE = "application/json".toMediaType()

private val json =
    Json {
        // Servers add fields we don't model (opodsync's `update_urls`), and will add more over time.
        ignoreUnknownKeys = true
        // Don't send `"guid": null` etc. for absent optional fields -- nextcloud-gpodder reads the
        // POST body with `isset()`-style checks, so an explicit null is not equivalent to omission.
        explicitNulls = false
    }

/**
 * Retrofit/OkHttp implementation of [GpodderClient] for the Nextcloud gpoddersync API.
 *
 * Authenticates with HTTP Basic per request via an interceptor rather than OkHttp's `authenticator`
 * (which only responds *after* a 401): gpoddersync always requires auth, so pre-emptive is both
 * correct and one round-trip cheaper.
 *
 * **Known server limitation, not a bug here:** `nextcloud-gpodder` >= 3.13.3 silently discards any
 * posted action whose type isn't `PLAY` and still returns 200 -- so `DOWNLOAD` actions sent by
 * [postEpisodeActions] never reach the shared log on a real Nextcloud. Podsilo emits them anyway
 * (honest, and correct against `opodsync`/older servers); see `decisions/0008`.
 *
 * **No failure leaves this class untyped.** All three methods run through [guarded], so a caller
 * only ever sees a [GpodderException] carrying a [GpodderFailure] -- never Retrofit's `HttpException`,
 * never a bare `SerializationException`. That is what lets `SyncOrchestrator` tell an expired app
 * password (`AUTH`, not worth retrying) from a server that is merely down (`SYNC`, retry).
 */
class RetrofitGpodderClient internal constructor(
    private val service: GpodderService,
) : GpodderClient {
    override suspend fun fetchSubscriptions(since: Long?): Result<SubscriptionDelta> =
        guarded { service.subscriptions(since).bodyOrFail().toDomain() }

    override suspend fun fetchEpisodeActions(since: Long): Result<EpisodeActionPage> =
        guarded { service.episodeActions(since).bodyOrFail().toDomain() }

    override suspend fun postEpisodeActions(actions: List<EpisodeAction>): Result<Unit> =
        guarded {
            val response = service.createEpisodeActions(actions.map { it.toDto() })
            // The body is deliberately not read: the two servers disagree on its shape and Podsilo
            // needs nothing from it beyond the status (`GpodderService.createEpisodeActions`).
            if (!response.isSuccessful) throw response.asFailure()
        }

    companion object {
        /**
         * @param baseUrl the user's Nextcloud root (e.g. `https://cloud.example.com`) --
         *   [GPODDERSYNC_PATH] is appended here so callers never hand-build the app path.
         */
        fun create(
            baseUrl: String,
            credentials: GpodderCredentials,
            okHttpClient: OkHttpClient = OkHttpClient(),
        ): RetrofitGpodderClient {
            val authenticated =
                okHttpClient
                    .newBuilder()
                    .addInterceptor(basicAuthInterceptor(credentials))
                    .build()

            val retrofit =
                Retrofit
                    .Builder()
                    .baseUrl(baseUrl.toGpoddersyncBaseUrl())
                    .client(authenticated)
                    .addConverterFactory(json.asConverterFactory(JSON_MEDIA_TYPE))
                    .build()

            return RetrofitGpodderClient(retrofit.create(GpodderService::class.java))
        }
    }
}

/**
 * Runs one request and maps whatever it throws onto a [GpodderFailure] — the single place in this
 * module where a transport-level failure becomes a value.
 *
 * The catch order is load-bearing: [SocketTimeoutException] is an [IOException], and
 * [kotlinx.serialization.SerializationException] is an `IllegalArgumentException` rather than
 * anything IO-shaped, so it would otherwise escape as an unclassified `RuntimeException` — which is
 * how a truncated response used to reach `SyncOrchestrator`'s non-retryable branch with no note of
 * what had actually gone wrong.
 */
private suspend inline fun <T> guarded(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (typed: GpodderException) {
        Result.failure(typed)
    } catch (timeout: SocketTimeoutException) {
        // Before the IOException branch it is a subclass of. Nextcloud's bruteforce protection
        // delays repeated requests from one address, so a correct server answering slowly is a
        // normal case here rather than an unreachable one.
        Result.failure(GpodderException(GpodderFailure.TIMED_OUT, timeout.message ?: "the server did not answer"))
    } catch (io: IOException) {
        Result.failure(GpodderException(GpodderFailure.UNREACHABLE, io.message ?: "could not reach the server"))
    } catch (malformed: SerializationException) {
        Result.failure(GpodderException(GpodderFailure.MALFORMED, malformed.message ?: "unreadable response body"))
    }

/** The parsed body of a 2xx, or a typed failure. A 2xx with no body at all is [GpodderFailure.MALFORMED]. */
private fun <T> Response<T>.bodyOrFail(): T {
    if (!isSuccessful) throw asFailure()
    return body() ?: throw GpodderException(GpodderFailure.MALFORMED, "HTTP ${code()} with an empty body", code())
}

/**
 * Maps a non-2xx onto a [GpodderFailure]. Only the status line goes into the message — never the URL
 * or a header, either of which can carry the app password (`GpodderException`).
 */
private fun Response<*>.asFailure(): GpodderException {
    val failure =
        when (code()) {
            HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> GpodderFailure.UNAUTHORIZED
            in HTTP_SERVER_ERROR_RANGE -> GpodderFailure.SERVER_ERROR
            else -> GpodderFailure.REJECTED
        }
    return GpodderException(failure, "HTTP ${code()} ${message()}".trim(), code())
}

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_SERVER_ERROR_FIRST = 500
private const val HTTP_SERVER_ERROR_LAST = 599
private val HTTP_SERVER_ERROR_RANGE = HTTP_SERVER_ERROR_FIRST..HTTP_SERVER_ERROR_LAST

private fun basicAuthInterceptor(credentials: GpodderCredentials) =
    Interceptor { chain ->
        val authorized =
            chain
                .request()
                .newBuilder()
                .header("Authorization", Credentials.basic(credentials.username, credentials.password))
                .header("Accept", "application/json")
                .build()
        chain.proceed(authorized)
    }

/** Retrofit requires a trailing slash on the base URL or it silently drops the last path segment. */
private fun String.toGpoddersyncBaseUrl(): HttpUrl {
    val root = trimEnd('/')
    return "$root/$GPODDERSYNC_PATH".toHttpUrl()
}
