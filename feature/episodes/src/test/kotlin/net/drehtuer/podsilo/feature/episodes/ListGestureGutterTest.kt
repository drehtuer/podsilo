// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import net.drehtuer.podsilo.core.ui.ListGutter
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneOffset

/**
 * Issue #92: a row that reaches the screen edge takes the drag the user meant for the system.
 *
 * The regression these pin is specific and was true of both lists before this change: the row —
 * the node that carries the click and, on S2, the swipe — started at x = 0, so a back-swipe or an
 * app-switch drag that clipped the list was read as a triage decision. Asserting on the row's own
 * bounds rather than on a screenshot is what makes it a regression test: the number that must not
 * go back to zero is the row's left edge.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp")
class ListGestureGutterTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `an episode row's gesture surface stops short of both screen edges`() {
        val state =
            EpisodeListUiState(
                feedUrl = FEED,
                feedTitle = "Der Podcast",
                content =
                    EpisodeListUiState.Content.Episodes(
                        listOf(
                            EpisodeUi(
                                episodeKey = "e1",
                                feedUrl = FEED,
                                feedTitle = "Der Podcast",
                                title = TITLE,
                                artworkUrl = null,
                                publishedAt = Instant.parse("2026-07-14T09:00:00Z"),
                                duration = null,
                                sizeBytes = null,
                                descriptionSnippet = "Eine Folge über Regen",
                                ledgerState = null,
                                progress = null,
                                lastError = null,
                                episodePageUrl = null,
                            ),
                        ),
                    ),
            )
        compose.setContent {
            EpisodeListScreen(state = state, onEvent = {}, zone = ZoneOffset.UTC)
        }

        val row = compose.onNodeWithText(TITLE).getUnclippedBoundsInRoot()
        val screenWidth = 360.dp

        assertTrue("row starts at ${row.left}, inside the gutter", row.left.value >= ListGutter.value)
        assertTrue(
            "row ends ${screenWidth - row.right} from the edge, inside the gutter",
            (screenWidth - row.right).value >= ListGutter.value,
        )
    }

    @Test
    fun `a podcast row's gesture surface stops short of both screen edges`() {
        compose.setContent {
            PodcastListScreen(
                state =
                    PodcastListUiState(
                        content =
                            PodcastListUiState.Content.Feeds(
                                listOf(FeedUi(url = "a", title = "Der Podcast", undecidedCount = 12)),
                            ),
                    ),
                onEvent = {},
                now = Instant.parse("2026-08-02T12:00:00Z"),
            )
        }

        val row = compose.onNodeWithText("Der Podcast").getUnclippedBoundsInRoot()
        val screenWidth = 360.dp

        assertTrue("row starts at ${row.left}, inside the gutter", row.left.value >= ListGutter.value)
        assertTrue(
            "row ends ${screenWidth - row.right} from the edge, inside the gutter",
            (screenWidth - row.right).value >= ListGutter.value,
        )
    }

    private companion object {
        const val FEED = "https://example.org/feed.xml"
        const val TITLE = "Warum Hamburg immer regnet"
    }
}
