// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import net.drehtuer.podsilo.core.model.Episode
import org.junit.After
import org.junit.Before
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * S2's fakes and its view-model builder, shared by the test classes that drive it.
 *
 * Extracted when detekt flagged `EpisodeListViewModelTest` as too large after issue #49's undo tests
 * landed. The split it asked for is a real one — the undo window is its own behaviour with its own
 * timing — and duplicating thirty lines of fake wiring into the second class would have been the
 * worse answer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class EpisodeListTestHarness {
    @Before
    fun setUpMain() {
        // `runTest` reuses this scheduler, so virtual time advanced in a test also advances the
        // `delay` inside `viewModelScope` — which is what makes the undo window testable at all.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDownMain() {
        Dispatchers.resetMain()
    }

    protected val ledger = FakeLedgerRepository()
    protected val episodes = FakeEpisodeRepository()
    protected val feeds = FakeFeedRepository()
    protected val settings = FakeSettingsRepository()
    protected val connectivity = FakeConnectivityMonitor()
    protected val scheduler = RecordingScheduler()
    protected val workMonitor = FakeDownloadWorkMonitor()
    protected val spaceProbe = FakeSpaceProbe()
    protected val folderStatus = FakeFolderStatus()
    protected val logs = FakeLogRepository()
    protected val clock: Clock = Clock.fixed(Instant.parse("2026-08-01T12:00:00Z"), ZoneOffset.UTC)

    /**
     * `Dispatchers.setMain(UnconfinedTestDispatcher())` makes `viewModelScope` run eagerly, so an
     * event is fully processed by the time `onEvent` returns — no manual pumping, and the production
     * class needs no injected scope.
     *
     * `state` is `WhileSubscribed`, so this collects it into `backgroundScope`, which is the same
     * condition the real screen creates.
     */
    protected fun TestScope.viewModel(filter: EpisodeFilter? = null): EpisodeListViewModel {
        // Only if the test has not supplied its own: several care about `lastRefreshedAt`.
        feeds.seedIfAbsent(feed())
        val vm =
            EpisodeListViewModel(
                feedUrl = FEED_URL,
                feedRepository = feeds,
                episodeRepository = episodes,
                listRepository = ledger,
                settingsRepository = settings,
                connectivityMonitor = connectivity,
                triageWriter = TriageWriter(ledger, clock),
                scheduler = scheduler,
                spaceProbe = spaceProbe,
                folderStatus = folderStatus,
                workMonitor = workMonitor,
                logRepository = logs,
                zone = ZoneOffset.UTC,
                // onCleared runs after viewModelScope is cancelled, so the commit needs a scope that
                // outlives the view model. backgroundScope is the test-owned equivalent.
                commitScope = backgroundScope,
            )
        backgroundScope.launch { vm.state.collect { } }
        // A row that already carries a ledger state is invisible under the default "To decide"
        // filter by definition, so tests about decided rows have to ask for a filter that shows them.
        filter?.let { vm.onEvent(EpisodeListEvent.FilterChanged(it)) }
        return vm
    }

    protected fun seed(vararg items: Episode) {
        episodes.seed(*items)
        ledger.seed(*items)
    }

    protected fun rows(state: EpisodeListUiState): List<EpisodeUi> =
        (state.content as? EpisodeListUiState.Content.Episodes)?.items.orEmpty()
}

/** Mirrors `EpisodeListViewModel`'s own window, so tests move virtual time by the real amount. */
internal const val UNDO_WINDOW_FOR_TEST = 5_000L

/**
 * Clears a view model through a real [androidx.lifecycle.ViewModelStore], which is the only way to
 * reach the `protected` `onCleared` — and the point is to exercise the real lifecycle hook rather
 * than add a test-only method to production code for the purpose.
 */
internal fun EpisodeListViewModel.clearForTest() {
    val store = androidx.lifecycle.ViewModelStore()
    androidx.lifecycle.ViewModelProvider(
        store,
        object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = this@clearForTest as T
        },
    )[EpisodeListViewModel::class.java]
    store.clear()
}

/**
 * A pull comfortably past the refresh threshold, independent of any row's height.
 *
 * `swipeDown()`'s default travels from a node's top to its bottom, which made two pull-to-refresh
 * tests depend on how tall a row happened to be — they broke the moment the row's buttons moved
 * into its overflow. Row height is not what those tests are about.
 */
internal const val PULL_DISTANCE_PX = 1_000f
