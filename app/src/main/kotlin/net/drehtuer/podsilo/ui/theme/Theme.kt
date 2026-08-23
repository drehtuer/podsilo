// SPDX-License-Identifier: GPL-3.0-or-later

// Colour literals throughout: extracting each ARGB value to a named constant would put a layer of
// indirection between the palette and the reader without making any of it clearer.
@file:Suppress("MagicNumber")

package net.drehtuer.podsilo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import net.drehtuer.podsilo.core.model.port.ThemePreference

/**
 * One seed colour, two schemes, **dynamic colour deliberately off** (`docs/UI.adoc` §12.7).
 *
 * Material You would make the app look different on every device, which means neither scheme could
 * actually be verified — and this project has no device to verify on at all yet, so a palette that
 * varies per handset is a palette nobody has seen.
 *
 * Zero corner radius throughout, matching the modernist system the screens were drawn in: shapes
 * are a design decision here, not a default worth inheriting.
 */
private val AccentLight = Color(0xFFEC3013)
private val AccentDark = Color(0xFFFF5533)

private val PodsiloLightScheme =
    lightColorScheme(
        primary = AccentLight,
        onPrimary = Color.White,
        surface = Color(0xFFFAF9F7),
        onSurface = Color(0xFF14110F),
        surfaceVariant = Color(0xFFEDEAE5),
        // Greyed-out rows use this role rather than an opacity, which would drop the title below
        // the 4.5:1 contrast floor (docs/UI.adoc §12.7).
        onSurfaceVariant = Color(0xFF57514B),
        background = Color(0xFFFAF9F7),
        onBackground = Color(0xFF14110F),
        outline = Color(0xFF8A8279),
        error = Color(0xFFA5200C),
    )

private val PodsiloDarkScheme =
    darkColorScheme(
        primary = AccentDark,
        onPrimary = Color(0xFF1A0500),
        surface = Color(0xFF14110F),
        onSurface = Color(0xFFF2EFEA),
        surfaceVariant = Color(0xFF2A2521),
        onSurfaceVariant = Color(0xFFB8B0A6),
        background = Color(0xFF14110F),
        onBackground = Color(0xFFF2EFEA),
        outline = Color(0xFF6E665D),
        error = Color(0xFFFF8A73),
    )

private val PodsiloShapes =
    Shapes(
        extraSmall = RoundedCornerShape(0.dp),
        small = RoundedCornerShape(0.dp),
        medium = RoundedCornerShape(0.dp),
        large = RoundedCornerShape(0.dp),
        extraLarge = RoundedCornerShape(0.dp),
    )

@Composable
fun PodsiloTheme(
    preference: ThemePreference = ThemePreference.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark =
        when (preference) {
            ThemePreference.LIGHT -> false
            ThemePreference.DARK -> true
            ThemePreference.SYSTEM -> isSystemInDarkTheme()
        }
    MaterialTheme(
        colorScheme = if (dark) PodsiloDarkScheme else PodsiloLightScheme,
        shapes = PodsiloShapes,
        content = content,
    )
}
