// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.settings

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The caret rules for S6, reported from the device: tapping a placeholder appended to the end of the
 * template no matter where the cursor was, and the cursor then jumped to position 0.
 */
class NamingEditingTest {
    @Test
    fun `inserting puts the text at the caret and the caret after it`() {
        val current = TextFieldValue("{date}_{title}", TextRange(6))

        val result = insertAtCursor(current, "{podcast}")

        assertEquals("{date}{podcast}_{title}", result.text)
        // After the insertion, ready to keep typing — not at 0, and not at the end of the line.
        assertEquals(TextRange(15), result.selection)
    }

    @Test
    fun `inserting at the start and at the end both work`() {
        assertEquals("{date}x", insertAtCursor(TextFieldValue("x", TextRange(0)), "{date}").text)
        assertEquals("x{date}", insertAtCursor(TextFieldValue("x", TextRange(1)), "{date}").text)
    }

    @Test
    fun `a selection is replaced, as a paste would`() {
        val current = TextFieldValue("{date}_{title}", TextRange(0, 6))

        val result = insertAtCursor(current, "{guid_short}")

        assertEquals("{guid_short}_{title}", result.text)
        assertEquals(TextRange(12), result.selection)
    }

    @Test
    fun `an echo of the same text leaves the caret alone`() {
        // The view model persists on every keystroke and echoes the template back. Rebuilding the
        // field from that echo is what threw the caret to 0.
        val current = TextFieldValue("{date}_{title}", TextRange(3))

        assertSame(current, syncedFromState(current, "{date}_{title}"))
    }

    @Test
    fun `genuinely different text is adopted, with the caret at the end`() {
        // In practice this is Reset to default: something other than typing changed the value.
        val current = TextFieldValue("{title}", TextRange(2))

        val result = syncedFromState(current, "{date}_{title}")

        assertEquals("{date}_{title}", result.text)
        assertEquals(TextRange("{date}_{title}".length), result.selection)
    }
}
