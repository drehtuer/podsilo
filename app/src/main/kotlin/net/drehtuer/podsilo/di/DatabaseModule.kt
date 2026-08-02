// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.drehtuer.podsilo.core.database.DatabaseArchiveStore
import net.drehtuer.podsilo.core.database.PODSILO_MIGRATIONS
import net.drehtuer.podsilo.core.database.PodsiloDatabase
import net.drehtuer.podsilo.core.database.repository.EpisodeLedgerRepositoryImpl
import net.drehtuer.podsilo.core.database.repository.EpisodeListRepositoryImpl
import net.drehtuer.podsilo.core.database.repository.EpisodeRepositoryImpl
import net.drehtuer.podsilo.core.database.repository.FeedRepositoryImpl
import net.drehtuer.podsilo.core.database.repository.LogRepositoryImpl
import net.drehtuer.podsilo.core.database.repository.SyncStateRepositoryImpl
import net.drehtuer.podsilo.core.model.port.DatabaseArchive
import net.drehtuer.podsilo.core.model.port.EpisodeLedgerRepository
import net.drehtuer.podsilo.core.model.port.EpisodeListRepository
import net.drehtuer.podsilo.core.model.port.EpisodeRepository
import net.drehtuer.podsilo.core.model.port.FeedRepository
import net.drehtuer.podsilo.core.model.port.LogRepository
import net.drehtuer.podsilo.core.model.port.SyncStateRepository
import java.time.Clock
import javax.inject.Singleton

/**
 * Binds the four persistence ports from `:core:model` to their Room adapters — one of the few
 * places in the app that knows Room exists at all (`docs/architecture.md` §2).
 *
 * `@Provides` rather than `@Binds` because the adapters are plain constructor-injectable classes
 * with no Hilt annotations of their own, which is what keeps `:core:database` free of DI plumbing.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): PodsiloDatabase =
        Room
            .databaseBuilder(context, PodsiloDatabase::class.java, PodsiloDatabase.DATABASE_NAME)
            // No fallbackToDestructiveMigration, deliberately: it would drop episode_ledger, and
            // every episode the author had already handled would come back as new — here and, after
            // the next sync, on every other client (CLAUDE.md section 5).
            .apply { PODSILO_MIGRATIONS.forEach { addMigrations(it) } }
            .build()

    @Provides
    @Singleton
    fun provideFeedRepository(database: PodsiloDatabase): FeedRepository = FeedRepositoryImpl(database.feedDao())

    @Provides
    @Singleton
    fun provideEpisodeRepository(database: PodsiloDatabase): EpisodeRepository =
        EpisodeRepositoryImpl(database.episodeDao())

    @Provides
    @Singleton
    fun provideEpisodeLedgerRepository(database: PodsiloDatabase): EpisodeLedgerRepository =
        EpisodeLedgerRepositoryImpl(database.episodeLedgerDao())

    @Provides
    @Singleton
    fun provideEpisodeListRepository(database: PodsiloDatabase): EpisodeListRepository =
        EpisodeListRepositoryImpl(database.episodeListDao())

    @Provides
    @Singleton
    fun provideSyncStateRepository(database: PodsiloDatabase): SyncStateRepository =
        SyncStateRepositoryImpl(database.syncStateDao())

    /** The zip backup of the whole database (`docs/decisions/0018`). */
    @Provides
    @Singleton
    fun provideDatabaseArchive(
        @ApplicationContext context: Context,
        database: PodsiloDatabase,
    ): DatabaseArchive = DatabaseArchiveStore(context, database)

    @Provides
    @Singleton
    fun provideLogRepository(
        database: PodsiloDatabase,
        clock: Clock,
    ): LogRepository = LogRepositoryImpl(database.logDao(), clock)
}
