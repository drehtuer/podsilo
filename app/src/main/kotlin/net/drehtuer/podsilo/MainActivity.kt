// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import net.drehtuer.podsilo.core.download.DownloadFolderAccess
import net.drehtuer.podsilo.core.model.port.SettingsRepository
import net.drehtuer.podsilo.core.model.port.ThemePreference
import net.drehtuer.podsilo.ui.EpisodeViewModelFactory
import net.drehtuer.podsilo.ui.HostActions
import net.drehtuer.podsilo.ui.PodsiloNavHost
import net.drehtuer.podsilo.ui.theme.PodsiloTheme
import javax.inject.Inject

/** What the export is written as, and the first filter the restore picker offers. */
private const val ZIP_MIME = "application/zip"

/**
 * What the restore picker will show.
 *
 * The wildcard type `&#42;&#47;&#42;` used to be on this list, which made the whole filter a no-op:
 * in a real Downloads folder the picker listed PDFs, APKs and photos, and the one file the user came
 * for was somewhere among them.
 * It was there for a real reason — a backup that cannot be picked is worse than a cluttered picker,
 * and file managers genuinely do report zips under several types — so the fix is to name those types
 * rather than to give up on filtering.
 *
 * The list is what providers actually report for a `.zip`: the standard type (which is also what
 * [ZIP_MIME] writes, so our own backups always match), the generic fallback for a file whose type a
 * provider cannot place, and the two `x-zip` spellings that come from Windows-influenced file
 * managers. `Intent.EXTRA_MIME_TYPES` is a union, so adding a spelling only ever widens what is
 * offered.
 *
 * A picked file is still validated before anything is replaced — `DatabaseArchive.importFrom` is
 * all-or-nothing and reports a typed failure — so the filter is about finding the file, not about
 * trusting it.
 *
 * **Measured on 2026-08-14**, which is what turned this from reasoning into a fact: a real Podsilo
 * backup sitting in a Pixel 10a's Downloads folder is reported by the provider as `application/zip`
 * — the first entry below, so the picker shows it. The other three stay as the cheap insurance they
 * were: `EXTRA_MIME_TYPES` is a union, so a spelling that never matches costs nothing.
 */
private val BACKUP_MIME_TYPES =
    arrayOf(
        ZIP_MIME,
        "application/octet-stream",
        "application/x-zip-compressed",
        "application/zip-compressed",
    )

/**
 * Single activity, as `docs/UI.adoc` §B9 specifies: one `NavHost`, S1 the start destination.
 *
 * The activity owns the two things a Composable cannot do for itself — launching the SAF picker
 * (an `ActivityResultContract`) and opening a link — and hands them to the host as callbacks. The
 * theme is observed and applied at the root without recreating the activity (`docs/UI.adoc` §12.7).
 *
 * S4–S8 are not built, so there is no route to them yet; the navigation host says so out loud
 * rather than dropping those events.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var viewModelFactory: EpisodeViewModelFactory

    @Inject
    lateinit var downloadFolderAccess: DownloadFolderAccess

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val preference by settingsRepository
                .observeTheme()
                .collectAsState(initial = ThemePreference.SYSTEM)
            val scope = rememberCoroutineScope()

            // OpenDocumentTree, and the grant is taken immediately: a persistable permission not
            // taken at the moment of the result is simply lost (CLAUDE.md §11).
            val folderPicker =
                rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                    if (uri != null) scope.launch { downloadFolderAccess.remember(uri) }
                }

            // Unlike the folder picker, these two hand their result back to whoever asked, because
            // S4 reports the outcome of the backup rather than the activity doing it silently. The
            // pending callback is held across the picker; if the process is recreated while the
            // picker is open the callback is gone and the operation simply does not happen — which
            // is the safe direction, since nothing has been written at that point.
            var pendingPick by remember { mutableStateOf<((String) -> Unit)?>(null) }

            fun deliver(uri: Uri?) {
                val callback = pendingPick
                pendingPick = null
                if (uri != null) callback?.invoke(uri.toString())
            }

            val backupCreator =
                rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ZIP_MIME)) { deliver(it) }
            val backupOpener =
                rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { deliver(it) }

            PodsiloTheme(preference = preference) {
                PodsiloNavHost(
                    factory = viewModelFactory,
                    actions =
                        HostActions(
                            openUrl = ::openUrl,
                            chooseFolder = { folderPicker.launch(null) },
                            copy = ::copyToClipboard,
                            share = ::shareText,
                            createBackupFile = { name, onPicked ->
                                pendingPick = onPicked
                                backupCreator.launch(name)
                            },
                            openBackupFile = { onPicked ->
                                pendingPick = onPicked
                                backupOpener.launch(BACKUP_MIME_TYPES)
                            },
                        ),
                )
            }
        }
    }

    /** S8's *copy all*. The log is plain text and never contains a credential — see `LogRepository`. */
    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("Podsilo error log", text))
    }

    /** S8's *share*. A chooser, so the destination is the user's choice and nothing is uploaded. */
    private fun shareText(text: String) {
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
        startActivity(Intent.createChooser(intent, "Share error log"))
    }

    /**
     * A plain `ACTION_VIEW`. `docs/UI.adoc` §6 asks for a Custom Tab with this as the fallback; the
     * Custom Tabs dependency is not in the catalog, so this is the fallback on its own — and a
     * device with no browser at all must not crash the app over a show-notes link.
     */
    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w("Podsilo", "No activity can open $url", e)
            Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show()
        }
    }
}
