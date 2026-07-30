// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.naming

import net.drehtuer.podsilo.core.model.Episode
import net.drehtuer.podsilo.core.model.Feed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class DefaultNamingTemplateEngineTest {
    private val utc = ZoneOffset.UTC

    private fun feed(title: String = "Der Podcast") =
        Feed(
            url = "https://example.com/feed.xml",
            title = title,
            imageUrl = null,
            firstSeenAt = 0L,
            lastRefreshedAt = null,
            httpEtag = null,
            httpLastModified = null,
        )

    private fun episode(
        title: String = "Warum Hamburg immer regnet",
        enclosureUrl: String = "https://example.com/ep1.mp3",
        guid: String? = "guid-123",
        pubDate: Long? = Instant.parse("2026-07-14T09:00:00Z").toEpochMilli(),
        description: String? = null,
    ) = Episode(
        episodeKey = guid ?: enclosureUrl,
        feedUrl = "https://example.com/feed.xml",
        guid = guid,
        enclosureUrl = enclosureUrl,
        title = title,
        description = description,
        pubDate = pubDate,
        durationMs = null,
    )

    private fun engine(
        titleCleanupRules: List<TitleCleanupRule> = emptyList(),
        transliterate: Boolean = false,
    ) = DefaultNamingTemplateEngine(zoneId = utc, titleCleanupRules = titleCleanupRules, transliterate = transliterate)

    @Test
    fun `default templates match the CLAUDE md example`() {
        val result =
            engine().resolve(
                feed = feed(),
                episode = episode(),
                folderTemplate = "{podcast}",
                fileTemplate = "{date}_{title}",
            )

        assertEquals("Der Podcast", result.folder)
        assertEquals("20260714_Warum Hamburg immer regnet", result.fileNameWithoutExtension)
        assertEquals("mp3", result.extension)
    }

    @Test
    fun `explicit date pattern is honoured`() {
        val result =
            engine().resolve(
                feed = feed(),
                episode = episode(),
                folderTemplate = "{podcast}",
                fileTemplate = "{date:yyyy-MM-dd}_{title}",
            )

        assertEquals("2026-07-14_Warum Hamburg immer regnet", result.fileNameWithoutExtension)
    }

    @Test
    fun `missing pubDate degrades to the sortable placeholder, never an empty leading segment`() {
        val result =
            engine().resolve(
                feed = feed(),
                episode = episode(pubDate = null),
                folderTemplate = "{podcast}",
                fileTemplate = "{date}_{title}",
            )

        assertTrue(result.fileNameWithoutExtension.startsWith("00000000_"))
    }

    @Test
    fun `extension is resolved from the enclosure url`() {
        val result =
            engine().resolve(
                feed = feed(),
                episode = episode(enclosureUrl = "https://example.com/ep1.opus?token=abc"),
                folderTemplate = "{podcast}",
                fileTemplate = "{title}",
            )

        assertEquals("opus", result.extension)
    }

    @Test
    fun `a 400-character title is truncated to a valid utf-8 byte budget, room reserved for date and extension`() {
        val hugeTitle = "日".repeat(400) // 3 bytes/char, 1200 bytes raw
        val result =
            engine().resolve(
                feed = feed(),
                episode = episode(title = hugeTitle),
                folderTemplate = "{podcast}",
                fileTemplate = "{date}_{title}",
            )

        val fileNameBytes = result.fileNameWithoutExtension.toByteArray(Charsets.UTF_8)
        val extensionOverhead = result.extension.toByteArray(Charsets.UTF_8).size + 1 // dot
        assertTrue(
            "expected room for extension + collision suffix headroom, was ${fileNameBytes.size} bytes",
            fileNameBytes.size + extensionOverhead + COLLISION_SUFFIX_RESERVED_BYTES <= DEFAULT_MAX_COMPONENT_BYTES,
        )
        // Round-trip proves no UTF-8 sequence was split.
        assertEquals(result.fileNameWithoutExtension, String(fileNameBytes, Charsets.UTF_8))
    }

    @Test
    fun `a podcast title that sanitises to a reserved device name is escaped`() {
        val result =
            engine().resolve(
                feed = feed(title = "con"),
                episode = episode(),
                folderTemplate = "{podcast}",
                fileTemplate = "{title}",
            )

        assertEquals("con_", result.folder)
    }

    @Test
    fun `an episode title that sanitises entirely away falls back to guid_short`() {
        val result =
            engine().resolve(
                feed = feed(),
                episode = episode(title = "...", guid = "guid-fallback-test"),
                folderTemplate = "{podcast}",
                fileTemplate = "{title}",
            )

        assertEquals(guidShort("guid-fallback-test"), result.fileNameWithoutExtension)
    }

    @Test
    fun `title cleanup rules run before sanitising`() {
        val rules = listOf(TitleCleanupRule(Regex("""^Ep\.? ?\d+ *[-–—] *"""), ""))
        val result =
            engine(titleCleanupRules = rules).resolve(
                feed = feed(),
                episode = episode(title = "Ep. 142 - Something Interesting"),
                folderTemplate = "{podcast}",
                fileTemplate = "{title}",
            )

        assertEquals("Something Interesting", result.fileNameWithoutExtension)
    }

    @Test
    fun `transliteration is off by default -- umlauts survive in the resolved name`() {
        val result =
            engine().resolve(
                feed = feed(title = "Über den Wolken"),
                episode = episode(),
                folderTemplate = "{podcast}",
                fileTemplate = "{title}",
            )

        assertEquals("Über den Wolken", result.folder)
    }

    @Test
    fun `transliteration is applied when explicitly enabled`() {
        val result =
            engine(transliterate = true).resolve(
                feed = feed(title = "Über den Wolken"),
                episode = episode(),
                folderTemplate = "{podcast}",
                fileTemplate = "{title}",
            )

        assertEquals("Ueber den Wolken", result.folder)
    }

    @Test
    fun `guid_short variable resolves to the same hash as the standalone helper`() {
        val result =
            engine().resolve(
                feed = feed(),
                episode = episode(guid = "guid-123"),
                folderTemplate = "{guid_short}",
                fileTemplate = "{title}",
            )

        assertEquals(guidShort("guid-123"), result.folder)
    }
}
