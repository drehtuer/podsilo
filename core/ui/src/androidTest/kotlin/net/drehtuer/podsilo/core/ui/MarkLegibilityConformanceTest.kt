// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * **Does the mark still read at 24 dp?** (`docs/logo.md` §7's first open Tier 3 item.)
 *
 * §1 puts a 16 dp floor on the mark because "below 16 dp the three bars stop separating; use nothing
 * rather than a smaller mark". 24 dp — the app-bar size (§4.1) — is the smallest the mark is ever
 * drawn in this app, and the closest to that floor. Whether it holds there is a claim about pixels
 * on a real display, and it had been carried as an open question answerable only by eye.
 *
 * Robolectric cannot answer it: its canvas draws nothing, so a mark that fused into a blob would
 * pass there. Hence an instrumented test — but a **runner-only** one, with no Compose and no
 * Espresso, so it runs on this phone where the Compose instrumented suite currently cannot.
 */
@RunWith(AndroidJUnit4::class)
class MarkLegibilityConformanceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val density = context.resources.displayMetrics.density

    private fun raster(
        drawableId: Int,
        dp: Int,
    ): Bitmap {
        val px = (dp * density).toInt()
        val drawable = requireNotNull(context.getDrawable(drawableId))
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, px, px)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    private fun save(
        name: String,
        bitmap: Bitmap,
    ) {
        val file = File(context.getExternalFilesDir(null), "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SCREENSHOT ${file.absolutePath}")
    }

    /**
     * Counts opaque/transparent alternations down the mark's vertical centre.
     *
     * That line crosses, in order: the gap above the first bar, bar one, a gap, bar two, the silo's
     * open mouth, the stored band, a gap, and the floor. Separation *is* the figure — if the bars
     * fuse into the vessel the count collapses, which is precisely what "the bars stop separating"
     * means and precisely what an eye is bad at judging at 24 dp.
     */
    private fun separationsDownTheCentre(bitmap: Bitmap): Int {
        val centre = bitmap.width / 2
        var runs = 0
        var previouslyInk = false
        for (y in 0 until bitmap.height) {
            val ink = Color.alpha(bitmap.getPixel(centre, y)) > OPACITY_FLOOR
            if (ink != previouslyInk) runs++
            previouslyInk = ink
        }
        return runs
    }

    @Test
    fun theMarkStillSeparatesAtTheAppBarSize() {
        val bitmap = raster(R.drawable.ic_podsilo_mark, DP_APP_BAR)
        save("mark-24dp", bitmap)

        val runs = separationsDownTheCentre(bitmap)
        assertTrue(
            "at ${DP_APP_BAR}dp the mark collapsed to $runs runs — the bars are not separating",
            runs >= MIN_RUNS,
        )
    }

    @Test
    fun theMarkStillSeparatesAtItsAbsoluteFloor() {
        // The floor is a promise: §1 says 16 dp is usable and below it is not. Nothing in the app
        // draws the mark this small, but the constant permits it, so it is checked.
        val bitmap = raster(R.drawable.ic_podsilo_mark, DP_FLOOR)
        save("mark-16dp", bitmap)

        val runs = separationsDownTheCentre(bitmap)
        assertTrue("at ${DP_FLOOR}dp the mark collapsed to $runs runs; the floor is set too low", runs >= MIN_RUNS)
    }

    @Test
    fun theInverseBuildIsTheSameFigure() {
        // The dark-surface build is a separate file, so it is a separate opportunity to get the
        // geometry wrong. It must be the same figure, not a redrawn approximation of one.
        val twoColour = separationsDownTheCentre(raster(R.drawable.ic_podsilo_mark, DP_APP_BAR))
        val inverse = raster(R.drawable.ic_podsilo_mark_inverse, DP_APP_BAR)
        save("mark-24dp-inverse", inverse)

        assertEquals(
            "the inverse build has different geometry from the two-colour one",
            twoColour,
            separationsDownTheCentre(inverse),
        )
    }

    @Test
    fun theMonoBuildIsTheSameFigureToo() {
        // The tintable silhouette is what the themed launcher icon becomes, and it loses colour
        // entirely — so its gaps are all it has.
        val twoColour = separationsDownTheCentre(raster(R.drawable.ic_podsilo_mark, DP_APP_BAR))
        val mono = raster(R.drawable.ic_podsilo_mark_mono, DP_APP_BAR)
        save("mark-24dp-mono", mono)

        assertEquals(
            "the mono build has different geometry from the two-colour one",
            twoColour,
            separationsDownTheCentre(mono),
        )
    }
}

/** §4.1 — the app bar, and the smallest the mark is drawn anywhere in this app. */
private const val DP_APP_BAR = 24

/** §1 — the stated minimum below which the mark should not be used at all. */
private const val DP_FLOOR = 16

/** Antialiasing puts faint pixels either side of every edge; below this is an edge, not a shape. */
private const val OPACITY_FLOOR = 40

/** transparent→bar→gap→bar→gap→band→gap→floor: six transitions at the very least. */
private const val MIN_RUNS = 6
