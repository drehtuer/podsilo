// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import net.drehtuer.podsilo.core.database.dao.EpisodeDao
import net.drehtuer.podsilo.core.database.dao.EpisodeLedgerDao
import net.drehtuer.podsilo.core.database.dao.FeedDao
import net.drehtuer.podsilo.core.database.dao.SyncStateDao
import net.drehtuer.podsilo.core.database.entity.EpisodeEntity
import net.drehtuer.podsilo.core.database.entity.EpisodeLedgerEntity
import net.drehtuer.podsilo.core.database.entity.FeedEntity
import net.drehtuer.podsilo.core.database.entity.SyncStateEntity

/**
 * The four-table schema of `docs/architecture.md` §4 — deliberately not a typical podcast app's.
 * Foreign-key enforcement is on (Room enables it automatically when a `@ForeignKey` is present),
 * so removing a feed cascades to its episodes; the ledger has no such key and survives.
 *
 * Room's own migration machinery owns schema evolution (CLAUDE.md §3 — no hand-rolled runner); the
 * exported schema under `schemas/` is the versioned baseline future migrations diff against.
 */
@Database(
    entities = [
        FeedEntity::class,
        EpisodeEntity::class,
        EpisodeLedgerEntity::class,
        SyncStateEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class PodsiloDatabase : RoomDatabase() {
    abstract fun feedDao(): FeedDao

    abstract fun episodeDao(): EpisodeDao

    abstract fun episodeLedgerDao(): EpisodeLedgerDao

    abstract fun syncStateDao(): SyncStateDao

    companion object {
        const val DATABASE_NAME: String = "podsilo.db"
    }
}
