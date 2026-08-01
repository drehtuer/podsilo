// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.model.port

import kotlinx.coroutines.flow.Flow

/**
 * Port for the error log (S8, `docs/UI.md` §11), implemented in `:core:database` over an
 * `error_log` table.
 *
 * This exists because everything that fails in Podsilo is currently returned as a value
 * (`FeedFetchResult`, `Result`, `SyncOutcome`, `EpisodeLedgerRow.lastError`) and then discarded
 * once handled — nothing keeps a chronological, categorised history. For a single-user self-hosted
 * setup that is the difference between debugging a failed sync on the phone and needing a laptop
 * and `adb`.
 *
 * A **failure** log, not a journal: successes are never recorded (S7's *recently downloaded* group
 * covers those). Nothing here leaves the device — no telemetry, per README.
 */
interface LogRepository {
    /** Newest first, already collapsed by the DAO. `null` [category] means all of them. */
    fun observe(category: LogCategory?): Flow<List<LogEntry>>

    /**
     * Records a failure, **collapsing onto an existing entry with the same identity** — category +
     * affected feed/episode + normalised message — by bumping [LogEntry.occurrences] and
     * [LogEntry.at] and replacing [LogEntry.detail] with this occurrence's.
     *
     * The collapsing is not cosmetic and belongs here rather than in the UI: one feed timing out
     * every few hours would otherwise evict every genuinely one-off error from the buffer within a
     * day, and the list would still look fine.
     *
     * **Never record a credential** — not the app password, not the `Authorization` header, not a
     * URL containing either. This is asserted by a test, because it is the kind of thing that
     * arrives by accident inside an exception message.
     */
    suspend fun record(entry: NewLogEntry)

    /**
     * Clears the whole ring buffer, never just the currently filtered category — a filtered clear
     * would leave a count the user cannot account for. Touches nothing else: no ledger row, no
     * worker, no sync state. Recording resumes immediately.
     */
    suspend fun clear()

    /** The whole log as plain text, for S8's *copy all* / *share*. */
    suspend fun exportPlainText(): String
}

/**
 * One collapsed failure. [message] is a plain-language sentence and is what the user reads;
 * [detail] is the technical half (HTTP status, exception class, URL, worker name, attempts), shown
 * only on demand and pasted into a bug report.
 *
 * @property at Epoch millis of the **most recent** occurrence; [firstSeenAt] of the first.
 * @property occurrences Collapse counter — 1 for a one-off. See [LogRepository.record].
 */
data class LogEntry(
    val id: Long,
    val at: Long,
    val category: LogCategory,
    val feedUrl: String?,
    val episodeKey: String?,
    val message: String,
    val detail: String?,
    val occurrences: Int,
    val firstSeenAt: Long,
)

/** A failure to record. The id, timestamps and collapse counter are the store's to assign. */
data class NewLogEntry(
    val category: LogCategory,
    val feedUrl: String? = null,
    val episodeKey: String? = null,
    val message: String,
    val detail: String? = null,
)

/** S8's filter chips, and half of an entry's collapse identity. */
enum class LogCategory { SYNC, FEED, DOWNLOAD, STORAGE, AUTH }
