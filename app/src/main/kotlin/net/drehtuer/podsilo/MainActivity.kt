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
import net.drehtuer.podsilo.core.model.greeting

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
                text = greeting(),
                modifier = Modifier.wrapContentSize(Alignment.Center),
            )
        }
    }
}
