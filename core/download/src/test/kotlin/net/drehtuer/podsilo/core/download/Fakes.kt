// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.download

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import net.drehtuer.podsilo.core.model.Episode
import net.drehtuer.podsilo.core.model.EpisodeLedgerRow
import net.drehtuer.podsilo.core.model.Feed
import net.drehtuer.podsilo.core.model.port.BulkScope
import net.drehtuer.podsilo.core.model.port.EpisodeLedgerRepository
import net.drehtuer.podsilo.core.model.port.EpisodeListItem
import net.drehtuer.podsilo.core.model.port.EpisodeRepository
import net.drehtuer.podsilo.core.model.port.FeedRefreshMetadata
import net.drehtuer.podsilo.core.model.port.FeedRepository
import net.drehtuer.podsilo.core.model.port.FeedUndecidedCount
import net.drehtuer.podsilo.core.model.port.LedgerFilter
import net.drehtuer.podsilo.core.model.port.NamingSettings
import net.drehtuer.podsilo.core.model.port.NextcloudAccount
import net.drehtuer.podsilo.core.model.port.NextcloudCredentials
import net.drehtuer.podsilo.core.model.port.OlderThan
import net.drehtuer.podsilo.core.model.port.SettingsRepository
import net.drehtuer.podsilo.core.model.port.SwipeMapping
import net.drehtuer.podsilo.core.model.port.ThemePreference

// In-memory port doubles for the DownloadWorker tests. Deliberately hand-written rather than mocked:
// the assertions are about what ends up *stored* (CLAUDE.md §7 item 8's durability cases), which
// reads far better against real state than against verified call sequences.

class FakeFeedRepository(
    private val feeds: MutableList<Feed> = mutableListOf(),
) : FeedRepository {
    override fun observeAll(): Flow<List<Feed>> = MutableStateFlow(feeds.toList())

    override suspend fun getAll(): List<Feed> = feeds.toList()

    override suspend fun get(url: String): Feed? = feeds.firstOrNull { it.url == url }

    override suspend fun replaceAll(feeds: List<Feed>) {
        this.feeds.clear()
        this.feeds += feeds
    }

    override suspend fun updateRefreshMetadata(
        feedUrl: String,
        metadata: FeedRefreshMetadata,
    ) {
        val index = feeds.indexOfFirst { it.url == feedUrl }
        if (index < 0) return
        feeds[index] =
            feeds[index].copy(
                title = metadata.title,
                imageUrl = metadata.imageUrl,
                httpEtag = metadata.httpEtag,
                httpLastModified = metadata.httpLastModified,
                lastRefreshedAt = metadata.refreshedAt,
            )
    }
}

class FakeEpisodeRepository(
    private val episodes: MutableList<Episode> = mutableListOf(),
) : EpisodeRepository {
    override fun observeForFeed(feedUrl: String): Flow<List<Episode>> =
        MutableStateFlow(
            episodes.filter {
                it.feedUrl ==
                    feedUrl
            },
        )

    override suspend fun get(episodeKey: String): Episode? = episodes.firstOrNull { it.episodeKey == episodeKey }

    override suspend fun replaceForFeed(
        feedUrl: String,
        episodes: List<Episode>,
    ) {
        this.episodes.removeAll { it.feedUrl == feedUrl }
        this.episodes += episodes
    }

    override suspend fun deleteForFeed(feedUrl: String) {
        episodes.removeAll { it.feedUrl == feedUrl }
    }
}

class FakeEpisodeLedgerRepository(
    initial: List<EpisodeLedgerRow> = emptyList(),
) : EpisodeLedgerRepository {
    private val rows = MutableStateFlow(initial.associateBy { it.episodeKey })

    /** Every write in order — the download path's durability is about *sequence*, not just end state. */
    val writes = mutableListOf<EpisodeLedgerRow>()

    override fun observe(filter: LedgerFilter): Flow<List<EpisodeLedgerRow>> = rows.map { it.values.toList() }

    override fun observeEpisodes(filter: LedgerFilter): Flow<List<EpisodeListItem>> = MutableStateFlow(emptyList())

    override suspend fun get(episodeKey: String): EpisodeLedgerRow? = rows.value[episodeKey]

    override suspend fun upsert(row: EpisodeLedgerRow) {
        writes += row
        rows.value = rows.value + (row.episodeKey to row)
    }

    override suspend fun getUnsynced(): List<EpisodeLedgerRow> = rows.value.values.filterNot { it.syncedToServer }

    override suspend fun markSynced(episodeKeys: List<String>) {
        val keys = episodeKeys.toSet()
        rows.value = rows.value.mapValues { (key, row) -> if (key in keys) row.copy(syncedToServer = true) else row }
    }

    override suspend fun upsertAll(rows: List<EpisodeLedgerRow>) = rows.forEach { row -> upsert(row) }

    override suspend fun previewUndecided(scope: BulkScope): List<FeedUndecidedCount> = emptyList()

    override suspend fun undecided(scope: BulkScope): List<Episode> = emptyList()
}

class FakeSettingsRepository(
    private val naming: NamingSettings = NamingSettings(),
    private val downloadFolderUri: String? = "content://tree/podcasts",
) : SettingsRepository {
    override fun observeNaming(): Flow<NamingSettings> = MutableStateFlow(naming)

    override suspend fun setNaming(settings: NamingSettings) = error("not needed by these tests")

    override fun observeDownloadFolderUri(): Flow<String?> = MutableStateFlow(downloadFolderUri)

    override suspend fun setDownloadFolderUri(uri: String?) = error("not needed by these tests")

    override fun observeSyncIntervalMinutes(): Flow<Long> = MutableStateFlow(0)

    override suspend fun setSyncIntervalMinutes(minutes: Long) = error("not needed by these tests")

    override fun observeTheme(): Flow<ThemePreference> = MutableStateFlow(ThemePreference.SYSTEM)

    override suspend fun setTheme(theme: ThemePreference) = error("not needed by these tests")

    override fun observeSwipeMapping(): Flow<SwipeMapping> = MutableStateFlow(SwipeMapping())

    override suspend fun setSwipeMapping(mapping: SwipeMapping) = error("not needed by these tests")

    override fun observeAllowMobileData(): Flow<Boolean> = MutableStateFlow(false)

    override suspend fun setAllowMobileData(allowed: Boolean) = error("not needed by these tests")

    override fun observeMarkOldOlderThan(): Flow<OlderThan> = MutableStateFlow(OlderThan.OFF)

    override suspend fun setMarkOldOlderThan(value: OlderThan) = error("not needed by these tests")

    override fun observeNextcloudAccount(): Flow<NextcloudAccount?> = MutableStateFlow(null)

    override suspend fun nextcloudCredentials(): NextcloudCredentials? = null

    override suspend fun setNextcloudCredentials(credentials: NextcloudCredentials?) =
        error("not needed by these tests")
}

class RecordingSyncTrigger : SyncTrigger {
    var requests: Int = 0
        private set

    override fun requestSyncNow() {
        requests++
    }
}
