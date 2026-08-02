// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import net.drehtuer.podsilo.core.database.entity.LogEntryEntity

/**
 * DAO for the error log. The collapse and eviction rules live here **as queries**, not as UI logic
 * and not as an app-start sweep (`docs/UI.md` §11) — a sweep would run at the one moment the user
 * is waiting for the app to open, and would not run at all for a process that only ever wakes for a
 * worker.
 */
@Dao
interface LogDao {
    @Query("SELECT * FROM error_log WHERE (:category IS NULL OR category = :category) ORDER BY at DESC")
    fun observe(category: String?): Flow<List<LogEntryEntity>>

    @Query("SELECT * FROM error_log ORDER BY at DESC")
    suspend fun getAll(): List<LogEntryEntity>

    @Query("SELECT COUNT(*) FROM error_log")
    suspend fun count(): Int

    @Query("DELETE FROM error_log")
    suspend fun clear()

    /**
     * Records one occurrence, collapsing onto an existing entry with the same [LogEntryEntity.identity].
     *
     * Deliberately not an `@Upsert`: a collapse must *increment* the counter and preserve
     * `firstSeenAt`, which no generated upsert can express. Wrapped in a transaction so two workers
     * failing at once cannot both read "no existing row" and insert duplicates — the unique index on
     * `identity` would then reject the second write outright.
     */
    @Transaction
    suspend fun record(entry: LogEntryEntity) {
        val bumped = bumpExisting(identity = entry.identity, at = entry.at, detail = entry.detail)
        if (bumped == 0) insert(entry)
        evict()
    }

    @Query(
        "UPDATE error_log SET occurrences = occurrences + 1, at = :at, detail = :detail " +
            "WHERE identity = :identity",
    )
    suspend fun bumpExisting(
        identity: String,
        at: Long,
        detail: String?,
    ): Int

    @Insert
    suspend fun insert(entry: LogEntryEntity)

    /** Restore only — see [EpisodeLedgerDao.deleteAll]. Keeps the archived rows' own ids. */
    @Insert
    suspend fun insertAll(entries: List<LogEntryEntity>)

    /**
     * Ring buffer: keep the newest [MAX_ENTRIES] **collapsed** entries. "Or 7 days, whichever is
     * larger" (`docs/UI.md` §11) needs no clause — an age-based rule can only ever delete rows this
     * one already keeps, so applying it too would make the buffer *smaller* than promised.
     */
    @Query(
        "DELETE FROM error_log WHERE id NOT IN " +
            "(SELECT id FROM error_log ORDER BY at DESC LIMIT $MAX_ENTRIES)",
    )
    suspend fun evict()

    companion object {
        const val MAX_ENTRIES: Int = 200
    }
}
