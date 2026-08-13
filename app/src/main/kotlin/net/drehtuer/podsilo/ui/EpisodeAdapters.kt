// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.ui

import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import net.drehtuer.podsilo.core.download.DownloadFolderAccess
import net.drehtuer.podsilo.core.download.DownloadFolderState
import net.drehtuer.podsilo.core.download.DownloadTarget
import net.drehtuer.podsilo.core.download.DownloadWorker
import net.drehtuer.podsilo.core.feed.FeedRefreshWorker
import net.drehtuer.podsilo.core.model.Episode
import net.drehtuer.podsilo.core.model.Feed
import net.drehtuer.podsilo.core.model.port.NamingSettings
import net.drehtuer.podsilo.core.naming.DefaultNamingTemplateEngine
import net.drehtuer.podsilo.core.naming.TitleCleanupRule
import net.drehtuer.podsilo.feature.episodes.DownloadFolderLabel
import net.drehtuer.podsilo.feature.episodes.DownloadFolderStatus
import net.drehtuer.podsilo.feature.episodes.DownloadProgress
import net.drehtuer.podsilo.feature.episodes.DownloadSpaceProbe
import net.drehtuer.podsilo.feature.episodes.DownloadWork
import net.drehtuer.podsilo.feature.episodes.DownloadWorkMonitor
import net.drehtuer.podsilo.feature.episodes.EpisodeScheduler
import net.drehtuer.podsilo.feature.episodes.FolderState
import net.drehtuer.podsilo.feature.episodes.NamingPreview
import net.drehtuer.podsilo.work.WorkScheduler
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The screens' ports, implemented over the adapters `:app` already owns. These exist so
 * `:feature:episodes` depends on neither WorkManager nor `:core:download`
 * (`docs/UI.md` §B0.2, `docs/architecture.md` §2).
 */
@Singleton
class WorkEpisodeScheduler
    @Inject
    constructor(
        private val workScheduler: WorkScheduler,
        private val workManager: WorkManager,
    ) : EpisodeScheduler {
        override fun enqueueDownload(
            episodeKey: String,
            userRequested: Boolean,
        ) = workScheduler.enqueueDownload(episodeKey, userRequested)

        override fun cancelDownload(episodeKey: String) = workScheduler.cancelDownload(episodeKey)

        /**
         * Suspends until the refresh reaches a terminal state — **not** a plain delegation to
         * [WorkScheduler.requestFeedRefresh], which returns the instant the work is enqueued. The
         * pull-to-refresh indicator has to stay up for the refresh, not for the microsecond
         * enqueueing takes; that class's KDoc warns about exactly this, so the wait lives here.
         */
        override suspend fun requestFeedRefresh(feedUrl: String?) {
            val name = FeedRefreshWorker.uniqueWorkName(feedUrl)
            workScheduler.requestFeedRefresh(feedUrl)
            workManager
                .getWorkInfosForUniqueWorkFlow(name)
                .first { infos -> infos.isNotEmpty() && infos.all { it.state.isFinished } }
        }
    }

/** Maps `:core:download`'s three-state grant onto the UI's, without dragging the module into it. */
@Singleton
class AccessDownloadFolderStatus
    @Inject
    constructor(
        private val access: DownloadFolderAccess,
    ) : DownloadFolderStatus {
        override fun observe(): Flow<FolderState> =
            access.observe().map {
                when (it) {
                    DownloadFolderState.NotChosen -> FolderState.NOT_CHOSEN
                    is DownloadFolderState.Granted -> FolderState.GRANTED
                    is DownloadFolderState.Revoked -> FolderState.REVOKED
                }
            }
    }

/**
 * The download folder's display name.
 *
 * `DocumentFile.name` is what the picker showed the user, which is the only label they can match
 * against what they see in their file browser. A tree URI's path is not a filesystem path and must
 * never be presented as one (CLAUDE.md §11).
 */
@Singleton
class DocumentFolderLabel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val access: DownloadFolderAccess,
    ) : DownloadFolderLabel {
        override suspend fun current(): String? =
            when (val state = access.current()) {
                is DownloadFolderState.Granted ->
                    DocumentFile.fromTreeUri(context, state.treeUri.toUri())?.name
                else -> null
            }
    }

@Singleton
class TargetSpaceProbe
    @Inject
    constructor(
        private val target: DownloadTarget,
    ) : DownloadSpaceProbe {
        override suspend fun freeBytes(): Long? = target.freeBytes()
    }

