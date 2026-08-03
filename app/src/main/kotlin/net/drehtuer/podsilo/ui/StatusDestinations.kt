// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import net.drehtuer.podsilo.ui.activity.ActivityEffect
import net.drehtuer.podsilo.ui.activity.ActivityScreen
import net.drehtuer.podsilo.ui.activity.ActivityViewModel
import net.drehtuer.podsilo.ui.errorlog.ErrorLogEffect
import net.drehtuer.podsilo.ui.errorlog.ErrorLogScreen
import net.drehtuer.podsilo.ui.errorlog.ErrorLogViewModel

/** S7 and S8 as navigation destinations. They live in `:app` because both are cross-cutting. */
@Composable
internal fun ActivityDestination(
    factory: EpisodeViewModelFactory,
    host: Host,
) {
    val viewModel = viewModel<ActivityViewModel>(factory = factory.activity())
    val state by viewModel.state.collectAsStateWithLifecycle()

    OnEffect(viewModel.effect) { effect ->
        when (effect) {
            is ActivityEffect.OpenEpisodeDetail -> host.navController.navigate(Routes.episodeDetail(effect.episodeKey))
            ActivityEffect.OpenErrorLog -> host.navController.navigate(Routes.ERROR_LOG)
            ActivityEffect.ChooseFolder -> host.onChooseFolder()
            is ActivityEffect.ShowMessage -> host.snackbar.showSnackbar(effect.text)
        }
    }
    ActivityScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = { host.navController.popBackStack() },
    )
}

@Composable
internal fun ErrorLogDestination(
    factory: EpisodeViewModelFactory,
    host: Host,
) {
    val viewModel = viewModel<ErrorLogViewModel>(factory = factory.errorLog())
    val state by viewModel.state.collectAsStateWithLifecycle()

    OnEffect(viewModel.effect) { effect ->
        when (effect) {
            // The clipboard and the share sheet are Activity concerns, handed down as callbacks.
            is ErrorLogEffect.CopyToClipboard -> {
                host.actions.copy(effect.text)
                host.snackbar.showSnackbar("Log copied")
            }
            is ErrorLogEffect.Share -> host.actions.share(effect.text)
            is ErrorLogEffect.OpenEpisode -> host.navController.navigate(Routes.episodes(effect.feedUrl))
            is ErrorLogEffect.ShowMessage -> host.snackbar.showSnackbar(effect.text)
        }
    }
    ErrorLogScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = { host.navController.popBackStack() },
    )
}
