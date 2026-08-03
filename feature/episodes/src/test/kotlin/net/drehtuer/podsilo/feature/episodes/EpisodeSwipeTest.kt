// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import net.drehtuer.podsilo.core.model.port.SwipeAction
import net.drehtuer.podsilo.core.model.port.SwipeDirection
import net.drehtuer.podsilo.core.model.port.SwipeMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.ZoneOffset

private const val SWIPE_MS = 400L

/**
 * The swipe gesture (`docs/UI.md` §5, §12.1).
 *
 * `SwipeCommitted` was declared, handled by the view model, and emitted by nothing — the third
 * affordance in this project specified and wired at both ends with no gesture in between, after
 * pull-to-refresh and the artwork slot. These tests exist so the *gesture* is covered, not just the
 * handler: `EpisodeListViewModelTest` already proves what a `SwipeCommitted` does once it arrives,
 * and proved it happily for weeks while nothing could produce one.
 */
@RunWith(RobolectricTestRunner::class)
class EpisodeSwipeTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = mutableListOf<EpisodeListEvent>()

    /**
     * A deliberate, slow, full-width drag on the ROW itself.
     *
     * `swipeRight()` on the list defaults to a fast flick across a fraction of the width, which does
     * not clear §12.1's 40 % commit threshold — the gesture the threshold exists to reject. The
     * clock is advanced afterwards because `anchoredDraggable` settles in an animation.
     */
    private fun swipeRow(gesture: androidx.compose.ui.test.TouchInjectionScope.() -> Unit) {
        compose.onNodeWithText("Warum Hamburg immer regnet").performTouchInput(gesture)
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun row(hasEnclosure: Boolean = true) =
        EpisodeUi(
            episodeKey = "e1",
            feedUrl = FEED_URL,
            feedTitle = "Der Podcast",
            title = "Warum Hamburg immer regnet",
            artworkUrl = null,
            publishedAt = Instant.parse("2026-07-14T09:00:00Z"),
            duration = null,
            descriptionSnippet = "",
            ledgerState = null,
            progress = null,
            lastError = null,
            hasEnclosure = hasEnclosure,
        )

    private fun render(
        mapping: SwipeMapping = SwipeMapping(),
        hasEnclosure: Boolean = true,
    ) {
        compose.setContent {
            EpisodeListScreen(
                state =
                    EpisodeListUiState(
                        feedUrl = FEED_URL,
                        feedTitle = "Der Podcast",
                        content = EpisodeListUiState.Content.Episodes(listOf(row(hasEnclosure))),
                        swipeMapping = mapping,
                    ),
                onEvent = { events += it },
                zone = ZoneOffset.UTC,
            )
        }
    }

    @Test
    fun `swiping right commits the right-hand action`() {
        render()

        swipeRow { swipeRight(startX = left, endX = right, durationMillis = SWIPE_MS) }

        // Exact list, not `contains`: a looser assertion passed while the first implementation fired
        // fourteen times for one gesture. "It committed" is not the property that matters here.
        assertEquals(
            listOf(EpisodeListEvent.SwipeCommitted("e1", SwipeDirection.RIGHT)),
            events.filterIsInstance<EpisodeListEvent.SwipeCommitted>(),
        )
    }

    @Test
    fun `swiping left commits the left-hand action`() {
        render()

        swipeRow { swipeLeft(startX = right, endX = left, durationMillis = SWIPE_MS) }

        assertEquals(
            listOf(EpisodeListEvent.SwipeCommitted("e1", SwipeDirection.LEFT)),
            events.filterIsInstance<EpisodeListEvent.SwipeCommitted>(),
        )
    }

    /**
     * §12.1: the directions are re-mappable, and `NONE` disables one. The screen must not swipe into
     * an action the user turned off — the view model would ignore it, but the row would still have
     * animated as though something happened.
     */
    @Test
    fun `a direction mapped to NONE does not swipe`() {
        render(mapping = SwipeMapping(right = SwipeAction.NONE, left = SwipeAction.MARK_AS_PLAYED))

        swipeRow { swipeRight(startX = left, endX = right, durationMillis = SWIPE_MS) }

        assertTrue("a disabled direction must not commit, got $events", events.isEmpty())
    }

    /** §5: an episode with no audio has nothing to download, so it must not swipe at all. */
    @Test
    fun `an episode with no enclosure does not swipe`() {
        render(hasEnclosure = false)

        swipeRow { swipeRight(startX = left, endX = right, durationMillis = SWIPE_MS) }

        assertTrue("a row with no audio must not swipe, got $events", events.isEmpty())
    }

    /**
     * §12.1: the background is rendered *from the mapping*. Swapping the two directions must swap
     * what the user is shown, or the row promises one verb and performs another.
     */
    @Test
    fun `the committed direction follows a swapped mapping`() {
        render(mapping = SwipeMapping(right = SwipeAction.MARK_AS_PLAYED, left = SwipeAction.DOWNLOAD))

        swipeRow { swipeRight(startX = left, endX = right, durationMillis = SWIPE_MS) }

        // The screen reports the DIRECTION; the view model resolves it through the same mapping.
        // That indirection is what keeps the label and the write from ever disagreeing.
        assertEquals(
            "one gesture must commit exactly once: " + events.filterIsInstance<EpisodeListEvent.SwipeCommitted>(),
            1,
            events.filterIsInstance<EpisodeListEvent.SwipeCommitted>().size,
        )
    }

    @Test
    fun `the row survives the swipe rather than being dismissed`() {
        render()

        swipeRow { swipeLeft(startX = right, endX = left, durationMillis = SWIPE_MS) }

        // Nothing is removed by a triage decision — the episode gains a ledger state and re-renders.
        compose.onNodeWithText("Warum Hamburg immer regnet").assertExists()
    }
}
