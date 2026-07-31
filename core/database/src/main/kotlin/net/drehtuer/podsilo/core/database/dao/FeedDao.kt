// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import net.drehtuer.podsilo.core.database.entity.FeedEntity

@Dao
interface FeedDao {
    @Query("SELECT * FROM feeds ORDER BY title")
    fun observeAll(): Flow<List<FeedEntity>>

    @Query("SELECT * FROM feeds")
    suspend fun getAll(): List<FeedEntity>

    // @Upsert (not @Insert(REPLACE)): REPLACE deletes-then-inserts, which would fire the episodes'
    // ON DELETE CASCADE and wipe an existing feed's cached episodes on every subscription refresh.
    // Upsert updates in place, so re-seeing a feed leaves its episodes untouched.
    @Upsert
    suspend fun upsertAll(feeds: List<FeedEntity>)

    @Query("DELETE FROM feeds WHERE url NOT IN (:keepUrls)")
    suspend fun deleteNotIn(keepUrls: List<String>)

    @Query("DELETE FROM feeds")
    suspend fun deleteAll()

    /**
     * Wholesale-replaces the subscription mirror with [feeds]: feeds no longer present are deleted
     * (cascading to their cached episodes), the rest are upserted. Feeds already present keep their
     * `firstSeenAt`/`title` because the caller passes those rows back unchanged
     * (`SyncOrchestrator.pullSubscriptions`), and upsert doesn't reset unspecified columns.
     */
    @Transaction
    suspend fun replaceAll(feeds: List<FeedEntity>) {
        if (feeds.isEmpty()) {
            deleteAll()
        } else {
            deleteNotIn(feeds.map { it.url })
            upsertAll(feeds)
        }
    }
}
