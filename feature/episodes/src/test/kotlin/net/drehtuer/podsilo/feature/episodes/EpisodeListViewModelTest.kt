// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.core.model.port.SwipeAction
import net.drehtuer.podsilo.core.model.port.SwipeDirection
import net.drehtuer.podsilo.core.model.port.SwipeMapping
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * S2's behaviour, as a plain object with fakes — no Robolectric, no `WorkManager`, no Compose.
 * That is the payoff of `EpisodeScheduler` being an interface the feature module owns.
 *
 * The cases here are the ones `HANDOVER.md` calls traps: a tap must never triage, a swipe must obey
 * the *configured* mapping, bulk writes must be one transaction, and only an explicit *Download
 * again* may carry the flag that gets past `DownloadWorker`'s terminal-row refusal.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EpisodeListViewModelTest {
    @Before
    fun setUpMain() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDownMain() {
        Dispatchers.resetMain()
    }

    private val ledger = FakeLedgerRepository()
    private val episodes = FakeEpisodeRepository()
    private val feeds = FakeFeedRepository()
    private val settings = FakeSettingsRepository()
    private val connectivity = FakeConnectivityMonitor()
    private val scheduler = RecordingScheduler()
    private val spaceProbe = FakeSpaceProbe()
    private val clock = Clock.fixed(Instant.parse("2026-08-01T12:00:00Z"), ZoneOffset.UTC)

    /**
     * `Dispatchers.setMain(UnconfinedTestDispatcher())` makes `viewModelScope` run eagerly, so an
     * event is fully processed by the time `onEvent` returns — no manual pumping, and the production
     * class needs no injected scope.
     *
     * `state` is `WhileSubscribed`, so tests that assert on it collect it into [backgroundScope],
     * which is the same condition the real screen creates.
     */
    private fun TestScope.viewModel(): EpisodeListViewModel {
        feeds.seed(feed())
        val vm =
            EpisodeListViewModel(
                feedUrl = FEED_URL,
                feedRepository = feeds,
                episodeRepository = episodes,
                ledgerRepository = ledger,
                settingsRepository = settings,
                connectivityMonitor = connectivity,
                triageWriter = TriageWriter(ledger, clock),
                scheduler = scheduler,
                spaceProbe = spaceProbe,
            )
        backgroundScope.launch { vm.state.collect { } }
        return vm
    }

    private fun seed(vararg items: net.drehtuer.podsilo.core.model.Episode) {
        episodes.seed(*items)
        ledger.seed(*items)
    }

    private fun rows(state: EpisodeListUiState): List<EpisodeUi> =
        (state.content as? EpisodeListUiState.Content.Episodes)?.items.orEmpty()

    @Test
    fun `the default filter is To decide, and it means exactly no ledger row`() =
        runTest {
            seed(episode("new"), episode("done"))
            ledger.seedRow(ledgerRow("done", LedgerState.DOWNLOADED))

            val vm = viewModel()
            runCurrent()

            assertEquals(EpisodeFilter.TO_DECIDE, vm.state.value.filter)
            assertEquals(listOf("new"), rows(vm.state.value).map { it.episodeKey })
        }

    @Test
    fun `an episode published long before the feed was first seen still appears`() =
        runTest {
            // docs/decisions/0013 at the screen: the backlog is cleared by writing SKIPPED rows, never
            // by hiding rows at read time. A date filter reappearing here would be invisible in the UI.
            seed(episode("ancient", pubDate = 0))

            val vm = viewModel()
            runCurrent()

            assertEquals(listOf("ancient"), rows(vm.state.value).map { it.episodeKey })
        }

    @Test
    fun `tapping a row opens detail and writes nothing`() =
        runTest {
            seed(episode("e1"))
            val vm = viewModel()
            runCurrent()

            vm.onEvent(EpisodeListEvent.RowClicked("e1"))
            runCurrent()

            assertTrue("a mis-tap must never queue a download", ledger.writes.isEmpty())
            assertTrue(scheduler.downloads.isEmpty())
        }

    @Test
    fun `downloading writes QUEUED unsynced and enqueues, without the user-requested flag`() =
        runTest {
            seed(episode("e1"))
            val vm = viewModel()
            runCurrent()

            vm.onEvent(EpisodeListEvent.Triage("e1", EpisodeUiAction.DOWNLOAD))
            runCurrent()

            val written = ledger.writes.flatten().single()
            assertEquals(LedgerState.QUEUED, written.state)
            assertFalse("a local write is always unsynced until a confirmed 2xx", written.syncedToServer)
            assertEquals(listOf("e1" to false), scheduler.downloads)
        }

    @Test
    fun `only Download again carries the flag that passes the terminal-row refusal`() =
        runTest {
            // The UI half of docs/decisions/0012. If a plain Download ever set this, the
            // no-auto-download invariant would stop being provable from one grep.
            seed(episode("e1"))
            ledger.seedRow(ledgerRow("e1", LedgerState.DOWNLOADED, writtenFileName = "old.mp3"))
            val vm = viewModel()
            runCurrent()

            vm.onEvent(EpisodeListEvent.Triage("e1", EpisodeUiAction.DOWNLOAD_AGAIN))
            runCurrent()

            assertEquals(listOf("e1" to true), scheduler.downloads)
        }

    @Test
    fun `a re-decision resets attempts and error but keeps the written file name`() =
        runTest {
            // Keeping writtenFileName is what arms the duplicate guard; losing it here would let a
            // later re-download write a second copy (docs/decisions/0012 §3a).
            seed(episode("e1"))
            ledger.seedRow(
                ledgerRow("e1", LedgerState.DOWNLOADED, writtenFileName = "kept.mp3", attempts = 4, lastError = "boom"),
            )
            val vm = viewModel()
            runCurrent()

            vm.onEvent(EpisodeListEvent.Triage("e1", EpisodeUiAction.DOWNLOAD_AGAIN))
            runCurrent()

            val written = ledger.writes.flatten().single()
            assertEquals(0, written.attempts)
            assertNull(written.lastError)
            assertEquals("kept.mp3", written.writtenFileName)
        }

    @Test
    fun `marking a downloaded episode as played also keeps the written file name`() =
        runTest {
            seed(episode("e1"))
            ledger.seedRow(ledgerRow("e1", LedgerState.DOWNLOADED, writtenFileName = "kept.mp3"))
            val vm = viewModel()
            runCurrent()

            vm.onEvent(EpisodeListEvent.Triage("e1", EpisodeUiAction.MARK_AS_PLAYED))
            runCurrent()

            val written = ledger.writes.flatten().single()
            assertEquals(LedgerState.SKIPPED, written.state)
            assertEquals("kept.mp3", written.writtenFileName)
            assertTrue("marking as played never enqueues anything", scheduler.downloads.isEmpty())
        }

    @Test
    fun `a swipe performs the configured action, not a hard-coded one`() =
        runTest {
            // The swipe background renders from the same mapping, so this is what stops the UI
            // advertising one verb and performing another (docs/UI.md §12.1).
            settings.swipeMapping = SwipeMapping(right = SwipeAction.MARK_AS_PLAYED, left = SwipeAction.DOWNLOAD)
            seed(episode("e1"))
            val vm = viewModel()
            runCurrent()

            vm.onEvent(EpisodeListEvent.SwipeCommitted("e1", SwipeDirection.RIGHT))
            runCurrent()

            assertEquals(
                LedgerState.SKIPPED,
                ledger.writes
                    .flatten()
                    .single()
                    .state,
            )
        }

    @Test
    fun `a disabled swipe direction does nothing at all`() =
        runTest {
            settings.swipeMapping = SwipeMapping(right = SwipeAction.NONE, left = SwipeAction.MARK_AS_PLAYED)
            seed(episode("e1"))
            val vm = viewModel()
            runCurrent()

            vm.onEvent(EpisodeListEvent.SwipeCommitted("e1", SwipeDirection.RIGHT))
            runCurrent()

            assertTrue(ledger.writes.isEmpty())
        }

    @Test
    fun `a bulk action is one write, not one per episode`() =
        runTest {
            // 412 upserts would be 412 transactions and 412 list emissions into a LazyColumn
            // (HANDOVER trap 7).
            val keys = (1..50).map { "e$it" }
            seed(*keys.map { episode(it) }.toTypedArray())
            val vm = viewModel()
            runCurrent()

            vm.onEvent(EpisodeListEvent.BulkConfirmed(EpisodeUiAction.MARK_AS_PLAYED, keys.toSet()))
            runCurrent()

            assertEquals("one batched write", 1, ledger.writes.size)
            assertEquals(50, ledger.writes.single().size)
        }

    @Test
    fun `acting on a selection leaves selection mode`() =
        runTest {
            seed(episode("e1"), episode("e2"))
            val vm = viewModel()
            runCurrent()

            vm.onEvent(EpisodeListEvent.SelectionStarted("e1"))
            runCurrent()
            assertTrue(vm.state.value.inSelectionMode)

            vm.onEvent(EpisodeListEvent.BulkConfirmed(EpisodeUiAction.MARK_AS_PLAYED, setOf("e1")))
            runCurrent()

            assertFalse(vm.state.value.inSelectionMode)
        }

    @Test
    fun `deselecting the last row leaves selection mode rather than showing zero selected`() =
        runTest {
            seed(episode("e1"))
            val vm = viewModel()
            runCurrent()

            vm.onEvent(EpisodeListEvent.SelectionStarted("e1"))
            runCurrent()
            vm.onEvent(EpisodeListEvent.SelectionToggled("e1"))
            runCurrent()

            assertNull(vm.state.value.selection)
        }

    @Test
    fun `changing the filter drops the selection`() =
        runTest {
            // Acting on rows the user can no longer see is the accidental bulk action §14.2 warns of.
            seed(episode("e1"))
            val vm = viewModel()
            runCurrent()

            vm.onEvent(EpisodeListEvent.SelectionStarted("e1"))
            runCurrent()
            vm.onEvent(EpisodeListEvent.FilterChanged(EpisodeFilter.ALL))
            runCurrent()

            assertNull(vm.state.value.selection)
        }

    @Test
    fun `Download all produces a preview and writes absolutely nothing`() =
        runTest {
            // The regression guard for the bug this replaced: it used to emit a "Queued (n)" snackbar
            // without queueing anything, which is worse than not implementing it. docs/decisions/0014
            // makes naming the count *before* writing the whole safeguard.
            seed(episode("a"), episode("b"), episode("c"))
            ledger.seedRow(ledgerRow("c", LedgerState.DOWNLOADED))
            val vm = viewModel()
            runCurrent()

            vm.onEvent(EpisodeListEvent.DownloadAllRequested)
            runCurrent()

            val preview = checkNotNull(vm.state.value.pendingBulk)
            assertEquals(2, preview.count)
            assertTrue("nothing may be written before confirmation", ledger.writes.isEmpty())
            assertTrue(scheduler.downloads.isEmpty())
        }

    @Test
    fun `confirming the preview is what writes, and dismissing it writes nothing`() =
        runTest {
            seed(episode("a"), episode("b"))
            val vm = viewModel()
            runCurrent()

            vm.onEvent(EpisodeListEvent.DownloadAllRequested)
            runCurrent()
            vm.onEvent(EpisodeListEvent.DownloadAllDismissed)
            runCurrent()
            assertNull(vm.state.value.pendingBulk)
            assertTrue("dismissing is a decision not to act", ledger.writes.isEmpty())

            vm.onEvent(EpisodeListEvent.DownloadAllRequested)
            runCurrent()
            vm.onEvent(EpisodeListEvent.DownloadAllConfirmed(listOf("a", "b")))
            runCurrent()

            assertNull(vm.state.value.pendingBulk)
            assertEquals(2, ledger.writes.flatten().size)
            assertEquals(2, scheduler.downloads.size)
        }

    @Test
    fun `an unknown duration makes the size estimate absent rather than understated`() =
        runTest {
            // A partial estimate would read as "it fits" when it might not; itunes:duration is too
            // unreliable for a number that looks authoritative.
            spaceProbe.freeBytes = 1_000_000_000
            seed(episode("known"), episode("unknown", durationMs = null))
            val vm = viewModel()
            runCurrent()

            vm.onEvent(EpisodeListEvent.DownloadAllRequested)
            runCurrent()

            val preview = checkNotNull(vm.state.value.pendingBulk)
            assertNull(preview.estimatedBytes)
            assertFalse("no estimate means no warning", preview.exceedsFreeSpace)
        }

    @Test
    fun `the size warning appears only when the estimate exceeds free space`() =
        runTest {
            // 30 minutes at the assumed bitrate is roughly 29 MB, so 1 MB free must warn and 1 GB not.
            seed(episode("a"))
            spaceProbe.freeBytes = 1_000_000
            val tight = viewModel()
            runCurrent()
            tight.onEvent(EpisodeListEvent.DownloadAllRequested)
            runCurrent()
            assertTrue(checkNotNull(tight.state.value.pendingBulk).exceedsFreeSpace)
        }

    @Test
    fun `isRefreshing stays true for as long as the refresh runs`() =
        runTest {
            // It used to be set and cleared on consecutive lines around a synchronous enqueue, so the
            // indicator could never appear at all.
            seed(episode("e1"))
            scheduler.inFlightRefresh = CompletableDeferred()
            val vm = viewModel()
            runCurrent()

            vm.onEvent(EpisodeListEvent.PullToRefresh)
            runCurrent()
            assertTrue("the indicator must be visible while the work runs", vm.state.value.isRefreshing)

            scheduler.completeRefresh()
            runCurrent()
            assertFalse(vm.state.value.isRefreshing)
        }

    @Test
    fun `an offline pull-to-refresh reports immediately and schedules nothing`() =
        runTest {
            connectivity.online = false
            seed(episode("e1"))
            val vm = viewModel()
            runCurrent()

            vm.onEvent(EpisodeListEvent.PullToRefresh)
            runCurrent()

            assertTrue("no request is attempted at all", scheduler.refreshes.isEmpty())
        }

    @Test
    fun `a pull-to-refresh is scoped to this feed, not to all of them`() =
        runTest {
            seed(episode("e1"))
            val vm = viewModel()
            runCurrent()

            vm.onEvent(EpisodeListEvent.PullToRefresh)
            runCurrent()

            assertEquals(listOf(FEED_URL), scheduler.refreshes)
        }

    @Test
    fun `the Download all count is the undecided count, and is zero on other filters`() =
        runTest {
            seed(episode("a"), episode("b"), episode("c"))
            ledger.seedRow(ledgerRow("c", LedgerState.DOWNLOADED))
            val vm = viewModel()
            runCurrent()

            assertEquals(2, vm.state.value.downloadAllCount)

            vm.onEvent(EpisodeListEvent.FilterChanged(EpisodeFilter.ALL))
            runCurrent()

            assertEquals(
                "'Download all' is meaningless outside the to-decide filter",
                0,
                vm.state.value.downloadAllCount,
            )
        }

    @Test
    fun `an empty filter renders Empty, not an empty Episodes list`() =
        runTest {
            seed(episode("e1"))
            ledger.seedRow(ledgerRow("e1", LedgerState.DOWNLOADED))
            val vm = viewModel()
            runCurrent()

            // "nothing to decide" and "not loaded yet" must be distinguishable states, which is the
            // whole reason Content is sealed rather than a list plus an isLoading flag.
            assertTrue(vm.state.value.content is EpisodeListUiState.Content.Empty)
        }

    @Test
    fun `cancelling a queued download cancels the work and writes no ledger row`() =
        runTest {
            seed(episode("e1"))
            ledger.seedRow(ledgerRow("e1", LedgerState.QUEUED))
            val vm = viewModel()
            runCurrent()

            vm.onEvent(EpisodeListEvent.Triage("e1", EpisodeUiAction.CANCEL))
            runCurrent()

            assertEquals(listOf("e1"), scheduler.cancellations)
            assertTrue(ledger.writes.isEmpty())
        }
}
