// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.sync

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoField

/**
 * The GPodder episode-action `timestamp` field is ISO-8601, and is a different format -- and a
 * different meaning -- from the Unix-seconds `since`/response-level `timestamp` used elsewhere in
 * the API (`docs/architecture.md` section 6).
 *
 * **Servers disagree on whether it carries an offset**, so parsing is deliberately lenient
 * (verified against both reference implementations -- see `docs/decisions/0003`):
 * - `nextcloud-gpodder` emits an offset (PHP `format("c")` -> `2021-10-06T11:49:23+00:00`)
 * - `opodsync` emits a trailing `Z` (`2021-10-06T11:49:23Z`)
 * - the gpodder API README shows a bare form (`2009-12-12T09:00:00`), which is what CLAUDE.md
 *   section 11 documents -- stale relative to the servers, but still worth accepting
 *
 * A bare timestamp carries no zone information at all, so *something* has to be assumed for it;
 * Podsilo assumes UTC ([ASSUMED_ZONE]) so that every device agrees on what the number means
 * regardless of its own zone setting. Inventing per-device local time would silently break
 * last-write-wins ordering between devices in different zones.
 */
private val ASSUMED_ZONE = ZoneOffset.UTC

/** Bare ISO local date-time, with an optional offset -- covers all three forms above. */
private val GPODDER_TIMESTAMP_PARSER: DateTimeFormatter =
    DateTimeFormatterBuilder()
        .append(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        .optionalStart()
        .appendOffsetId()
        .optionalEnd()
        .parseDefaulting(ChronoField.OFFSET_SECONDS, ASSUMED_ZONE.totalSeconds.toLong())
        .toFormatter()

/**
 * Emits the bare form CLAUDE.md section 11 specifies, rendered in UTC. Both servers parse it:
 * `nextcloud-gpodder` reads the POST timestamp as `new DateTime($ts, new DateTimeZone("UTC"))`,
 * which treats an offset-less value as UTC -- matching what's meant here.
 */
fun Long.toGpodderTimestamp(): String =
    Instant
        .ofEpochMilli(this)
        .atZone(ASSUMED_ZONE)
        .toLocalDateTime()
        .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

/**
 * Returns `null` for a malformed timestamp rather than throwing -- callers decide the fallback.
 *
 * Parses to [OffsetDateTime], **not** [LocalDateTime]: a `LocalDateTime` silently discards any
 * offset the server sent, so `...T11:49:23+02:00` would be read as 11:49 UTC instead of 09:49 UTC
 * -- a two-hour error that no test asserting only on bare/`Z` timestamps would ever catch.
 */
fun parseGpodderTimestamp(value: String): Long? =
    try {
        OffsetDateTime
            .parse(value.trim(), GPODDER_TIMESTAMP_PARSER)
            .toInstant()
            .toEpochMilli()
    } catch (
        @Suppress("SwallowedException") malformed: DateTimeParseException,
    ) {
        null
    }
