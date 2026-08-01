// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.feed

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

private const val BACKOFF_SECONDS = 60L
private const val MAX_ATTEMPTS = 3

/**
 * WorkManager's wrapper around [FeedRefresher] — periodic background refresh plus the "refresh now"
 * the UI offers (CLAUDE.md §1 requirement 2). All the behaviour is in [FeedRefresher]; this class
 * only decides whether a pass is worth retrying.
 */
@HiltWorker
class FeedRefreshWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted workerParameters: WorkerParameters,
        private val feedRefresher: FeedRefresher,
    ) : CoroutineWorker(appContext, workerParameters) {
        override suspend fun doWork(): Result =
            when {
                feedRefresher.refresh(inputData.getString(KEY_FEED_URL)) == 0 -> Result.success()
                runAttemptCount < MAX_ATTEMPTS -> Result.retry()
                // Out of attempts: the next scheduled pass tries again anyway, so this is "not now",
                // not "never". Nothing is lost — episodes are a disposable cache (CLAUDE.md §5).
                else -> Result.success()
            }

        companion object {
            const val UNIQUE_WORK_NAME: String = "podsilo-feed-refresh"
            const val PERIODIC_WORK_NAME: String = "podsilo-feed-refresh-periodic"

            /**
             * Scopes a pass to one feed, for S2's pull-to-refresh. Absent means "all of them".
             *
             * The same worker, deliberately not a second one: a separate single-feed worker would be
             * a second copy of the retry policy, the constraints and the mark-old rule, kept in step
             * by hand (CLAUDE.md §3).
             */
            const val KEY_FEED_URL: String = "feedUrl"

            private val networkConstraint =
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

            fun request(feedUrl: String? = null): OneTimeWorkRequest =
                OneTimeWorkRequestBuilder<FeedRefreshWorker>()
                    .setConstraints(networkConstraint)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                    .apply { feedUrl?.let { setInputData(workDataOf(KEY_FEED_URL to it)) } }
                    .build()

            /** Unique-work name for a scoped pass, so two pulls on the same feed coalesce. */
            fun uniqueWorkName(feedUrl: String?): String =
                if (feedUrl == null) UNIQUE_WORK_NAME else "$UNIQUE_WORK_NAME:$feedUrl"

            fun periodicRequest(intervalMinutes: Long): PeriodicWorkRequest =
                PeriodicWorkRequestBuilder<FeedRefreshWorker>(intervalMinutes, TimeUnit.MINUTES)
                    .setConstraints(networkConstraint)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                    .build()
        }
    }
