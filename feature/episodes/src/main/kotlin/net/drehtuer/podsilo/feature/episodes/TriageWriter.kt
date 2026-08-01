// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import net.drehtuer.podsilo.core.model.Episode
import net.drehtuer.podsilo.core.model.EpisodeLedgerRow
import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.core.model.port.EpisodeLedgerRepository
import java.time.Clock

private const val MILLIS_PER_SECOND = 1_000

/**
 * Writes triage decisions to the ledger — the one place a UI action becomes durable state.
 *
 * Extracted from the view models because S1, S2, S3 and (later) S7 all triage, and a decision
 * written four slightly different ways is four chances to drop `writtenFileName` or forget that a
 * local write is always unsynced.
 *
 * **Every row it writes has `syncedToServer = false`.** That is not an oversight to tidy up: the
 * durable row must exist *before* anything is posted, and only a confirmed 2xx may flip the flag —
 * which only the sync pass does (CLAUDE.md §5's mark-on-download rule).
 */
class TriageWriter(
    private val ledgerRepository: EpisodeLedgerRepository,
    private val clock: Clock,
) {
    /**
     * Marks [episodes] as played, in **one transaction and one `Flow` emission** — bulk triage
     * routinely covers hundreds of rows and the list underneath is on screen.
     *
     * `writtenFileName` survives a `DOWNLOADED` → `SKIPPED` transition (`docs/decisions/0012` §3a):
     * losing it would silently disarm the duplicate guard, so a later *Download again* would write a
     * second copy of a file already in the folder.
     */
    suspend fun markAsPlayed(episodes: List<Episode>) {
        if (episodes.isEmpty()) return
        val now = clock.millis()
        val existing = episodes.associate { it.episodeKey to ledgerRepository.get(it.episodeKey) }
        ledgerRepository.upsertAll(
            episodes.map { episode ->
                episode.toRow(
                    state = LedgerState.SKIPPED,
                    now = now,
                    writtenFileName = existing[episode.episodeKey]?.writtenFileName,
                )
            },
        )
    }

    /**
     * Marks [episodes] `QUEUED` so the list reflects the decision immediately, before any worker
     * runs. The download itself is enqueued by the caller through `WorkScheduler` — this class never
     * touches WorkManager (`docs/UI_interface.md` §0.2).
     *
     * `attempts` resets to 0 and `lastError` clears, per `docs/decisions/0012` §3: a re-decision is a
     * new attempt chain, and a fresh download that rendered as "attempt 3 of 3" would look exhausted
     * before it started.
     */
    suspend fun queue(episodes: List<Episode>) {
        if (episodes.isEmpty()) return
        val now = clock.millis()
        val existing = episodes.associate { it.episodeKey to ledgerRepository.get(it.episodeKey) }
        ledgerRepository.upsertAll(
            episodes.map { episode ->
                episode.toRow(
                    state = LedgerState.QUEUED,
                    now = now,
                    writtenFileName = existing[episode.episodeKey]?.writtenFileName,
                )
            },
        )
    }

    private fun Episode.toRow(
        state: LedgerState,
        now: Long,
        writtenFileName: String?,
    ): EpisodeLedgerRow =
        EpisodeLedgerRow(
            episodeKey = episodeKey,
            feedUrl = feedUrl,
            enclosureUrl = enclosureUrl,
            state = state,
            actionedAt = now,
            syncedToServer = false,
            attempts = 0,
            lastError = null,
            writtenFileName = writtenFileName,
            // Snapshotted at write time so the outbox can still build a valid action if the episode
            // row is pruned before the push (docs/decisions/0001).
            durationSeconds = durationMs?.let { (it / MILLIS_PER_SECOND).toInt() },
        )
}
