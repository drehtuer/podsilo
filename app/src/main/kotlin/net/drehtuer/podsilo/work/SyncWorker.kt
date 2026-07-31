// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
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
 * stay plain-JVM testable (`docs/architecture.md` §2). Everything interesting — order of
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

            return when (syncOrchestratorFactory.create(credentials).sync()) {
                is SyncOutcome.Success -> Result.success()
                // Unsynced ledger rows stay unsynced until a confirmed 2xx, so giving up here costs
                // nothing but time — the next pass drains the same outbox (CLAUDE.md §5).
                is SyncOutcome.Retry -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
                is SyncOutcome.Failure -> Result.failure()
            }
        }

        companion object {
            const val UNIQUE_WORK_NAME: String = "podsilo-sync"
            const val PERIODIC_WORK_NAME: String = "podsilo-sync-periodic"

            private val networkConstraint =
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

            /**
             * Expedited: this is what a just-completed download or skip triggers, and the point of
             * mark-on-download is that the action reaches the server promptly. Quota exhaustion
             * degrades it to a normal job rather than dropping it.
             */
            fun expeditedRequest(): OneTimeWorkRequest =
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(networkConstraint)
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                    .build()

            fun periodicRequest(intervalMinutes: Long): PeriodicWorkRequest =
                PeriodicWorkRequestBuilder<SyncWorker>(intervalMinutes, TimeUnit.MINUTES)
                    .setConstraints(networkConstraint)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                    .build()
        }
    }
