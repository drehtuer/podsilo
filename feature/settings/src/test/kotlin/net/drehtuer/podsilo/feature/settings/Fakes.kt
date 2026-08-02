// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import net.drehtuer.podsilo.core.model.Episode
import net.drehtuer.podsilo.core.model.EpisodeLedgerRow
import net.drehtuer.podsilo.core.model.Feed
import net.drehtuer.podsilo.core.model.port.BulkScope
import net.drehtuer.podsilo.core.model.port.EpisodeLedgerRepository
import net.drehtuer.podsilo.core.model.port.EpisodeListItem
import net.drehtuer.podsilo.core.model.port.EpisodeListRepository
import net.drehtuer.podsilo.core.model.port.FeedRefreshMetadata
import net.drehtuer.podsilo.core.model.port.FeedRepository
import net.drehtuer.podsilo.core.model.port.FeedUndecidedCount
import net.drehtuer.podsilo.core.model.port.LedgerFilter
import net.drehtuer.podsilo.core.model.port.LoginFlow
import net.drehtuer.podsilo.core.model.port.LoginResult
import net.drehtuer.podsilo.core.model.port.NamingSettings
import net.drehtuer.podsilo.core.model.port.NextcloudAccount
import net.drehtuer.podsilo.core.model.port.NextcloudCredentials
import net.drehtuer.podsilo.core.model.port.NextcloudLoginFlowClient
import net.drehtuer.podsilo.core.model.port.OlderThan
import net.drehtuer.podsilo.core.model.port.SettingsRepository
import net.drehtuer.podsilo.core.model.port.SwipeMapping
import net.drehtuer.podsilo.core.model.port.ThemePreference

const val FEED_URL = "https://example.org/feed.xml"

fun episode(
    key: String,
    feedUrl: String = FEED_URL,
    pubDate: Long? = 1_784_019_600_000,
): Episode =
    Episode(
        episodeKey = key,
        feedUrl = feedUrl,
        guid = key,
        enclosureUrl = "https://example.org/$key.mp3",
        title = key,
        description = null,
        pubDate = pubDate,
        durationMs = 1_800_000,
        link = null,
    )

fun feed(
    url: String = FEED_URL,
    title: String = "Der Podcast",
): Feed =
    Feed(
        url = url,
        title = title,
        imageUrl = null,
        firstSeenAt = 0,
        lastRefreshedAt = null,
        httpEtag = null,
        httpLastModified = null,
    )

/** Writes are recorded as batches, because "one transaction" is an assertion about *how many*. */
class FakeLedgerRepository : EpisodeLedgerRepository {
    val writes = mutableListOf<List<EpisodeLedgerRow>>()
    private val rows = MutableStateFlow<Map<String, EpisodeLedgerRow>>(emptyMap())

    override fun observe(filter: LedgerFilter): Flow<List<EpisodeLedgerRow>> = rows.map { it.values.toList() }

    override suspend fun get(episodeKey: String): EpisodeLedgerRow? = rows.value[episodeKey]

    override fun observeRow(episodeKey: String): Flow<EpisodeLedgerRow?> = rows.map { it[episodeKey] }

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
}

