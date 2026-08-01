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

    /**
     * Writes many rows in **one transaction and one [Flow] emission**. Bulk triage (`docs/UI.md`
     * §5's *Download all* and selection mode, §7's *mark old/all as played*) routinely touches
     * hundreds of episodes; doing that as N calls to [upsert] is N transactions and N list
     * re-emissions into a `LazyColumn`.
     */
    suspend fun upsertAll(rows: List<EpisodeLedgerRow>)

    /**
     * Per-feed counts of episodes that would be affected by a bulk operation, for the confirmation
     * dialog — feed URL to count, largest first.
     *
     * This is the safeguard, not a nicety. A bulk *mark as played* emits `PLAY` actions to the
     * shared log and **cannot be undone in bulk** (`docs/decisions/0013`); naming the exact count
     * and the feeds before anything is written is what replaced the old rule against writing
     * backlog rows at all.
     */
    suspend fun previewUndecided(scope: BulkScope): List<FeedUndecidedCount>

    /**
     * The episodes a bulk operation would actually touch — same predicate as [previewUndecided], so
     * the count a confirmation dialog promised is the set that gets written.
     *
     * Returns [Episode]s rather than keys because the caller has to build ledger rows from them, and
     * a row needs the feed URL, enclosure URL and duration snapshotted at write time
     * (`docs/decisions/0001`). It deliberately does not write anything itself: *what* state to write
     * is the caller's decision — `SKIPPED` for the mark-old rule, `QUEUED` for *Download all*.
     */
    suspend fun undecided(scope: BulkScope): List<Episode>
}

/**
 * What a bulk operation applies to. Both select only episodes with **no ledger row** — an already
 * decided episode is never swept up by a bulk action, which is why *Download all* cannot re-fetch
 * something the user already marked as played.
 *
 * @property olderThanMillis For [BulkScopeKind.OLDER_THAN], the epoch-millis cutoff; episodes with
 *   an unknown `pubDate` are **excluded** rather than guessed at (an episode with no date is not
 *   evidence of being old).
 * @property feedUrl Narrows to one feed — *Download all* is deliberately per-podcast and there is
 *   no global variant (`docs/decisions/0014`).
 */
data class BulkScope(
    val kind: BulkScopeKind,
    val olderThanMillis: Long? = null,
    val feedUrl: String? = null,
)

enum class BulkScopeKind { OLDER_THAN, ALL_UNDECIDED }

/** One line of the bulk-confirmation dialog: how many undecided episodes this feed contributes. */
data class FeedUndecidedCount(
    val feedUrl: String,
    val count: Int,
)

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
 * The triage list filter. "New" is exactly "no ledger row" — there is no date clause and no
 * backlog flag: the read-time `pubDate >= Feed.firstSeenAt` cutoff was retired by
 * `docs/decisions/0013` in favour of writing `SKIPPED` rows, which is visible in the UI,
 * reversible per episode, and shared with the author's other clients.
 */
data class LedgerFilter(
    val state: LedgerFilterState = LedgerFilterState.NEW,
    val feedUrl: String? = null,
)
