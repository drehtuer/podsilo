// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import net.drehtuer.podsilo.core.model.port.OlderThan
import net.drehtuer.podsilo.core.model.port.ThemePreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * S4, S5 and S6 rendering, under Robolectric (Tier 1 — headless, no emulator).
 *
 * The assertions worth having here are the ones about what must **not** be on screen: no password
 * field anywhere in the connection dialog, and no `{ext}` chip in the naming editor.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val settingsEvents = mutableListOf<SettingsEvent>()
    private val connectEvents = mutableListOf<ConnectEvent>()
    private val namingEvents = mutableListOf<NamingEvent>()
    private val now = Instant.parse("2026-08-02T12:00:00Z")

    private fun renderSettings(state: SettingsUiState) {
        compose.setContent {
            SettingsScreen(state = state, onEvent = { settingsEvents += it }, onBack = {}, now = now)
        }
    }

    @Test
    fun `an unconnected instance row is empty rather than showing a placeholder`() {
        renderSettings(SettingsUiState(version = "0.1.0"))

        compose.onNodeWithText("Instance").assertIsDisplayed()
        compose.onNodeWithText("Connect Nextcloud").assertIsDisplayed()
        // The Account and Last sync rows are hidden entirely when nothing is connected (§7).
        compose.onAllNodes(hasText("Account")).assertCountEquals(0)
        compose.onAllNodes(hasText("Last sync", substring = true)).assertCountEquals(0)
    }

    @Test
    fun `a connected instance shows the account and offers to change or disconnect`() {
        renderSettings(
            SettingsUiState(
                nextcloud =
                    NextcloudUi(
                        instanceUrl = "https://cloud.example.org",
                        loginName = "author",
                        lastSyncAt = now.minusSeconds(600),
                        outboxDepth = 3,
                    ),
                version = "0.1.0",
            ),
        )

        compose.onNodeWithText("https://cloud.example.org").assertIsDisplayed()
        compose.onNodeWithText("author").assertIsDisplayed()
        compose.onNode(hasText("3 actions pending", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("Change Nextcloud instance").assertIsDisplayed()
        compose.onNodeWithText("Disconnect").assertIsDisplayed()
    }

    @Test
    fun `a revoked folder grant reads as a warning, not as unchosen`() {
        renderSettings(
            SettingsUiState(
                downloadFolder = FolderUi(label = "Podcasts", state = FolderState.REVOKED),
                version = "0.1.0",
            ),
        )

        compose.onNode(hasText("not available", substring = true)).assertIsDisplayed()
    }

    @Test
    fun `Preview and apply is disabled while the older-than rule is off`() {
        // With no cutoff the button would have nothing to preview, and offering it invites a tap
        // that does nothing.
        renderSettings(SettingsUiState(markOldOlderThan = OlderThan.OFF, version = "0.1.0"))

        compose.onNodeWithText("Preview & apply").performClick()

        assertTrue(settingsEvents.none { it is SettingsEvent.BulkPreviewRequested })
    }

    @Test
    fun `the theme control commits on tap, with no Save button anywhere`() {
        renderSettings(SettingsUiState(theme = ThemePreference.SYSTEM, version = "0.1.0"))

        compose.onNodeWithText("Dark").performScrollTo().performClick()

        assertEquals(listOf(SettingsEvent.ThemeChanged(ThemePreference.DARK)), settingsEvents)
        compose.onAllNodes(hasText("Save")).assertCountEquals(0)
    }

    @Test
    fun `the bulk dialog names the count and says the state goes to Nextcloud`() {
        renderSettings(
            SettingsUiState(
                version = "0.1.0",
                pendingBulk =
                    BulkConfirmation(
                        scope =
                            net.drehtuer.podsilo.core.model.port.BulkScope(
                                net.drehtuer.podsilo.core.model.port.BulkScopeKind.ALL_UNDECIDED,
                            ),
                        perFeed = listOf(FeedCount("Der Podcast", 128), FeedCount("Lage der Nation", 94)),
                    ),
            ),
        )

        compose.onNode(hasText("Mark 222 episodes as played?", substring = true)).assertIsDisplayed()
        compose.onNode(hasText("sent to Nextcloud", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()

        assertTrue(settingsEvents.contains(SettingsEvent.BulkCancelled))
    }

    @Test
    fun `the remainder line keeps the listed numbers adding up to the title`() {
        val many = (1..8).map { FeedCount("Feed $it", 10) }
        val confirmation =
            BulkConfirmation(
                scope =
                    net.drehtuer.podsilo.core.model.port.BulkScope(
                        net.drehtuer.podsilo.core.model.port.BulkScopeKind.ALL_UNDECIDED,
                    ),
                perFeed = many,
            )

        assertEquals("… 3 more podcasts   30", remainderLine(confirmation))
        assertEquals(null, remainderLine(confirmation.copy(perFeed = many.take(3))))
    }

    @Test
    fun `the connection dialog has no password field, ever`() {
        // Login Flow v2 exclusively (CLAUDE.md §5). This assertion is the guarantee.
        compose.setContent {
            ConnectDialog(state = ConnectUiState(), onEvent = { connectEvents += it })
        }

        compose.onNodeWithText("Nextcloud address").assertIsDisplayed()
        compose.onAllNodes(hasText("Password", substring = true)).assertCountEquals(0)
        compose.onAllNodes(hasText("Username", substring = true)).assertCountEquals(0)
    }

    @Test
    fun `while awaiting authorization the dialog says what it is waiting for and keeps Cancel`() {
        compose.setContent {
            ConnectDialog(
                state = ConnectUiState(phase = ConnectUiState.Phase.AwaitingAuthorization),
                onEvent = { connectEvents += it },
            )
        }

        compose.onNode(hasText("Waiting for authorization", substring = true)).assertIsDisplayed()
        // The primary button is gone; Cancel stays and aborts the poll (docs/UI.md §8).
        compose.onAllNodes(hasText("Request authorization")).assertCountEquals(0)
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(connectEvents.contains(ConnectEvent.Cancel))
    }

    @Test
    fun `each connection failure has a plain sentence, never a stack trace`() {
        ConnectError.entries.forEach { error ->
            val message = error.message
            assertTrue("$error had an empty message", message.isNotBlank())
            assertTrue("$error read like a stack trace", !message.contains("Exception"))
        }
    }

    @Test
    fun `the naming editor offers only placeholders the engine resolves`() {
        compose.setContent {
            NamingScreen(
                state =
                    NamingUiState(
                        previews = listOf(NamingPreviewLine(PreviewCase.RECENT_EPISODE, "Der Podcast/x.mp3")),
                    ),
                onEvent = { namingEvents += it },
                onBack = {},
            )
        }

        // Twice: once as the folder template's value, once as the chip that inserts it.
        compose.onAllNodes(hasText("{podcast}")).assertCountEquals(2)
        // Absent on purpose: the extension is appended after resolution, not resolved (CLAUDE.md §6).
        compose.onAllNodes(hasText("{ext}")).assertCountEquals(0)
        compose.onNode(hasText("never renamed", substring = true)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `an invalid template shows its reason under the field`() {
        compose.setContent {
            NamingScreen(
                state =
                    NamingUiState(
                        validation =
                            NamingUiState.Validation.Invalid(NamingField.FILE, "The file template can't be empty."),
                    ),
                onEvent = { namingEvents += it },
                onBack = {},
            )
        }

        compose.onNode(hasText("can't be empty", substring = true)).assertIsDisplayed()
    }
}
