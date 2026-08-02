// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.drehtuer.podsilo.core.model.EpochTime
import net.drehtuer.podsilo.core.model.Feed
import net.drehtuer.podsilo.core.model.port.ConnectivityMonitor
import net.drehtuer.podsilo.core.model.port.EpisodeListRepository
import net.drehtuer.podsilo.core.model.port.EpisodeRepository
import net.drehtuer.podsilo.core.model.port.FeedRepository
import net.drehtuer.podsilo.core.model.port.FeedUndecidedCount
import net.drehtuer.podsilo.core.model.port.NamingSettings
import net.drehtuer.podsilo.core.model.port.SettingsRepository

/** Matches S2's grace period, so navigating into a feed and back does not restart S1's queries. */
private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

/**
 * S1 — the launcher screen (`docs/UI_interface.md` §2).
 *
 * **The ordering is frozen**, and that is the one rule in this class worth reading twice: the sort
 * is computed on cold start and on each explicit refresh, then held as a list of feed URLs into
 * which updated rows are re-projected. Recomputing it inside the `combine` is the bug the rule
 * exists to prevent — a background sync would then reorder the list under the user's finger
 * (`docs/UI.md` §4).
 */
@Suppress("LongParameterList")
class PodcastListViewModel(
    private val feedRepository: FeedRepository,
    private val episodeRepository: EpisodeRepository,
    private val listRepository: EpisodeListRepository,
    private val settingsRepository: SettingsRepository,
    private val connectivityMonitor: ConnectivityMonitor,
    private val scheduler: EpisodeScheduler,
    private val folderStatus: DownloadFolderStatus,
    private val namingPreview: NamingPreview,
) : ViewModel() {
    private val filter = MutableStateFlow(PodcastFilter.WITH_NEW)
    private val refreshing = MutableStateFlow(false)

    /** Feed URLs in display order. Empty until the first freeze, which the `init` below performs. */
    private val order = MutableStateFlow<List<String>>(emptyList())

    private val effects = Channel<PodcastListEffect>(Channel.BUFFERED)
    val effect: Flow<PodcastListEffect> = effects.receiveAsFlow()

    init {
        // Cold start is the other moment the order is allowed to change. Deliberately an
        // `init`-launched job rather than something derived inside `state`: "computed once" cannot
        // be expressed as a derivation of the very flows it must not react to.
        viewModelScope.launch { freezeOrder() }
    }

    val state: StateFlow<PodcastListUiState> =
        combine(
            feedRepository.observeAll(),
            listRepository.observeUndecidedCounts(),
            order,
            combine(filter, refreshing, folderStatus.observe(), ::Triple),
            combine(
                settingsRepository.observeNextcloudAccount(),
                settingsRepository.observeNaming(),
                connectivityMonitor.observe(),
            ) { account, naming, connectivity ->
                Environment(account?.serverUrl, namingPreview.render(naming), connectivity.online)
            },
        ) { feeds, counts, frozen, chrome, environment ->
            build(feeds, counts, frozen, chrome.first, chrome.second, chrome.third, environment)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            initialValue = PodcastListUiState(),
        )

    @Suppress("LongParameterList")
    private fun build(
        feeds: List<Feed>,
        counts: List<FeedUndecidedCount>,
        frozen: List<String>,
        filter: PodcastFilter,
        refreshing: Boolean,
        folder: FolderState,
        environment: Environment,
    ): PodcastListUiState {
        val byUrl = counts.associate { it.feedUrl to it.count }
        val rows = feeds.map { it.toUi(undecidedCount = byUrl[it.url]) }.inFrozenOrder(frozen)
        val visible =
            when (filter) {
                // A feed whose count is unknown stays visible: "never fetched" is not "nothing new",
                // and hiding it would make a newly subscribed podcast invisible (docs/UI.md §12.5).
                PodcastFilter.WITH_NEW -> rows.filter { it.undecidedCount == null || it.undecidedCount > 0 }
                PodcastFilter.ALL -> rows
            }
        val checklist =
            SetupChecklist(
                nextcloudConnected = environment.instanceLabel != null,
                instanceLabel = environment.instanceLabel,
                folderState = folder,
                namingPreview = environment.namingPreview,
            )
        return PodcastListUiState(
            content = contentFor(rows, visible, environment.instanceLabel != null),
            filter = filter,
            isRefreshing = refreshing,
            queueStatus = queueStatusForFolder(folder),
            isOffline = !environment.online,
            setup = checklist.takeUnless { it.isComplete },
            activityBadge = rows.any { it.activeDownloads > 0 },
            totalUndecided = rows.sumOf { it.undecidedCount ?: 0 },
        )
    }

    private fun contentFor(
        all: List<FeedUi>,
        visible: List<FeedUi>,
        configured: Boolean,
    ): PodcastListUiState.Content =
        when {
            !configured -> PodcastListUiState.Content.NotConfigured
            all.isEmpty() -> PodcastListUiState.Content.NoSubscriptions
            // An empty *filtered* list is not "no subscriptions": the screen says "all caught up"
            // and offers the other filter, rather than the read-only-follower empty state.
            else -> PodcastListUiState.Content.Feeds(visible)
        }

    fun onEvent(event: PodcastListEvent) {
        when (event) {
            is PodcastListEvent.FeedClicked -> emit(PodcastListEffect.OpenEpisodes(event.feedUrl))
            is PodcastListEvent.FilterChanged -> filter.value = event.filter
            PodcastListEvent.PullToRefresh -> refresh()
            PodcastListEvent.ActivityClicked -> emit(PodcastListEffect.OpenActivity)
            PodcastListEvent.SettingsClicked -> emit(PodcastListEffect.OpenSettings)
            PodcastListEvent.ConnectNextcloudClicked -> emit(PodcastListEffect.OpenConnect)
            PodcastListEvent.ChooseFolderClicked -> emit(PodcastListEffect.ChooseFolder)
            PodcastListEvent.NamingClicked -> emit(PodcastListEffect.OpenNaming)
            PodcastListEvent.PausedBannerActionClicked -> emit(PodcastListEffect.ResolvePausedQueue)
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            // Checked before anything starts, so an offline pull answers immediately instead of
            // timing out against every feed in turn (docs/UI.md §12.10).
            if (!connectivityMonitor.observe().first().online) {
                emit(PodcastListEffect.ShowMessage(SnackbarText.Offline))
                return@launch
            }
            refreshing.value = true
            try {
                // `null` means every feed — S1 refreshes the whole subscription list, S2 one feed.
                scheduler.requestFeedRefresh(null)
            } finally {
                refreshing.value = false
            }
            // Only *after* a refresh may the list reorder: the user asked for it and is watching the
            // indicator, so movement is expected rather than startling.
            freezeOrder()
        }
    }

    private suspend fun freezeOrder() {
        val feeds = feedRepository.getAll()
        val latest = episodeRepository.latestPublicationByFeed()
        order.value =
            feeds
                .sortedWith(
                    // Newest publication first; never-fetched last, then title A–Z. `compareByDescending`
                    // on a nullable puts nulls last, which is exactly the rule (docs/UI.md §4).
                    compareByDescending<Feed> { latest[it.url] }
                        .thenBy { it.title.lowercase() },
                ).map { it.url }
    }

    private fun emit(effect: PodcastListEffect) {
        effects.trySend(effect)
    }

    private data class Environment(
        val instanceLabel: String?,
        val namingPreview: String,
        val online: Boolean,
    )
}