/**
 * Renders S1's checklist example through the *real* template engine, so a preview that looks right
 * means downloads will be named right — the point of `docs/UI.md` §4's step 3.
 */
@Singleton
class TemplateNamingPreview
    @Inject
    constructor() : NamingPreview {
        /**
         * A fresh engine per call, exactly as `EpisodeDownloader` builds one: the cleanup rules and
         * the transliteration flag are *settings*, so an engine constructed once could only ever
         * preview one configuration — and the preview would then quietly disagree with the download.
         */
        override fun render(settings: NamingSettings): String =
            // A user-authored cleanup rule is a raw regex and can be malformed. The preview is one
            // line on a checklist card; it must never take the home screen down with it.
            runCatching { resolve(settings) }.getOrElse {
                android.util.Log.w("Podsilo", "naming preview failed", it)
                "—"
            }

        private fun resolve(settings: NamingSettings): String {
            val engine =
                DefaultNamingTemplateEngine(
                    titleCleanupRules =
                        settings.titleCleanupRules.map { TitleCleanupRule(Regex(it.pattern), it.replacement) },
                    transliterate = settings.transliterate,
                )
            val resolved =
                engine.resolve(
                    feed = SAMPLE_FEED,
                    episode = SAMPLE_EPISODE,
                    folderTemplate = settings.folderTemplate,
                    fileTemplate = settings.fileTemplate,
                )
            return "${resolved.folder}/${resolved.fileNameWithoutExtension}.${resolved.extension}"
        }

        private companion object {
            /** A fixed example, not a real episode: the preview must render before any feed is fetched. */
            val SAMPLE_FEED =
                Feed(
                    url = "https://example.org/feed.xml",
                    title = "Der Podcast",
                    imageUrl = null,
                    firstSeenAt = 0,
                    lastRefreshedAt = null,
                    httpEtag = null,
                    httpLastModified = null,
                )
            val SAMPLE_EPISODE =
                Episode(
                    episodeKey = "sample",
                    feedUrl = SAMPLE_FEED.url,
                    guid = "sample",
                    enclosureUrl = "https://example.org/sample.mp3",
                    title = "Warum Hamburg immer regnet",
                    description = null,
                    pubDate = 1_784_019_600_000,
                    durationMs = 2_880_000,
                    link = null,
                )
        }
    }

/** True while any download work is enqueued or running — S1's activity dot. */
internal fun WorkManager.hasActiveWork(): Flow<Boolean> =
    getWorkInfosFlow(
        androidx.work.WorkQuery.fromStates(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING),
    ).map { it.isNotEmpty() }

/**
 * The one place `WorkInfo.progress` becomes something a screen can render (issue #47).
 *
 * Before this, `DownloadWorker` published no progress and nothing observed any, so every
 * `DOWNLOADING` row in the app drew the indeterminate *resuming* bar for the entire download.
 *
 * `WorkInfo` does not expose the unique work name it was enqueued under, which is why
 * `DownloadWorker` tags each request with its episode key — without the tag there is no way to map a
 * queued download back to the episode it belongs to.
 */
@Singleton
class WorkManagerDownloadMonitor
    @Inject
    constructor(
        private val workScheduler: WorkScheduler,
    ) : DownloadWorkMonitor {
        override fun observe(): Flow<DownloadWork> =
            workScheduler.observeDownloadWork().map { infos ->
                val live = mutableSetOf<String>()
                val progress = mutableMapOf<String, DownloadProgress>()
                infos.forEach { info ->
                    val key = DownloadWorker.episodeKeyOf(info.tags) ?: return@forEach
                    live += key
                    // Absent until the worker's first 1 Hz tick, and gone again after process death
                    // — which is exactly the distinction docs/UI.md §B7 renders as
                    // *resuming* rather than as a stale percentage.
                    val bytes = info.progress.getLong(DownloadWorker.KEY_PROGRESS_BYTES, UNSET)
                    if (bytes >= 0) {
                        val total =
                            info.progress.getLong(
                                DownloadWorker.KEY_PROGRESS_TOTAL,
                                DownloadWorker.UNKNOWN_TOTAL,
                            )
                        progress[key] = DownloadProgress(bytes, total.takeIf { it >= 0 })
                    }
                }
                DownloadWork(progress = progress, live = live)
            }

        private companion object {
            const val UNSET = -1L
        }
    }
