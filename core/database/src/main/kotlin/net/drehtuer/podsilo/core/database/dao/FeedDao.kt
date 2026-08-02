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

    @Query("SELECT COUNT(*) FROM feeds")
    suspend fun count(): Int

    @Query("SELECT * FROM feeds WHERE url = :url")
    suspend fun get(url: String): FeedEntity?

    // Column-scoped UPDATE, not an upsert of a whole row: firstSeenAt must survive untouched (it is
    // write-once and drives the backlog cutoff), and a feed that has been unsubscribed in the
    // meantime must not be resurrected by its own in-flight refresh — no row, no update.
    //
    // @Suppress: Room binds query parameters positionally and cannot destructure an object, so the
    // columns have to be listed flat here. The port takes a FeedRefreshMetadata; this is the
    // one place that unpacks it.
    @Suppress("LongParameterList")
    @Query(
        "UPDATE feeds SET title = :title, imageUrl = :imageUrl, httpEtag = :httpEtag, " +
            "httpLastModified = :httpLastModified, lastRefreshedAt = :lastRefreshedAt WHERE url = :url",
    )
    suspend fun updateRefreshMetadata(
        url: String,
        title: String,
        imageUrl: String?,
        httpEtag: String?,
        httpLastModified: String?,
        lastRefreshedAt: Long,
    )

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