/**
 * Re-projects rows into the frozen order.
 *
 * Feeds absent from [frozen] — subscribed on the server since the last freeze — are **appended**
 * rather than sorted in. They have to appear (the app is a follower, and a new subscription is not
 * ours to hide), but inserting one mid-list would shift every row below it, which is the movement
 * the freeze exists to prevent.
 */
internal fun List<FeedUi>.inFrozenOrder(frozen: List<String>): List<FeedUi> {
    if (frozen.isEmpty()) return this
    val byUrl = associateBy { it.url }
    val known = frozen.mapNotNull { byUrl[it] }
    val added = filterNot { it.url in frozen.toSet() }
    return known + added
}

private fun Feed.toUi(undecidedCount: Int?) =
    FeedUi(
        url = url,
        title = title.takeIf { it.isNotBlank() },
        artworkUrl = imageUrl,
        lastRefreshedAt = EpochTime.ofMillisOrNull(lastRefreshedAt),
        // A feed that has never been fetched has no episode rows, so SQL contributes no count for
        // it — and "no count" must render as "–", not as 0 (docs/UI.md §12.5).
        undecidedCount = if (lastRefreshedAt == null) null else undecidedCount ?: 0,
    )

/** S1's queue banner has no per-episode rows to inspect, so only the folder grant can pause it. */
private fun queueStatusForFolder(folder: FolderState): QueueStatus =
    when (folder) {
        FolderState.NOT_CHOSEN -> QueueStatus.Paused(QueueStatus.PauseCause.FOLDER_NOT_CHOSEN, queuedCount = 0)
        FolderState.REVOKED -> QueueStatus.Paused(QueueStatus.PauseCause.FOLDER_REVOKED, queuedCount = 0)
        FolderState.GRANTED -> QueueStatus.Running
    }

/**
 * Renders the checklist's naming example.
 *
 * A port because the template engine lives in `:core:naming` and needs a sample episode to resolve
 * against; `:feature:episodes` should not grow a dependency on it just to draw one line of text.
 */
fun interface NamingPreview {
    fun render(settings: NamingSettings): String
}
