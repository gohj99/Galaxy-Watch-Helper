package com.gohj99.galaxywatchhelper.ui.main.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
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
import com.gohj99.galaxywatchhelper.utils.WearVpnConfirmationRedirect
import com.gohj99.galaxywatchhelper.utils.WearVpnRestore
import kotlinx.coroutines.launch

@Composable
internal fun WearVpnSettingsPage() {
    val state = LocalMainScreenState.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var appsLoading by remember { mutableStateOf(true) }
    val listState = rememberTransformingLazyColumnState()
    val overscrollEffect = rememberOverscrollEffect()
    val transformationSpec = rememberTransformationSpec()
    LaunchedEffect(Unit) {
        try {
            state.refreshStatus()
            state.loadWearVpnApplications(context)
        } finally {
            appsLoading = false
        }
    }

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
                        stringResource(R.string.wear_vpn_title),
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item {
                SwitchButton(
                    checked = state.status.wearVpnRequested,
                    onCheckedChange = { enabled -> scope.launch { state.setWearVpnRestore(context, enabled) } },
                    enabled = state.status.hasRoot && !state.busy &&
                        (WearVpnRestore.firmwareStatus() == WearVpnRestore.FirmwareStatus.AUDITED_CODE_REMOVED ||
                            state.status.wearVpnRequested),
                    label = { Text(stringResource(R.string.wear_vpn_switch_label)) },
                    secondaryLabel = {
                        Text(stringResource(
                            when {
                                !state.status.wearVpnConfigReadable -> R.string.wear_vpn_unknown
                                state.status.wearVpnRequested -> R.string.wear_vpn_requested
                                else -> R.string.wear_vpn_off
                            },
                        ))
                    },
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                )
            }
            item {
                SwitchButton(
                    checked = state.status.wearVpnConsentRequested,
                    onCheckedChange = { enabled -> scope.launch { state.setWearVpnConsent(context, enabled) } },
                    enabled = state.status.hasRoot && !state.busy && WearVpnConfirmationRedirect.isApiSupported(),
                    label = { Text(stringResource(R.string.wear_vpn_consent_switch_label)) },
                    secondaryLabel = {
                        Text(stringResource(
                            when {
                                !WearVpnConfirmationRedirect.isApiSupported() -> R.string.wear_vpn_consent_api_blocked
                                !state.status.wearVpnConsentConfigReadable -> R.string.wear_vpn_unknown
                                state.status.wearVpnConsentRequested && !state.status.wearVpnRequested -> R.string.wear_vpn_consent_requires_provider
                                state.status.wearVpnConsentRequested -> R.string.wear_vpn_consent_requested
                                else -> R.string.wear_vpn_consent_off
                            },
                        ))
                    },
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                )
            }
            if (appsLoading || state.wearVpnApplications.isEmpty()) {
                item {
                    Text(
                        stringResource(if (appsLoading) R.string.wear_vpn_loading_apps else R.string.wear_vpn_no_apps),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                state.wearVpnApplications.forEach { application ->
                    item(key = application.packageName) {
                        SwitchButton(
                            checked = application.authorized == true,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    state.setWearVpnAuthorization(context, application.packageName, enabled)
                                }
                            },
                            enabled = state.status.hasRoot && !state.busy,
                            label = { Text(application.label) },
                            secondaryLabel = {
                                Column {
                                    Text(application.packageName)
                                    if (application.authorized == null) {
                                        Text(
                                            stringResource(R.string.wear_vpn_permission_unknown),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            },
                            transformation = SurfaceTransformation(transformationSpec),
                            modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                        )
                    }
                }
            }
            if (state.notice.isNotBlank()) {
                item { Text(state.notice, style = MaterialTheme.typography.bodySmall) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
