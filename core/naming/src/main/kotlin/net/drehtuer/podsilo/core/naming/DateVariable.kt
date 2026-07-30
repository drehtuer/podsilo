// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.naming

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** `{date}`'s default pattern, deliberately date-first so filenames sort correctly (CLAUDE.md section 6). */
const val DEFAULT_DATE_PATTERN = "yyyyMMdd"

/**
 * Sortable placeholder for a missing/unusable `pubDate`. CLAUDE.md section 6: "never emit a
 * filename beginning with an empty or partial date; a missing date must degrade to something
 * sortable, not to `_Title.mp3`" -- an empty string would do exactly that, so an explicit all-zero
 * date is used instead, sorting before every real date.
 *
 * This is the module's own defensive fallback for a `null` `pubDate` reaching it directly; the
 * primary fallback chain CLAUDE.md section 6 describes (other date field -> date first seen
 * locally) is `:core:feed`'s responsibility -- by the time an [net.drehtuer.podsilo.core.model.Episode]
 * reaches this module, `pubDate` is expected to already carry the best available value.
 */
const val FALLBACK_DATE = "00000000"

/**
 * Formats [pubDateEpochMillis] in [zoneId] -- a fixed zone chosen once by the caller, not
 * re-resolved per call, so the same episode never produces two different dates across two syncs
 * even if the device's timezone setting changes in between (open decision resolved in
 * `docs/decisions/`; see also CLAUDE.md section 6).
 */
fun formatDate(
    pubDateEpochMillis: Long?,
    zoneId: ZoneId,
    pattern: String = DEFAULT_DATE_PATTERN,
): String {
    if (pubDateEpochMillis == null) return FALLBACK_DATE
    return try {
        Instant.ofEpochMilli(pubDateEpochMillis).atZone(zoneId).format(DateTimeFormatter.ofPattern(pattern))
    } catch (
        @Suppress("SwallowedException") malformedPattern: IllegalArgumentException,
    ) {
        // A malformed user-supplied pattern is an expected input error, not a bug to surface --
        // degrade to the same sortable placeholder used for a missing pubDate (CLAUDE.md section 6).
        FALLBACK_DATE
    }
}
