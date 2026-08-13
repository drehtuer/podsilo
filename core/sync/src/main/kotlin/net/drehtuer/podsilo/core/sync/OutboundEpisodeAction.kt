// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.sync

import net.drehtuer.podsilo.core.model.EpisodeLedgerRow
import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.core.model.port.EpisodeAction
import net.drehtuer.podsilo.core.model.port.EpisodeActionType

private const val SKIP_STARTED_SECONDS = 0

/**
 * What a skip puts in `position`/`total` when the feed declared no usable duration.
 *
 * **Not a duration, and deliberately not a plausible one.** CLAUDE.md §6 forbids inventing a
 * plausible-looking duration, and this is the smallest value that is not one: a single second says
 * "there was something and it is finished", which is exactly the claim a skip makes. Forty-five
 * invented minutes would be a lie another client could display.
 *
 * It has to be non-zero because of how the reading side works. RePod — the Nextcloud web client the
 * author actually looks at — decides *played* with `position > 0 && total > 0 && position >= total`
 * (`src/utils/status.ts`). A `0/0` action is stored, is returned by the API, and renders as
 * **unplayed for ever**: there is no code path in RePod by which it ever shows as read. Verified
 * against the author's own server on 2026-08-13 before this value was chosen.
 */
private const val UNKNOWN_DURATION_SECONDS = 1

/**
 * Builds the outbound [EpisodeAction] for a ledger row, or `null` if [EpisodeLedgerRow.state] isn't
 * one the API can represent -- CLAUDE.md section 5: download-in-progress/retry/error state is local
 * only, so [LedgerState.QUEUED], [LedgerState.DOWNLOADING], [LedgerState.ERROR], and
 * [LedgerState.HANDLED_REMOTELY] never reach the outbox.
 *
 * Skip-as-`PLAY` encoding matches AntennaPod's own convention: `started = 0` (Podsilo has no resume
 * position -- it never plays audio), `position == total`, and `total` is the duration in seconds if
 * known, else [UNKNOWN_DURATION_SECONDS] -- never a fabricated plausible-looking value.
 */
fun EpisodeLedgerRow.toOutboundAction(): EpisodeAction? =
    when (state) {
        LedgerState.DOWNLOADED ->
            EpisodeAction(
                podcast = feedUrl,
                episode = enclosureUrl,
                guid = guid,
                action = EpisodeActionType.DOWNLOAD,
                timestamp = actionedAt.toGpodderTimestamp(),
            )
        LedgerState.SKIPPED -> {
            val total = durationSeconds ?: UNKNOWN_DURATION_SECONDS
            EpisodeAction(
                podcast = feedUrl,
                episode = enclosureUrl,
                guid = guid,
                action = EpisodeActionType.PLAY,
                timestamp = actionedAt.toGpodderTimestamp(),
                started = SKIP_STARTED_SECONDS,
                position = total,
                total = total,
            )
        }
        LedgerState.QUEUED, LedgerState.DOWNLOADING, LedgerState.ERROR, LedgerState.HANDLED_REMOTELY -> null
    }
