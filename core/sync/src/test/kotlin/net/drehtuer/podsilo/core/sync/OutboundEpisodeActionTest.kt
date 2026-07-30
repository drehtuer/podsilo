// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.sync

import net.drehtuer.podsilo.core.model.EpisodeLedgerRow
import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.core.model.port.EpisodeActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class OutboundEpisodeActionTest {
    private fun row(
        state: LedgerState,
        durationSeconds: Int? = null,
        guid: String? = "guid-123",
        enclosureUrl: String = "https://example.com/ep.mp3",
    ) = EpisodeLedgerRow(
        episodeKey = guid ?: enclosureUrl,
        feedUrl = "https://example.com/feed.xml",
        enclosureUrl = enclosureUrl,
        state = state,
        actionedAt = Instant.parse("2026-07-14T09:00:00Z").toEpochMilli(),
        syncedToServer = false,
        attempts = 0,
        lastError = null,
        writtenFileName = null,
        durationSeconds = durationSeconds,
    )

    @Test
    fun `downloaded maps to a DOWNLOAD action with no playback fields`() {
        val action = row(LedgerState.DOWNLOADED).toOutboundAction()

        requireNotNull(action)
        assertEquals(EpisodeActionType.DOWNLOAD, action.action)
        assertEquals("https://example.com/feed.xml", action.podcast)
        assertEquals("https://example.com/ep.mp3", action.episode)
        assertEquals("guid-123", action.guid)
        assertEquals("2026-07-14T09:00:00", action.timestamp)
        assertNull(action.started)
        assertNull(action.position)
        assertNull(action.total)
    }

    @Test
    fun `skipped with a known duration encodes started 0, position equals total`() {
        val action = row(LedgerState.SKIPPED, durationSeconds = 1800).toOutboundAction()

        requireNotNull(action)
        assertEquals(EpisodeActionType.PLAY, action.action)
        assertEquals(0, action.started)
        assertEquals(1800, action.position)
        assertEquals(1800, action.total)
    }

    @Test
    fun `skipped with unknown duration sends 0, not a fabricated value`() {
        val action = row(LedgerState.SKIPPED, durationSeconds = null).toOutboundAction()

        requireNotNull(action)
        assertEquals(0, action.started)
        assertEquals(0, action.position)
        assertEquals(0, action.total)
    }

    @Test
    fun `a guid-less row derives guid as null on the outbound action`() {
        val ledgerRow = row(LedgerState.DOWNLOADED, guid = null, enclosureUrl = "https://example.com/ep.mp3")
        val action = ledgerRow.toOutboundAction()

        requireNotNull(action)
        assertNull(action.guid)
    }

    @Test
    fun `local-only states never produce an outbound action`() {
        val localOnlyStates =
            listOf(
                LedgerState.QUEUED,
                LedgerState.DOWNLOADING,
                LedgerState.ERROR,
                LedgerState.HANDLED_REMOTELY,
            )
        for (state in localOnlyStates) {
            assertNull("state=$state", row(state).toOutboundAction())
        }
    }
}
