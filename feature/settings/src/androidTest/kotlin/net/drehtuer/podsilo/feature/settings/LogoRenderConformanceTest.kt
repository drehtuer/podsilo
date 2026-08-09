// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.settings

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.drehtuer.podsilo.core.ui.PODSILO_MARK_TEST_TAG
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

/**
 * S4's About lockup on a real device (`docs/logo.md` §4.3).
 *
 * The horizontal lockup is the one placement where the mark and the wordmark sit on a single
 * baseline, so it is where a mismatch between the mark's `dp` size and the wordmark's `sp` size
 * shows up as the pair looking wrong together. That is a look-at-it question, which is why this
 * writes the render out rather than only counting nodes.
 */
@RunWith(AndroidJUnit4::class)
class LogoRenderConformanceTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = Instant.parse("2026-08-02T12:00:00Z")

    private fun save(
        name: String,
        bitmap: Bitmap,
    ) {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
        val file = File(dir, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SCREENSHOT ${file.absolutePath}")
    }

    private fun renderSettings(dark: Boolean) {
        compose.setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface {
                    Box {
                        SettingsScreen(
                            state = SettingsUiState(version = "0.2.1", build = "114 · 2026-08-08"),
                            onEvent = {},
                            onBack = {},
                            now = now,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun theAboutLockupRendersOnceAndIsWorthLookingAt() {
        renderSettings(dark = false)

        compose.onAllNodesWithTag(PODSILO_MARK_TEST_TAG).assertCountEquals(1)
        save("s4-settings-light", compose.onRoot().captureToImage().asAndroidBitmap())
    }

    @Test
    fun theAboutLockupRendersInTheDarkSchemeToo() {
        // The ink vessel would vanish here if `PodsiloMark` picked the wrong drawable, and the
        // wordmark would vanish if it were baked art rather than `onSurface` type. Both are the
        // point of §6, and both are only true or false in pixels.
        renderSettings(dark = true)

        compose.onAllNodesWithTag(PODSILO_MARK_TEST_TAG).assertCountEquals(1)
        save("s4-settings-dark", compose.onRoot().captureToImage().asAndroidBitmap())
    }
}
