// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.drehtuer.podsilo.core.model.port.SettingsRepository
import net.drehtuer.podsilo.work.WorkScheduler
import javax.inject.Inject

/**
 * Hilt's application entry point, and WorkManager's: the workers are `@HiltWorker`s, so WorkManager
 * has to be handed a [HiltWorkerFactory] instead of using its default no-arg factory. The manifest
 * removes WorkManager's automatic `androidx.startup` initializer for the same reason — this
 * [Configuration] must be the one that wins.
 */
@HiltAndroidApp
class PodsiloApplication :
    Application(),
    Configuration.Provider,
    SingletonImageLoader.Factory {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var workScheduler: WorkScheduler

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var httpClient: dagger.Lazy<okhttp3.OkHttpClient>

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Coil, on the OkHttp this app already pins.
     *
     * That reuse is the whole reason `coil-network-okhttp` is in the catalog rather than Coil's
     * default engine (`UI.adoc` §18) — one connection pool, one TLS config, one place where
     * timeouts are set. Without this factory Coil silently builds a second HTTP stack.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader
            .Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { httpClient.get() })) }
            .crossfade(true)
            .build()

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // Periodic sync and refresh are best-effort by design (CLAUDE.md §11's Doze note) — the
        // schedule is (re-)declared on every launch so a changed interval takes effect, and the UI
        // always offers a manual refresh regardless.
        applicationScope.launch {
            workScheduler.schedulePeriodicWork(settingsRepository.observeSyncIntervalMinutes().first())
        }
    }
}
