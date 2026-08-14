// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.sync

import kotlinx.coroutines.runBlocking
import net.drehtuer.podsilo.core.model.EpisodeLedgerRow
import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.core.model.SyncOutcome
import net.drehtuer.podsilo.core.model.SyncState
import net.drehtuer.podsilo.core.model.port.EpisodeAction
import net.drehtuer.podsilo.core.model.port.EpisodeActionPage
import net.drehtuer.podsilo.core.model.port.EpisodeActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The two directional passes (`docs/decisions/0025`) — *apply Nextcloud's state here* and *send this
 * device's state to Nextcloud*.
 *
 * The pull's whole design is that it is **the ordinary reconciliation with `since = 0`**, overriding
 * nothing. Most of these tests exist to keep it that way: if any of them start needing a special
 * case in `reconcile`, the button has drifted into the dangerous version the author ruled out.
 */
class DirectionalSyncTest {
    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-08-14T12:00:00Z"), ZoneOffset.UTC)

    private fun orchestrator(
        ledger: FakeEpisodeLedgerRepository = FakeEpisodeLedgerRepository(),
        client: FakeGpodderClient = FakeGpodderClient(),
        syncState: FakeSyncStateRepository = FakeSyncStateRepository(SyncState(1_000_000L, "device-a")),
    ) = SyncOrchestrator(FakeFeedRepository(), ledger, syncState, client, RecordingLogRepository(), fixedClock)

    private fun row(
        key: String,
        state: LedgerState,
        synced: Boolean = true,
    ) = EpisodeLedgerRow(
        episodeKey = key,
        feedUrl = "https://example.com/feed.xml",
        enclosureUrl = "https://example.com/$key.mp3",
        state = state,
        actionedAt = 0L,
        syncedToServer = synced,
        attempts = 0,
        lastError = null,
        writtenFileName = if (state == LedgerState.DOWNLOADED) "$key.mp3" else null,
        durationSeconds = 1_800,
    )

    private fun played(key: String) =
        EpisodeAction(
            podcast = "https://example.com/feed.xml",
            episode = "https://example.com/$key.mp3",
            guid = key,
            action = EpisodeActionType.PLAY,
            timestamp = "2026-08-01T09:00:00+00:00",
            started = 0,
            position = 1_800,
            total = 1_800,
        )

    // ---------------------------------------------------------------- force pull

    @Test
    fun `the pull asks for the whole log, not the delta`() =
        runBlocking {
            val client = FakeGpodderClient(episodeActionsPage = EpisodeActionPage(emptyList(), timestamp = 5L))

            orchestrator(client = client).forcePull()

            assertEquals(
                "since must be 0 — the button's entire difference",
                listOf(0L),
                client.fetchEpisodeActionsSinceValues,
            )
        }

    @Test
    fun `the pull decides episodes this device has not decided`() =
        runBlocking {
            val ledger = FakeEpisodeLedgerRepository()
            val client = FakeGpodderClient(episodeActionsPage = EpisodeActionPage(listOf(played("e1")), timestamp = 5L))

            val outcome = orchestrator(ledger, client).forcePull()

            assertEquals(SyncOutcome.Success, outcome)
            assertEquals(LedgerState.HANDLED_REMOTELY, ledger.allRows.single().state)
        }

    /**
     * D4 and D5, as behaviour rather than as prose. The pull can only ever *shorten* the To-decide
     * list — it never re-opens a decision, and never replaces a `DOWNLOADED` row, which the server
     * structurally cannot carry back (`docs/decisions/0008`).
     */
    @Test
    fun `the pull leaves every decided state exactly as it found it`() =
        runBlocking {
            listOf(LedgerState.DOWNLOADED, LedgerState.SKIPPED, LedgerState.HANDLED_REMOTELY).forEach { state ->
                val ledger = FakeEpisodeLedgerRepository(initial = listOf(row("e1", state)))
                val client =
                    FakeGpodderClient(episodeActionsPage = EpisodeActionPage(listOf(played("e1")), timestamp = 5L))

                orchestrator(ledger, client).forcePull()

                val stored = ledger.allRows.single()
                assertEquals("state=$state must be untouched", state, stored.state)
                assertEquals(0L, stored.actionedAt)
            }
        }

    @Test
    fun `the pull writes nothing to the server`() =
        runBlocking {
            val ledger = FakeEpisodeLedgerRepository(initial = listOf(row("e1", LedgerState.SKIPPED, synced = false)))
            val client = FakeGpodderClient(episodeActionsPage = EpisodeActionPage(emptyList(), timestamp = 5L))

            orchestrator(ledger, client).forcePull()

            assertEquals("a pull is a pull", 0, client.postEpisodeActionsCallCount)
        }

