// SPDX-License-Identifier: GPL-3.0-or-later

// Every numeric literal in this file is a schema version, and each is already named by the property
// it belongs to — MIGRATION_1_2 goes 1 -> 2.
@file:Suppress("MagicNumber")

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

/**
 * Records *why* a download failed, next to the message saying what happened.
 *
 * Additive and nullable, so every existing row keeps its `lastError` and simply has no
 * classification — which the UI reads as `UNKNOWN` and therefore offers a plain **Retry** for. That
 * is the right default for historical rows: the alternative, guessing a cause from the stored
 * sentence, would be wrong silently and in the unsafe direction.
 */
val MIGRATION_2_3: Migration =
    object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE episode_ledger ADD COLUMN lastErrorCause TEXT")
            db.execSQL("ALTER TABLE episode_ledger ADD COLUMN lastErrorRetryable INTEGER")
        }
    }

/**
 * Adds the per-episode artwork URL.
 *
 * Nullable and unbackfilled on purpose: `episodes` is "a disposable cache of parsed RSS, safe to
 * wipe and rebuild" (CLAUDE.md §5), so the next feed refresh fills it in. Backfilling would mean
 * re-fetching every feed inside a migration, which is the wrong place to do network I/O.
 */
val MIGRATION_3_4: Migration =
    object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE episodes ADD COLUMN imageUrl TEXT")
        }
    }

/**
 * v5 — `episodes.sizeBytes`, the enclosure length a feed advertises.
 *
 * Additive and nullable, and deliberately not backfilled: `episodes` is a disposable cache of parsed
 * RSS (`docs/architecture.adoc` §4), so the next refresh fills it in. Same shape as [MIGRATION_3_4].
 */
val MIGRATION_4_5: Migration =
    object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE episodes ADD COLUMN sizeBytes INTEGER")
            // AND CLEAR THE CONDITIONAL-GET VALIDATORS, or the new column stays empty for weeks.
            //
            // `FeedFetcher` sends If-None-Match/If-Modified-Since and a 304 skips the parse entirely
            // (`docs/architecture.adoc` §7), so an unchanged feed never re-parses and never fills a
            // newly added column. Observed on the author's phone: v5 applied, every `sizeBytes` null,
            // and a refresh that dutifully did nothing because all four feeds answered 304.
            //
            // Dropping the validators costs exactly one full fetch per feed, once. Any migration that
            // adds a column to `episodes` needs this line — the alternative is a column that fills in
            // whenever the publisher next happens to post, which is not a schedule we control.
            db.execSQL("UPDATE feeds SET httpEtag = NULL, httpLastModified = NULL")
        }
    }

/** Every migration, in order — what `:app` hands to `Room.databaseBuilder().addMigrations(...)`. */
val PODSILO_MIGRATIONS: List<Migration> = listOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
