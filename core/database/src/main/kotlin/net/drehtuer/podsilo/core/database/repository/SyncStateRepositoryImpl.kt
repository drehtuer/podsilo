// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.database.repository

import net.drehtuer.podsilo.core.database.dao.SyncStateDao
import net.drehtuer.podsilo.core.database.entity.SyncStateEntity
import net.drehtuer.podsilo.core.database.toDomain
import net.drehtuer.podsilo.core.model.SyncState
import net.drehtuer.podsilo.core.model.port.SyncStateRepository
import java.util.UUID

/**
 * Room-backed [SyncStateRepository]. On first run there is no row, so [get] mints a fresh
 * [SyncState] (`lastEpisodeActionSyncTs = 0`, a new [deviceIdGenerator] id) **and persists it**, so
 * the `deviceId` is stable forever after — losing it would stop us recognising our own echoed-back
 * actions in the remote stream (CLAUDE.md §5). [deviceIdGenerator] is injectable so tests get a
 * deterministic id.
 */
class SyncStateRepositoryImpl(
    private val syncStateDao: SyncStateDao,
    private val deviceIdGenerator: () -> String = { UUID.randomUUID().toString() },
) : SyncStateRepository {
    override suspend fun get(): SyncState {
        syncStateDao.get()?.let { return it.toDomain() }
        val fresh = SyncState(lastEpisodeActionSyncTs = 0, deviceId = deviceIdGenerator())
        save(fresh)
        return fresh
    }

    override suspend fun save(state: SyncState) {
        syncStateDao.upsert(
            SyncStateEntity(
                id = SyncStateEntity.SINGLETON_ID,
                lastEpisodeActionSyncTs = state.lastEpisodeActionSyncTs,
                deviceId = state.deviceId,
            ),
        )
    }
}
