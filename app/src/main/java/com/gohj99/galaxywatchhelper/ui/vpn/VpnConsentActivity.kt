package com.gohj99.galaxywatchhelper.ui.vpn

import android.app.Activity
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
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
import com.gohj99.galaxywatchhelper.ui.theme.GalaxyWatchHelperTheme
import com.gohj99.galaxywatchhelper.utils.WearVpnAuthorizationFlow
import com.gohj99.galaxywatchhelper.utils.WearVpnConsentContract
import com.gohj99.galaxywatchhelper.utils.WearVpnConfirmationRedirect
import com.gohj99.galaxywatchhelper.utils.WearVpnRestore
import com.gohj99.galaxywatchhelper.utils.isVpnApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ConsentAllowBlue = Color(0xFFA8C7FA)
private val ConsentAllowOnBlue = Color(0xFF0A305F)
private val ConsentDenyGray = Color(0xFF45464F)
private val ConsentDenyOnGray = Color(0xFFE2E2E9)

/** User-facing confirmation page launched by the system-server VPN redirect. */
class VpnConsentActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val packageName = intent.getStringExtra(WearVpnConsentContract.EXTRA_VPN_PACKAGE)
        val caller = callingPackage
        val promptEnabled = try {
            Settings.Global.getInt(contentResolver, WearVpnRestore.CONSENT_ENABLED_SETTING, 1) == 1
        } catch (_: RuntimeException) {
            false
        }
        val providerEnabled = try {
            Settings.Global.getInt(contentResolver, WearVpnRestore.ENABLED_SETTING, 0) == 1
        } catch (_: RuntimeException) {
            false
        }
        if (!WearVpnConfirmationRedirect.isApiSupported() || !promptEnabled || !providerEnabled ||
            packageName.isNullOrBlank() || caller != packageName || !isVpnApplication(this, packageName)
        ) {
            Log.w("GWSystemServerHook", "[WearVpnConsent] consent Activity rejected an unsupported or invalid launch")
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        val label = try {
            packageManager.getApplicationInfo(packageName, 0).loadLabel(packageManager).toString()
        } catch (_: Exception) {
            packageName
        }
        setContent {
            GalaxyWatchHelperTheme {
                ConsentPage(
                    appLabel = label.ifBlank { packageName },
                    packageName = packageName,
                    onGrant = {
                        val result = withContext(Dispatchers.IO) {
                            WearVpnAuthorizationFlow.grant(this@VpnConsentActivity, packageName)
                        }
                        if (result.success) {
                            complete(Activity.RESULT_OK)
                            null
                        } else {
                            result.output
                        }
                    },
                    onDeny = { complete(Activity.RESULT_CANCELED) },
                )
            }
        }
    }

    private fun complete(resultCode: Int) {
        setResult(resultCode)
        finish()
    }
}

@Composable
private fun ConsentPage(
    appLabel: String,
    packageName: String,
    onGrant: suspend () -> String?,
    onDeny: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
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
                        stringResource(R.string.wear_vpn_consent_title),
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item {
                TitleCard(
                    title = { Text(appLabel) },
                    subtitle = { Text(stringResource(R.string.wear_vpn_consent_description)) },
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                ) {
                    Text(
                        packageName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val errorMessage = error
            if (errorMessage != null) {
                item {
                    Text(
                        stringResource(R.string.wear_vpn_consent_error, errorMessage),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    )
                }
            }
            item {
                Button(
                    onClick = {
                        if (!working) {
                            working = true
                            scope.launch {
                                error = try {
                                    onGrant()
                                } catch (failure: Exception) {
                                    failure.message ?: failure.javaClass.simpleName
                                } finally {
                                    working = false
                                }
                            }
                        }
                    },
                    enabled = !working,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ConsentAllowBlue,
                        contentColor = ConsentAllowOnBlue,
                    ),
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                ) {
                    Text(stringResource(if (working) R.string.wear_vpn_consent_working else R.string.wear_vpn_consent_allow))
                }
            }
            item {
                FilledTonalButton(
                    onClick = onDeny,
                    enabled = !working,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = ConsentDenyGray,
                        contentColor = ConsentDenyOnGray,
                    ),
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                ) { Text(stringResource(R.string.wear_vpn_consent_deny)) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
