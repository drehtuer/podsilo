// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.naming

import java.text.Normalizer

/**
 * Characters illegal on the strictest plausible target (FAT32/exFAT, i.e. an SD card, which is
 * stricter than Android's internal ext4 -- CLAUDE.md section 6): `< > : " / \ | ? *`, plus all C0
 * control characters and the path separators. Space and hyphen are deliberately **not** here --
 * both are ordinary, legal filename characters. Runs of illegal characters are collapsed to a
 * single [SEPARATOR] rather than deleted, so words don't run together.
 *
 * Whitespace control characters (tab, newline, CR, ...) are handled by [WHITESPACE_RUN] *first*
 * and so never reach this regex as themselves -- without that ordering, a title containing a tab
 * or embedded newline would sanitise to a stray `_` instead of collapsing like any other
 * whitespace. What's left for this regex is genuinely non-whitespace control bytes.
 */
private val ILLEGAL_CHARACTERS_RUN = Regex("[<>:\"/\\\\|?*\\x00-\\x1F]+")
private val WHITESPACE_RUN = Regex("\\s+")
private val TRAILING_DOTS_AND_SPACES = Regex("[. ]+$")
private const val SEPARATOR = "_"

/**
 * German umlauts and eszett, for the optional transliteration setting (default off -- CLAUDE.md
 * section 6 says non-ASCII must survive by default; this map exists only for players with poor
 * Unicode handling that the author opts into explicitly).
 */
private val TRANSLITERATION_MAP =
    mapOf(
        'ä' to "ae",
        'ö' to "oe",
        'ü' to "ue",
        'ß' to "ss",
        'Ä' to "Ae",
        'Ö' to "Oe",
        'Ü' to "Ue",
    )

/**
 * Sanitises one filename/foldername component for the strictest plausible target filesystem. Does
 * **not** truncate or apply the Windows-reserved-name check -- those are separate, composable
 * steps ([truncateUtf8Safe], [escapeReservedName]) since truncation needs a byte budget only the
 * caller knows and the reserved-name check must run on the fully assembled (post-truncation) name.
 *
 * May return an empty string if nothing valid survives -- CLAUDE.md section 6: "empty result after
 * sanitising -> fall back to `{guid_short}`", which is the caller's responsibility.
 */
fun sanitizeComponent(
    value: String,
    transliterate: Boolean,
): String {
    var result = if (transliterate) transliterate(value) else value
    result = Normalizer.normalize(result, Normalizer.Form.NFC)
    result = result.replace(WHITESPACE_RUN, " ").trim()
    result = result.replace(ILLEGAL_CHARACTERS_RUN, SEPARATOR)
    return stripTrailingDotsAndSpaces(result)
}

/** Exposed separately: truncation can re-expose a trailing dot/space at the new cut point. */
fun stripTrailingDotsAndSpaces(value: String): String = value.replace(TRAILING_DOTS_AND_SPACES, "")

private fun transliterate(value: String): String =
    buildString {
        for (char in value) {
            append(TRANSLITERATION_MAP[char] ?: char.toString())
        }
    }
