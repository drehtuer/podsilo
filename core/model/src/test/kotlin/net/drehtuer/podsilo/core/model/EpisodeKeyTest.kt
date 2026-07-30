// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class EpisodeKeyTest {
    @Test
    fun `uses guid when present`() {
        assertEquals(
            "guid-123",
            episodeKey(guid = "guid-123", enclosureUrl = "https://example.com/ep.mp3"),
        )
    }

    @Test
    fun `falls back to enclosure url when guid is absent`() {
        assertEquals(
            "https://example.com/ep.mp3",
            episodeKey(guid = null, enclosureUrl = "https://example.com/ep.mp3"),
        )
    }
}
