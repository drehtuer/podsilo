// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import net.drehtuer.podsilo.core.feed.FeedFetcher
import net.drehtuer.podsilo.core.feed.FeedRefresher
import net.drehtuer.podsilo.core.feed.FeedXmlParser
import net.drehtuer.podsilo.core.feed.MarkOldEpisodesRule
import net.drehtuer.podsilo.core.model.port.EpisodeLedgerRepository
import net.drehtuer.podsilo.core.model.port.EpisodeListRepository
import net.drehtuer.podsilo.core.model.port.EpisodeRepository
import net.drehtuer.podsilo.core.model.port.FeedRepository
import net.drehtuer.podsilo.core.model.port.LogRepository
import net.drehtuer.podsilo.core.model.port.SettingsRepository
import net.drehtuer.podsilo.core.model.port.SyncTrigger
import okhttp3.OkHttpClient
import java.time.Clock
import javax.inject.Singleton

/** Feed fetching and parsing. Note there is no GPodder dependency here — refreshing never syncs. */
@Module
@InstallIn(SingletonComponent::class)
object FeedModule {
    @Provides
    @Singleton
    fun provideFeedFetcher(okHttpClient: OkHttpClient): FeedFetcher = FeedFetcher(okHttpClient)

    @Provides
    @Singleton
    fun provideFeedXmlParser(): FeedXmlParser = FeedXmlParser()

    @Provides
    @Singleton
    fun provideMarkOldEpisodesRule(
        ledgerRepository: EpisodeLedgerRepository,
        listRepository: EpisodeListRepository,
        settingsRepository: SettingsRepository,
        syncTrigger: SyncTrigger,
        clock: Clock,
    ): MarkOldEpisodesRule =
        MarkOldEpisodesRule(ledgerRepository, listRepository, settingsRepository, syncTrigger, clock)

    // A @Provides method for a composition root mirrors that root's dependency list; see
    // FeedRefresher's own KDoc for why that list is what it is.
    @Suppress("LongParameterList")
    @Provides
    @Singleton
    fun provideFeedRefresher(
        feedRepository: FeedRepository,
        episodeRepository: EpisodeRepository,
        feedFetcher: FeedFetcher,
        feedXmlParser: FeedXmlParser,
        clock: Clock,
        logRepository: LogRepository,
        markOldEpisodesRule: MarkOldEpisodesRule,
    ): FeedRefresher =
        FeedRefresher(
            feedRepository = feedRepository,
            episodeRepository = episodeRepository,
            feedFetcher = feedFetcher,
            feedXmlParser = feedXmlParser,
            clock = clock,
            logRepository = logRepository,
            markOldEpisodesRule = markOldEpisodesRule,
        )
}
