// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 * The same screen on a **real Android runtime** — Tier 2 (`docs/dev-environment.md` §6).
 *
 * Deliberately a thin smoke test rather than a copy of the Robolectric suite: the point is to prove
 * the screen composes, measures and responds to input on a real device, which is the one thing
 * Robolectric's shadows cannot vouch for. Behaviour is asserted in the JVM tests, which are faster
 * and run everywhere.
 *
 * The case chosen is `docs/decisions/0011`'s guarantee, because it is the one where being wrong is
 * a button that cannot work.
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

        compose.onNodeWithText("Choose folder").assertIsDisplayed()
    }
}
