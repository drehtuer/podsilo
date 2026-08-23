// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.sync

import net.drehtuer.podsilo.core.model.EpisodeLedgerRow
import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.core.model.episodeKey
import net.drehtuer.podsilo.core.model.port.EpisodeAction
import net.drehtuer.podsilo.core.model.port.EpisodeActionType
import java.time.Clock

/** States a remote action must never override -- see `docs/architecture.adoc` section 9. */
private val TERMINAL_STATES = setOf(LedgerState.DOWNLOADED, LedgerState.SKIPPED, LedgerState.HANDLED_REMOTELY)

/**
 * Whether a remote action means *another client has finished with this episode*, so this device must
 * not offer it for download.
 *
 * **A `PLAY` is not automatically that**, which is the correction this function exists for. Until
 * 2026-08-14 the type alone decided, and every `PLAY` was terminal — but the gpodder API has no way
 * to delete an action or to say "unread", so **that is exactly how a client says it**: RePod's *mark
 * as unread* writes a `PLAY` with `position = 0`, keeping whatever `total` the row already had
 * (`markAs` in `src/utils/status.ts`). Reading the type alone therefore turned "I have *not* listened
 * to this" into `HANDLED_REMOTELY` — terminal, never revisited, and silently the opposite of what the
 * user asked for. Observed on the author's own server: five of six actions in one window disagreed.
 *
 * The rule is RePod's own `hasEnded`, transcribed rather than approximated, because it is the client
 * the author reads the state in and one shared rule beats two nearly-identical ones:
 *
 * ```js
 * action.action.toLowerCase() === 'delete'
 *   || (action.position > 0 && action.total > 0 && action.position >= action.total)
 * ```
 *
 * `DOWNLOAD` is ours to add and does not conflict: RePod's question is "was it *played*", ours is
 * "has another client *handled* it", and CLAUDE.md §5 says a remote `DOWNLOAD` means do not download
 * it here. `NEW` means neither.
 *
 * A `PLAY` with no playback values at all reads as *not* ended, since the server's own absent-value
 * sentinel has already been normalised to `null` at the client boundary. That is the same answer
 * RePod gives such an action, which is the point of copying the rule.
 */
fun EpisodeAction.meansHandledElsewhere(): Boolean =
    when (action) {
        EpisodeActionType.DOWNLOAD, EpisodeActionType.DELETE -> true
        EpisodeActionType.PLAY -> hasEnded()
        EpisodeActionType.NEW -> false
    }

private fun EpisodeAction.hasEnded(): Boolean {
    val at = position ?: 0
    val end = total ?: 0
    return at > 0 && end > 0 && at >= end
}

/**
 * Reconciles [remoteActions] against [localLedger] (keyed by episode key), returning only the rows
 * that need upserting.
 *
 * Matches CLAUDE.md section 5's identification rule (`guid ?: episode`) and section 9's state
 * machine: local terminal states are never revisited -- an idempotent no-op, which is also what
 * makes replays of our own echoed-back actions harmless, since the wire format carries no device id
 * to check against (`docs/architecture.adoc` section 6). Duplicate remote actions for the same
 * episode within one batch are resolved by latest timestamp, with ties won by the later entry in
 * [remoteActions] -- deterministic, not a guess at true wall-clock ordering under clock skew.
 *
 * A remote action for an episode not in any currently-subscribed feed is still processed -- the
 * ledger is keyed by episode, not by subscription (CLAUDE.md section 5).
 *
 * **Which actions count is [meansHandledElsewhere], and it is not simply "the type".** Read its
 * KDoc before changing anything here.
 */
fun reconcile(
    localLedger: Map<String, EpisodeLedgerRow>,
    remoteActions: List<EpisodeAction>,
    clock: Clock,
): List<EpisodeLedgerRow> {
    val winningActionByKey = mutableMapOf<String, EpisodeAction>()
    val winningTimestampByKey = mutableMapOf<String, Long>()

    for (action in remoteActions) {
        if (!action.meansHandledElsewhere()) continue
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
