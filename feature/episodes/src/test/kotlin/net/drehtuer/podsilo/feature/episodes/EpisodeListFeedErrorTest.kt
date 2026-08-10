// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * S2's feed-error banner (`docs/UI.md` §5) — a state field that was set by nobody and read by
 * nobody, so a feed that would not load was silent on the screen that lists it.
 *
 * Its own class because the rule has real content: the banner shows an error **newer than the last
 * successful refresh**, not merely an error that exists. Getting that wrong in either direction is a
 * visible bug — a stale banner that never clears, or no banner at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EpisodeListFeedErrorTest : EpisodeListTestHarness() {
    @Test
    fun `a feed failure newer than the last success shows its sentence verbatim`() =
        runTest {
            // Verbatim, because FeedRefresher already wrote the plain sentence §5 wants into the
            // error log. Two writers for one failure is two chances to describe it differently.
            seed(episode("e1"))
            feeds.seed(feed(lastRefreshedAt = 1_000))
            logs.seed(FEED_URL, "Feed server did not respond.", at = 2_000)
            val vm = viewModel()
            runCurrent()

            assertEquals("Feed server did not respond.", vm.state.value.feedError)
            // The episodes stay listed — the banner sits above the list, never in place of it.
            assertEquals(listOf("e1"), rows(vm.state.value).map { it.episodeKey })
        }

    /**
     * The rule is "newer than the last successful refresh", not "exists". An error a later refresh
     * cleared must not sit on the screen for ever.
     */
    @Test
    fun `an error older than the last successful refresh is not shown`() =
        runTest {
            seed(episode("e1"))
            feeds.seed(feed(lastRefreshedAt = 5_000))
            logs.seed(FEED_URL, "Feed server did not respond.", at = 2_000)
            val vm = viewModel()
            runCurrent()

            assertNull(vm.state.value.feedError)
        }

    @Test
    fun `another feed's failure never appears on this feed`() =
        runTest {
            seed(episode("e1"))
            feeds.seed(feed(lastRefreshedAt = null))
            logs.seed("https://example.org/other.xml", "Feed server did not respond.", at = 9_000)
            val vm = viewModel()
            runCurrent()

            assertNull(vm.state.value.feedError)
        }

    /** A feed that has never been refreshed has no success to compare against, so any error stands. */
    @Test
    fun `a never-refreshed feed shows its error`() =
        runTest {
            seed(episode("e1"))
            feeds.seed(feed(lastRefreshedAt = null))
            logs.seed(FEED_URL, "This podcast's feed could not be loaded (HTTP 404).", at = 1)
            val vm = viewModel()
            runCurrent()

            assertEquals("This podcast's feed could not be loaded (HTTP 404).", vm.state.value.feedError)
        }

    @Test
    fun `Try again refreshes this feed, exactly as the pull gesture does`() =
        runTest {
            seed(episode("e1"))
            val vm = viewModel()
            runCurrent()

            vm.onEvent(EpisodeListEvent.RetryFeedClicked)
            runCurrent()

            assertEquals(listOf(FEED_URL), scheduler.refreshes)
        }
}
