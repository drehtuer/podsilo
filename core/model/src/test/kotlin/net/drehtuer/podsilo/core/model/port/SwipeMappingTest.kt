// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.model.port

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The invariant under test is `docs/UI.md` §7's: the two directions never hold the same action, and
 * picking a taken one **swaps** rather than being rejected. If this ever breaks, one triage verb
 * becomes unreachable by gesture while the swipe background still advertises it.
 */
class SwipeMappingTest {
    @Test
    fun `defaults are download right, mark-as-played left`() {
        val mapping = SwipeMapping()
        assertEquals(SwipeAction.DOWNLOAD, mapping.actionFor(SwipeDirection.RIGHT))
        assertEquals(SwipeAction.MARK_AS_PLAYED, mapping.actionFor(SwipeDirection.LEFT))
    }

    @Test
    fun `assigning the other direction's action swaps them`() {
        val swapped = SwipeMapping().with(SwipeDirection.RIGHT, SwipeAction.MARK_AS_PLAYED)

        assertEquals(SwipeAction.MARK_AS_PLAYED, swapped.actionFor(SwipeDirection.RIGHT))
        assertEquals(SwipeAction.DOWNLOAD, swapped.actionFor(SwipeDirection.LEFT))
    }

    @Test
    fun `assigning an action a direction already holds is a no-op, not a self-swap`() {
        val mapping = SwipeMapping().with(SwipeDirection.RIGHT, SwipeAction.DOWNLOAD)
        assertEquals(SwipeMapping(), mapping)
    }

    @Test
    fun `disabling one direction leaves the other alone`() {
        val mapping = SwipeMapping().with(SwipeDirection.LEFT, SwipeAction.NONE)

        assertEquals(SwipeAction.DOWNLOAD, mapping.actionFor(SwipeDirection.RIGHT))
        assertEquals(SwipeAction.NONE, mapping.actionFor(SwipeDirection.LEFT))
    }

    @Test
    fun `both directions may be disabled at once`() {
        // NONE is exempt from the uniqueness rule — "no swipe actions at all" is a legal choice,
        // since every swipe has a visible equivalent in the overflow (docs/UI.md §1).
        val mapping =
            SwipeMapping()
                .with(SwipeDirection.LEFT, SwipeAction.NONE)
                .with(SwipeDirection.RIGHT, SwipeAction.NONE)

        assertEquals(SwipeAction.NONE, mapping.actionFor(SwipeDirection.RIGHT))
        assertEquals(SwipeAction.NONE, mapping.actionFor(SwipeDirection.LEFT))
    }

    @Test
    fun `re-enabling a direction over a disabled pair does not resurrect the old action`() {
        val disabled =
            SwipeMapping()
                .with(SwipeDirection.LEFT, SwipeAction.NONE)
                .with(SwipeDirection.RIGHT, SwipeAction.NONE)

        val reEnabled = disabled.with(SwipeDirection.RIGHT, SwipeAction.DOWNLOAD)

        assertEquals(SwipeAction.DOWNLOAD, reEnabled.actionFor(SwipeDirection.RIGHT))
        assertEquals(SwipeAction.NONE, reEnabled.actionFor(SwipeDirection.LEFT))
    }
}