/** The count and the write share one list, exactly as they share one SQL predicate in Room. */
class FakeListRepository(
    private val undecided: MutableList<Episode> = mutableListOf(),
) : EpisodeListRepository {
    fun seed(vararg items: Episode) {
        undecided += items
    }

    override fun observeEpisodes(filter: LedgerFilter): Flow<List<EpisodeListItem>> = MutableStateFlow(emptyList())

    override fun observeUndecidedCounts(): Flow<List<FeedUndecidedCount>> = MutableStateFlow(emptyList())

    override suspend fun previewUndecided(scope: BulkScope): List<FeedUndecidedCount> =
        undecided(scope).groupingBy { it.feedUrl }.eachCount().map { FeedUndecidedCount(it.key, it.value) }

    override suspend fun undecided(scope: BulkScope): List<Episode> =
        undecided.filter {
            scope.olderThanMillis == null ||
                (it.pubDate != null && it.pubDate!! < scope.olderThanMillis!!)
        }
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

/** Mutable, so a test can assert that a control actually persisted. */
class FakeSettingsRepository : SettingsRepository {
    val naming = MutableStateFlow(NamingSettings())
    val folderUri = MutableStateFlow<String?>(null)
    val syncInterval = MutableStateFlow(60L)
    val theme = MutableStateFlow(ThemePreference.SYSTEM)
    val swipe = MutableStateFlow(SwipeMapping())
    val mobileData = MutableStateFlow(false)
    val olderThan = MutableStateFlow(OlderThan.OFF)
    val account = MutableStateFlow<NextcloudAccount?>(null)
    var storedCredentials: NextcloudCredentials? = null

    override fun observeNaming(): Flow<NamingSettings> = naming

    override suspend fun setNaming(settings: NamingSettings) {
        naming.value = settings
    }

    override fun observeDownloadFolderUri(): Flow<String?> = folderUri

    override suspend fun setDownloadFolderUri(uri: String?) {
        folderUri.value = uri
    }

    override fun observeSyncIntervalMinutes(): Flow<Long> = syncInterval

    override suspend fun setSyncIntervalMinutes(minutes: Long) {
        syncInterval.value = minutes
    }

    override fun observeTheme(): Flow<ThemePreference> = theme

    override suspend fun setTheme(theme: ThemePreference) {
        this.theme.value = theme
    }

    override fun observeSwipeMapping(): Flow<SwipeMapping> = swipe

    override suspend fun setSwipeMapping(mapping: SwipeMapping) {
        swipe.value = mapping
    }

    override fun observeAllowMobileData(): Flow<Boolean> = mobileData

    override suspend fun setAllowMobileData(allowed: Boolean) {
        mobileData.value = allowed
    }

    override fun observeMarkOldOlderThan(): Flow<OlderThan> = olderThan

    override suspend fun setMarkOldOlderThan(value: OlderThan) {
        olderThan.value = value
    }

    override fun observeNextcloudAccount(): Flow<NextcloudAccount?> = account

    override suspend fun nextcloudCredentials(): NextcloudCredentials? = storedCredentials

    override suspend fun setNextcloudCredentials(credentials: NextcloudCredentials?) {
        storedCredentials = credentials
        account.value = credentials?.account
    }
}

class FakeCounts(
    private val errors: Int = 0,
    private val outbox: Int = 0,
) : SettingsCounts {
    override fun observeErrorLogCount(): Flow<Int> = MutableStateFlow(errors)

    override fun observeOutboxDepth(): Flow<Int> = MutableStateFlow(outbox)
}

/**
 * A scriptable Login Flow v2 server. Each step can be made to fail independently, because the order
 * of the three is the guarantee under test.
 */
class FakeLoginFlowClient(
    var startResult: Result<LoginFlow> = Result.success(FLOW),
    var pollResult: Result<LoginResult> = Result.success(RESULT),
    var verifyResult: Result<Unit> = Result.success(Unit),
) : NextcloudLoginFlowClient {
    val startedWith = mutableListOf<String>()

    override suspend fun start(baseUrl: String): Result<LoginFlow> {
        startedWith += baseUrl
        return startResult
    }

    override suspend fun poll(flow: LoginFlow): Result<LoginResult> = pollResult

    override suspend fun verifyGpodderSync(credentials: NextcloudCredentials): Result<Unit> = verifyResult

    companion object {
        val FLOW =
            LoginFlow(
                loginUrl = "https://cloud.example.org/login/flow",
                pollEndpoint = "https://cloud.example.org/login/v2/poll",
                token = "tok",
            )
        val RESULT =
            LoginResult(
                serverUrl = "https://cloud.example.org",
                loginName = "author",
                appPassword = "app-password",
            )
    }
}

class RecordingSyncTrigger : ConnectSyncTrigger {
    var syncs = 0

    override fun requestSyncNow() {
        syncs++
    }
}
