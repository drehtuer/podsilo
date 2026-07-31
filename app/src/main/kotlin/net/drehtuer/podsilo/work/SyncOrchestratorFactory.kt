// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.work

import net.drehtuer.podsilo.core.model.port.EpisodeLedgerRepository
import net.drehtuer.podsilo.core.model.port.FeedRepository
import net.drehtuer.podsilo.core.model.port.GpodderClientFactory
import net.drehtuer.podsilo.core.model.port.NextcloudCredentials
import net.drehtuer.podsilo.core.model.port.SyncStateRepository
import net.drehtuer.podsilo.core.sync.SyncOrchestrator
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Assembles a [SyncOrchestrator] for one sync pass.
 *
 * The orchestrator cannot be a singleton: its `GpodderClient` is built from the credentials in
 * force right now, which the user can change and which are deliberately not held long-term
 * (`SettingsRepository`). Keeping the assembly here rather than in [SyncWorker] means the worker
 * holds one dependency instead of five, and the repositories never appear in a worker signature.
 */
@Singleton
class SyncOrchestratorFactory
    @Inject
    constructor(
        private val feedRepository: FeedRepository,
        private val episodeLedgerRepository: EpisodeLedgerRepository,
        private val syncStateRepository: SyncStateRepository,
        private val gpodderClientFactory: GpodderClientFactory,
        private val clock: Clock,
    ) {
        fun create(credentials: NextcloudCredentials): SyncOrchestrator =
            SyncOrchestrator(
                feedRepository = feedRepository,
                episodeLedgerRepository = episodeLedgerRepository,
                syncStateRepository = syncStateRepository,
                gpodderClient = gpodderClientFactory.create(credentials),
                clock = clock,
            )
    }
