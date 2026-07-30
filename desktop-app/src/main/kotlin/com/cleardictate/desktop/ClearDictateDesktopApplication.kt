package com.cleardictate.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

/**
 * Starts the Windows development application.
 *
 * The current screen is intentionally limited to proving the desktop build and rendering path.
 * Real controls are added only as their production inference implementations become available.
 */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "ClearDictate"
    ) {
        ClearDictateDesktopBootstrapScreen()
    }
}
/**
 * Displays the temporary desktop build-readiness surface.
 */
@Composable
private fun ClearDictateDesktopBootstrapScreen()
{
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "ClearDictate", style = MaterialTheme.typography.headlineLarge)
                Text(
                    text = "Offline inference integration is being verified.",
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}
