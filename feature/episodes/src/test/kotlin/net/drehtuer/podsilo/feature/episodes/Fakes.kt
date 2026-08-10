// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import net.drehtuer.podsilo.core.model.Episode
import net.drehtuer.podsilo.core.model.EpisodeLedgerRow
import net.drehtuer.podsilo.core.model.ErrorCause
import net.drehtuer.podsilo.core.model.Feed
import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.core.model.port.BulkScope
import net.drehtuer.podsilo.core.model.port.Connectivity
import net.drehtuer.podsilo.core.model.port.ConnectivityMonitor
import net.drehtuer.podsilo.core.model.port.EpisodeLedgerRepository
import net.drehtuer.podsilo.core.model.port.EpisodeListItem
import net.drehtuer.podsilo.core.model.port.EpisodeListRepository
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
    feedUrl: String = FEED_URL,
    pubDate: Long? = 1_752_480_000_000,
    durationMs: Long? = 1_800_000,
    description: String? = null,
    link: String? = null,
    enclosureUrl: String = "https://example.org/$key.mp3",
    imageUrl: String? = null,
): Episode =
    Episode(
        episodeKey = key,
        feedUrl = feedUrl,
        guid = key,
        enclosureUrl = enclosureUrl,
        title = title,
        description = description,
        pubDate = pubDate,
        durationMs = durationMs,
        link = link,
        imageUrl = imageUrl,
    )

@Suppress("LongParameterList")
fun ledgerRow(
    key: String,
    state: LedgerState,
    writtenFileName: String? = null,
    attempts: Int = 0,
    lastError: String? = null,
    lastErrorCause: ErrorCause? = null,
    lastErrorRetryable: Boolean? = null,
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
        lastErrorCause = lastErrorCause,
        lastErrorRetryable = lastErrorRetryable,
        writtenFileName = writtenFileName,
        durationSeconds = 1_800,
    )

/**
 * In-memory ledger that resolves [LedgerFilter] the way the Room DAO does, so a filter test here
 * exercises the same predicate the screen will see rather than a simplification of it.
 */
class FakeLedgerRepository(
    private val episodes: MutableList<Episode> = mutableListOf(),
) : EpisodeLedgerRepository,
    EpisodeListRepository {
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

    override fun observeRow(episodeKey: String): Flow<EpisodeLedgerRow?> = rows.map { it[episodeKey] }

    /** Derived from the same map, so this cannot disagree with [observeEpisodes] about a row's state. */
    override fun observeInFlight(): Flow<List<EpisodeListItem>> =
        rows.map { current ->
            episodes.mapNotNull { episode ->
                current[episode.episodeKey]
                    ?.takeIf { it.state in IN_FLIGHT_STATES }
                    ?.let { EpisodeListItem(episode, it) }
            }
        }

    override fun observeRecentlyDelivered(
        since: Long,
        limit: Int,
    ): Flow<List<EpisodeLedgerRow>> =
        rows.map { current ->
            current.values
                .filter { it.state == LedgerState.DOWNLOADED && it.writtenFileName != null && it.actionedAt > since }
                .sortedByDescending { it.actionedAt }
                .take(limit)
        }

    override fun observeUnsyncedCount(): Flow<Int> = rows.map { current -> current.values.count { !it.syncedToServer } }

    /** Derived from the same map as everything else, so a badge here cannot drift from its list. */
    override fun observeUndecidedCounts(): Flow<List<FeedUndecidedCount>> =
        rows.map { current ->
            episodes
                .filter { current[it.episodeKey] == null }
                .groupingBy { it.feedUrl }
                .eachCount()
                .map { FeedUndecidedCount(it.key, it.value) }
        }

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

    /** Undated episodes contribute nothing, so a feed with only those is absent — never zero. */
    override suspend fun latestPublicationByFeed(): Map<String, Long> =
        episodes
            .mapNotNull { episode -> episode.pubDate?.let { episode.feedUrl to it } }
            .groupBy(Pair<String, Long>::first, Pair<String, Long>::second)
            .mapValues { (_, dates) -> dates.max() }

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
    lastRefreshedAt: Long? = null,
): Feed =
    Feed(
        url = url,
        title = title,
        imageUrl = imageUrl,
        firstSeenAt = 0,
        lastRefreshedAt = lastRefreshedAt,
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

    /** Suspends until [completeRefresh] is called, so a test can observe `isRefreshing` while it is true. */
    override suspend fun requestFeedRefresh(feedUrl: String?) {
        refreshes += feedUrl
        inFlightRefresh?.await()
    }

    /** Set to hold a refresh open; complete it to let the scheduler return. */
    var inFlightRefresh: CompletableDeferred<Unit>? = null

    fun completeRefresh() {
        inFlightRefresh?.complete(Unit)
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

    override fun observeDeliveredClearedAt(): kotlinx.coroutines.flow.Flow<Long> = kotlinx.coroutines.flow.flowOf(0L)

    override suspend fun setDeliveredClearedAt(millis: Long) = Unit

    override fun observeMarkOldOlderThan(): Flow<OlderThan> = MutableStateFlow(OlderThan.OFF)

    override suspend fun setMarkOldOlderThan(value: OlderThan) = Unit

    val account = MutableStateFlow<NextcloudAccount?>(null)

    override fun observeNextcloudAccount(): Flow<NextcloudAccount?> = account

    override suspend fun nextcloudCredentials(): NextcloudCredentials? = null

    override suspend fun setNextcloudCredentials(credentials: NextcloudCredentials?) = Unit
}

/** Free space the dialog would report; `null` is the "provider cannot say" case it must tolerate. */
class FakeSpaceProbe(
    var freeBytes: Long? = null,
) : DownloadSpaceProbe {
    override suspend fun freeBytes(): Long? = freeBytes
}

/** Folder grant the screen sees; flip it to exercise the paused banner. */
class FakeFolderStatus(
    var state: FolderState = FolderState.GRANTED,
) : DownloadFolderStatus {
    override fun observe(): Flow<FolderState> = MutableStateFlow(state)
}

/** S7's three groups — the states the narrowed `observeInFlight` query selects. */
private val IN_FLIGHT_STATES =
    setOf(LedgerState.QUEUED, LedgerState.DOWNLOADING, LedgerState.ERROR)

/**
 * A settable [DownloadWorkMonitor], so a test can say "this episode is running and 62 % done" without
 * a WorkManager anywhere near it.
 */
class FakeDownloadWorkMonitor(
    initial: DownloadWork = DownloadWork(),
) : DownloadWorkMonitor {
    private val state = MutableStateFlow(initial)

    override fun observe(): Flow<DownloadWork> = state

    fun set(work: DownloadWork) {
        state.value = work
    }
}
