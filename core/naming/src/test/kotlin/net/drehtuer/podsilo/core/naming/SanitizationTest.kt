// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.naming

import org.junit.Assert.assertEquals
import org.junit.Test

class SanitizationTest {
    @Test
    fun `replaces a run of illegal characters with a single separator`() {
        assertEquals("Foo_Bar", sanitizeComponent("Foo<>:\"Bar", transliterate = false))
    }

    @Test
    fun `each of the nine illegal characters is replaced`() {
        val illegal = listOf('<', '>', ':', '"', '/', '\\', '|', '?', '*')
        for (char in illegal) {
            assertEquals("Foo_Bar", sanitizeComponent("Foo${char}Bar", transliterate = false))
        }
    }

    @Test
    fun `control characters are replaced`() {
        assertEquals("Foo_Bar", sanitizeComponent("Foo${0x01.toChar()}${0x1F.toChar()}Bar", transliterate = false))
    }

    @Test
    fun `space and hyphen are preserved, not treated as illegal`() {
        assertEquals("Foo Bar-Baz", sanitizeComponent("Foo Bar-Baz", transliterate = false))
    }

    @Test
    fun `trailing dots and spaces are stripped`() {
        assertEquals("Episode Title", sanitizeComponent("Episode Title. . ", transliterate = false))
    }

    @Test
    fun `leading dots are preserved`() {
        assertEquals(".hidden style prefix", sanitizeComponent(".hidden style prefix", transliterate = false))
    }

    @Test
    fun `whitespace runs collapse to a single space`() {
        assertEquals("Foo Bar", sanitizeComponent("Foo   \t  Bar", transliterate = false))
    }

    @Test
    fun `umlauts survive by default without transliteration`() {
        val umlauts = "Über Bär" // "Über Bär"
        assertEquals(umlauts, sanitizeComponent(umlauts, transliterate = false))
    }

    @Test
    fun `cjk characters survive untouched`() {
        val cjk = "日本語のエピソード" // "日本語のエピソード"
        assertEquals(cjk, sanitizeComponent(cjk, transliterate = false))
    }

    @Test
    fun `rtl text survives untouched`() {
        val rtl = "مرحبا بالعالم" // "مرحبا بالعالم"
        assertEquals(rtl, sanitizeComponent(rtl, transliterate = false))
    }

    @Test
    fun `emoji survive untouched`() {
        val withEmoji = "Episode 🎧🔥" // "Episode 🎧🔥"
        assertEquals(withEmoji, sanitizeComponent(withEmoji, transliterate = false))
    }

    @Test
    fun `nfd input is normalised to nfc`() {
        val nfdUmlaut = "ü" // 'u' + combining diaeresis (U+0308) -- decomposed form
        val result = sanitizeComponent(nfdUmlaut, transliterate = false)
        assertEquals("ü", result) // precomposed 'ü' (U+00FC), single codepoint
        assertEquals(1, result.codePointCount(0, result.length))
    }

    @Test
    fun `transliteration maps german umlauts and eszett when enabled`() {
        val input = "Über die Straße" // "Über die Straße"
        assertEquals("Ueber die Strasse", sanitizeComponent(input, transliterate = true))
    }

    @Test
    fun `transliteration is off by default behaviour when flag is false`() {
        val input = "Über" // "Über"
        assertEquals(input, sanitizeComponent(input, transliterate = false))
    }

    @Test
    fun `a run of illegal characters becomes a single separator, not empty`() {
        // Illegal characters are replaced, never deleted -- CLAUDE.md section 6 -- so this alone
        // never triggers the "empty after sanitising" fallback; only whitespace-only or
        // dots-and-spaces-only input (or an empty string to begin with) does.
        assertEquals("_", sanitizeComponent("///???", transliterate = false))
    }

    @Test
    fun `input that is entirely dots and spaces sanitises to empty`() {
        assertEquals("", sanitizeComponent("...", transliterate = false))
    }

    @Test
    fun `whitespace-only input sanitises to empty`() {
        assertEquals("", sanitizeComponent("   ", transliterate = false))
    }

    @Test
    fun `empty input sanitises to empty`() {
        assertEquals("", sanitizeComponent("", transliterate = false))
    }
}
