// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.drehtuer.podsilo.core.download.ArtworkFetcher
import net.drehtuer.podsilo.core.download.AudioTagWriter
import net.drehtuer.podsilo.core.download.DownloadFolderAccess
import net.drehtuer.podsilo.core.download.DownloadNotifications
import net.drehtuer.podsilo.core.download.DownloadTarget
import net.drehtuer.podsilo.core.download.EnclosureDownloader
import net.drehtuer.podsilo.core.download.EpisodeDownloader
import net.drehtuer.podsilo.core.download.SafDownloadTarget
import net.drehtuer.podsilo.core.model.port.SettingsRepository
import okhttp3.OkHttpClient
import java.io.File
import java.time.Clock
import javax.inject.Singleton

/** Sub-directory of the app cache the download pipeline stages files in before the SAF copy. */
private const val DOWNLOAD_CACHE_DIR = "episode-downloads"

@Module
@InstallIn(SingletonComponent::class)
object DownloadModule {
    @Provides
    @Singleton
    fun provideEnclosureDownloader(okHttpClient: OkHttpClient): EnclosureDownloader = EnclosureDownloader(okHttpClient)

    @Provides
    @Singleton
    fun provideAudioTagWriter(): AudioTagWriter = AudioTagWriter()

    /** The only production [DownloadTarget]; the interface exists to keep the pipeline testable (architecture §11). */
    @Provides
    @Singleton
    fun provideDownloadTarget(
        @ApplicationContext context: Context,
        settingsRepository: SettingsRepository,
    ): DownloadTarget = SafDownloadTarget(context, settingsRepository)

    @Provides
    @Singleton
    fun provideDownloadFolderAccess(
        @ApplicationContext context: Context,
        settingsRepository: SettingsRepository,
    ): DownloadFolderAccess = DownloadFolderAccess(context, settingsRepository)

    @Provides
    @Singleton
    fun provideDownloadNotifications(
        @ApplicationContext context: Context,
    ): DownloadNotifications = DownloadNotifications(context)

    // A @Provides method mirrors its target's constructor; see FeedModule's provideFeedRefresher.
    @Suppress("LongParameterList")
    @Provides
    @Singleton
    fun provideEpisodeDownloader(
        enclosureDownloader: EnclosureDownloader,
        audioTagWriter: AudioTagWriter,
        downloadTarget: DownloadTarget,
        @ApplicationContext context: Context,
        clock: Clock,
        artworkFetcher: ArtworkFetcher,
    ): EpisodeDownloader =
        EpisodeDownloader(
            enclosureDownloader = enclosureDownloader,
            audioTagWriter = audioTagWriter,
            downloadTarget = downloadTarget,
            cacheDir = File(context.cacheDir, DOWNLOAD_CACHE_DIR),
            // The device's zone, fixed here rather than re-resolved per call, so one episode always
            // formats to the same date across retries (`docs/architecture.adoc` §11).
            zoneId = clock.zone,
            artworkFetcher = artworkFetcher,
        )

    /** Shares the one OkHttp client, as everything else that speaks HTTP does (CLAUDE.md §3). */
    @Provides
    @Singleton
    fun provideArtworkFetcher(okHttpClient: OkHttpClient): ArtworkFetcher = ArtworkFetcher(okHttpClient)
}
