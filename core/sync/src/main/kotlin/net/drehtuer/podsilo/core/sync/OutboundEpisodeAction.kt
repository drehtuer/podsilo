// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.sync

import net.drehtuer.podsilo.core.model.EpisodeLedgerRow
import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.core.model.port.EpisodeAction
import net.drehtuer.podsilo.core.model.port.EpisodeActionType

private const val SKIP_STARTED_SECONDS = 0
private const val UNKNOWN_DURATION_SECONDS = 0

/**
 * Builds the outbound [EpisodeAction] for a ledger row, or `null` if [EpisodeLedgerRow.state] isn't
 * one the API can represent -- CLAUDE.md section 5: download-in-progress/retry/error state is local
 * only, so [LedgerState.QUEUED], [LedgerState.DOWNLOADING], [LedgerState.ERROR], and
 * [LedgerState.HANDLED_REMOTELY] never reach the outbox.
 *
 * Skip-as-`PLAY` encoding matches AntennaPod's own convention (see `docs/decisions/`): `started = 0`
 * (Podsilo has no resume position -- it never plays audio), `position == total`, and `total` is the
 * duration in seconds if known, else `0` -- never a fabricated plausible-looking value.
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
