// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import net.drehtuer.podsilo.core.database.dao.EpisodeDao
import net.drehtuer.podsilo.core.database.dao.EpisodeLedgerDao
import net.drehtuer.podsilo.core.database.dao.EpisodeListDao
import net.drehtuer.podsilo.core.database.dao.FeedDao
import net.drehtuer.podsilo.core.database.dao.LogDao
import net.drehtuer.podsilo.core.database.dao.SyncStateDao
import net.drehtuer.podsilo.core.database.entity.EpisodeEntity
import net.drehtuer.podsilo.core.database.entity.EpisodeLedgerEntity
import net.drehtuer.podsilo.core.database.entity.FeedEntity
import net.drehtuer.podsilo.core.database.entity.LogEntryEntity
import net.drehtuer.podsilo.core.database.entity.SyncStateEntity

/**
 * The schema of `docs/architecture.md` §4 — deliberately not a typical podcast app's. Foreign-key
 * enforcement is on (Room enables it automatically when a `@ForeignKey` is present), so removing a
 * feed cascades to its episodes; the ledger has no such key and survives.
 *
 * Room's own migration machinery owns schema evolution (CLAUDE.md §3 — no hand-rolled runner); the
 * exported schema under `schemas/` is the versioned baseline future migrations diff against.
 *
 * **v2** added `episodes.link` and the `error_log` table ([MIGRATION_1_2]); **v3** added the failure
 * classification beside `episode_ledger.lastError` ([MIGRATION_2_3]). Migrating rather
 * than falling back destructively is not a nicety here: a destructive fallback would drop
 * `episode_ledger`, and every episode the user had ever handled would come back as new, here and on
 * every other client after the next sync.
 */
@Database(
    entities = [
        FeedEntity::class,
        EpisodeEntity::class,
        EpisodeLedgerEntity::class,
        SyncStateEntity::class,
        LogEntryEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class PodsiloDatabase : RoomDatabase() {
    abstract fun feedDao(): FeedDao

    abstract fun episodeDao(): EpisodeDao

    abstract fun episodeLedgerDao(): EpisodeLedgerDao

    abstract fun episodeListDao(): EpisodeListDao

    abstract fun syncStateDao(): SyncStateDao

    abstract fun logDao(): LogDao

    companion object {
        const val DATABASE_NAME: String = "podsilo.db"
    }
}
