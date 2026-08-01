// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.work

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import net.drehtuer.podsilo.core.download.DownloadWorker
import net.drehtuer.podsilo.core.download.SyncTrigger
import net.drehtuer.podsilo.core.feed.FeedRefreshWorker
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
    ) : SyncTrigger {
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

        /**
         * Bulk triage (docs/UI.md section 5). One work request per episode, deliberately: each row
         * then appears in S7 and can be cancelled on its own, which is what makes "every queued
         * episode is visible" true rather than aspirational (docs/decisions/0014).
         *
         * `userRequested` is false here — a bulk action only ever covers undecided episodes, so it
         * never needs to pass the terminal-row refusal.
         */
        fun enqueueDownloads(episodeKeys: List<String>) {
            episodeKeys.forEach { enqueueDownload(it) }
        }

        fun cancelDownload(episodeKey: String) {
            workManager.cancelUniqueWork(DownloadWorker.uniqueWorkName(episodeKey))
        }

        /** "Refresh now" — CLAUDE.md §11: periodic work is best-effort, so a manual trigger must exist. */
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
