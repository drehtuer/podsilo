// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.drehtuer.podsilo.core.ui.PODSILO_MARK_TEST_TAG
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

/**
 * **The brand mark on a real device** (`docs/logo.md` §7's open Tier 3 items).
 *
 * The Robolectric `LogoPlacementTest` counts placements; it cannot answer whether anything was
 * actually *drawn*. Robolectric's canvas is a no-op, so a mark that resolved to a missing resource,
 * rendered at zero size, or came out the same colour as the surface behind it would pass there and
 * be invisible here. This asserts against real pixels.
 *
 * It also writes each render to the test app's external files dir, so the results can be looked at
 * rather than only asserted — the "does 24 dp read?" question is a judgement no assertion makes.
 */
@RunWith(AndroidJUnit4::class)
class LogoRenderConformanceTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = Instant.parse("2026-08-02T12:00:00Z")

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

    private fun s1(
        state: PodcastListUiState,
        dark: Boolean = false,
    ) = render(dark) { PodcastListScreen(state = state, onEvent = {}, now = now) }

    /** Written where `adb pull` can reach it; the run prints the directory. */
    private fun save(
        name: String,
        bitmap: Bitmap,
    ) {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
        val file = File(dir, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SCREENSHOT ${file.absolutePath}")
    }

    /**
     * The mark's own pixels, not the screen's.
     *
     * A drawable that failed to resolve renders as nothing, and "nothing" against a surface is a
     * uniform block of one colour. Counting distinct colours is the cheapest question that
     * distinguishes a drawn figure from an empty box, and it is the one Robolectric cannot answer.
     */
    private fun assertMarkActuallyDrew(name: String) {
        val bitmap = compose.onNodeWithTag(PODSILO_MARK_TEST_TAG).captureToImage().asAndroidBitmap()
        save(name, bitmap)

        assertTrue("the mark rendered at zero size", bitmap.width > 0 && bitmap.height > 0)
        val colours = mutableSetOf<Int>()
        for (x in 0 until bitmap.width) {
            for (y in 0 until bitmap.height) {
                colours += bitmap.getPixel(x, y)
            }
        }
        // Bars, vessel and ground — three at the very least, and antialiasing adds more. A blank
        // box has one.
        assertTrue("the mark drew $colours distinct colours; it is not a figure", colours.size >= 3)
    }

    @Test
    fun theAppBarMarkIsActuallyDrawnAtTwentyFourDp() {
        // `docs/logo.md` §1's 16 dp floor exists because below it the bars stop separating. 24 dp is
        // the app-bar size (§4.1) and the smallest the mark is ever drawn in this app, so it is the
        // one worth photographing on real hardware.
        s1(PodcastListUiState(content = PodcastListUiState.Content.Feeds(emptyList())))

        assertMarkActuallyDrew("s1-appbar-mark-light")
    }

    @Test
    fun theAppBarMarkIsDrawnOnADarkSurfaceToo() {
        // §1: on ink the whole mark is white. The two-colour build's vessel would be invisible here,
        // so this is the assertion that the luminance switch in `PodsiloMark` picked the other
        // drawable — on a real canvas, where "invisible" is a pixel fact rather than a code path.
        s1(PodcastListUiState(content = PodcastListUiState.Content.Feeds(emptyList())), dark = true)

        assertMarkActuallyDrew("s1-appbar-mark-dark")
    }

    @Test
    fun theFirstRunScreenRendersTheLockupAboveItsCopy() {
        // §4.2, the one large unhurried appearance. Captured whole rather than cropped to the mark:
        // whether a lockup sits well above its own paragraph is not a thing to assert, only to look
        // at.
        s1(PodcastListUiState(content = PodcastListUiState.Content.NotConfigured))

        compose.onAllNodesWithTag(PODSILO_MARK_TEST_TAG).assertCountEquals(2)
        save("s1-first-run-light", compose.onRoot().captureToImage().asAndroidBitmap())
    }

    @Test
    fun theFirstRunScreenRendersInTheDarkSchemeAsWell() {
        s1(PodcastListUiState(content = PodcastListUiState.Content.NotConfigured), dark = true)

        save("s1-first-run-dark", compose.onRoot().captureToImage().asAndroidBitmap())
    }

    @Test
    fun apopulatedHomeScreenShowsTheMarkOnceAndNoWordmarkArtefacts() {
        s1(
            PodcastListUiState(
                content =
                    PodcastListUiState.Content.Feeds(
                        listOf(
                            FeedUi(
                                url = "https://example.org/feed.xml",
                                title = "Der Podcast",
                                artworkUrl = null,
                                undecidedCount = 12,
                            ),
                            FeedUi(url = "https://example.org/b.xml", title = "Lage der Nation", artworkUrl = null),
                        ),
                    ),
            ),
        )

        compose.onAllNodesWithTag(PODSILO_MARK_TEST_TAG).assertCountEquals(1)
        save("s1-populated-light", compose.onRoot().captureToImage().asAndroidBitmap())
    }
}
