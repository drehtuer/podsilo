// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.naming

/** Used when the URL carries no plausible extension -- podcasts are not always MP3, but it's the safest default. */
const val DEFAULT_EXTENSION = "mp3"

private val EXTENSION_PATTERN = Regex("""\.([A-Za-z0-9]{1,5})$""")

/**
 * The `Content-Type` values podcast enclosures actually arrive with, mapped to the extension the
 * user's player expects. Deliberately small and explicit: an unknown type falls through to the URL
 * path rather than being guessed at from the subtype, because `audio/x-whatever` guesses produce
 * extensions no player recognises. Podcasts are not always MP3 (CLAUDE.md section 6).
 */
private val CONTENT_TYPE_EXTENSIONS =
    mapOf(
        "audio/mpeg" to "mp3",
        "audio/mp3" to "mp3",
        "audio/x-mpeg" to "mp3",
        "audio/mp4" to "m4a",
        "audio/x-m4a" to "m4a",
        "audio/m4a" to "m4a",
        "audio/aac" to "aac",
        "audio/aacp" to "aac",
        "audio/ogg" to "ogg",
        "application/ogg" to "ogg",
        "audio/opus" to "opus",
        "audio/flac" to "flac",
        "audio/x-flac" to "flac",
        "audio/wav" to "wav",
        "audio/x-wav" to "wav",
        "video/mp4" to "mp4",
    )

/**
 * The full three-step chain CLAUDE.md section 6 specifies: response `Content-Type` -> extension in
 * the URL path -> `mp3`. Returns no leading dot.
 *
 * [contentType] may carry parameters (`audio/mpeg; charset=binary`) and arbitrary casing; both are
 * normalised away. `null` (no response yet, as in the settings live preview) or an unrecognised
 * type falls through to [resolveExtensionFromUrl] -- **never** trust the URL's extension when a
 * usable `Content-Type` is present, which is the whole point of the ordering.
 */
fun resolveExtension(
    contentType: String?,
    enclosureUrl: String,
): String {
    val normalised =
        contentType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
    return CONTENT_TYPE_EXTENSIONS[normalised] ?: resolveExtensionFromUrl(enclosureUrl)
}

/**
 * URL-only extension resolution (no leading dot), ignoring the query string and fragment -- step
 * two of [resolveExtension]'s chain, exposed separately because it is meaningful on its own
 * wherever no HTTP response exists.
 */
fun resolveExtensionFromUrl(enclosureUrl: String): String {
    val path = enclosureUrl.substringBefore('?').substringBefore('#').substringAfterLast('/')
    return EXTENSION_PATTERN
        .find(path)
        ?.groupValues
        ?.get(1)
        ?.lowercase() ?: DEFAULT_EXTENSION
}
