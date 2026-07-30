// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PodsiloHelloWorld()
        }
    }
}

@Composable
private fun PodsiloHelloWorld() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Text(
                // Placeholder until :feature:episodes/:feature:settings land (CLAUDE.md §10 step 8).
                text = "Podsilo",
                modifier = Modifier.wrapContentSize(Alignment.Center),
            )
        }
    }
}
