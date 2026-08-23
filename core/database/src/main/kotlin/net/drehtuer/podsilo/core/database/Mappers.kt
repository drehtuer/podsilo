// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.database

import net.drehtuer.podsilo.core.database.dao.EpisodeWithLedger
import net.drehtuer.podsilo.core.database.entity.EpisodeEntity
import net.drehtuer.podsilo.core.database.entity.EpisodeLedgerEntity
import net.drehtuer.podsilo.core.database.entity.FeedEntity
import net.drehtuer.podsilo.core.database.entity.SyncStateEntity
import net.drehtuer.podsilo.core.model.Episode
import net.drehtuer.podsilo.core.model.EpisodeLedgerRow
import net.drehtuer.podsilo.core.model.ErrorCause
import net.drehtuer.podsilo.core.model.Feed
import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.core.model.SyncState
import net.drehtuer.podsilo.core.model.port.EpisodeListItem

// entity <-> domain mapping, kept at the module boundary so nothing outside :core:database sees a
// Room entity (architecture.adoc §4). LedgerState is stored as its enum name; an unrecognised
// value in the column would be a schema-migration bug, so let enumValueOf throw rather than mask it.

internal fun FeedEntity.toDomain(): Feed =
    Feed(
        url = url,
        title = title,
        imageUrl = imageUrl,
        firstSeenAt = firstSeenAt,
        lastRefreshedAt = lastRefreshedAt,
        httpEtag = httpEtag,
        httpLastModified = httpLastModified,
    )

internal fun Feed.toEntity(): FeedEntity =
    FeedEntity(
        url = url,
        title = title,
        imageUrl = imageUrl,
        firstSeenAt = firstSeenAt,
        lastRefreshedAt = lastRefreshedAt,
        httpEtag = httpEtag,
        httpLastModified = httpLastModified,
    )

internal fun EpisodeEntity.toDomain(): Episode =
    Episode(
        episodeKey = episodeKey,
        feedUrl = feedUrl,
        guid = guid,
        enclosureUrl = enclosureUrl,
        title = title,
        description = description,
        pubDate = pubDate,
        durationMs = durationMs,
        link = link,
        imageUrl = imageUrl,
        sizeBytes = sizeBytes,
    )

internal fun Episode.toEntity(): EpisodeEntity =
    EpisodeEntity(
        episodeKey = episodeKey,
        feedUrl = feedUrl,
        guid = guid,
        enclosureUrl = enclosureUrl,
        title = title,
        description = description,
        pubDate = pubDate,
        durationMs = durationMs,
        link = link,
        imageUrl = imageUrl,
        sizeBytes = sizeBytes,
    )

internal fun EpisodeLedgerEntity.toDomain(): EpisodeLedgerRow =
    EpisodeLedgerRow(
        episodeKey = episodeKey,
        feedUrl = feedUrl,
        enclosureUrl = enclosureUrl,
        state = LedgerState.valueOf(state),
        actionedAt = actionedAt,
        syncedToServer = syncedToServer,
        attempts = attempts,
        lastError = lastError,
        lastErrorCause = lastErrorCause?.let { runCatching { enumValueOf<ErrorCause>(it) }.getOrNull() },
        lastErrorRetryable = lastErrorRetryable,
        writtenFileName = writtenFileName,
        durationSeconds = durationSeconds,
    )

internal fun EpisodeLedgerRow.toEntity(): EpisodeLedgerEntity =
    EpisodeLedgerEntity(
        episodeKey = episodeKey,
        feedUrl = feedUrl,
        enclosureUrl = enclosureUrl,
        state = state.name,
        actionedAt = actionedAt,
        syncedToServer = syncedToServer,
        attempts = attempts,
        lastError = lastError,
        lastErrorCause = lastErrorCause?.name,
        lastErrorRetryable = lastErrorRetryable,
        writtenFileName = writtenFileName,
        durationSeconds = durationSeconds,
    )

internal fun EpisodeWithLedger.toDomain(): EpisodeListItem =
    EpisodeListItem(
        episode = episode.toDomain(),
        ledger = ledger?.toDomain(),
    )

internal fun SyncStateEntity.toDomain(): SyncState =
    SyncState(
        lastEpisodeActionSyncTs = lastEpisodeActionSyncTs,
        deviceId = deviceId,
    )
