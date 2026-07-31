// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.database

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.drehtuer.podsilo.core.database.repository.EpisodeRepositoryImpl
import net.drehtuer.podsilo.core.database.repository.FeedRepositoryImpl
import org.junit.Assert.assertEquals
import org.junit.Test

class EpisodeRepositoryTest : RoomTestBase() {
    private val feeds by lazy { FeedRepositoryImpl(db.feedDao()) }
    private val episodes by lazy { EpisodeRepositoryImpl(db.episodeDao()) }

    @Test
    fun `replaceForFeed wipes and rebuilds the cache for that feed only`() =
        runTest {
            feeds.replaceAll(listOf(feed("a"), feed("b")))
            episodes.replaceForFeed("a", listOf(episode("a1", "a"), episode("a2", "a")))
            episodes.replaceForFeed("b", listOf(episode("b1", "b")))

            episodes.replaceForFeed("a", listOf(episode("a3", "a")))

            assertEquals(listOf("a3"), episodes.observeForFeed("a").first().map { it.episodeKey })
            assertEquals(listOf("b1"), episodes.observeForFeed("b").first().map { it.episodeKey })
        }

    @Test
    fun `episodes are returned newest-first by pubDate`() =
        runTest {
            feeds.replaceAll(listOf(feed("a")))
            episodes.replaceForFeed(
                "a",
                listOf(
                    episode("old", "a", pubDate = 100),
                    episode("new", "a", pubDate = 300),
                    episode("mid", "a", pubDate = 200),
                ),
            )

            assertEquals(listOf("new", "mid", "old"), episodes.observeForFeed("a").first().map { it.episodeKey })
        }

    @Test
    fun `deleteForFeed empties one feed's cache`() =
        runTest {
            feeds.replaceAll(listOf(feed("a")))
            episodes.replaceForFeed("a", listOf(episode("a1", "a")))

            episodes.deleteForFeed("a")

            assertEquals(emptyList<String>(), episodes.observeForFeed("a").first().map { it.episodeKey })
        }
}
