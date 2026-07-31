// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.drehtuer.podsilo.core.model.port.DEFAULT_SYNC_INTERVAL_MINUTES
import net.drehtuer.podsilo.core.model.port.NamingSettings
import net.drehtuer.podsilo.core.model.port.NextcloudAccount
import net.drehtuer.podsilo.core.model.port.NextcloudCredentials
import net.drehtuer.podsilo.core.model.port.TitleCleanupRuleSetting
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File

class DataStoreSettingsRepositoryTest {
    private lateinit var scope: CoroutineScope
    private lateinit var file: File
    private lateinit var dataStore: DataStore<Preferences>
    private val cipher = FakeAppPasswordCipher()

    private fun repository() = DataStoreSettingsRepository(dataStore, cipher)

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        file = File.createTempFile("settings", ".preferences_pb").also { it.delete() }
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
    }

    @After
    fun tearDown() {
        scope.cancel()
        file.delete()
    }

    @Test
    fun `naming settings fall back to the documented defaults when nothing is stored`() =
        runTest {
            val naming = repository().observeNaming().first()

            assertEquals(NamingSettings.DEFAULT_FOLDER_TEMPLATE, naming.folderTemplate)
            assertEquals(NamingSettings.DEFAULT_FILE_TEMPLATE, naming.fileTemplate)
            assertFalse(naming.transliterate)
            assertEquals(emptyList<TitleCleanupRuleSetting>(), naming.titleCleanupRules)
        }

    @Test
    fun `naming settings round-trip including title cleanup rules`() =
        runTest {
            val repo = repository()
            val stripPrefix = TitleCleanupRuleSetting(pattern = "^Ep\\.? ?\\d+ *[-–—:] *", replacement = "")
            val collapseSpaces = TitleCleanupRuleSetting(pattern = " +", replacement = " ")
            val settings =
                NamingSettings(
                    folderTemplate = "{podcast}/{date:yyyy}",
                    fileTemplate = "{date}_{title}",
                    transliterate = true,
                    titleCleanupRules = listOf(stripPrefix, collapseSpaces),
                )

            repo.setNaming(settings)

            assertEquals(settings, repo.observeNaming().first())
        }

    @Test
    fun `sync interval defaults then round-trips`() =
        runTest {
            val repo = repository()
            assertEquals(DEFAULT_SYNC_INTERVAL_MINUTES, repo.observeSyncIntervalMinutes().first())

            repo.setSyncIntervalMinutes(720)
            assertEquals(720, repo.observeSyncIntervalMinutes().first())
        }

    @Test
    fun `download folder uri is nullable and clearable`() =
        runTest {
            val repo = repository()
            assertNull(repo.observeDownloadFolderUri().first())

            repo.setDownloadFolderUri("content://tree/primary%3APodcasts")
            assertEquals("content://tree/primary%3APodcasts", repo.observeDownloadFolderUri().first())

            repo.setDownloadFolderUri(null)
            assertNull(repo.observeDownloadFolderUri().first())
        }

    @Test
    fun `credentials round-trip, and the app password is stored encrypted, never plaintext`() =
        runTest {
            val repo = repository()
            val creds =
                NextcloudCredentials(
                    serverUrl = "https://cloud.example.net",
                    username = "author",
                    appPassword = "s3cr3t-app-pw",
                )

            repo.setNextcloudCredentials(creds)

            // Full credentials come back decrypted.
            assertEquals(creds, repo.nextcloudCredentials())
            // The non-secret account is observable without the password.
            val account = NextcloudAccount("https://cloud.example.net", "author")
            assertEquals(account, repo.observeNextcloudAccount().first())
            // The value actually persisted is the ciphertext, not the plaintext password.
            val storedPassword = dataStore.data.first()[stringPreferencesKey("nextcloud_app_password_enc")]
            assertEquals(cipher.encrypt("s3cr3t-app-pw"), storedPassword)
            assertFalse("plaintext password must not be persisted", storedPassword!!.contains("s3cr3t-app-pw"))
        }

    @Test
    fun `clearing credentials removes them entirely`() =
        runTest {
            val repo = repository()
            repo.setNextcloudCredentials(NextcloudCredentials("https://cloud.example.net", "author", "pw"))

            repo.setNextcloudCredentials(null)

            assertNull(repo.nextcloudCredentials())
            assertNull(repo.observeNextcloudAccount().first())
        }

    @Test
    fun `a cipher that fails to decrypt degrades to no credentials rather than crashing`() =
        runTest {
            // Write with the working cipher, then read back through one whose decrypt always throws
            // (mimicking an invalidated Keystore key — CLAUDE.md §11 resilience).
            repository().setNextcloudCredentials(NextcloudCredentials("https://cloud.example.net", "author", "pw"))

            val brokenCipher =
                object : AppPasswordCipher {
                    override fun encrypt(plaintext: String) = plaintext

                    override fun decrypt(ciphertext: String) = error("key invalidated")
                }
            val repo = DataStoreSettingsRepository(dataStore, brokenCipher)

            assertNull(repo.nextcloudCredentials())
            // The non-secret account is still observable — only the secret is unreadable.
            val account = NextcloudAccount("https://cloud.example.net", "author")
            assertEquals(account, repo.observeNextcloudAccount().first())
        }
}
