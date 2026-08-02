// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.download

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import net.drehtuer.podsilo.core.model.Episode
import net.drehtuer.podsilo.core.model.EpisodeLedgerRow
import net.drehtuer.podsilo.core.model.ErrorCause
import net.drehtuer.podsilo.core.model.Feed
import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.core.model.port.EpisodeLedgerRepository
import net.drehtuer.podsilo.core.model.port.EpisodeRepository
import net.drehtuer.podsilo.core.model.port.FeedRepository
import net.drehtuer.podsilo.core.model.port.SettingsRepository
import java.time.Clock
import java.util.concurrent.TimeUnit

private const val PROGRESS_NOTIFICATION_INTERVAL_MS = 1_000L
private const val BACKOFF_SECONDS = 30L

/** Terminal ledger states (`docs/architecture.md` §9) — none of them may be re-entered by a download. */
private val TERMINAL_STATES =
    setOf(LedgerState.DOWNLOADED, LedgerState.SKIPPED, LedgerState.HANDLED_REMOTELY)

/**
 * Downloads one episode, then durably records it (CLAUDE.md §5's mark-on-download: **ledger row
 * first, POST second, never the other way round**). The POST itself is not made here — the row is
 * written with `syncedToServer = false` and [SyncTrigger] asks for a sync pass, so exactly one
 * piece of code ever talks to the GPodder API (`docs/architecture.md` §10).
 *
 * The pipeline it drives is [EpisodeDownloader]; this class owns only the WorkManager contract, the
 * ledger transitions, and the foreground notification.
 *
 * Nothing here downloads anything on its own initiative: the worker is enqueued exclusively by an
 * explicit user triage action, and it refuses to act on an episode whose ledger row is already
 * terminal (CLAUDE.md §1's no-auto-download rule and §7 item 8's triage durability).
 */
