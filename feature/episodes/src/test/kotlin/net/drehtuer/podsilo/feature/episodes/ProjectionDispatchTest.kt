// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import kotlin.coroutines.CoroutineContext

/**
 * Issue #91: the UI froze during a download, and briefly on every swipe.
 *
 * Neither was the download's doing. `stateIn(viewModelScope)` collects on `Dispatchers.Main`, so
 * every mapping above it — projecting each row, then grouping the whole list into month sections —
 * was main-thread work, re-run on **every** emission of any source it combines. Live byte progress
 * is one of those sources and ticks once a second for the length of a download; a ledger write is
 * another, which is the swipe.
 *
 * Asserting *where* work runs is harder than it looks: a test that only checks "the state is still
 * empty until the scheduler advances" passes with and without the fix, because the fake sources are
 * asynchronous anyway. It was written that way first and proved nothing. What does discriminate is
 * counting dispatches to the injected context — with no `flowOn` the context is simply never used.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectionDispatchTest : EpisodeListTestHarness() {
    /** Counts what is handed to [delegate], which is the whole assertion. */
    private class CountingDispatcher(
        private val delegate: CoroutineDispatcher,
    ) : CoroutineDispatcher() {
        var dispatches = 0
            private set

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            dispatches++
            delegate.dispatch(context, block)
        }
    }

    @Test
    fun `the projection is dispatched to the injected context, not run on the collector`() =
        runTest {
            seed(episode(key = "e1", title = "Warum Hamburg immer regnet"))
            feeds.seedIfAbsent(feed())
            val projection = CountingDispatcher(StandardTestDispatcher(testScheduler))

            val vm =
                EpisodeListViewModel(
                    feedUrl = FEED_URL,
                    feedRepository = feeds,
                    episodeRepository = episodes,
                    listRepository = ledger,
                    settingsRepository = settings,
                    connectivityMonitor = connectivity,
                    triageWriter = TriageWriter(ledger, clock, syncTrigger),
                    scheduler = scheduler,
                    spaceProbe = spaceProbe,
                    folderStatus = folderStatus,
                    workMonitor = workMonitor,
                    logRepository = logs,
                    zone = ZoneOffset.UTC,
                    commitScope = backgroundScope,
                    projectionContext = projection,
                )
            backgroundScope.launch { vm.state.collect { } }
            advanceUntilIdle()

            assertTrue(
                "the projection never reached the injected dispatcher — it ran on the collector",
                projection.dispatches > 0,
            )
            val content = vm.state.value.content
            assertTrue(
                "expected rows once the projection ran, got $content",
                content is EpisodeListUiState.Content.Episodes,
            )
        }
}
