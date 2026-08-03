// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.download

import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import net.drehtuer.podsilo.core.download.SafDownloadTarget
import net.drehtuer.podsilo.core.model.port.NamingSettings
import net.drehtuer.podsilo.core.model.port.NextcloudAccount
import net.drehtuer.podsilo.core.model.port.NextcloudCredentials
import net.drehtuer.podsilo.core.model.port.OlderThan
import net.drehtuer.podsilo.core.model.port.SettingsRepository
import net.drehtuer.podsilo.core.model.port.SwipeMapping
import net.drehtuer.podsilo.core.model.port.ThemePreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * `SafDownloadTarget` writing through a **real** `DocumentsProvider` — what `docs/decisions/0011`
 * says can only be checked on a device, and what `docs/dev-environment.md` listed as "never run".
 *
 * These run in the **app's own process**, so they inherit the persistable tree-URI grant the app
 * already holds. That is the only way to get a usable tree URI without driving the system picker:
 * a grant belongs to a package, and `:core:download`'s own test APK is a different one.
 *
 * **Requires a download folder to have been chosen once** (S1's checklist, or S4). Without one the
 * tests skip rather than fail — a missing grant is a setup gap, not a regression, and a red suite
 * would train people to ignore it.
 */
@RunWith(AndroidJUnit4::class)
class SafDownloadTargetInstrumentedTest {
    private lateinit var context: Context
    private lateinit var treeUri: String
    private lateinit var target: SafDownloadTarget

    /** A folder of our own per run, so a failure never leaves rubbish in the real download folder. */
    private val folder = "PodsiloInstrumentedTest"

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val granted =
            context.contentResolver.persistedUriPermissions
                .firstOrNull { it.isWritePermission }
                ?.uri
                ?.toString()
        assumeTrue(
            "no download folder has been granted — pick one in the app first (S1 → Choose folder)",
            granted != null,
        )
        treeUri = granted!!
        target = SafDownloadTarget(context, FixedFolderSettings(treeUri))
    }

    private fun sourceFile(
        name: String,
        bytes: ByteArray,
    ): File = File.createTempFile(name, ".tmp", context.cacheDir).apply { writeBytes(bytes) }

    private fun delivered(fileName: String): DocumentFile? =
        DocumentFile
            .fromTreeUri(context, treeUri.toUri())
            ?.findFile(folder)
            ?.findFile(fileName)

    @Test
    fun deliversAFileIntoTheChosenFolder() {
        val payload = "podsilo instrumented payload".toByteArray()
        val source = sourceFile("deliver", payload)

        val result = runBlocking { target.deliver(folder, "delivered.mp3", source) }

        assertTrue("deliver failed: ${result.exceptionOrNull()}", result.isSuccess)
        val written = delivered("delivered.mp3")
        assertNotNull("the file is not in the folder", written)
        val readBack =
            context.contentResolver.openInputStream(written!!.uri)!!.use { it.readBytes() }
        assertEquals("the bytes changed in transit", payload.toList(), readBack.toList())
    }

    @Test
    fun createsTheFeedSubfolderWhenItDoesNotExist() {
        // The naming template's {podcast} component: the folder is ours to create, once.
        val source = sourceFile("subfolder", "x".toByteArray())

        runBlocking { target.deliver("$folder/Nested Feed", "in-nested.mp3", source) }.getOrThrow()

        val nested =
            DocumentFile
                .fromTreeUri(context, treeUri.toUri())
                ?.findFile(folder)
                ?.findFile("Nested Feed")
        assertNotNull("the nested folder was not created", nested)
        assertNotNull(nested!!.findFile("in-nested.mp3"))
    }

    @Test
    fun aRetryOverwritesItsOwnPartialFileRatherThanCreatingASecond() {
        // The ledger reuses `writtenFileName` on a retry, so delivering the same name twice must
        // replace — not produce "… (2)" beside a half-written predecessor (CLAUDE.md §6).
        val name = "retried.mp3"
        runBlocking { target.deliver(folder, name, sourceFile("first", "partial".toByteArray())) }.getOrThrow()
        runBlocking { target.deliver(folder, name, sourceFile("second", "complete".toByteArray())) }.getOrThrow()

        val matches =
            DocumentFile
                .fromTreeUri(context, treeUri.toUri())
                ?.findFile(folder)
                ?.listFiles()
                ?.filter { it.name?.startsWith("retried") == true }
                .orEmpty()
        assertEquals("a second file was created instead of overwriting", 1, matches.size)
        val bytes = context.contentResolver.openInputStream(matches.single().uri)!!.use { it.readBytes() }
        assertEquals("complete", String(bytes))
    }

    @Test
    fun existingNamesReportsWhatIsActuallyThere() {
        runBlocking { target.deliver(folder, "listed.mp3", sourceFile("listed", "y".toByteArray())) }.getOrThrow()

        val names = runBlocking { target.existingNames(folder) }.getOrThrow()

        assertTrue("existingNames missed a file it just wrote: $names", names.contains("listed.mp3"))
    }

    @Test
    fun freeBytesAnswersFromTheTreeUriRatherThanAPath() {
        // fstatvfs on a descriptor opened from the tree URI — a tree URI has no filesystem path
        // (CLAUDE.md §11). `null` is a documented outcome, so only a *negative* answer is a bug.
        val free = runBlocking { target.freeBytes() }

        assertTrue("freeBytes returned a nonsensical $free", free == null || free > 0)
    }

    @Test
    fun umlautsSurviveTheRoundTripThroughSaf() {
        // CLAUDE.md §6: the author's own language must reach the folder intact.
        val name = "20260714_Wärme über Hamburg.mp3"

        runBlocking { target.deliver(folder, name, sourceFile("umlaut", "z".toByteArray())) }.getOrThrow()

        assertNotNull("the umlauted name did not survive", delivered(name))
    }
}

