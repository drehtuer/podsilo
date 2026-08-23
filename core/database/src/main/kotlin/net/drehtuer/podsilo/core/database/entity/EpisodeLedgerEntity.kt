// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room row for `EpisodeLedgerRow` — "the one table that must never be lost" (CLAUDE.md §5). No
 * foreign key onto `episodes`: it shares the `episodeKey` value-space but must survive its episode
 * being pruned when a feed is unsubscribed, or a re-subscribe would re-download the back catalogue
 * (`docs/architecture.adoc` §4). [state] is stored as the `LedgerState` enum name; the mapper
 * converts. [syncedToServer] is the outbox flag ([EpisodeLedgerDao.getUnsynced] is `WHERE
 * syncedToServer = 0`).
 */
@Entity(tableName = "episode_ledger")
data class EpisodeLedgerEntity(
    @PrimaryKey val episodeKey: String,
    val feedUrl: String,
    val enclosureUrl: String,
    val state: String,
    val actionedAt: Long,
    val syncedToServer: Boolean,
    val attempts: Int,
    val lastError: String?,
    // Schema v3: the classification, kept beside the message so a screen never has to parse prose.
    val lastErrorCause: String? = null,
    val lastErrorRetryable: Boolean? = null,
    val writtenFileName: String?,
    val durationSeconds: Int?,
)
