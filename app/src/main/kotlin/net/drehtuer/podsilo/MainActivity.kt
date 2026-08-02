// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
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

/**
 * Single activity, as `docs/UI_interface.md` §9 specifies: one `NavHost`, S1 the start destination.
 *
 * The activity owns the two things a Composable cannot do for itself — launching the SAF picker
 * (an `ActivityResultContract`) and opening a link — and hands them to the host as callbacks. The
 * theme is observed and applied at the root without recreating the activity (`docs/UI.md` §12.7).
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

            PodsiloTheme(preference = preference) {
                PodsiloNavHost(
                    factory = viewModelFactory,
                    actions =
                        HostActions(
                            openUrl = ::openUrl,
                            chooseFolder = { folderPicker.launch(null) },
                            copy = ::copyToClipboard,
                            share = ::shareText,
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
     * A plain `ACTION_VIEW`. `docs/UI.md` §6 asks for a Custom Tab with this as the fallback; the
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
