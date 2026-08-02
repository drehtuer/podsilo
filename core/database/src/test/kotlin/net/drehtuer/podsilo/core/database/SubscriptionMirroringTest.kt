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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CLAUDE.md §5/§7 item 7: the subscription mirror is one-way, and the ledger must outlive the feed.
 * When a feed disappears from the server list its cached episodes are pruned but its ledger rows
 * are kept, so a later re-subscribe does not re-expose the back catalogue as "new" and does not
 * re-download it. The whole no-cascade-FK design of the ledger table (`docs/architecture.md` §4)
 * exists to make this test pass.
 */
class SubscriptionMirroringTest : RoomTestBase() {
    private val feeds by lazy { FeedRepositoryImpl(db.feedDao()) }
    private val episodes by lazy { EpisodeRepositoryImpl(db.episodeDao()) }
    private val ledger by lazy { EpisodeLedgerRepositoryImpl(db.episodeLedgerDao()) }
    private val list by lazy { EpisodeListRepositoryImpl(db.episodeListDao()) }

    @Test
    fun `feed removed then re-added keeps the ledger, so the episode never returns as new`() =
        runTest {
            // Subscribe, cache an episode, and mark it downloaded.
            feeds.replaceAll(listOf(feed("f", firstSeenAt = 0)))
            episodes.replaceForFeed("f", listOf(episode("e", "f", pubDate = 100)))
            ledger.upsert(ledgerRow("e", "f", LedgerState.DOWNLOADED, syncedToServer = true))

            // Feed disappears from the server list: episodes pruned, ledger kept.
            feeds.replaceAll(emptyList())
            assertEquals(emptyList<String>(), episodes.observeForFeed("f").first().map { it.episodeKey })
            assertEquals(listOf("e"), db.episodeLedgerDao().getAll().map { it.episodeKey })

            // Re-subscribe and re-parse the same feed.
            feeds.replaceAll(listOf(feed("f", firstSeenAt = 0)))
            episodes.replaceForFeed("f", listOf(episode("e", "f", pubDate = 100)))

            // The episode must NOT show up as new — its ledger row still marks it handled.
            val new = list.observeEpisodes(LedgerFilter(state = LedgerFilterState.NEW)).first()
            assertTrue("re-subscribed episode must not reappear as new", new.none { it.episode.episodeKey == "e" })

            val downloaded = list.observeEpisodes(LedgerFilter(state = LedgerFilterState.DOWNLOADED)).first()
            assertEquals(listOf("e"), downloaded.map { it.episode.episodeKey })
        }
}
