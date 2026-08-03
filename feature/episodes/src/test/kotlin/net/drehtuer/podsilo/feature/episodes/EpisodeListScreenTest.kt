// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import net.drehtuer.podsilo.core.model.ErrorCause
import net.drehtuer.podsilo.core.model.LedgerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.ZoneOffset

/**
 * S2's rendering, driven through the real Compose runtime under Robolectric — headless, no emulator
 * (CLAUDE.md §4's Tier 1 definition: "Android-framework bits via Robolectric").
 *
 * These assert the things a screen can get wrong on its own, independently of the view model: that a
 * tap on the body opens detail rather than triaging, that a failure the user cannot retry away shows
 * the action that *can* clear it, and that a `DOWNLOADING` row with no live progress says *resuming*
 * rather than drawing a stale percentage.
 */
@RunWith(RobolectricTestRunner::class)
class EpisodeListScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = mutableListOf<EpisodeListEvent>()

    @Suppress("LongParameterList") // A builder's parameters are the type's fields.
    private fun row(
        key: String = "e1",
        title: String = "Warum Hamburg immer regnet",
        ledgerState: LedgerState? = null,
        progress: DownloadProgress? = null,
        failure: FailureUi? = null,
        publishedAt: Instant? = Instant.parse("2026-07-14T09:00:00Z"),
        durationMinutes: Long? = 48,
    ) = EpisodeUi(
        episodeKey = key,
        feedUrl = FEED_URL,
        feedTitle = "Der Podcast",
        title = title,
        artworkUrl = null,
        publishedAt = publishedAt,
        duration = durationMinutes?.let { java.time.Duration.ofMinutes(it) },
        descriptionSnippet = "Eine Folge über Regen",
        ledgerState = ledgerState,
        progress = progress,
        lastError = failure,
    )

    private fun render(state: EpisodeListUiState = EpisodeListUiState(feedUrl = FEED_URL, feedTitle = "Der Podcast")) {
        compose.setContent {
            EpisodeListScreen(state = state, onEvent = { events += it }, zone = ZoneOffset.UTC)
        }
    }

    private fun listOf(vararg rows: EpisodeUi) =
        EpisodeListUiState(
            feedUrl = FEED_URL,
            feedTitle = "Der Podcast",
            content = EpisodeListUiState.Content.Episodes(rows.toList()),
        )

    @Test
    fun `an episode renders its title, date and duration`() {
        render(listOf(row()))

        compose.onNodeWithText("Warum Hamburg immer regnet").assertIsDisplayed()
        compose.onNode(hasText("48 min", substring = true)).assertIsDisplayed()
    }

    @Test
    fun `a missing duration simply has no part in the meta line`() {
        // Never "unknown", never a fabricated value (docs/UI.md §5).
        render(listOf(row(publishedAt = null, durationMinutes = null)))

        compose.onNodeWithText("Warum Hamburg immer regnet").assertIsDisplayed()
        compose.onAllNodes(hasText("min", substring = true)).assertCountEquals(0)
    }

    @Test
    fun `tapping the row body opens detail and never triages`() {
        render(listOf(row()))

        compose.onNodeWithText("Warum Hamburg immer regnet").performClick()

        assertEquals(kotlin.collections.listOf(EpisodeListEvent.RowClicked("e1")), events)
    }

    @Test
    fun `an undecided episode offers Download and Mark as played`() {
        render(listOf(row()))

        compose.onNodeWithText("Download").assertIsDisplayed()
        compose.onNodeWithText("Mark as played").assertIsDisplayed()
    }

    @Test
    fun `Download emits a triage event for that episode`() {
        render(listOf(row()))

        compose.onNodeWithText("Download").performClick()

        assertEquals(
            kotlin.collections.listOf(EpisodeListEvent.Triage("e1", EpisodeUiAction.DOWNLOAD)),
            events,
        )
    }

    @Test
    fun `a lost folder grant shows Choose folder instead of Retry`() {
        // The rendered half of `docs/decisions/0011`: a Retry button here cannot possibly work, so
        // the row must offer the action that can.
        render(
            listOf(
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
        )

        compose.onNodeWithText("Choose folder").assertIsDisplayed()
        compose.onAllNodes(hasText("Retry", substring = true)).assertCountEquals(0)
    }

    @Test
    fun `a network failure does show Retry`() {
        render(
            listOf(
                row(
                    ledgerState = LedgerState.ERROR,
                    failure =
                        FailureUi(
                            cause = ErrorCause.NETWORK,
                            message = "connection reset",
                            attempts = 2,
                            retryable = true,
                        ),
                ),
            ),
        )

        compose.onNodeWithText("Retry").assertIsDisplayed()
        // The message is shown verbatim — it is the one string the UI does not re-word.
        compose.onNode(hasText("connection reset", substring = true)).assertIsDisplayed()
    }

    @Test
    fun `a downloading row with no live progress says resuming, not zero percent`() {
        // UI_interface §7: a percentage is only ever drawn from an update seen in this process, so
        // after process death the row must not imply it knows how far along it is.
        render(listOf(row(ledgerState = LedgerState.DOWNLOADING, progress = null)))

        compose.onNodeWithContentDescription("resuming").assertIsDisplayed()
    }

    @Test
    fun `a downloading row with live progress draws the percentage`() {
        render(
            listOf(
                row(
                    ledgerState = LedgerState.DOWNLOADING,
                    progress = DownloadProgress(bytesDownloaded = 620, totalBytes = 1_000),
                ),
            ),
        )

        compose.onNodeWithContentDescription("downloading, 62 percent").assertIsDisplayed()
    }

    @Test
    fun `a paused queue shows the banner with its fix, not just the problem`() {
        render(
            EpisodeListUiState(
                feedUrl = FEED_URL,
                feedTitle = "Der Podcast",
                content = EpisodeListUiState.Content.Episodes(kotlin.collections.listOf(row())),
                queueStatus = QueueStatus.Paused(QueueStatus.PauseCause.FOLDER_REVOKED, queuedCount = 1),
            ),
        )

        compose.onNode(hasText("no longer available", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("Choose folder").performClick()

        assertTrue(events.contains(EpisodeListEvent.PausedBannerActionClicked))
    }

    @Test
    fun `the empty state names the filter rather than looking like a loading screen`() {
        render(
            EpisodeListUiState(
                feedUrl = FEED_URL,
                feedTitle = "Der Podcast",
                content = EpisodeListUiState.Content.Empty(EpisodeFilter.TO_DECIDE),
            ),
        )

        compose.onNodeWithText("Nothing to decide in this podcast.").assertIsDisplayed()
    }

    @Test
    fun `changing the filter emits, and does not decide anything locally`() {
        render(listOf(row()))

        compose.onNodeWithText("Downloaded").performClick()

        assertEquals(
            kotlin.collections.listOf(EpisodeListEvent.FilterChanged(EpisodeFilter.DOWNLOADED)),
            events,
        )
    }

    @Test
    fun `the Download all dialog names the count and writes nothing until confirmed`() {
        render(
            EpisodeListUiState(
                feedUrl = FEED_URL,
                feedTitle = "Der Podcast",
                content = EpisodeListUiState.Content.Episodes(kotlin.collections.listOf(row())),
                pendingBulk =
                    BulkPreview(
                        episodeKeys = kotlin.collections.listOf("a", "b", "c"),
                        perFeed = kotlin.collections.listOf(FeedBreakdown(FEED_URL, 3)),
                        estimatedBytes = null,
                        freeBytes = null,
                    ),
            ),
        )

        compose.onNode(hasText("Download 3 episodes?", substring = true)).assertIsDisplayed()

        compose.onNodeWithText("Cancel").performClick()
        assertTrue(events.contains(EpisodeListEvent.DownloadAllDismissed))
        assertTrue("dismissing must not confirm", events.none { it is EpisodeListEvent.DownloadAllConfirmed })
    }

    @Test
    fun `the size warning appears only when the estimate exceeds free space`() {
        render(
            EpisodeListUiState(
                feedUrl = FEED_URL,
                feedTitle = "Der Podcast",
                content = EpisodeListUiState.Content.Episodes(kotlin.collections.listOf(row())),
                pendingBulk =
                    BulkPreview(
                        episodeKeys = kotlin.collections.listOf("a"),
                        perFeed = kotlin.collections.listOf(FeedBreakdown(FEED_URL, 1)),
                        estimatedBytes = 5_000_000_000,
                        freeBytes = 1_000_000,
                    ),
            ),
        )

        compose.onNode(hasText("may not fit", substring = true)).assertIsDisplayed()
        // The confirm button stays present: the estimate is a guess and must not veto the decision.
        // Matched by exact text, since the row underneath also has a "Download" button.
        compose.onAllNodes(hasText("Download")).assertCountEquals(2)
    }

    /**
     * Artwork was specified in `docs/UI.md` §5's row anatomy and never drawn — Coil was approved
     * (ADR 0015), added to the catalog, and depended on by no module at all.
     */
    @Test
    fun `an episode row draws its artwork`() {
        render(listOf(row()))

        compose.onNodeWithContentDescription("cover art for Warum Hamburg immer regnet").assertIsDisplayed()
    }

    /**
     * S2's half of the missing-refresh bug (see `PodcastListScreenTest`): the event and its handler
     * existed, and **no affordance anywhere on this screen emitted it** — so a feed whose fetch had
     * failed could not be retried from the screen that shows the failure.
     */
    @Test
    fun `pulling the episode list down refreshes this feed`() {
        render(listOf(row()))

        compose.onNode(hasScrollAction()).performTouchInput { swipeDown() }

        assertTrue(
            "pulling the episode list down must request a refresh, got $events",
            events.contains(EpisodeListEvent.PullToRefresh),
        )
    }

    /**
     * *Mark all as played* on the **Downloaded** filter (the author's request).
     *
     * Scoped to that filter: *To decide* already has S4's per-feed preview, and on *Played /
     * handled* it would be a no-op. It confirms first because this writes `PLAY` actions to a shared
     * log that other clients act on, and no undo reaches them (`docs/decisions/0013`).
     */
    @Test
    fun `Downloaded offers Mark all as played with its count`() {
        render(downloaded(row(key = "a"), row(key = "b")))

        compose.onNodeWithText("Mark all 2 as played").performClick()

        assertTrue(events.contains(EpisodeListEvent.MarkAllRequested))
    }

    @Test
    fun `Mark all is absent on the To decide filter`() {
        render(listOf(row()))

        compose.onAllNodes(hasText("Mark all", substring = true)).assertCountEquals(0)
    }

    @Test
    fun `the Mark all dialog names the count and says the state reaches Nextcloud`() {
        render(downloaded(row(key = "a"), row(key = "b")).copy(pendingMarkAll = listOf("a", "b")))

        compose.onNode(hasText("Mark 2 downloaded episodes as played?", substring = true)).assertIsDisplayed()
        compose.onNode(hasText("sent to Nextcloud", substring = true)).assertIsDisplayed()
        // Podsilo never deletes a file, and the dialog has to say so or "mark as played" reads as tidy-up.
        compose.onNode(hasText("never deletes files", substring = true)).assertIsDisplayed()
    }

    private fun downloaded(vararg rows: EpisodeUi) =
        EpisodeListUiState(
            feedUrl = FEED_URL,
            feedTitle = "Der Podcast",
            filter = EpisodeFilter.DOWNLOADED,
            content = EpisodeListUiState.Content.Episodes(rows.toList()),
        )
}
