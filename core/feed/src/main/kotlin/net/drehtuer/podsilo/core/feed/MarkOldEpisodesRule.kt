// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.feed

import kotlinx.coroutines.flow.first
import net.drehtuer.podsilo.core.model.Episode
import net.drehtuer.podsilo.core.model.EpisodeLedgerRow
import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.core.model.port.BulkScope
import net.drehtuer.podsilo.core.model.port.BulkScopeKind
import net.drehtuer.podsilo.core.model.port.EpisodeLedgerRepository
import net.drehtuer.podsilo.core.model.port.EpisodeListRepository
import net.drehtuer.podsilo.core.model.port.OlderThan
import net.drehtuer.podsilo.core.model.port.SettingsRepository
import java.time.Clock
import java.time.ZoneId

private const val MILLIS_PER_SECOND = 1_000

/**
 * *Mark old episodes as played*, applied to whatever a refresh just parsed (`docs/decisions/0013`).
 *
 * Once the user has set an *older than* cutoff, an episode arriving already older than it is marked
 * immediately and without a preview — they consented once, at the setting, and re-asking on every
 * refresh would make the setting pointless. Until they set one, this does nothing at all and does
 * not even query.
 *
 * **It writes `SKIPPED` and only `SKIPPED`.** It has no download dependency, so a `QUEUED` row is
 * not merely absent here but unreachable — which is what keeps CLAUDE.md §1's no-auto-download rule
 * structural now that a refresh writes ledger rows at all. The rows enter the normal outbox
 * (`syncedToServer = false`) and `SyncWorker` pushes the `PLAY` actions in its own batches; nothing
 * here posts.
 *
 * Separate from [FeedRefresher] because it is a separate decision with separate dependencies — the
 * refresher fetches and parses, this decides what the user already said about what came back.
 */
class MarkOldEpisodesRule(
    private val ledgerRepository: EpisodeLedgerRepository,
    private val listRepository: EpisodeListRepository,
    private val settingsRepository: SettingsRepository,
    private val clock: Clock,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    /** @return how many episodes were marked; `0` when the rule is off or nothing qualified. */
    suspend fun apply(): Int {
        val cutoff =
            settingsRepository
                .observeMarkOldOlderThan()
                .first()
                .takeIf { it != OlderThan.OFF }
                ?.cutoffMillis(clock.instant(), zone)
                ?: return 0

        val stale = listRepository.undecided(BulkScope(kind = BulkScopeKind.OLDER_THAN, olderThanMillis = cutoff))
        if (stale.isEmpty()) return 0

        val now = clock.millis()
        // One transaction and one Flow emission, not one per episode: this routinely touches
        // hundreds of rows and the list underneath is on screen.
        ledgerRepository.upsertAll(stale.map { it.toSkippedRow(now) })
        return stale.size
    }
}

private fun Episode.toSkippedRow(now: Long): EpisodeLedgerRow =
    EpisodeLedgerRow(
        episodeKey = episodeKey,
        feedUrl = feedUrl,
        enclosureUrl = enclosureUrl,
        state = LedgerState.SKIPPED,
        actionedAt = now,
        syncedToServer = false,
        attempts = 0,
        lastError = null,
        writtenFileName = null,
        // Snapshotted so the outbox can encode the PLAY action's total/position even if the episode
        // row is pruned before the push (`docs/architecture.md` §4 and 0002).
        durationSeconds = durationMs?.let { (it / MILLIS_PER_SECOND).toInt() },
    )
