// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.feed

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises the full bytes -> [ParsedFeed] pipeline against static fixtures in
 * `src/test/resources/feeds/` -- no network call anywhere in this class (CLAUDE.md section 7 item
 * 2). Robolectric is required for rssparser's Android target to resolve an
 * `org.xmlpull.v1.XmlPullParserFactory` implementation -- see `docs/decisions/0005`.
 */
@RunWith(RobolectricTestRunner::class)
class FeedXmlParserTest {
    private val feedUrl = "https://example.com/feed.xml"
    private val parser = FeedXmlParser()

    private fun fixtureBytes(name: String): ByteArray {
        val bytes = javaClass.classLoader?.getResourceAsStream("feeds/$name")?.use { it.readBytes() }
        return requireNotNull(bytes) { "missing fixture: $name" }
    }

    private fun parseFixture(name: String) = runBlocking { parser.parse(feedUrl, fixtureBytes(name)) }

    @Test
    fun `a valid feed maps channel metadata and both episodes, newest first, with parsed durations`() {
        val result = parseFixture("valid_minimal.xml")

        assertEquals("Der Podcast", result.metadata.title)
        assertEquals("https://example.com/art.jpg", result.metadata.imageUrl)

        assertEquals(2, result.episodes.size)
        val first = result.episodes[0]
        assertEquals("Folge 2: Warum Hamburg immer regnet", first.title)
        assertEquals("guid-episode-2", first.guid)
        assertEquals("guid-episode-2", first.episodeKey)
        assertEquals("https://example.com/episodes/ep2.mp3", first.enclosureUrl)
        assertEquals((1 * 60 + 32) * 60 * 1000L + 15 * 1000L, first.durationMs)

        val second = result.episodes[1]
        assertEquals("Folge 1: Der Anfang", second.title)
        assertEquals(3_600_000L, second.durationMs)
    }

    @Test
    fun `an episode without a guid derives episodeKey from the enclosure url`() {
        val result = parseFixture("missing_guid.xml")

        val episode = result.episodes.single()
        assertNull(episode.guid)
        assertEquals("https://example.com/episodes/no-guid.mp3", episode.episodeKey)
    }

    @Test
    fun `a reused guid across items keeps only the first (newest) occurrence`() {
        val result = parseFixture("duplicate_guid.xml")

        assertEquals(1, result.episodes.size)
        assertEquals("Second Episode, Same GUID", result.episodes.single().title)
    }

    @Test
    fun `an item with no enclosure is excluded entirely`() {
        val result = parseFixture("missing_enclosure.xml")

        assertEquals(1, result.episodes.size)
        assertEquals("Episode With an Enclosure", result.episodes.single().title)
    }

    @Test
    fun `malformed and missing pubDate both degrade to null, not a crash`() {
        val result = parseFixture("bad_dates.xml")

        assertEquals(2, result.episodes.size)
        assertTrue(result.episodes.all { it.pubDate == null })
    }

    @Test
    fun `content encoded CDATA HTML is preferred over the plain description`() {
        val result = parseFixture("cdata_html_description.xml")

        val episode = result.episodes.single()
        assertEquals(
            """<p>Full show notes with <a href="https://example.com">a link</a>.</p>""",
            episode.description,
        )
    }

    @Test
    fun `a genuinely non-utf-8-encoded feed is decoded correctly, not mangled`() {
        val result = parseFixture("wrong_encoding_iso_8859_1.xml")

        assertEquals("Über den Wolken - Der Podcast", result.metadata.title)
        assertEquals("Warum Käse besser schmeckt als Brötchen", result.episodes.single().title)
    }

    @Test
    fun `a missing itunes duration yields a null durationMs, never a fabricated one`() {
        val result = parseFixture("no_duration.xml")

        assertNull(result.episodes.single().durationMs)
    }
}
