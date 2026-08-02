// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.Flow
import net.drehtuer.podsilo.feature.episodes.EpisodeDetailEffect
import net.drehtuer.podsilo.feature.episodes.EpisodeDetailSheet
import net.drehtuer.podsilo.feature.episodes.EpisodeDetailViewModel
import net.drehtuer.podsilo.feature.episodes.EpisodeListEffect
import net.drehtuer.podsilo.feature.episodes.EpisodeListScreen
import net.drehtuer.podsilo.feature.episodes.EpisodeListViewModel
import net.drehtuer.podsilo.feature.episodes.PodcastListEffect
import net.drehtuer.podsilo.feature.episodes.PodcastListScreen
import net.drehtuer.podsilo.feature.episodes.PodcastListViewModel
import net.drehtuer.podsilo.feature.episodes.SnackbarText

/**
 * The screens that exist: S1, S2 and S3.
 *
 * S4–S8 are designed but unbuilt, so the events that would open them surface a snackbar naming the
 * missing screen rather than silently doing nothing — the difference between an app that is
 * unfinished and one that looks broken.
 */
@Composable
fun PodsiloNavHost(
    factory: EpisodeViewModelFactory,
    onOpenUrl: (String) -> Unit,
    onChooseFolder: () -> Unit,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val snackbar = remember { SnackbarHostState() }
    val host =
        remember(navController, snackbar, onOpenUrl, onChooseFolder) {
            Host(navController, snackbar, onOpenUrl, onChooseFolder)
        }

    Box(modifier = modifier.fillMaxSize()) {
        NavHost(navController = navController, startDestination = Routes.PODCASTS) {
            composable(Routes.PODCASTS) {
                PodcastsDestination(factory, host)
            }
            composable(
                Routes.EPISODES,
                arguments = listOf(navArgument(Routes.ARG_FEED_URL) { type = NavType.StringType }),
            ) { entry ->
                val feedUrl = Uri.decode(entry.arguments?.getString(Routes.ARG_FEED_URL).orEmpty())
                EpisodesDestination(feedUrl, factory, host)
            }
            composable(Routes.SETTINGS) { SettingsDestination(factory, host) }
            // A dialog destination: S5 sits over S4 rather than replacing it (docs/UI_interface.md §9).
            dialog(Routes.CONNECT) { ConnectDestination(factory, host) }
            composable(Routes.NAMING) { NamingDestination(factory, host) }
            composable(
                Routes.EPISODE_DETAIL,
                arguments = listOf(navArgument(Routes.ARG_EPISODE_KEY) { type = NavType.StringType }),
            ) { entry ->
                val key = Uri.decode(entry.arguments?.getString(Routes.ARG_EPISODE_KEY).orEmpty())
                DetailDestination(key, factory, host)
            }
        }
        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/**
 * What a destination needs from the activity around it: where to go, where to say things, and the
 * two actions only an Activity can perform. Grouped rather than passed as four parameters each
 * time, because every destination takes the same set.
 */
internal data class Host(
    val navController: NavHostController,
    val snackbar: SnackbarHostState,
    val onOpenUrl: (String) -> Unit,
    val onChooseFolder: () -> Unit,
)

@Composable
private fun PodcastsDestination(
    factory: EpisodeViewModelFactory,
    host: Host,
) {
    val viewModel = viewModel<PodcastListViewModel>(factory = factory.podcastList())
    val state by viewModel.state.collectAsStateWithLifecycle()

    OnEffect(viewModel.effect) { effect ->
        when (effect) {
            is PodcastListEffect.OpenEpisodes -> host.navController.navigate(Routes.episodes(effect.feedUrl))
            PodcastListEffect.ChooseFolder, PodcastListEffect.ResolvePausedQueue -> host.onChooseFolder()
            PodcastListEffect.OpenSettings -> host.navController.navigate(Routes.SETTINGS)
            PodcastListEffect.OpenConnect -> host.navController.navigate(Routes.CONNECT)
            PodcastListEffect.OpenNaming -> host.navController.navigate(Routes.NAMING)
            PodcastListEffect.OpenActivity -> host.snackbar.notBuiltYet("Activity")
            is PodcastListEffect.ShowMessage -> host.snackbar.showMessage(effect.text)
        }
    }
    PodcastListScreen(state = state, onEvent = viewModel::onEvent)
}

@Composable
private fun EpisodesDestination(
    feedUrl: String,
    factory: EpisodeViewModelFactory,
    host: Host,
) {
    val viewModel = viewModel<EpisodeListViewModel>(factory = factory.episodeList(feedUrl))
    val state by viewModel.state.collectAsStateWithLifecycle()

    OnEffect(viewModel.effect) { effect ->
        when (effect) {
            is EpisodeListEffect.OpenDetail -> host.navController.navigate(Routes.episodeDetail(effect.episodeKey))
            is EpisodeListEffect.OpenUrl -> host.onOpenUrl(effect.url)
            EpisodeListEffect.ResolvePausedQueue -> host.onChooseFolder()
            is EpisodeListEffect.ShowMessage -> host.snackbar.showMessage(effect.text)
        }
    }
    EpisodeListScreen(state = state, onEvent = viewModel::onEvent)
}

@Composable
private fun DetailDestination(
    episodeKey: String,
    factory: EpisodeViewModelFactory,
    host: Host,
) {
    val viewModel = viewModel<EpisodeDetailViewModel>(factory = factory.episodeDetail(episodeKey))
    val state by viewModel.state.collectAsStateWithLifecycle()

    OnEffect(viewModel.effect) { effect ->
        when (effect) {
            EpisodeDetailEffect.Close -> host.navController.popBackStack()
            // Not navigation: the sheet stays open behind the browser (docs/UI.md §6).
            is EpisodeDetailEffect.OpenUrl -> host.onOpenUrl(effect.url)
            EpisodeDetailEffect.OpenErrorLog -> host.snackbar.notBuiltYet("The error log")
            is EpisodeDetailEffect.ShowMessage -> host.snackbar.showMessage(effect.text)
        }
    }
    // An episode pruned while its sheet is open resolves to null — unsubscribing a feed deletes its
    // cached episodes (CLAUDE.md §5). Closing beats rendering a sheet with nothing in it.
    state?.let { EpisodeDetailSheet(state = it, onEvent = viewModel::onEvent) }
}

/**
 * Collects a view model's one-shot effects for as long as the destination is composed.
 *
 * Keyed on the flow rather than on `Unit`: a destination that is popped and re-entered gets a new
 * view model, and re-collecting the old one's channel would deliver its effects to the new screen.
 */
@Composable
internal fun <T> OnEffect(
    effects: Flow<T>,
    handle: suspend (T) -> Unit,
) {
    LaunchedEffect(effects) { effects.collect { handle(it) } }
}

internal suspend fun SnackbarHostState.notBuiltYet(screen: String) {
    showSnackbar("$screen is not built yet.")
}

private suspend fun SnackbarHostState.showMessage(text: SnackbarText) {
    showSnackbar(text.render())
}

/**
 * The one place a [SnackbarText] becomes words. Kept out of `:feature:episodes` because the module
 * has no resources of its own; when strings move to `strings.xml`, this is the function that changes.
 */
private fun SnackbarText.render(): String =
    when (this) {
        is SnackbarText.Queued -> if (count == 1) "Queued 1 episode" else "Queued $count episodes"
        is SnackbarText.BulkApplied -> if (count == 1) "Marked 1 episode as played" else "Marked $count as played"
        is SnackbarText.AlreadyInFolder -> "Already in your folder: $fileName"
        is SnackbarText.DownloadFailed -> "Download failed: ${cause.name.lowercase().replace('_', ' ')}"
        SnackbarText.LinkCopied -> "Link copied"
        SnackbarText.Offline -> "No network connection"
        SnackbarText.AlreadyUpToDate -> "Already up to date"
    }
