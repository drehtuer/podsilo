// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import net.drehtuer.podsilo.feature.settings.ConnectDialog
import net.drehtuer.podsilo.feature.settings.ConnectEffect
import net.drehtuer.podsilo.feature.settings.ConnectViewModel
import net.drehtuer.podsilo.feature.settings.NamingScreen
import net.drehtuer.podsilo.feature.settings.NamingViewModel
import net.drehtuer.podsilo.feature.settings.SettingsEffect
import net.drehtuer.podsilo.feature.settings.SettingsEvent
import net.drehtuer.podsilo.feature.settings.SettingsScreen
import net.drehtuer.podsilo.feature.settings.SettingsViewModel

/**
 * S4, S5 and S6 as navigation destinations. Split from [PodsiloNavHost] so the host stays a list of
 * routes rather than the sum of every screen's wiring.
 */
@Composable
internal fun SettingsDestination(
    factory: EpisodeViewModelFactory,
    host: Host,
) {
    val viewModel = viewModel<SettingsViewModel>(factory = factory.settings())
    val state by viewModel.state.collectAsStateWithLifecycle()

    OnEffect(viewModel.effect) { effect ->
        when (effect) {
            SettingsEffect.OpenConnect -> host.navController.navigate(Routes.CONNECT)
            SettingsEffect.OpenNaming -> host.navController.navigate(Routes.NAMING)
            SettingsEffect.ChooseFolder -> host.onChooseFolder()
            SettingsEffect.OpenActivity -> host.navController.navigate(Routes.ACTIVITY)
            SettingsEffect.OpenErrorLog -> host.navController.navigate(Routes.ERROR_LOG)
            // The picked document goes back in as an event, so the view model — not the activity —
            // owns what happens to it and can report the outcome.
            is SettingsEffect.CreateBackupFile ->
                host.onCreateBackupFile(effect.suggestedName) {
                    viewModel.onEvent(SettingsEvent.BackupDestinationChosen(it))
                }
            SettingsEffect.OpenBackupFile ->
                host.onOpenBackupFile { viewModel.onEvent(SettingsEvent.BackupSourceChosen(it)) }
            is SettingsEffect.ShowMessage -> host.snackbar.showSnackbar(effect.text)
        }
    }
    SettingsScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = { host.navController.popBackStack() },
    )
}

@Composable
internal fun ConnectDestination(
    factory: EpisodeViewModelFactory,
    host: Host,
) {
    val viewModel = viewModel<ConnectViewModel>(factory = factory.connect())
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Pre-fills the host and re-titles the dialog when an instance is already configured.
    LaunchedEffect(viewModel) { viewModel.prefillFromCurrentAccount() }

    OnEffect(viewModel.effect) { effect ->
        when (effect) {
            // The user signs in on their own Nextcloud — this is the only place the app sends them
            // to a browser, and the only way it ever obtains credentials (CLAUDE.md §5).
            is ConnectEffect.OpenBrowser -> host.onOpenUrl(effect.url)
            ConnectEffect.Connected -> {
                host.navController.popBackStack()
                host.snackbar.showSnackbar("Connected. Fetching your subscriptions…")
            }
            ConnectEffect.Dismiss -> host.navController.popBackStack()
        }
    }
    ConnectDialog(state = state, onEvent = viewModel::onEvent)
}

@Composable
internal fun NamingDestination(
    factory: EpisodeViewModelFactory,
    host: Host,
) {
    val viewModel = viewModel<NamingViewModel>(factory = factory.naming())
    val state by viewModel.state.collectAsStateWithLifecycle()

    NamingScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = { host.navController.popBackStack() },
    )
}
