// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ItunesDurationTest {
    @Test
    fun `plain seconds`() {
        assertEquals(3_600_000L, parseItunesDuration("3600"))
    }

    @Test
    fun `minutes and seconds`() {
        assertEquals((32 * 60 + 15) * 1000L, parseItunesDuration("32:15"))
    }

    @Test
    fun `hours minutes and seconds`() {
        val expected = ((1 * 60 + 32) * 60 + 15) * 1000L
        assertEquals(expected, parseItunesDuration("01:32:15"))
    }

    @Test
    fun `null input yields null`() {
        assertNull(parseItunesDuration(null))
    }

    @Test
    fun `empty or blank input yields null`() {
        assertNull(parseItunesDuration(""))
        assertNull(parseItunesDuration("   "))
    }

    @Test
    fun `garbage input yields null, never invented`() {
        assertNull(parseItunesDuration("not a duration"))
        assertNull(parseItunesDuration("12:ab"))
    }

    @Test
    fun `too many components yields null`() {
        assertNull(parseItunesDuration("1:02:03:04"))
    }

    @Test
    fun `a negative value yields null rather than a negative duration`() {
        assertNull(parseItunesDuration("-5"))
    }

    @Test
    fun `whitespace around the value is trimmed`() {
        assertEquals(3_600_000L, parseItunesDuration("  3600  "))
    }
}
