// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.download

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
 * **The notification small icon against the real alpha mask** (`docs/UI.md` §C7's second open Tier 3
 * item, and §3).
 *
 * Android does not draw a small icon as supplied. It keeps the **alpha channel**, throws the colour
 * away, and repaints the silhouette in the system's own tint. So the only question that matters is
 * whether the figure still reads once it is reduced to alpha — and the mark is not a solid shape: it
 * is bars and a vessel separated by gaps, and those gaps are the whole figure. If they close up, the
 * icon becomes a blob and the brand carries no information at all.
 *
 * That is a question about pixels, so it is asked in pixels rather than by eye. The rasterised icon
 * is also written out for a look.
 */
@RunWith(AndroidJUnit4::class)
class NotificationIconConformanceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * The icon drawn onto a transparent canvas, which is all the system keeps of it.
     *
     * Drawn through the framework rather than a `toBitmap()` extension so this needs no dependency
     * beyond the platform — and so the `setBounds` is visible, since a drawable with no bounds draws
     * nothing and would make every assertion below fail for the wrong reason.
     */
    private fun alphaMask(sizePx: Int): Bitmap {
        val drawable = requireNotNull(context.getDrawable(R.drawable.ic_podsilo_notification))
        val mask = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(Canvas(mask))
        return mask
    }

    private fun save(
        name: String,
        bitmap: Bitmap,
    ) {
        val file = File(context.getExternalFilesDir(null), "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SCREENSHOT ${file.absolutePath}")
    }

    @Test
    fun theIconResolvesAndRasterisesAtTheSizeTheShadeUsesIt() {
        // 24 dp is the small-icon size; on this device that is whatever density it runs at, which is
        // the point of asking a device rather than a JVM.
        val px = (24 * context.resources.displayMetrics.density).toInt()
        val mask = alphaMask(px)
        save("notification-icon-alpha", mask)

        val anythingDrawn =
            (0 until px).any { x ->
                (0 until px).any { y -> Color.alpha(mask.getPixel(x, y)) > 0 }
            }
        assertTrue("nothing was drawn", anythingDrawn)
    }

    @Test
    fun theFigureSurvivesBeingReducedToAlpha() {
        // Down the mark's vertical centre line the figure is: gap, bar, gap, bar, gap (the silo's
        // open mouth), stored band, gap, floor. Opaque and transparent runs must therefore ALTERNATE
        // several times. A blob — bars fused into the vessel — is one long opaque run, which is
        // exactly the failure `docs/UI.md` §C1's minimum size and §3's 18-in-24 padding exist to
        // prevent, and exactly what a careless re-export would produce.
        val px = (24 * context.resources.displayMetrics.density).toInt()
        val mask = alphaMask(px)
        val centre = px / 2

        var runs = 0
        var previouslyOpaque = false
        for (y in 0 until px) {
            val opaque = Color.alpha(mask.getPixel(centre, y)) > OPACITY_FLOOR
            if (opaque != previouslyOpaque) runs++
            previouslyOpaque = opaque
        }

        // transparent→bar→gap→bar→gap→band→gap→floor: at least six transitions down the centre.
        assertTrue("the silhouette collapsed into $runs runs; the bars are not separating", runs >= 6)
    }

    @Test
    fun theSystemsOwnPaddingCannotClipTheTopBar() {
        // §3: the mark is held at 18 of 24 units precisely because the system adds padding and clips
        // past it, and the top bar is the first thing to go. So the outermost ring of the canvas must
        // be empty — if anything is drawn there, the icon was exported full-bleed and the phone will
        // crop the figure rather than the margin.
        val px = (24 * context.resources.displayMetrics.density).toInt()
        val mask = alphaMask(px)
        val inset = (px * INSET_FRACTION).toInt().coerceAtLeast(1)

        val border =
            (0 until px).flatMap { x ->
                (0 until px).mapNotNull { y ->
                    val onBorder = x < inset || y < inset || x >= px - inset || y >= px - inset
                    if (onBorder) Color.alpha(mask.getPixel(x, y)) else null
                }
            }

        assertEquals("the icon is drawn into the margin the system will clip", 0, border.maxOrNull() ?: 0)
    }
}

/** Antialiasing puts faint pixels either side of every edge; below this is an edge, not a shape. */
private const val OPACITY_FLOOR = 40

/** §3 holds the mark at 18 of 24 units, so 3 units of margin exist on each side. Check two of them. */
private const val INSET_FRACTION = 2.0 / 24.0
