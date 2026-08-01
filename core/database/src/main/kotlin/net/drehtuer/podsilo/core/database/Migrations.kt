// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The project's first migration. Two purely additive changes, neither of which touches existing
 * data — which is the point: the `episodes` table is a disposable cache that a refresh rebuilds,
 * but `episode_ledger` is "the one table that must never be lost" (CLAUDE.md §5), and a
 * `fallbackToDestructiveMigration` would take it with everything else.
 *
 * - `episodes.link` — the episode's own page, for *Open in browser*. Null for every existing row
 *   until its feed is next refreshed, which is correct: we never had the value, and synthesising
 *   one from the enclosure URL would produce a link to an audio file.
 * - `error_log` — S8's backing table, new in v2 and read by nothing else.
 */
val MIGRATION_1_2: Migration =
    object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE episodes ADD COLUMN link TEXT")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS error_log (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    identity TEXT NOT NULL,
                    at INTEGER NOT NULL,
                    category TEXT NOT NULL,
                    feedUrl TEXT,
                    episodeKey TEXT,
                    message TEXT NOT NULL,
                    detail TEXT,
                    occurrences INTEGER NOT NULL,
                    firstSeenAt INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_error_log_identity ON error_log (identity)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_error_log_at ON error_log (at)")
        }
    }

/** Every migration, in order — what `:app` hands to `Room.databaseBuilder().addMigrations(...)`. */
val PODSILO_MIGRATIONS: List<Migration> = listOf(MIGRATION_1_2)
