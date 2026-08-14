// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.work

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import net.drehtuer.podsilo.core.download.DownloadWorker
import net.drehtuer.podsilo.core.feed.FeedRefreshWorker
import net.drehtuer.podsilo.core.model.port.SyncTrigger
import net.drehtuer.podsilo.feature.settings.DirectionalSync
import javax.inject.Inject
import javax.inject.Singleton

/** WorkManager's own floor for periodic work; asking for less is silently clamped, so clamp visibly. */
private const val MIN_PERIODIC_MINUTES = 15L

/**
 * Every enqueue in the app goes through here, so scheduling policy (unique names, keep-vs-replace,
 * intervals) lives in one readable place instead of being spread across view models and workers.
 *
 * Also the [SyncTrigger] `:core:download` asks for after a download lands
 * (`docs/architecture.md` §10) — which is why the download module never needs to know that a
 * `SyncWorker` exists at all.
 */
@Singleton
class WorkScheduler
    @Inject
    constructor(
        private val workManager: WorkManager,
    ) : SyncTrigger,
        DirectionalSync {
        /**
         * KEEP, not REPLACE: a second tap on the same episode must join the download already
         * running rather than restart it (and restarting would discard the resume progress).
         */
        fun enqueueDownload(
            episodeKey: String,
            userRequested: Boolean = false,
        ) {
            workManager.enqueueUniqueWork(
                DownloadWorker.uniqueWorkName(episodeKey),
                ExistingWorkPolicy.KEEP,
                DownloadWorker.request(episodeKey, userRequested),
            )
        }

        fun cancelDownload(episodeKey: String) {
            workManager.cancelUniqueWork(DownloadWorker.uniqueWorkName(episodeKey))
        }

        /**
         * "Refresh now" — CLAUDE.md §11: periodic work is best-effort, so a manual trigger must exist.
         *
         * **Fire-and-forget, and that is not what a screen wants.**
         * `EpisodeScheduler.requestFeedRefresh` (in `:feature:episodes`) is declared `suspend` and
         * documented to return only when the work reaches a terminal state, because a pull-to-refresh
         * indicator has to stay up for the duration of the refresh rather than for the microsecond
         * enqueueing takes. When this class is wired to that interface, **delegating directly will
         * silently break that contract** — the implementation has to observe the `WorkInfo` and
         * suspend until it finishes.
         */
        fun requestFeedRefresh(feedUrl: String? = null) {
            workManager.enqueueUniqueWork(
                FeedRefreshWorker.uniqueWorkName(feedUrl),
                ExistingWorkPolicy.KEEP,
                FeedRefreshWorker.request(feedUrl),
            )
        }

        /** Work state for the screens: S1's aggregate ring, S7's list, and the "is a refresh running" flag. */
        fun observeDownloadWork(): kotlinx.coroutines.flow.Flow<List<androidx.work.WorkInfo>> =
            workManager.getWorkInfosFlow(
                androidx.work.WorkQuery.fromStates(
                    androidx.work.WorkInfo.State.ENQUEUED,
                    androidx.work.WorkInfo.State.RUNNING,
                ),
            )

        override fun requestSyncNow() {
            workManager.enqueueUniqueWork(
                SyncWorker.UNIQUE_WORK_NAME,
                // APPEND_OR_REPLACE, not KEEP: a pass already in flight may have read the outbox
                // before this row was written, so the new row needs a pass of its own afterwards.
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                SyncWorker.expeditedRequest(),
            )
        }

        /**
         * The two directional passes (`docs/decisions/0025`). `APPEND_OR_REPLACE` for the same reason
         * [requestSyncNow] uses it: a pass already in flight may have read the ledger before the user
         * pressed this, so the button needs a pass of its own afterwards rather than joining one.
         */
        override fun applyRemoteState() = enqueueSync(SyncWorker.MODE_FORCE_PULL)

        override fun sendLocalState() = enqueueSync(SyncWorker.MODE_FORCE_PUSH)

        private fun enqueueSync(mode: String) {
            workManager.enqueueUniqueWork(
                SyncWorker.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                SyncWorker.expeditedRequest(mode),
            )
        }

        /** UPDATE so changing the interval in settings re-times the existing schedule instead of adding one. */
        fun schedulePeriodicWork(intervalMinutes: Long) {
            val interval = intervalMinutes.coerceAtLeast(MIN_PERIODIC_MINUTES)
            workManager.enqueueUniquePeriodicWork(
                SyncWorker.PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                SyncWorker.periodicRequest(interval),
            )
            workManager.enqueueUniquePeriodicWork(
                FeedRefreshWorker.PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                FeedRefreshWorker.periodicRequest(interval),
            )
        }
    }
