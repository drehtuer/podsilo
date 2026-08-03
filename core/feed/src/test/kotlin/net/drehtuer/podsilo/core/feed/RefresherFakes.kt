// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.feed

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import net.drehtuer.podsilo.core.model.Episode
import net.drehtuer.podsilo.core.model.EpisodeLedgerRow
import net.drehtuer.podsilo.core.model.port.BulkScope
import net.drehtuer.podsilo.core.model.port.BulkScopeKind
import net.drehtuer.podsilo.core.model.port.EpisodeLedgerRepository
import net.drehtuer.podsilo.core.model.port.EpisodeListItem
import net.drehtuer.podsilo.core.model.port.EpisodeListRepository
import net.drehtuer.podsilo.core.model.port.FeedUndecidedCount
import net.drehtuer.podsilo.core.model.port.LedgerFilter
import net.drehtuer.podsilo.core.model.port.LogCategory
import net.drehtuer.podsilo.core.model.port.LogEntry
import net.drehtuer.podsilo.core.model.port.LogRepository
import net.drehtuer.podsilo.core.model.port.NamingSettings
import net.drehtuer.podsilo.core.model.port.NewLogEntry
import net.drehtuer.podsilo.core.model.port.NextcloudAccount
import net.drehtuer.podsilo.core.model.port.NextcloudCredentials
import net.drehtuer.podsilo.core.model.port.OlderThan
import net.drehtuer.podsilo.core.model.port.SettingsRepository
import net.drehtuer.podsilo.core.model.port.SwipeMapping
import net.drehtuer.podsilo.core.model.port.ThemePreference

/**
 * Ledger double for the refresher tests. Records every write so a test can assert not just the end
 * state but **what state was written** — the whole point being that a refresh may write `SKIPPED`
 * and must never write `QUEUED` (`docs/decisions/0013`).
 */
class RecordingLedgerRepository(
    private val undecided: MutableList<Episode> = mutableListOf(),
) : EpisodeLedgerRepository,
    EpisodeListRepository {
    val writes = mutableListOf<EpisodeLedgerRow>()

    /** Scopes the fake was asked about, so a test can pin *which* cutoff the rule used. */
    val queriedScopes = mutableListOf<BulkScope>()

    fun seedUndecided(episodes: List<Episode>) {
        undecided.clear()
        undecided += episodes
    }

    override fun observe(filter: LedgerFilter): Flow<List<EpisodeLedgerRow>> = MutableStateFlow(writes.toList())

    override fun observeEpisodes(filter: LedgerFilter): Flow<List<EpisodeListItem>> = MutableStateFlow(emptyList())

    override suspend fun get(episodeKey: String): EpisodeLedgerRow? = writes.lastOrNull { it.episodeKey == episodeKey }

    override fun observeRow(episodeKey: String): Flow<EpisodeLedgerRow?> = MutableStateFlow(null)

    override fun observeUndecidedCounts(): Flow<List<FeedUndecidedCount>> = MutableStateFlow(emptyList())

    override suspend fun upsert(row: EpisodeLedgerRow) {
        writes += row
    }

    override suspend fun getUnsynced(): List<EpisodeLedgerRow> = writes.filterNot { it.syncedToServer }

    override suspend fun markSynced(episodeKeys: List<String>) = Unit

    override suspend fun upsertAll(rows: List<EpisodeLedgerRow>) {
        writes += rows
    }

    override suspend fun previewUndecided(scope: BulkScope): List<FeedUndecidedCount> {
        queriedScopes += scope
        return undecided.groupingBy { it.feedUrl }.eachCount().map { FeedUndecidedCount(it.key, it.value) }
    }

    override suspend fun undecided(scope: BulkScope): List<Episode> {
        queriedScopes += scope
        val cutoff = scope.olderThanMillis.takeIf { scope.kind == BulkScopeKind.OLDER_THAN }
        return undecided.filter { episode ->
            // Mirrors the SQL: an undated episode is never swept up by an age cutoff.
            cutoff == null || (episode.pubDate != null && episode.pubDate!! < cutoff)
        }
    }
}

/** Settings double exposing only what the refresher reads; everything else is out of scope. */
class FakeRefreshSettings(
    var markOldOlderThan: OlderThan = OlderThan.OFF,
) : SettingsRepository {
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

    override fun observeMarkOldOlderThan(): Flow<OlderThan> = MutableStateFlow(markOldOlderThan)

    override suspend fun setMarkOldOlderThan(value: OlderThan) {
        markOldOlderThan = value
    }

    override fun observeNextcloudAccount(): Flow<NextcloudAccount?> = MutableStateFlow(null)

    override suspend fun nextcloudCredentials(): NextcloudCredentials? = null

    override suspend fun setNextcloudCredentials(credentials: NextcloudCredentials?) =
        error("not needed by these tests")
}

/** Log double that keeps what was recorded, so failure paths can be asserted rather than assumed. */
class RecordingLogRepository : LogRepository {
    val recorded = mutableListOf<NewLogEntry>()

    override fun observe(category: LogCategory?): Flow<List<LogEntry>> = MutableStateFlow(emptyList())

    override suspend fun record(entry: NewLogEntry) {
        recorded += entry
    }

    override suspend fun clear() {
        recorded.clear()
    }

    override suspend fun exportPlainText(): String = recorded.joinToString("\n") { it.message }
}
