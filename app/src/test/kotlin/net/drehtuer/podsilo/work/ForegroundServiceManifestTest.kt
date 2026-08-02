// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.work

import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The regression test for the crash that ended the first real download attempt on a Pixel 5.
 *
 * `DownloadWorker` calls `setForeground(... FOREGROUND_SERVICE_TYPE_DATA_SYNC)`. WorkManager serves
 * that through its own `SystemForegroundService`, whose manifest entry declares **no**
 * `foregroundServiceType` at all — and from API 34 the type passed to `startForeground()` must be a
 * subset of the one declared for the service. It was not, so the system threw
 *
 * ```
 * foregroundServiceType 0x00000001 is not a subset of
 * foregroundServiceType attribute 0x00000000 in service element of manifest file
 * ```
 *
 * inside its own service dispatch — a hard process crash rather than a failure the worker could
 * catch. Every download died the instant it started, and the process crash-looped as WorkManager
 * retried.
 *
 * This asserts the merged manifest, which is the only artefact that can be wrong here: the
 * `FOREGROUND_SERVICE_DATA_SYNC` permission was already declared and correct, and was not enough.
 * Robolectric reads the same merged manifest the device installs, so this is a Tier 1 test for a
 * defect that previously needed real hardware to notice.
 */
@RunWith(RobolectricTestRunner::class)
class ForegroundServiceManifestTest {
    @Test
    fun `WorkManager's foreground service declares the dataSync type`() {
        val context = RuntimeEnvironment.getApplication()
        val component =
            ComponentName(context, "androidx.work.impl.foreground.SystemForegroundService")

        val service =
            context.packageManager.getServiceInfo(
                component,
                PackageManager.GET_META_DATA,
            )

        assertNotEquals(
            "SystemForegroundService declares no foregroundServiceType — setForeground() will " +
                "crash the process on API 34+",
            0,
            service.foregroundServiceType,
        )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            service.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }
}
