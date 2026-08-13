// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import java.time.Instant

/**
 * S1 — the podcast list, and the app's launcher screen (`docs/UI.md` §B2).
 *
 * @property setup `null` once the app can actually complete a download. The checklist is not
 *   onboarding decoration: without it, the first time the author learns no folder is chosen is when
 *   a download fails (`docs/UI.md` §4).
 * @property activityBadge a dot, not a count — S7 owns the detail. True when anything is running,
 *   failed, or still unsynced.
 */
data class PodcastListUiState(
    val content: Content = Content.Loading,
    val filter: PodcastFilter = PodcastFilter.WITH_NEW,
    val isRefreshing: Boolean = false,
    val queueStatus: QueueStatus = QueueStatus.Running,
    val isOffline: Boolean = false,
    val setup: SetupChecklist? = null,
    val activityBadge: Boolean = false,
    val totalUndecided: Int = 0,
) {
    sealed interface Content {
        /** No Nextcloud yet: the empty state offers **Connect**, never an add-feed field. */
        data object NotConfigured : Content

        data object Loading : Content

        /** Configured, and the server's subscription list is genuinely empty. */
        data object NoSubscriptions : Content

        data class Feeds(
            val feeds: List<FeedUi>,
        ) : Content
    }
}

/**
 * One podcast row.
 *
 * @property title `null` renders the [url] instead. A feed has no title until the first successful
 *   fetch, and "Unknown podcast" would be a worse answer than the URL the user recognises
 *   (architecture §4).
 * @property undecidedCount `null` renders "–". **Never fetched is not zero** (`docs/UI.md` §12.5):
 *   a feed that has not been read yet has an unknown number of new episodes, not none.
 */
data class FeedUi(
    val url: String,
    val title: String?,
    val artworkUrl: String? = null,
    val lastRefreshedAt: Instant? = null,
    val undecidedCount: Int? = null,
    val activeDownloads: Int = 0,
) {
    val displayTitle: String get() = title?.takeIf { it.isNotBlank() } ?: url
}

/**
 * The first-run steps, shown until the app can complete a download (`docs/UI.md` §4).
 *
 * Step 3 (naming) is explicitly optional — a default template exists — so it never holds the card
 * open; only steps 1 and 2 do.
 */
data class SetupChecklist(
    val nextcloudConnected: Boolean,
    val instanceLabel: String?,
    val folderState: FolderState,
    val namingPreview: String,
) {
    val isComplete: Boolean get() = nextcloudConnected && folderState == FolderState.GRANTED
}

/** Session-scoped, not persisted: the default makes the home screen a worklist (`docs/UI.md` §4). */
enum class PodcastFilter { WITH_NEW, ALL }

sealed interface PodcastListEvent {
    data class FeedClicked(
        val feedUrl: String,
    ) : PodcastListEvent

    data class FilterChanged(
        val filter: PodcastFilter,
    ) : PodcastListEvent

    data object PullToRefresh : PodcastListEvent

    data object ActivityClicked : PodcastListEvent

    data object SettingsClicked : PodcastListEvent

    data object ConnectNextcloudClicked : PodcastListEvent

    data object ChooseFolderClicked : PodcastListEvent

    data object NamingClicked : PodcastListEvent

    data object PausedBannerActionClicked : PodcastListEvent
}

sealed interface PodcastListEffect {
    data class OpenEpisodes(
        val feedUrl: String,
    ) : PodcastListEffect

    data object OpenSettings : PodcastListEffect

    data object OpenConnect : PodcastListEffect

    data object OpenNaming : PodcastListEffect

    data object OpenActivity : PodcastListEffect

    /** The SAF picker — an Activity result, so only the host can launch it. */
    data object ChooseFolder : PodcastListEffect

    data object ResolvePausedQueue : PodcastListEffect

    data class ShowMessage(
        val text: SnackbarText,
    ) : PodcastListEffect
}
