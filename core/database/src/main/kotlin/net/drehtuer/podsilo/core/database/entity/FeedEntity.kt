// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room row for `Feed` — a read-only mirror of one server subscription (`architecture.adoc` §4).
 * `EpisodeEntity` has a cascading foreign key onto this table, so removing a feed prunes its
 * cached episodes; the ledger has **no** such key and survives (subscription-mirroring rule, §5).
 */
@Entity(tableName = "feeds")
data class FeedEntity(
    @PrimaryKey val url: String,
    val title: String,
    val imageUrl: String?,
    val firstSeenAt: Long,
    val lastRefreshedAt: Long?,
    val httpEtag: String?,
    val httpLastModified: String?,
)
