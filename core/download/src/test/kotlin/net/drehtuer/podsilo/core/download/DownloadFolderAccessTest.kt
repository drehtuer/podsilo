// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.download

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.drehtuer.podsilo.core.model.port.NamingSettings
import net.drehtuer.podsilo.core.model.port.NextcloudAccount
import net.drehtuer.podsilo.core.model.port.NextcloudCredentials
import net.drehtuer.podsilo.core.model.port.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

private const val TREE_URI = "content://com.android.externalstorage.documents/tree/primary%3APodcasts"

/**
 * The grant lifecycle CLAUDE.md §11 insists on: take it when the folder is picked, and re-check it
 * afterwards rather than assuming it survived. Robolectric's `ContentResolver` implements
 * persistable URI permissions, so both halves are exercised for real here — headless, no emulator.
 */
@RunWith(RobolectricTestRunner::class)
class DownloadFolderAccessTest {
    private lateinit var context: Context
    private val settings = MutableSettingsRepository()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    private fun access() = DownloadFolderAccess(context, settings)

    @Test
    fun `no folder chosen yet`() =
        runBlocking {
            assertEquals(DownloadFolderState.NotChosen, access().current())
        }

    @Test
    fun `remembering a picked folder takes the persistable permission and stores it`() =
        runBlocking {
            access().remember(Uri.parse(TREE_URI))

            assertEquals(TREE_URI, settings.folderUri.value)
            assertEquals(DownloadFolderState.Granted(TREE_URI), access().current())
        }

    @Test
    fun `a stored folder whose permission was revoked reports Revoked, not Granted`() =
        runBlocking {
            // Exactly what "app data cleared" or "SD card removed" looks like: the setting survives,
            // the grant does not. Downloads must prompt for a re-grant instead of failing silently.
            settings.folderUri.value = TREE_URI

            assertEquals(DownloadFolderState.Revoked(TREE_URI), access().current())
        }

    @Test
    fun `observe re-evaluates the grant when the stored folder changes`() =
        runBlocking {
            val access = access()
            settings.folderUri.value = TREE_URI
            assertEquals(DownloadFolderState.Revoked(TREE_URI), access.observe().first())

            access.remember(Uri.parse(TREE_URI))
            assertEquals(DownloadFolderState.Granted(TREE_URI), access.observe().first())
        }
}

/** Only the download-folder half is exercised; the rest of the port is out of scope for these tests. */
private class MutableSettingsRepository : SettingsRepository {
    val folderUri = MutableStateFlow<String?>(null)

    override fun observeNaming(): Flow<NamingSettings> = MutableStateFlow(NamingSettings())

    override suspend fun setNaming(settings: NamingSettings) = error("not needed by these tests")

    override fun observeDownloadFolderUri(): Flow<String?> = folderUri

    override suspend fun setDownloadFolderUri(uri: String?) {
        folderUri.value = uri
    }

    override fun observeSyncIntervalMinutes(): Flow<Long> = MutableStateFlow(0)

    override suspend fun setSyncIntervalMinutes(minutes: Long) = error("not needed by these tests")

    override fun observeNextcloudAccount(): Flow<NextcloudAccount?> = MutableStateFlow(null)

    override suspend fun nextcloudCredentials(): NextcloudCredentials? = null

    override suspend fun setNextcloudCredentials(credentials: NextcloudCredentials?) =
        error("not needed by these tests")
}
