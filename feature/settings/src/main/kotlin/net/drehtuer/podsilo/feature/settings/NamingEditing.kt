// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.settings

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Inserts [insertion] at the cursor and leaves the cursor **after** it.
 *
 * Two things this fixes, both reported from the device:
 *
 * 1. Tapping a placeholder chip used to append to the end of the template regardless of where the
 *    cursor was, so building `{date}_{title}` by tapping meant retyping the order by hand.
 * 2. The cursor then jumped to position 0, because the new text arrived back from the view model as
 *    a plain `String` with no selection information — Compose has nowhere to put the caret and
 *    resets it. Carrying a [TextFieldValue] is what keeps the caret where the user left it.
 *
 * A selection (rather than a bare caret) is **replaced**, which is what every other text field on
 * the platform does with a paste.
 */
internal fun insertAtCursor(
    current: TextFieldValue,
    insertion: String,
): TextFieldValue {
    val start = current.selection.min
    val end = current.selection.max
    val text = current.text.take(start) + insertion + current.text.drop(end)
    return TextFieldValue(text = text, selection = TextRange(start + insertion.length))
}

/**
 * Adopts [incoming] only when it is genuinely different text.
 *
 * The view model echoes the template back on every keystroke — it persists as you type — and
 * rebuilding the [TextFieldValue] from that echo is what threw the caret to the start. So the field
 * keeps its own value while the text agrees, and re-seeds only when something *else* changed it,
 * which in practice means *Reset to default*. The caret then goes to the end, where a fresh value
 * expects it.
 */
internal fun syncedFromState(
    current: TextFieldValue,
    incoming: String,
): TextFieldValue =
    if (current.text == incoming) {
        current
    } else {
        TextFieldValue(text = incoming, selection = TextRange(incoming.length))
    }
