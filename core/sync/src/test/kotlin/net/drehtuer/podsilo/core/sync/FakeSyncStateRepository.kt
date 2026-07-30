// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.sync

import net.drehtuer.podsilo.core.model.SyncState
import net.drehtuer.podsilo.core.model.port.SyncStateRepository

class FakeSyncStateRepository(
    initial: SyncState = SyncState(lastEpisodeActionSyncTs = 0L, deviceId = "fake-device"),
) : SyncStateRepository {
    var current: SyncState = initial
        private set

    override suspend fun get(): SyncState = current

    override suspend fun save(state: SyncState) {
        current = state
    }
}
