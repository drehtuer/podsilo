// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.naming

/** Used when the URL carries no plausible extension -- podcasts are not always MP3, but it's the safest default. */
const val DEFAULT_EXTENSION = "mp3"

private val EXTENSION_PATTERN = Regex("""\.([A-Za-z0-9]{1,5})$""")

/**
 * URL-only extension resolution (no leading dot), ignoring the query string and fragment.
 *
 * This is **not** the full three-step chain CLAUDE.md section 6 specifies (`Content-Type` -> URL
 * path -> `.mp3`) -- this pure-JVM module never sees an HTTP response, so it only ever provides the
 * URL-path fallback and the final default. `:core:download` (Tier 4) is responsible for trying the
 * live response's `Content-Type` first and falling back to this value, not the other way round.
 * This function still has a use on its own: the settings live preview has no HTTP response either.
 */
fun resolveExtensionFromUrl(enclosureUrl: String): String {
    val path = enclosureUrl.substringBefore('?').substringBefore('#').substringAfterLast('/')
    return EXTENSION_PATTERN
        .find(path)
        ?.groupValues
        ?.get(1)
        ?.lowercase() ?: DEFAULT_EXTENSION
}
