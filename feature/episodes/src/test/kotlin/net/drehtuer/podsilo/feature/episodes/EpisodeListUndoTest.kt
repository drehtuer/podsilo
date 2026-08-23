// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.core.model.port.SwipeAction
import net.drehtuer.podsilo.core.model.port.SwipeDirection
import net.drehtuer.podsilo.core.model.port.SwipeMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The undo window (issue #49, `docs/UI.adoc` §12.3).
 *
 * Its own class because the behaviour is its own: everything here is about *when* a decision becomes
 * durable, and every test moves virtual time. The rest of S2's behaviour lives in
 * `EpisodeListViewModelTest`.
 *
 * The property under test throughout is the one the ADR turns on: **inside the window, nothing is
 * written anywhere**. A skip becomes a `PLAY` action in an append-only log that other clients act on
 * and the GPodder API cannot retract, so the only reliably reversible state is one where the row was
 * never written at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EpisodeListUndoTest : EpisodeListTestHarness() {
    /**
     * The heart of UI.adoc §12.3. A swipe writes **nothing** until its window elapses — not a ledger row,
     * not an outbox entry, no work. That is what makes undo honest: a skip becomes a `PLAY` action
     * in an append-only log with no retraction, so the only reliably reversible state is one where
     * the row was never written.
     */
    @Test
    fun `a swipe writes nothing until the undo window elapses`() =
        runTest {
            seed(episode("e1"))
            settings.swipeMapping = SwipeMapping(right = SwipeAction.MARK_AS_PLAYED)
            val vm = viewModel()
            runCurrent()

            vm.onEvent(EpisodeListEvent.SwipeCommitted("e1", SwipeDirection.RIGHT))
            runCurrent()

            assertTrue("nothing may be written inside the window", ledger.writes.isEmpty())
            assertEquals(PendingUndo("e1", EpisodeUiAction.MARK_AS_PLAYED), vm.state.value.pendingUndo)

            advanceTimeBy(UNDO_WINDOW_FOR_TEST + 1)
            runCurrent()

            assertEquals(
                LedgerState.SKIPPED,
                ledger.writes
                    .flatten()
                    .single()
                    .state,
            )
            assertNull(vm.state.value.pendingUndo)
        }

    @Test
    fun `undo inside the window writes nothing and enqueues nothing, ever`() =
        runTest {
            seed(episode("e1"))
            settings.swipeMapping = SwipeMapping(right = SwipeAction.DOWNLOAD)
            val vm = viewModel()
            runCurrent()

            vm.onEvent(EpisodeListEvent.SwipeCommitted("e1", SwipeDirection.RIGHT))
            runCurrent()
            vm.onEvent(EpisodeListEvent.UndoRequested)
            runCurrent()

            assertNull(vm.state.value.pendingUndo)

            // And it stays undone: the timer must not fire behind the undo.
            advanceTimeBy(UNDO_WINDOW_FOR_TEST * 2)
            runCurrent()

            assertTrue("an undone decision must never be written", ledger.writes.isEmpty())
            assertTrue("and never enqueued", scheduler.downloads.isEmpty())
        }

    /**
     * The row shows the decision immediately even though nothing is stored, because a swipe that
     * appeared to do nothing for five seconds would read as the app ignoring it.
     */
    @Test
    fun `the row renders the pending decision without anything being stored`() =
        runTest {
            seed(episode("e1"))
            settings.swipeMapping = SwipeMapping(right = SwipeAction.MARK_AS_PLAYED)
            val vm = viewModel()
            runCurrent()

            vm.onEvent(EpisodeListEvent.SwipeCommitted("e1", SwipeDirection.RIGHT))
            runCurrent()

            assertEquals(LedgerState.SKIPPED, rows(vm.state.value).single().ledgerState)
            assertTrue(ledger.writes.isEmpty())
        }

    /** One pending decision at a time: a second swipe commits the first rather than losing it. */
    @Test
    fun `a second swipe commits the first decision`() =
        runTest {
            seed(episode("e1"), episode("e2"))
            settings.swipeMapping = SwipeMapping(right = SwipeAction.MARK_AS_PLAYED)
            val vm = viewModel()
            runCurrent()

            vm.onEvent(EpisodeListEvent.SwipeCommitted("e1", SwipeDirection.RIGHT))
            runCurrent()
            vm.onEvent(EpisodeListEvent.SwipeCommitted("e2", SwipeDirection.RIGHT))
            runCurrent()

            assertEquals(listOf("e1"), ledger.writes.flatten().map { it.episodeKey })
            assertEquals(
                "e2",
                vm.state.value.pendingUndo
                    ?.episodeKey,
            )
        }

    /**
     * Leaving the screen commits. Silently dropping a decision the user made and watched take effect
     * is worse than committing one they might have wanted back — they can still act again.
     */
    @Test
    fun `leaving the screen commits a decision still inside its window`() =
        runTest {
            seed(episode("e1"))
            settings.swipeMapping = SwipeMapping(right = SwipeAction.MARK_AS_PLAYED)
            val vm = viewModel()
            runCurrent()
            vm.onEvent(EpisodeListEvent.SwipeCommitted("e1", SwipeDirection.RIGHT))
            runCurrent()
            assertTrue(ledger.writes.isEmpty())

            vm.clearForTest()
            runCurrent()

            assertEquals(
                LedgerState.SKIPPED,
                ledger.writes
                    .flatten()
                    .single()
                    .state,
            )
        }

    /** An undo that arrives after the write finds nothing to discard, rather than racing it. */
    @Test
    fun `an undo after the window has closed is ignored`() =
        runTest {
            seed(episode("e1"))
            settings.swipeMapping = SwipeMapping(right = SwipeAction.MARK_AS_PLAYED)
            val vm = viewModel()
            runCurrent()
            vm.onEvent(EpisodeListEvent.SwipeCommitted("e1", SwipeDirection.RIGHT))
            advanceTimeBy(UNDO_WINDOW_FOR_TEST + 1)
            runCurrent()

            vm.onEvent(EpisodeListEvent.UndoRequested)
            runCurrent()

            assertEquals("the write stands", 1, ledger.writes.size)
            assertEquals(
                LedgerState.SKIPPED,
                ledger.writes
                    .flatten()
                    .single()
                    .state,
            )
        }

    /** D2: bulk actions keep their confirmations and gain no undo. */
    @Test
    fun `a bulk action still writes immediately and offers no undo`() =
        runTest {
            seed(episode("e1"), episode("e2"))
            val vm = viewModel()
            runCurrent()

            vm.onEvent(EpisodeListEvent.BulkConfirmed(EpisodeUiAction.MARK_AS_PLAYED, setOf("e1", "e2")))
            runCurrent()

            assertEquals(1, ledger.writes.size)
            assertNull(vm.state.value.pendingUndo)
        }
}
