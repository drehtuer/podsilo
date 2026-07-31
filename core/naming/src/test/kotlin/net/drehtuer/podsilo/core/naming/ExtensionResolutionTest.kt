// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.naming

import org.junit.Assert.assertEquals
import org.junit.Test

class ExtensionResolutionTest {
    @Test
    fun `resolves extension from a plain url`() {
        assertEquals("mp3", resolveExtensionFromUrl("https://example.com/episodes/ep1.mp3"))
    }

    @Test
    fun `is case-insensitive and normalises to lowercase`() {
        assertEquals("m4a", resolveExtensionFromUrl("https://example.com/ep1.M4A"))
    }

    @Test
    fun `ignores the query string`() {
        assertEquals("ogg", resolveExtensionFromUrl("https://example.com/ep1.ogg?token=abc123&x=1"))
    }

    @Test
    fun `ignores the fragment`() {
        assertEquals("opus", resolveExtensionFromUrl("https://example.com/ep1.opus#t=30"))
    }

    @Test
    fun `falls back to mp3 when there is no extension in the path`() {
        assertEquals(DEFAULT_EXTENSION, resolveExtensionFromUrl("https://example.com/episodes/12345"))
    }

    @Test
    fun `falls back to mp3 when the query string contains a dotted segment but the path does not`() {
        assertEquals(DEFAULT_EXTENSION, resolveExtensionFromUrl("https://example.com/stream?file=ep1.mp3"))
    }

    @Test
    fun `does not treat a long dotted segment as an extension`() {
        // Bounded to 1-5 chars so something like a version segment isn't mistaken for an extension.
        assertEquals(DEFAULT_EXTENSION, resolveExtensionFromUrl("https://example.com/ep1.toolongtobeanextension"))
    }

    @Test
    fun `Content-Type wins over a contradicting url extension`() {
        // CLAUDE.md §6: do not trust the enclosure URL's extension. CDNs serve .mp3-looking URLs
        // that are actually AAC-in-MP4 often enough that this ordering is the whole point.
        assertEquals("m4a", resolveExtension("audio/mp4", "https://example.com/ep1.mp3"))
    }

    @Test
    fun `Content-Type parameters and casing are ignored`() {
        assertEquals("mp3", resolveExtension("Audio/MPEG; charset=binary", "https://example.com/stream"))
    }

    @Test
    fun `an unknown Content-Type falls through to the url`() {
        // application/octet-stream is what a badly-configured origin serves for everything.
        assertEquals("opus", resolveExtension("application/octet-stream", "https://example.com/ep1.opus"))
    }

    @Test
    fun `a null Content-Type falls through to the url`() {
        assertEquals("ogg", resolveExtension(null, "https://example.com/ep1.ogg"))
    }

    @Test
    fun `an unknown Content-Type and an extensionless url still yield the mp3 default`() {
        assertEquals(DEFAULT_EXTENSION, resolveExtension("application/octet-stream", "https://example.com/stream"))
    }

    @Test
    fun `the non-mp3 container types podcasts actually use are recognised`() {
        val url = "https://example.com/stream"
        assertEquals("aac", resolveExtension("audio/aac", url))
        assertEquals("ogg", resolveExtension("application/ogg", url))
        assertEquals("opus", resolveExtension("audio/opus", url))
        assertEquals("m4a", resolveExtension("audio/x-m4a", url))
        assertEquals("flac", resolveExtension("audio/flac", url))
    }
}
