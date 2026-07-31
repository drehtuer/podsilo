// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.database

import androidx.room.Room
import net.drehtuer.podsilo.core.model.Episode
import net.drehtuer.podsilo.core.model.EpisodeLedgerRow
import net.drehtuer.podsilo.core.model.Feed
import net.drehtuer.podsilo.core.model.LedgerState
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Shared in-memory Room setup for the `:core:database` tests. Robolectric supplies the SQLite
 * runtime and an Android `Context`; no emulator (CLAUDE.md §4, Tier 1/2). Foreign-key enforcement
 * is on (Room enables it whenever a `@ForeignKey` exists), so the cascade behaviour these tests
 * assert is the real thing.
 */
@RunWith(RobolectricTestRunner::class)
abstract class RoomTestBase {
    protected lateinit var db: PodsiloDatabase

    @Before
    fun setUpDb() {
        db =
            Room
                .inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PodsiloDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDownDb() {
        db.close()
    }

    protected fun feed(
        url: String,
        title: String = url,
        firstSeenAt: Long = 0,
    ): Feed =
        Feed(
            url = url,
            title = title,
            imageUrl = null,
            firstSeenAt = firstSeenAt,
            lastRefreshedAt = null,
            httpEtag = null,
            httpLastModified = null,
        )

    // guid is null and enclosureUrl == key throughout these tests (episodeKey = guid ?: enclosureUrl,
    // so key doubles as the enclosure URL); kept off the parameter list to stay readable.
    protected fun episode(
        key: String,
        feedUrl: String,
        title: String = key,
        pubDate: Long? = null,
    ): Episode =
        Episode(
            episodeKey = key,
            feedUrl = feedUrl,
            guid = null,
            enclosureUrl = key,
            title = title,
            description = null,
            pubDate = pubDate,
            durationMs = null,
        )

    protected fun ledgerRow(
        key: String,
        feedUrl: String,
        state: LedgerState,
        syncedToServer: Boolean = false,
    ): EpisodeLedgerRow =
        EpisodeLedgerRow(
            episodeKey = key,
            feedUrl = feedUrl,
            enclosureUrl = key,
            state = state,
            actionedAt = 0,
            syncedToServer = syncedToServer,
            attempts = 0,
            lastError = null,
            writtenFileName = null,
            durationSeconds = null,
        )
}
