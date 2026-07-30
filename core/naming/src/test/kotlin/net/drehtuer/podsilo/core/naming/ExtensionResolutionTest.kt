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
}
