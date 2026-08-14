// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import net.drehtuer.podsilo.core.model.EpisodeLedgerRow
import net.drehtuer.podsilo.core.model.Feed
import net.drehtuer.podsilo.core.model.SyncOutcome
import net.drehtuer.podsilo.core.model.SyncState
import net.drehtuer.podsilo.core.model.port.EpisodeAction
import net.drehtuer.podsilo.core.model.port.EpisodeLedgerRepository
import net.drehtuer.podsilo.core.model.port.FeedRepository
import net.drehtuer.podsilo.core.model.port.GpodderClient
import net.drehtuer.podsilo.core.model.port.LedgerFilter
import net.drehtuer.podsilo.core.model.port.LedgerFilterState
import net.drehtuer.podsilo.core.model.port.LogCategory
import net.drehtuer.podsilo.core.model.port.LogRepository
import net.drehtuer.podsilo.core.model.port.NewLogEntry
import net.drehtuer.podsilo.core.model.port.SyncStateRepository
import java.io.IOException
import java.time.Clock

/**
 * How far back the `since` cursor is rewound on every pull, in seconds — **one day**.
 *
 * The cursor compares two different clocks and nothing can make them the same one. The server
 * selects `WHERE timestamp_epoch > :since` on the **client-authored** timestamp inside each action,
 * while the `timestamp` it hands back — the value stored as the next `since` — is the **server's own
 * wall clock**. An action authored before our last pass is therefore invisible to us permanently,
 * with no error and no gap anyone could notice.
 *
 * That is measured, not theorised. Against the author's instance on 2026-08-13, actions written by
 * the Nextcloud web client came back **6 980 seconds ahead** of the server's clock, because it emits
 * local time with no offset and the server parses it as UTC. Ahead is the survivable direction —
 * those arrive, repeatedly, for two hours. A client whose clock runs *behind* the server's, or one
 * in a timezone west of UTC, lands in the invisible half.
 *
 * A day of overlap costs one re-read of at most a day's actions per pass; reconciliation is
 * idempotent, so re-delivered actions produce no writes. A missed action costs a re-download of an
 * episode the user already handled, which is the one failure CLAUDE.md §11 calls the app's central
 * job to prevent. The asymmetry is the whole argument.
 *
 * **Not** computed from local device time: CLAUDE.md §11 forbids that outright, and it would make
 * clock skew the cure for clock skew.
 */
private const val CURSOR_OVERLAP_SECONDS = 24L * 60 * 60

/** Never below zero — `since = 0` already means "everything", and a negative would be nonsense. */
private fun Long.rewound(): Long = (this - CURSOR_OVERLAP_SECONDS).coerceAtLeast(0)

/** `since = 0` — every action the server has ever stored. Only ever a button press. */
private const val FULL_HISTORY = 0L

/**
 * Actions per POST. Deliberately well under anything the server is likely to refuse: the body is a
 * few hundred bytes per action, so this is tens of kilobytes rather than the megabytes an unchunked
 * push of a full ledger would send.
 */
private const val MAX_ACTIONS_PER_REQUEST = 200

/**
 * Groups rows so that no request carries more than [limit] actions, without splitting a row across
 * two requests — a row is marked synced as a unit, so its actions have to succeed as one.
 */
private fun <T> List<Pair<T, List<EpisodeAction>>>.chunkedByActionCount(
    limit: Int,
): List<List<Pair<T, List<EpisodeAction>>>> {
    val chunks = mutableListOf<List<Pair<T, List<EpisodeAction>>>>()
    var current = mutableListOf<Pair<T, List<EpisodeAction>>>()
    var count = 0
    for (entry in this) {
        if (current.isNotEmpty() && count + entry.second.size > limit) {
            chunks += current
            current = mutableListOf()
            count = 0
        }
        current += entry
        count += entry.second.size
    }
    if (current.isNotEmpty()) chunks += current
    return chunks
}

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
 * still coarser than the adapter it now runs against: a failed GET surfaces as Retrofit's own
 * `HttpException`, which is not an `IOException`, so an expired app password lands in the
 * non-retryable branch and in the log as a plain SYNC failure. Typing that failure in the port is
 * tracked in `docs/backlog.md`.
 */
