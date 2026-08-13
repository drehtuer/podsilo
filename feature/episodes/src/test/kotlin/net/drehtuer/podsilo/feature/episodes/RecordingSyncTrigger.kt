// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import net.drehtuer.podsilo.core.model.port.SyncTrigger

/** Counts sync requests, so a test can assert *one* pass for a bulk write rather than one per row. */
class RecordingSyncTrigger : SyncTrigger {
    var requests = 0
        private set

    override fun requestSyncNow() {
        requests++
    }
}
