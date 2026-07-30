// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.naming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Utf8TruncationTest {
    @Test
    fun `input under budget is unchanged`() {
        assertEquals("Episode Title", truncateUtf8Safe("Episode Title", maxBytes = 255))
    }

    @Test
    fun `ascii input is truncated to exactly maxBytes`() {
        val input = "a".repeat(400)
        val result = truncateUtf8Safe(input, maxBytes = 100)
        assertEquals(100, result.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun `zero or negative budget yields empty string`() {
        assertEquals("", truncateUtf8Safe("anything", maxBytes = 0))
        assertEquals("", truncateUtf8Safe("anything", maxBytes = -5))
    }

    @Test
    fun `400 three-byte characters are truncated without splitting a character`() {
        val cjkChar = "日" // one 3-byte UTF-8 character
        val input = cjkChar.repeat(400) // 400 chars, 1200 bytes
        val result = truncateUtf8Safe(input, maxBytes = 100)

        val resultBytes = result.toByteArray(Charsets.UTF_8)
        assertTrue("expected <= 100 bytes, was ${resultBytes.size}", resultBytes.size <= 100)
        // 100 / 3 = 33 whole characters (99 bytes); a 34th would be 102 bytes, over budget.
        assertEquals(33, result.length)
        assertEquals(99, resultBytes.size)
        // Round-tripping through UTF-8 must reproduce the same string -- proof no byte sequence was split.
        assertEquals(result, String(resultBytes, Charsets.UTF_8))
    }

    @Test
    fun `four-byte surrogate-pair characters are truncated on whole-character boundaries`() {
        val emoji = "🎧" // one 4-byte UTF-8 character (surrogate pair)
        val input = emoji.repeat(100) // 400 bytes total
        val result = truncateUtf8Safe(input, maxBytes = 10)

        val resultBytes = result.toByteArray(Charsets.UTF_8)
        assertTrue(resultBytes.size <= 10)
        assertEquals(emoji.repeat(2), result) // 2 * 4 bytes = 8 <= 10; a third would be 12 bytes
        assertEquals(result, String(resultBytes, Charsets.UTF_8))
    }

    @Test
    fun `a combining character cluster is never split from its base character`() {
        val cluster = "ü" // 'u' + combining diaeresis: one grapheme, 3 UTF-8 bytes total
        // Budget fits the base character alone (1 byte) but not the full cluster (3 bytes) --
        // the result must not be a dangling combining mark or a truncated multi-byte mark.
        val result = truncateUtf8Safe(cluster, maxBytes = 2)
        assertEquals("", result)
    }

    @Test
    fun `rtl text is truncated on character boundaries`() {
        val rtl = "مرحبا" // "مرحبا"
        val result = truncateUtf8Safe(rtl, maxBytes = 4) // each Arabic letter is 2 UTF-8 bytes
        assertEquals(2, result.length)
        assertTrue(result.toByteArray(Charsets.UTF_8).size <= 4)
    }
}
