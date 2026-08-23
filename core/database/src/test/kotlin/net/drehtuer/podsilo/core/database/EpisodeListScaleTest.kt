// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.database

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.drehtuer.podsilo.core.database.repository.EpisodeListRepositoryImpl
import net.drehtuer.podsilo.core.database.repository.EpisodeRepositoryImpl
import net.drehtuer.podsilo.core.database.repository.FeedRepositoryImpl
import net.drehtuer.podsilo.core.model.Episode
import net.drehtuer.podsilo.core.model.port.LedgerFilter
import net.drehtuer.podsilo.core.model.port.LedgerFilterState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **The measurement `docs/backlog.adoc` asked for before adding Paging 3.**
 *
 * `docs/UI.adoc` §B14.3 says a 500-episode feed under `All` needs "paging or a keyed `LazyColumn`",
 * and CLAUDE.md §3/§5 name Paging 3 for long lists. The note deliberately refused to decide that
 * from principle, because the cost of the dependency is real and the size of the problem was
 * unmeasured. These tests are the measurement, at three sizes: the 500 the UI contract names, the
 * ~9,500 the author's own subscriptions actually hold, and one whole feed.
 *
 * **What they assert is a budget, not a stopwatch.** The thresholds are deliberately an order of
 * magnitude above what the query costs in practice, so this fails when the shape of the query
 * changes — an accidental N+1 in the join, a `LEFT JOIN` that stops using the index — and not when
 * CI happens to be busy. A timing assertion tight enough to measure a regression of 20% would be a
 * flaky test, which is worse than no test.
 */
class EpisodeListScaleTest : RoomTestBase() {
    private val feeds by lazy { FeedRepositoryImpl(db.feedDao()) }
    private val episodes by lazy { EpisodeRepositoryImpl(db.episodeDao()) }
    private val list by lazy { EpisodeListRepositoryImpl(db.episodeListDao()) }

    private suspend fun seed(count: Int) {
        feeds.replaceAll(listOf(feed("https://example.org/feed.xml")))
        episodes.replaceForFeed(
            "https://example.org/feed.xml",
            (1..count).map { index ->
                Episode(
                    episodeKey = "guid-$index",
                    feedUrl = "https://example.org/feed.xml",
                    guid = "guid-$index",
                    enclosureUrl = "https://example.org/ep$index.mp3",
                    title = "Folge $index — Warum Hamburg immer regnet",
                    // Realistic weight: show notes are the biggest column and the reason a row is
                    // not free. A few hundred bytes each is typical of the author's own feeds.
                    description = "<p>Show notes for episode $index.</p>".repeat(10),
                    pubDate = 1_700_000_000_000L + index * 60_000L,
                    durationMs = 1_800_000,
                )
            },
        )
    }

    private suspend fun timeFirstEmission(filter: LedgerFilterState): Pair<Int, Long> {
        val startedAt = System.nanoTime()
        val rows = list.observeEpisodes(LedgerFilter(state = filter)).first()
        val millis = (System.nanoTime() - startedAt) / 1_000_000
        println("MEASURE $filter ${rows.size} rows in ${millis}ms")
        return rows.size to millis
    }

    @Test
    fun `the 500-episode feed named by the UI contract loads in one emission`() =
        runTest {
            seed(500)

            val (size, millis) = timeFirstEmission(LedgerFilterState.ALL)

            assertEquals(500, size)
            assertTrue("500 episodes took ${millis}ms", millis < ONE_SCREENFUL_BUDGET_MS)
        }

    @Test
    fun `the author's real library size loads in one emission`() =
        runTest {
            // ~9,500 episodes across four feeds is what the real device holds (README). Modelled as
            // one feed because the query filters by feed or not at all — the row count is the cost.
            seed(9_500)

            val (size, millis) = timeFirstEmission(LedgerFilterState.ALL)

            assertEquals(9_500, size)
            assertTrue("9,500 episodes took ${millis}ms", millis < WHOLE_LIBRARY_BUDGET_MS)
        }

    @Test
    fun `the To decide filter costs no more than All at the same size`() =
        runTest {
            // The default view, and the one with the sub-select against episode_ledger. If paging is
            // ever needed it will be needed here first, so the NOT IN must not be the thing that
            // makes it necessary.
            seed(9_500)

            val (size, millis) = timeFirstEmission(LedgerFilterState.NEW)

            assertEquals("no ledger rows exist, so every episode is undecided", 9_500, size)
            assertTrue("9,500 undecided episodes took ${millis}ms", millis < WHOLE_LIBRARY_BUDGET_MS)
        }

    private companion object {
        /** Generous by design — see the class KDoc. Measured at well under a tenth of this. */
        const val ONE_SCREENFUL_BUDGET_MS = 2_000L
        const val WHOLE_LIBRARY_BUDGET_MS = 10_000L
    }
}
