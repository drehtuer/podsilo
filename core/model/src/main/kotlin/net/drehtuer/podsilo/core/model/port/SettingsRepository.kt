// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.model.port

import kotlinx.coroutines.flow.Flow

/**
 * Port for user-configurable settings, implemented in `:core:datastore` (Jetpack DataStore, with
 * the Nextcloud app password encrypted via a Keystore-backed cipher — never plaintext, CLAUDE.md
 * §5). Lives in Android-free `:core:model` so `:core:sync` and the feature view models depend on
 * the interface, not the DataStore implementation (`docs/architecture.md` §2).
 *
 * Everything except the app password is observable as a [Flow] so the UI (and the live naming
 * preview in `:feature:settings`) reacts to edits. The password is read-only through the suspend
 * [nextcloudCredentials] accessor rather than a hot [Flow], so the decrypted secret is never held
 * in a long-lived stream — it's fetched at the point of use (a sync pass) and dropped.
 */
interface SettingsRepository {
    fun observeNaming(): Flow<NamingSettings>

    suspend fun setNaming(settings: NamingSettings)

    fun observeDownloadFolderUri(): Flow<String?>

    suspend fun setDownloadFolderUri(uri: String?)

    fun observeSyncIntervalMinutes(): Flow<Long>

    suspend fun setSyncIntervalMinutes(minutes: Long)

    /** Non-secret connection fields, observable for the settings UI (URL + username, never the password). */
    fun observeNextcloudAccount(): Flow<NextcloudAccount?>

    /**
     * Reads and decrypts the full credentials for a sync pass. `null` when the user has not
     * configured an account yet. The decrypted app password is only ever materialised here.
     */
    suspend fun nextcloudCredentials(): NextcloudCredentials?

    suspend fun setNextcloudCredentials(credentials: NextcloudCredentials?)
}

/**
 * @property folderTemplate Default `{podcast}` (CLAUDE.md §6).
 * @property fileTemplate Default `{date}_{title}` — date first so files sort correctly in any
 *   browser/player (§6 forbids a title-first default).
 * @property transliterate Default `false`: non-ASCII (umlauts, CJK) survives by default (§6).
 * @property titleCleanupRules Ordered find/replace rules applied to the raw title before
 *   sanitising, default empty (opt-in). Held here as plain pattern/replacement strings — the
 *   `Regex` compilation belongs to `:core:naming`, which this Android-free module must not depend
 *   on.
 */
data class NamingSettings(
    val folderTemplate: String = DEFAULT_FOLDER_TEMPLATE,
    val fileTemplate: String = DEFAULT_FILE_TEMPLATE,
    val transliterate: Boolean = false,
    val titleCleanupRules: List<TitleCleanupRuleSetting> = emptyList(),
) {
    companion object {
        const val DEFAULT_FOLDER_TEMPLATE: String = "{podcast}"
        const val DEFAULT_FILE_TEMPLATE: String = "{date}_{title}"
    }
}

/** One persisted title-cleanup rule — a raw regex [pattern] and its [replacement]. See [NamingSettings]. */
data class TitleCleanupRuleSetting(
    val pattern: String,
    val replacement: String,
)

/** Non-secret Nextcloud connection fields, safe to expose to the UI. */
data class NextcloudAccount(
    val serverUrl: String,
    val username: String,
)

/**
 * Full Nextcloud credentials including the app password (CLAUDE.md §5: a Nextcloud **app
 * password**, HTTP Basic, not the account password). Only constructed transiently around a sync
 * pass; the password never lives in a persisted domain object beyond this.
 */
data class NextcloudCredentials(
    val serverUrl: String,
    val username: String,
    val appPassword: String,
) {
    val account: NextcloudAccount get() = NextcloudAccount(serverUrl, username)
}

/** Default background sync cadence when the user hasn't chosen one (best-effort — CLAUDE.md §11's Doze note). */
const val DEFAULT_SYNC_INTERVAL_MINUTES: Long = 240
