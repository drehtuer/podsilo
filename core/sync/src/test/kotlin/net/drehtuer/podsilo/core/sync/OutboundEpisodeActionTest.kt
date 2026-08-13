// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.sync

import net.drehtuer.podsilo.core.model.EpisodeLedgerRow
import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.core.model.port.EpisodeActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /**
     * **Changed 2026-08-14.** This asserted `0/0` and was correct against the encoding as documented
     * — and the encoding was wrong about the world: RePod requires `position > 0 && total > 0` to
     * call an episode played, so every `0/0` action rendered as unplayed in Nextcloud for ever.
     *
     * `1` is not a fabricated duration. It is the smallest value that says "there was something and
     * it is finished", which is the claim a skip actually makes; CLAUDE.md §6's rule against
     * inventing a plausible-looking duration is what rules out the alternative of guessing 45
     * minutes.
     */
    @Test
    fun `skipped with unknown duration sends 1, which is a marker and not a duration`() {
        val action = row(LedgerState.SKIPPED, durationSeconds = null).toOutboundAction()

        requireNotNull(action)
        assertEquals(0, action.started)
        assertEquals(1, action.position)
        assertEquals(1, action.total)
    }

    /** The whole point of the value: RePod's own rule has to read it as ended. */
    @Test
    fun `every skip we send reads as played by RePod's rule, duration or not`() {
        listOf(null, 1_800).forEach { duration ->
            val action = requireNotNull(row(LedgerState.SKIPPED, durationSeconds = duration).toOutboundAction())
            val position = requireNotNull(action.position)
            val total = requireNotNull(action.total)

            assertTrue(
                "duration=$duration produced $position/$total, which RePod renders as unplayed",
                position > 0 && total > 0 && position >= total,
            )
        }
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
