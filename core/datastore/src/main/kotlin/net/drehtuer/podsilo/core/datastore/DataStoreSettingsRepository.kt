// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import net.drehtuer.podsilo.core.model.port.DEFAULT_SYNC_INTERVAL_MINUTES
import net.drehtuer.podsilo.core.model.port.NamingSettings
import net.drehtuer.podsilo.core.model.port.NextcloudAccount
import net.drehtuer.podsilo.core.model.port.NextcloudCredentials
import net.drehtuer.podsilo.core.model.port.OlderThan
import net.drehtuer.podsilo.core.model.port.SettingsRepository
import net.drehtuer.podsilo.core.model.port.SwipeAction
import net.drehtuer.podsilo.core.model.port.SwipeMapping
import net.drehtuer.podsilo.core.model.port.ThemePreference
import net.drehtuer.podsilo.core.model.port.TitleCleanupRuleSetting

/**
 * DataStore-Preferences implementation of [SettingsRepository] (CLAUDE.md §3 mandates DataStore
 * over SharedPreferences wrappers). Non-secret settings are plain preference keys; the Nextcloud
 * app password is stored only as ciphertext from [cipher] and decrypted transiently in
 * [nextcloudCredentials]. Bound to the port via Hilt `@Binds` in `:app` (Tier 4c); a real
 * [DataStore] is built with [createSettingsDataStore].
 *
 * `@Suppress("TooManyFunctions")`: the count is the port's nine members plus three private
 * `Preferences`->domain mappers — intrinsic to a thin store, not a sign it's doing too much.
 */
