// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.ui

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.drehtuer.podsilo.core.model.SyncState
import net.drehtuer.podsilo.core.model.port.SyncStateRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * The regression test for a wrong *unit*, which is the only thing this adapter can get wrong.
 *
 * `SyncState.lastEpisodeActionSyncTs` is Unix **seconds**, stored verbatim from the server — the one
 * value in the app that is not epoch millis (`docs/architecture.adoc` §4, CLAUDE.md §11). The adapter
 * read it with `EpochTime.ofMillisOrNull`, so a sync that had just succeeded rendered on the Pixel 5
 * as **"20647 d ago"**: 1785703652 milliseconds after the epoch is 21 January 1970.
 *
 * `docs/architecture.adoc` §5 exists to stop exactly this, by giving `EpochTime` two functions whose names
 * carry the unit — and the one call site that needed `ofServerSeconds` used `ofMillis` anyway. A
 * comment would not have caught it; this does.
 */
class SyncStatusAdapterTest {
    private class FakeSyncState(
        private val state: SyncState,
    ) : SyncStateRepository {
        override suspend fun get(): SyncState = state

        override suspend fun save(state: SyncState) = Unit
    }

    private fun adapterFor(seconds: Long) =
        SyncStatusAdapter(FakeSyncState(SyncState(lastEpisodeActionSyncTs = seconds, deviceId = "d")))

    @Test
    fun `the server cursor is read as seconds, not milliseconds`() =
        runTest {
            // The real value observed on the device, which must land in 2026 and not in 1970.
            val lastSync = adapterFor(1_785_703_652).observeLastSyncAt().first()

            assertEquals(Instant.parse("2026-08-02T20:47:32Z"), lastSync)
        }

    @Test
    fun `a pass that has never run reports never, rather than the epoch`() =
        runTest {
            assertNull(adapterFor(0).observeLastSyncAt().first())
        }
}
