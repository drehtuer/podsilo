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
