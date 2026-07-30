// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.sync

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * The GPodder episode-action `timestamp` field is ISO-8601 **without a timezone offset** (CLAUDE.md
 * section 11) -- a different format, and a different meaning, from the Unix-seconds `since`/
 * response-level `timestamp` used elsewhere in the API (`docs/architecture.md` section 6). The API
 * itself doesn't say which clock a naive timestamp like this represents; Podsilo always renders and
 * reads it as UTC (recorded in `docs/decisions/`) so every device agrees on what the numbers mean
 * regardless of its own local timezone -- inventing per-device local time here would silently break
 * last-write-wins ordering between devices in different zones.
 */
private val GPODDER_TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

fun Long.toGpodderTimestamp(): String =
    Instant
        .ofEpochMilli(this)
        .atZone(ZoneOffset.UTC)
        .toLocalDateTime()
        .format(GPODDER_TIMESTAMP_FORMAT)

/** Returns `null` for a malformed timestamp rather than throwing -- callers decide the fallback. */
fun parseGpodderTimestamp(value: String): Long? =
    try {
        LocalDateTime.parse(value, GPODDER_TIMESTAMP_FORMAT).toInstant(ZoneOffset.UTC).toEpochMilli()
    } catch (
        @Suppress("SwallowedException") malformed: DateTimeParseException,
    ) {
        null
    }
