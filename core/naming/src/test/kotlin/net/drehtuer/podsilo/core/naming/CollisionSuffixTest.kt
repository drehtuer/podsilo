// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.naming

import org.junit.Assert.assertEquals
import org.junit.Test

class CollisionSuffixTest {
    @Test
    fun `candidate is returned unchanged when there is no collision`() {
        assertEquals("Episode Title", nextAvailableName("Episode Title", existingNames = emptySet()))
    }

    @Test
    fun `first collision gets suffix 2`() {
        assertEquals(
            "Episode Title (2)",
            nextAvailableName("Episode Title", existingNames = setOf("Episode Title")),
        )
    }

    @Test
    fun `suffix increments deterministically past existing collisions`() {
        val existingNames = setOf("Episode Title", "Episode Title (2)", "Episode Title (3)")

        assertEquals("Episode Title (4)", nextAvailableName("Episode Title", existingNames))
    }

    @Test
    fun `a gap in existing suffixes is not reused -- suffixing is deterministic, not first-fit`() {
        assertEquals(
            "Episode Title (2)",
            nextAvailableName(
                "Episode Title",
                existingNames = setOf("Episode Title", "Episode Title (3)"),
            ),
        )
    }
}
