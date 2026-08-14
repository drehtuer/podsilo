// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.ui.errorlog

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import net.drehtuer.podsilo.core.model.port.LogCategory
import net.drehtuer.podsilo.core.model.port.LogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.ZoneOffset

/**
 * S8's rendering. The assertions that carry weight are the ones about the *plain sentence first*
 * rule and about Clear being disabled rather than hidden.
 */
@RunWith(RobolectricTestRunner::class)
class ErrorLogScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = mutableListOf<ErrorLogEvent>()

    private fun entry(
        id: Long = 1,
        category: LogCategory = LogCategory.DOWNLOAD,
        message: String = "No space left on device",
        detail: String? = "java.io.IOException: ENOSPC",
        occurrences: Int = 1,
    ) = LogEntry(
        id = id,
        at = Instant.parse("2026-07-31T21:14:00Z").toEpochMilli(),
        category = category,
        feedUrl = "https://example.org/feed.xml",
        episodeKey = "e1",
        message = message,
        detail = detail,
        occurrences = occurrences,
        firstSeenAt = Instant.parse("2026-07-30T04:12:00Z").toEpochMilli(),
    )

    private fun render(state: ErrorLogUiState) {
        compose.setContent {
            ErrorLogScreen(state = state, onEvent = { events += it }, onBack = {}, zone = ZoneOffset.UTC)
        }
    }

    @Test
    fun `an entry leads with its plain sentence and hides the technical half`() {
        render(ErrorLogUiState(entries = listOf(entry())))

        compose.onNodeWithText("No space left on device").assertIsDisplayed()
        compose.onAllNodes(hasText("ENOSPC", substring = true)).assertCountEquals(0)
        compose.onNodeWithText("Show technical detail").assertIsDisplayed()
    }

    @Test
    fun `expanding reveals the detail that gets pasted into a bug report`() {
        render(ErrorLogUiState(entries = listOf(entry()), expanded = setOf(1)))

        compose.onNode(hasText("ENOSPC", substring = true)).assertIsDisplayed()
    }

    @Test
    fun `a collapsed repeat shows its count rather than one row per attempt`() {
        // Without this, one feed failing hourly evicts every one-off error within a day.
        render(ErrorLogUiState(entries = listOf(entry(occurrences = 14))))

        compose.onNode(hasText("× 14", substring = true)).assertIsDisplayed()
    }

    @Test
    fun `Copy, Share and Clear are disabled rather than hidden when the log is empty`() {
        render(ErrorLogUiState(entries = emptyList()))

        compose.onNodeWithContentDescription("Copy all").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Share").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Clear").assertIsNotEnabled()
        compose.onNodeWithText("Nothing has failed.").assertIsDisplayed()
    }

    @Test
    fun `with entries they are enabled`() {
        render(ErrorLogUiState(entries = listOf(entry())))

        compose.onNodeWithContentDescription("Clear").assertIsEnabled()
    }

    @Test
    fun `clearing always confirms, and the dialog names the count and says it is device-local`() {
        render(ErrorLogUiState(entries = listOf(entry(1), entry(2)), pendingClear = true))

        compose.onNode(hasText("Clear all 2 log entries?", substring = true)).assertIsDisplayed()
        compose.onNode(hasText("only on this device", substring = true)).assertIsDisplayed()

        compose.onNodeWithText("Cancel").performClick()
        assertTrue(events.contains(ErrorLogEvent.ClearCancelled))
    }

    @Test
    fun `a filter chip emits and decides nothing locally`() {
        render(ErrorLogUiState(entries = listOf(entry())))

        compose.onNodeWithText("Sync").performClick()

        assertEquals(listOf(ErrorLogEvent.FilterChanged(LogCategory.SYNC)), events)
    }

    @Test
    fun `the cleared message counts what was there`() {
        assertEquals("Cleared 1 log entry.", clearedMessage(1))
        assertEquals("Cleared 4 log entries.", clearedMessage(4))
    }
}
