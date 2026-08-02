// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import net.drehtuer.podsilo.core.database.entity.EpisodeEntity

@Dao
interface EpisodeDao {
    @Query("SELECT * FROM episodes WHERE feedUrl = :feedUrl ORDER BY pubDate DESC")
    fun observeForFeed(feedUrl: String): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE episodeKey = :episodeKey")
    suspend fun get(episodeKey: String): EpisodeEntity?

    /**
     * Newest publication date per feed, for S1's ordering. Undated episodes contribute nothing:
     * `MAX` skips NULLs, so a feed with only undated episodes is simply absent from the result and
     * sorts as "never fetched" rather than as ancient.
     */
    @Query("SELECT feedUrl, MAX(pubDate) AS latest FROM episodes GROUP BY feedUrl")
    suspend fun latestPublicationByFeed(): List<FeedLatestRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(episodes: List<EpisodeEntity>)

    @Query("DELETE FROM episodes WHERE feedUrl = :feedUrl")
    suspend fun deleteForFeed(feedUrl: String)

    /** Wipe-and-rebuild the parsed cache for one feed (episodes are disposable — CLAUDE.md §5). */
    @Transaction
    suspend fun replaceForFeed(
        feedUrl: String,
        episodes: List<EpisodeEntity>,
    ) {
        deleteForFeed(feedUrl)
        insertAll(episodes)
    }
}

/** Projection for [EpisodeDao.latestPublicationByFeed]; `latest` is null when the feed has no dated episode. */
data class FeedLatestRow(
    val feedUrl: String,
    val latest: Long?,
)
