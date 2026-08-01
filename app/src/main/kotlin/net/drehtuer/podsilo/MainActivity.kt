// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import net.drehtuer.podsilo.core.model.port.SettingsRepository
import net.drehtuer.podsilo.core.model.port.ThemePreference
import net.drehtuer.podsilo.ui.theme.PodsiloTheme
import javax.inject.Inject

/**
 * Single activity, as `docs/UI_interface.md` §9 specifies.
 *
 * **The screens themselves are not written yet** — S1–S8 are designed and specified but unbuilt, so
 * this renders a placeholder inside the real theme rather than a `NavHost`. The theme wiring is not
 * a placeholder: the user's Light/Dark/System preference is observed and applied at the root
 * without recreating the activity (`docs/UI.md` §12.7).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val preference by settingsRepository
                .observeTheme()
                .collectAsState(initial = ThemePreference.SYSTEM)

            PodsiloTheme(preference = preference) {
                PodsiloPlaceholder()
            }
        }
    }
}

@Composable
private fun PodsiloPlaceholder() {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = "Podsilo", style = MaterialTheme.typography.headlineMedium)
            Text(
                text = "Everything below the screens is built. The screens are next.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
