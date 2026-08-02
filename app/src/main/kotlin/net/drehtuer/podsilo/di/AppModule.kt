// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.di

import android.content.Context
import androidx.work.WorkManager
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.drehtuer.podsilo.BuildConfig
import net.drehtuer.podsilo.core.download.SyncTrigger
import net.drehtuer.podsilo.core.gpodder.RetrofitGpodderClientFactory
import net.drehtuer.podsilo.core.gpodder.RetrofitNextcloudLoginFlowClient
import net.drehtuer.podsilo.core.model.port.ConnectivityMonitor
import net.drehtuer.podsilo.core.model.port.GpodderClientFactory
import net.drehtuer.podsilo.core.model.port.NextcloudLoginFlowClient
import net.drehtuer.podsilo.feature.settings.ConnectSyncTrigger
import net.drehtuer.podsilo.system.AndroidConnectivityMonitor
import net.drehtuer.podsilo.work.WorkScheduler
import okhttp3.OkHttpClient
import java.time.Clock
import javax.inject.Named
import javax.inject.Singleton

/** Process-wide singletons every other module builds on: HTTP, the clock, WorkManager. */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    /**
     * One shared client for feed fetches, enclosure downloads and the GPodder API: one connection
     * pool, one dispatcher (CLAUDE.md §3 — use OkHttp directly, don't wrap or duplicate it).
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient()

    /** Injected rather than called statically so time-dependent logic stays testable (CLAUDE.md §7). */
    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemDefaultZone()

    @Provides
    @Singleton
    fun provideWorkManager(
        @ApplicationContext context: Context,
    ): WorkManager = WorkManager.getInstance(context)

    @Provides
    @Singleton
    fun provideGpodderClientFactory(okHttpClient: OkHttpClient): GpodderClientFactory =
        RetrofitGpodderClientFactory(okHttpClient)

    /** Login Flow v2 lives on Nextcloud core, before any credentials exist — see the port's KDoc. */
    @Provides
    @Singleton
    fun provideLoginFlowClient(okHttpClient: OkHttpClient): NextcloudLoginFlowClient =
        RetrofitNextcloudLoginFlowClient(okHttpClient)

    /**
     * S5 enqueues the first sync as soon as credentials land, so S1 fills in by itself.
     *
     * An adapter rather than a second interface on `WorkScheduler`: `:core:download` and
     * `:feature:settings` each declare their own one-method port for this, which is the ports rule
     * working as intended — neither module should have to know the other exists.
     */
    @Provides
    @Singleton
    fun provideConnectSyncTrigger(workScheduler: WorkScheduler): ConnectSyncTrigger =
        ConnectSyncTrigger { workScheduler.requestSyncNow() }

    /** Shown in S4's About row. Named because a bare String binding would be ambiguous. */
    @Provides
    @Named("appVersion")
    fun provideAppVersion(): String = BuildConfig.VERSION_NAME
}

/** `@Binds` needs an abstract class, so it can't live in the `object` module above. */
@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    /** `:core:download` asks for a sync pass through this; `:app` is where the worker it schedules lives. */
    @Binds
    abstract fun bindSyncTrigger(workScheduler: WorkScheduler): SyncTrigger

    /**
     * The screens ask "is there a network" before starting a refresh, so an offline pull can answer
     * instantly rather than timing out against every feed (`docs/UI.md` §12.10).
     */
    @Binds
    abstract fun bindConnectivityMonitor(monitor: AndroidConnectivityMonitor): ConnectivityMonitor
}
