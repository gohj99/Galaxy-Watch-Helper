package com.gohj99.galaxywatchhelper.ui.main

import android.content.ComponentName
import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextToggleButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private const val KSU = "/data/adb/ksud"
private const val TARGET_SETTING = "gw_wallet_redirect_target"
private const val ENABLED_SETTING = "gw_wallet_hook_enabled"
private const val GWH_PACKAGE = "com.gohj99.galaxywatchhelper"
private const val NEOZYGISK_SHA256 = "93a1425c67bb89f58a0dcb9fc823ec93f6472214f221e667a70b67c6a6e061a1"
private const val VECTOR_SHA256 = "9ee8323575d615f7b3f1076ff60b2a63a49390ef11881b52632311a37f6f79cc"

private data class ModuleStatus(
    val isChecking: Boolean = true,
    val hasRoot: Boolean = false,
    val gwhInstalled: Boolean = false,
    val targetPackage: String = "",
    val enabled: Boolean = true,
    val modules: String = "",
)

private data class ShellResult(val exitCode: Int, val output: String)
private enum class SettingsSection { Menu, Frameworks, SideKey }

@Composable
internal fun MainScreen() {
    val context = LocalContext.current
    val pagerState = rememberPagerState(initialPage = 0) { 2 }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(ModuleStatus()) }
    var targetPackage by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var settingsSection by remember { mutableStateOf(SettingsSection.Menu) }

    suspend fun refreshStatus() {
        val refreshed = withContext(Dispatchers.IO) { readModuleStatus() }
        status = refreshed
        targetPackage = refreshed.targetPackage
    }

    LaunchedEffect(Unit) { refreshStatus() }

    AppScaffold {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            when {
                page == 0 -> HomePage(status = status, onRefresh = { scope.launch { refreshStatus() } })
                settingsSection == SettingsSection.Menu -> SettingsMenuPage(
                    onFrameworks = { settingsSection = SettingsSection.Frameworks },
                    onSideKey = { settingsSection = SettingsSection.SideKey },
                )
                settingsSection == SettingsSection.Frameworks -> InstallerPage(
                    status = status,
                    notice = notice,
                    busy = busy,
                    onBack = { settingsSection = SettingsSection.Menu },
                    onRefresh = { scope.launch { refreshStatus() } },
                    onInstall = {
                        scope.launch {
                            busy = true
                            notice = "Preparing NeoZygisk..."
                            notice = withContext(Dispatchers.IO) {
                                installFrameworkModules(context) { progress -> scope.launch { notice = progress } }
                            }
                            refreshStatus()
                            busy = false
                        }
                    },
                )
                else -> SideKeySettingsPage(
                    status = status,
                    targetPackage = targetPackage,
                    notice = notice,
                    busy = busy,
                    onBack = { settingsSection = SettingsSection.Menu },
                    onTargetChange = { targetPackage = it },
                    onRefresh = { scope.launch { refreshStatus() } },
                    onToggle = { enabled ->
                        scope.launch {
                            busy = true
                            val result = withContext(Dispatchers.IO) {
                                runRoot("settings put global $ENABLED_SETTING ${if (enabled) 1 else 0}")
                            }
                            notice = result.message("Hook ${if (enabled) "enabled" else "disabled"}")
                            refreshStatus()
                            busy = false
                        }
                    },
                    onSaveTarget = {
                        scope.launch {
                            val packageName = targetPackage.trim()
                            if (!PACKAGE_NAME.matches(packageName)) {
                                notice = "Enter a valid package name"
                                return@launch
                            }
                            val component = context.packageManager.getLaunchIntentForPackage(packageName)?.component
                            if (component == null) {
                                notice = "No launcher activity found for $packageName"
                                return@launch
                            }
                            busy = true
                            val result = withContext(Dispatchers.IO) {
                                runRoot("settings put global $TARGET_SETTING ${shellQuote(component.flattenToString())}")
                            }
                            notice = result.message("Target saved: $packageName")
                            refreshStatus()
                            busy = false
                        }
                    },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(bottom = 2.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(
                onClick = {
                    settingsSection = SettingsSection.Menu
                    scope.launch { pagerState.animateScrollToPage(0) }
                },
                modifier = Modifier.padding(horizontal = 3.dp),
            ) { Text("Home") }
            FilledTonalButton(
                onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                modifier = Modifier.padding(horizontal = 3.dp),
            ) { Text("Settings") }
        }
    }
}

@Composable
private fun HomePage(status: ModuleStatus, onRefresh: () -> Unit) {
    val listState = rememberTransformingLazyColumnState()
    val overscrollEffect = rememberOverscrollEffect()
    ScreenScaffold(scrollState = listState, overscrollEffect = overscrollEffect) { padding ->
        TransformingLazyColumn(
            state = listState,
            overscrollEffect = overscrollEffect,
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                ListHeader {
                    Text("Galaxy Watch Helper", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }
            item { StatusLine("Device", Build.MODEL) }
            item { StatusLine("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})") }
            item { StatusLine("Root", when { status.isChecking -> "Checking"; status.hasRoot -> "Available"; else -> "Unavailable" }) }
            item { StatusLine("GWH app", when { status.isChecking -> "Checking"; status.gwhInstalled -> "Installed"; else -> "Not installed" }) }
            item { StatusLine("NeoZygisk / Vector", moduleSummary(status.modules)) }
            item { StatusLine("Wallet target", status.targetPackage.ifBlank { "Not set" }) }
            if (!status.isChecking && !status.hasRoot) item { StatusLine("Status", "Root access is required for module setup") }
            item { FilledTonalButton(onClick = onRefresh) { Text("Refresh") } }
            item { Spacer(Modifier.height(42.dp)) }
        }
    }
}

@Composable
private fun SettingsMenuPage(onFrameworks: () -> Unit, onSideKey: () -> Unit) {
    val listState = rememberTransformingLazyColumnState()
    val overscrollEffect = rememberOverscrollEffect()
    ScreenScaffold(scrollState = listState, overscrollEffect = overscrollEffect) { padding ->
        TransformingLazyColumn(
            state = listState,
            overscrollEffect = overscrollEffect,
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                ListHeader {
                    Text("Settings", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }
            item { FilledTonalButton(onClick = onFrameworks, modifier = Modifier.fillMaxWidth()) { Text("Install NeoZygisk → Vector") } }
            item { FilledTonalButton(onClick = onSideKey, modifier = Modifier.fillMaxWidth()) { Text("Wallet redirection") } }
            item { Spacer(Modifier.height(42.dp)) }
        }
    }
}

@Composable
private fun InstallerPage(
    status: ModuleStatus,
    notice: String,
    busy: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onInstall: () -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    val overscrollEffect = rememberOverscrollEffect()
    ScreenScaffold(scrollState = listState, overscrollEffect = overscrollEffect) { padding ->
        TransformingLazyColumn(
            state = listState,
            overscrollEffect = overscrollEffect,
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item { ListHeader { Text("Framework installer", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) } }
            item { StatusLine("Installed modules", moduleSummary(status.modules)) }
            item { FilledTonalButton(onClick = onInstall, enabled = status.hasRoot && !busy) { Text("Install NeoZygisk → Vector") } }
            if (notice.isNotBlank()) item { StatusLine("Status", notice) }
            item { FilledTonalButton(onClick = onRefresh, enabled = !busy) { Text("Refresh") } }
            item { FilledTonalButton(onClick = onBack, enabled = !busy) { Text("Back") } }
            item { Spacer(Modifier.height(42.dp)) }
        }
    }
}

@Composable
private fun SideKeySettingsPage(
    status: ModuleStatus,
    targetPackage: String,
    notice: String,
    busy: Boolean,
    onBack: () -> Unit,
    onTargetChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onSaveTarget: () -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    val overscrollEffect = rememberOverscrollEffect()
    ScreenScaffold(scrollState = listState, overscrollEffect = overscrollEffect) { padding ->
        TransformingLazyColumn(
            state = listState,
            overscrollEffect = overscrollEffect,
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item { ListHeader { Text("Wallet redirection", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) } }
            item { StatusLine("Vector scope", "Enable GalaxyWatchHelper for Android System, then reboot") }
            item {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Redirect enabled", style = MaterialTheme.typography.bodyMedium)
                    TextToggleButton(checked = status.enabled, onCheckedChange = onToggle, enabled = status.hasRoot && !busy) {
                        Text(if (status.enabled) "On" else "Off")
                    }
                }
            }
            item { StatusLine("Target package", status.targetPackage.ifBlank { "Not set" }) }
            item {
                BasicTextField(
                    value = targetPackage,
                    onValueChange = onTargetChange,
                    singleLine = true,
                    enabled = !busy,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                    decorationBox = { inner ->
                        Box(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 12.dp, vertical = 10.dp)) {
                            if (targetPackage.isEmpty()) Text("com.example.app", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            inner()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }
            item { FilledTonalButton(onClick = onSaveTarget, enabled = status.hasRoot && !busy) { Text("Save target package") } }
            if (notice.isNotBlank()) item { StatusLine("Status", notice) }
            item { FilledTonalButton(onClick = onRefresh, enabled = !busy) { Text("Refresh") } }
            item { FilledTonalButton(onClick = onBack, enabled = !busy) { Text("Back") } }
            item { Spacer(Modifier.height(42.dp)) }
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

private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+$")

private fun readModuleStatus(): ModuleStatus {
    val root = runRoot("id -u")
    val hasRoot = root.exitCode == 0 && root.output.trim() == "0"
    if (!hasRoot) return ModuleStatus(isChecking = false)

    val installed = runRoot("pm path $GWH_PACKAGE")
    val target = runRoot("settings get global $TARGET_SETTING").output.trim().takeUnless { it == "null" }.orEmpty()
    val enabled = runRoot("settings get global $ENABLED_SETTING").output.trim() != "0"
    val modules = runRoot("$KSU module list").output.trim()
    return ModuleStatus(
        isChecking = false,
        hasRoot = true,
        gwhInstalled = installed.exitCode == 0,
        targetPackage = ComponentName.unflattenFromString(target)?.packageName.orEmpty(),
        enabled = enabled,
        modules = modules,
    )
}

private fun moduleSummary(modules: String): String {
    if (modules.isBlank()) return "Unavailable"
    val found = listOf("zygisksu", "zygisk_vector").filter { modules.contains(it, ignoreCase = true) }
    return if (found.isEmpty()) "No matching modules" else found.joinToString(" · ")
}

private fun installFrameworkModules(context: Context, onProgress: (String) -> Unit): String {
    val modules = listOf(
        FrameworkAsset("NeoZygisk", "NeoZygisk-v2.4-289-release.zip", NEOZYGISK_SHA256),
        FrameworkAsset("Vector", "Vector-v2.2-3080-Release.zip", VECTOR_SHA256),
    )
    for (module in modules) {
        val path = "/data/local/tmp/${module.assetName.lowercase()}"
        try {
            onProgress("Preparing ${module.label}...")
            if (!writeAssetAsRoot(context, module.assetName, path, module.sha256)) return "${module.label} ZIP verification or staging failed"
            onProgress("Installing ${module.label} with ksud...")
            val install = runRoot("$KSU module install ${shellQuote(path)}")
            if (install.exitCode != 0) return "${module.label} install failed: ${install.output.ifBlank { "exit ${install.exitCode}" }}"
            if (module.label == "Vector") {
                onProgress("Installing Vector manager...")
                val manager = runRoot("unzip -p ${shellQuote(path)} manager.apk > /data/local/tmp/vector-manager.apk && test -s /data/local/tmp/vector-manager.apk && pm install -r /data/local/tmp/vector-manager.apk")
                if (manager.exitCode != 0) return "Vector module installed; manager APK failed: ${manager.output.ifBlank { "exit ${manager.exitCode}" }}"
            }
        } catch (error: Exception) {
            return "${module.label} failed: ${error.message ?: error.javaClass.simpleName}"
        } finally {
            runRoot("rm -f ${shellQuote(path)}")
            if (module.label == "Vector") runRoot("rm -f /data/local/tmp/vector-manager.apk")
        }
    }
    return "NeoZygisk and Vector installed. Reboot to activate them."
}

private data class FrameworkAsset(val label: String, val assetName: String, val sha256: String)

private fun writeAssetAsRoot(context: Context, asset: String, destination: String, expectedSha256: String? = null): Boolean {
    val process = ProcessBuilder("su", "-c", "cat > ${shellQuote(destination)}").redirectErrorStream(true).start()
    val output = StringBuilder()
    val reader = Thread { process.inputStream.bufferedReader().use { output.append(it.readText()) } }.apply { isDaemon = true; start() }
    val checksum = MessageDigest.getInstance("SHA-256")
    context.assets.open(asset).use { input ->
        process.outputStream.use { outputStream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                checksum.update(buffer, 0, count)
                outputStream.write(buffer, 0, count)
            }
        }
    }
    if (!process.waitFor(30, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return false
    }
    reader.join(1_000)
    val actualSha256 = checksum.digest().joinToString("") { "%02x".format(it) }
    return process.exitValue() == 0 && (expectedSha256 == null || actualSha256 == expectedSha256)
}

private fun runRoot(command: String): ShellResult = try {
    val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
    val output = StringBuilder()
    val reader = Thread { process.inputStream.bufferedReader().use { output.append(it.readText()) } }.apply { isDaemon = true; start() }
    if (!process.waitFor(90, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        ShellResult(-1, "Command timed out")
    } else {
        reader.join(1_000)
        ShellResult(process.exitValue(), output.toString().trim())
    }
} catch (error: Exception) {
    ShellResult(-1, error.message ?: error.javaClass.simpleName)
}

private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

private fun ShellResult.message(success: String): String = if (exitCode == 0) success else output.ifBlank { "Command failed (exit $exitCode)" }
