// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import net.drehtuer.podsilo.core.model.SyncOutcome
import net.drehtuer.podsilo.core.model.port.SettingsRepository
import net.drehtuer.podsilo.core.sync.SyncOrchestrator
import java.util.concurrent.TimeUnit

private const val BACKOFF_SECONDS = 60L
private const val MAX_ATTEMPTS = 5

/**
 * The thin `CoroutineWorker` around `:core:sync`'s [SyncOrchestrator]. It lives in `:app` rather
 * than `:core:sync` for one reason: `androidx.work` is an Android dependency and `:core:sync` must
 * stay plain-JVM testable (`docs/architecture.adoc` §2). Everything interesting — order of
 * operations, reconciliation, the outbox — is in the orchestrator and tested without Android.
 */
@HiltWorker
class SyncWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted workerParameters: WorkerParameters,
        private val settingsRepository: SettingsRepository,
        private val syncOrchestratorFactory: SyncOrchestratorFactory,
    ) : CoroutineWorker(appContext, workerParameters) {
        override suspend fun doWork(): Result {
            // No account configured yet: there is nothing to sync, and retrying cannot change that
            // until the user visits settings — which triggers a pass of its own.
            val credentials = settingsRepository.nextcloudCredentials() ?: return Result.success()
            val orchestrator = syncOrchestratorFactory.create(credentials)

            // One worker, three modes — the same shape `FeedRefreshWorker.KEY_FEED_URL` already uses.
            // Three workers would be three copies of the credential check, the outcome mapping and the
            // retry policy, differing only in which method they call (`docs/decisions/0025`).
            val outcome =
                when (inputData.getString(KEY_SYNC_MODE)) {
                    MODE_FORCE_PULL -> orchestrator.forcePull()
                    MODE_FORCE_PUSH -> orchestrator.forcePush()
                    else -> orchestrator.sync()
                }

            return when (outcome) {
                is SyncOutcome.Success -> Result.success()
                // Unsynced ledger rows stay unsynced until a confirmed 2xx, so giving up here costs
                // nothing but time — the next pass drains the same outbox (CLAUDE.md §5).
                is SyncOutcome.Retry -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
                is SyncOutcome.Failure -> Result.failure()
            }
        }

        companion object {
            const val UNIQUE_WORK_NAME: String = "podsilo-sync"

            /** Absent means the ordinary pass, so every existing enqueue keeps working untouched. */
            const val KEY_SYNC_MODE: String = "sync_mode"
            const val MODE_FORCE_PULL: String = "force_pull"
            const val MODE_FORCE_PUSH: String = "force_push"

            /**
             * Kept **only** so the schedule an older build persisted can be cancelled
             * (`docs/decisions/0026`). Nothing enqueues under this name any more, and the request
             * builder that used to is gone: a builder for work nobody schedules is an invitation to
             * schedule it again by accident.
             */
            const val PERIODIC_WORK_NAME: String = "podsilo-sync-periodic"

            private val networkConstraint =
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

            /**
             * Expedited: this is what a just-completed download or skip triggers, and the point of
             * mark-on-download is that the action reaches the server promptly. Quota exhaustion
             * degrades it to a normal job rather than dropping it.
             */
            fun expeditedRequest(mode: String? = null): OneTimeWorkRequest =
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setInputData(Data.Builder().apply { mode?.let { putString(KEY_SYNC_MODE, it) } }.build())
                    .setConstraints(networkConstraint)
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                    .build()
        }
    }
