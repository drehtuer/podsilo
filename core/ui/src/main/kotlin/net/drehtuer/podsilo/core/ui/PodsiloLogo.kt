// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// The brand mark and its lockups (`docs/UI.md` Part C).
//
// This is NOT an icon, and it deliberately does not live in `PodsiloIcons`. That object is an
// allow-list of *functional* glyphs, each standing for an action or a state; the mark stands for
// nothing, is never tappable, and has exactly the placements `docs/UI.md` §C4 lists. Putting it in
// the allow-list would invite call sites to use the logo as a glyph, which is how a brand becomes
// noise. Beside it, not inside it — that is the whole reason this is a separate file.

/** S1's app bar (`docs/UI.md` §C4.1). 24 dp beside the title, which stays live type. */
val MarkSizeAppBar = 24.dp

/** The About row's horizontal lockup (`docs/UI.md` §C4.3). */
val MarkSizeLockupHorizontal = 36.dp

/** S1's not-configured empty state (`docs/UI.md` §C4.2) — the one large, unhurried appearance. */
val MarkSizeLockupStacked = 56.dp

/** Mark to wordmark — 8 dp in both lockups and in S1's app bar (`docs/UI.md` §C4.1). */
val LogoGap = 8.dp

/**
 * How a test finds the mark.
 *
 * A test tag in production code needs justifying, and this one has a specific reason: the mark is
 * deliberately invisible to accessibility everywhere it appears (`contentDescription = null`, because
 * a wordmark is always beside it), so no semantics query can find it. `docs/UI.md` §C4 says the mark
 * has **exactly** four placements and §5 lists where it must never appear — rules that are only worth
 * writing down if something checks them. Without this tag, "the logo is not in S2–S8's app bars"
 * cannot be asserted at all, and a stray mark would be caught by a human eye or not at all.
 */
const val PODSILO_MARK_TEST_TAG = "podsilo-mark"

/**
 * The wordmark's cap height against the mark's height, taken from the exported lockup SVGs: a
 * 100-unit mark beside 58-unit type. Deriving the one from the other is what keeps a lockup in
 * proportion at any size, instead of two constants that drift apart.
 */
private const val WORDMARK_RATIO = 0.58f

/** Below this the three bars stop separating (`docs/UI.md` §C1) — use nothing rather than less. */
private const val MIN_MARK_DP = 16

/**
 * §1's minimum size, as a check a caller cannot render past.
 *
 * A free function rather than an inline `require` so it is assertable without a composition: an
 * exception thrown mid-compose is awkward to pin down in a test, and a rule nobody tests is a
 * comment.
 */
internal fun requireLegibleMarkSize(size: Dp) {
    require(size.value >= MIN_MARK_DP) {
        "The mark has a ${MIN_MARK_DP}dp floor (docs/UI.md §C1); below it the bars stop separating. Got $size"
    }
}

/**
 * The mark alone, at [size].
 *
 * **Which of the two drawables is right depends on the ground, not on the system.** The two-colour
 * mark's vessel is ink `#201E1D`, invisible against the dark scheme's `#14110F` surface; on ink and
 * on the accent field `docs/UI.md` §C1 says the whole mark is white. So this picks by the *theme's*
 * surface luminance rather than by a `drawable-night` qualifier: the theme is a user preference in
 * DataStore (`docs/UI.md` §12.7) and can disagree with the device's night mode, and a qualifier
 * would then paint a white mark onto a light surface.
 *
 * Neither drawable is tinted — tinting the two-colour one flattens the bars into the vessel and
 * destroys the figure (`docs/UI.md` §C6).
 *
 * @param contentDescription `null` at every placement in this app, because the wordmark is beside it
 *   in all of them — in the app bar as the title, in a lockup as live type. Announcing both makes
 *   TalkBack read the product name twice. It stays a parameter for a mark that one day stands alone.
 */
