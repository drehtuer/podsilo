// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.model.port

import net.drehtuer.podsilo.core.model.SyncState

/** Port for the single-row sync cursor. Implemented in `:core:database` (Room). */
interface SyncStateRepository {
    /** Returns a fresh [SyncState] (`lastEpisodeActionSyncTs = 0`, new random `deviceId`) on first run. */
    suspend fun get(): SyncState

    suspend fun save(state: SyncState)
}