@Suppress("TooManyFunctions")
class DataStoreSettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val cipher: AppPasswordCipher,
    private val json: Json = Json,
) : SettingsRepository {
    override fun observeNaming(): Flow<NamingSettings> = dataStore.data.map { it.toNamingSettings() }

    override suspend fun setNaming(settings: NamingSettings) {
        dataStore.edit { prefs ->
            prefs[Keys.FOLDER_TEMPLATE] = settings.folderTemplate
            prefs[Keys.FILE_TEMPLATE] = settings.fileTemplate
            prefs[Keys.TRANSLITERATE] = settings.transliterate
            prefs[Keys.TITLE_CLEANUP_RULES] = json.encodeToString(RULES_SERIALIZER, settings.titleCleanupRules.toDto())
        }
    }

    override fun observeDownloadFolderUri(): Flow<String?> = dataStore.data.map { it[Keys.DOWNLOAD_FOLDER_URI] }

    override suspend fun setDownloadFolderUri(uri: String?) {
        dataStore.edit { prefs ->
            if (uri == null) prefs.remove(Keys.DOWNLOAD_FOLDER_URI) else prefs[Keys.DOWNLOAD_FOLDER_URI] = uri
        }
    }

    override fun observeSyncIntervalMinutes(): Flow<Long> =
        dataStore.data.map { it[Keys.SYNC_INTERVAL_MINUTES] ?: DEFAULT_SYNC_INTERVAL_MINUTES }

    override suspend fun setSyncIntervalMinutes(minutes: Long) {
        dataStore.edit { it[Keys.SYNC_INTERVAL_MINUTES] = minutes }
    }

    override fun observeTheme(): Flow<ThemePreference> =
        dataStore.data.map { prefs -> prefs[Keys.THEME].toEnumOr(ThemePreference.SYSTEM) }

    override suspend fun setTheme(theme: ThemePreference) {
        dataStore.edit { it[Keys.THEME] = theme.name }
    }

    override fun observeSwipeMapping(): Flow<SwipeMapping> =
        dataStore.data.map { prefs ->
            SwipeMapping(
                right = prefs[Keys.SWIPE_RIGHT].toEnumOr(SwipeAction.DOWNLOAD),
                left = prefs[Keys.SWIPE_LEFT].toEnumOr(SwipeAction.MARK_AS_PLAYED),
            )
        }

    override suspend fun setSwipeMapping(mapping: SwipeMapping) {
        dataStore.edit { prefs ->
            prefs[Keys.SWIPE_RIGHT] = mapping.right.name
            prefs[Keys.SWIPE_LEFT] = mapping.left.name
        }
    }

    override fun observeAllowMobileData(): Flow<Boolean> = dataStore.data.map { it[Keys.ALLOW_MOBILE_DATA] ?: false }

    override suspend fun setAllowMobileData(allowed: Boolean) {
        dataStore.edit { it[Keys.ALLOW_MOBILE_DATA] = allowed }
    }

    override fun observeMarkOldOlderThan(): Flow<OlderThan> =
        dataStore.data.map { prefs -> prefs[Keys.MARK_OLD_OLDER_THAN].toEnumOr(OlderThan.OFF) }

    override suspend fun setMarkOldOlderThan(value: OlderThan) {
        dataStore.edit { it[Keys.MARK_OLD_OLDER_THAN] = value.name }
    }

    override fun observeDeliveredClearedAt(): Flow<Long> =
        dataStore.data.map { prefs -> prefs[Keys.DELIVERED_CLEARED_AT] ?: 0L }

    override suspend fun setDeliveredClearedAt(millis: Long) {
        dataStore.edit { it[Keys.DELIVERED_CLEARED_AT] = millis }
    }

    override fun observeNextcloudAccount(): Flow<NextcloudAccount?> = dataStore.data.map { it.toNextcloudAccount() }

    override suspend fun nextcloudCredentials(): NextcloudCredentials? {
        val prefs = dataStore.data.first()
        val url = prefs[Keys.NEXTCLOUD_SERVER_URL]
        val username = prefs[Keys.NEXTCLOUD_USERNAME]
        val encrypted = prefs[Keys.NEXTCLOUD_APP_PASSWORD_ENC]
        if (url == null || username == null || encrypted == null) return null
        // A key that was invalidated (device credential reset, etc.) makes decryption fail; treat
        // that as "no credentials" so the UI prompts for re-entry rather than crashing (CLAUDE.md §11).
        val password = runCatching { cipher.decrypt(encrypted) }.getOrNull()
        return password?.let { NextcloudCredentials(serverUrl = url, username = username, appPassword = it) }
    }

    override suspend fun setNextcloudCredentials(credentials: NextcloudCredentials?) {
        dataStore.edit { prefs ->
            if (credentials == null) {
                prefs.remove(Keys.NEXTCLOUD_SERVER_URL)
                prefs.remove(Keys.NEXTCLOUD_USERNAME)
                prefs.remove(Keys.NEXTCLOUD_APP_PASSWORD_ENC)
            } else {
                prefs[Keys.NEXTCLOUD_SERVER_URL] = credentials.serverUrl
                prefs[Keys.NEXTCLOUD_USERNAME] = credentials.username
                prefs[Keys.NEXTCLOUD_APP_PASSWORD_ENC] = cipher.encrypt(credentials.appPassword)
            }
        }
    }

    private fun Preferences.toNamingSettings(): NamingSettings =
        NamingSettings(
            folderTemplate = this[Keys.FOLDER_TEMPLATE] ?: NamingSettings.DEFAULT_FOLDER_TEMPLATE,
            fileTemplate = this[Keys.FILE_TEMPLATE] ?: NamingSettings.DEFAULT_FILE_TEMPLATE,
            transliterate = this[Keys.TRANSLITERATE] ?: false,
            titleCleanupRules = decodeRules(this[Keys.TITLE_CLEANUP_RULES]),
        )

    private fun Preferences.toNextcloudAccount(): NextcloudAccount? {
        val url = this[Keys.NEXTCLOUD_SERVER_URL]
        val username = this[Keys.NEXTCLOUD_USERNAME]
        return if (url != null && username != null) NextcloudAccount(serverUrl = url, username = username) else null
    }

    private fun decodeRules(raw: String?): List<TitleCleanupRuleSetting> {
        if (raw.isNullOrEmpty()) return emptyList()
        return runCatching { json.decodeFromString(RULES_SERIALIZER, raw).toDomain() }.getOrDefault(emptyList())
    }

    private object Keys {
        val THEME = stringPreferencesKey("theme")
        val SWIPE_RIGHT = stringPreferencesKey("swipe_right")
        val SWIPE_LEFT = stringPreferencesKey("swipe_left")
        val ALLOW_MOBILE_DATA = booleanPreferencesKey("allow_mobile_data")
        val MARK_OLD_OLDER_THAN = stringPreferencesKey("mark_old_older_than")
        val DELIVERED_CLEARED_AT = longPreferencesKey("delivered_cleared_at")
        val FOLDER_TEMPLATE = stringPreferencesKey("folder_template")
        val FILE_TEMPLATE = stringPreferencesKey("file_template")
        val TRANSLITERATE = booleanPreferencesKey("transliterate")
        val TITLE_CLEANUP_RULES = stringPreferencesKey("title_cleanup_rules")
        val DOWNLOAD_FOLDER_URI = stringPreferencesKey("download_folder_uri")
        val SYNC_INTERVAL_MINUTES = longPreferencesKey("sync_interval_minutes")
        val NEXTCLOUD_SERVER_URL = stringPreferencesKey("nextcloud_server_url")
        val NEXTCLOUD_USERNAME = stringPreferencesKey("nextcloud_username")
        val NEXTCLOUD_APP_PASSWORD_ENC = stringPreferencesKey("nextcloud_app_password_enc")
    }

    private companion object {
        val RULES_SERIALIZER = ListSerializer(SerializableTitleCleanupRule.serializer())
    }
}

/**
 * Enums are persisted by `name`, so a value renamed or removed in a later version would otherwise
 * throw on read and take the whole settings [Flow] down with it. An unrecognised stored name falls
 * back to [fallback] — the same defensive posture as the invalidated-cipher path above: a settings
 * store that cannot be read must degrade to defaults, never crash the screen reading it.
 */
private inline fun <reified T : Enum<T>> String?.toEnumOr(fallback: T): T =
    this?.let { stored -> enumValues<T>().firstOrNull { it.name == stored } } ?: fallback
