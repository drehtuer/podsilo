// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import net.drehtuer.podsilo.core.model.port.ConnectivityMonitor
import net.drehtuer.podsilo.core.model.port.EpisodeLedgerRepository
import net.drehtuer.podsilo.core.model.port.EpisodeListRepository
import net.drehtuer.podsilo.core.model.port.EpisodeRepository
import net.drehtuer.podsilo.core.model.port.FeedRepository
import net.drehtuer.podsilo.core.model.port.NextcloudLoginFlowClient
import net.drehtuer.podsilo.core.model.port.SettingsRepository
import net.drehtuer.podsilo.feature.episodes.EpisodeDetailViewModel
import net.drehtuer.podsilo.feature.episodes.EpisodeListViewModel
import net.drehtuer.podsilo.feature.episodes.PodcastListViewModel
import net.drehtuer.podsilo.feature.episodes.TriageWriter
import net.drehtuer.podsilo.feature.settings.ConnectSyncTrigger
import net.drehtuer.podsilo.feature.settings.ConnectViewModel
import net.drehtuer.podsilo.feature.settings.NamingViewModel
import net.drehtuer.podsilo.feature.settings.SettingsViewModel
import java.time.Clock
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Builds the three episode view models.
 *
 * They are not `@HiltViewModel` on purpose: two of them take a feed URL or an episode key as a
 * construction parameter, and keeping all three plain classes means `:feature:episodes` needs no
 * Hilt dependency and every one of them is testable with fakes and no test runner
 * (`docs/architecture.md` §2).
 */
@Suppress("LongParameterList") // A composition root's parameter list is its graph; see FeedRefresher.
@Singleton
class EpisodeViewModelFactory
    @Inject
    constructor(
        private val feedRepository: FeedRepository,
        private val episodeRepository: EpisodeRepository,
        private val ledgerRepository: EpisodeLedgerRepository,
        private val listRepository: EpisodeListRepository,
        private val settingsRepository: SettingsRepository,
        private val connectivityMonitor: ConnectivityMonitor,
        private val scheduler: WorkEpisodeScheduler,
        private val spaceProbe: TargetSpaceProbe,
        private val folderStatus: AccessDownloadFolderStatus,
        private val folderLabel: DocumentFolderLabel,
        private val namingPreview: TemplateNamingPreview,
        private val clock: Clock,
        private val settingsFolderStatus: SettingsFolderStatusAdapter,
        private val settingsCounts: SettingsCountsAdapter,
        private val syncStatus: SyncStatusAdapter,
        private val namingSample: NamingSampleSourceAdapter,
        private val loginFlowClient: NextcloudLoginFlowClient,
        private val syncTrigger: ConnectSyncTrigger,
        @Named("appVersion") private val appVersion: String,
    ) {
        fun podcastList(): ViewModelProvider.Factory =
            factory {
                PodcastListViewModel(
                    feedRepository = feedRepository,
                    episodeRepository = episodeRepository,
                    listRepository = listRepository,
                    settingsRepository = settingsRepository,
                    connectivityMonitor = connectivityMonitor,
                    scheduler = scheduler,
                    folderStatus = folderStatus,
                    namingPreview = namingPreview,
                )
            }

        fun episodeList(feedUrl: String): ViewModelProvider.Factory =
            factory {
                EpisodeListViewModel(
                    feedUrl = feedUrl,
                    feedRepository = feedRepository,
                    episodeRepository = episodeRepository,
                    listRepository = listRepository,
                    settingsRepository = settingsRepository,
                    connectivityMonitor = connectivityMonitor,
                    triageWriter = triageWriter(),
                    scheduler = scheduler,
                    spaceProbe = spaceProbe,
                    folderStatus = folderStatus,
                )
            }

        fun episodeDetail(episodeKey: String): ViewModelProvider.Factory =
            factory {
                EpisodeDetailViewModel(
                    episodeKey = episodeKey,
                    episodeRepository = episodeRepository,
                    ledgerRepository = ledgerRepository,
                    feedRepository = feedRepository,
                    triageWriter = triageWriter(),
                    scheduler = scheduler,
                    folderLabel = folderLabel,
                )
            }

        fun settings(): ViewModelProvider.Factory =
            factory {
                SettingsViewModel(
                    settingsRepository = settingsRepository,
                    ledgerRepository = ledgerRepository,
                    listRepository = listRepository,
                    feedRepository = feedRepository,
                    folderStatus = settingsFolderStatus,
                    counts = settingsCounts,
                    namingSummary = { namingPreview.render(it) },
                    syncStatus = syncStatus,
                    clock = clock,
                    version = appVersion,
                )
            }

        fun connect(): ViewModelProvider.Factory =
            factory { ConnectViewModel(loginFlowClient, settingsRepository, syncTrigger) }

        fun naming(): ViewModelProvider.Factory = factory { NamingViewModel(settingsRepository, namingSample) }

        /**
         * A fresh instance per view model, which is fine — it is stateless. Sharing the *class* is
         * the point: S2 and S3 must write byte-identical ledger rows for the same decision.
         */
        private fun triageWriter() = TriageWriter(ledgerRepository, clock)

        private inline fun factory(crossinline create: () -> ViewModel): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
            }
    }
