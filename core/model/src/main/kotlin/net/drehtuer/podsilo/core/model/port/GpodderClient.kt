// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.model.port

/**
 * Port for the three GPodder endpoints Podsilo actually calls. Implemented in `:core:gpodder`
 * (Retrofit/OkHttp). `subscription_change/create` — the fourth endpoint the API exposes — is
 * permanently out of scope and has deliberately no method here (CLAUDE.md §1/§5): the app is a
 * read-only follower of the server's subscription list.
 *
 * **Every method returns a [Result], and every failure inside it is a [GpodderException].** Nothing
 * here throws its transport library's own exception type: the two `GET`s used to let Retrofit's
 * `HttpException` propagate, which is not an `IOException`, so an expired app password reached
 * `SyncOrchestrator` in the same shape as a bug and was logged as a plain `SYNC` failure — the one
 * category S8's filter chips exist to separate. Recovering that from a message string works until a
 * server rewords it, so the *kind* of failure is part of this contract (CLAUDE.md §8: expected
 * failures are return types).
 */
interface GpodderClient {
    /** `since` is Unix **seconds**; `null` requests the full current list (CLAUDE.md §5). */
    suspend fun fetchSubscriptions(since: Long? = null): Result<SubscriptionDelta>

    suspend fun postEpisodeActions(actions: List<EpisodeAction>): Result<Unit>

    /** `since` is Unix **seconds** — not the same format as [EpisodeAction.timestamp]. */
    suspend fun fetchEpisodeActions(since: Long): Result<EpisodeActionPage>
}

/**
 * Why a GPodder request failed, in the vocabulary the sync pass classifies against — modelled on
 * [LoginFlowFailure], which answers the same question for the login endpoints.
 *
 * Two callers read this and they read it for different reasons, which is why the distinctions are
 * the ones below and not finer:
 *
 * - **`SyncOrchestrator` decides whether to retry.** [retryable] carries that, so the decision is
 *   made once here rather than re-derived from a status code at each call site.
 * - **S8 decides which chip an entry files under.** [UNAUTHORIZED] is the only value that is an
 *   `AUTH` failure rather than a `SYNC` one, and separating it is the entire reason this type
 *   exists.
 *
 * @property retryable Whether asking again, unchanged, could plausibly succeed. A wrong app
 *   password will still be wrong on the next attempt; a timeout may not be.
 */
enum class GpodderFailure(
    val retryable: Boolean,
) {
    /**
     * 401 or 403 — nearly always an app password the user has revoked in Nextcloud's *Security*
     * settings, or one that never worked. Not retryable, and the only failure here the user can
     * actually act on: it is fixed by signing in again (S5), not by waiting.
     */
    UNAUTHORIZED(retryable = false),

    /** 5xx. The server is there and broken rather than absent, so a later pass is worth making. */
    SERVER_ERROR(retryable = true),

    /**
     * A non-2xx that is neither of the above — a 404 from a Nextcloud without gpoddersync, a 413
     * from a proxy refusing a large push, a 400. Retrying an unchanged request that the server has
     * already refused just re-refuses it.
     */
    REJECTED(retryable = false),

    /** DNS failure, connection refused, no route, TLS — the phone could not get a reply at all. */
    UNREACHABLE(retryable = true),

    /**
     * The request went out and nothing came back in time. Kept apart from [UNREACHABLE] because
     * Nextcloud's bruteforce protection delays repeated requests from one address, so a *correct*
     * server answering slowly is a normal case and not a wrong address.
     */
    TIMED_OUT(retryable = true),

    /** A 2xx whose body could not be parsed. Asking again gets the same unreadable answer. */
    MALFORMED(retryable = false),
}

/**
 * Carries a [GpodderFailure] out through `Result.failure`. **Never contains a credential** — the
 * message is a status line or a transport error, never a request header or a URL carrying one.
 *
 * @property statusCode the HTTP status when there was one; `null` for a failure that never got a
 *   response (an unreachable host, a timeout).
 */
class GpodderException(
    val failure: GpodderFailure,
    message: String,
    val statusCode: Int? = null,
) : Exception(message)

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
