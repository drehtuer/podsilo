// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import net.drehtuer.podsilo.core.model.Episode
import net.drehtuer.podsilo.core.model.EpisodeLedgerRow
import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.core.model.port.EpisodeLedgerRepository
import net.drehtuer.podsilo.core.model.port.SyncTrigger
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
    private val syncTrigger: SyncTrigger,
) {
    /**
     * Marks [episodes] as played, in **one transaction and one `Flow` emission** — bulk triage
     * routinely covers hundreds of rows and the list underneath is on screen.
     *
     * `writtenFileName` survives a `DOWNLOADED` → `SKIPPED` transition (`decisions/0012` §3a):
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
        // Issue #60: this is the *only* reason a skip ever reaches Nextcloud promptly, and it did
        // not exist. Nothing asked for a pass after a triage decision, so a skip waited for a
        // completed download or an app-bar tap — and since `decisions/0026` removed the
        // periodic pass, there is no timer left to catch it either. One request per call, not per
        // episode: the write above is one transaction and this is one pass, however many rows it
        // covered.
        syncTrigger.requestSyncNow()
    }

    /**
     * Puts [episodes] back into *To decide* — the inverse of [markAsPlayed] (`decisions/0024`).
     *
     * **Writes a row rather than deleting one.** "Undecided" is normally the absence of a row, so the
     * obvious implementation is a delete — and that is exactly what CLAUDE.md §11 forbids, because
     * the row is the only thing that stops an episode being downloaded twice. `UNPLAYED` keeps
     * `writtenFileName` and the history while the list treats the episode as undecided again.
     *
     * It reaches Nextcloud like any other decision: `PLAY` with `position = 0`, which is how the API
     * expresses *unread* (`decisions/0022`).
     */
    suspend fun markAsUnplayed(episodes: List<Episode>) {
        if (episodes.isEmpty()) return
        val now = clock.millis()
        val existing = episodes.associate { it.episodeKey to ledgerRepository.get(it.episodeKey) }
        ledgerRepository.upsertAll(
            episodes.map { episode ->
                episode.toRow(
                    state = LedgerState.UNPLAYED,
                    now = now,
                    writtenFileName = existing[episode.episodeKey]?.writtenFileName,
                )
            },
        )
        syncTrigger.requestSyncNow()
    }

    /**
     * Marks [episodes] `QUEUED` so the list reflects the decision immediately, before any worker
     * runs. The download itself is enqueued by the caller through `WorkScheduler` — this class never
     * touches WorkManager (`UI.adoc` §B0.2).
     *
     * **No sync is requested here, and that is not an omission.** `QUEUED` has no outbound action —
     * `toOutboundActions` returns nothing for it, because "I intend to download this" is local state
     * the API cannot express. The `DOWNLOAD` action exists only once the file has landed, and
     * `DownloadWorker` asks for the pass then.
     *
     * `attempts` resets to 0 and `lastError` clears, per `decisions/0012` §3: a re-decision is a
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
            // row is pruned before the push (`architecture.adoc` §4).
            durationSeconds = durationMs?.let { (it / MILLIS_PER_SECOND).toInt() },
        )
}
