// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.model.port

import kotlinx.coroutines.flow.Flow
import net.drehtuer.podsilo.core.model.Feed

/**
 * Port for the local mirror of the server's subscription list. Implemented in `:core:database`
 * (Room), consumed by `:core:sync`'s `SyncOrchestrator` and `:feature:episodes`'s view models —
 * see `docs/architecture.md` §2 for why this interface lives in Android-free `:core:model` rather
 * than the Room module.
 */
interface FeedRepository {
    fun observeAll(): Flow<List<Feed>>

    /**
     * Wholesale-replaces the local table with [feeds] (`add - remove` computed by the caller — a
     * follower doesn't need to know what changed, only what currently is, CLAUDE.md §5). Feeds
     * absent from [feeds] but present locally are removed; their cached `Episode` rows are deleted
     * but their `EpisodeLedgerRow`s are kept.
     */
    suspend fun replaceAll(feeds: List<Feed>)
}