class SyncOrchestrator(
    private val feedRepository: FeedRepository,
    private val episodeLedgerRepository: EpisodeLedgerRepository,
    private val syncStateRepository: SyncStateRepository,
    private val gpodderClient: GpodderClient,
    private val logRepository: LogRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun sync(): SyncOutcome =
        guarded {
            pullSubscriptions()
            val pushFailure = pushUnsyncedLedgerRows()
            if (pushFailure != null) {
                pushFailure
            } else {
                pullAndReconcileEpisodeActions()
                SyncOutcome.Success
            }
        }

    /**
     * *Apply Nextcloud's state here* — the ordinary pull, run over the **whole** action log rather
     * than the delta since the cursor.
     *
     * That is the entire difference: `since = 0` and nothing else. It overrides no rule, because the
     * two the user might expect it to override are exactly the two they said it must not
     * (`docs/decisions/0025`) — a decided episode is never re-opened, and a `DOWNLOADED` row is never
     * replaced by a remote action the server structurally cannot carry. So this can only ever
     * *shorten* the To-decide list: it decides episodes this device has not decided, and leaves
     * everything else alone.
     *
     * CLAUDE.md §5 warns that `since = 0` is unbounded and must not be the normal path. It is fine
     * once, on a button press, which is why it lives here and not in [sync].
     */
    suspend fun forcePull(): SyncOutcome =
        guarded {
            pullSubscriptions()
            pullAndReconcileEpisodeActions(since = FULL_HISTORY)
            SyncOutcome.Success
        }

    /**
     * *Send this device's state to Nextcloud* — re-posts **every** ledger row that maps to an action,
     * including rows already marked synced.
     *
     * The one operation in the app that deliberately writes to a shared, append-only log on purpose
     * rather than as a consequence, so the confirmation naming the count is not decoration
     * (`docs/decisions/0025`). It is also the only way to repair state the server never received —
     * a download recorded before `docs/decisions/0023` sent `DOWNLOAD` alone, which Nextcloud
     * discards, and its row is already `syncedToServer = true` so no ordinary pass will retry it.
     */
    suspend fun forcePush(): SyncOutcome =
        guarded {
            val rows = episodeLedgerRepository.observe(LedgerFilter(state = LedgerFilterState.ALL)).first()
            pushRows(rows) ?: SyncOutcome.Success
        }

    /** The failure classification every pass shares — see the class KDoc for why it is this coarse. */
    private suspend inline fun guarded(block: () -> SyncOutcome): SyncOutcome =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (network: IOException) {
            record(
                message = "Sync with Nextcloud failed: the server could not be reached.",
                failure = network,
            )
            SyncOutcome.Retry(network.message ?: "network error during sync")
        } catch (
            @Suppress("TooGenericExceptionCaught") unexpected: RuntimeException,
        ) {
            // Deliberately broad: this is the outermost classification boundary between
            // "worth retrying" (network-shaped) and "not" (everything else), not a swallowed bug --
            // see the class KDoc's note that this classification is provisional pending
            // :core:gpodder's real exception types.
            record(
                message = "Sync with Nextcloud failed.",
                failure = unexpected,
            )
            SyncOutcome.Failure(unexpected.message ?: "unexpected error during sync")
        }

    /**
     * Every failure ends up in the error log (S8) as well as in the returned [SyncOutcome], because
     * the outcome reaches WorkManager and no further: a pass that fails on every attempt for four
     * hours used to leave no trace a user could see, which is why issue #60 had to be diagnosed by
     * reading source instead of by reading the app.
     *
     * Plain sentence first, technical half separate (`docs/UI.md` §11). The exception's own message
     * is [detail] and never the headline — "unable to resolve host" is not a sentence the user asked
     * for. Credentials are stripped by the store, not here ([LogRepository.record]).
     *
     * Every sync failure is [LogCategory.SYNC], including an expired app password. Distinguishing
     * `AUTH` needs a typed failure from the GPodder port — the client currently throws Retrofit's
     * own `HttpException` for a failed `GET` — and sniffing "401" out of a message string is the
     * kind of thing that works until a server rewords it. Noted in `docs/backlog.md`.
     */
    private suspend fun record(
        message: String,
        failure: Throwable,
    ) {
        logRepository.record(
            NewLogEntry(
                category = LogCategory.SYNC,
                message = message,
                detail = "${failure::class.simpleName}: ${failure.message}",
            ),
        )
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
    private suspend fun pushUnsyncedLedgerRows(): SyncOutcome.Retry? = pushRows(episodeLedgerRepository.getUnsynced())

    /**
     * Posts [rows] in chunks, marking each chunk synced only on its own confirmed 2xx.
     *
     * **Chunked because a single POST is not a safe assumption about size.** A *mark all as played*
     * over the author's ~9,500 episodes, or a force push over every decided row, is thousands of
     * actions in one body — against a PHP endpoint whose `post_max_size` defaults to 8 MB and which
     * loops per action with an insert-then-update-on-conflict. The normal outbox drain had the same
     * latent problem and now shares the fix, which is the reason this lives here rather than in
     * [forcePush].
     *
     * A failure stops the run rather than continuing: the rows in earlier chunks stay marked (they
     * really were accepted) and everything after stays unsynced for the next pass. Partial progress
     * is the correct outcome of a partial success, and the outbox is what makes it safe to resume.
     */
    private suspend fun pushRows(rows: List<EpisodeLedgerRow>): SyncOutcome.Retry? {
        // One row can produce more than one action -- a completed download emits both `DOWNLOAD` and
        // `PLAY` (`docs/decisions/0023`) -- so the row and its actions are kept paired: the actions
        // are what gets posted, the rows are what gets marked synced.
        val outbox =
            rows
                .map { row -> row to row.toOutboundActions() }
                .filter { (_, actions) -> actions.isNotEmpty() }
        var remaining = outbox.size
        var failure: Throwable? = null
        for (chunk in outbox.chunkedByActionCount(MAX_ACTIONS_PER_REQUEST)) {
            failure = gpodderClient.postEpisodeActions(chunk.flatMap { it.second }).exceptionOrNull()
            if (failure != null) break
            episodeLedgerRepository.markSynced(chunk.map { it.first.episodeKey })
            remaining -= chunk.size
        }

        return failure?.let {
            // Named separately from the generic sync failure because the reassurance is the point:
            // nothing was lost, the remaining rows are still unsynced, and the next pass sends them.
            record(
                message =
                    "$remaining decision(s) could not be sent to Nextcloud. " +
                        "They are kept here and will be sent again.",
                failure = it,
            )
            SyncOutcome.Retry(it.message ?: "failed to push episode actions")
        }
    }

    private suspend fun pullAndReconcileEpisodeActions(since: Long? = null) {
        val syncState = syncStateRepository.get()
        val page = gpodderClient.fetchEpisodeActions(since = since ?: syncState.lastEpisodeActionSyncTs.rewound())
        val localLedger =
            episodeLedgerRepository
                .observe(LedgerFilter(state = LedgerFilterState.ALL))
                .first()
                .associateBy { it.episodeKey }

        reconcile(localLedger, page.actions, clock).forEach { row -> episodeLedgerRepository.upsert(row) }
        syncStateRepository.save(SyncState(page.timestamp, syncState.deviceId))
    }
}
