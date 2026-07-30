// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.naming

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class DateVariableTest {
    private val utc: ZoneId = ZoneOffset.UTC

    @Test
    fun `formats using the default yyyyMMdd pattern`() {
        val pubDate = Instant.parse("2026-07-14T09:00:00Z").toEpochMilli()
        assertEquals("20260714", formatDate(pubDate, utc))
    }

    @Test
    fun `supports an explicit custom pattern`() {
        val pubDate = Instant.parse("2026-07-14T09:00:00Z").toEpochMilli()
        assertEquals("2026-07-14", formatDate(pubDate, utc, pattern = "yyyy-MM-dd"))
    }

    @Test
    fun `missing pubDate falls back to the sortable placeholder, never an empty string`() {
        assertEquals(FALLBACK_DATE, formatDate(null, utc))
        assertEquals("00000000", FALLBACK_DATE)
    }

    @Test
    fun `a malformed custom pattern falls back to the sortable placeholder instead of throwing`() {
        val pubDate = Instant.parse("2026-07-14T09:00:00Z").toEpochMilli()
        assertEquals(FALLBACK_DATE, formatDate(pubDate, utc, pattern = "not a valid pattern {{{"))
    }

    @Test
    fun `formatting is a pure function of the injected zone, not the JVM default zone`() {
        val pubDate = Instant.parse("2026-07-14T23:30:00Z").toEpochMilli()
        // Fixed zones must each be internally consistent -- this is not asserting zones agree with
        // each other (they legitimately differ), only that formatDate is a pure function of the
        // zone it's given, not of the JVM's default zone.
        val tokyo = formatDate(pubDate, ZoneId.of("Asia/Tokyo"))
        val losAngeles = formatDate(pubDate, ZoneId.of("America/Los_Angeles"))
        assertEquals("20260715", tokyo) // next day in UTC+9
        assertEquals("20260714", losAngeles) // still same day in UTC-7 (DST)
    }
}
