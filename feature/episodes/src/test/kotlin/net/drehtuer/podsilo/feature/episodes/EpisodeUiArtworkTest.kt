// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import net.drehtuer.podsilo.core.model.port.EpisodeListItem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which artwork an episode row shows.
 *
 * `UI.adoc` §5's row anatomy says "episode image if the feed supplies one, else the feed's". The
 * projection ignored `Episode.imageUrl` altogether and always used the podcast's cover — which, in
 * the author's own subscriptions, meant 9,558 of 9,565 episodes rendered the wrong image. It only
 * became visible once artwork was drawn at all.
 */
class EpisodeUiArtworkTest {
    @Test
    fun `an episode with its own image prefers it over the podcast's`() {
        val ui =
            EpisodeListItem(episode("e1", imageUrl = "https://example.org/ep.jpg"), null)
                .toUi(feedTitle = "Der Podcast", feedArtworkUrl = "https://example.org/feed.jpg")

        assertEquals("https://example.org/ep.jpg", ui.artworkUrl)
    }

    @Test
    fun `an episode without its own image falls back to the podcast's`() {
        val ui =
            EpisodeListItem(episode("e1"), null)
                .toUi(feedTitle = "Der Podcast", feedArtworkUrl = "https://example.org/feed.jpg")

        assertEquals("https://example.org/feed.jpg", ui.artworkUrl)
    }

    @Test
    fun `neither available is null, and the row falls back to a monogram rather than a broken image`() {
        val ui = EpisodeListItem(episode("e1"), null).toUi(feedTitle = "Der Podcast")

        assertEquals(null, ui.artworkUrl)
    }
}
