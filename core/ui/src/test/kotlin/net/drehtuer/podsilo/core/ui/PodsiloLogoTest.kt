// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The brand mark's rules from `docs/logo.md`, checked rather than trusted.
 *
 * The three worth asserting are the ones a reasonable change breaks silently: the mark must not be
 * announced twice when a wordmark is beside it, the lockup's wordmark must stay lowercase, and the
 * 16 dp floor must be a failure rather than an unreadable smudge nobody notices in review.
 *
 * Which of the two drawables a dark scheme selects is deliberately **not** asserted here — a
 * Robolectric render cannot tell the two apart without pixel-reading, and an assertion that only
 * restates the `if` would pass whatever the `if` said.
 */
@RunWith(RobolectricTestRunner::class)
class PodsiloLogoTest {
    @get:Rule
    val compose = createComposeRule()

    private fun render(
        dark: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        compose.setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface { Box { content() } }
            }
        }
    }

    @Test
    fun `the lockup announces the product name once, not once per part`() {
        // §6 asks for a "Podsilo" description on the empty-state lockup because it expected a
        // text-free image. Built from live type it is not text-free — the wordmark is the
        // announcement, and a description on top of it is the "Podsilo Podsilo" §6 was avoiding.
        render { PodsiloLockup(orientation = LockupOrientation.STACKED) }

        compose.onNodeWithText("podsilo").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Podsilo").assertCountEquals(0)
    }

    @Test
    fun `the wordmark is lowercase, never title-cased`() {
        // §2. It is the one rule about the wordmark that a well-meaning edit reliably undoes.
        render { PodsiloLockup() }

        compose.onNodeWithText("podsilo").assertIsDisplayed()
    }

    @Test
    fun `the mark beside its own wordmark is not announced at all`() {
        // §6: `null` in S1's app bar, where the title says it. The lockup is the only text-free
        // instance and therefore the only one that describes itself.
        render { PodsiloMark(contentDescription = null) }

        compose.onAllNodesWithContentDescription("Podsilo").assertCountEquals(0)
    }

    @Test
    fun `the standalone mark can still describe itself when nothing else does`() {
        render { PodsiloMark(contentDescription = "Podsilo") }

        compose.onNodeWithContentDescription("Podsilo").assertIsDisplayed()
    }

    @Test
    fun `below the 16dp floor the mark refuses rather than smudging`() {
        // §1: below 16 dp the bars stop separating — "use nothing rather than a smaller mark". A
        // silent render at 12 dp is a logo nobody can read and everybody ships.
        assertThrows(IllegalArgumentException::class.java) { requireLegibleMarkSize(12.dp) }
    }

    @Test
    fun `every size the design actually uses is above the floor`() {
        listOf(MarkSizeAppBar, MarkSizeLockupHorizontal, MarkSizeLockupStacked)
            .forEach { requireLegibleMarkSize(it) }
    }

    @Test
    fun `the mark renders on a dark scheme as well as a light one`() {
        // Not which drawable — only that a dark surface does not crash or drop the mark. The
        // two-colour build's ink vessel is invisible there, which is why the selection exists.
        render(dark = true) { PodsiloMark(contentDescription = "Podsilo") }

        compose.onNodeWithContentDescription("Podsilo").assertIsDisplayed()
    }
}
