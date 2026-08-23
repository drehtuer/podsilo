// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #92: the arithmetic behind the gutter that keeps a swipeable row off the system's own
 * gesture strips.
 *
 * The rule is "never narrower than the design grid, never narrower than the strip the device
 * actually reserves" — a fixed 16 dp would still leave a live swipe surface under the back gesture
 * on any device that reserves more, which is the collision the issue reported.
 */
class ListGutterTest {
    @Test
    fun `a device that reserves nothing still gets the design grid`() {
        assertEquals(ListGutter, gutterFor(0.dp))
    }

    @Test
    fun `a device reserving less than the grid still gets the grid`() {
        assertEquals(16.dp, gutterFor(8.dp))
    }

    @Test
    fun `a device reserving exactly the grid is unchanged`() {
        assertEquals(16.dp, gutterFor(16.dp))
    }

    @Test
    fun `a device reserving more than the grid wins`() {
        assertEquals(24.dp, gutterFor(24.dp))
    }

    @Test
    fun `the minimum is overridable for a caller with a wider grid`() {
        assertEquals(32.dp, gutterFor(systemGestureInset = 10.dp, minimum = 32.dp))
    }
}
