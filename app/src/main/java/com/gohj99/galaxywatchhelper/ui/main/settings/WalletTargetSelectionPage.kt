package com.gohj99.galaxywatchhelper.ui.main.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
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
import com.gohj99.galaxywatchhelper.ui.Destinations
import com.gohj99.galaxywatchhelper.ui.LocalNavController
import com.gohj99.galaxywatchhelper.utils.LocalMainScreenState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun WalletAppSelectionRoute() {
    val navController = LocalNavController.current
    InstalledAppSelectionPage(
        onApplicationSelected = { packageName ->
            navController?.navigate(Destinations.walletActivitySelection(packageName))
        },
    )
}

@Composable
internal fun WalletActivitySelectionRoute(packageName: String) {
    val navController = LocalNavController.current
    val state = LocalMainScreenState.current
    WalletActivitySelectionPage(
        packageName = packageName,
        onActivitySelected = { component ->
            state.targetPackage = component.flattenToString()
            navController?.popBackStack(Destinations.WALLET_REDIRECTION, false)
        },
    )
}

private data class InstalledApp(
    val packageName: String,
    val label: String,
    val isSystemApp: Boolean,
)

private data class TargetActivity(
    val component: ComponentName,
    val label: String,
)

@Composable
internal fun InstalledAppSelectionPage(
    onApplicationSelected: (String) -> Unit,
    title: String? = null,
    allowedPackages: Set<String>? = null,
    loadingCandidates: Boolean = false,
    statusMessage: String? = null,
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var appQuery by rememberSaveable { mutableStateOf("") }
    var showSystemApps by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadInstalledApps(context) }
    }
    val query = appQuery.trim()
    val visibleApps = apps
        ?.asSequence()
        ?.filter { allowedPackages == null || it.packageName in allowedPackages }
        ?.filter { showSystemApps || !it.isSystemApp }
        ?.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }
        ?.toList()

    SelectionPageScaffold(
        title = title ?: stringResource(R.string.wallet_apps_title),
    ) { transformationSpec ->
        item {
            BasicTextField(
                value = appQuery,
                onValueChange = { appQuery = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search),
                decorationBox = { inner ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.large)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        if (appQuery.isEmpty()) {
                            Text(
                                stringResource(R.string.wallet_app_search_placeholder),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
        item {
            SwitchButton(
                checked = showSystemApps,
                onCheckedChange = { showSystemApps = it },
                label = { Text(stringResource(R.string.wallet_show_system_apps)) },
                secondaryLabel = { Text(stringResource(R.string.wallet_show_system_apps_summary)) },
                transformation = SurfaceTransformation(transformationSpec),
                modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
            )
        }
        if (statusMessage != null) item { SelectionMessage(statusMessage) }
        when {
            apps == null || loadingCandidates -> item { SelectionMessage(stringResource(R.string.wallet_loading_apps)) }
            apps!!.isEmpty() -> item { SelectionMessage(stringResource(R.string.wallet_no_apps)) }
            visibleApps.isNullOrEmpty() -> item { SelectionMessage(stringResource(R.string.wallet_no_matching_apps)) }
            else -> visibleApps.forEach { app ->
                item(key = app.packageName) {
                    TitleCard(
                        onClick = { onApplicationSelected(app.packageName) },
                        title = { Text(app.label) },
                        subtitle = { Text(app.packageName) },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                    )
                }
            }
        }
    }
}

@Composable
private fun WalletActivitySelectionPage(
    packageName: String,
    onActivitySelected: (ComponentName) -> Unit,
) {
    val context = LocalContext.current
    var appLabel by remember(packageName) { mutableStateOf(packageName) }
    var activities by remember(packageName) { mutableStateOf<List<TargetActivity>?>(null) }
    LaunchedEffect(packageName) {
        val result = withContext(Dispatchers.IO) { loadTargetActivities(context, packageName) }
        appLabel = result.first
        activities = result.second
    }

    SelectionPageScaffold(
        title = stringResource(R.string.wallet_activities_title),
    ) { transformationSpec ->
        item {
            Text(
                text = appLabel,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
            Text(
                text = packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
        when {
            activities == null -> item { SelectionMessage(stringResource(R.string.wallet_loading_activities)) }
            activities!!.isEmpty() -> item { SelectionMessage(stringResource(R.string.wallet_no_activities)) }
            else -> activities!!.forEach { activity ->
                item(key = activity.component.flattenToString()) {
                    TitleCard(
                        onClick = { onActivitySelected(activity.component) },
                        title = { Text(activity.label) },
                        subtitle = { Text(activity.component.flattenToShortString()) },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectionPageScaffold(
    title: String,
    content: TransformingLazyColumnScope.(androidx.wear.compose.material3.lazy.TransformationSpec) -> Unit,
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
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            content(transformationSpec)
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SelectionMessage(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    )
}

private fun loadInstalledApps(context: Context): List<InstalledApp> =
    context.packageManager.getInstalledApplications(PackageManager.MATCH_ALL)
        .asSequence()
        .filter { it.enabled }
        .map { info ->
            InstalledApp(
                packageName = info.packageName,
                label = info.loadLabel(context.packageManager).toString().ifBlank { info.packageName },
                isSystemApp = info.flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or
                    android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
            )
        }
        .sortedWith(compareBy({ it.label.lowercase() }, { it.packageName }))
        .toList()

private fun loadTargetActivities(context: Context, packageName: String): Pair<String, List<TargetActivity>> {
    val packageManager = context.packageManager
    return try {
        val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
        val label = applicationInfo.loadLabel(packageManager).toString().ifBlank { packageName }
        val activities = packageManager.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES).activities.orEmpty()
            .asSequence()
            .filter { it.enabled && it.exported && it.permission == null }
            .map { info: ActivityInfo ->
                TargetActivity(
                    component = ComponentName(packageName, info.name),
                    label = info.loadLabel(packageManager).toString().ifBlank { info.name.substringAfterLast('.') },
                )
            }
            .sortedWith(compareBy({ it.label.lowercase() }, { it.component.className }))
            .toList()
        label to activities
    } catch (_: PackageManager.NameNotFoundException) {
        packageName to emptyList()
    }
}
