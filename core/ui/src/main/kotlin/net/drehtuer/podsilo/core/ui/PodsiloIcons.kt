// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.ui

import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.composables.icons.lucide.R as LucideR

/**
 * The icon allow-list from `docs/UI.md` §18, as code.
 *
 * **An icon not named here has no call site.** That is the rule the doc states, and putting the
 * mapping in one object is what makes it enforceable: adding an affordance means adding a property
 * here — and a row in §18 — rather than reaching for a glyph at the point of use.
 *
 * Lucide only, one weight everywhere. Never mixed with Material Symbols: two icon families in one
 * app read as an unfinished migration.
 *
 * **What the artifact actually ships** (`docs/UI.md` §18 assumed otherwise, and is amended):
 * `com.composables:icons-lucide-android` is a pack of **`VectorDrawable` XML resources**, not
 * `ImageVector` objects — its `classes.jar` is empty. So these are drawable ids resolved with
 * `painterResource`, which is why they are `Int`s and not `ImageVector`s. Everything UI.md §18
 * decided still holds; only the call site differs.
 */
object PodsiloIcons {
    /** Up navigation on S2, S4, S6, S7, S8. */
    @DrawableRes val Back: Int = LucideR.drawable.lucide_ic_arrow_left

    /** S1 app bar → S4. */
    @DrawableRes val Settings: Int = LucideR.drawable.lucide_ic_settings

    /** S1/S2 app bar → S7; carries the badge dot. */
    @DrawableRes val Activity: Int = LucideR.drawable.lucide_ic_activity

    /** Row overflow, app-bar overflow. */
    @DrawableRes val Overflow: Int = LucideR.drawable.lucide_ic_ellipsis_vertical

    /** Row navigation affordance; also S8's collapsed *show technical detail*. */
    @DrawableRes val ChevronRight: Int = LucideR.drawable.lucide_ic_chevron_right

    /** The swipe-mapping and *older than* dropdowns on S4. */
    @DrawableRes val ChevronDown: Int = LucideR.drawable.lucide_ic_chevron_down

    /** The Download action, and the swipe-right background. */
    @DrawableRes val Download: Int = LucideR.drawable.lucide_ic_download

    /**
     * The **played** state badge and the mark-as-played swipe background.
     *
     * Never playback: Podsilo does not play audio (README). It appears beside the word, never alone.
     */
    @DrawableRes val Played: Int = LucideR.drawable.lucide_ic_play

    /** ✓ downloaded badge; the satisfied step in S1's checklist; S7's delivered rows. */
    @DrawableRes val Check: Int = LucideR.drawable.lucide_ic_check

    /** "All caught up" and "nothing has failed" empty states. */
    @DrawableRes val AllDone: Int = LucideR.drawable.lucide_ic_check_check

    /**
     * **Handled elsewhere** — the state the user did not create here.
     *
     * Deliberately not [Check]: rendering it as the same ✓ as a download this device performed would
     * claim a decision the user did not make, and the affordances differ (`docs/UI.md` §12.6).
     */
    @DrawableRes val HandledRemotely: Int = LucideR.drawable.lucide_ic_cloud_check

    /** Leave selection mode. */
    @DrawableRes val Close: Int = LucideR.drawable.lucide_ic_x

    @DrawableRes val Unchecked: Int = LucideR.drawable.lucide_ic_square

    @DrawableRes val Checked: Int = LucideR.drawable.lucide_ic_square_check

    /**
     * **A condition the queue is in** — paused, failed, will not fit.
     *
     * Not interchangeable with [InputError]: swapping them makes a typo look like a system fault.
     */
    @DrawableRes val Warning: Int = LucideR.drawable.lucide_ic_triangle_alert

    /** **Input the user can fix** — a bad server address, an invalid template, a feed that did not respond. */
    @DrawableRes val InputError: Int = LucideR.drawable.lucide_ic_circle_alert

    /** Sync in progress on S1. */
    @DrawableRes val Syncing: Int = LucideR.drawable.lucide_ic_refresh_cw

    /** S5's awaiting-authorization spinner. */
    @DrawableRes val Waiting: Int = LucideR.drawable.lucide_ic_loader

    /** Offline banner and status line. */
    @DrawableRes val Offline: Int = LucideR.drawable.lucide_ic_wifi_off

    // `server` was here, for S1's not-configured empty state. That state now leads with the brand
    // lockup instead (`docs/UI.md` §C4.2), leaving the glyph with no call site — and this object is
    // an allow-list, not an inventory, so an entry nobody renders is an invitation to find it a job.
    // The brand mark is deliberately *not* added in its place: it is not a glyph. See PodsiloLogo.kt.

    /** Filter-empty states. */
    @DrawableRes val Empty: Int = LucideR.drawable.lucide_ic_inbox

    /** S7 app bar → S8. */
    @DrawableRes val ErrorLog: Int = LucideR.drawable.lucide_ic_file_text

    @DrawableRes val Copy: Int = LucideR.drawable.lucide_ic_copy

    @DrawableRes val Share: Int = LucideR.drawable.lucide_ic_share_2

    @DrawableRes val Clear: Int = LucideR.drawable.lucide_ic_trash_2

    /** *Open episode page in browser* (S3, and the S2 row overflow). */
    @DrawableRes val OpenInBrowser: Int = LucideR.drawable.lucide_ic_external_link

    /** The **no audio** badge on an episode with no enclosure. */
    @DrawableRes val NoEnclosure: Int = LucideR.drawable.lucide_ic_volume_off
}

/**
 * The one way an icon reaches the screen.
 *
 * [contentDescription] is **required and nullable on purpose**: `null` is the correct answer for an
 * icon beside its own label, and stating it at every call site is what stops a decorative icon from
 * being announced twice and an icon-only control from being announced not at all
 * (`docs/UI.md` §12.12).
 */
@Composable
fun PodsiloIcon(
    @DrawableRes icon: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    Icon(
        painter = painterResource(icon),
        contentDescription = contentDescription,
        modifier = modifier,
        tint = if (tint == Color.Unspecified) androidx.compose.material3.LocalContentColor.current else tint,
    )
}
