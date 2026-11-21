package com.gohj99.galaxywatchhelper.utils

import androidx.compose.runtime.staticCompositionLocalOf

/** Shared screen data supplied by the activity to all module-related routes. */
val LocalMainScreenState = staticCompositionLocalOf { ModuleManager }
