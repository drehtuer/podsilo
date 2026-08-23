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
 * Builds the outbound [EpisodeAction]s for a ledger row, or an empty list if
 * [EpisodeLedgerRow.state] isn't one the API can represent -- CLAUDE.md section 5:
 * download-in-progress/retry/error state is local only, so [LedgerState.QUEUED],
 * [LedgerState.DOWNLOADING], [LedgerState.ERROR] and [LedgerState.HANDLED_REMOTELY] never reach the
 * outbox.
 *
 * Skip-as-`PLAY` encoding matches AntennaPod's own convention: `started = 0` (Podsilo has no resume
 * position -- it never plays audio), `position == total`, and `total` is the duration in seconds if
 * known, else [UNKNOWN_DURATION_SECONDS] -- never a fabricated plausible-looking value.
 *
 * **A completed download also emits `PLAY`** (`decisions/0023`, 2026-08-14). CLAUDE.md §5 used
 * to forbid this in terms, on the grounds that it asserts something untrue and can trigger
 * auto-delete in other clients. The author has ruled the other way, for a reason the original rule
 * did not account for: on this setup a download *is* the end of the episode's life in Podsilo — it
 * goes to a player that never reports back — and `DOWNLOAD` alone is invisible on Nextcloud, so a
 * downloaded episode stayed "new" everywhere else for ever. CLAUDE.md §5 is amended to match.
 */
fun EpisodeLedgerRow.toOutboundActions(): List<EpisodeAction> =
    when (state) {
        // Both, in this order. `DOWNLOAD` is the honest record that this device fetched the file, and
        // it is what a server that keeps it (opodsync) should store. `PLAY` is what makes the episode
        // read as handled in Nextcloud, where `DOWNLOAD` is discarded on arrival
        // (`decisions/0008`) — so on that server the second action is the only one that survives,
        // and on a server that keeps both, the later one wins.
        LedgerState.DOWNLOADED -> listOf(downloadAction(), playedAction())
        LedgerState.SKIPPED -> listOf(playedAction())
        // The one action that *withdraws* a claim rather than making one, and the API's only way of
        // saying it (`decisions/0024`).
        LedgerState.UNPLAYED -> listOf(unplayedAction())
        LedgerState.QUEUED, LedgerState.DOWNLOADING, LedgerState.ERROR, LedgerState.HANDLED_REMOTELY ->
            emptyList()
    }

private fun EpisodeLedgerRow.downloadAction() =
    EpisodeAction(
        podcast = feedUrl,
        episode = enclosureUrl,
        guid = guid,
        action = EpisodeActionType.DOWNLOAD,
        timestamp = actionedAt.toGpodderTimestamp(),
    )

/**
 * `position = 0` with the duration left intact — the encoding every gpodder client already uses for
 * *unread*, and the only one the API offers: it cannot delete an action and has no `UNPLAYED` type.
 *
 * `total` keeps the real duration rather than being zeroed, matching what other clients write and
 * leaving the row readable if the same episode is marked played again later.
 */
private fun EpisodeLedgerRow.unplayedAction(): EpisodeAction =
    EpisodeAction(
        podcast = feedUrl,
        episode = enclosureUrl,
        guid = guid,
        action = EpisodeActionType.PLAY,
        timestamp = actionedAt.toGpodderTimestamp(),
        started = SKIP_STARTED_SECONDS,
        position = 0,
        total = durationSeconds ?: UNKNOWN_DURATION_SECONDS,
    )

/** `position == total > 0` — the encoding every reader treats as *finished* (`decisions/0022`). */
private fun EpisodeLedgerRow.playedAction(): EpisodeAction {
    val total = durationSeconds ?: UNKNOWN_DURATION_SECONDS
    return EpisodeAction(
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
