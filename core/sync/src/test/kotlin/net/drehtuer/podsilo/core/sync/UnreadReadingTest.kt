// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.sync

import net.drehtuer.podsilo.core.model.EpisodeLedgerRow
import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.core.model.port.EpisodeAction
import net.drehtuer.podsilo.core.model.port.EpisodeActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Issue #60, step 3b: **a remote *mark as unread* must not be read as "handled elsewhere".**
 *
 * The gpodder API cannot delete an action and has no "unread" type, so a client says it by writing a
 * `PLAY` with `position = 0` — keeping whatever `total` the row already had. Reading the action type
 * alone therefore inverted the user's intent: the episode they had just said they had *not* listened
 * to was the one Podsilo filed as terminal and hid from *To decide* for ever.
 *
 * **Every shape below was measured**, not invented — these are the actual actions the author's own
 * Nextcloud returned on 2026-08-13, before and after they flipped five episodes back to unread.
 */
class UnreadReadingTest {
    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-08-14T00:00:00Z"), ZoneOffset.UTC)

    private fun play(
        guid: String,
        position: Int?,
        total: Int?,
    ) = EpisodeAction(
        podcast = "https://feeds.acast.com/public/shows/6654ee607d5431001308bb90",
        episode = "https://sphinx.acast.com/p/open/s/$guid/media.mp3",
        guid = guid,
        action = EpisodeActionType.PLAY,
        timestamp = "2026-08-13T23:33:15+00:00",
        started = 0,
        position = position,
        total = total,
    )

    private fun localRow(
        episodeKey: String,
        state: LedgerState,
    ) = EpisodeLedgerRow(
        episodeKey = episodeKey,
        feedUrl = "https://feeds.acast.com/public/shows/6654ee607d5431001308bb90",
        enclosureUrl = "https://sphinx.acast.com/p/open/s/$episodeKey/media.mp3",
        state = state,
        actionedAt = 0L,
        syncedToServer = true,
        attempts = 0,
        lastError = null,
        writtenFileName = null,
    )

    /**
     * The five actions from the probe's second run, verbatim. Each is an episode the author marked
     * **unread** in RePod, and every one of them was previously filed as `HANDLED_REMOTELY`.
     */
    @Test
    fun `the five real unread marks leave the episodes undecided`() {
        val unreadMarks =
            listOf(
                play("68e584b0f513ad2b81ad179b", position = 0, total = 2838),
                play("690f68fcc1ed8717c5144786", position = 0, total = 2398),
                play("691a4895e42e3466f2dd4623", position = 0, total = 2766),
                play("694c09decb029db7577a6952", position = 0, total = 3082),
                play("699d915a3a5156c5d2f62b01", position = 0, total = 1854),
            )

        val result = reconcile(localLedger = emptyMap(), remoteActions = unreadMarks, clock = fixedClock)

        assertTrue("an unread mark must not create a ledger row at all", result.isEmpty())
    }

    /** The sixth action in the same window: genuinely played, and it still marks handled. */
    @Test
    fun `a real played mark still marks handled elsewhere`() {
        val played = play("69af61b1b58ea3074ddfc173", position = 1_800, total = 1_800)

        val result = reconcile(emptyMap(), listOf(played), fixedClock)

        assertEquals(LedgerState.HANDLED_REMOTELY, result.single().state)
    }

    /**
     * Our own encoding for a skip whose feed declared no duration, since D2: `1/1`. It has to survive
     * its own round trip, or a second Podsilo device would not see the first one's decision.
     */
    @Test
    fun `our own duration-less skip reads as handled when it comes back`() {
        val ourSkip = play("guid-no-duration", position = 1, total = 1)

        val result = reconcile(emptyMap(), listOf(ourSkip), fixedClock)

        assertEquals(LedgerState.HANDLED_REMOTELY, result.single().state)
    }

    /**
     * The shape this app used to emit, before D2. It now reads as *not* handled — which is the
     * accepted cost of adopting RePod's rule verbatim: an old duration-less skip sitting in the
     * server's log stops propagating to a device that has never seen it. The local row that produced
     * it is terminal and unaffected, so nothing is lost on the device that made the decision.
     */
    @Test
    fun `a legacy zero-zero skip no longer counts as handled`() {
        val legacy = play("guid-legacy", position = 0, total = 0)

        val result = reconcile(emptyMap(), listOf(legacy), fixedClock)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `a play with no playback values at all is not handled, exactly as RePod reads it`() {
        val bare = play("guid-bare", position = null, total = null)

        val result = reconcile(emptyMap(), listOf(bare), fixedClock)

        assertTrue(result.isEmpty())
    }

    /** A partial listen is not a decision either — RePod calls it *listening*, not *ended*. */
    @Test
    fun `a partial listen is not handled`() {
        val halfway = play("guid-partial", position = 900, total = 1_800)

        val result = reconcile(emptyMap(), listOf(halfway), fixedClock)

        assertTrue(result.isEmpty())
    }

    /**
     * `DOWNLOAD` and `DELETE` are unaffected by the new rule. Ours is a wider question than RePod's —
     * "has another client handled it" rather than "was it played" — and CLAUDE.md §5 says a remote
     * `DOWNLOAD` means do not download it here.
     */
    @Test
    fun `DOWNLOAD and DELETE still mark handled regardless of playback values`() {
        listOf(EpisodeActionType.DOWNLOAD, EpisodeActionType.DELETE).forEach { type ->
            val action = play("guid-$type", position = 0, total = 0).copy(action = type)

            val result = reconcile(emptyMap(), listOf(action), fixedClock)

            assertEquals("action=$type", LedgerState.HANDLED_REMOTELY, result.single().state)
        }
    }

    /**
     * An unread mark must not *re-open* a decision this device made either. The terminal-state rule
     * already guarantees it, and the ledger has no delete — but this is the case a reader will worry
     * about after reading the rest of this file, so it is pinned rather than argued.
     */
    @Test
    fun `an unread mark never re-opens a local decision`() {
        listOf(LedgerState.DOWNLOADED, LedgerState.SKIPPED, LedgerState.HANDLED_REMOTELY).forEach { state ->
            val local = mapOf("guid-1" to localRow("guid-1", state))

            val result = reconcile(local, listOf(play("guid-1", position = 0, total = 2838)), fixedClock)

            assertTrue("state=$state must be untouched", result.isEmpty())
        }
    }
}
