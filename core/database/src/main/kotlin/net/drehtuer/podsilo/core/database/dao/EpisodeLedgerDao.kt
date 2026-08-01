// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import net.drehtuer.podsilo.core.database.entity.EpisodeLedgerEntity

/**
 * DAO for the ledger table itself — "the one table that must never be lost" (CLAUDE.md §5) — and
 * the outbox drain that hangs off it. The UI-facing joins against `episodes` live in
 * [EpisodeListDao]; this one never reads the episode cache.
 */
@Dao
interface EpisodeLedgerDao {
    @Upsert
    suspend fun upsert(row: EpisodeLedgerEntity)

    /**
     * One transaction and one emission for a bulk triage write. `@Upsert` on a list is already
     * transactional in Room; the port's contract is that observers see the whole batch at once, not
     * 412 intermediate list states (`docs/UI.md` §7).
     */
    @Upsert
    suspend fun upsertAll(rows: List<EpisodeLedgerEntity>)

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
}
