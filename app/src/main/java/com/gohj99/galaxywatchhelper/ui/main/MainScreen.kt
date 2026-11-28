package com.gohj99.galaxywatchhelper.ui.main

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.EdgeButton
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
import com.gohj99.galaxywatchhelper.utils.moduleSummary

@Composable
internal fun MainScreen() {
    val state = LocalMainScreenState.current
    val navController = LocalNavController.current
    val context = LocalContext.current
    LaunchedEffect(Unit) { state.refreshStatus(context) }

    HomePage(
        status = state.status,
        onSettings = { navController?.navigate(Destinations.SETTINGS) },
        onInstaller = { navController?.navigate(Destinations.FRAMEWORK_INSTALLER) },
        onWallet = { navController?.navigate(Destinations.WALLET_REDIRECTION) },
    )
}

@Composable
private fun HomePage(
    status: com.gohj99.galaxywatchhelper.utils.ModuleStatus,
    onSettings: () -> Unit,
    onInstaller: () -> Unit,
    onWallet: () -> Unit,
) {
    val context = LocalContext.current
    val listState = rememberTransformingLazyColumnState()
    val overscrollEffect = rememberOverscrollEffect()
    val transformationSpec = rememberTransformationSpec()
    val checking = stringResource(R.string.status_checking)
    val available = stringResource(R.string.status_available)
    val unavailable = stringResource(R.string.status_unavailable)
    val installed = stringResource(R.string.status_installed)
    val notInstalled = stringResource(R.string.status_not_installed)
    val hookChecking = stringResource(R.string.hook_status_checking)
    val hookReady = stringResource(R.string.hook_status_ready)
    val hookFrameworkMissing = stringResource(R.string.hook_status_framework_missing)
    val hookMissingSelf = stringResource(R.string.hook_status_missing_self)
    val hookMissingSystem = stringResource(R.string.hook_status_missing_system)
    val hookStatusText = when {
        status.isChecking -> hookChecking
        status.selfHooked && status.systemHooked -> hookReady
        !status.selfHooked && !status.systemHooked -> hookFrameworkMissing
        !status.selfHooked -> hookMissingSelf
        else -> hookMissingSystem
    }
    val hookHealthy = !status.isChecking && status.selfHooked && status.systemHooked
    AppScaffold {
        ScreenScaffold(
            scrollState = listState,
            overscrollEffect = overscrollEffect,
            edgeButtonSpacing = 15.dp,
            edgeButton = {
                EdgeButton(onClick = onSettings) { Text(stringResource(R.string.settings_title)) }
            },
        ) { padding ->
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
                            stringResource(R.string.home_title),
                            style = MaterialTheme.typography.titleLarge,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                item {
                    TitleCard(
                        onClick = onInstaller,
                        title = { Text(stringResource(R.string.hook_status_title)) },
                        subtitle = { Text(hookStatusText) },
                        colors = androidx.wear.compose.material3.CardDefaults.cardColors(
                            containerColor = if (hookHealthy) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.errorContainer
                            },
                            contentColor = if (hookHealthy) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onErrorContainer
                            },
                        ),
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                    )
                }
                item {
                    TitleCard(
                        onClick = onSettings,
                        title = { Text(stringResource(R.string.home_device_card_title)) },
                        subtitle = {
                            Text(Build.MODEL, style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.status_android_version, Build.VERSION.RELEASE, Build.VERSION.SDK_INT),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                    )
                }
                item {
                    TitleCard(
                        onClick = onSettings,
                        title = { Text(stringResource(R.string.home_modules_card_title)) },
                        subtitle = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                StatusValue(stringResource(R.string.status_root), when { status.isChecking -> checking; status.hasRoot -> available; else -> unavailable })
                                StatusValue(stringResource(R.string.status_modules), moduleSummary(status.modules, context))
                            }
                        },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                    )
                }
                item {
                    TitleCard(
                        onClick = onWallet,
                        title = { Text(stringResource(R.string.home_wallet_card_title)) },
                        subtitle = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                StatusValue(stringResource(R.string.status_wallet_target), status.targetPackage.ifBlank { stringResource(R.string.status_not_set) })
                                if (!status.isChecking && !status.hasRoot) {
                                    Text(
                                        stringResource(R.string.status_root_required),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }

}

@Composable
private fun StatusValue(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.2f),
        )
    }
}
