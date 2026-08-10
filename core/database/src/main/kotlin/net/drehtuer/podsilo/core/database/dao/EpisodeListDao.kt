// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import net.drehtuer.podsilo.core.database.entity.EpisodeEntity
import net.drehtuer.podsilo.core.database.entity.EpisodeLedgerEntity

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
            "l.attempts AS l_attempts, l.lastError AS l_lastError, " +
            "l.lastErrorCause AS l_lastErrorCause, l.lastErrorRetryable AS l_lastErrorRetryable, " +
            "l.writtenFileName AS l_writtenFileName, l.durationSeconds AS l_durationSeconds " +
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
            "NULL AS l_attempts, NULL AS l_lastError, " +
            "NULL AS l_lastErrorCause, NULL AS l_lastErrorRetryable, " +
            "NULL AS l_writtenFileName, NULL AS l_durationSeconds " +
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
            "l.attempts AS l_attempts, l.lastError AS l_lastError, " +
            "l.lastErrorCause AS l_lastErrorCause, l.lastErrorRetryable AS l_lastErrorRetryable, " +
            "l.writtenFileName AS l_writtenFileName, l.durationSeconds AS l_durationSeconds " +
            "FROM episodes e JOIN episode_ledger l ON e.episodeKey = l.episodeKey " +
            "WHERE l.state = :state AND (:feedUrl IS NULL OR e.feedUrl = :feedUrl) " +
            "ORDER BY e.pubDate DESC",
    )
    fun observeEpisodesByState(
        feedUrl: String?,
        state: String,
    ): Flow<List<EpisodeWithLedger>>

    /**
     * **S7's list, bounded by what S7 renders.**
     *
     * The Activity screen used to observe every ledger row on the device and then look each row's
     * episode up one at a time, in Kotlin, before discarding all but the handful that are in flight.
     * On a device with thousands of decided episodes that is thousands of queries per emission — and
     * it re-ran on *every* ledger write anywhere in the app, which is what made downloads "appear
     * delayed" (issue #47). Here the predicate and the join are both the database's job, so the work
     * scales with the queue rather than with the ledger.
     *
     * `QUEUED`, `DOWNLOADING` and `ERROR` are exactly S7's three groups. Newest decision first, so a
     * download the user just asked for is at the top where they are looking.
     */
    @Transaction
    @Query(
        "SELECT e.*, " +
            "l.episodeKey AS l_episodeKey, l.feedUrl AS l_feedUrl, l.enclosureUrl AS l_enclosureUrl, " +
            "l.state AS l_state, l.actionedAt AS l_actionedAt, l.syncedToServer AS l_syncedToServer, " +
            "l.attempts AS l_attempts, l.lastError AS l_lastError, " +
            "l.lastErrorCause AS l_lastErrorCause, l.lastErrorRetryable AS l_lastErrorRetryable, " +
            "l.writtenFileName AS l_writtenFileName, l.durationSeconds AS l_durationSeconds " +
            "FROM episodes e JOIN episode_ledger l ON e.episodeKey = l.episodeKey " +
            "WHERE l.state IN ('QUEUED', 'DOWNLOADING', 'ERROR') " +
            "ORDER BY l.actionedAt DESC",
    )
    fun observeInFlight(): Flow<List<EpisodeWithLedger>>

    /**
     * The last [limit] delivered files — S7's *recently downloaded* group, and the app's only
     * "did it actually land?" affordance.
     *
     * Ledger rows rather than the join: the group renders `writtenFileName` and the feed, both of
     * which the ledger row carries denormalised (`docs/decisions/0001`), so it stays correct for an
     * episode whose cached row was pruned by an unsubscribe.
     *
     * [since] is the *display cursor* `SettingsRepository.observeDeliveredClearedAt` holds — "stop
     * showing me these" — and never deletes anything: those rows are what stop an episode being
     * downloaded twice (CLAUDE.md §11). `LIMIT` in SQL, not `take()` in Kotlin, so the whole
     * downloaded history is never materialised to show twenty lines.
     */
    @Query(
        "SELECT * FROM episode_ledger " +
            "WHERE state = 'DOWNLOADED' AND writtenFileName IS NOT NULL AND actionedAt > :since " +
            "ORDER BY actionedAt DESC LIMIT :limit",
    )
    fun observeRecentlyDelivered(
        since: Long,
        limit: Int,
    ): Flow<List<EpisodeLedgerEntity>>

    /**
     * How deep the outbox is, for S7's "n actions pending" line.
     *
     * Here rather than on `EpisodeLedgerDao` — which owns the outbox — because this is a **screen
     * read**, and that is the seam these two DAOs are split along. (detekt's function-count ceiling
     * on the ledger DAO is what forced the question; the answer it forced is the right one.)
     *
     * `COUNT(*)` rather than `getUnsynced().size`: the screen wants an integer, and materialising
     * every pending row on every ledger write is the shape of query that made S7 lag (issue #47).
     */
    @Query("SELECT COUNT(*) FROM episode_ledger WHERE syncedToServer = 0")
    fun observeUnsyncedCount(): Flow<Int>

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

    /**
     * [countUndecidedByFeed]'s live twin, for S1's badges — same predicate, no cutoff parameter
     * because a home-screen badge is never scoped to a date.
     *
     * Counting in SQL rather than observing the episodes and grouping them in Kotlin is the point:
     * the badges are a handful of integers over a table that can hold thousands of rows.
     */
    @Query(
        "SELECT e.feedUrl AS feedUrl, COUNT(*) AS count FROM episodes e " +
            "WHERE e.episodeKey NOT IN (SELECT episodeKey FROM episode_ledger) " +
            "GROUP BY e.feedUrl",
    )
    fun observeUndecidedCounts(): Flow<List<FeedUndecidedCountRow>>

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
