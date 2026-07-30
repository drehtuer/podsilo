// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.sync

import net.drehtuer.podsilo.core.model.EpisodeLedgerRow
import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.core.model.episodeKey
import net.drehtuer.podsilo.core.model.port.EpisodeAction
import net.drehtuer.podsilo.core.model.port.EpisodeActionType
import java.time.Clock

/** States a remote action must never override -- see `docs/architecture.md` section 9. */
private val TERMINAL_STATES = setOf(LedgerState.DOWNLOADED, LedgerState.SKIPPED, LedgerState.HANDLED_REMOTELY)

/** Action types that mean "this device has handled the episode" -- `NEW` does not (CLAUDE.md section 5/6). */
private val TERMINAL_ACTION_TYPES = setOf(EpisodeActionType.DOWNLOAD, EpisodeActionType.PLAY, EpisodeActionType.DELETE)

/**
 * Reconciles [remoteActions] against [localLedger] (keyed by episode key), returning only the rows
 * that need upserting.
 *
 * Matches CLAUDE.md section 5's identification rule (`guid ?: episode`) and section 9's state
 * machine: local terminal states are never revisited -- an idempotent no-op, which is also what
 * makes replays of our own echoed-back actions harmless, since the wire format carries no device id
 * to check against (`docs/architecture.md` section 6). Duplicate remote actions for the same
 * episode within one batch are resolved by latest timestamp, with ties won by the later entry in
 * [remoteActions] -- deterministic, not a guess at true wall-clock ordering under clock skew.
 *
 * A remote action for an episode not in any currently-subscribed feed is still processed -- the
 * ledger is keyed by episode, not by subscription (CLAUDE.md section 5).
 */
fun reconcile(
    localLedger: Map<String, EpisodeLedgerRow>,
    remoteActions: List<EpisodeAction>,
    clock: Clock,
): List<EpisodeLedgerRow> {
    val winningActionByKey = mutableMapOf<String, EpisodeAction>()
    val winningTimestampByKey = mutableMapOf<String, Long>()

    for (action in remoteActions) {
        if (action.action !in TERMINAL_ACTION_TYPES) continue
        val key = episodeKey(action.guid, action.episode)
        val parsedTimestamp = parseGpodderTimestamp(action.timestamp) ?: clock.millis()
        val currentBest = winningTimestampByKey[key]
        if (currentBest == null || parsedTimestamp >= currentBest) {
            winningActionByKey[key] = action
            winningTimestampByKey[key] = parsedTimestamp
        }
    }

    return winningActionByKey.mapNotNull { (key, action) ->
        val existing = localLedger[key]
        if (existing != null && existing.state in TERMINAL_STATES) return@mapNotNull null

        EpisodeLedgerRow(
            episodeKey = key,
            feedUrl = action.podcast,
            enclosureUrl = action.episode,
            state = LedgerState.HANDLED_REMOTELY,
            actionedAt = winningTimestampByKey.getValue(key),
            syncedToServer = true,
            attempts = 0,
            lastError = null,
            writtenFileName = existing?.writtenFileName,
            durationSeconds = existing?.durationSeconds,
        )
    }
}
