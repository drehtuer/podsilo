// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.model.port

import kotlinx.coroutines.flow.Flow
import net.drehtuer.podsilo.core.model.Episode
import net.drehtuer.podsilo.core.model.EpisodeLedgerRow

/**
 * Port for the **reads a screen makes** — episodes joined to their ledger state, and the counts
 * derived from the same predicate. Implemented in `:core:database` over `EpisodeListDao`.
 *
 * Split from [EpisodeLedgerRepository] along the seam the DAOs already use: that one owns the
 * durable "already handled" record and its outbox, this one owns the joins the UI renders. Keeping
 * every query in this interface together is the point — they must share one definition of
 * "undecided", or a count badge could promise a different number than the list it opens shows
 * (`docs/UI.md` §12.5).
 */
interface EpisodeListRepository {
    /**
     * The triage list: each [Episode] paired with its [EpisodeLedgerRow] if one exists (`null` ==
     * genuinely new, no action anywhere). Resolving [filter] is a join the Room implementation does
     * in SQL, which is why it lives here and not on [EpisodeRepository]. This is the one method that
     * can express "new" — a row-typed query cannot, because new means the *absence* of a row.
     */
    fun observeEpisodes(filter: LedgerFilter): Flow<List<EpisodeListItem>>

    /**
     * S7's list: every episode whose ledger row is `QUEUED`, `DOWNLOADING` or `ERROR`, newest
     * decision first.
     *
     * A query of its own rather than a filter on [observeEpisodes], because the Activity screen's
     * cost must scale with the **queue**, not with the ledger. Observing every row and narrowing in
     * Kotlin is what made downloads appear minutes late on a device with a few thousand decided
     * episodes (issue #47): the join and the predicate both belong in SQL.
     */
    fun observeInFlight(): Flow<List<EpisodeListItem>>

    /**
     * The last [limit] **decisions**, newest first — S7's *recent actions* group (issue #90).
     *
     * The affordance this backs is recovery, not history for its own sake: *Mark as played* holds
     * its decision for five seconds and is then silent, so a mis-swipe cannot be found again. Every
     * decided state is included, because "what did I just do?" does not distinguish a wrong
     * *Download* from a wrong *Mark as played*; the in-flight states are not, because they are
     * already S7's other groups.
     *
     * Joined to the episode rather than read off the ledger alone: a row that names only an
     * enclosure URL cannot be recognised, and recognising the episode is the whole point. An episode
     * whose cached row was pruned by an unsubscribe therefore drops out of this list — its ledger
     * row survives, which is what matters (CLAUDE.md §11), but it is not something the user can act
     * on here.
     */
    fun observeRecentActions(limit: Int): Flow<List<EpisodeListItem>>

    /**
     * The last [limit] successfully delivered files, newest first, excluding anything at or before
     * [since] — S7's *recently downloaded* group and the only place the app answers "did it land?".
     *
     * [since] is a **display cursor**, not a deletion: hiding a row here must never remove the
     * ledger record that stops the episode being downloaded again (CLAUDE.md §11).
     */

    fun observeRecentlyDelivered(
        since: Long,
        limit: Int,
    ): Flow<List<EpisodeLedgerRow>>

    /**
     * How many ledger rows are still waiting to reach the server — S7's "n actions pending".
     *
     * A screen read, which is why it is here and not beside the outbox drain on
     * [EpisodeLedgerRepository]: counted in SQL, never by taking the size of the drained list.
     */
    fun observeUnsyncedCount(): Flow<Int>

    /**
     * Per-feed undecided counts, live — S1's badges.
     *
     * Counted in SQL rather than by observing the episodes and grouping them: a home screen must not
     * load several thousand rows to render a handful of integers (CLAUDE.md §5).
     */
    fun observeUndecidedCounts(): Flow<List<FeedUndecidedCount>>

    /**
     * Per-feed counts of episodes a bulk operation would affect, for the confirmation dialog — feed
     * URL to count, largest first.
     *
     * This is the safeguard, not a nicety. A bulk *mark as played* emits `PLAY` actions to the
     * shared log and **cannot be undone in bulk** (`docs/decisions/0013`); naming the exact count
     * and the feeds before anything is written is what replaced the old rule against writing backlog
     * rows at all.
     */
    suspend fun previewUndecided(scope: BulkScope): List<FeedUndecidedCount>

    /**
     * The episodes a bulk operation would actually touch — same predicate as [previewUndecided], so
     * the count a confirmation dialog promised is the set that gets written.
     *
     * Returns [Episode]s rather than keys because the caller has to build ledger rows from them, and
     * a row needs the feed URL, enclosure URL and duration snapshotted at write time
     * (`docs/architecture.md` §4). It deliberately does not write anything itself: *what* state to write
     * is the caller's decision — `SKIPPED` for the mark-old rule, `QUEUED` for *Download all*.
     */
    suspend fun undecided(scope: BulkScope): List<Episode>
}

/**
 * What a bulk operation applies to. Both kinds select only episodes with **no ledger row** — an
 * already decided episode is never swept up by a bulk action, which is why *Download all* cannot
 * re-fetch something the user already marked as played.
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
