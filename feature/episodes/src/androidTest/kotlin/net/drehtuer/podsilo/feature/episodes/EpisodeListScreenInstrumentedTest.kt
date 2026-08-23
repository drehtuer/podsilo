// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.drehtuer.podsilo.core.model.ErrorCause
import net.drehtuer.podsilo.core.model.LedgerState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

private const val TEST_FEED_URL = "https://example.org/feed.xml"

/**
 * The same screen on a **real Android runtime** — Tier 2 (`dev-environment.adoc` §6).
 *
 * Deliberately a thin smoke test rather than a copy of the Robolectric suite: the point is to prove
 * the screen composes, measures and responds to input on a real device, which is the one thing
 * Robolectric's shadows cannot vouch for. Behaviour is asserted in the JVM tests, which are faster
 * and run everywhere.
 *
 * The first case is `architecture.adoc` §11's guarantee, because it is the one where being wrong is a
 * button that cannot work.
 *
 * **The rest are issue #48's**, and they are here rather than only in the JVM suite for a specific
 * reason: that bug was a *measured layout* fault on a real screen, and it stayed green through 627
 * JVM tests. The Robolectric versions pin the behaviour at a synthetic `w320dp` qualifier; these
 * measure against the device's own width, density and font scale — the three inputs that actually
 * produced the report — and exercise the overflow as a **real popup window**, which is a separate
 * window rather than the shadow Robolectric substitutes.
 */