/** Only the folder URI matters here; everything else is a default the target never reads. */
private class FixedFolderSettings(
    private val uri: String,
) : SettingsRepository {
    override fun observeDownloadFolderUri(): Flow<String?> = MutableStateFlow(uri)

    override suspend fun setDownloadFolderUri(uri: String?) = Unit

    override fun observeNaming(): Flow<NamingSettings> = MutableStateFlow(NamingSettings())

    override suspend fun setNaming(settings: NamingSettings) = Unit

    override fun observeSyncIntervalMinutes(): Flow<Long> = MutableStateFlow(60)

    override suspend fun setSyncIntervalMinutes(minutes: Long) = Unit

    override fun observeTheme(): Flow<ThemePreference> = MutableStateFlow(ThemePreference.SYSTEM)

    override suspend fun setTheme(theme: ThemePreference) = Unit

    override fun observeSwipeMapping(): Flow<SwipeMapping> = MutableStateFlow(SwipeMapping())

    override suspend fun setSwipeMapping(mapping: SwipeMapping) = Unit

    override fun observeAllowMobileData(): Flow<Boolean> = MutableStateFlow(false)

    override suspend fun setAllowMobileData(allowed: Boolean) = Unit

    override fun observeDeliveredClearedAt(): kotlinx.coroutines.flow.Flow<Long> = kotlinx.coroutines.flow.flowOf(0L)

    override suspend fun setDeliveredClearedAt(millis: Long) = Unit

    override fun observeMarkOldOlderThan(): Flow<OlderThan> = MutableStateFlow(OlderThan.OFF)

    override suspend fun setMarkOldOlderThan(value: OlderThan) = Unit

    override fun observeNextcloudAccount(): Flow<NextcloudAccount?> = MutableStateFlow(null)

    override suspend fun nextcloudCredentials(): NextcloudCredentials? = null

    override suspend fun setNextcloudCredentials(credentials: NextcloudCredentials?) = Unit
}
