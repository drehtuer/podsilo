// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.feed

import org.junit.Assert.assertTrue
import org.junit.Test

private const val ISO_8859_1_TITLE = "Über Käse"
private val ISO_8859_1_XML =
    """<?xml version="1.0" encoding="ISO-8859-1"?><rss><channel><title>$ISO_8859_1_TITLE</title></channel></rss>"""

class FeedXmlDecodingTest {
    @Test
    fun `defaults to utf-8 when no encoding is declared`() {
        val xml = "<?xml version=\"1.0\"?><rss><channel><title>Über den Wolken</title></channel></rss>"
        val bytes = xml.toByteArray(Charsets.UTF_8)

        assertTrue(decodeFeedXml(bytes).contains("Über den Wolken"))
    }

    @Test
    fun `defaults to utf-8 when there is no xml declaration at all`() {
        val xml = "<rss><channel><title>Über den Wolken</title></channel></rss>"
        val bytes = xml.toByteArray(Charsets.UTF_8)

        assertTrue(decodeFeedXml(bytes).contains("Über den Wolken"))
    }

    @Test
    fun `honours a declared non-utf-8 encoding when decoding the characters`() {
        val bytes = ISO_8859_1_XML.toByteArray(Charsets.ISO_8859_1)

        assertTrue(decodeFeedXml(bytes).contains(ISO_8859_1_TITLE))
    }

    @Test
    fun `rewrites the declared encoding to utf-8, since the string is re-encoded as utf-8 downstream`() {
        // rssparser's own parse(String) re-serialises this string as UTF-8 bytes before
        // re-parsing it -- a stale non-UTF-8 declaration left in the text would cause it to
        // mis-decode its own freshly-UTF-8-encoded bytes a second time.
        val bytes = ISO_8859_1_XML.toByteArray(Charsets.ISO_8859_1)

        val decoded = decodeFeedXml(bytes)

        assertTrue(decoded.contains("encoding=\"UTF-8\""))
        assertTrue(!decoded.contains("ISO-8859-1"))
    }

    @Test
    fun `is case-insensitive about the encoding attribute name and quote style`() {
        val xml = "<?xml version='1.0' ENCODING='ISO-8859-1'?><rss><channel><title>Käse</title></channel></rss>"
        val bytes = xml.toByteArray(Charsets.ISO_8859_1)

        assertTrue(decodeFeedXml(bytes).contains("Käse"))
    }

    @Test
    fun `an unrecognised declared charset falls back to utf-8 rather than throwing`() {
        val xml =
            """<?xml version="1.0" encoding="not-a-real-charset"?><rss><channel><title>Title</title></channel></rss>"""
        val bytes = xml.toByteArray(Charsets.UTF_8)

        assertTrue(decodeFeedXml(bytes).contains("Title"))
    }

    @Test
    fun `the real iso-8859-1 fixture round-trips correctly`() {
        val bytes =
            javaClass.classLoader
                ?.getResourceAsStream("feeds/wrong_encoding_iso_8859_1.xml")
                ?.use { it.readBytes() }
        requireNotNull(bytes)

        val decoded = decodeFeedXml(bytes)

        assertTrue(decoded.contains("Über den Wolken"))
        assertTrue(decoded.contains("Käse"))
        assertTrue(decoded.contains("Brötchen"))
    }
}
