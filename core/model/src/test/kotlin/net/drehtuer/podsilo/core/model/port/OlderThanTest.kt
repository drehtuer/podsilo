// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.model.port

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * `decisions/0013`'s cutoff. Calendar arithmetic, not fixed day counts — this rule *writes*
 * `SKIPPED` rows and emits `PLAY` actions other clients act on, so "3 months" has to mean what the
 * label says rather than 90 days.
 */
class OlderThanTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    @Test
    fun `off has no cutoff at all`() {
        assertNull(OlderThan.OFF.cutoffMillis(Instant.parse("2026-08-01T12:00:00Z"), berlin))
    }

    @Test
    fun `three months means three calendar months, not ninety days`() {
        val now = Instant.parse("2026-08-01T12:00:00Z")
        val cutoff = OlderThan.MONTH_3.cutoffMillis(now, berlin)

        assertEquals(Instant.parse("2026-05-01T12:00:00Z").toEpochMilli(), cutoff)
    }

    @Test
    fun `month lengths differ, so a month back from 31 March lands on 28 February`() {
        // Fixed-day arithmetic would give 1 March here and silently spare a day's worth of episodes.
        //
        // The result is 10:00Z, not the 09:00Z the input suggests, and that is correct rather than
        // drift: 31 March is CEST (+2) while 28 February is CET (+1), and subtracting a Period
        // preserves the *wall clock* — 11:00 local stays 11:00 local, which is one hour later in
        // UTC. A cutoff the user set by calendar should move by calendar.
        val cutoff = OlderThan.MONTH_1.cutoffMillis(Instant.parse("2026-03-31T09:00:00Z"), berlin)

        assertEquals(Instant.parse("2026-02-28T10:00:00Z").toEpochMilli(), cutoff)
    }

    @Test
    fun `a year back from a leap day lands on 28 February`() {
        val cutoff = OlderThan.YEAR_1.cutoffMillis(Instant.parse("2028-02-29T09:00:00Z"), berlin)

        assertEquals(Instant.parse("2027-02-28T09:00:00Z").toEpochMilli(), cutoff)
    }

    @Test
    fun `the zone is the one passed in, not the runner's default`() {
        // Crossing a DST boundary is where a zone-unaware implementation drifts by an hour. The
        // zone is a parameter for the same reason :core:naming takes one (`architecture.adoc` §11).
        val now = Instant.parse("2026-04-15T00:30:00Z")

        val berlinCutoff = OlderThan.MONTH_1.cutoffMillis(now, berlin)
        val utcCutoff = OlderThan.MONTH_1.cutoffMillis(now, ZoneId.of("UTC"))

        // Berlin was on CET in March and CEST in April, so the wall-clock-preserving subtraction
        // yields an instant one hour later than the UTC one.
        assertEquals(3_600_000L, berlinCutoff!! - utcCutoff!!)
    }

    @Test
    fun `every non-off value produces a cutoff in the past`() {
        val now = Instant.parse("2026-08-01T12:00:00Z")

        OlderThan.entries.filter { it != OlderThan.OFF }.forEach { value ->
            val cutoff = requireNotNull(value.cutoffMillis(now, berlin)) { "$value should have a cutoff" }
            assert(cutoff < now.toEpochMilli()) { "$value produced a cutoff that is not in the past" }
        }
    }
}