@HiltWorker
class DownloadWorker
    @AssistedInject
    // A worker is the composition root of its own operation, so its dependencies *are* its
    // constructor. Bundling them into a holder type would move the list, not shorten it.
    @Suppress("LongParameterList")
    constructor(
        @Assisted appContext: Context,
        @Assisted workerParameters: WorkerParameters,
        private val episodeRepository: EpisodeRepository,
        private val feedRepository: FeedRepository,
        private val ledgerRepository: EpisodeLedgerRepository,
        private val settingsRepository: SettingsRepository,
        private val episodeDownloader: EpisodeDownloader,
        private val notifications: DownloadNotifications,
        private val syncTrigger: SyncTrigger,
        private val clock: Clock,
    ) : CoroutineWorker(appContext, workerParameters) {
        override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(episodeTitle = "", 0, null)

        override suspend fun doWork(): Result {
            val episodeKey = inputData.getString(KEY_EPISODE_KEY) ?: return Result.failure()
            val userRequested = inputData.getBoolean(KEY_USER_REQUESTED, false)
            val existing = ledgerRepository.get(episodeKey)

            // A remote DOWNLOAD/PLAY/DELETE (or a local skip) that landed while this was queued wins:
            // the episode is already handled, so downloading it now would be the double-download the
            // ledger exists to prevent (CLAUDE.md §11).
            //
            // The single exception is an explicit *Download again* (docs/decisions/0012): the flag is
            // settable only from a UI event, never by a worker or a sync path, which is what keeps
            // "only a user can create a file from a terminal row" one grep and one test to verify.
            if (existing != null && existing.state in TERMINAL_STATES && !userRequested) return Result.success()

            val episode = episodeRepository.get(episodeKey)
            val feed = episode?.let { feedRepository.get(it.feedUrl) }
            return when {
                episode == null -> fail(existing, "episode is no longer in any subscribed feed")
                feed == null -> fail(existing, "feed is no longer subscribed")
                else -> runGuarded(existing, episode, feed, userRequested)
            }
        }

        /** Wraps the run so a WorkManager stop leaves a sane ledger state behind rather than DOWNLOADING forever. */
        private suspend fun runGuarded(
            existing: EpisodeLedgerRow?,
            episode: Episode,
            feed: Feed,
            userRequested: Boolean,
        ): Result =
            try {
                run(existing, episode, feed, userRequested)
            } catch (cancellation: CancellationException) {
                // Stopped by WorkManager (constraints lost, work cancelled). Hand the row back to
                // QUEUED so it doesn't sit in DOWNLOADING forever; the partial file stays for resume.
                withContext(NonCancellable) {
                    val attempts = existing?.attempts ?: 0
                    val row = rowFor(episode, LedgerState.QUEUED, attempts, existing?.writtenFileName)
                    ledgerRepository.upsert(row)
                    notifications.clear()
                }
                throw cancellation
            }

        private suspend fun run(
            existing: EpisodeLedgerRow?,
            episode: Episode,
            feed: Feed,
            userRequested: Boolean,
        ): Result {
            // docs/decisions/0012 section 3: a re-decision is a new attempt chain, not a continuation.
            // Leaving attempts at 3 would render a fresh download as "attempt 3 of 3" and look
            // exhausted before it started. writtenFileName is deliberately NOT reset — it is what the
            // duplicate guard checks, and losing it would let a second copy be written.
            val reopening = userRequested && existing != null && existing.state in TERMINAL_STATES
            val attempts = if (reopening) 1 else (existing?.attempts ?: 0) + 1
            val recordedFileName = existing?.writtenFileName
            ledgerRepository.upsert(rowFor(episode, LedgerState.DOWNLOADING, attempts, recordedFileName))
            notifications.ensureChannel()
            setForeground(foregroundInfo(episode.title, 0, null))

            var lastNotifiedAt = 0L
            val outcome =
                episodeDownloader.download(
                    DownloadRequest(
                        feed = feed,
                        episode = episode,
                        naming = settingsRepository.observeNaming().first(),
                        previousFileName = recordedFileName,
                        userRequested = userRequested,
                    ),
                ) { written, total ->
                    // Throttled: a 64 KB read granularity would otherwise redraw the notification
                    // hundreds of times a second for no benefit.
                    val now = clock.millis()
                    if (now - lastNotifiedAt >= PROGRESS_NOTIFICATION_INTERVAL_MS) {
                        lastNotifiedAt = now
                        notifications.showProgress(episode.title, written, total)
                    }
                }
            notifications.clear()

            return when (outcome) {
                is DownloadOutcome.Delivered -> succeed(episode, attempts, outcome.fileName)
                is DownloadOutcome.AlreadyPresent -> {
                    // Nothing was fetched, so this is not an attempt: the row goes back to exactly
                    // what it was, `attempts` and all. Not an ERROR, not logged (docs/decisions/0012
                    // §4) — the UI reports it as a snackbar and the file stays untouched.
                    ledgerRepository.upsert(
                        rowFor(episode, LedgerState.DOWNLOADED, existing?.attempts ?: 0, outcome.fileName),
                    )
                    Result.success()
                }
                is DownloadOutcome.Failed -> {
                    ledgerRepository.upsert(
                        rowFor(
                            episode,
                            LedgerState.ERROR,
                            attempts,
                            recordedFileName,
                            outcome.reason,
                            outcome.cause,
                            outcome.retryable,
                        ),
                    )
                    // Genuinely transient failures go back to WorkManager's backoff; everything else
                    // stays in ERROR for the user to retry deliberately (CLAUDE.md §3: never
                    // hand-roll retry logic WorkManager already provides).
                    if (outcome.retryable && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
                }
            }
        }

        private suspend fun succeed(
            episode: Episode,
            attempts: Int,
            fileName: String,
        ): Result {
            // Durable first, unconditionally: if the process dies right here, the next sync pass
            // still pushes this row, and the episode is never downloaded twice (CLAUDE.md §5).
            ledgerRepository.upsert(rowFor(episode, LedgerState.DOWNLOADED, attempts, fileName))
            syncTrigger.requestSyncNow()
            return Result.success()
        }

        /** No [Episode] row left to denormalise from, so the queued row's own snapshot is preserved. */
        private suspend fun fail(
            existing: EpisodeLedgerRow?,
            reason: String,
        ): Result {
            val previous = existing ?: return Result.failure()
            ledgerRepository.upsert(
                previous.copy(
                    state = LedgerState.ERROR,
                    actionedAt = clock.millis(),
                    lastError = reason,
                    attempts = previous.attempts + 1,
                ),
            )
            return Result.failure()
        }

        /**
         * Ledger rows carry denormalised `feedUrl`/`enclosureUrl`/`durationSeconds` snapshots so the
         * outbox can still build a valid action after the episode row is pruned
         * (`docs/decisions/0001`). `syncedToServer` is always `false` on a local write — only a
         * confirmed 2xx flips it, and only the sync pass may do that.
         */
        @Suppress("LongParameterList")
        private fun rowFor(
            episode: Episode,
            state: LedgerState,
            attempts: Int,
            writtenFileName: String?,
            lastError: String? = null,
            lastErrorCause: ErrorCause? = null,
            lastErrorRetryable: Boolean? = null,
        ): EpisodeLedgerRow =
            EpisodeLedgerRow(
                episodeKey = episode.episodeKey,
                feedUrl = episode.feedUrl,
                enclosureUrl = episode.enclosureUrl,
                state = state,
                actionedAt = clock.millis(),
                syncedToServer = false,
                attempts = attempts,
                lastError = lastError,
                lastErrorCause = lastErrorCause,
                lastErrorRetryable = lastErrorRetryable,
                writtenFileName = writtenFileName,
                durationSeconds = episode.durationMs?.let { (it / MILLIS_PER_SECOND).toInt() },
            )

        private fun foregroundInfo(
            episodeTitle: String,
            bytesWritten: Long,
            totalBytes: Long?,
        ): ForegroundInfo =
            ForegroundInfo(
                DOWNLOAD_NOTIFICATION_ID,
                notifications.buildProgress(episodeTitle, bytesWritten, totalBytes),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )

        companion object {
            const val KEY_EPISODE_KEY: String = "episodeKey"

            /**
             * The one way past the terminal-row refusal (`docs/decisions/0012`). **Settable only
             * from a UI triage event** — never from a worker, a sync pass, or `FeedRefresher`.
             *
             * A flag rather than relaxing the refusal, because the invariant then reads "only a UI
             * event can create a file from a terminal row", which is one grep and one test to
             * verify, instead of a property that has to be re-derived whenever the worker changes.
             */
            const val KEY_USER_REQUESTED: String = "userRequested"

            /** Beyond this, retrying is noise: the ledger keeps ERROR + `lastError` and the user decides. */
            const val MAX_ATTEMPTS: Int = 5

            private const val MILLIS_PER_SECOND = 1_000L

            /** One unique work item per episode, so a double tap can't start two downloads of the same file. */
            fun uniqueWorkName(episodeKey: String): String = "podsilo-download:$episodeKey"

            fun request(
                episodeKey: String,
                userRequested: Boolean = false,
            ): OneTimeWorkRequest =
                OneTimeWorkRequestBuilder<DownloadWorker>()
                    .setInputData(
                        Data
                            .Builder()
                            .putString(KEY_EPISODE_KEY, episodeKey)
                            .putBoolean(KEY_USER_REQUESTED, userRequested)
                            .build(),
                    ).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                    .build()
        }
    }
