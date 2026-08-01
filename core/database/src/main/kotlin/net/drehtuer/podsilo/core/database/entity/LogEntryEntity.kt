// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room row for the error log (S8, `docs/UI.md` §11). Added in **schema v2**.
 *
 * Purely additive: no existing table or type changes, and nothing else in the app reads it. It
 * exists because every failure in Podsilo is currently returned as a value and then discarded once
 * handled, which leaves a self-hosted single-user setup with nothing to debug from but `adb`.
 *
 * [identity] is the collapse key — category + affected feed/episode + a normalised message — and is
 * uniquely indexed so `record()` can upsert onto an existing row instead of appending. Without that,
 * one feed timing out hourly evicts every genuinely one-off error inside a day.
 *
 * @property at Epoch millis of the **most recent** occurrence; [firstSeenAt] of the first.
 */
@Entity(
    tableName = "error_log",
    indices = [
        Index(value = ["identity"], unique = true),
        Index("at"),
    ],
)
data class LogEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val identity: String,
    val at: Long,
    val category: String,
    val feedUrl: String?,
    val episodeKey: String?,
    val message: String,
    val detail: String?,
    val occurrences: Int,
    val firstSeenAt: Long,
)
