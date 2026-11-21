package com.gohj99.galaxywatchhelper.ui.theme

import androidx.compose.runtime.Composable
import androidx.wear.compose.material3.MaterialTheme

@Composable
fun GalaxyWatchHelperTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = wearColorScheme,
        typography = MaterialTheme.typography,
        content = content,
    )
}
