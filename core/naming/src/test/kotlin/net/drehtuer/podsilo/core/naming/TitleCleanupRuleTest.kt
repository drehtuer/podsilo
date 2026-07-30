// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.naming

import org.junit.Assert.assertEquals
import org.junit.Test

class TitleCleanupRuleTest {
    @Test
    fun `no rules leaves the title unchanged`() {
        assertEquals("Episode 42: Something", applyCleanupRules("Episode 42: Something", emptyList()))
    }

    @Test
    fun `strips an episode number prefix`() {
        val rules = listOf(TitleCleanupRule(Regex("""^Ep\.? ?\d+ *[-–—:] *"""), ""))
        assertEquals("Something Interesting", applyCleanupRules("Ep. 142 - Something Interesting", rules))
    }

    @Test
    fun `strips a repeated show name prefix`() {
        val rules = listOf(TitleCleanupRule(Regex("""^Der Podcast: *"""), ""))
        assertEquals("Warum Hamburg immer regnet", applyCleanupRules("Der Podcast: Warum Hamburg immer regnet", rules))
    }

    @Test
    fun `rules apply in order, each seeing the previous result`() {
        val rules =
            listOf(
                TitleCleanupRule(Regex("""^\[Rerun] *"""), ""),
                TitleCleanupRule(Regex("""^Ep\.? ?\d+ *: *"""), ""),
            )
        assertEquals("Something", applyCleanupRules("[Rerun] Ep. 1: Something", rules))
    }

    @Test
    fun `a rule that does not match leaves the title unchanged`() {
        val rules = listOf(TitleCleanupRule(Regex("""^Nonexistent Prefix: """), ""))
        assertEquals("Episode Title", applyCleanupRules("Episode Title", rules))
    }
}