@Composable
fun PodsiloMark(
    modifier: Modifier = Modifier,
    size: Dp = MarkSizeAppBar,
    contentDescription: String? = null,
) {
    requireLegibleMarkSize(size)
    val onDarkGround = MaterialTheme.colorScheme.surface.luminance() < DARK_GROUND_LUMINANCE
    Image(
        painter = painterResource(if (onDarkGround) R.drawable.ic_podsilo_mark_inverse else R.drawable.ic_podsilo_mark),
        contentDescription = contentDescription,
        modifier = modifier.size(size).testTag(PODSILO_MARK_TEST_TAG),
    )
}

private const val DARK_GROUND_LUMINANCE = 0.5f

/** Which way a [PodsiloLockup] stacks. Both are flush left; neither is ever centred. */
enum class LockupOrientation {
    /** Mark + wordmark on one line — the About row (`docs/UI.md` §C4.3). */
    HORIZONTAL,

    /** Mark over wordmark — S1's not-configured empty state (`docs/UI.md` §C4.2). */
    STACKED,
}

/**
 * The mark with the wordmark beside or beneath it.
 *
 * **The wordmark is set as type, never imported as art.** `docs/UI.md` §C4.1 already requires this
 * of the app bar, for a reason that applies just as well to both lockups: a title has to scale with
 * the user's font setting, and a drawable will not. Two further reasons decided it here — a
 * `VectorDrawable` cannot hold text at all, so the exported lockup SVGs could only ship as art if
 * their `<text>` were outlined first, which needs the Archivo font this repo does not carry; and
 * type follows the theme's `onSurface`, where baked-in ink would go invisible in the dark scheme.
 *
 * Consequence to be honest about: **in-app the wordmark is the platform font, not Archivo.** The
 * exported SVGs remain the reference for anything leaving the app — a store listing, the README —
 * where §2's "outline the wordmark first" still applies.
 */
@Composable
fun PodsiloLockup(
    modifier: Modifier = Modifier,
    orientation: LockupOrientation = LockupOrientation.HORIZONTAL,
    markSize: Dp =
        when (orientation) {
            LockupOrientation.HORIZONTAL -> MarkSizeLockupHorizontal
            LockupOrientation.STACKED -> MarkSizeLockupStacked
        },
) {
    // No content description on the pair, and none on the mark inside it. `docs/UI.md` §C6 asks for
    // `"Podsilo"` on the empty-state lockup because it is "the only text-free instance" — but a
    // lockup built from live type is not text-free, so the wordmark *is* the announcement. Adding a
    // description on top is exactly the "Podsilo Podsilo" the rule was written to prevent.
    when (orientation) {
        LockupOrientation.HORIZONTAL ->
            Row(
                modifier = modifier,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LogoGap),
            ) {
                PodsiloMark(size = markSize)
                Wordmark(markSize)
            }

        LockupOrientation.STACKED ->
            Column(
                modifier = modifier,
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(LogoGap),
            ) {
                PodsiloMark(size = markSize)
                Wordmark(markSize)
            }
    }
}

/**
 * `podsilo` — all lowercase, heavy, tight, flush left (`docs/UI.md` §C2).
 *
 * Never title-cased and never centred. The size is in `sp` and therefore grows with the user's font
 * setting, which is the point: this is the product's name being said, not a picture of it.
 */
@Composable
private fun Wordmark(markSize: Dp) {
    Text(
        text = "podsilo",
        style =
            MaterialTheme.typography.headlineMedium.copy(
                fontSize = (markSize.value * WORDMARK_RATIO).sp,
                // The style's own line height is a fixed sp value drawn for its own font size; kept
                // while the size varies with the mark, it either clips the descender or leaves a
                // gap the lockup did not ask for. Unspecified defers to the font's natural metrics.
                lineHeight = TextUnit.Unspecified,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.04).em,
            ),
        color = MaterialTheme.colorScheme.onSurface,
    )
}
