// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.database

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.drehtuer.podsilo.core.database.repository.EpisodeLedgerRepositoryImpl
import net.drehtuer.podsilo.core.database.repository.EpisodeListRepositoryImpl
import net.drehtuer.podsilo.core.database.repository.EpisodeRepositoryImpl
import net.drehtuer.podsilo.core.database.repository.FeedRepositoryImpl
import net.drehtuer.podsilo.core.model.ErrorCause
import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.core.model.port.LedgerFilter
import net.drehtuer.podsilo.core.model.port.LedgerFilterState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The queries S7 gained in issue #47, and the projection bug found while writing them.
 *
 * The Activity screen used to observe **every** ledger row and then look each row's episode up
 * individually in Kotlin, discarding all but the handful in flight — thousands of sequential
 * queries per emission on a real device, re-run on every ledger write anywhere in the app. These
 * assert that the narrowing and the join are the database's job now.
 */
class ActivityQueriesTest : RoomTestBase() {
    private val feeds by lazy { FeedRepositoryImpl(db.feedDao()) }
    private val episodes by lazy { EpisodeRepositoryImpl(db.episodeDao()) }
    private val ledger by lazy { EpisodeLedgerRepositoryImpl(db.episodeLedgerDao()) }
    private val list by lazy { EpisodeListRepositoryImpl(db.episodeListDao()) }

    private suspend fun seedFeedWithEpisodes(vararg keys: String) {
        feeds.replaceAll(listOf(feed("f")))
        episodes.replaceForFeed("f", keys.map { episode(it, "f") })
    }

    @Test
    fun `observeInFlight selects only the three states S7 renders`() =
        runTest {
            seedFeedWithEpisodes("queued", "downloading", "failed", "done", "played")
            ledger.upsert(ledgerRow("queued", "f", LedgerState.QUEUED))
            ledger.upsert(ledgerRow("downloading", "f", LedgerState.DOWNLOADING))
            ledger.upsert(ledgerRow("failed", "f", LedgerState.ERROR))
            ledger.upsert(ledgerRow("done", "f", LedgerState.DOWNLOADED))
            ledger.upsert(ledgerRow("played", "f", LedgerState.SKIPPED))

            val inFlight = list.observeInFlight().first()

            assertEquals(
                setOf("queued", "downloading", "failed"),
                inFlight.map { it.episode.episodeKey }.toSet(),
            )
        }

    /**
     * The cost claim, made checkable: a ledger full of decided episodes must not enlarge the result.
     * A thousand terminal rows contribute nothing, which is exactly what the old Kotlin-side
     * filtering could not promise — it had already loaded and joined all of them by then.
     */
    @Test
    fun `a large decided ledger does not enlarge the in-flight result`() =
        runTest {
            val keys = (1..1_000).map { "e$it" }
            seedFeedWithEpisodes(*(keys + "live").toTypedArray())
            keys.forEach { ledger.upsert(ledgerRow(it, "f", LedgerState.SKIPPED)) }
            ledger.upsert(ledgerRow("live", "f", LedgerState.DOWNLOADING))

            assertEquals(listOf("live"), list.observeInFlight().first().map { it.episode.episodeKey })
        }

    @Test
    fun `observeRecentlyDelivered limits, orders newest first and respects the clear cursor`() =
        runTest {
            seedFeedWithEpisodes("a", "b", "c")
            ledger.upsert(ledgerRow("a", "f", LedgerState.DOWNLOADED).copy(actionedAt = 100, writtenFileName = "a.mp3"))
            ledger.upsert(ledgerRow("b", "f", LedgerState.DOWNLOADED).copy(actionedAt = 300, writtenFileName = "b.mp3"))
            ledger.upsert(ledgerRow("c", "f", LedgerState.DOWNLOADED).copy(actionedAt = 200, writtenFileName = "c.mp3"))

            assertEquals(
                listOf("b", "c", "a"),
                list.observeRecentlyDelivered(since = 0, limit = 20).first().map { it.episodeKey },
            )
            assertEquals(
                listOf("b"),
                list.observeRecentlyDelivered(since = 0, limit = 1).first().map { it.episodeKey },
            )
            // The cursor hides rows; it must never delete them (CLAUDE.md §11).
            assertEquals(
                listOf("b"),
                list.observeRecentlyDelivered(since = 250, limit = 20).first().map { it.episodeKey },
            )
            assertEquals(LedgerState.DOWNLOADED, ledger.get("a")?.state)
        }

    /** A download that never produced a file is not something to claim landed. */
    @Test
    fun `a delivered row with no written file name is not listed`() =
        runTest {
            seedFeedWithEpisodes("a")
            ledger.upsert(ledgerRow("a", "f", LedgerState.DOWNLOADED).copy(actionedAt = 100))

            assertTrue(list.observeRecentlyDelivered(since = 0, limit = 20).first().isEmpty())
        }

    @Test
    fun `observeUnsyncedCount counts the outbox in SQL`() =
        runTest {
            ledger.upsert(ledgerRow("a", "f", LedgerState.DOWNLOADED, syncedToServer = false))
            ledger.upsert(ledgerRow("b", "f", LedgerState.SKIPPED, syncedToServer = false))
            ledger.upsert(ledgerRow("c", "f", LedgerState.SKIPPED, syncedToServer = true))

            assertEquals(2, list.observeUnsyncedCount().first())

            ledger.markSynced(listOf("a", "b"))
            assertEquals(0, list.observeUnsyncedCount().first())
        }

    /**
     * **The regression test for a bug found while writing the queries above.**
     *
     * Not one of the list queries projected `lastErrorCause` or `lastErrorRetryable` — the columns
     * exist (schema v3), the entity has them, and every `SELECT` simply left them out, so Room saw
     * `NULL` and the fields fell back to their defaults. The visible consequence is `docs/UI.md`
     * §12.11 and architecture §11 quietly not working: a `FOLDER_UNAVAILABLE` failure could never render
     * *Choose folder* rather than a *Retry* button that cannot possibly succeed, because the screen
     * had no way to tell one failure from another.
     *
     * Asserted through every query that carries a ledger row, since the fault was per-`SELECT`.
     */
    @Test
    fun `the typed failure survives every list query, not just the ledger row`() =
        runTest {
            seedFeedWithEpisodes("a")
            ledger.upsert(
                ledgerRow("a", "f", LedgerState.ERROR).copy(
                    lastError = "the download folder is no longer accessible",
                    lastErrorCause = ErrorCause.FOLDER_UNAVAILABLE,
                    lastErrorRetryable = false,
                ),
            )

            val fromAll = list.observeEpisodes(LedgerFilter(state = LedgerFilterState.ALL)).first().single()
            val fromInFlight = list.observeInFlight().first().single()

            listOf(fromAll, fromInFlight).forEach { item ->
                assertEquals(ErrorCause.FOLDER_UNAVAILABLE, item.ledger?.lastErrorCause)
                assertFalse("a lost folder grant is not retryable", item.ledger?.lastErrorRetryable ?: true)
            }
        }
}
