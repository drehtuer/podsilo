// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.download

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import net.drehtuer.podsilo.core.model.port.NamingSettings
import net.drehtuer.podsilo.core.model.port.NextcloudAccount
import net.drehtuer.podsilo.core.model.port.NextcloudCredentials
import net.drehtuer.podsilo.core.model.port.OlderThan
import net.drehtuer.podsilo.core.model.port.SettingsRepository
import net.drehtuer.podsilo.core.model.port.SwipeMapping
import net.drehtuer.podsilo.core.model.port.ThemePreference

/**
 * A [SettingsRepository] that reports one already-granted download folder and defaults for
 * everything else.
 *
 * Shared by the two device tests that write through real SAF ([SafDownloadTargetInstrumentedTest]
 * and [DownloadPipelineInstrumentedTest]) rather than declared twice: the folder is the only setting
 * either of them reads, and two copies of a fake this long drift the moment the port gains a method.
 */
internal class GrantedFolderSettings(
    private val uri: String,
) : SettingsRepository {
    override fun observeDownloadFolderUri(): Flow<String?> = MutableStateFlow(uri)

    override suspend fun setDownloadFolderUri(uri: String?) = Unit

    override fun observeNaming(): Flow<NamingSettings> = MutableStateFlow(NamingSettings())

    override suspend fun setNaming(settings: NamingSettings) = Unit

    override fun observeSyncIntervalMinutes(): Flow<Long> = MutableStateFlow(60)

    override suspend fun setSyncIntervalMinutes(minutes: Long) = Unit

    override fun observeTheme(): Flow<ThemePreference> = MutableStateFlow(ThemePreference.SYSTEM)

    override suspend fun setTheme(theme: ThemePreference) = Unit

    override fun observeSwipeMapping(): Flow<SwipeMapping> = MutableStateFlow(SwipeMapping())

    override suspend fun setSwipeMapping(mapping: SwipeMapping) = Unit

    override fun observeAllowMobileData(): Flow<Boolean> = MutableStateFlow(false)

    override suspend fun setAllowMobileData(allowed: Boolean) = Unit

    override fun observeDeliveredClearedAt(): Flow<Long> = MutableStateFlow(0L)

    override suspend fun setDeliveredClearedAt(millis: Long) = Unit

    override fun observeMarkOldOlderThan(): Flow<OlderThan> = MutableStateFlow(OlderThan.OFF)

    override suspend fun setMarkOldOlderThan(value: OlderThan) = Unit

    override fun observeNextcloudAccount(): Flow<NextcloudAccount?> = MutableStateFlow(null)

    override suspend fun nextcloudCredentials(): NextcloudCredentials? = null

    override suspend fun setNextcloudCredentials(credentials: NextcloudCredentials?) = Unit
}
