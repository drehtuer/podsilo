// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class GreetingTest {
    @Test
    fun `greeting uses the default name`() {
        assertEquals("Hello, Podsilo!", greeting())
    }

    @Test
    fun `greeting uses a supplied name`() {
        assertEquals("Hello, Nextcloud!", greeting("Nextcloud"))
    }
}
