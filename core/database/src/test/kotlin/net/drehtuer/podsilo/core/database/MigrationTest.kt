// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val TEST_DB = "migration-test.db"

/**
 * The project's first migration, exercised against the **exported v1 schema** rather than against
 * the current entity classes — which is the only way this test can fail for the right reason.
 *
 * What it is really guarding is the ledger. `episode_ledger` is "the one table that must never be
 * lost" (CLAUDE.md §5): if a future schema change is ever shipped without a migration, the
 * alternative is a destructive fallback that drops it, and every episode the author had already
 * handled comes back as new — here, and on every other client after the next sync. A migration test
 * is cheap; that failure is not recoverable.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    @get:Rule
    val helper: MigrationTestHelper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            PodsiloDatabase::class.java,
        )

    @Test
    fun `migrate 1 to 2 adds the link column and the error log, and keeps every row`() {
        helper.createDatabase(TEST_DB, 1).use { v1 ->
            v1.execSQL(
                "INSERT INTO feeds (url, title, imageUrl, firstSeenAt, lastRefreshedAt, httpEtag, httpLastModified) " +
                    "VALUES ('https://example.com/feed.xml', 'Der Podcast', NULL, 1000, NULL, NULL, NULL)",
            )
            v1.execSQL(
                "INSERT INTO episodes (episodeKey, feedUrl, guid, enclosureUrl, title, description, pubDate, " +
                    "durationMs) VALUES ('ep-1', 'https://example.com/feed.xml', 'ep-1', " +
                    "'https://example.com/ep1.mp3', 'Warum Hamburg immer regnet', NULL, 1500, NULL)",
            )
            v1.execSQL(
                "INSERT INTO episode_ledger (episodeKey, feedUrl, enclosureUrl, state, actionedAt, syncedToServer, " +
                    "attempts, lastError, writtenFileName, durationSeconds) VALUES " +
                    "('ep-1', 'https://example.com/feed.xml', 'https://example.com/ep1.mp3', 'DOWNLOADED', 2000, 1, " +
                    "0, NULL, '20260714_Warum-Hamburg-immer-regnet.mp3', NULL)",
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2)

        db.query("SELECT link FROM episodes WHERE episodeKey = 'ep-1'").use { cursor ->
            assertTrue("the migrated episode row survived", cursor.moveToFirst())
            // We never had a link for rows written before v2, and the enclosure URL is not a
            // substitute — it points at audio, not at a page. Null is the honest value.
            assertNull(cursor.getString(0))
        }

        db.query("SELECT writtenFileName, state FROM episode_ledger WHERE episodeKey = 'ep-1'").use { cursor ->
            assertTrue("the ledger row survived the migration", cursor.moveToFirst())
            assertEquals("20260714_Warum-Hamburg-immer-regnet.mp3", cursor.getString(0))
            assertEquals("DOWNLOADED", cursor.getString(1))
        }

        db.query("SELECT COUNT(*) FROM error_log").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun `migrate 2 to 3 classifies future failures without touching recorded ones`() {
        helper.createDatabase(TEST_DB, 2).use { v2 ->
            v2.execSQL(
                "INSERT INTO episode_ledger (episodeKey, feedUrl, enclosureUrl, state, actionedAt, syncedToServer, " +
                    "attempts, lastError, writtenFileName, durationSeconds) VALUES " +
                    "('ep-1', 'https://example.com/feed.xml', 'https://example.com/ep1.mp3', 'ERROR', 2000, 0, " +
                    "3, 'No space left on device', NULL, NULL)",
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3)

        db.query("SELECT lastError, lastErrorCause, lastErrorRetryable FROM episode_ledger").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("the message a user already saw is untouched", "No space left on device", cursor.getString(0))
            // Deliberately null rather than guessed from the sentence: the UI reads that as UNKNOWN
            // and offers a plain Retry, which is the safe direction for a row we cannot classify.
            assertNull(cursor.getString(1))
            assertTrue(cursor.isNull(2))
        }
    }

    @Test
    fun `migrate 3 to 4 adds episode artwork and keeps the ledger and the episode cache intact`() {
        helper.createDatabase(TEST_DB, 3).use { v3 ->
            v3.execSQL(
                "INSERT INTO feeds (url, title, imageUrl, firstSeenAt, lastRefreshedAt, httpEtag, httpLastModified) " +
                    "VALUES ('https://example.com/feed.xml', 'Der Podcast', NULL, 1000, NULL, NULL, NULL)",
            )
            v3.execSQL(
                "INSERT INTO episodes (episodeKey, feedUrl, guid, enclosureUrl, title, description, pubDate, " +
                    "durationMs, link) VALUES ('ep-1', 'https://example.com/feed.xml', 'ep-1', " +
                    "'https://example.com/ep1.mp3', 'Folge 1', NULL, 2000, NULL, NULL)",
            )
            v3.execSQL(
                "INSERT INTO episode_ledger (episodeKey, feedUrl, enclosureUrl, state, actionedAt, syncedToServer, " +
                    "attempts, lastError, lastErrorCause, lastErrorRetryable, writtenFileName, durationSeconds) " +
                    "VALUES ('ep-1', 'https://example.com/feed.xml', 'https://example.com/ep1.mp3', 'DOWNLOADED', " +
                    "3000, 1, 0, NULL, NULL, NULL, '20260714_Folge-1.mp3', 1800)",
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4)

        db.query("SELECT title, imageUrl FROM episodes").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("the cached episode survived", "Folge 1", cursor.getString(0))
            // Null and unbackfilled on purpose: `episodes` is a disposable cache and the next feed
            // refresh fills this in. Backfilling would mean network I/O inside a migration.
            assertTrue("artwork starts unknown, not guessed", cursor.isNull(1))
        }
        db.query("SELECT state, writtenFileName FROM episode_ledger").use { cursor ->
            assertTrue(cursor.moveToFirst())
            // The table that must never be lost (CLAUDE.md §5) — an artwork column has no business
            // disturbing a record of what the author already handled.
            assertEquals("DOWNLOADED", cursor.getString(0))
            assertEquals("20260714_Folge-1.mp3", cursor.getString(1))
        }
    }

    @Test
    fun `migrate 4 to 5 adds the advertised size and keeps the ledger intact`() {
        helper.createDatabase(TEST_DB, 4).use { v4 ->
            v4.execSQL(
                "INSERT INTO feeds (url, title, imageUrl, firstSeenAt, lastRefreshedAt, httpEtag, httpLastModified) " +
                    "VALUES ('https://example.com/feed.xml', 'Der Podcast', NULL, 1000, NULL, " +
                    "'etag-1', 'Mon, 14 Jul 2026 09:00:00 GMT')",
            )
            v4.execSQL(
                "INSERT INTO episodes (episodeKey, feedUrl, guid, enclosureUrl, title, description, pubDate, " +
                    "durationMs, link, imageUrl) VALUES ('ep-1', 'https://example.com/feed.xml', 'ep-1', " +
                    "'https://example.com/ep1.mp3', 'Folge 1', NULL, 2000, NULL, NULL, NULL)",
            )
            v4.execSQL(
                "INSERT INTO episode_ledger (episodeKey, feedUrl, enclosureUrl, state, actionedAt, syncedToServer, " +
                    "attempts, lastError, lastErrorCause, lastErrorRetryable, writtenFileName, durationSeconds) " +
                    "VALUES ('ep-1', 'https://example.com/feed.xml', 'https://example.com/ep1.mp3', 'DOWNLOADED', " +
                    "3000, 1, 0, NULL, NULL, NULL, '20260714_Folge-1.mp3', 1800)",
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 5, true, MIGRATION_4_5)

        db.query("SELECT title, sizeBytes FROM episodes").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("the cached episode survived", "Folge 1", cursor.getString(0))
            // Unbackfilled, like imageUrl in v4: the next refresh supplies it, and a migration is the
            // wrong place to do network I/O.
            assertTrue("size starts unknown rather than zero", cursor.isNull(1))
        }
        // Without this the new column stays null until a publisher happens to change the feed: a 304
        // skips the parse, so an unchanged feed never refills the cache (docs/architecture.adoc §7).
        db.query("SELECT httpEtag, httpLastModified FROM feeds").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue("the ETag must be dropped so one full re-fetch happens", cursor.isNull(0))
            assertTrue("Last-Modified too", cursor.isNull(1))
        }
        db.query("SELECT writtenFileName FROM episode_ledger").use { cursor ->
            assertTrue(cursor.moveToFirst())
            // CLAUDE.md §5's one table that must never be lost. A size column on a different table
            // has no business touching it.
            assertEquals("20260714_Folge-1.mp3", cursor.getString(0))
        }
    }
}
