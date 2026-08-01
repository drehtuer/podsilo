// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import net.drehtuer.podsilo.core.database.entity.EpisodeEntity

/**
 * The UI-facing joins across `episodes` and `episode_ledger`, split out of `EpisodeLedgerDao` —
 * that one owns the ledger table and the outbox, this one answers "what does the triage list show".
 *
 * Keeping the three list queries and [countUndecidedByFeed] in one place is deliberate: they must
 * share the *same* "no ledger row" predicate, or a count badge could disagree with the list it
 * opens, and a bulk-confirmation dialog could promise a different number than it writes
 * (`docs/UI.md` §12.5).
 *
 * All three list queries share the same `l_`-aliased projection of the ledger columns (see
 * [EpisodeWithLedger]); the three columns the two tables have in common (`episodeKey`, `feedUrl`,
 * `enclosureUrl`) must be aliased or they would collide with the embedded episode's identically
 * named columns.
 */
@Dao
interface EpisodeListDao {
    /** All episodes for the filter, each with its ledger row if present — the `ALL` tab. */
    @Transaction
    @Query(
        "SELECT e.*, " +
            "l.episodeKey AS l_episodeKey, l.feedUrl AS l_feedUrl, l.enclosureUrl AS l_enclosureUrl, " +
            "l.state AS l_state, l.actionedAt AS l_actionedAt, l.syncedToServer AS l_syncedToServer, " +
            "l.attempts AS l_attempts, l.lastError AS l_lastError, l.writtenFileName AS l_writtenFileName, " +
            "l.durationSeconds AS l_durationSeconds " +
            "FROM episodes e LEFT JOIN episode_ledger l ON e.episodeKey = l.episodeKey " +
            "WHERE (:feedUrl IS NULL OR e.feedUrl = :feedUrl) " +
            "ORDER BY e.pubDate DESC",
    )
    fun observeAllEpisodes(feedUrl: String?): Flow<List<EpisodeWithLedger>>

    /**
     * The `To decide` tab: episodes with **no** ledger row at all (CLAUDE.md §9). That is the whole
     * predicate — no date clause.
     *
     * The `pubDate >= Feed.firstSeenAt` cutoff this query used to carry is **retired**
     * (`docs/decisions/0013`), removed rather than left behind a flag: old episodes are hidden by
     * *writing* `SKIPPED` rows now, and an unused parameter is one caller away from becoming a
     * second, contradictory mechanism. `Feed.firstSeenAt` stays in the schema as the default cutoff
     * date offered for a newly-appearing feed.
     */
    @Transaction
    @Query(
        "SELECT e.*, " +
            "NULL AS l_episodeKey, NULL AS l_feedUrl, NULL AS l_enclosureUrl, " +
            "NULL AS l_state, NULL AS l_actionedAt, NULL AS l_syncedToServer, " +
            "NULL AS l_attempts, NULL AS l_lastError, NULL AS l_writtenFileName, " +
            "NULL AS l_durationSeconds " +
            "FROM episodes e " +
            "WHERE e.episodeKey NOT IN (SELECT episodeKey FROM episode_ledger) " +
            "AND (:feedUrl IS NULL OR e.feedUrl = :feedUrl) " +
            "ORDER BY e.pubDate DESC",
    )
    fun observeNewEpisodes(feedUrl: String?): Flow<List<EpisodeWithLedger>>

    /** The `Downloaded` / `Played` tabs: episodes whose ledger row is in [state]. */
    @Transaction
    @Query(
        "SELECT e.*, " +
            "l.episodeKey AS l_episodeKey, l.feedUrl AS l_feedUrl, l.enclosureUrl AS l_enclosureUrl, " +
            "l.state AS l_state, l.actionedAt AS l_actionedAt, l.syncedToServer AS l_syncedToServer, " +
            "l.attempts AS l_attempts, l.lastError AS l_lastError, l.writtenFileName AS l_writtenFileName, " +
            "l.durationSeconds AS l_durationSeconds " +
            "FROM episodes e JOIN episode_ledger l ON e.episodeKey = l.episodeKey " +
            "WHERE l.state = :state AND (:feedUrl IS NULL OR e.feedUrl = :feedUrl) " +
            "ORDER BY e.pubDate DESC",
    )
    fun observeEpisodesByState(
        feedUrl: String?,
        state: String,
    ): Flow<List<EpisodeWithLedger>>

    /**
     * Per-feed counts of undecided episodes for a bulk-confirmation dialog — the safeguard that
     * replaced the old rule against writing backlog rows at all (`docs/decisions/0013`).
     *
     * Mirrors [observeNewEpisodes]'s "no ledger row" predicate exactly, so the number the dialog
     * promises is the number that gets written. Undated episodes are **excluded** when an
     * [olderThanMillis] cutoff is given: a missing `pubDate` is not evidence of being old, and
     * sweeping one up would emit a `PLAY` the user never agreed to.
     */
    @Query(
        "SELECT e.feedUrl AS feedUrl, COUNT(*) AS count FROM episodes e " +
            "WHERE e.episodeKey NOT IN (SELECT episodeKey FROM episode_ledger) " +
            "AND (:feedUrl IS NULL OR e.feedUrl = :feedUrl) " +
            "AND (:olderThanMillis IS NULL OR (e.pubDate IS NOT NULL AND e.pubDate < :olderThanMillis)) " +
            "GROUP BY e.feedUrl ORDER BY count DESC, e.feedUrl ASC",
    )
    suspend fun countUndecidedByFeed(
        feedUrl: String?,
        olderThanMillis: Long?,
    ): List<FeedUndecidedCountRow>

    /** The rows [countUndecidedByFeed] counts. Same predicate, verbatim — see that KDoc. */
    @Query(
        "SELECT e.* FROM episodes e " +
            "WHERE e.episodeKey NOT IN (SELECT episodeKey FROM episode_ledger) " +
            "AND (:feedUrl IS NULL OR e.feedUrl = :feedUrl) " +
            "AND (:olderThanMillis IS NULL OR (e.pubDate IS NOT NULL AND e.pubDate < :olderThanMillis)) " +
            "ORDER BY e.pubDate DESC",
    )
    suspend fun undecided(
        feedUrl: String?,
        olderThanMillis: Long?,
    ): List<EpisodeEntity>
}
