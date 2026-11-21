package com.gohj99.galaxywatchhelper.ui.main.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.gohj99.galaxywatchhelper.R
import com.gohj99.galaxywatchhelper.utils.LocalMainScreenState
import kotlinx.coroutines.launch

@Composable
internal fun WalletRedirectionPage(onBack: () -> Unit, onChooseApplication: () -> Unit) {
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
                        stringResource(R.string.wallet_title),
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item { StatusLine(stringResource(R.string.wallet_vector_scope), stringResource(R.string.wallet_scope_instructions)) }
            item {
                SwitchButton(
                    checked = state.status.enabled,
                    onCheckedChange = { enabled -> scope.launch { state.setWalletRedirect(context, enabled) } },
                    enabled = state.status.hasRoot && !state.busy,
                    label = { Text(stringResource(R.string.wallet_redirect_enabled)) },
                    secondaryLabel = {
                        Text(stringResource(if (state.status.enabled) R.string.message_module_enabled else R.string.message_module_disabled))
                    },
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                )
            }
            item {
                StatusLine(
                    stringResource(R.string.status_target_package),
                    state.status.targetPackage.ifBlank { stringResource(R.string.status_not_set) },
                )
            }
            item {
                BasicTextField(
                    value = state.targetPackage,
                    onValueChange = { state.targetPackage = it },
                    singleLine = true,
                    enabled = !state.busy,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                    decorationBox = { inner ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.large)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            if (state.targetPackage.isEmpty()) {
                                Text(stringResource(R.string.wallet_package_placeholder), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            inner()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }
            item {
                FilledTonalButton(
                    onClick = onChooseApplication,
                    enabled = !state.busy,
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                ) { Text(stringResource(R.string.action_choose_application)) }
            }
            item {
                FilledTonalButton(
                    onClick = { scope.launch { state.saveWalletTarget(context) } },
                    enabled = state.status.hasRoot && !state.busy,
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                ) { Text(stringResource(R.string.action_save_target)) }
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
            item {
                FilledTonalButton(
                    onClick = onBack,
                    enabled = !state.busy,
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                ) { Text(stringResource(R.string.action_back)) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
    }
}
