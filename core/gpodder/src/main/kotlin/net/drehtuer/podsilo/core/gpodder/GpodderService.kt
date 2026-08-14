// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.gpodder

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * The three GPodder endpoints Podsilo calls. `subscription_change/create` is deliberately absent
 * and must stay that way -- CLAUDE.md section 1: the app is a read-only follower of the server's
 * subscription list, and the cheapest way to guarantee that is to make the call impossible to
 * write rather than to rely on review catching it.
 *
 * Paths are relative to a base URL of `<nextcloud-root>/index.php/apps/gpoddersync/`.
 */
internal interface GpodderService {
    /**
     * [since] is Unix **seconds**; omitted entirely when `null`, which requests the full list.
     *
     * `Response<...>` rather than the bare DTO, here and below, so a non-2xx is a *value* this
     * module classifies into a [net.drehtuer.podsilo.core.model.port.GpodderFailure] instead of
     * Retrofit throwing its own `HttpException` — which is not an `IOException`, and so reached the
     * sync pass looking exactly like a bug (see [RetrofitGpodderClient]).
     */
    @GET("subscriptions")
    suspend fun subscriptions(
        @Query("since") since: Long? = null,
    ): Response<SubscriptionsResponseDto>

    @GET("episode_action")
    suspend fun episodeActions(
        @Query("since") since: Long,
    ): Response<EpisodeActionPageDto>

    /**
     * The body is a **bare JSON array**, not an envelope object -- `nextcloud-gpodder`'s controller
     * reads it via `filterEpisodesFromRequestParams(...)`, keeping only numeric-keyed params, which
     * is what a top-level JSON array decodes to in Nextcloud's request handling.
     *
     * Returns `Response<Unit>` rather than a parsed body: the two servers disagree on the response
     * shape (`{"timestamp": N}` vs. an extra `update_urls` field) and Podsilo needs nothing from
     * it beyond the status code.
     */
    @POST("episode_action/create")
    suspend fun createEpisodeActions(
        @Body actions: List<EpisodeActionDto>,
    ): Response<Unit>
}