@RunWith(AndroidJUnit4::class)
class EpisodeListScreenInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    private fun row(
        ledgerState: LedgerState? = null,
        failure: FailureUi? = null,
    ) = EpisodeUi(
        episodeKey = "e1",
        feedUrl = TEST_FEED_URL,
        feedTitle = "Der Podcast",
        title = "Warum Hamburg immer regnet",
        artworkUrl = null,
        publishedAt = Instant.parse("2026-07-14T09:00:00Z"),
        duration = Duration.ofMinutes(48),
        descriptionSnippet = "Eine Folge über Regen",
        ledgerState = ledgerState,
        lastError = failure,
    )

    private fun stateWith(vararg rows: EpisodeUi) =
        EpisodeListUiState(
            feedUrl = TEST_FEED_URL,
            feedTitle = "Der Podcast",
            content = EpisodeListUiState.Content.Episodes(rows.toList()),
        )

    @Test
    fun anEpisodeRendersAndTappingItOpensDetailWithoutTriaging() {
        val events = mutableListOf<EpisodeListEvent>()
        compose.setContent {
            EpisodeListScreen(state = stateWith(row()), onEvent = { events += it }, zone = ZoneOffset.UTC)
        }

        compose.onNodeWithText("Warum Hamburg immer regnet").assertIsDisplayed()
        compose.onNodeWithText("Warum Hamburg immer regnet").performClick()

        assertEquals(listOf(EpisodeListEvent.RowClicked("e1")), events)
    }

    /**
     * `architecture.adoc` §11's guarantee, asserted **through the row overflow** since the `⋮` replaced
     * the inline `TextButton`s (`UI.adoc` §5's row anatomy).
     *
     * Opening the menu is the subject rather than incidental setup: §12.1 makes it the mandatory
     * non-gesture equivalent of the swipes, so the remedy being reachable *there* is the actual
     * promise. The earlier version asserted the label was on screen, which stopped being true when
     * the menu landed — and went unnoticed because these never run on CI.
     */
    @Test
    fun aLostFolderGrantOffersChooseFolderRatherThanRetry() {
        compose.setContent {
            EpisodeListScreen(
                state =
                    stateWith(
                        row(
                            ledgerState = LedgerState.ERROR,
                            failure =
                                FailureUi(
                                    cause = ErrorCause.FOLDER_UNAVAILABLE,
                                    message = "the download folder is no longer accessible",
                                    attempts = 1,
                                    retryable = false,
                                ),
                        ),
                    ),
                onEvent = { },
                zone = ZoneOffset.UTC,
            )
        }

        compose.onNodeWithContentDescription("Actions for Warum Hamburg immer regnet").performClick()

        compose.onNodeWithText("Choose folder").assertIsDisplayed()
        // The "rather than Retry" half, which the name promised and the original never checked.
        compose.onAllNodesWithText("Retry").assertCountEquals(0)
    }

    /**
     * Issue #48, measured on the device rather than at a synthetic qualifier.
     *
     * The assertion is *reachability*, not visibility: on a narrow screen the four chips genuinely
     * cannot all be on screen at once, which is what decision D3 accepted when it chose a scrolling
     * line over a wrapping one. What the shipped row got wrong was having no scroll at all, so the
     * last chip was clipped and `All` could not be reached by any means. `performScrollTo` fails on a
     * node whose parents cannot scroll, so this fails against the row that was reported.
     *
     * It is also the test that a large system font scale would break first, which is the point of
     * running it against a real device's settings.
     */
    @Test
    fun everyFilterChipIsReachableAtTheDeviceWidth() {
        val events = mutableListOf<EpisodeListEvent>()
        compose.setContent {
            EpisodeListScreen(state = stateWith(row()), onEvent = { events += it }, zone = ZoneOffset.UTC)
        }

        compose.onNodeWithText("All").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("All").performClick()

        assertEquals(listOf(EpisodeListEvent.FilterChanged(EpisodeFilter.ALL)), events)
    }

    /**
     * The app bar S2 shipped without — alone among the eight screens, which is why there was no up
     * navigation and no host for the overflow.
     */
    @Test
    fun theAppBarNamesTheFeedAndOffersUpNavigation() {
        val events = mutableListOf<EpisodeListEvent>()
        compose.setContent {
            EpisodeListScreen(state = stateWith(row()), onEvent = { events += it }, zone = ZoneOffset.UTC)
        }

        compose.onNodeWithText("Der Podcast").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()

        assertEquals(listOf(EpisodeListEvent.BackClicked), events)
    }

    /**
     * *Download all (n)* end to end on a real runtime.
     *
     * Worth a device test specifically because a `DropdownMenu` is a **popup in its own window**, and
     * a popup is one of the things Robolectric substitutes rather than runs. The assertion that
     * opening the menu emits nothing is `decisions/0014`'s safeguard: the count is named before
     * anything is written, and only the confirmation dialog writes.
     */
    @Test
    fun theOverflowOpensAsARealPopupAndOffersDownloadAll() {
        val events = mutableListOf<EpisodeListEvent>()
        compose.setContent {
            EpisodeListScreen(
                state = stateWith(row()).copy(downloadAllCount = 12),
                onEvent = { events += it },
                zone = ZoneOffset.UTC,
            )
        }

        compose.onNodeWithContentDescription("More actions").performClick()
        compose.onNodeWithText("Download all (12)").assertIsDisplayed()
        assertEquals(emptyList<EpisodeListEvent>(), events)

        compose.onNodeWithText("Download all (12)").performClick()
        assertEquals(listOf(EpisodeListEvent.DownloadAllRequested), events)
    }

    /**
     * Issue #46's entry point, on a real runtime.
     *
     * Worth a device test rather than only a Robolectric one because a long-press is a **timed
     * gesture**: it depends on the platform's real `ViewConfiguration.getLongPressTimeout()` and on
     * the touch-slop of an actual input pipeline, neither of which Robolectric's shadows exercise.
     * A long-press that Compose interprets as a scroll or a tap is exactly how this affordance
     * fails in the hand while passing headless.
     */
    @Test
    fun longPressingARowEntersSelectionMode() {
        val events = mutableListOf<EpisodeListEvent>()
        compose.setContent {
            EpisodeListScreen(state = stateWith(row()), onEvent = { events += it }, zone = ZoneOffset.UTC)
        }

        compose.onNodeWithText("Warum Hamburg immer regnet").performTouchInput { longClick() }

        assertEquals(listOf(EpisodeListEvent.SelectionStarted("e1")), events)
    }

    /** The bar replaces the normal one, so "Back" must be gone while a selection is live. */
    @Test
    fun theSelectionBarReplacesTheNormalOne() {
        compose.setContent {
            EpisodeListScreen(
                state = stateWith(row()).copy(selection = Selection(setOf("e1"), allInFilter = 1)),
                onEvent = { },
                zone = ZoneOffset.UTC,
            )
        }

        compose.onNodeWithText("1 selected").assertIsDisplayed()
        compose.onNodeWithContentDescription("Selected").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Back").assertCountEquals(0)
    }
}
