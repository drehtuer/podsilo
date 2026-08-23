// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.ui

import net.drehtuer.podsilo.core.model.port.NamingSettings
import net.drehtuer.podsilo.core.model.port.TitleCleanupRuleSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * S1's checklist example goes through the real engine, so a preview that looks right means downloads
 * will be named right (`UI.adoc` §4). A regression test because the first run on a device showed
 * the "—" fallback instead of a filename.
 */
@RunWith(RobolectricTestRunner::class) // The fallback path logs, and android.util.Log is not mocked.
class TemplateNamingPreviewTest {
    private val preview = TemplateNamingPreview()

    @Test
    fun `the default templates render a real example`() {
        val rendered = preview.render(NamingSettings())

        assertNotEquals("the preview must not fall back", "—", rendered)
        assertEquals("Der Podcast/20260714_Warum Hamburg immer regnet.mp3", rendered)
    }

    @Test
    fun `a malformed cleanup rule degrades to a dash rather than taking the home screen down`() {
        // The rules are user-authored raw regexes; S1 renders this inside its state flow.
        val rendered =
            preview.render(NamingSettings(titleCleanupRules = listOf(TitleCleanupRuleSetting("[unclosed", ""))))

        assertEquals("—", rendered)
    }
}
