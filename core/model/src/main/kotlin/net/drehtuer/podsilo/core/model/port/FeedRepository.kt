// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.model.port

import kotlinx.coroutines.flow.Flow
import net.drehtuer.podsilo.core.model.Feed

/**
 * Port for the local mirror of the server's subscription list. Implemented in `:core:database`
 * (Room), consumed by `:core:sync`'s `SyncOrchestrator` and `:feature:episodes`'s view models —
 * see `docs/architecture.adoc` §2 for why this interface lives in Android-free `:core:model` rather
 * than the Room module.
 */
interface FeedRepository {
    fun observeAll(): Flow<List<Feed>>

    /**
     * One-shot snapshot for `FeedRefreshWorker`, which iterates the current subscription list once
     * per run rather than reacting to it — a [Flow] would leave the worker subscribed to a stream
     * it has no use for after the first emission.
     */
    suspend fun getAll(): List<Feed>

    /** The naming templates need the feed's title/image for a download; `null` if unsubscribed meanwhile. */
    suspend fun get(url: String): Feed?

    /**
     * Persists what a **successful** (200, not 304) feed fetch learned. Deliberately not part of
     * [replaceAll] — that mirrors the server's subscription list, this records a feed *fetch*, and
     * the two happen in different workers at different times. Never touches `firstSeenAt`, which is
     * write-once (it drives the backlog cutoff, CLAUDE.md §5).
     */
    suspend fun updateRefreshMetadata(
        feedUrl: String,
        metadata: FeedRefreshMetadata,
    )

    /**
     * Wholesale-replaces the local table with [feeds] (`add - remove` computed by the caller — a
     * follower doesn't need to know what changed, only what currently is, CLAUDE.md §5). Feeds
     * absent from [feeds] but present locally are removed; their cached `Episode` rows are deleted
     * but their `EpisodeLedgerRow`s are kept.
     */
    suspend fun replaceAll(feeds: List<Feed>)
}

/**
 * What one successful feed fetch learned about a [Feed]. Grouped into a type rather than passed as
 * five parameters because they are only ever written together, by one caller.
 *
 * @property title from the RSS `<channel><title>` — the GPodder API carries no feed titles, so a
 *   fetch is the only place one ever comes from.
 * @property httpEtag validator for the next conditional `GET`.
 * @property httpLastModified the other validator for the next conditional `GET`.
 * @property refreshedAt local clock, set on a 200 only — a 304 means nothing was refreshed.
 */
data class FeedRefreshMetadata(
    val title: String,
    val imageUrl: String?,
    val httpEtag: String?,
    val httpLastModified: String?,
    val refreshedAt: Long,
)
