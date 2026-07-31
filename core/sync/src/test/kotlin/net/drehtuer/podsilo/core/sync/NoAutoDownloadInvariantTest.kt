// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.sync

import kotlinx.coroutines.runBlocking
import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.core.model.SyncOutcome
import net.drehtuer.podsilo.core.model.port.EpisodeAction
import net.drehtuer.podsilo.core.model.port.EpisodeActionPage
import net.drehtuer.podsilo.core.model.port.EpisodeActionType
import net.drehtuer.podsilo.core.model.port.SubscriptionDelta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * CLAUDE.md section 7 item 6 -- the no-auto-download invariant. A large freshly-followed
 * subscription list must produce **zero** episode actions and **zero** ledger rows: nothing is
 * downloaded and nothing is written to the shared action log until the author explicitly triages
 * an episode.
 *
 * This is the guard against CLAUDE.md section 11's "a read-only follower can stampede" hazard: the
 * moment subscriptions arrive wholesale from a server rather than being typed in one at a time,
 * any code path that treats "no action recorded" as "should download" would fire thousands of
 * times at once.
 *
 * **What this covers:** the sync half -- that `SyncOrchestrator` is driven entirely by the ledger
 * and never by the episode catalogue, so a backlog of untriaged episodes cannot generate actions.
 * The `subscription_change/create` half is structural (`GpodderClient` has no such method) and is
 * additionally asserted over the wire in `:core:gpodder`'s `RetrofitGpodderClientTest`.
 *
 * **What this cannot cover yet:** "downloads exactly zero files" needs `DownloadWorker`, which is
 * Tier 4b and does not exist. `TODO.md` flags re-running this end-to-end once it does.
 */
class NoAutoDownloadInvariantTest {
    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), ZoneOffset.UTC)

    private fun manyFeedUrls(count: Int) = (1..count).map { "https://example.com/feed-$it.xml" }

    @Test
    fun `a full sync over a large fresh subscription list posts no actions and writes no ledger rows`() =
        runBlocking {
            val feedUrls = manyFeedUrls(FEED_COUNT)
            val feedRepository = FakeFeedRepository()
            val ledgerRepository = FakeEpisodeLedgerRepository() // empty: nothing triaged yet
            val gpodderClient =
                FakeGpodderClient(
                    subscriptions = SubscriptionDelta(add = feedUrls, remove = emptyList(), timestamp = 100L),
                    episodeActionsPage = EpisodeActionPage(actions = emptyList(), timestamp = 100L),
                )

            val outcome =
                SyncOrchestrator(
                    feedRepository,
                    ledgerRepository,
                    FakeSyncStateRepository(),
                    gpodderClient,
                    fixedClock,
                ).sync()

            assertEquals(SyncOutcome.Success, outcome)
            assertEquals("all feeds mirrored", FEED_COUNT, feedRepository.current.size)

            // The two assertions that matter:
            assertEquals("no episode actions may be posted", 0, gpodderClient.postEpisodeActionsCallCount)
            assertTrue("no ledger rows may be created", ledgerRepository.allRows.isEmpty())
        }

    @Test
    fun `repeated syncs of the same untouched backlog stay at zero -- no drift over time`() =
        runBlocking {
            val feedRepository = FakeFeedRepository()
            val ledgerRepository = FakeEpisodeLedgerRepository()
            val syncStateRepository = FakeSyncStateRepository()
            val gpodderClient =
                FakeGpodderClient(
                    subscriptions = SubscriptionDelta(manyFeedUrls(FEED_COUNT), emptyList(), timestamp = 100L),
                    episodeActionsPage = EpisodeActionPage(emptyList(), timestamp = 100L),
                )

            repeat(SYNC_PASSES) {
                SyncOrchestrator(
                    feedRepository,
                    ledgerRepository,
                    syncStateRepository,
                    gpodderClient,
                    fixedClock,
                ).sync()
            }

            assertEquals(0, gpodderClient.postEpisodeActionsCallCount)
            assertTrue(ledgerRepository.allRows.isEmpty())
        }

    @Test
    fun `a large inbound remote action log marks episodes handled, still without posting anything back`() =
        runBlocking {
            // Other clients' actions arriving in bulk must not echo back out as our own posts.
            val remoteActions =
                (1..REMOTE_ACTION_COUNT).map { index ->
                    EpisodeAction(
                        podcast = "https://example.com/feed-1.xml",
                        episode = "https://example.com/ep-$index.mp3",
                        guid = "guid-$index",
                        action = EpisodeActionType.PLAY,
                        timestamp = "2026-07-14T09:00:00",
                    )
                }
            val ledgerRepository = FakeEpisodeLedgerRepository()
            val gpodderClient =
                FakeGpodderClient(
                    subscriptions = SubscriptionDelta(manyFeedUrls(1), emptyList(), timestamp = 100L),
                    episodeActionsPage = EpisodeActionPage(remoteActions, timestamp = 100L),
                )

            SyncOrchestrator(
                FakeFeedRepository(),
                ledgerRepository,
                FakeSyncStateRepository(),
                gpodderClient,
                fixedClock,
            ).sync()

            val noEchoMessage = "inbound actions must not trigger outbound posts"
            assertEquals(noEchoMessage, 0, gpodderClient.postEpisodeActionsCallCount)
            assertEquals(REMOTE_ACTION_COUNT, ledgerRepository.allRows.size)
            assertTrue(
                "every row created from a remote action is terminal and already synced",
                ledgerRepository.allRows.all { it.state == LedgerState.HANDLED_REMOTELY && it.syncedToServer },
            )
        }

    private companion object {
        private const val FEED_COUNT = 25
        private const val SYNC_PASSES = 3
        private const val REMOTE_ACTION_COUNT = 500
    }
}
