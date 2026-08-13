// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import net.drehtuer.podsilo.core.model.port.LogCategory
import net.drehtuer.podsilo.core.model.port.LogEntry
import net.drehtuer.podsilo.core.model.port.LogRepository
import net.drehtuer.podsilo.core.model.port.NewLogEntry

/**
 * Keeps what was recorded so a test can assert on it. Deliberately does **not** collapse or redact:
 * both belong to the Room implementation and are tested there, and a fake that quietly reproduced
 * them would let a missing redaction pass here.
 */
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
