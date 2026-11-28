package com.gohj99.galaxywatchhelper.ui.main.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.gohj99.galaxywatchhelper.R
import com.gohj99.galaxywatchhelper.ui.Destinations
import com.gohj99.galaxywatchhelper.ui.LocalNavController
import com.gohj99.galaxywatchhelper.utils.LocalMainScreenState

@Composable
internal fun SettingsPage() {
    val state = LocalMainScreenState.current
    val navController = LocalNavController.current
    LaunchedEffect(state) { state.refreshStatus() }

    SettingsMenuPage(
        state = state,
        onFrameworks = { navController?.navigate(Destinations.FRAMEWORK_INSTALLER) },
        onSideKey = { navController?.navigate(Destinations.WALLET_REDIRECTION) },
        onFreecess = { navController?.navigate(Destinations.FREECESS_SETTINGS) },
        onWearVpn = { navController?.navigate(Destinations.WEAR_VPN_SETTINGS) },
        onAbout = { navController?.navigate(Destinations.ABOUT) },
    )
}

@Composable
internal fun FrameworkInstallerRoute() {
    InstallerPage()
}

@Composable
internal fun WalletRedirectionRoute() {
    val navController = LocalNavController.current
    WalletRedirectionPage(
        onChooseApplication = { navController?.navigate(Destinations.WALLET_APP_SELECTION) },
    )
}

@Composable
internal fun FreecessSettingsRoute() {
    val navController = LocalNavController.current
    FreecessSettingsPage(
        onLogs = { navController?.navigate(Destinations.FREECESS_LOGS) },
    )
}

@Composable
internal fun WearVpnSettingsRoute() {
    WearVpnSettingsPage()
}

@Composable
internal fun FreecessLogsRoute() {
    val navController = LocalNavController.current
    FreecessLogsPage(
        onOpenLog = { fileName -> navController?.navigate(Destinations.freecessLogDetail(fileName)) },
    )
}

@Composable
internal fun FreecessLogDetailRoute(fileName: String) {
    FreecessLogDetailPage(fileName)
}

@Composable
private fun SettingsMenuPage(
    state: com.gohj99.galaxywatchhelper.utils.ModuleManager,
    onFrameworks: () -> Unit,
    onSideKey: () -> Unit,
    onFreecess: () -> Unit,
    onWearVpn: () -> Unit,
    onAbout: () -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    val overscrollEffect = rememberOverscrollEffect()
    val transformationSpec = rememberTransformationSpec()
    ScreenScaffold(scrollState = listState, overscrollEffect = overscrollEffect) { padding ->
        TransformingLazyColumn(
            state = listState,
            rotaryScrollableBehavior = RotaryScrollableDefaults.behavior(listState),
            overscrollEffect = overscrollEffect,
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                ListHeader {
                    Text(
                        stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item {
                FilledTonalButton(
                    onClick = onFrameworks,
                    enabled = !state.busy,
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                ) { Text(stringResource(R.string.action_install_frameworks)) }
            }
            item {
                TitleCard(
                    onClick = onSideKey,
                    enabled = !state.busy,
                    title = { Text(stringResource(R.string.settings_wallet_redirection)) },
                    subtitle = { Text(stringResource(R.string.wallet_settings_summary)) },
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                ) {
                    Text(
                        state.status.targetPackage.ifBlank { stringResource(R.string.status_not_set) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                TitleCard(
                    onClick = onFreecess,
                    enabled = !state.busy,
                    title = { Text(stringResource(R.string.freecess_settings_title)) },
                    subtitle = { Text(stringResource(R.string.freecess_settings_summary)) },
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                )
            }
            item {
                TitleCard(
                    onClick = onWearVpn,
                    enabled = !state.busy,
                    title = { Text(stringResource(R.string.wear_vpn_title)) },
                    subtitle = { Text(stringResource(R.string.wear_vpn_menu_summary)) },
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                )
            }
            item {
                FilledTonalButton(
                    onClick = onAbout,
                    enabled = !state.busy,
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                ) { Text(stringResource(R.string.settings_about)) }
            }
            if (state.notice.isNotBlank()) {
                item {
                    Text(
                        state.notice,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
