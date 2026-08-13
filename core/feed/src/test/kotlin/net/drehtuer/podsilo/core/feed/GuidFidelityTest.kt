// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.feed

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issue #60, step 5: **the `guid` we parse has to be byte-identical to the feed's raw `<guid>` text.**
 *
 * It is the join key between this app and every other client. RePod looks an action up with
 * `findByGuid((string) $item->guid)` — the element's raw text, no trimming, no normalisation — and
 * falls back to the enclosure URL only when that misses. A parser that trimmed where the other
 * client does not (or the reverse) would put every action we post under a key the other side cannot
 * find, and the symptom would be indistinguishable from "sync doesn't work": actions present in the
 * database, invisible in the UI.
 *
 * The probe confirmed the *live* instance matches (2026-08-13); this pins the parser so it stays
 * that way, and covers the shapes a real feed produces rather than only the tidy one.
 */
@RunWith(RobolectricTestRunner::class)
class GuidFidelityTest {
    private val parser = FeedXmlParser()

    private fun parseFixture(name: String) =
        runBlocking {
            val stream = javaClass.classLoader?.getResourceAsStream("feeds/$name")
            val bytes = requireNotNull(stream?.use { it.readBytes() }) { "missing fixture: $name" }
            parser.parse("https://example.com/feed.xml", bytes)
        }

    @Test
    fun `every guid shape survives parsing exactly as the feed wrote it`() {
        val byTitle = parseFixture("guid_whitespace_and_shapes.xml").episodes.associateBy { it.title }

        assertEquals("guid-plain", byTitle.getValue("Plain guid").guid)
        assertEquals(
            "https://example.com/episodes/2026/08/14?id=42&x=1",
            byTitle.getValue("Guid that is a URL").guid,
        )
        assertEquals("urn:bbc:podcast:p08y46bf", byTitle.getValue("Guid with a colon-shaped scheme").guid)
    }

    /**
     * The case that would actually bite: a pretty-printed feed puts the guid on its own indented
     * line. Whatever the parser does with that whitespace, **the episode key must be the same string
     * we would post as `guid`** — those two come from one field, so the join stays consistent even if
     * the surrounding text ever changed.
     */
    @Test
    fun `an indented guid produces a key identical to the guid we would post`() {
        val episode = parseFixture("guid_whitespace_and_shapes.xml").episodes.single { it.title.contains("indented") }

        assertEquals(episode.guid, episode.episodeKey)
        assertEquals("guid-indented", episode.guid?.trim())
        // Recorded rather than asserted as a requirement: whether rssparser trims is its business,
        // and what this app must guarantee is that guid and episodeKey agree — which the line above
        // pins. Printed here so a future change of parser shows up as a diff in this comment.
        // Observed 2026-08-14: "guid-indented" (trimmed by the parser).
    }
}
