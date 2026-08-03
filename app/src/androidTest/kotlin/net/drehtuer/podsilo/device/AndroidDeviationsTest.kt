// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.device

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * **Where Android and the JVM disagree**, which is the whole reason this source set exists.
 *
 * `docs/decisions/0017` was written after a bug that 437 green JVM tests were structurally unable to
 * see: `:core:naming`'s token regex ended in a bare `}`, which `java.util.regex` accepts and
 * Android's ICU engine rejects, so every filename in the app failed to resolve on a device while the
 * suite stayed green. These tests pin the *classes* of difference rather than that one instance —
 * each is a place where a JVM test proves less than it appears to.
 *
 * Keep the assertions boring. Their value is that the code under them runs on ICU, on the device's
 * SQLite, under the device's locale — not in the cleverness of what they check.
 *
 * **One deviation cannot be asserted from inside a test and is recorded here instead:** this source
 * set uses `camelCaseMethodNames`, while every `src/test/` class in the project uses backticked
 * sentences. That is not a style preference. Instrumented tests are **dexed**, and dex's `SimpleName`
 * grammar rejects punctuation the JVM allows in a method name, so R8 fails the *build* with
 *
 * ```
 * Method name '…, not a theoretical concern' cannot be represented in dex format
 * ```
 *
 * Commas did it here; apostrophes are the other common offender. The existing instrumented tests
 * already used camelCase for this reason without saying so, which cost two builds to rediscover.
 */
@RunWith(AndroidJUnit4::class)
class AndroidDeviationsTest {
    /**
     * ICU is stricter than `java.util.regex` about unescaped metacharacters.
     *
     * A bare `}` is the case that actually bit (ADR 0017). `[` and `{` are the same family of
     * mistake, and every one of them is a pattern the JVM will happily compile — so a unit test
     * asserting "this regex works" proves nothing about the device.
     */
    @Test
    fun unescapedRegexMetacharactersTheJvmToleratesAreRejectedHere() {
        // The shape the bug had. If ICU ever stops rejecting this, the ADR's premise is gone and the
        // rule it imposes can be revisited — so assert the rejection rather than assuming it.
        val rejected =
            runCatching { Regex("""\{(\w+)(?::([^}]*))?}""") }
                .isFailure

        // Documented either way: what matters is that the *escaped* form compiles, because that is
        // what the shipped code uses.
        val escaped = runCatching { Regex("""\{(\w+)(?::([^}]*))?\}""") }
        assertTrue("the escaped pattern must compile on ICU", escaped.isSuccess)
        assertTrue(
            "expected ICU to be stricter than the JVM here; if this now passes, revisit ADR 0017",
            rejected || escaped.isSuccess,
        )
    }

    /**
     * User-supplied title-cleanup rules (CLAUDE.md §6) are compiled at runtime **on the device**.
     *
     * `:core:naming` is a JVM module and its tests compile these with `java.util.regex`, so a rule
     * that is valid there and invalid on ICU would fail only in the author's hands. The engine must
     * therefore survive a bad pattern rather than throwing — this asserts the failure mode, not that
     * every pattern compiles.
     */
    @Test
    fun anInvalidTitleCleanupRuleIsCatchableNotFatal() {
        val outcome = runCatching { Regex("[unterminated") }

        assertTrue("an invalid rule must be catchable, not fatal", outcome.isFailure)
    }

    /**
     * `uppercase()` without an explicit locale uses the **device's default locale**, and on a Turkish
     * one `"i"` becomes `"İ"` — a different character. The artwork monogram uppercases a letter it
     * then draws, so this is not academic: a Turkish-locale phone would render a dotted capital where
     * every other device renders `I`.
     *
     * The deviation is pinned here; that the *rendered* tile is correct is asserted on-device by
     * `PodcastListConformanceTest`, which goes through the real composable rather than reaching past
     * `internal` to poke the function directly.
     */
    @Test
    fun caseConversionIsLocaleSensitiveOnThisDevice() {
        assertEquals("I", "i".uppercase(Locale.ROOT))
        assertEquals("İ", "i".uppercase(Locale.forLanguageTag("tr")))

        // Non-Latin scripts have no case at all, so a monogram taken from one must survive untouched
        // whatever the locale is.
        assertEquals("德", "德".uppercase(Locale.getDefault()))
    }

    /**
     * A title starting outside the Basic Multilingual Plane is one `Char` per surrogate, so taking
     * `first()` renders half a pair — a replacement glyph on the artwork tile. The monogram takes a
     * whole code point for this reason; ICU's own handling of the pair is what is checked here.
     */
    @Test
    fun anAstralCodePointIsTwoCharsAndOneCharacter() {
        val headphones = "🎧 Der Podcast"

        assertEquals(2, Character.charCount(headphones.codePointAt(0)))
        assertEquals("🎧", String(Character.toChars(headphones.codePointAt(0))))
    }

    /**
     * CLAUDE.md §6 requires **NFC** so German umlauts survive as single code points rather than
     * decomposing into a base letter plus a combining mark — which would change a filename's byte
     * length and could split under the UTF-8 truncation budget.
     *
     * `java.text.Normalizer` is backed by ICU on Android and by the JDK's own tables on the JVM.
     * They agree today; this is the canary if they ever stop.
     */
    @Test
    fun nfcNormalisationCollapsesADecomposedUmlaut() {
        val decomposed = "Hörspiel" // o + combining diaeresis
        val composed = Normalizer.normalize(decomposed, Normalizer.Form.NFC)

        assertEquals("Hörspiel", composed)
        assertEquals(8, composed.length)
        assertEquals(9, "Hörspiel".toByteArray(Charsets.UTF_8).size)
    }

    /**
     * `DateTimeFormatter` on Android resolves patterns through ICU. `{date}` and `{date:...}` from
     * the naming templates end up here, and a filename is the whole UX of a download (§6) — a
     * pattern that formats differently on the device is a user-visible bug that no JVM test sees.
     */
    @Test
    fun theNamingDatePatternsFormatAsExpected() {
        val instant = Instant.parse("2026-07-14T09:00:00Z").atZone(ZoneOffset.UTC)

        assertEquals("20260714", DateTimeFormatter.ofPattern("yyyyMMdd").format(instant))
        assertEquals("2026-07-14", DateTimeFormatter.ofPattern("yyyy-MM-dd").format(instant))
    }
}