    // ---------------------------------------------------------------- force push

    @Test
    fun `the push re-sends rows the server has already seen`() =
        runBlocking {
            val ledger =
                FakeEpisodeLedgerRepository(
                    initial = listOf(row("e1", LedgerState.SKIPPED), row("e2", LedgerState.DOWNLOADED)),
                )
            val client = FakeGpodderClient()

            val outcome = orchestrator(ledger, client).forcePush()

            assertEquals(SyncOutcome.Success, outcome)
            // SKIPPED -> one PLAY; DOWNLOADED -> DOWNLOAD + PLAY (`docs/decisions/0023`).
            assertEquals(3, client.postedActions.size)
        }

    /** The reason the button exists: a row the ordinary pass will never retry because it is synced. */
    @Test
    fun `an already-synced download is exactly what the push repairs`() =
        runBlocking {
            val ledger = FakeEpisodeLedgerRepository(initial = listOf(row("e1", LedgerState.DOWNLOADED, synced = true)))

            val ordinary = FakeGpodderClient()
            orchestrator(ledger, ordinary).sync()

            val forced = FakeGpodderClient()
            orchestrator(ledger, forced).forcePush()

            assertEquals(
                "the ordinary pass has nothing to send — the row is already synced",
                0,
                ordinary.postEpisodeActionsCallCount,
            )
            assertEquals("the force push sends it anyway", 1, forced.postEpisodeActionsCallCount)
            assertEquals(
                listOf(EpisodeActionType.DOWNLOAD, EpisodeActionType.PLAY),
                forced.postedActions.map { it.action },
            )
        }

    @Test
    fun `states the API cannot represent are still never pushed`() =
        runBlocking {
            val ledger =
                FakeEpisodeLedgerRepository(
                    initial =
                        listOf(
                            row("q", LedgerState.QUEUED),
                            row("d", LedgerState.DOWNLOADING),
                            row("e", LedgerState.ERROR),
                            row("h", LedgerState.HANDLED_REMOTELY),
                        ),
                )
            val client = FakeGpodderClient()

            orchestrator(ledger, client).forcePush()

            assertEquals(0, client.postEpisodeActionsCallCount)
        }

    // ---------------------------------------------------------------- chunking

    /**
     * A push of the author's ledger is thousands of actions. One body would be megabytes against a
     * PHP endpoint whose `post_max_size` defaults to 8 MB, so the request count is the assertion.
     */
    @Test
    fun `a large push is split into several requests`() =
        runBlocking {
            val rows = (1..450).map { row("e$it", LedgerState.SKIPPED) }
            val client = FakeGpodderClient()

            orchestrator(FakeEpisodeLedgerRepository(initial = rows), client).forcePush()

            assertEquals("450 actions at 200 per request", 3, client.postEpisodeActionsCallCount)
        }

    /** A row's actions must not straddle two requests: it is marked synced as a unit. */
    @Test
    fun `a row's actions always travel together`() =
        runBlocking {
            // Every row emits two actions, so an even limit can only be respected by not splitting.
            val rows = (1..150).map { row("e$it", LedgerState.DOWNLOADED) }
            val client = FakeGpodderClient()

            orchestrator(FakeEpisodeLedgerRepository(initial = rows), client).forcePush()

            assertTrue(client.postedActionBatches.all { batch -> batch.size % 2 == 0 })
        }

    /**
     * Partial progress is the correct outcome of a partial success. The rows in accepted chunks stay
     * marked — they really were accepted — and the rest stay unsynced for the next pass, which is
     * precisely what the outbox is for.
     */
    @Test
    fun `a chunk failing mid-way keeps the earlier chunks and leaves the rest unsynced`() =
        runBlocking {
            val rows = (1..450).map { row("e$it", LedgerState.SKIPPED, synced = false) }
            val ledger = FakeEpisodeLedgerRepository(initial = rows)
            val client = FakeGpodderClient(failPostAfterCalls = 2, postFailure = IOException("HTTP 500"))

            val outcome = orchestrator(ledger, client).forcePush()

            assertTrue(outcome is SyncOutcome.Retry)
            val synced = ledger.allRows.count { it.syncedToServer }
            assertEquals("two chunks of 200 were accepted", 400, synced)
            assertFalse("and nothing beyond them was marked", ledger.allRows.all { it.syncedToServer })
        }
}
