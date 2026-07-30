// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.naming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReservedNamesTest {
    @Test
    fun `CON PRN AUX NUL are reserved case-insensitively`() {
        for (name in listOf("CON", "con", "Con", "PRN", "AUX", "NUL")) {
            assertTrue(name, isReservedName(name))
        }
    }

    @Test
    fun `COM1 through COM9 are reserved`() {
        for (n in 1..9) assertTrue("COM$n", isReservedName("COM$n"))
    }

    @Test
    fun `LPT1 through LPT9 are reserved`() {
        for (n in 1..9) assertTrue("LPT$n", isReservedName("LPT$n"))
    }

    @Test
    fun `COM0 and LPT0 are not reserved`() {
        assertFalse(isReservedName("COM0"))
        assertFalse(isReservedName("LPT0"))
    }

    @Test
    fun `a name that merely contains a reserved word is not reserved`() {
        assertFalse(isReservedName("CONcert"))
        assertFalse(isReservedName("Falcon"))
    }

    @Test
    fun `ordinary names are not reserved`() {
        assertFalse(isReservedName("Episode 1"))
    }

    @Test
    fun `escapeReservedName appends a suffix only when reserved`() {
        assertEquals("CON_", escapeReservedName("CON"))
        assertEquals("Episode 1", escapeReservedName("Episode 1"))
    }
}
