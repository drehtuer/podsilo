// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import net.drehtuer.podsilo.core.model.EpisodeLedgerRow
import net.drehtuer.podsilo.core.model.Feed
import net.drehtuer.podsilo.core.model.SyncState
import net.drehtuer.podsilo.core.model.port.EpisodeAction
import net.drehtuer.podsilo.core.model.port.EpisodeActionPage
import net.drehtuer.podsilo.core.model.port.EpisodeLedgerRepository
import net.drehtuer.podsilo.core.model.port.FeedRefreshMetadata
import net.drehtuer.podsilo.core.model.port.FeedRepository
import net.drehtuer.podsilo.core.model.port.GpodderClient
import net.drehtuer.podsilo.core.model.port.LedgerFilter
import net.drehtuer.podsilo.core.model.port.NamingSettings
import net.drehtuer.podsilo.core.model.port.NextcloudAccount
import net.drehtuer.podsilo.core.model.port.NextcloudCredentials
import net.drehtuer.podsilo.core.model.port.OlderThan
import net.drehtuer.podsilo.core.model.port.SettingsRepository
import net.drehtuer.podsilo.core.model.port.SubscriptionDelta
import net.drehtuer.podsilo.core.model.port.SwipeMapping
import net.drehtuer.podsilo.core.model.port.SyncStateRepository
import net.drehtuer.podsilo.core.model.port.ThemePreference
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * [SyncWorker] is deliberately thin — the sync logic itself is `:core:sync`'s, tested there without
 * Android. What is worth asserting here is the translation layer: credentials in, `SyncOutcome`
 * mapped to the right `WorkManager` result.
 */
@RunWith(RobolectricTestRunner::class)
class SyncWorkerTest {
    private lateinit var context: Context
    private val settings = FakeSettingsRepository()
    private val client = FakeGpodderClient()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    private fun buildWorker(): SyncWorker {
        val factory =
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker =
                    SyncWorker(
                        appContext = appContext,
                        workerParameters = workerParameters,
                        settingsRepository = settings,
                        syncOrchestratorFactory =
                            SyncOrchestratorFactory(
                                feedRepository = FakeFeedRepository(),
                                episodeLedgerRepository = FakeEpisodeLedgerRepository(),
                                syncStateRepository = FakeSyncStateRepository(),
                                gpodderClientFactory = { client },
                                clock = Clock.fixed(Instant.ofEpochMilli(0), ZoneOffset.UTC),
                            ),
                    )
            }
        return TestListenableWorkerBuilder<SyncWorker>(context).setWorkerFactory(factory).build()
    }

    @Test
    fun `with no account configured the pass is a no-op, not a failure`() =
        runBlocking {
            settings.credentials = null

            assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())
            assertEquals("no credentials means no request at all", 0, client.subscriptionCalls)
        }

    @Test
    fun `a completed pass succeeds`() =
        runBlocking {
            assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())
            assertEquals(1, client.subscriptionCalls)
        }

    @Test
    fun `a network failure asks WorkManager to retry rather than dropping the outbox`() =
        runBlocking {
            client.failWith = IOException("no route to host")

            assertEquals(ListenableWorker.Result.retry(), buildWorker().doWork())
        }
}

private class FakeGpodderClient : GpodderClient {
    var failWith: IOException? = null
    var subscriptionCalls: Int = 0
        private set

    override suspend fun fetchSubscriptions(since: Long?): SubscriptionDelta {
        subscriptionCalls++
        failWith?.let { throw it }
        return SubscriptionDelta(add = emptyList(), remove = emptyList(), timestamp = 0)
    }

    override suspend fun postEpisodeActions(actions: List<EpisodeAction>): Result<Unit> = Result.success(Unit)

    override suspend fun fetchEpisodeActions(since: Long): EpisodeActionPage =
        EpisodeActionPage(actions = emptyList(), timestamp = 0)
}

private class FakeSettingsRepository : SettingsRepository {
    var credentials: NextcloudCredentials? =
        NextcloudCredentials("https://cloud.example.org", "podsilo", "app-password")

