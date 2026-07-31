// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.gpodder

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import net.drehtuer.podsilo.core.model.port.EpisodeAction
import net.drehtuer.podsilo.core.model.port.EpisodeActionPage
import net.drehtuer.podsilo.core.model.port.GpodderClient
import net.drehtuer.podsilo.core.model.port.SubscriptionDelta
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException

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
 * (honest, and correct against `opodsync`/older servers); see `docs/decisions/0008`.
 */
class RetrofitGpodderClient internal constructor(
    private val service: GpodderService,
) : GpodderClient {
    override suspend fun fetchSubscriptions(since: Long?): SubscriptionDelta = service.subscriptions(since).toDomain()

    override suspend fun fetchEpisodeActions(since: Long): EpisodeActionPage = service.episodeActions(since).toDomain()

    override suspend fun postEpisodeActions(actions: List<EpisodeAction>): Result<Unit> =
        try {
            val response = service.createEpisodeActions(actions.map { it.toDto() })
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(GpodderHttpException(response.code(), response.message()))
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (network: IOException) {
            // Only the POST returns Result<...> per the port; the two GETs let exceptions propagate to
            // SyncOrchestrator, which already classifies IOException as retryable.
            Result.failure(network)
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

/** Thrown for a non-2xx response; carries the status code so callers can distinguish auth failures. */
class GpodderHttpException(
    val code: Int,
    val statusMessage: String,
) : IOException("GPodder request failed: HTTP $code $statusMessage")

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
