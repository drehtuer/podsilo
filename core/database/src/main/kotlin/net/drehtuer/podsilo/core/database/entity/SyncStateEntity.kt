// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room row for `SyncState` — a single-row table (`id` is always [SINGLETON_ID]). [lastEpisodeActionSyncTs]
 * is **Unix seconds** persisted verbatim from the server and sent back as the next `since`; never
 * computed from local device time (CLAUDE.md §11). [deviceId] is generated once and kept forever.
 */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int,
    val lastEpisodeActionSyncTs: Long,
    val deviceId: String,
) {
    companion object {
        const val SINGLETON_ID: Int = 1
    }
}
