// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.naming

import java.security.MessageDigest

private const val SHORT_HASH_LENGTH = 8

/**
 * `{guid_short}` -- the first 8 hex characters of a SHA-256 hash of [episodeKey] (CLAUDE.md
 * section 6: "for guaranteed uniqueness"). Also the fallback value used whenever a sanitised
 * component would otherwise be empty, so it doubles as this module's last-resort identifier.
 */
fun guidShort(episodeKey: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(episodeKey.toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { "%02x".format(it) }.take(SHORT_HASH_LENGTH)
}
