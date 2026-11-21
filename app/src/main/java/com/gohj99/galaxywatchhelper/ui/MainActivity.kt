package com.gohj99.galaxywatchhelper.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.gohj99.galaxywatchhelper.ui.main.MainScreen
import com.gohj99.galaxywatchhelper.ui.theme.GalaxyWatchHelperTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GalaxyWatchHelperTheme {
                MainScreen()
            }
        }
    }
}
