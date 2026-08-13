// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import net.drehtuer.podsilo.feature.episodes.EpisodeListEvent
import net.drehtuer.podsilo.feature.episodes.EpisodeListScreen
import net.drehtuer.podsilo.feature.episodes.EpisodeListViewModel
import net.drehtuer.podsilo.feature.episodes.EpisodeUiAction
import net.drehtuer.podsilo.feature.episodes.PodcastListEffect
import net.drehtuer.podsilo.feature.episodes.PodcastListScreen
import net.drehtuer.podsilo.feature.episodes.PodcastListViewModel
import net.drehtuer.podsilo.feature.episodes.SnackbarText

/**
 * All eight of `docs/UI.md`'s screens, and every route between them.
 *
 */
@Composable
fun PodsiloNavHost(
    factory: EpisodeViewModelFactory,
    actions: HostActions,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val snackbar = remember { SnackbarHostState() }
    val host = remember(navController, snackbar, actions) { Host(navController, snackbar, actions) }

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
            // A dialog destination: S5 sits over S4 rather than replacing it (docs/UI.md §B9).
            dialog(Routes.CONNECT) { ConnectDestination(factory, host) }
            composable(Routes.NAMING) { NamingDestination(factory, host) }
            composable(Routes.ACTIVITY) { ActivityDestination(factory, host) }
            composable(Routes.ERROR_LOG) { ErrorLogDestination(factory, host) }
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
    val actions: HostActions,
) {
    val onOpenUrl: (String) -> Unit get() = actions.openUrl
    val onChooseFolder: () -> Unit get() = actions.chooseFolder
    val onCreateBackupFile: (String, (String) -> Unit) -> Unit get() = actions.createBackupFile
    val onOpenBackupFile: ((String) -> Unit) -> Unit get() = actions.openBackupFile
}

/**
 * Everything only an `Activity` can do: launch the SAF picker, open a link, reach the clipboard and
 * the share sheet. Grouped because every one of them is the same kind of thing and they always
 * travel together.
 */
data class HostActions(
    val openUrl: (String) -> Unit,
    val chooseFolder: () -> Unit,
    val copy: (String) -> Unit,
    val share: (String) -> Unit,
    /**
     * `CreateDocument`, then the chosen URI back to the caller. A callback rather than a plain
     * launch because, unlike the download folder, the result belongs to a view model rather than to
     * the activity — S4 has to report what happened to the file the user picked.
     */
    val createBackupFile: (suggestedName: String, onPicked: (String) -> Unit) -> Unit,
    val openBackupFile: (onPicked: (String) -> Unit) -> Unit,
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
            PodcastListEffect.OpenActivity -> host.navController.navigate(Routes.ACTIVITY)
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
            EpisodeListEffect.NavigateUp -> host.navController.popBackStack()
            EpisodeListEffect.OpenActivity -> host.navController.navigate(Routes.ACTIVITY)
            is EpisodeListEffect.OpenUrl -> host.onOpenUrl(effect.url)
            is EpisodeListEffect.CopyLink -> host.actions.copy(effect.url)
            EpisodeListEffect.ResolvePausedQueue -> host.onChooseFolder()
            is EpisodeListEffect.ShowMessage -> host.snackbar.showMessage(effect.text)
            // The one snackbar with a reply. The view model still owns the window (UI.md §12.3); this
            // only reports a tap, and a tap that arrives after the write finds nothing to undo.
            is EpisodeListEffect.ShowUndo -> {
                val result =
                    host.snackbar.showSnackbar(
                        message = effect.action.undoMessage(),
                        actionLabel = "Undo",
                        duration = SnackbarDuration.Short,
                    )
                if (result == SnackbarResult.ActionPerformed) viewModel.onEvent(EpisodeListEvent.UndoRequested)
            }
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
            is EpisodeDetailEffect.CopyLink -> host.actions.copy(effect.url)
            EpisodeDetailEffect.OpenErrorLog -> host.navController.navigate(Routes.ERROR_LOG)
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

private suspend fun SnackbarHostState.showMessage(text: SnackbarText) {
    showSnackbar(text.render())
}

/**
 * What the undo snackbar says a swipe did.
 *
 * Past tense, because as far as the user is concerned it *has* happened — the row already shows it.
 * That the write is still five seconds away is the app's business, not theirs (UI.md §12.3).
 */
private fun EpisodeUiAction.undoMessage(): String =
    when (this) {
        EpisodeUiAction.MARK_AS_PLAYED -> "Marked as played"
        else -> "Queued for download"
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
