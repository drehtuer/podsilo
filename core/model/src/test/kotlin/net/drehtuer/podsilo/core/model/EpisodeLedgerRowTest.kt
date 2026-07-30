// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeLedgerRowTest {
    private fun row(
        episodeKey: String,
        enclosureUrl: String,
    ) = EpisodeLedgerRow(
        episodeKey = episodeKey,
        feedUrl = "https://example.com/feed.xml",
        enclosureUrl = enclosureUrl,
        state = LedgerState.DOWNLOADED,
        actionedAt = 0L,
        syncedToServer = false,
        attempts = 0,
        lastError = null,
        writtenFileName = null,
    )

    @Test
    fun `guid is derived when episodeKey differs from enclosure url`() {
        val ledgerRow = row(episodeKey = "guid-123", enclosureUrl = "https://example.com/ep.mp3")

        assertEquals("guid-123", ledgerRow.guid)
    }

    @Test
    fun `guid is null when episodeKey is the enclosure url fallback`() {
        val ledgerRow =
            row(
                episodeKey = "https://example.com/ep.mp3",
                enclosureUrl = "https://example.com/ep.mp3",
            )

        assertNull(ledgerRow.guid)
    }
}
