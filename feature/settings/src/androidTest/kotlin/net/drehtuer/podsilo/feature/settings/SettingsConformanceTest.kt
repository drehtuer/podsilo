// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **S4 and S5 against `docs/UI.md` §7–§8, on a real Compose runtime.**
 *
 * These four are the ones where being wrong has a consequence beyond looking untidy: a password
 * field would mean the app handles a credential it promised never to touch, an ungated restore would
 * drop the ledger behind a screen that cannot show it, and a bulk mark-as-played without its
 * preview writes to a shared action log that no undo can reach.
 */
@RunWith(AndroidJUnit4::class)
class SettingsConformanceTest {
    @get:Rule
    val compose = createComposeRule()

    private val settingsEvents = mutableListOf<SettingsEvent>()
    private val connectEvents = mutableListOf<ConnectEvent>()

    private val connected =
        NextcloudUi(instanceUrl = "https://cloud.example.org", loginName = "podsilo")

    private fun renderSettings(state: SettingsUiState) {
        compose.setContent {
            SettingsScreen(state = state, onEvent = { settingsEvents += it }, onBack = {})
        }
    }

    /**
     * §8: S5 is Login Flow v2 **only**. There is no password field in the module and there must
     * never be one — the app never sees the account password, and an app password is minted by
     * Nextcloud rather than typed here (CLAUDE.md §5).
     */
    @Test
    fun theConnectionDialogHasNoPasswordFieldEver() {
        compose.setContent {
            ConnectDialog(state = ConnectUiState(host = ""), onEvent = { connectEvents += it })
        }

        // One text field: the host. Anything else is the bug this test exists for.
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        compose.onAllNodes(hasText("Password", substring = true)).assertCountEquals(0)
        compose.onAllNodes(hasText("password", substring = true)).assertCountEquals(0)
    }

    /**
     * `docs/decisions/0018`'s 2026-08-02 amendment, and the author's rule: **no backup is loaded
     * until the Nextcloud login has succeeded.** The row says why rather than silently doing nothing.
     */
    @Test
    fun restoreIsRefusedAndExplainsItselfWhileUnconnected() {
        renderSettings(SettingsUiState(version = "0.1.0"))

        compose.onNodeWithText("Connect Nextcloud first").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Restore from backup").performScrollTo().performClick()

        assertTrue(settingsEvents.none { it is SettingsEvent.RestoreDatabaseClicked })
    }

    @Test
    fun restoreBecomesAvailableOnceAnAccountExists() {
        renderSettings(SettingsUiState(version = "0.1.0", nextcloud = connected))

        compose.onNodeWithText("Restore from backup").performScrollTo().performClick()

        assertTrue(settingsEvents.contains(SettingsEvent.RestoreDatabaseClicked))
    }

    /**
     * §7 and `docs/decisions/0013`: the restore warning is **mandatory**, states that it cannot be
     * undone, and appears before any file is chosen.
     */
    @Test
    fun theRestoreWarningNamesTheConsequenceFirst() {
        renderSettings(
            SettingsUiState(version = "0.1.0", nextcloud = connected, restoreConfirmationVisible = true),
        )

        compose.onNode(hasText("cannot be undone", substring = true)).assertIsDisplayed()
        compose.onNode(hasText("Nextcloud account is not touched", substring = true)).assertIsDisplayed()
    }

    /**
     * §7: the bulk *mark as played* preview names the exact count and says in words that the state
     * reaches Nextcloud — the safeguard that replaced the old rule against writing backlog rows at
     * all (`docs/decisions/0013`). Weakening this is explicitly forbidden.
     */
    @Test
    fun theBulkPreviewNamesTheCountAndMentionsNextcloud() {
        renderSettings(
            SettingsUiState(
                version = "0.1.0",
                nextcloud = connected,
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

        compose.onNode(hasText("222", substring = true)).assertIsDisplayed()
        compose.onNode(hasText("sent to Nextcloud", substring = true)).assertIsDisplayed()
    }

    /** §7: there is no Save button anywhere — every control commits on change. */
    @Test
    fun settingsHasNoSaveButton() {
        renderSettings(SettingsUiState(version = "0.1.0", nextcloud = connected))

        compose.onAllNodes(hasText("Save")).assertCountEquals(0)
        compose.onAllNodes(hasText("Apply changes")).assertCountEquals(0)
    }
}
