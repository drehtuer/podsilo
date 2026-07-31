// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.database

import kotlinx.coroutines.test.runTest
import net.drehtuer.podsilo.core.database.repository.SyncStateRepositoryImpl
import net.drehtuer.podsilo.core.model.SyncState
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncStateRepositoryTest : RoomTestBase() {
    @Test
    fun `first run mints and persists a stable deviceId with a zero cursor`() =
        runTest {
            var generated = 0
            val repo = SyncStateRepositoryImpl(db.syncStateDao(), deviceIdGenerator = { "device-${generated++}" })

            val first = repo.get()
            val second = repo.get()

            assertEquals(0, first.lastEpisodeActionSyncTs)
            assertEquals("device-0", first.deviceId)
            // Second call reads the persisted row rather than generating a new id.
            assertEquals(first, second)
            assertEquals(1, generated)
        }

    @Test
    fun `save persists the server timestamp verbatim and round-trips`() =
        runTest {
            val repo = SyncStateRepositoryImpl(db.syncStateDao(), deviceIdGenerator = { "fixed" })
            repo.get()

            repo.save(SyncState(lastEpisodeActionSyncTs = 1_752_483_600, deviceId = "fixed"))

            assertEquals(1_752_483_600, repo.get().lastEpisodeActionSyncTs)
        }

    @Test
    fun `save keeps a single row - later saves overwrite, not accumulate`() =
        runTest {
            val repo = SyncStateRepositoryImpl(db.syncStateDao(), deviceIdGenerator = { "fixed" })

            repo.save(SyncState(lastEpisodeActionSyncTs = 1, deviceId = "fixed"))
            repo.save(SyncState(lastEpisodeActionSyncTs = 2, deviceId = "fixed"))

            assertEquals(2, repo.get().lastEpisodeActionSyncTs)
        }
}
