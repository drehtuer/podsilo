// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.feed

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The per-episode cover a feed may supply, which is the first choice when embedding artwork on
 * download. The podcast's own image is the fallback and is resolved later, in `:core:download` —
 * parsing deliberately reports only what the item actually said.
 */
@RunWith(RobolectricTestRunner::class)
class EpisodeImageParsingTest {
    private val parser = FeedXmlParser()

    private fun parse(fixture: String) =
        runBlocking {
            val bytes =
                requireNotNull(javaClass.classLoader?.getResourceAsStream("feeds/$fixture")?.use { it.readBytes() }) {
                    "missing fixture: $fixture"
                }
            parser.parse("https://example.org/feed.xml", bytes)
        }

    @Test
    fun `itunes image on an item is the episode's artwork`() {
        val episodes = parse("episode_images.xml").episodes

        assertEquals("https://example.org/ep-itunes.jpg", episodes.single { it.guid == "ep-itunes" }.imageUrl)
    }

    @Test
    fun `a bare image element on an item is accepted too`() {
        val episodes = parse("episode_images.xml").episodes

        assertEquals("https://example.org/ep-bare.png", episodes.single { it.guid == "ep-bare" }.imageUrl)
    }

    @Test
    fun `an item with no artwork reports none rather than borrowing the channel's`() {
        // The fallback is the downloader's decision, not the parser's: recording the podcast cover
        // here would make it impossible to tell "the episode has its own" from "it inherited one".
        val episodes = parse("episode_images.xml").episodes

        assertNull(episodes.single { it.guid == "ep-none" }.imageUrl)
    }

    @Test
    fun `the channel image is still the feed's, unchanged`() {
        assertEquals("https://example.org/podcast-cover.jpg", parse("episode_images.xml").metadata.imageUrl)
    }

    @Test
    fun `a feed with no episode images at all parses with null throughout`() {
        parse("valid_minimal.xml").episodes.forEach { assertNull(it.imageUrl) }
    }

    /**
     * `<enclosure length>` — advisory, and only when positive.
     *
     * Feeds write `length="0"` when they mean "no idea", and a row reading "0 MB" is worse than a row
     * with no size at all, so zero is dropped rather than stored.
     */
    @Test
    fun `the enclosure length is parsed as the advertised size`() {
        val episodes = parse("valid_minimal.xml").episodes

        assertEquals(12_345_678L, episodes.first { it.enclosureUrl.endsWith("ep2.mp3") }.sizeBytes)
        assertEquals(10_000_000L, episodes.first { it.enclosureUrl.endsWith("ep1.mp3") }.sizeBytes)
    }
}
