// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.naming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidShortTest {
    @Test
    fun `is 8 lowercase hex characters`() {
        val result = guidShort("https://example.com/ep.mp3")
        assertEquals(8, result.length)
        assertTrue(result.matches(Regex("[0-9a-f]{8}")))
    }

    @Test
    fun `is deterministic for the same key`() {
        assertEquals(guidShort("guid-123"), guidShort("guid-123"))
    }

    @Test
    fun `differs for different keys`() {
        assertNotEquals(guidShort("guid-123"), guidShort("guid-456"))
    }
}
