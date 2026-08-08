// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import net.drehtuer.podsilo.core.ui.PODSILO_MARK_TEST_TAG
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * **Where the brand mark appears, and — mostly — where it does not** (`docs/logo.md` §4 and §5).
 *
 * §4 says the mark has exactly four placements and calls that "the complete list"; §5 then names the
 * places it must never be. Those are the kind of rules that hold for a week and then quietly stop
 * holding, because adding a logo to a screen always looks like an improvement in isolation. Counting
 * them is the only way the list stays complete.
 *
 * The screens in this module are S1, S2 and S3. S4–S6 are covered in `:feature:settings` and S7–S8 in
 * `:app`, each beside the screens they test.
 */
@RunWith(RobolectricTestRunner::class)
class LogoPlacementTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = Instant.parse("2026-08-02T12:00:00Z")

    private fun marks() = compose.onAllNodesWithTag(PODSILO_MARK_TEST_TAG)

    private fun renderS1(state: PodcastListUiState) {
        compose.setContent { PodcastListScreen(state = state, onEvent = {}, now = now) }
    }

    @Test
    fun `S1 carries the mark in its app bar, and once`() {
        // §4.1: the leading element of the one app bar entitled to it.
        renderS1(PodcastListUiState(content = PodcastListUiState.Content.Feeds(emptyList())))

        marks().assertCountEquals(1)
    }

    @Test
    fun `S1's first run shows the app-bar mark and the empty-state lockup, and nothing more`() {
        // §4.1 plus §4.2 — the only screen state in the app that legitimately holds two marks. Worth
        // pinning as a number so a third one cannot arrive unnoticed.
        renderS1(PodcastListUiState(content = PodcastListUiState.Content.NotConfigured))

        marks().assertCountEquals(2)
        compose.onNodeWithText("podsilo").assertIsDisplayed()
    }

    @Test
    fun `a feed with no artwork gets its monogram, never the brand mark`() {
        // §5: "never as the artwork placeholder". Repeating the logo down a list makes every podcast
        // look like it is ours — the count stays 1, which is the app bar's.
        renderS1(
            PodcastListUiState(
                content =
                    PodcastListUiState.Content.Feeds(
                        listOf(
                            FeedUi(url = FEED_URL, title = "Der Podcast", artworkUrl = null, undecidedCount = 3),
                            FeedUi(url = "https://example.org/b.xml", title = null, artworkUrl = null),
                        ),
                    ),
            ),
        )

        marks().assertCountEquals(1)
        // The monogram, which is what a null artworkUrl is supposed to produce.
        compose.onNodeWithText("D").assertIsDisplayed()
    }

    @Test
    fun `S1 in its other empty states still holds exactly the app-bar mark`() {
        // The filter-empty and no-subscriptions states keep their glyphs (§4.2): they are momentary
        // and local, not introductions, so the lockup does not follow them.
        renderS1(PodcastListUiState(content = PodcastListUiState.Content.NoSubscriptions))

        marks().assertCountEquals(1)
        compose.onAllNodes(hasText("podsilo")).assertCountEquals(0)
    }

    @Test
    fun `S2 has no mark anywhere, app bar included`() {
        // §5: never in the app bar of S2–S8 — a mark there competes with the context title, which is
        // the one thing the user opened the screen to read.
        compose.setContent {
            EpisodeListScreen(
                state = EpisodeListUiState(feedUrl = FEED_URL, feedTitle = "Der Podcast"),
                onEvent = {},
                zone = ZoneOffset.UTC,
            )
        }

        marks().assertCountEquals(0)
        compose.onAllNodes(hasText("podsilo")).assertCountEquals(0)
    }

    @Test
    fun `S3 has no mark, on a row's own artwork or anywhere else`() {
        // §5: never an episode-row element. The detail sheet is where an episode's own artwork is
        // largest, so it is where a brand fallback would be most tempting.
        compose.setContent {
            EpisodeDetailSheet(
                state =
                    EpisodeDetailUiState(
                        episode =
                            EpisodeUi(
                                episodeKey = "e1",
                                feedUrl = FEED_URL,
                                feedTitle = "Der Podcast",
                                title = "Warum Hamburg immer regnet",
                                // null artwork, so the slot falls back — to a monogram, not to us.
                                artworkUrl = null,
                                publishedAt = Instant.parse("2026-07-14T09:00:00Z"),
                                duration = Duration.ofMinutes(48),
                                descriptionSnippet = "Eine Folge über Regen",
                                ledgerState = null,
                            ),
                        descriptionHtml = "<p>Eine Folge über Regen</p>",
                    ),
                onEvent = {},
            )
        }

        marks().assertCountEquals(0)
    }
}
