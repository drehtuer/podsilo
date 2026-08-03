// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage

/**
 * The artwork slot used by S1's podcast rows, S2's episode rows and S3's header.
 *
 * Shared here rather than written twice because the two rules below have to hold identically in
 * every place artwork appears, and the first one was already stated in `docs/UI.md` §18 for a slot
 * that did not exist yet:
 *
 * 1. **The monogram fallback is artwork, not an error state.** When [url] is null — a feed that has
 *    never been fetched, or an episode whose feed supplies no image — the slot renders a filled
 *    square carrying the first letter of [title]. It is never blank and never a broken-image glyph.
 * 2. **Both cases carry the same content description**, "cover art for X", never "no image": a
 *    screen-reader user is being told what the slot represents, not how the fetch went.
 *
 * Zero corner radius, like every other shape in this app (`docs/UI_interface.md` §10).
 *
 * @param title the podcast (or episode) name, used for the monogram letter and the description.
 */
@Composable
fun PodsiloArtwork(
    url: String?,
    title: String,
    modifier: Modifier = Modifier,
    size: Dp = ArtworkSize,
) {
    val description = "cover art for $title"

    // The monogram is drawn *underneath* and the image on top of it, rather than the image having
    // placeholder/error slots. Two reasons, one of them found on the device:
    //
    //  - A failed load then shows the monogram for free. The author's `heute journal` feed advertises
    //    its cover over plain `http://`, which Android blocks as cleartext, and with Coil's `error`
    //    left null the row rendered a blank square — worse than the fallback it was meant to have.
    //  - No `SubcomposeAsyncImage`, whose per-item subcomposition is what you do not want in a list
    //    that is 9,490 rows long in this author's own subscriptions.
    Box(
        modifier = modifier.size(size).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        MonogramTile(title)
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                // Null, not the description: the Box already carries it, and Coil would otherwise
                // add a second, competing semantics node for the same square.
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun MonogramTile(title: String) {
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = monogram(title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The letter on the fallback tile.
 *
 * Takes the first *code point*, not the first `Char`: a title starting with an emoji or an astral
 * CJK character would otherwise render half a surrogate pair. Falls back to `?` only when the title
 * has nothing letter-like at all, which for a feed means its URL was used as the title and even that
 * was empty.
 */
internal fun monogram(title: String): String {
    val first = title.trim().takeWhile { !it.isWhitespace() }.firstOrNull() ?: return "?"
    val codePoint = title.trim().codePointAt(0)
    return if (Character.charCount(codePoint) > 1) {
        String(Character.toChars(codePoint))
    } else {
        first.uppercase()
    }
}
