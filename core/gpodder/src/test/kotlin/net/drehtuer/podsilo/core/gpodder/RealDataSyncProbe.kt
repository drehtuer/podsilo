// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.gpodder

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import net.drehtuer.podsilo.core.model.Episode
import net.drehtuer.podsilo.core.model.EpisodeLedgerRow
import net.drehtuer.podsilo.core.model.Feed
import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.core.model.SyncState
import net.drehtuer.podsilo.core.model.port.EpisodeLedgerRepository
import net.drehtuer.podsilo.core.model.port.FeedRefreshMetadata
import net.drehtuer.podsilo.core.model.port.FeedRepository
import net.drehtuer.podsilo.core.model.port.LedgerFilter
import net.drehtuer.podsilo.core.model.port.LoginResult
import net.drehtuer.podsilo.core.model.port.SyncStateRepository
import net.drehtuer.podsilo.core.sync.SyncOrchestrator
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * A **full `SyncOrchestrator` pass against the real server, using the account's real
 * subscriptions and a real episode.**
 *
 * CLAUDE.md §7 ranks sync reconciliation as the highest test priority in the project — "the most
 * complex, most breakable logic here" — and until now it had only ever run against `MockWebServer`
 * and `opodsync`. This runs the production orchestrator, the production client, and the production
 * reconciliation, over in-memory storage.
 *
 * **In-memory repositories, not fakes of the logic.** `:core:database` is an Android module and
 * cannot be loaded here, so the four ports are backed by maps. Nothing about the *behaviour* under
 * test is stubbed: the orchestration order, the outbox semantics, the reconciliation and the `since`
 * bookkeeping are all the real thing.
 *
 * It **writes a real `PLAY` for a real episode** and is therefore gated behind the same
 * `-Pwrite=<loginName>` guard as the rest of the write probe.
 */
@Suppress("LongMethod") // A probe is a narrated sequence; splitting it hides the order under test.
internal suspend fun realDataSyncPass(
    http: OkHttpClient,
    result: LoginResult,
) {
    val client = RetrofitGpodderClientFactory(http).create(result.credentials)
    val feeds = InMemoryFeeds()
    val ledger = InMemoryLedger()
    val syncState = InMemorySyncState()
    val orchestrator = SyncOrchestrator(feeds, ledger, syncState, client)

    println()
    println("REAL-DATA SYNC PASS")

    // 1. A first pass with an empty outbox: this is what a fresh install does.
    println("→ sync() #1 (nothing to push)")
    println("   outcome: ${orchestrator.sync()}")
    val mirrored = feeds.all().map { it.url }
    println("   subscriptions mirrored locally: ${mirrored.size}")
    mirrored.forEach { println("     $it") }
    println("   since after pass 1: ${syncState.get().lastEpisodeActionSyncTs}")

    // 2. A real episode from one of those real feeds.
    val episode = firstRealEpisode(http, mirrored)
    if (episode == null) {
        println("✗ could not read an episode out of any subscribed feed — stopping before writing")
        return
    }
    println()
    println("→ real episode chosen from ${episode.feedUrl}")
    println("   guid=${episode.episodeKey}")
    println("   enclosure=${episode.enclosureUrl.take(90)}")

    // 3. Mark it played locally, exactly as a swipe would: durable row first, unsynced.
    ledger.upsert(
        EpisodeLedgerRow(
            episodeKey = episode.episodeKey,
            feedUrl = episode.feedUrl,
            enclosureUrl = episode.enclosureUrl,
            state = LedgerState.SKIPPED,
            actionedAt = System.currentTimeMillis(),
            syncedToServer = false,
            attempts = 0,
            lastError = null,
            writtenFileName = null,
            durationSeconds = 1800,
        ),
    )
    println("   local ledger row written: SKIPPED, syncedToServer=false")

    // 4. The pass that actually pushes it.
    println()
    println("→ sync() #2 (one row in the outbox)")
    println("   outcome: ${orchestrator.sync()}")
    val pushed = ledger.get(episode.episodeKey)
    println("   syncedToServer after push: ${pushed?.syncedToServer}")
    println("   since after pass 2: ${syncState.get().lastEpisodeActionSyncTs}")

    // 5. Did the server actually keep it, for this real episode?
    val onServer =
        client
            .fetchEpisodeActions(since = 0)
            .actions
            .filter { it.podcast == episode.feedUrl }
    println()
    println("SERVER STATE for that feed: ${onServer.size} action(s)")
    onServer.forEach {
        println("   ${it.action} guid=${it.guid} position=${it.position} total=${it.total} at=${it.timestamp}")
    }

    // 6. A third pass must be a no-op: the outbox is empty and reconciliation must not re-queue.
    println()
    println("→ sync() #3 (must change nothing)")
    println("   outcome: ${orchestrator.sync()}")
    val after = ledger.get(episode.episodeKey)
    println("   state still ${after?.state}, syncedToServer=${after?.syncedToServer}")
    println("   since after pass 3: ${syncState.get().lastEpisodeActionSyncTs}")

    println()
    println(
        if (after?.state == LedgerState.SKIPPED && after.syncedToServer) {
            "✓ round trip intact: marked played locally → pushed → echoed back → still SKIPPED, still synced"
        } else {
            "✗ the ledger row did not survive its own echo: $after"
        },
    )
}

