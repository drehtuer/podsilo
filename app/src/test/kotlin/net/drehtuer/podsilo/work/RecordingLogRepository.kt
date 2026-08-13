// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.work

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import net.drehtuer.podsilo.core.model.port.LogCategory
import net.drehtuer.podsilo.core.model.port.LogEntry
import net.drehtuer.podsilo.core.model.port.LogRepository
import net.drehtuer.podsilo.core.model.port.NewLogEntry

/** Keeps what a sync pass recorded, so a worker test can assert the pass reached the log at all. */
class RecordingLogRepository : LogRepository {
    val recorded = mutableListOf<NewLogEntry>()

    override fun observe(category: LogCategory?): Flow<List<LogEntry>> = MutableStateFlow(emptyList())

    override suspend fun record(entry: NewLogEntry) {
        recorded += entry
    }

    override suspend fun clear() {
        recorded.clear()
    }

    override suspend fun exportPlainText(): String = recorded.joinToString("\n") { it.message }
}
