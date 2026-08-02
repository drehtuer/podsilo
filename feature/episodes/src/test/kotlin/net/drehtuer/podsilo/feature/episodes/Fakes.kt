// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import net.drehtuer.podsilo.core.model.Episode
import net.drehtuer.podsilo.core.model.EpisodeLedgerRow
import net.drehtuer.podsilo.core.model.Feed
import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.core.model.port.BulkScope
import net.drehtuer.podsilo.core.model.port.Connectivity
import net.drehtuer.podsilo.core.model.port.ConnectivityMonitor
import net.drehtuer.podsilo.core.model.port.EpisodeLedgerRepository
import net.drehtuer.podsilo.core.model.port.EpisodeListItem
import net.drehtuer.podsilo.core.model.port.EpisodeRepository
import net.drehtuer.podsilo.core.model.port.FeedRefreshMetadata
import net.drehtuer.podsilo.core.model.port.FeedRepository
import net.drehtuer.podsilo.core.model.port.FeedUndecidedCount
import net.drehtuer.podsilo.core.model.port.LedgerFilter
import net.drehtuer.podsilo.core.model.port.LedgerFilterState
import net.drehtuer.podsilo.core.model.port.NamingSettings
import net.drehtuer.podsilo.core.model.port.NextcloudAccount
import net.drehtuer.podsilo.core.model.port.NextcloudCredentials
import net.drehtuer.podsilo.core.model.port.OlderThan
import net.drehtuer.podsilo.core.model.port.SettingsRepository
import net.drehtuer.podsilo.core.model.port.SwipeMapping
import net.drehtuer.podsilo.core.model.port.ThemePreference

const val FEED_URL = "https://example.org/feed.xml"

// Test builders: the parameter count is the type's field count, which is the point of a builder.
@Suppress("LongParameterList")
fun episode(
    key: String,
    title: String = key,
    pubDate: Long? = 1_752_480_000_000,
    durationMs: Long? = 1_800_000,
    description: String? = null,
    link: String? = null,
    enclosureUrl: String = "https://example.org/$key.mp3",
): Episode =
    Episode(
        episodeKey = key,
        feedUrl = FEED_URL,
        guid = key,
        enclosureUrl = enclosureUrl,
        title = title,
        description = description,
        pubDate = pubDate,
        durationMs = durationMs,
        link = link,
    )

@Suppress("LongParameterList")
fun ledgerRow(
    key: String,
    state: LedgerState,
    writtenFileName: String? = null,
    attempts: Int = 0,
    lastError: String? = null,
    syncedToServer: Boolean = false,
): EpisodeLedgerRow =
    EpisodeLedgerRow(
        episodeKey = key,
        feedUrl = FEED_URL,
        enclosureUrl = "https://example.org/$key.mp3",
        state = state,
        actionedAt = 0,
        syncedToServer = syncedToServer,
        attempts = attempts,
        lastError = lastError,
        writtenFileName = writtenFileName,
        durationSeconds = 1_800,
    )

/**
 * In-memory ledger that resolves [LedgerFilter] the way the Room DAO does, so a filter test here
 * exercises the same predicate the screen will see rather than a simplification of it.
 */
class FakeLedgerRepository(
    private val episodes: MutableList<Episode> = mutableListOf(),
) : EpisodeLedgerRepository {
    private val rows = MutableStateFlow<Map<String, EpisodeLedgerRow>>(emptyMap())

    /** Every write in order — bulk behaviour is about *how many* writes, not just the end state. */
    val writes = mutableListOf<List<EpisodeLedgerRow>>()

    fun seed(vararg items: Episode) {
        episodes += items
    }

    fun seedRow(row: EpisodeLedgerRow) {
        rows.value = rows.value + (row.episodeKey to row)
    }

    override fun observe(filter: LedgerFilter): Flow<List<EpisodeLedgerRow>> = rows.map { it.values.toList() }

    override fun observeEpisodes(filter: LedgerFilter): Flow<List<EpisodeListItem>> =
        rows.map { current ->
            episodes
                .filter { filter.feedUrl == null || it.feedUrl == filter.feedUrl }
                .mapNotNull { episode ->
                    val row = current[episode.episodeKey]
                    val matches =
                        when (filter.state) {
                            // "New" is the absence of a row — no date clause (docs/decisions/0013).
                            LedgerFilterState.NEW -> row == null
                            LedgerFilterState.DOWNLOADED -> row?.state == LedgerState.DOWNLOADED
                            LedgerFilterState.SKIPPED -> row?.state == LedgerState.SKIPPED
                            LedgerFilterState.ALL -> true
                        }
                    if (matches) EpisodeListItem(episode, row) else null
                }
        }

    override suspend fun get(episodeKey: String): EpisodeLedgerRow? = rows.value[episodeKey]

    override suspend fun upsert(row: EpisodeLedgerRow) {
        writes += listOf(row)
        rows.value = rows.value + (row.episodeKey to row)
    }

    override suspend fun getUnsynced(): List<EpisodeLedgerRow> = rows.value.values.filterNot { it.syncedToServer }

    override suspend fun markSynced(episodeKeys: List<String>) = Unit

    override suspend fun upsertAll(rows: List<EpisodeLedgerRow>) {
        writes += rows
        this.rows.value = this.rows.value + rows.associateBy { it.episodeKey }
    }

    override suspend fun previewUndecided(scope: BulkScope): List<FeedUndecidedCount> =
        undecided(scope).groupingBy { it.feedUrl }.eachCount().map { FeedUndecidedCount(it.key, it.value) }

    override suspend fun undecided(scope: BulkScope): List<Episode> =
        episodes.filter { rows.value[it.episodeKey] == null }
}

