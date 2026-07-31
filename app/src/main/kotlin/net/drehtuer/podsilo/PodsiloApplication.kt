// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
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
    Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var workScheduler: WorkScheduler

    @Inject
    lateinit var settingsRepository: SettingsRepository

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
