// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.model.port

/**
 * Port for the three GPodder endpoints Podsilo actually calls. Implemented in `:core:gpodder`
 * (Retrofit/OkHttp). `subscription_change/create` — the fourth endpoint the API exposes — is
 * permanently out of scope and has deliberately no method here (CLAUDE.md §1/§5): the app is a
 * read-only follower of the server's subscription list.
 */
interface GpodderClient {
    /** `since` is Unix **seconds**; `null` requests the full current list (CLAUDE.md §5). */
    suspend fun fetchSubscriptions(since: Long? = null): SubscriptionDelta

    suspend fun postEpisodeActions(actions: List<EpisodeAction>): Result<Unit>

    /** `since` is Unix **seconds** — not the same format as [EpisodeAction.timestamp]. */
    suspend fun fetchEpisodeActions(since: Long): EpisodeActionPage
}

/** @property timestamp Unix seconds, verbatim from the server — persist and echo back as the next `since`. */
data class SubscriptionDelta(
    val add: List<String>,
    val remove: List<String>,
    val timestamp: Long,
)

/** The four action types the GPodder episode-action log recognises. */
enum class EpisodeActionType { DOWNLOAD, PLAY, DELETE, NEW }

/**
 * One entry in the GPodder episode-action log — either outbound (built from an
 * [net.drehtuer.podsilo.core.model.EpisodeLedgerRow]) or inbound (received from
 * [GpodderClient.fetchEpisodeActions]).
 *
 * @property podcast Feed URL (`Feed.url` / `EpisodeLedgerRow.feedUrl`).
 * @property episode Enclosure URL (`Episode.enclosureUrl` / `EpisodeLedgerRow.enclosureUrl`).
 * @property timestamp **ISO-8601, no timezone offset** (e.g. `2026-07-14T09:00:00`) — a different
 *   format from the Unix-seconds `since`/response `timestamp` used elsewhere in this API. Getting
 *   the two confused doesn't crash anything, it silently breaks incremental sync (CLAUDE.md §11).
 * @property started `PLAY` only: playback start position, in seconds.
 * @property position `PLAY` only: playback stop position, in seconds. `position == total` encodes
 *   "fully played" — see `docs/decisions/` for Podsilo's skip-as-`PLAY` convention.
 * @property total `PLAY` only: episode duration, in seconds.
 */
data class EpisodeAction(
    val podcast: String,
    val episode: String,
    val guid: String?,
    val action: EpisodeActionType,
    val timestamp: String,
    val started: Int? = null,
    val position: Int? = null,
    val total: Int? = null,
)

/** @property timestamp Unix seconds, verbatim from the server — persist and echo back as the next `since`. */
data class EpisodeActionPage(
    val actions: List<EpisodeAction>,
    val timestamp: Long,
)
