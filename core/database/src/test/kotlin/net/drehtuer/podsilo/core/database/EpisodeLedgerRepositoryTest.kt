// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.database

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.drehtuer.podsilo.core.database.repository.EpisodeLedgerRepositoryImpl
import net.drehtuer.podsilo.core.database.repository.EpisodeListRepositoryImpl
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
    private val list by lazy { EpisodeListRepositoryImpl(db.episodeListDao()) }

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

    /**
     * `ErrorCause` is a **persisted vocabulary**, so removing a value has to be safe against a row
     * that already holds it. `FEED_PARSE` and `TAG_WRITE` were removed on 2026-08-14 having never
     * been produced by anything, but "nothing ever wrote it" is an argument about today's code and
     * this is the property that makes the change safe regardless: an unrecognised name reads back as
     * `null`, which the UI already renders as `UNKNOWN`. A `valueOf` here would instead throw while
     * mapping a row, taking out the whole list rather than one field.
     */
    @Test
    fun `a lastErrorCause the enum no longer has reads back as null rather than throwing`() =
        runTest {
            ledger.upsert(ledgerRow("a", "f", LedgerState.ERROR))
            // Written straight to the column: the domain type cannot express a value it no longer has.
            db.openHelper.writableDatabase.execSQL(
                "UPDATE episode_ledger SET lastErrorCause = 'TAG_WRITE' WHERE episodeKey = 'a'",
            )

            val row = ledger.get("a")

            assertEquals(LedgerState.ERROR, row?.state)
            assertNull("an unknown cause degrades to null, it does not fail the read", row?.lastErrorCause)
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
    fun `observeEpisodes NEW returns exactly the episodes with no ledger row`() =
        runTest {
            feeds.replaceAll(listOf(feed("f", firstSeenAt = 1_000)))
            episodes.replaceForFeed(
                "f",
                listOf(
                    episode("recent", "f", pubDate = 1_500),
                    episode("undated", "f", pubDate = null),
                    episode("handled", "f", pubDate = 2_000),
                ),
            )
            // "handled" has an action, so it is not new.
            ledger.upsert(ledgerRow("handled", "f", LedgerState.DOWNLOADED))

            val new = list.observeEpisodes(LedgerFilter(state = LedgerFilterState.NEW)).first()

            assertEquals(setOf("recent", "undated"), new.map { it.episode.episodeKey }.toSet())
            assertTrue("NEW items carry no ledger row", new.all { it.ledger == null })
        }

    /**
     * `decisions/0024`. The SQL is the half the compiler cannot check: adding `UNPLAYED` to the
     * enum compiles everywhere without this, and the feature silently does nothing.
     *
     * The row is still there — that is the entire design — so this asserts both halves: the episode
     * is offered for triage again, **and** the ledger has not forgotten it.
     */
    @Test
    fun `observeEpisodes NEW includes an UNPLAYED episode, whose row still exists`() =
        runTest {
            feeds.replaceAll(listOf(feed("f", firstSeenAt = 1_000)))
            episodes.replaceForFeed("f", listOf(episode("withdrawn", "f", pubDate = 2_000)))
            ledger.upsert(ledgerRow("withdrawn", "f", LedgerState.SKIPPED))

            ledger.upsert(ledgerRow("withdrawn", "f", LedgerState.UNPLAYED))

            val new = list.observeEpisodes(LedgerFilter(state = LedgerFilterState.NEW)).first()
            assertEquals(listOf("withdrawn"), new.map { it.episode.episodeKey })
            assertEquals(LedgerState.UNPLAYED, ledger.get("withdrawn")?.state)
        }

    /** And the count badge has to agree with the list it opens, or S1 lies about the work left. */
    @Test
    fun `an UNPLAYED episode is counted as undecided`() =
        runTest {
            feeds.replaceAll(listOf(feed("f", firstSeenAt = 1_000)))
            episodes.replaceForFeed("f", listOf(episode("withdrawn", "f", pubDate = 2_000)))
            ledger.upsert(ledgerRow("withdrawn", "f", LedgerState.UNPLAYED))

            val counts = list.observeUndecidedCounts().first()

            assertEquals(1, counts.single().count)
        }

    @Test
    fun `observeEpisodes NEW does not apply a firstSeenAt cutoff`() =
        runTest {
            // The regression guard for decisions/0013. An episode published long before its feed
            // was first seen is still undecided, and must appear — the backlog is cleared by writing
            // SKIPPED rows, never by hiding rows at read time. If a date clause ever comes back, this
            // fails and the two mechanisms cannot silently coexist.
            feeds.replaceAll(listOf(feed("f", firstSeenAt = 1_000)))
            episodes.replaceForFeed(
                "f",
                listOf(
                    episode("recent", "f", pubDate = 1_500),
                    episode("backlog", "f", pubDate = 500),
                ),
            )

            val new = list.observeEpisodes(LedgerFilter(state = LedgerFilterState.NEW)).first()

            assertEquals(setOf("recent", "backlog"), new.map { it.episode.episodeKey }.toSet())
        }

    @Test
    fun `observeEpisodes DOWNLOADED pairs the episode with its ledger row`() =
        runTest {
            feeds.replaceAll(listOf(feed("f")))
            episodes.replaceForFeed("f", listOf(episode("e", "f", title = "Episode E")))
            ledger.upsert(ledgerRow("e", "f", LedgerState.DOWNLOADED))

            val items = list.observeEpisodes(LedgerFilter(state = LedgerFilterState.DOWNLOADED)).first()

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

            val items = list.observeEpisodes(LedgerFilter(state = LedgerFilterState.ALL)).first()

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
                list
                    .observeEpisodes(LedgerFilter(state = LedgerFilterState.NEW, feedUrl = "f1"))
                    .first()

            assertEquals(listOf("a"), onlyF1.map { it.episode.episodeKey })
        }
}