class FakeEpisodeRepository(
    private val episodes: MutableList<Episode> = mutableListOf(),
) : EpisodeRepository {
    fun seed(vararg items: Episode) {
        episodes += items
    }

    override fun observeForFeed(feedUrl: String): Flow<List<Episode>> =
        MutableStateFlow(episodes.filter { it.feedUrl == feedUrl })

    override suspend fun get(episodeKey: String): Episode? = episodes.firstOrNull { it.episodeKey == episodeKey }

    override suspend fun replaceForFeed(
        feedUrl: String,
        episodes: List<Episode>,
    ) = Unit

    override suspend fun deleteForFeed(feedUrl: String) = Unit
}

class FakeFeedRepository(
    private val feeds: MutableList<Feed> = mutableListOf(),
) : FeedRepository {
    fun seed(vararg items: Feed) {
        feeds += items
    }

    override fun observeAll(): Flow<List<Feed>> = MutableStateFlow(feeds.toList())

    override suspend fun getAll(): List<Feed> = feeds.toList()

    override suspend fun get(url: String): Feed? = feeds.firstOrNull { it.url == url }

    override suspend fun replaceAll(feeds: List<Feed>) = Unit

    override suspend fun updateRefreshMetadata(
        feedUrl: String,
        metadata: FeedRefreshMetadata,
    ) = Unit
}

fun feed(
    url: String = FEED_URL,
    title: String = "Der Podcast",
    imageUrl: String? = null,
): Feed =
    Feed(
        url = url,
        title = title,
        imageUrl = imageUrl,
        firstSeenAt = 0,
        lastRefreshedAt = null,
        httpEtag = null,
        httpLastModified = null,
    )

class FakeConnectivityMonitor(
    var online: Boolean = true,
) : ConnectivityMonitor {
    override fun observe(): Flow<Connectivity> = MutableStateFlow(Connectivity(online = online, metered = false))
}

/** Records what the screen asked to be scheduled — never runs anything. */
class RecordingScheduler : EpisodeScheduler {
    val downloads = mutableListOf<Pair<String, Boolean>>()
    val cancellations = mutableListOf<String>()
    val refreshes = mutableListOf<String?>()

    override fun enqueueDownload(
        episodeKey: String,
        userRequested: Boolean,
    ) {
        downloads += episodeKey to userRequested
    }

    override fun cancelDownload(episodeKey: String) {
        cancellations += episodeKey
    }

    override fun requestFeedRefresh(feedUrl: String?) {
        refreshes += feedUrl
    }
}

class FakeSettingsRepository(
    var swipeMapping: SwipeMapping = SwipeMapping(),
) : SettingsRepository {
    override fun observeNaming(): Flow<NamingSettings> = MutableStateFlow(NamingSettings())

    override suspend fun setNaming(settings: NamingSettings) = Unit

    override fun observeDownloadFolderUri(): Flow<String?> = MutableStateFlow("content://tree/podcasts")

    override suspend fun setDownloadFolderUri(uri: String?) = Unit

    override fun observeSyncIntervalMinutes(): Flow<Long> = MutableStateFlow(240)

    override suspend fun setSyncIntervalMinutes(minutes: Long) = Unit

    override fun observeTheme(): Flow<ThemePreference> = MutableStateFlow(ThemePreference.SYSTEM)

    override suspend fun setTheme(theme: ThemePreference) = Unit

    override fun observeSwipeMapping(): Flow<SwipeMapping> = MutableStateFlow(swipeMapping)

    override suspend fun setSwipeMapping(mapping: SwipeMapping) {
        swipeMapping = mapping
    }

    override fun observeAllowMobileData(): Flow<Boolean> = MutableStateFlow(false)

    override suspend fun setAllowMobileData(allowed: Boolean) = Unit

    override fun observeMarkOldOlderThan(): Flow<OlderThan> = MutableStateFlow(OlderThan.OFF)

    override suspend fun setMarkOldOlderThan(value: OlderThan) = Unit

    override fun observeNextcloudAccount(): Flow<NextcloudAccount?> = MutableStateFlow(null)

    override suspend fun nextcloudCredentials(): NextcloudCredentials? = null

    override suspend fun setNextcloudCredentials(credentials: NextcloudCredentials?) = Unit
}
