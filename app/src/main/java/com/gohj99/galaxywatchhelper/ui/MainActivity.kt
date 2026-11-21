package com.gohj99.galaxywatchhelper.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.compose.navigation.rememberSwipeDismissableNavHostState
import com.gohj99.galaxywatchhelper.ui.main.MainScreen
import com.gohj99.galaxywatchhelper.ui.main.settings.FrameworkInstallerRoute
import com.gohj99.galaxywatchhelper.ui.main.settings.SettingsPage
import com.gohj99.galaxywatchhelper.ui.main.settings.WalletActivitySelectionRoute
import com.gohj99.galaxywatchhelper.ui.main.settings.WalletAppSelectionRoute
import com.gohj99.galaxywatchhelper.ui.main.settings.WalletRedirectionRoute
import com.gohj99.galaxywatchhelper.ui.theme.GalaxyWatchHelperTheme
import com.gohj99.galaxywatchhelper.utils.GlobalAppState
import com.gohj99.galaxywatchhelper.utils.LocalMainScreenState
import com.gohj99.galaxywatchhelper.utils.LocalGlobalAppState
import com.gohj99.galaxywatchhelper.utils.ModuleManager
import com.gohj99.galaxywatchhelper.utils.MainViewModel
import kotlin.getValue

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var appState: GlobalAppState? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        // 初始化启动页
        val splashScreen = installSplashScreen()
        // 设置停留条件 (Condition)
        splashScreen.setKeepOnScreenCondition {
            // 只要 isLoading.value 是 true，就会一直显示启动图标
            viewModel.isLoading.value
        }
        super.onCreate(savedInstanceState)
        setContent {
            GalaxyWatchHelperTheme {
                val navController = rememberSwipeDismissableNavController()
                val navHostState = rememberSwipeDismissableNavHostState()

                // 初始化"数据共享区域"
                val appState = remember { GlobalAppState() }
                appState.navController = navController
                this@MainActivity.appState = appState

                val startupError by viewModel.startupError.collectAsState()

                CompositionLocalProvider(
                    LocalGlobalAppState provides appState,
                    LocalMainScreenState provides ModuleManager,
                    LocalNavController provides navController,
                ) {
                    AppScaffold {
                        SwipeDismissableNavHost(
                            navController = navController,
                            state = navHostState,
                            startDestination = Destinations.HOME,
                        ) {
                            composable(Destinations.HOME) {
                                MainScreen()
                            }
                            composable(Destinations.SETTINGS) {
                                SettingsPage()
                            }
                            composable(Destinations.FRAMEWORK_INSTALLER) {
                                FrameworkInstallerRoute()
                            }
                            composable(Destinations.WALLET_REDIRECTION) {
                                WalletRedirectionRoute()
                            }
                            composable(Destinations.WALLET_APP_SELECTION) {
                                WalletAppSelectionRoute()
                            }
                            composable(Destinations.WALLET_ACTIVITY_SELECTION_ROUTE) { entry ->
                                WalletActivitySelectionRoute(entry.arguments?.getString("packageName").orEmpty())
                            }
                            composable(Destinations.ABOUT) {
                                AboutPage()
                            }
                        }

                        // 全局错误对话框，覆盖在所有页面之上
                        ErrorDialog(
                            error = startupError,
                            onDismiss = viewModel::dismissStartupError,
                        )
                    }
                }
            }
        }
    }
}
