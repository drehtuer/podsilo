// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import net.drehtuer.podsilo.core.model.Feed
import net.drehtuer.podsilo.core.model.SyncOutcome
import net.drehtuer.podsilo.core.model.SyncState
import net.drehtuer.podsilo.core.model.port.EpisodeLedgerRepository
import net.drehtuer.podsilo.core.model.port.FeedRepository
import net.drehtuer.podsilo.core.model.port.GpodderClient
import net.drehtuer.podsilo.core.model.port.LedgerFilter
import net.drehtuer.podsilo.core.model.port.LedgerFilterState
import net.drehtuer.podsilo.core.model.port.SyncStateRepository
import java.io.IOException
import java.time.Clock

/**
 * Runs one full sync pass in the exact order CLAUDE.md section 5 mandates: pull subscriptions
 * (full) -> push unsynced ledger rows -> pull episode actions since last timestamp -> reconcile ->
 * persist new timestamps. See `docs/architecture.md` section 6 for the sequence diagram this
 * mirrors.
 *
 * Depends only on `:core:model` ports (never Room or Retrofit directly -- `docs/architecture.md`
 * section 2's ports-and-adapters rule), so it's constructed here with plain interfaces and tested
 * with hand-written in-memory fakes, not a real database or `MockWebServer`.
 *
 * Exception classification (network `IOException` -> retryable, anything else -> non-retryable) is
 * provisional: it's based on what a pure-JVM port can throw in principle, not on `:core:gpodder`'s
 * actual Retrofit/OkHttp exception types, since that adapter doesn't exist yet (Tier 3). Revisit
 * once it does.
 */
class SyncOrchestrator(
    private val feedRepository: FeedRepository,
    private val episodeLedgerRepository: EpisodeLedgerRepository,
    private val syncStateRepository: SyncStateRepository,
    private val gpodderClient: GpodderClient,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun sync(): SyncOutcome =
        try {
            pullSubscriptions()
            val pushFailure = pushUnsyncedLedgerRows()
            if (pushFailure != null) {
                pushFailure
            } else {
                pullAndReconcileEpisodeActions()
                SyncOutcome.Success
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (network: IOException) {
            SyncOutcome.Retry(network.message ?: "network error during sync")
        } catch (
            @Suppress("TooGenericExceptionCaught") unexpected: RuntimeException,
        ) {
            // Deliberately broad: this is the outermost classification boundary between
            // "worth retrying" (network-shaped) and "not" (everything else), not a swallowed bug --
            // see the class KDoc's note that this classification is provisional pending
            // :core:gpodder's real exception types.
            SyncOutcome.Failure(unexpected.message ?: "unexpected error during sync")
        }

    /**
     * Full current set, not a delta -- CLAUDE.md section 5: a read-only follower doesn't need to
     * know what changed, only what currently is. Existing [Feed] rows are preserved as-is (keeping
     * their real title/`firstSeenAt` once known); only URLs new to this device get a placeholder
     * [Feed] with `title = url` and `firstSeenAt = now`.
     */
    private suspend fun pullSubscriptions() {
        val delta = gpodderClient.fetchSubscriptions(since = null)
        val currentUrls = delta.add.toSet() - delta.remove.toSet()
        val existingByUrl = feedRepository.observeAll().first().associateBy { it.url }
        val now = clock.millis()

        val feeds =
            currentUrls.map { url ->
                existingByUrl[url] ?: Feed(
                    url = url,
                    title = url,
                    imageUrl = null,
                    firstSeenAt = now,
                    lastRefreshedAt = null,
                    httpEtag = null,
                    httpLastModified = null,
                )
            }
        feedRepository.replaceAll(feeds)
    }

    /** Returns a [SyncOutcome.Retry] on a failed push, or `null` if there was nothing to push or it succeeded. */
    private suspend fun pushUnsyncedLedgerRows(): SyncOutcome.Retry? {
        val outbox =
            episodeLedgerRepository.getUnsynced().mapNotNull { row ->
                row.toOutboundAction()?.let { action -> row to action }
            }
        if (outbox.isEmpty()) return null

        val result = gpodderClient.postEpisodeActions(outbox.map { it.second })
        return result.fold(
            onSuccess = {
                episodeLedgerRepository.markSynced(outbox.map { it.first.episodeKey })
                null
            },
            onFailure = { failure -> SyncOutcome.Retry(failure.message ?: "failed to push episode actions") },
        )
    }

    private suspend fun pullAndReconcileEpisodeActions() {
        val syncState = syncStateRepository.get()
        val page = gpodderClient.fetchEpisodeActions(since = syncState.lastEpisodeActionSyncTs)
        val localLedger =
            episodeLedgerRepository
                .observe(LedgerFilter(state = LedgerFilterState.ALL))
                .first()
                .associateBy { it.episodeKey }

        reconcile(localLedger, page.actions, clock).forEach { row -> episodeLedgerRepository.upsert(row) }
        syncStateRepository.save(SyncState(page.timestamp, syncState.deviceId))
    }
}
