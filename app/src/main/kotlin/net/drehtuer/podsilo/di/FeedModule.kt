// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import net.drehtuer.podsilo.core.feed.FeedFetcher
import net.drehtuer.podsilo.core.feed.FeedRefresher
import net.drehtuer.podsilo.core.feed.FeedXmlParser
import net.drehtuer.podsilo.core.model.port.EpisodeRepository
import net.drehtuer.podsilo.core.model.port.FeedRepository
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
    fun provideFeedRefresher(
        feedRepository: FeedRepository,
        episodeRepository: EpisodeRepository,
        feedFetcher: FeedFetcher,
        feedXmlParser: FeedXmlParser,
        clock: Clock,
    ): FeedRefresher = FeedRefresher(feedRepository, episodeRepository, feedFetcher, feedXmlParser, clock)
}
