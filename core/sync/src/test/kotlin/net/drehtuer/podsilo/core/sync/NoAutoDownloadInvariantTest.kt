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
 * **Where the other halves live**, now that they exist:
 * - "downloads exactly zero files" — `:core:download`'s `DownloadWorkerTest`, which proves the only
 *   path to a file is an explicit per-episode enqueue that also refuses an already-terminal row.
 * - "a refresh writes `SKIPPED` and never `QUEUED`" — `:core:feed`'s `FeedRefreshWorkerTest`.
 *   `docs/decisions/0013` asked for that assertion *here*, which is not possible: `MarkOldEpisodesRule`
 *   lives in `:core:feed` and an Android-free `:core:sync` cannot see it. It is asserted where the
 *   code is, not where the ADR guessed it would be.
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
                    RecordingLogRepository(),
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
                    RecordingLogRepository(),
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
                        // An ended pair: since 2026-08-14 a PLAY is only "handled elsewhere" when it
                        // reads as finished, so a bare PLAY here would be testing the *unread* shape
                        // and this test would pass for the wrong reason.
                        started = 0,
                        position = 1_800,
                        total = 1_800,
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
                RecordingLogRepository(),
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

    @Test
    fun `no sync path ever writes a QUEUED row`() =
        runBlocking {
            // `docs/decisions/0014` allows bulk download as a *command* and forbids it as a *rule*.
            // QUEUED is the state that causes a file to be fetched, so "sync never writes QUEUED" is
            // the narrow, checkable form of "no rule downloads anything". The tests above assert the
            // stronger property for a fresh list (zero rows at all); this one holds even when there
            // *is* remote traffic to react to.
            val remoteActions =
                (1..REMOTE_ACTION_COUNT).map { index ->
                    EpisodeAction(
                        podcast = "https://example.com/feed-1.xml",
                        episode = "https://example.com/ep-$index.mp3",
                        guid = "guid-$index",
                        action = if (index % 2 == 0) EpisodeActionType.DOWNLOAD else EpisodeActionType.DELETE,
                        timestamp = "2026-07-14T09:00:00",
                    )
                }
            val ledgerRepository = FakeEpisodeLedgerRepository()

            SyncOrchestrator(
                FakeFeedRepository(),
                ledgerRepository,
                FakeSyncStateRepository(),
                FakeGpodderClient(
                    subscriptions = SubscriptionDelta(manyFeedUrls(1), emptyList(), timestamp = 100L),
                    episodeActionsPage = EpisodeActionPage(remoteActions, timestamp = 100L),
                ),
                RecordingLogRepository(),
                fixedClock,
            ).sync()

            assertTrue(
                "a sync pass may mark episodes handled, but must never queue one for download",
                ledgerRepository.allRows.none { it.state == LedgerState.QUEUED },
            )
        }

    private companion object {
        private const val FEED_COUNT = 25
        private const val SYNC_PASSES = 3
        private const val REMOTE_ACTION_COUNT = 500
    }
}
