// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.sync

import kotlinx.coroutines.runBlocking
import net.drehtuer.podsilo.core.model.EpisodeLedgerRow
import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.core.model.SyncState
import net.drehtuer.podsilo.core.model.port.EpisodeAction
import net.drehtuer.podsilo.core.model.port.EpisodeActionPage
import net.drehtuer.podsilo.core.model.port.EpisodeActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Issue #60, step 4: **the `since` cursor compares two different clocks.**
 *
 * The server selects `WHERE timestamp_epoch > :since` on the *client-authored* timestamp inside each
 * action, while the `timestamp` it returns — which we store as the next `since` — is its own wall
 * clock. Anything authored before our last pass is invisible permanently, silently, and with no gap
 * a user could notice.
 *
 * Measured against the author's instance: web-client actions arrive **6 980 seconds ahead** of the
 * server clock, because that client writes local time with no offset and the server reads it as UTC.
 * Ahead survives; behind disappears.
 */
class CursorOverlapTest {
    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-08-14T12:00:00Z"), ZoneOffset.UTC)
    private val oneDay = 24L * 60 * 60

    private fun playedAt(timestamp: String) =
        EpisodeAction(
            podcast = "https://example.com/feed.xml",
            episode = "https://example.com/ep.mp3",
            guid = "guid-1",
            action = EpisodeActionType.PLAY,
            timestamp = timestamp,
            started = 0,
            position = 1_800,
            total = 1_800,
        )

    @Test
    fun `the cursor sent to the server is rewound by a day`() =
        runBlocking {
            val storedCursor = Instant.parse("2026-08-14T12:00:00Z").epochSecond
            val client = FakeGpodderClient(episodeActionsPage = EpisodeActionPage(emptyList(), timestamp = 0L))

            SyncOrchestrator(
                FakeFeedRepository(),
                FakeEpisodeLedgerRepository(),
                FakeSyncStateRepository(SyncState(storedCursor, "device-a")),
                client,
                RecordingLogRepository(),
                fixedClock,
            ).sync()

            assertEquals(listOf(storedCursor - oneDay), client.fetchEpisodeActionsSinceValues)
        }

    /**
     * The case the whole step exists for: an action authored *before* the stored cursor. Without the
     * overlap the server would never return it, so this asserts the behaviour the client needs
     * rather than the arithmetic — the fake returns what it is given, and what matters is that the
     * action is reconciled rather than dropped.
     */
    @Test
    fun `an action authored before the stored cursor is still reconciled`() =
        runBlocking {
            val storedCursor = Instant.parse("2026-08-14T12:00:00Z").epochSecond
            val ledger = FakeEpisodeLedgerRepository()
            // Two hours older than the cursor — the shape a client with a lagging clock produces.
            val late = playedAt("2026-08-14T10:00:00+00:00")

            SyncOrchestrator(
                FakeFeedRepository(),
                ledger,
                FakeSyncStateRepository(SyncState(storedCursor, "device-a")),
                FakeGpodderClient(episodeActionsPage = EpisodeActionPage(listOf(late), timestamp = storedCursor)),
                RecordingLogRepository(),
                fixedClock,
            ).sync()

            assertEquals(LedgerState.HANDLED_REMOTELY, ledger.allRows.single().state)
        }

    /** A fresh install asks for everything; rewinding must not produce a negative `since`. */
    @Test
    fun `a zero cursor stays zero rather than going negative`() =
        runBlocking {
            val client = FakeGpodderClient(episodeActionsPage = EpisodeActionPage(emptyList(), timestamp = 0L))

            SyncOrchestrator(
                FakeFeedRepository(),
                FakeEpisodeLedgerRepository(),
                FakeSyncStateRepository(SyncState(0L, "device-a")),
                client,
                RecordingLogRepository(),
                fixedClock,
            ).sync()

            assertEquals(listOf(0L), client.fetchEpisodeActionsSinceValues)
        }

    /**
     * The overlap is only ever applied to what we *send*. What we persist stays the server's own
     * value, verbatim — CLAUDE.md §11 is explicit that the next `since` must never be computed
     * locally, and rewinding the stored cursor would compound a day per pass.
     */
    @Test
    fun `the stored cursor is the server's value, not the rewound one`() =
        runBlocking {
            val syncState = FakeSyncStateRepository(SyncState(1_000_000L, "device-a"))
            val serverTimestamp = 2_000_000L

            SyncOrchestrator(
                FakeFeedRepository(),
                FakeEpisodeLedgerRepository(),
                syncState,
                FakeGpodderClient(episodeActionsPage = EpisodeActionPage(emptyList(), timestamp = serverTimestamp)),
                RecordingLogRepository(),
                fixedClock,
            ).sync()

            assertEquals(serverTimestamp, syncState.current.lastEpisodeActionSyncTs)
        }

    /**
     * Re-delivery is the price of the overlap, so it has to be free. Reconciliation is idempotent
     * against a terminal local state, which is what makes a day of replayed actions cost nothing but
     * the bytes.
     */
    @Test
    fun `a replayed action against a terminal row writes nothing`() =
        runBlocking {
            val ledger = FakeEpisodeLedgerRepository()
            ledger.upsert(
                EpisodeLedgerRow(
                    episodeKey = "guid-1",
                    feedUrl = "https://example.com/feed.xml",
                    enclosureUrl = "https://example.com/ep.mp3",
                    state = LedgerState.DOWNLOADED,
                    actionedAt = 0L,
                    syncedToServer = true,
                    attempts = 0,
                    lastError = null,
                    writtenFileName = "20260714_Episode.mp3",
                ),
            )

            SyncOrchestrator(
                FakeFeedRepository(),
                ledger,
                FakeSyncStateRepository(SyncState(1_000L, "device-a")),
                FakeGpodderClient(
                    episodeActionsPage =
                        EpisodeActionPage(listOf(playedAt("2026-08-14T09:00:00+00:00")), timestamp = 2_000L),
                ),
                RecordingLogRepository(),
                fixedClock,
            ).sync()

            // Unchanged in every field the replay could have touched — the fake has no write log, and
            // "the row is exactly what it was" is the property that actually matters anyway.
            val row = ledger.allRows.single()
            assertEquals(LedgerState.DOWNLOADED, row.state)
            assertEquals(0L, row.actionedAt)
            assertEquals("20260714_Episode.mp3", row.writtenFileName)
            assertTrue("and it must not be re-queued for pushing", row.syncedToServer)
        }
}
