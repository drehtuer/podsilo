// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.gpodder

/**
 * Nextcloud credentials for HTTP Basic auth. [password] must be a Nextcloud **app password**, not
 * the account password (CLAUDE.md section 5). Storing it securely is `:core:datastore`'s job
 * (Tier 4a); this module only ever receives it.
 *
 * [toString] is overridden so an accidental log/crash-report of this object can't leak the
 * password -- data classes otherwise print every property.
 */
data class GpodderCredentials(
    val username: String,
    val password: String,
) {
    override fun toString(): String = "GpodderCredentials(username=$username, password=***)"
}
