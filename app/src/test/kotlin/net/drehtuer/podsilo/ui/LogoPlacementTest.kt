// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import net.drehtuer.podsilo.core.ui.PODSILO_MARK_TEST_TAG
import net.drehtuer.podsilo.ui.activity.ActivityScreen
import net.drehtuer.podsilo.ui.activity.ActivityUiState
import net.drehtuer.podsilo.ui.errorlog.ErrorLogScreen
import net.drehtuer.podsilo.ui.errorlog.ErrorLogUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.ZoneOffset

/**
 * Where the brand mark appears across S7 and S8 (`docs/UI.adoc` §C4 and §5): nowhere.
 *
 * Both are screens the user reaches when something needs attention, which is precisely when a mark
 * costs the most — §5's "a mark there competes with the one thing the user is looking for". The
 * companion tests are `LogoPlacementTest` in `:feature:episodes` (S1–S3) and `:feature:settings`
 * (S4–S6); between the three, every screen in the app is counted.
 */
@RunWith(RobolectricTestRunner::class)
class LogoPlacementTest {
    @get:Rule
    val compose = createComposeRule()

    private fun marks() = compose.onAllNodesWithTag(PODSILO_MARK_TEST_TAG)

    @Test
    fun `S7 has no mark, idle or busy`() {
        compose.setContent {
            ActivityScreen(
                state = ActivityUiState(),
                onEvent = {},
                onBack = {},
                now = Instant.parse("2026-08-02T12:00:00Z"),
            )
        }

        marks().assertCountEquals(0)
    }

    @Test
    fun `S8 has no mark`() {
        compose.setContent {
            ErrorLogScreen(state = ErrorLogUiState(), onEvent = {}, onBack = {}, zone = ZoneOffset.UTC)
        }

        marks().assertCountEquals(0)
    }
}
