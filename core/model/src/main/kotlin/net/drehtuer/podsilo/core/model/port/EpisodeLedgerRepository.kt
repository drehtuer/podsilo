// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.model.port

import kotlinx.coroutines.flow.Flow
import net.drehtuer.podsilo.core.model.EpisodeLedgerRow

/**
 * Port for the ledger — "the one table that must never be lost" (CLAUDE.md §5). Implemented in
 * `:core:database` (Room). Doubles as the sync outbox: [getUnsynced] is the drain query,
 * [markSynced] is the only way `syncedToServer` flips to `true`.
 */
interface EpisodeLedgerRepository {
    /**
     * [filter]'s `NEW` state has no corresponding [EpisodeLedgerRow] by definition (CLAUDE.md
     * §9 — "new" is the absence of a row) — resolving it requires joining against
     * [EpisodeRepository] and `Feed.firstSeenAt`. That join is the implementation's job (it needs
     * the Room DAOs to do it in one query); this port only describes what the UI asks for.
     */
    fun observe(filter: LedgerFilter): Flow<List<EpisodeLedgerRow>>

    suspend fun upsert(row: EpisodeLedgerRow)

    /** Rows where `syncedToServer = false` — the outbox drain query. */
    suspend fun getUnsynced(): List<EpisodeLedgerRow>

    suspend fun markSynced(episodeKeys: List<String>)
}

/** The states a [LedgerFilter] can select on — distinct from [net.drehtuer.podsilo.core.model.LedgerState]. */
enum class LedgerFilterState { NEW, DOWNLOADED, SKIPPED, ALL }

/**
 * @property includeBacklog Lifts the `pubDate >= Feed.firstSeenAt` restriction that otherwise
 *   applies to [LedgerFilterState.NEW] (CLAUDE.md §5's "backlog is a UI problem, not a download
 *   problem" — the default view must stay short even when a feed exposes years of back catalogue).
 */
data class LedgerFilter(
    val state: LedgerFilterState = LedgerFilterState.NEW,
    val feedUrl: String? = null,
    val includeBacklog: Boolean = false,
)
