// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.database

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.drehtuer.podsilo.core.database.repository.EpisodeRepositoryImpl
import net.drehtuer.podsilo.core.database.repository.FeedRepositoryImpl
import org.junit.Assert.assertEquals
import org.junit.Test

class FeedRepositoryTest : RoomTestBase() {
    private val feeds by lazy { FeedRepositoryImpl(db.feedDao()) }
    private val episodes by lazy { EpisodeRepositoryImpl(db.episodeDao()) }

    @Test
    fun `replaceAll is wholesale - removed feeds vanish, new feeds appear`() =
        runTest {
            feeds.replaceAll(listOf(feed("a"), feed("b")))
            assertEquals(
                setOf("a", "b"),
                feeds
                    .observeAll()
                    .first()
                    .map { it.url }
                    .toSet(),
            )

            feeds.replaceAll(listOf(feed("b"), feed("c")))
            assertEquals(
                setOf("b", "c"),
                feeds
                    .observeAll()
                    .first()
                    .map { it.url }
                    .toSet(),
            )
        }

    @Test
    fun `an empty subscription list clears the mirror`() =
        runTest {
            feeds.replaceAll(listOf(feed("a")))
            feeds.replaceAll(emptyList())
            assertEquals(emptyList<String>(), feeds.observeAll().first().map { it.url })
        }

    @Test
    fun `re-seeing an existing feed keeps its cached episodes (upsert, not delete+insert)`() =
        runTest {
            feeds.replaceAll(listOf(feed("a")))
            episodes.replaceForFeed("a", listOf(episode("e1", feedUrl = "a")))

            // Same feed reappears alongside a new one — its episodes must not be wiped by a REPLACE cascade.
            feeds.replaceAll(listOf(feed("a"), feed("b")))

            assertEquals(listOf("e1"), episodes.observeForFeed("a").first().map { it.episodeKey })
        }

    @Test
    fun `removing a feed cascade-deletes its episodes`() =
        runTest {
            feeds.replaceAll(listOf(feed("a")))
            episodes.replaceForFeed("a", listOf(episode("e1", feedUrl = "a")))

            feeds.replaceAll(emptyList())

            assertEquals(emptyList<String>(), episodes.observeForFeed("a").first().map { it.episodeKey })
        }

    @Test
    fun `caller-preserved firstSeenAt survives a replace`() =
        runTest {
            feeds.replaceAll(listOf(feed("a", firstSeenAt = 111)))
            feeds.replaceAll(listOf(feed("a", firstSeenAt = 111), feed("b", firstSeenAt = 222)))

            assertEquals(
                111,
                feeds
                    .observeAll()
                    .first()
                    .first { it.url == "a" }
                    .firstSeenAt,
            )
        }
}
