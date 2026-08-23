// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The artwork slot, which shipped as a `heightIn` reserving space for an image no screen ever drew.
 *
 * The assertions worth having are about the **fallback**, because that is the case a naive
 * implementation gets wrong: a null URL must render the monogram, not a blank square, and it must
 * describe itself the same way real artwork does (`docs/UI.adoc` §18 — "same content description as
 * real artwork, never 'no image'").
 */
@RunWith(RobolectricTestRunner::class)
class PodsiloArtworkTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a feed with no image yet renders its monogram, not an empty square`() {
        compose.setContent { PodsiloArtwork(url = null, title = "Der Podcast") }

        compose.onNodeWithText("D").assertIsDisplayed()
        compose.onNodeWithContentDescription("cover art for Der Podcast").assertIsDisplayed()
    }

    @Test
    fun `real artwork describes itself exactly as the fallback does`() {
        // Never "no image", and never a different phrasing depending on whether the fetch worked —
        // the description says what the slot is, not how loading went.
        compose.setContent { PodsiloArtwork(url = "https://example.org/a.jpg", title = "Der Podcast") }

        compose.onNodeWithContentDescription("cover art for Der Podcast").assertIsDisplayed()
    }

    @Test
    fun `a blank url is treated as no url, not as a broken image`() {
        compose.setContent { PodsiloArtwork(url = "   ", title = "Lage der Nation") }

        compose.onNodeWithText("L").assertIsDisplayed()
    }

    /**
     * Found on the device: the `heute journal` feed advertises its cover over plain `http://`, which
     * Android blocks as cleartext. With Coil's `error` slot left null the row drew a blank square —
     * no image and no fallback. The monogram sits underneath the image, so a load that never
     * succeeds simply leaves it visible.
     */
    @Test
    fun `a url that cannot load still leaves the monogram showing`() {
        compose.setContent { PodsiloArtwork(url = "http://blocked.invalid/a.jpg", title = "heute journal") }

        compose.onNodeWithText("H").assertIsDisplayed()
        compose.onNodeWithContentDescription("cover art for heute journal").assertIsDisplayed()
    }

    @Test
    fun `the monogram takes a whole code point, so an emoji title is not half a surrogate pair`() {
        assertEquals("🎧", monogram("🎧 Der Podcast"))
        assertEquals("德", monogram("德州中文台 Texas Chinese Radio"))
        assertEquals("Ä", monogram("ärgerlich"))
        assertEquals("H", monogram("  hörspiel"))
        assertEquals("?", monogram(""))
    }
}
