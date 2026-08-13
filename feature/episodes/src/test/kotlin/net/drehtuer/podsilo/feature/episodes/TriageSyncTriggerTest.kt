// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import kotlinx.coroutines.test.runTest
import net.drehtuer.podsilo.core.model.Episode
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Issue #60, and the whole of the reported symptom: **a decision that never leaves the device.**
 *
 * `TriageWriter` wrote the row with `syncedToServer = false` and returned, and nothing anywhere
 * asked for a pass. A skip therefore waited for a completed download, an app-bar tap, or the
 * periodic pass — four hours by default — before Nextcloud heard about it.
 *
 * These pin the trigger where the write is, rather than at the four call sites that reach it.
 */
class TriageSyncTriggerTest {
    private val clock = Clock.fixed(Instant.parse("2026-08-14T10:00:00Z"), ZoneOffset.UTC)
    private val ledger = FakeLedgerRepository()
    private val syncTrigger = RecordingSyncTrigger()
    private val writer = TriageWriter(ledger, clock, syncTrigger)

    private fun episode(key: String) =
        Episode(
            episodeKey = key,
            feedUrl = "https://example.org/feed.xml",
            guid = key,
            enclosureUrl = "https://example.org/$key.mp3",
            title = "Episode $key",
            description = null,
            pubDate = null,
            durationMs = 1_800_000,
        )

    @Test
    fun `marking one episode as played asks for a sync pass`() =
        runTest {
            writer.markAsPlayed(listOf(episode("e1")))

            assertEquals(1, syncTrigger.requests)
        }

    /**
     * A bulk mark is **one** transaction and therefore **one** pass. 412 requests would be 412
     * enqueues of the same unique work — survivable, but it would make the trigger look like
     * something to be careful with rather than something to call from the writer.
     */
    @Test
    fun `a bulk mark asks once, not once per episode`() =
        runTest {
            writer.markAsPlayed((1..50).map { episode("e$it") })

            assertEquals(1, syncTrigger.requests)
        }

    @Test
    fun `marking nothing asks for nothing`() =
        runTest {
            writer.markAsPlayed(emptyList())

            assertEquals(0, syncTrigger.requests)
        }

    /**
     * Queuing a download must **not** ask for a pass: `QUEUED` has no outbound action at all, so the
     * pass would find an empty outbox and post nothing. `DownloadWorker` asks once the file lands,
     * which is the moment a `DOWNLOAD` action exists to send.
     */
    @Test
    fun `queuing a download asks for nothing — there is no action to push yet`() =
        runTest {
            writer.queue(listOf(episode("e1")))

            assertEquals(0, syncTrigger.requests)
        }

    /** The trigger must never come at the cost of durability: the row is written and still unsynced. */
    @Test
    fun `the row is written first and stays unsynced until a pass confirms it`() =
        runTest {
            writer.markAsPlayed(listOf(episode("e1")))

            val row = checkNotNull(ledger.get("e1"))
            assertEquals(false, row.syncedToServer)
            assertEquals(1, ledger.getUnsynced().size)
        }
}
