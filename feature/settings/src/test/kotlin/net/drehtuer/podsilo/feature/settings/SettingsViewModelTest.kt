// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.settings

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.core.model.port.BulkScope
import net.drehtuer.podsilo.core.model.port.BulkScopeKind
import net.drehtuer.podsilo.core.model.port.NextcloudCredentials
import net.drehtuer.podsilo.core.model.port.OlderThan
import net.drehtuer.podsilo.core.model.port.SwipeAction
import net.drehtuer.podsilo.core.model.port.SwipeDirection
import net.drehtuer.podsilo.core.model.port.ThemePreference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * S4. Most controls are one-liners that persist; the tests that carry weight are the ones about the
 * bulk *mark as played*, which is the only operation on this screen that reaches the shared action
 * log and cannot be undone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val settings = FakeSettingsRepository()
    private val ledger = FakeLedgerRepository()
    private val list = FakeListRepository()
    private val feeds = FakeFeedRepository()
    private val folder = MutableStateFlow(FolderUi())
    private val lastSync = MutableStateFlow<Instant?>(null)
    private val now = Instant.parse("2026-08-02T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(counts: FakeCounts = FakeCounts()) =
        SettingsViewModel(
            settingsRepository = settings,
            ledgerRepository = ledger,
            listRepository = list,
            feedRepository = feeds,
            folderStatus = { folder },
            counts = counts,
            namingSummary = { "Der Podcast/{date}_{title}.mp3" },
            syncStatus = { lastSync },
            clock = clock,
            version = "0.1.0",
            zone = ZoneOffset.UTC,
        )

    @Test
    fun `every control commits immediately — there is no Save button`() =
        runTest {
            val viewModel = viewModel()

            viewModel.onEvent(SettingsEvent.MobileDataChanged(true))
            viewModel.onEvent(SettingsEvent.ThemeChanged(ThemePreference.DARK))
            viewModel.onEvent(SettingsEvent.OlderThanChanged(OlderThan.MONTH_3))

            assertTrue(settings.mobileData.value)
            assertEquals(ThemePreference.DARK, settings.theme.value)
            assertEquals(OlderThan.MONTH_3, settings.olderThan.value)
        }

    @Test
    fun `assigning an action a direction already holds swaps them rather than duplicating`() =
        runTest {
            // Defaults are right = DOWNLOAD, left = MARK_AS_PLAYED. Giving the left DOWNLOAD must
            // not leave both on DOWNLOAD, which would make one action unreachable (docs/UI.md §7).
            val viewModel = viewModel()

            viewModel.onEvent(SettingsEvent.SwipeChanged(SwipeDirection.LEFT, SwipeAction.DOWNLOAD))

            assertEquals(SwipeAction.DOWNLOAD, settings.swipe.value.left)
            assertEquals(SwipeAction.MARK_AS_PLAYED, settings.swipe.value.right)
        }

    @Test
    fun `both directions may be disabled at once`() =
        runTest {
            val viewModel = viewModel()

            viewModel.onEvent(SettingsEvent.SwipeChanged(SwipeDirection.LEFT, SwipeAction.NONE))
            viewModel.onEvent(SettingsEvent.SwipeChanged(SwipeDirection.RIGHT, SwipeAction.NONE))

            assertEquals(SwipeAction.NONE, settings.swipe.value.left)
            assertEquals(SwipeAction.NONE, settings.swipe.value.right)
        }

    @Test
    fun `previewing a bulk mark writes absolutely nothing`() =
        runTest {
            // The safeguard that replaced the old rule against writing backlog rows at all
            // (docs/decisions/0013): the count is named before anything happens.
            list.seed(episode("a"), episode("b"), episode("c"))
            feeds.seed(feed(title = "Der Podcast"))
            val viewModel = viewModel()

            viewModel.state.test {
                viewModel.onEvent(SettingsEvent.BulkPreviewRequested(BulkScope(BulkScopeKind.ALL_UNDECIDED)))

                val pending = awaitUntil { it.pendingBulk != null }.pendingBulk
                assertNotNull(pending)
                assertEquals(3, pending?.count)
                assertEquals("Der Podcast", pending?.perFeed?.single()?.feedTitle)
            }
            assertTrue("preview must not write", ledger.writes.isEmpty())
        }

    @Test
    fun `confirming writes SKIPPED rows in one transaction, unsynced, and never QUEUED`() =
        runTest {
            list.seed(episode("a"), episode("b"))
            feeds.seed(feed())
            val viewModel = viewModel()
            viewModel.state.test {
                viewModel.onEvent(SettingsEvent.BulkPreviewRequested(BulkScope(BulkScopeKind.ALL_UNDECIDED)))
                awaitUntil { it.pendingBulk != null }

                viewModel.onEvent(SettingsEvent.BulkConfirmed)
                awaitUntil { it.pendingBulk == null }
            }

            // One batch: bulk triage routinely covers hundreds of rows and the list is on screen.
            val batch = ledger.writes.single()
            assertEquals(2, batch.size)
            assertTrue(batch.all { it.state == LedgerState.SKIPPED })
            // Never QUEUED — the no-auto-download invariant is untouched by this operation.
            assertFalse(batch.any { it.state == LedgerState.QUEUED })
            // The durable row exists before anything is posted; only a 2xx flips this (CLAUDE.md §5).
            assertTrue(batch.none { it.syncedToServer })
        }

    @Test
    fun `cancelling the preview writes nothing and clears the dialog`() =
        runTest {
            list.seed(episode("a"))
            feeds.seed(feed())
            val viewModel = viewModel()

            viewModel.state.test {
                viewModel.onEvent(SettingsEvent.BulkPreviewRequested(BulkScope(BulkScopeKind.ALL_UNDECIDED)))
                awaitUntil { it.pendingBulk != null }

                viewModel.onEvent(SettingsEvent.BulkCancelled)
                assertNull(awaitUntil { it.pendingBulk == null }.pendingBulk)
            }
            assertTrue(ledger.writes.isEmpty())
        }

    @Test
    fun `an older-than preview uses the stored cutoff, not the whole catalogue`() =
        runTest {
            settings.olderThan.value = OlderThan.MONTH_3
            // now is 2026-08-02; the cutoff is 2026-05-02.
            list.seed(
                episode("old", pubDate = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()),
                episode("recent", pubDate = Instant.parse("2026-07-30T00:00:00Z").toEpochMilli()),
            )
            feeds.seed(feed())
            val viewModel = viewModel()

            viewModel.state.test {
                viewModel.onEvent(SettingsEvent.BulkPreviewRequested(BulkScope(BulkScopeKind.OLDER_THAN)))

                assertEquals(1, awaitUntil { it.pendingBulk != null }.pendingBulk?.count)
            }
        }

    @Test
    fun `nothing to mark says so rather than opening an empty dialog`() =
        runTest {
            val viewModel = viewModel()

            viewModel.effect.test {
                viewModel.onEvent(SettingsEvent.BulkPreviewRequested(BulkScope(BulkScopeKind.ALL_UNDECIDED)))

                assertTrue((awaitItem() as SettingsEffect.ShowMessage).text.contains("Nothing to mark"))
            }
            assertNull(viewModel.state.value.pendingBulk)
        }

    @Test
    fun `disconnecting clears the credentials and keeps the ledger`() =
        runTest {
            // The ledger has no foreign key to feeds (architecture §4), so reconnecting must not
            // re-download a back catalogue the user already handled.
            settings.setNextcloudCredentials(
                NextcloudCredentials("https://cloud.example.org", "author", "app-password"),
            )
            ledger.upsertAll(listOf(episode("a").toSkippedRow(0)))
            val viewModel = viewModel()

            viewModel.onEvent(SettingsEvent.DisconnectClicked)

            assertNull(settings.storedCredentials)
            assertNotNull("the ledger row survives", ledger.get("a"))
        }

    @Test
    fun `a revoked folder grant is a distinct state from never having chosen one`() =
        runTest {
            folder.value = FolderUi(label = "Podcasts", state = FolderState.REVOKED)

            viewModel().state.test {
                assertEquals(FolderState.REVOKED, awaitItem().downloadFolder.state)
            }
        }

    @Test
    fun `the last sync line distinguishes nothing-to-do from things-stuck`() {
        val connected = NextcloudUi(instanceUrl = "https://cloud.example.org", lastSyncAt = now.minusSeconds(600))

        assertEquals("10 min ago", lastSyncLine(connected, now))
        assertEquals("10 min ago · 1 action pending", lastSyncLine(connected.copy(outboxDepth = 1), now))
        assertEquals("10 min ago · 3 actions pending", lastSyncLine(connected.copy(outboxDepth = 3), now))
        assertEquals("never", lastSyncLine(NextcloudUi(), now))
    }
}

private suspend fun app.cash.turbine.ReceiveTurbine<SettingsUiState>.awaitUntil(
    predicate: (SettingsUiState) -> Boolean,
): SettingsUiState {
    while (true) {
        val item = awaitItem()
        if (predicate(item)) return item
    }
}
