// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A pull comfortably past the refresh threshold, independent of any row's height.
 *
 * Deliberately duplicated from `EpisodeListTestHarness` in the Robolectric source set rather than
 * shared: `src/test/` and `src/androidTest/` are separate compilations, and a `commonTest` module
 * for one float would cost more than it saves.
 */
private const val PULL_DISTANCE_PX = 1_000f

/**
 * **S1 against `docs/UI.adoc` §4, on a real Compose runtime.**
 *
 * The same assertions exist under Robolectric, and that is deliberate rather than duplication: three
 * of the bugs found on the author's phone this week were things a Robolectric render agreed with and
 * a device did not — an ICU regex, a manifest attribute, a dependency that was never on the compile
 * classpath. A conformance claim about the UI is worth making where the UI actually runs.
 *
 * Each test names the clause of the design document it enforces. If one fails, either the screen
 * drifted or the document did — and the document is canonical (`docs/UI.adoc`), so the screen moves.
 */
@RunWith(AndroidJUnit4::class)
class PodcastListConformanceTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = mutableListOf<PodcastListEvent>()

    private fun render(state: PodcastListUiState) {
        compose.setContent { PodcastListScreen(state = state, onEvent = { events += it }) }
    }

    private fun feeds(vararg rows: FeedUi) =
        PodcastListUiState(content = PodcastListUiState.Content.Feeds(rows.toList()))

    /**
     * §4 and CLAUDE.md §10: the empty state points at Nextcloud and offers **no way to add a feed**.
     * This is the main place the read-only design becomes visible, so the absence is the assertion.
     */
    @Test
    fun noSubscriptionsPointsAtNextcloudAndOffersNoAddFeedControl() {
        render(PodcastListUiState(content = PodcastListUiState.Content.NoSubscriptions))

        compose.onNode(hasText("add feeds in Nextcloud", substring = true)).assertIsDisplayed()
        compose.onAllNodes(hasText("Add", substring = true)).assertCountEquals(0)
        compose.onAllNodes(hasText("New podcast", substring = true)).assertCountEquals(0)
        compose.onAllNodes(hasText("URL", substring = true)).assertCountEquals(0)
    }

    /** §12.5: a feed nobody has fetched shows `–`, because unknown is not zero. */
    @Test
    fun aNeverFetchedFeedShowsADashRatherThanZero() {
        render(feeds(FeedUi(url = "a", title = "Der Podcast", undecidedCount = null)))

        compose.onNodeWithText("–").assertIsDisplayed()
        compose.onAllNodes(hasText("0")).assertCountEquals(0)
    }

    /**
     * §18: the artwork slot renders a monogram tile when there is no image, with **the same content
     * description as real artwork** — never "no image".
     */
    @Test
    fun artworkFallsBackToAMonogramDescribedAsCoverArt() {
        render(feeds(FeedUi(url = "a", title = "Der Podcast", artworkUrl = null, undecidedCount = 3)))

        compose.onNodeWithContentDescription("cover art for Der Podcast").assertIsDisplayed()
        compose.onNodeWithText("D").assertIsDisplayed()
        compose.onAllNodes(hasText("no image", substring = true)).assertCountEquals(0)
    }

    /**
     * §4: pull-to-refresh is the refresh affordance, and it must work on a *populated* list.
     *
     * Swiping the feed row rather than `hasScrollAction()`, and by an explicit distance rather than
     * the default `swipeDown()` — the same two corrections `PodcastListScreenTest` already carries,
     * for the same two reasons. S1's chip row became scrollable in its own right with #48's fix, so
     * that matcher now finds two nodes and fails as an ambiguous match; and the default swipe travels
     * a node's own height, which makes the gesture's distance depend on how tall a row happens to be.
     * Nested scroll carries the gesture up to the `PullToRefreshBox` regardless.
     */
    @Test
    fun aPopulatedListCanBePulledToRefresh() {
        render(feeds(FeedUi(url = "a", title = "Der Podcast", undecidedCount = 12)))

        compose.onNodeWithText("Der Podcast").performTouchInput {
            swipeDown(startY = centerY, endY = centerY + PULL_DISTANCE_PX)
        }

        assertTrue(
            "pulling a populated list down must request a refresh, got $events",
            events.contains(PodcastListEvent.PullToRefresh),
        )
    }

    /**
     * §17 / §12.12: the ≥ 48 dp touch-target floor. Measured on the device because dp→px conversion
     * and the real density are part of what is being claimed.
     */
    @Test
    fun theRowTapTargetClearsThe48DpFloor() {
        render(feeds(FeedUi(url = "a", title = "Der Podcast", undecidedCount = 1)))

        compose.onNodeWithText("Der Podcast").assertHeightIsAtLeast(48.dp)
    }

    /** §4: the row's primary line falls back to the URL, never to "Unknown podcast". */
    @Test
    fun aFeedWithNoTitleYetShowsItsUrl() {
        render(feeds(FeedUi(url = "https://example.org/feed.xml", title = null)))

        compose.onNodeWithText("https://example.org/feed.xml").assertIsDisplayed()
        compose.onAllNodes(hasText("Unknown", substring = true)).assertCountEquals(0)
    }
}
