// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.model.port

import kotlinx.coroutines.flow.Flow
import net.drehtuer.podsilo.core.model.Episode
import net.drehtuer.podsilo.core.model.EpisodeLedgerRow

/**
 * Port for the ledger — "the one table that must never be lost" (CLAUDE.md §5). Implemented in
 * `:core:database` (Room). Doubles as the sync outbox: [getUnsynced] is the drain query,
 * [markSynced] is the only way `syncedToServer` flips to `true`.
 */
interface EpisodeLedgerRepository {
    /**
     * Ledger rows whose [EpisodeLedgerRow.state] matches [filter]. Note [LedgerFilterState.NEW]
     * has **no** corresponding row by definition (CLAUDE.md §9 — "new" is the *absence* of a row),
     * so this method returns an empty list for `NEW`; the episode-backed New/backlog listing the UI
     * actually renders is [observeEpisodes], which joins against [EpisodeRepository] and
     * `Feed.firstSeenAt`. [feedUrl] narrows to a single feed when set.
     */
    fun observe(filter: LedgerFilter): Flow<List<EpisodeLedgerRow>>

    /**
     * The UI-facing episode list: each [Episode] paired with its [EpisodeLedgerRow] if one exists
     * (`null` == genuinely new, no action anywhere). Resolving [filter] — especially
     * [LedgerFilterState.NEW] with its `pubDate >= Feed.firstSeenAt` backlog cutoff (CLAUDE.md §5)
     * — is a join the Room implementation does in SQL, which is why it lives here and not in
     * [EpisodeRepository]. This is the one method that can express "new" (a row-typed query can't).
     */
    fun observeEpisodes(filter: LedgerFilter): Flow<List<EpisodeListItem>>

    /**
     * The single durable "has this already been handled?" lookup (CLAUDE.md §11). `DownloadWorker`
     * reads it to reuse `writtenFileName` on a retry and to bail out when a remote action reached a
     * terminal state while the download was queued — never a file-existence check.
     */
    suspend fun get(episodeKey: String): EpisodeLedgerRow?

    suspend fun upsert(row: EpisodeLedgerRow)

    /** Rows where `syncedToServer = false` — the outbox drain query. */
    suspend fun getUnsynced(): List<EpisodeLedgerRow>

    suspend fun markSynced(episodeKeys: List<String>)
}

/**
 * One row of the triage list: the parsed [episode] plus its [ledger] state, or `null` [ledger]
 * when the episode has no action anywhere and is therefore "new" (CLAUDE.md §9).
 */
data class EpisodeListItem(
    val episode: Episode,
    val ledger: EpisodeLedgerRow?,
)

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