    override fun observeNaming(): Flow<NamingSettings> = MutableStateFlow(NamingSettings())

    override suspend fun setNaming(settings: NamingSettings) = error("not needed by these tests")

    override fun observeDownloadFolderUri(): Flow<String?> = MutableStateFlow(null)

    override suspend fun setDownloadFolderUri(uri: String?) = error("not needed by these tests")

    override fun observeSyncIntervalMinutes(): Flow<Long> = MutableStateFlow(0)

    override suspend fun setSyncIntervalMinutes(minutes: Long) = error("not needed by these tests")

    override fun observeTheme(): Flow<ThemePreference> = MutableStateFlow(ThemePreference.SYSTEM)

    override suspend fun setTheme(theme: ThemePreference) = error("not needed by these tests")

    override fun observeSwipeMapping(): Flow<SwipeMapping> = MutableStateFlow(SwipeMapping())

    override suspend fun setSwipeMapping(mapping: SwipeMapping) = error("not needed by these tests")

    override fun observeAllowMobileData(): Flow<Boolean> = MutableStateFlow(false)

    override suspend fun setAllowMobileData(allowed: Boolean) = error("not needed by these tests")

    override fun observeDeliveredClearedAt(): kotlinx.coroutines.flow.Flow<Long> = kotlinx.coroutines.flow.flowOf(0L)

    override suspend fun setDeliveredClearedAt(millis: Long) = Unit

    override fun observeMarkOldOlderThan(): Flow<OlderThan> = MutableStateFlow(OlderThan.OFF)

    override suspend fun setMarkOldOlderThan(value: OlderThan) = error("not needed by these tests")

    override fun observeNextcloudAccount(): Flow<NextcloudAccount?> = MutableStateFlow(credentials?.account)

    override suspend fun nextcloudCredentials(): NextcloudCredentials? = credentials

    override suspend fun setNextcloudCredentials(credentials: NextcloudCredentials?) =
        error("not needed by these tests")
}

private class FakeFeedRepository : FeedRepository {
    private val feeds = MutableStateFlow(emptyList<Feed>())

    override fun observeAll(): Flow<List<Feed>> = feeds

    override suspend fun getAll(): List<Feed> = feeds.value

    override suspend fun get(url: String): Feed? = feeds.value.firstOrNull { it.url == url }

    override suspend fun replaceAll(feeds: List<Feed>) {
        this.feeds.value = feeds
    }

    override suspend fun updateRefreshMetadata(
        feedUrl: String,
        metadata: FeedRefreshMetadata,
    ) = error("the sync pass never refreshes feeds")
}

private class FakeEpisodeLedgerRepository : EpisodeLedgerRepository {
    private val rows = MutableStateFlow(emptyMap<String, EpisodeLedgerRow>())

    override fun observe(filter: LedgerFilter): Flow<List<EpisodeLedgerRow>> = rows.map { it.values.toList() }

    override suspend fun get(episodeKey: String): EpisodeLedgerRow? = rows.value[episodeKey]

    override fun observeRow(episodeKey: String): Flow<EpisodeLedgerRow?> = MutableStateFlow(null)

    override suspend fun upsert(row: EpisodeLedgerRow) {
        rows.value = rows.value + (row.episodeKey to row)
    }

    override suspend fun getUnsynced(): List<EpisodeLedgerRow> = rows.value.values.filterNot { it.syncedToServer }

    override suspend fun markSynced(episodeKeys: List<String>) {
        val keys = episodeKeys.toSet()
        rows.value = rows.value.mapValues { (key, row) -> if (key in keys) row.copy(syncedToServer = true) else row }
    }

    override suspend fun upsertAll(rows: List<EpisodeLedgerRow>) = rows.forEach { row -> upsert(row) }
}

private class FakeSyncStateRepository : SyncStateRepository {
    private var state = SyncState(lastEpisodeActionSyncTs = 0, deviceId = "test-device")

    override suspend fun get(): SyncState = state

    override suspend fun save(state: SyncState) {
        this.state = state
    }
}
