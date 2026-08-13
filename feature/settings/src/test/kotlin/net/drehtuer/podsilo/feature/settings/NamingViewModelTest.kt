// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.drehtuer.podsilo.core.model.port.NamingSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneOffset

/**
 * S6. The editor owns no naming logic, so these tests are about the two things it *does* decide:
 * what reaches the previews, and what is allowed to be persisted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NamingViewModelTest {
    private val settings = FakeSettingsRepository()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(recent: net.drehtuer.podsilo.core.model.Episode? = null) =
        NamingViewModel(settings, { recent }, ZoneOffset.UTC)

    @Test
    fun `the defaults render the documented example`() =
        runTest {
            val state = viewModel().state.value

            assertEquals(
                "Der Podcast/20260714_Warum Hamburg immer regnet.mp3",
                state.previews.single { it.case == PreviewCase.RECENT_EPISODE }.resolved,
            )
        }

    @Test
    fun `a missing date renders 00000000, never an empty segment`() =
        runTest {
            // architecture §11. If this ever renders an empty segment the engine regressed, not the screen —
            // a filename must never begin with a partial date (CLAUDE.md §6).
            val resolved =
                viewModel()
                    .state.value.previews
                    .single { it.case == PreviewCase.MISSING_DATE }
                    .resolved

            assertTrue(resolved, resolved.contains("00000000"))
            assertFalse(resolved.contains("/_"))
        }

    @Test
    fun `the awkward-characters preview keeps none of them`() =
        runTest {
            val resolved =
                viewModel()
                    .state.value.previews
                    .single { it.case == PreviewCase.ILLEGAL_CHARACTERS }
                    .resolved
            val fileName = resolved.substringAfterLast('/')

            assertTrue(fileName, fileName.none { it in "<>:\"\\|?*" })
        }

    @Test
    fun `the over-long preview is truncated to a usable length`() =
        runTest {
            val resolved =
                viewModel()
                    .state.value.previews
                    .single { it.case == PreviewCase.OVERLONG_TITLE }
                    .resolved
            val fileName = resolved.substringAfterLast('/')

            // The engine budgets by UTF-8 bytes, not characters (CLAUDE.md §6).
            assertTrue("$fileName was ${fileName.toByteArray().size} bytes", fileName.toByteArray().size <= 255)
        }

    @Test
    fun `a real episode replaces the sample in the first preview line`() =
        runTest {
            val state = viewModel(recent = episode("Meine echte Folge")).state.value

            assertTrue(
                state.previews
                    .single { it.case == PreviewCase.RECENT_EPISODE }
                    .resolved
                    .contains("Meine echte Folge"),
            )
        }

    @Test
    fun `a valid template is persisted as it is typed`() =
        runTest {
            val viewModel = viewModel()

            viewModel.onEvent(NamingEvent.FileTemplateChanged("{date:yyyy-MM-dd}_{title}"))

            assertEquals("{date:yyyy-MM-dd}_{title}", settings.naming.value.fileTemplate)
            assertEquals(NamingUiState.Validation.Valid, viewModel.state.value.validation)
        }

    @Test
    fun `an empty file template is refused and never reaches storage`() =
        runTest {
            // Otherwise a half-deleted field would name the next download after nothing at all.
            val viewModel = viewModel()
            val before = settings.naming.value.fileTemplate

            viewModel.onEvent(NamingEvent.FileTemplateChanged(""))

            assertTrue(viewModel.state.value.validation is NamingUiState.Validation.Invalid)
            assertEquals(before, settings.naming.value.fileTemplate)
        }

    @Test
    fun `reset restores both documented defaults`() =
        runTest {
            val viewModel = viewModel()
            viewModel.onEvent(NamingEvent.FileTemplateChanged("{title}"))

            viewModel.onEvent(NamingEvent.ResetToDefault)

            assertEquals(NamingSettings.DEFAULT_FOLDER_TEMPLATE, settings.naming.value.folderTemplate)
            assertEquals(NamingSettings.DEFAULT_FILE_TEMPLATE, settings.naming.value.fileTemplate)
        }

    @Test
    fun `the placeholder chips are exactly what the engine resolves`() =
        runTest {
            // Offering a chip the engine does not know would put its literal text in a filename;
            // `{ext}` is deliberately absent because the extension is appended, not resolved.
            val placeholders = viewModel().state.value.placeholders

            assertEquals(
                listOf("{podcast}", "{title}", "{date}", "{description}", "{guid_short}"),
                placeholders,
            )
            assertFalse(placeholders.contains("{ext}"))
        }
}
