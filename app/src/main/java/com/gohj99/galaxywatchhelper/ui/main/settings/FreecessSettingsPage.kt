package com.gohj99.galaxywatchhelper.ui.main.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.gohj99.galaxywatchhelper.R
import com.gohj99.galaxywatchhelper.utils.LocalMainScreenState
import kotlinx.coroutines.launch

@Composable
internal fun FreecessSettingsPage(onLogs: () -> Unit) {
    val state = LocalMainScreenState.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberTransformingLazyColumnState()
    val overscrollEffect = rememberOverscrollEffect()
    val transformationSpec = rememberTransformationSpec()
    LaunchedEffect(Unit) { state.refreshStatus() }

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
                        stringResource(R.string.freecess_settings_title),
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item {
                SwitchButton(
                    checked = state.status.freecessEnabled,
                    onCheckedChange = { enabled -> scope.launch { state.setFreecessHook(context, enabled) } },
                    enabled = state.status.hasRoot && !state.busy,
                    label = { Text(stringResource(R.string.freecess_enabled_label)) },
                    secondaryLabel = {
                        Text(
                            stringResource(
                                if (state.status.freecessEnabled) R.string.message_freecess_enabled
                                else R.string.message_freecess_disabled,
                            ),
                        )
                    },
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                )
            }
            item {
                SwitchButton(
                    checked = state.status.freecessLoggingEnabled,
                    onCheckedChange = { enabled -> scope.launch { state.setFreecessLogging(context, enabled) } },
                    enabled = state.status.hasRoot && !state.busy,
                    label = { Text(stringResource(R.string.freecess_logging_label)) },
                    secondaryLabel = {
                        Text(
                            stringResource(
                                if (state.status.freecessLoggingEnabled) R.string.message_freecess_logging_enabled
                                else R.string.message_freecess_logging_disabled,
                            ),
                        )
                    },
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                )
            }
            item {
                FilledTonalButton(
                    onClick = onLogs,
                    enabled = state.status.hasRoot && !state.busy,
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                ) { Text(stringResource(R.string.action_view_freecess_logs)) }
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
            item {
                FilledTonalButton(
                    onClick = { scope.launch { state.refreshStatus() } },
                    enabled = !state.busy,
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                ) { Text(stringResource(R.string.action_refresh)) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
internal fun FreecessLogsPage(onOpenLog: (String) -> Unit) {
    val state = LocalMainScreenState.current
    val scope = rememberCoroutineScope()
    val listState = rememberTransformingLazyColumnState()
    val overscrollEffect = rememberOverscrollEffect()
    val transformationSpec = rememberTransformationSpec()
    LaunchedEffect(Unit) { state.refreshFreecessLogs() }

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
                        stringResource(R.string.freecess_logs_title),
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (state.freecessLogs.isEmpty()) item {
                Text(
                    stringResource(R.string.freecess_logs_empty),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            state.freecessLogs.forEach { fileName ->
                item(key = fileName) {
                    TitleCard(
                        onClick = { onOpenLog(fileName) },
                        title = { Text(fileName.removeSuffix(".log")) },
                        subtitle = { Text(stringResource(R.string.freecess_log_entry)) },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                    )
                }
            }
            item {
                FilledTonalButton(
                    onClick = { scope.launch { state.refreshFreecessLogs() } },
                    enabled = !state.busy,
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                ) { Text(stringResource(R.string.action_refresh)) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
internal fun FreecessLogDetailPage(fileName: String) {
    val state = LocalMainScreenState.current
    val context = LocalContext.current
    val listState = rememberTransformingLazyColumnState()
    val overscrollEffect = rememberOverscrollEffect()
    val transformationSpec = rememberTransformationSpec()
    LaunchedEffect(fileName) { state.readFreecessLog(context, fileName) }

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
                        fileName.removeSuffix(".log"),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item {
                Text(
                    state.freecessLogContent.ifBlank { stringResource(R.string.freecess_log_loading) },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
