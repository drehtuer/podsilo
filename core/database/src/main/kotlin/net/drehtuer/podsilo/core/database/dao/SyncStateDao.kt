// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import net.drehtuer.podsilo.core.database.entity.SyncStateEntity

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE id = :id")
    suspend fun get(id: Int = SyncStateEntity.SINGLETON_ID): SyncStateEntity?

    @Upsert
    suspend fun upsert(entity: SyncStateEntity)

    /** Restore only — see [EpisodeLedgerDao.deleteAll]. */
    @Query("DELETE FROM sync_state")
    suspend fun deleteAll()
}