/**
 * The first episode of the first subscribed feed we can parse.
 *
 * Deliberately crude regex rather than `:core:feed`, which is an Android module and cannot be loaded
 * here — the point of this probe is the *sync* path, and the feed is only a source of a genuine
 * `guid` and enclosure URL.
 */
private fun firstRealEpisode(
    http: OkHttpClient,
    feedUrls: List<String>,
): Episode? = feedUrls.firstNotNullOfOrNull { feedUrl -> episodeFrom(http, feedUrl) }

private fun episodeFrom(
    http: OkHttpClient,
    feedUrl: String,
): Episode? {
    val xml =
        runCatching {
            http.newCall(Request.Builder().url(feedUrl).build()).execute().use { it.body?.string() }
        }.getOrNull() ?: return null

    val item = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1) ?: return null
    val enclosure = Regex("""<enclosure[^>]*url="([^"]+)"""").find(item)?.groupValues?.get(1) ?: return null
    val guid =
        Regex("<guid[^>]*>(.*?)</guid>", RegexOption.DOT_MATCHES_ALL)
            .find(item)
            ?.groupValues
            ?.get(1)
            ?.trim()
    val title =
        Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
            .find(item)
            ?.groupValues
            ?.get(1)
            ?.trim()

    return Episode(
        // The app's own identity rule: guid, falling back to the enclosure URL (CLAUDE.md §5).
        episodeKey = guid?.takeIf { it.isNotBlank() } ?: enclosure,
        feedUrl = feedUrl,
        guid = guid,
        enclosureUrl = enclosure,
        title = title.orEmpty(),
        description = null,
        pubDate = null,
        durationMs = null,
        link = null,
    )
}

private class InMemoryFeeds : FeedRepository {
    private val feeds = MutableStateFlow<List<Feed>>(emptyList())

    fun all(): List<Feed> = feeds.value

    override fun observeAll(): Flow<List<Feed>> = feeds

    override suspend fun getAll(): List<Feed> = feeds.value

    override suspend fun get(url: String): Feed? = feeds.value.firstOrNull { it.url == url }

    override suspend fun replaceAll(feeds: List<Feed>) {
        this.feeds.value = feeds
    }

    override suspend fun updateRefreshMetadata(
        feedUrl: String,
        metadata: FeedRefreshMetadata,
    ) = Unit
}

private class InMemoryLedger : EpisodeLedgerRepository {
    private val rows = MutableStateFlow<Map<String, EpisodeLedgerRow>>(emptyMap())

    override fun observe(filter: LedgerFilter): Flow<List<EpisodeLedgerRow>> = rows.map { it.values.toList() }

    override suspend fun get(episodeKey: String): EpisodeLedgerRow? = rows.value[episodeKey]

    override fun observeRow(episodeKey: String): Flow<EpisodeLedgerRow?> = rows.map { it[episodeKey] }

    override suspend fun upsert(row: EpisodeLedgerRow) {
        rows.value = rows.value + (row.episodeKey to row)
    }

    override suspend fun getUnsynced(): List<EpisodeLedgerRow> = rows.value.values.filterNot { it.syncedToServer }

    override suspend fun markSynced(episodeKeys: List<String>) {
        rows.value =
            rows.value.mapValues { (key, row) ->
                if (key in episodeKeys.toSet()) row.copy(syncedToServer = true) else row
            }
    }

    override suspend fun upsertAll(rows: List<EpisodeLedgerRow>) {
        this.rows.value = this.rows.value + rows.associateBy { it.episodeKey }
    }
}

private class InMemorySyncState : SyncStateRepository {
    private var state = SyncState(lastEpisodeActionSyncTs = 0, deviceId = "podsilo-probe")

    override suspend fun get(): SyncState = state

    override suspend fun save(state: SyncState) {
        this.state = state
    }
}
