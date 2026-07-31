// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import net.drehtuer.podsilo.core.database.entity.EpisodeLedgerEntity

/**
 * DAO for the ledger table and the UI-facing episode-list joins. The three list queries share the
 * same `l_`-aliased projection of the ledger columns (see [EpisodeWithLedger]); the shared columns
 * (`episodeKey`, `feedUrl`, `enclosureUrl`) must be aliased or they'd collide with the embedded
 * episode's identically-named columns.
 */
@Dao
interface EpisodeLedgerDao {
    @Upsert
    suspend fun upsert(row: EpisodeLedgerEntity)

    @Query("SELECT * FROM episode_ledger")
    suspend fun getAll(): List<EpisodeLedgerEntity>

    @Query("SELECT * FROM episode_ledger WHERE episodeKey = :episodeKey")
    suspend fun get(episodeKey: String): EpisodeLedgerEntity?

    /** The outbox drain query (CLAUDE.md §5). */
    @Query("SELECT * FROM episode_ledger WHERE syncedToServer = 0")
    suspend fun getUnsynced(): List<EpisodeLedgerEntity>

    @Query("UPDATE episode_ledger SET syncedToServer = 1 WHERE episodeKey IN (:episodeKeys)")
    suspend fun markSynced(episodeKeys: List<String>)

    /** Row-typed filter for the `observe(filter)` port. `NEW` has no rows and is handled in the repository. */
    @Query(
        "SELECT * FROM episode_ledger " +
            "WHERE (:feedUrl IS NULL OR feedUrl = :feedUrl) AND (:state IS NULL OR state = :state) " +
            "ORDER BY actionedAt DESC",
    )
    fun observeRows(
        feedUrl: String?,
        state: String?,
    ): Flow<List<EpisodeLedgerEntity>>

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
     * The `NEW` tab: episodes with **no** ledger row at all (CLAUDE.md §9), with the backlog cutoff
     * `pubDate >= Feed.firstSeenAt` applied unless [includeBacklog] (CLAUDE.md §5's "backlog is a UI
     * problem"). Undated episodes (`pubDate IS NULL`) are kept so nothing genuinely new is hidden.
     */
    @Transaction
    @Query(
        "SELECT e.*, " +
            "NULL AS l_episodeKey, NULL AS l_feedUrl, NULL AS l_enclosureUrl, " +
            "NULL AS l_state, NULL AS l_actionedAt, NULL AS l_syncedToServer, " +
            "NULL AS l_attempts, NULL AS l_lastError, NULL AS l_writtenFileName, " +
            "NULL AS l_durationSeconds " +
            "FROM episodes e " +
            "JOIN feeds f ON e.feedUrl = f.url " +
            "WHERE e.episodeKey NOT IN (SELECT episodeKey FROM episode_ledger) " +
            "AND (:feedUrl IS NULL OR e.feedUrl = :feedUrl) " +
            "AND (:includeBacklog OR e.pubDate IS NULL OR e.pubDate >= f.firstSeenAt) " +
            "ORDER BY e.pubDate DESC",
    )
    fun observeNewEpisodes(
        feedUrl: String?,
        includeBacklog: Boolean,
    ): Flow<List<EpisodeWithLedger>>

    /** The `DOWNLOADED`/`SKIPPED` tabs: episodes whose ledger row is in [state]. */
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
}
