// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.database

import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.drehtuer.podsilo.core.database.dao.LogDao
import net.drehtuer.podsilo.core.database.repository.LogRepositoryImpl
import net.drehtuer.podsilo.core.model.port.LogCategory
import net.drehtuer.podsilo.core.model.port.NewLogEntry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** S8's backing store: the collapse rule, eviction, and the promise that no credential is ever written. */
@RunWith(RobolectricTestRunner::class)
class LogRepositoryTest {
    private lateinit var db: PodsiloDatabase
    private var now = Instant.parse("2026-08-01T12:00:00Z")

    private val log by lazy {
        LogRepositoryImpl(
            logDao = db.logDao(),
            clock =
                object : Clock() {
                    override fun getZone(): ZoneId = ZoneOffset.UTC

                    override fun withZone(zone: ZoneId?): Clock = this

                    override fun instant(): Instant = now
                },
            zone = ZoneOffset.UTC,
        )
    }

    @Before
    fun setUp() {
        db =
            Room
                .inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PodsiloDatabase::class.java)
                .build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `an identical failure collapses instead of appending`() =
        runTest {
            repeat(3) { log.record(feedTimeout()) }

            val entries = log.observe(null).first()

            assertEquals(1, entries.size)
            assertEquals(3, entries.single().occurrences)
        }

    @Test
    fun `collapsing keeps the first timestamp and advances the last`() =
        runTest {
            log.record(feedTimeout())
            now = Instant.parse("2026-08-01T18:00:00Z")
            log.record(feedTimeout())

            val entry = log.observe(null).first().single()

            assertEquals(Instant.parse("2026-08-01T12:00:00Z").toEpochMilli(), entry.firstSeenAt)
            assertEquals(Instant.parse("2026-08-01T18:00:00Z").toEpochMilli(), entry.at)
        }

    @Test
    fun `a message differing only in numbers still collapses`() =
        runTest {
            // The whole point of normalising: the same failure never carries the same text twice —
            // a fresh timeout, a fresh port, a rotating CDN host. An identity over the raw message
            // would collapse nothing and the buffer would evict every one-off error within a day.
            log.record(feedTimeout("Feed server did not respond (timeout after 30012 ms)"))
            log.record(feedTimeout("Feed server did not respond (timeout after 29998 ms)"))

            assertEquals(1, log.observe(null).first().size)
        }

    @Test
    fun `different feeds do not collapse together`() =
        runTest {
            log.record(feedTimeout(feedUrl = "https://a.example/feed.xml"))
            log.record(feedTimeout(feedUrl = "https://b.example/feed.xml"))

            assertEquals(2, log.observe(null).first().size)
        }

    @Test
    fun `the newest occurrence's detail replaces the older one`() =
        runTest {
            log.record(feedTimeout(detail = "first attempt"))
            log.record(feedTimeout(detail = "second attempt"))

            assertEquals(
                "second attempt",
                log
                    .observe(null)
                    .first()
                    .single()
                    .detail,
            )
        }

    @Test
    fun `the filter selects one category`() =
        runTest {
            log.record(feedTimeout())
            log.record(NewLogEntry(category = LogCategory.SYNC, message = "Nextcloud returned 401"))

            assertEquals(2, log.observe(null).first().size)
            assertEquals(1, log.observe(LogCategory.SYNC).first().size)
            assertEquals(
                LogCategory.SYNC,
                log
                    .observe(LogCategory.SYNC)
                    .first()
                    .single()
                    .category,
            )
        }

    @Test
    fun `entries come back newest first`() =
        runTest {
            log.record(NewLogEntry(category = LogCategory.FEED, message = "older"))
            now = now.plusSeconds(60)
            log.record(NewLogEntry(category = LogCategory.SYNC, message = "newer"))

            assertEquals(listOf("newer", "older"), log.observe(null).first().map { it.message })
        }

    @Test
    fun `normalising collapses messages that differ only in numbers, even unrelated ones`() =
        runTest {
            // A deliberate over-reach, recorded so it is not discovered as a surprise: the identity
            // folds *all* digits, so "attempt 1 of 3" and "attempt 2 of 3" are one entry — which is
            // what we want — but so are two genuinely different failures whose text happens to
            // differ only numerically. Anything that must stay separate gets a distinct feed or
            // episode key, which the identity keeps verbatim.
            log.record(NewLogEntry(category = LogCategory.FEED, message = "failure 1"))
            log.record(NewLogEntry(category = LogCategory.FEED, message = "failure 2"))

            assertEquals(1, log.observe(null).first().size)
        }

    @Test
    fun `the buffer evicts the oldest beyond its cap`() =
        runTest {
            // Distinct feeds, not distinct numbers — see the test above for why numbering them would
            // have produced exactly one collapsed row and proved nothing about eviction.
            repeat(LogDao.MAX_ENTRIES + 20) { index ->
                now = now.plusSeconds(1)
                log.record(feedTimeout(feedUrl = "https://feed-$index.example/rss"))
            }

            val entries = log.observe(null).first()

            assertEquals(LogDao.MAX_ENTRIES, entries.size)
            assertTrue(entries.none { it.feedUrl == "https://feed-0.example/rss" })
            assertTrue(entries.any { it.feedUrl == "https://feed-${LogDao.MAX_ENTRIES + 19}.example/rss" })
        }

    @Test
    fun `clearing empties the whole buffer, not the current filter`() =
        runTest {
            log.record(feedTimeout())
            log.record(NewLogEntry(category = LogCategory.SYNC, message = "Nextcloud returned 401"))

            log.clear()

            assertEquals(emptyList<String>(), log.observe(null).first().map { it.message })
        }

    @Test
    fun `the plain-text export carries the message, the count and the detail`() =
        runTest {
            log.record(feedTimeout(detail = "SocketTimeoutException"))
            log.record(feedTimeout(detail = "SocketTimeoutException"))

            val text = log.exportPlainText()

            assertTrue(text.contains("Feed server did not respond"))
            assertTrue(text.contains("×2"))
            assertTrue(text.contains("SocketTimeoutException"))
            assertTrue(text.contains("2026-08-01"))
        }

    @Test
    fun `an empty log still exports something readable`() =
        runTest {
            assertTrue(log.exportPlainText().contains("empty"))
        }

    @Test
    fun `nothing a caller passes is transformed, so credentials can only arrive by being passed`() =
        runTest {
            // This store cannot scrub what it is handed — it has no way to tell a password from a
            // podcast title. The invariant is enforced at the *write points* (asserted in :app), and
            // this test pins the half that lives here: the store never invents or copies a field, so
            // a clean caller stays clean.
            log.record(
                NewLogEntry(
                    category = LogCategory.AUTH,
                    message = "Authorization was refused. Try again.",
                    detail = "HTTP 401",
                ),
            )

            val text = log.exportPlainText()

            assertFalse(text.contains("password", ignoreCase = true))
            assertFalse(text.contains("Basic ", ignoreCase = true))
        }

    private fun feedTimeout(
        message: String = "Feed server did not respond (timeout after 30000 ms)",
        feedUrl: String? = "https://example.com/feed.xml",
        detail: String? = null,
    ) = NewLogEntry(category = LogCategory.FEED, feedUrl = feedUrl, message = message, detail = detail)
}
