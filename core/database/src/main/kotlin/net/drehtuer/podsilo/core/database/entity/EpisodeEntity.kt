// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room row for `Episode` — the disposable parsed-RSS cache (`docs/architecture.md` §4). The
 * cascading foreign key onto `feeds` is what implements "a feed disappearing from the server
 * deletes its episodes"; the ledger is deliberately a separate table with no such key, so its rows
 * outlive these (CLAUDE.md §5).
 */
@Entity(
    tableName = "episodes",
    foreignKeys = [
        ForeignKey(
            entity = FeedEntity::class,
            parentColumns = ["url"],
            childColumns = ["feedUrl"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("feedUrl")],
)
data class EpisodeEntity(
    @PrimaryKey val episodeKey: String,
    val feedUrl: String,
    val guid: String?,
    val enclosureUrl: String,
    val title: String,
    val description: String?,
    val pubDate: Long?,
    val durationMs: Long?,
    // Schema v2. The episode's own page, for "Open in browser" — never the enclosure, which is audio.
    val link: String? = null,
)
