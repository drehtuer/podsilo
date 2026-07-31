// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.database

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.drehtuer.podsilo.core.database.repository.EpisodeLedgerRepositoryImpl
import net.drehtuer.podsilo.core.database.repository.EpisodeRepositoryImpl
import net.drehtuer.podsilo.core.database.repository.FeedRepositoryImpl
import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.core.model.port.LedgerFilter
import net.drehtuer.podsilo.core.model.port.LedgerFilterState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeLedgerRepositoryTest : RoomTestBase() {
    private val feeds by lazy { FeedRepositoryImpl(db.feedDao()) }
    private val episodes by lazy { EpisodeRepositoryImpl(db.episodeDao()) }
    private val ledger by lazy { EpisodeLedgerRepositoryImpl(db.episodeLedgerDao()) }

    @Test
    fun `get is the durable already-handled lookup and outlives the episode row`() =
        runTest {
            feeds.replaceAll(listOf(feed("f")))
            episodes.replaceForFeed("f", listOf(episode("a", "f")))
            ledger.upsert(ledgerRow("a", "f", LedgerState.DOWNLOADED))

            assertEquals(LedgerState.DOWNLOADED, ledger.get("a")?.state)
            assertNull(ledger.get("never-touched"))

            // The feed goes away: the episode cache is pruned, the ledger answer must not change.
            feeds.replaceAll(emptyList())
            assertNull(episodes.get("a"))
            assertEquals(LedgerState.DOWNLOADED, ledger.get("a")?.state)
        }

    @Test
    fun `upsert then getUnsynced returns only rows awaiting the server`() =
        runTest {
            ledger.upsert(ledgerRow("a", "f", LedgerState.DOWNLOADED, syncedToServer = false))
            ledger.upsert(ledgerRow("b", "f", LedgerState.SKIPPED, syncedToServer = true))

            assertEquals(listOf("a"), ledger.getUnsynced().map { it.episodeKey })
        }

    @Test
    fun `markSynced flips the outbox flag so the row stops draining`() =
        runTest {
            ledger.upsert(ledgerRow("a", "f", LedgerState.DOWNLOADED, syncedToServer = false))
            ledger.markSynced(listOf("a"))

            assertTrue(ledger.getUnsynced().isEmpty())
        }

    @Test
    fun `observe DOWNLOADED and SKIPPED select by state, NEW is always empty (rows-typed)`() =
        runTest {
            ledger.upsert(ledgerRow("d", "f", LedgerState.DOWNLOADED))
            ledger.upsert(ledgerRow("s", "f", LedgerState.SKIPPED))

            val downloaded = ledger.observe(LedgerFilter(state = LedgerFilterState.DOWNLOADED)).first()
            val skipped = ledger.observe(LedgerFilter(state = LedgerFilterState.SKIPPED)).first()
            val all = ledger.observe(LedgerFilter(state = LedgerFilterState.ALL)).first()
            val new = ledger.observe(LedgerFilter(state = LedgerFilterState.NEW)).first()

            assertEquals(listOf("d"), downloaded.map { it.episodeKey })
            assertEquals(listOf("s"), skipped.map { it.episodeKey })
            assertEquals(setOf("d", "s"), all.map { it.episodeKey }.toSet())
            assertEquals(emptyList<String>(), new.map { it.episodeKey })
        }

    @Test
    fun `observeEpisodes NEW returns only episodes with no ledger row, past the firstSeenAt cutoff`() =
        runTest {
            feeds.replaceAll(listOf(feed("f", firstSeenAt = 1_000)))
            episodes.replaceForFeed(
                "f",
                listOf(
                    episode("recent", "f", pubDate = 1_500),
                    episode("backlog", "f", pubDate = 500),
                    episode("undated", "f", pubDate = null),
                    episode("handled", "f", pubDate = 2_000),
                ),
            )
            // "handled" has an action, so it is not new.
            ledger.upsert(ledgerRow("handled", "f", LedgerState.DOWNLOADED))

            val new = ledger.observeEpisodes(LedgerFilter(state = LedgerFilterState.NEW)).first()

            assertEquals(setOf("recent", "undated"), new.map { it.episode.episodeKey }.toSet())
            assertTrue("NEW items carry no ledger row", new.all { it.ledger == null })
        }

    @Test
    fun `observeEpisodes NEW with includeBacklog lifts the date cutoff`() =
        runTest {
            feeds.replaceAll(listOf(feed("f", firstSeenAt = 1_000)))
            episodes.replaceForFeed(
                "f",
                listOf(
                    episode("recent", "f", pubDate = 1_500),
                    episode("backlog", "f", pubDate = 500),
                ),
            )

            val archive =
                ledger
                    .observeEpisodes(LedgerFilter(state = LedgerFilterState.NEW, includeBacklog = true))
                    .first()

            assertEquals(setOf("recent", "backlog"), archive.map { it.episode.episodeKey }.toSet())
        }

    @Test
    fun `observeEpisodes DOWNLOADED pairs the episode with its ledger row`() =
        runTest {
            feeds.replaceAll(listOf(feed("f")))
            episodes.replaceForFeed("f", listOf(episode("e", "f", title = "Episode E")))
            ledger.upsert(ledgerRow("e", "f", LedgerState.DOWNLOADED))

            val items = ledger.observeEpisodes(LedgerFilter(state = LedgerFilterState.DOWNLOADED)).first()

            assertEquals(1, items.size)
            assertEquals("Episode E", items.single().episode.title)
            assertEquals(LedgerState.DOWNLOADED, items.single().ledger?.state)
        }

    @Test
    fun `observeEpisodes ALL includes new and actioned episodes together`() =
        runTest {
            feeds.replaceAll(listOf(feed("f")))
            episodes.replaceForFeed("f", listOf(episode("new", "f"), episode("done", "f")))
            ledger.upsert(ledgerRow("done", "f", LedgerState.DOWNLOADED))

            val items = ledger.observeEpisodes(LedgerFilter(state = LedgerFilterState.ALL)).first()

            assertEquals(setOf("new", "done"), items.map { it.episode.episodeKey }.toSet())
            assertNull(items.single { it.episode.episodeKey == "new" }.ledger)
            assertEquals(LedgerState.DOWNLOADED, items.single { it.episode.episodeKey == "done" }.ledger?.state)
        }

    @Test
    fun `observeEpisodes narrows to a single feed when feedUrl is set`() =
        runTest {
            feeds.replaceAll(listOf(feed("f1"), feed("f2")))
            episodes.replaceForFeed("f1", listOf(episode("a", "f1")))
            episodes.replaceForFeed("f2", listOf(episode("b", "f2")))

            val onlyF1 =
                ledger
                    .observeEpisodes(LedgerFilter(state = LedgerFilterState.NEW, feedUrl = "f1"))
                    .first()

            assertEquals(listOf("a"), onlyF1.map { it.episode.episodeKey })
        }
}
