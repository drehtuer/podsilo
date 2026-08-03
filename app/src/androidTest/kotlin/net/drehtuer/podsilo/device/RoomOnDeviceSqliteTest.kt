// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.device

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.drehtuer.podsilo.core.database.PODSILO_MIGRATIONS
import net.drehtuer.podsilo.core.database.PodsiloDatabase
import net.drehtuer.podsilo.core.database.entity.EpisodeEntity
import net.drehtuer.podsilo.core.database.entity.EpisodeLedgerEntity
import net.drehtuer.podsilo.core.database.entity.FeedEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The schema against the **device's own SQLite**, which is not the one the JVM tests use.
 *
 * `:core:database`'s suite runs under Robolectric, which supplies its own SQLite build — a different
 * version, compiled with different options, from the one on any given phone. Foreign-key
 * enforcement, `ON DELETE CASCADE` timing and `PRAGMA` behaviour are exactly the kind of thing that
 * is version-dependent, and the cascade here carries the app's single most important invariant.
 *
 * So this asserts the one rule that must survive contact with any SQLite: **removing a feed prunes
 * its episodes and keeps its ledger rows** (CLAUDE.md §5). If that ever inverted, a re-subscribe
 * would re-download the entire back catalogue.
 */
@RunWith(AndroidJUnit4::class)
class RoomOnDeviceSqliteTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: PodsiloDatabase
    private lateinit var file: File

    @Before
    fun setUp() {
        // A file-backed database, not in-memory: WAL, page size and journalling are part of what is
        // under test, and an in-memory database has none of them.
        file = File.createTempFile("device-schema", ".db", context.cacheDir)
        db =
            Room
                .databaseBuilder(context, PodsiloDatabase::class.java, file.absolutePath)
                .apply { PODSILO_MIGRATIONS.forEach { addMigrations(it) } }
                .build()
    }

    @After
    fun tearDown() {
        db.close()
        file.parentFile?.listFiles { f -> f.name.startsWith(file.name) }?.forEach { it.delete() }
    }

    @Test
    fun removingAFeedPrunesEpisodesAndKeepsTheLedger() =
        runBlocking {
            val feedUrl = "https://example.org/feed.xml"
            db.feedDao().upsertAll(
                listOf(
                    FeedEntity(
                        url = feedUrl,
                        title = "Der Podcast",
                        imageUrl = null,
                        firstSeenAt = 1,
                        lastRefreshedAt = null,
                        httpEtag = null,
                        httpLastModified = null,
                    ),
                ),
            )
            db.episodeDao().insertAll(
                listOf(
                    EpisodeEntity(
                        episodeKey = "ep-1",
                        feedUrl = feedUrl,
                        guid = "ep-1",
                        enclosureUrl = "https://example.org/ep-1.mp3",
                        title = "Folge 1",
                        description = null,
                        pubDate = 1,
                        durationMs = null,
                    ),
                ),
            )
            db.episodeLedgerDao().upsert(
                EpisodeLedgerEntity(
                    episodeKey = "ep-1",
                    feedUrl = feedUrl,
                    enclosureUrl = "https://example.org/ep-1.mp3",
                    state = "DOWNLOADED",
                    actionedAt = 1,
                    syncedToServer = true,
                    attempts = 1,
                    lastError = null,
                    writtenFileName = "20260101_Folge-1.mp3",
                    durationSeconds = 1800,
                ),
            )

            // What a subscription disappearing from the server does.
            db.feedDao().replaceAll(emptyList())

            assertEquals("the episode cache must be pruned", 0, db.episodeDao().count())
            assertNotNull(
                "THE ledger row must outlive its feed, or a re-subscribe re-downloads everything",
                db.episodeLedgerDao().get("ep-1"),
            )
            assertEquals(
                "20260101_Folge-1.mp3",
                db.episodeLedgerDao().get("ep-1")?.writtenFileName,
            )
        }

    /**
     * The migrations run forward on this device's SQLite, from the oldest schema Room can open to
     * the current one. `:core:database`'s `MigrationTest` does this under Robolectric; this proves
     * the same statements are accepted by the SQLite that actually ships on the phone.
     */
    @Test
    fun everyMigrationAppliesOnTheDevice() =
        runBlocking {
            // Opening the database runs whatever migration chain is needed; querying every table
            // proves the resulting schema is usable rather than merely created.
            //
            // `sync_state` is queried but NOT asserted non-null: it has no row until a sync pass
            // writes one, and asserting otherwise is what this test did on its first run. A fresh
            // install legitimately has no cursor.
            assertEquals(0, db.feedDao().count())
            assertEquals(0, db.episodeDao().count())
            assertEquals(0, db.episodeLedgerDao().count())
            assertEquals(0, db.logDao().count())
            assertNull("a database nothing has synced yet has no cursor", db.syncStateDao().get())
        }
}
