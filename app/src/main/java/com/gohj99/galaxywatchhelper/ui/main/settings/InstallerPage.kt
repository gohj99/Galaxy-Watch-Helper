package com.gohj99.galaxywatchhelper.ui.main.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.runtime.Composable
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
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.gohj99.galaxywatchhelper.R
import com.gohj99.galaxywatchhelper.utils.LocalMainScreenState
import com.gohj99.galaxywatchhelper.utils.moduleSummary
import kotlinx.coroutines.launch

@Composable
internal fun InstallerPage() {
    val state = LocalMainScreenState.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
                        stringResource(R.string.installer_title),
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item { StatusLine(stringResource(R.string.status_installed_modules), moduleSummary(state.status.modules, context)) }
            item {
                StatusLine(
                    stringResource(R.string.installer_scope_label),
                    stringResource(R.string.installer_scope_summary),
                )
            }
            item {
                FilledTonalButton(
                    onClick = {
                        scope.launch {
                            state.installModules(context) { progress ->
                                scope.launch { state.notice = progress }
                            }
                        }
                    },
                    enabled = state.status.hasRoot && !state.busy,
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                ) { Text(stringResource(R.string.action_install_frameworks)) }
            }
            item {
                FilledTonalButton(
                    onClick = { scope.launch { state.enableModule(context) } },
                    enabled = !state.busy,
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                ) { Text(stringResource(R.string.action_enable_module)) }
            }
            if (state.notice.isNotBlank()) item { StatusLine(stringResource(R.string.status_label), state.notice) }
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
private fun StatusLine(label: String, value: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
    }
}
