// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import net.drehtuer.podsilo.core.model.Episode
import net.drehtuer.podsilo.core.model.Feed
import net.drehtuer.podsilo.core.naming.DefaultNamingTemplateEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneOffset

/**
 * `:core:naming` on a **real Android runtime**, which is the only place its regexes are compiled by
 * ICU rather than by `java.util.regex`.
 *
 * This exists because of a bug that every one of the project's JVM tests was structurally unable to
 * see: `TOKEN_PATTERN` ended in a bare `}`, which the JVM accepts and ICU rejects. The module is
 * pure JVM by design (CLAUDE.md §5), so its whole suite ran green while the app produced no filename
 * at all on the device. The assertions below are not the interesting part — **compiling every
 * pattern in the module under ICU is** — so keep exercising the paths that touch each one.
 */
@RunWith(AndroidJUnit4::class)
class NamingOnAndroidTest {
    private val engine = DefaultNamingTemplateEngine(zoneId = ZoneOffset.UTC)

    private val feed =
        Feed(
            url = "https://example.org/feed.xml",
            title = "Der Podcast",
            imageUrl = null,
            firstSeenAt = 0,
            lastRefreshedAt = null,
            httpEtag = null,
            httpLastModified = null,
        )

    private fun episode(title: String) =
        Episode(
            episodeKey = "e1",
            feedUrl = feed.url,
            guid = "e1",
            enclosureUrl = "https://example.org/e1.mp3",
            title = title,
            description = null,
            pubDate = 1_784_019_600_000,
            durationMs = 2_880_000,
            link = null,
        )

    @Test
    fun theDefaultTemplatesResolveOnAndroid() {
        // TOKEN_PATTERN: the pattern that was broken.
        val resolved =
            engine.resolve(
                feed = feed,
                episode = episode("Warum Hamburg immer regnet"),
                folderTemplate = "{podcast}",
                fileTemplate = "{date}_{title}",
            )

        assertEquals("Der Podcast", resolved.folder)
        assertEquals("20260714_Warum Hamburg immer regnet", resolved.fileNameWithoutExtension)
        assertEquals("mp3", resolved.extension)
    }

    @Test
    fun anExplicitDatePatternResolvesOnAndroid() {
        // The `{date:pattern}` branch — the optional group in the same regex.
        val resolved =
            engine.resolve(
                feed = feed,
                episode = episode("Folge"),
                folderTemplate = "{podcast}",
                fileTemplate = "{date:yyyy-MM-dd}_{title}",
            )

        assertEquals("2026-07-14_Folge", resolved.fileNameWithoutExtension)
    }

    @Test
    fun sanitisationRegexesCompileUnderIcuToo() {
        // ILLEGAL_CHARACTERS_RUN (with its control-character range), WHITESPACE_RUN and
        // TRAILING_DOTS_AND_SPACES — the module's other three patterns.
        val resolved =
            engine.resolve(
                feed = feed,
                episode = episode("Ep 3/4: \"Regen\"   uber Hamburg.  "),
                folderTemplate = "{podcast}",
                fileTemplate = "{title}",
            )

        val name = resolved.fileNameWithoutExtension
        assertTrue("no illegal character may survive", name.none { it in "<>:\"/\\|?*" })
        assertFalse("no trailing dot or space", name.endsWith(".") || name.endsWith(" "))
    }

    @Test
    fun umlautsSurviveOnAndroid() {
        // CLAUDE.md §6: the author's own language must not be ASCII-stripped into mush.
        val resolved =
            engine.resolve(
                feed = feed,
                episode = episode("Wärme über Hamburg"),
                folderTemplate = "{podcast}",
                fileTemplate = "{title}",
            )

        assertTrue(resolved.fileNameWithoutExtension.contains("über"))
    }
}
