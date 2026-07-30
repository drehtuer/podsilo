// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class GpodderTimestampsTest {
    @Test
    fun `formats an epoch millis instant as UTC iso-8601 without an offset`() {
        val epochMillis = Instant.parse("2026-07-14T09:00:00Z").toEpochMilli()
        assertEquals("2026-07-14T09:00:00", epochMillis.toGpodderTimestamp())
    }

    @Test
    fun `round-trips through parse`() {
        val epochMillis = Instant.parse("2026-07-14T09:00:00Z").toEpochMilli()
        assertEquals(epochMillis, parseGpodderTimestamp(epochMillis.toGpodderTimestamp()))
    }

    @Test
    fun `a malformed timestamp parses to null rather than throwing`() {
        assertNull(parseGpodderTimestamp("not a timestamp"))
    }

    @Test
    fun `a timestamp with an offset -- the wrong format for this field -- parses to null`() {
        assertNull(parseGpodderTimestamp("2026-07-14T09:00:00+02:00"))
    }

    @Test
    fun `an empty string parses to null`() {
        assertNull(parseGpodderTimestamp(""))
    }
}
