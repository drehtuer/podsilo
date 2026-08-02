// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.settings

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.drehtuer.podsilo.core.model.port.LoginFlowException
import net.drehtuer.podsilo.core.model.port.LoginFlowFailure
import net.drehtuer.podsilo.core.model.port.NextcloudCredentials
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * S5. The load-bearing test in this file is the last one: **the app password is never stored unless
 * gpoddersync answered 200**, because connecting to a Nextcloud without it leaves the user with an
 * app that silently syncs nothing (`docs/UI.md` §8).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectViewModelTest {
    private val settings = FakeSettingsRepository()
    private val client = FakeLoginFlowClient()
    private val syncTrigger = RecordingSyncTrigger()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = ConnectViewModel(client, settings, syncTrigger)

    @Test
    fun `a successful flow stores the credentials and kicks off the first sync`() =
        runTest {
            val viewModel = viewModel()
            viewModel.onEvent(ConnectEvent.HostChanged("cloud.example.org"))

            viewModel.effect.test {
                viewModel.onEvent(ConnectEvent.Submit)

                // The browser is opened by the host, not by :core:gpodder (docs/decisions/0007).
                assertEquals(
                    ConnectEffect.OpenBrowser("https://cloud.example.org/login/flow"),
                    awaitItem(),
                )
                assertEquals(ConnectEffect.Connected, awaitItem())
            }

            // The server's own canonical URL, not the typed one — a reverse proxy legitimately
            // returns a different host.
            assertEquals(
                NextcloudCredentials("https://cloud.example.org", "author", "app-password"),
                settings.storedCredentials,
            )
            assertEquals(1, syncTrigger.syncs)
        }

    @Test
    fun `a typed scheme is stripped rather than rejected`() =
        runTest {
            // The field renders a fixed https:// prefix, so a pasted URL must not double it.
            val viewModel = viewModel()
            viewModel.onEvent(ConnectEvent.HostChanged("https://cloud.example.org/"))

            viewModel.effect.test {
                viewModel.onEvent(ConnectEvent.Submit)
                cancelAndIgnoreRemainingEvents()
            }

            assertEquals(listOf("https://cloud.example.org"), client.startedWith)
        }

    @Test
    fun `a deliberate http scheme is honoured, not silently upgraded`() =
        runTest {
            // A self-hosted instance on a LAN is a real setup; upgrading it would fail confusingly.
            val viewModel = viewModel()
            viewModel.onEvent(ConnectEvent.HostChanged("http://nextcloud.lan"))

            viewModel.effect.test {
                viewModel.onEvent(ConnectEvent.Submit)
                cancelAndIgnoreRemainingEvents()
            }

            assertEquals(listOf("http://nextcloud.lan"), client.startedWith)
        }

    @Test
    fun `an empty or spaced host is rejected before anything is contacted`() {
        assertEquals(ConnectError.UNREACHABLE, hostProblem(""))
        assertEquals(ConnectError.UNREACHABLE, hostProblem("cloud example org"))
        assertNull(hostProblem("cloud.example.org"))
        assertNull("a subdirectory install is legal", hostProblem("example.org/nextcloud"))
    }

    @Test
    fun `an address that does not resolve says check the spelling, not "not a Nextcloud"`() =
        runTest {
            // Found by running the manual probe against a host with no DNS record: every start
            // failure used to collapse into NOT_NEXTCLOUD, which sends the user to check their
            // server instead of their typing (docs/UI.md §8's table exists to prevent exactly this).
            client.startResult =
                Result.failure(LoginFlowException(LoginFlowFailure.UNREACHABLE, "Name or service not known"))
            val viewModel = viewModel()
            viewModel.onEvent(ConnectEvent.HostChanged("cloud.example.org"))

            viewModel.onEvent(ConnectEvent.Submit)

            assertEquals(ConnectError.UNREACHABLE, viewModel.state.value.inlineError)
        }

    @Test
    fun `an untrusted certificate says so rather than blaming the address`() =
        runTest {
            client.startResult = Result.failure(LoginFlowException(LoginFlowFailure.TLS, "cert"))
            val viewModel = viewModel()
            viewModel.onEvent(ConnectEvent.HostChanged("cloud.example.org"))

            viewModel.onEvent(ConnectEvent.Submit)

            assertEquals(ConnectError.TLS, viewModel.state.value.inlineError)
        }

    @Test
    fun `an untyped failure still degrades to the step's most likely cause`() {
        assertEquals(ConnectError.NOT_NEXTCLOUD, IllegalStateException("?").asConnectError(ConnectError.NOT_NEXTCLOUD))
        assertEquals(ConnectError.ABANDONED, IllegalStateException("?").asConnectError(ConnectError.ABANDONED))
    }

    @Test
    fun `a server that is not a Nextcloud says so and stores nothing`() =
        runTest {
            client.startResult = Result.failure(IllegalStateException("404"))
            val viewModel = viewModel()
            viewModel.onEvent(ConnectEvent.HostChanged("example.org"))

            viewModel.onEvent(ConnectEvent.Submit)

            assertEquals(ConnectError.NOT_NEXTCLOUD, viewModel.state.value.inlineError)
            assertEquals(ConnectUiState.Phase.Editing, viewModel.state.value.phase)
            assertNull(settings.storedCredentials)
        }

    @Test
    fun `an abandoned authorization returns to editing with the host intact`() =
        runTest {
            client.pollResult = Result.failure(IllegalStateException("timeout"))
            val viewModel = viewModel()
            viewModel.onEvent(ConnectEvent.HostChanged("cloud.example.org"))

            viewModel.onEvent(ConnectEvent.Submit)

            assertEquals(ConnectError.ABANDONED, viewModel.state.value.inlineError)
            assertEquals("cloud.example.org", viewModel.state.value.host)
            assertNull(settings.storedCredentials)
        }

    @Test
    fun `a Nextcloud without gpoddersync never gets the app password stored`() =
        runTest {
            // The whole point of the third step: a completed login flow proves the server is a
            // Nextcloud and the password works, and says nothing about gpoddersync being installed.
            client.verifyResult = Result.failure(IllegalStateException("404"))
            val viewModel = viewModel()
            viewModel.onEvent(ConnectEvent.HostChanged("cloud.example.org"))

            viewModel.onEvent(ConnectEvent.Submit)

            assertEquals(ConnectError.NO_GPODDERSYNC, viewModel.state.value.inlineError)
            assertNull("the app password must be discarded, not stored", settings.storedCredentials)
            assertEquals(0, syncTrigger.syncs)
        }

    @Test
    fun `the field is read-only while a request is in flight`() =
        runTest {
            // Accepting an edit mid-flow would leave the poll running against a different host than
            // the one on screen.
            val viewModel = viewModel()
            viewModel.onEvent(ConnectEvent.HostChanged("cloud.example.org"))
            // Force a phase that is not Editing by failing the verify step after the flow ran.
            client.verifyResult = Result.failure(IllegalStateException("404"))

            viewModel.onEvent(ConnectEvent.Submit)
            // Back in Editing after the failure, so an edit is accepted again.
            viewModel.onEvent(ConnectEvent.HostChanged("other.example.org"))

            assertEquals("other.example.org", viewModel.state.value.host)
        }

    @Test
    fun `changing an existing instance pre-fills the host without its scheme`() =
        runTest {
            settings.setNextcloudCredentials(
                NextcloudCredentials("https://cloud.example.org", "author", "app-password"),
            )
            val viewModel = viewModel()

            viewModel.prefillFromCurrentAccount()

            assertEquals("cloud.example.org", viewModel.state.value.host)
            assertTrue(viewModel.state.value.isChangingExisting)
        }

    @Test
    fun `cancel from the editing state dismisses the dialog`() =
        runTest {
            val viewModel = viewModel()

            viewModel.effect.test {
                viewModel.onEvent(ConnectEvent.Cancel)

                assertEquals(ConnectEffect.Dismiss, awaitItem())
            }
        }
}
