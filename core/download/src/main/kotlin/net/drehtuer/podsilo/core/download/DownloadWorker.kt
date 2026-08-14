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
import net.drehtuer.podsilo.core.model.port.LogCategory
import net.drehtuer.podsilo.core.model.port.LogRepository
import net.drehtuer.podsilo.core.model.port.NewLogEntry
import net.drehtuer.podsilo.core.model.port.SettingsRepository
import net.drehtuer.podsilo.core.model.port.SyncTrigger
import java.time.Clock
import java.util.concurrent.TimeUnit

/** 1 Hz — one tick drives the notification *and* `WorkInfo.progress`, so they cannot disagree. */
private const val PROGRESS_INTERVAL_MS = 1_000L
private const val BACKOFF_SECONDS = 30L

/** Terminal ledger states (`docs/architecture.md` §9) — none of them may be re-entered by a download. */
private val TERMINAL_STATES =
    setOf(LedgerState.DOWNLOADED, LedgerState.SKIPPED, LedgerState.HANDLED_REMOTELY)

/**
 * The plain-language half of a failed download (`docs/UI.md` §11: the sentence the user reads comes
 * first, the technical half is separate and collapsed).
 *
 * Each one names the *next step* where there is one, because a log entry the user can act on is the
 * whole reason S8 exists. `FOLDER_UNAVAILABLE` deliberately does not say "retry": retrying cannot
 * work until the folder is chosen again (`docs/architecture.md` §11).
 */
private fun ErrorCause.sentence(): String =
    when (this) {
        ErrorCause.NETWORK -> "This episode could not be downloaded: the server did not respond."
        ErrorCause.SERVER -> "This episode could not be downloaded: the podcast server refused it."
        ErrorCause.AUTH -> "This episode could not be downloaded: the podcast server refused access."
        ErrorCause.DISK_FULL -> "There is not enough space left to download this episode."
        ErrorCause.FOLDER_UNAVAILABLE -> "The download folder is not available. Choose it again in Settings."
        ErrorCause.UNKNOWN -> "This episode could not be downloaded."
    }

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
        private val logRepository: LogRepository,
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

            var lastPublishedAt = 0L
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
                    if (now - lastPublishedAt >= PROGRESS_INTERVAL_MS) {
                        lastPublishedAt = now
                        notifications.showProgress(episode.title, written, total)
                        publishProgress(written, total)
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
                    recordFailure(episode, outcome, attempts)
                    // Genuinely transient failures go back to WorkManager's backoff; everything else
                    // stays in ERROR for the user to retry deliberately (CLAUDE.md §3: never
                    // hand-roll retry logic WorkManager already provides).
                    if (outcome.retryable && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
                }
            }
        }

        /**
         * Writes the failure to the error log (S8) beside the row's own `lastError`.
         *
         * **Every attempt, not only the last.** The row shows the current state and the log shows
         * the history, and the DAO collapses repeats onto one entry with a count — which is what
         * turns "it failed three times overnight" from an inference into a `×3` the user can read
         * (`docs/UI.md` §11). Logging only the final attempt would lose exactly that.
         *
         * A download that fails because the folder is gone or the disk is full is a **storage**
         * problem the user fixes elsewhere, so it is filed under that category rather than under
         * `DOWNLOAD`; S8's filter chips are the reason the distinction is worth making.
         */
        private suspend fun recordFailure(
            episode: Episode,
            outcome: DownloadOutcome.Failed,
            attempts: Int,
        ) {
            val category =
                when (outcome.cause) {
                    ErrorCause.DISK_FULL, ErrorCause.FOLDER_UNAVAILABLE -> LogCategory.STORAGE
                    else -> LogCategory.DOWNLOAD
                }
            logRepository.record(
                NewLogEntry(
                    category = category,
                    feedUrl = episode.feedUrl,
                    episodeKey = episode.episodeKey,
                    message = outcome.cause.sentence(),
                    detail = "${episode.title} · attempt $attempts · ${outcome.reason}",
                ),
            )
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

        /**
         * Publishes byte progress for the UI, in the **same throttled tick** as the notification.
         *
         * That shared tick is the point, not an economy: `docs/UI.md` §B7 requires the
         * notification, the episode row, S1's aggregate and S7 never to disagree, and the surest way
         * to guarantee that is for one clock to drive them all. This is the only publisher; nothing
         * persists a percentage, so after process death there is simply no progress to read and the
         * row correctly reads *resuming* rather than a stale number.
         *
         * `setProgressAsync` rather than the suspending `setProgress`: the downloader's callback is
         * an ordinary function, and making it suspend would push a coroutine boundary all the way
         * down through `EnclosureDownloader`'s read loop to buy nothing here.
         */
        private fun publishProgress(
            bytesWritten: Long,
            totalBytes: Long?,
        ) {
            setProgressAsync(
                Data
                    .Builder()
                    .putLong(KEY_PROGRESS_BYTES, bytesWritten)
                    // -1, not absent: "the server disclosed no Content-Length" is a fact the UI acts
                    // on (indeterminate bar), and a missing key would be indistinguishable from a
                    // progress update that had not arrived yet.
                    .putLong(KEY_PROGRESS_TOTAL, totalBytes ?: UNKNOWN_TOTAL)
                    .build(),
            )
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
         * (`docs/architecture.md` §4). `syncedToServer` is always `false` on a local write — only a
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

            /** Bytes on disk so far, in `WorkInfo.progress`. Live only — never persisted anywhere. */
            const val KEY_PROGRESS_BYTES: String = "progressBytes"

            /** Total bytes, or [UNKNOWN_TOTAL] when the server disclosed no `Content-Length`. */
            const val KEY_PROGRESS_TOTAL: String = "progressTotal"

            const val UNKNOWN_TOTAL: Long = -1L

            private const val MILLIS_PER_SECOND = 1_000L

            private const val EPISODE_TAG_PREFIX = "podsilo-download-episode:"

            /** One unique work item per episode, so a double tap can't start two downloads of the same file. */
            fun uniqueWorkName(episodeKey: String): String = "podsilo-download:$episodeKey"

            /**
             * The tag that lets an observer map a `WorkInfo` back to its episode.
             *
             * A tag rather than the unique work name: `WorkInfo` exposes its tags and does **not**
             * expose the unique name it was enqueued under, so without this there is no way to tell
             * which episode a queued download belongs to — which is what S1's per-feed
             * "n downloading" and S7's rows both need.
             */
            fun episodeTag(episodeKey: String): String = "$EPISODE_TAG_PREFIX$episodeKey"

            fun episodeKeyOf(tags: Set<String>): String? =
                tags.firstOrNull { it.startsWith(EPISODE_TAG_PREFIX) }?.removePrefix(EPISODE_TAG_PREFIX)

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
                    ).addTag(episodeTag(episodeKey))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                    .build()
        }
    }
