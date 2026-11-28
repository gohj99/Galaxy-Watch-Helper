package com.gohj99.galaxywatchhelper.utils

import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gohj99.galaxywatchhelper.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.Arrays
import java.util.concurrent.TimeUnit

private const val KSU = "/data/adb/ksud"
private const val VECTOR_CLI = "/data/adb/modules/zygisk_vector/cli"
private const val TARGET_SETTING = "gw_wallet_redirect_target"
private const val ENABLED_SETTING = "gw_wallet_hook_enabled"
private const val FREECESS_ENABLED_SETTING = "gw_freecess_hook_enabled"
private const val FREECESS_LOGGING_SETTING = "gw_freecess_logging_enabled"
private const val FREECESS_LOG_DIRECTORY = "/data/system/app_freecess_timeout"
private const val GWH_PACKAGE = "com.gohj99.galaxywatchhelper"
// Vector names Android System "system/0". Side-key, Freecess and the VPN
// backend all hook system_server. VPN apps use its Binder service and require
// ACTIVATE_VPN authorization, not Xposed injection into their own processes.
private val GWH_VECTOR_SCOPES = listOf("system/0")
private const val NEOZYGISK_SHA256 = "93a1425c67bb89f58a0dcb9fc823ec93f6472214f221e667a70b67c6a6e061a1"
private const val VECTOR_SHA256 = "9ee8323575d615f7b3f1076ff60b2a63a49390ef11881b52632311a37f6f79cc"

data class ModuleStatus(
    val isChecking: Boolean = true,
    val hasRoot: Boolean = false,
    val gwhInstalled: Boolean = false,
    val targetPackage: String = "",
    val enabled: Boolean = true,
    val freecessEnabled: Boolean = false,
    val freecessLoggingEnabled: Boolean = false,
    val wearVpnRequested: Boolean = false,
    val wearVpnConfigReadable: Boolean = false,
    val wearVpnConsentRequested: Boolean = false,
    val wearVpnConsentConfigReadable: Boolean = false,
    val modules: String = "",
)

data class WearVpnApplicationState(
    val packageName: String,
    val label: String,
    val authorized: Boolean? = null,
)

/** Root/module operations shared by every module-related screen. */
@Stable
object ModuleManager {
    var status by mutableStateOf(ModuleStatus())
        private set
    var targetPackage by mutableStateOf("")
    var freecessLogs by mutableStateOf<List<String>>(emptyList())
        private set
    var freecessLogContent by mutableStateOf("")
        private set
    var wearVpnApplications by mutableStateOf<List<WearVpnApplicationState>>(emptyList())
        private set
    var notice by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set

    suspend fun refreshStatus() {
        val refreshed = withContext(Dispatchers.IO) { readModuleStatus() }
        status = refreshed
        targetPackage = refreshed.targetPackage
    }

    suspend fun enableModule(context: Context) {
        if (busy) return
        busy = true
        notice = context.getString(R.string.message_enabling_module)
        try {
            notice = withContext(Dispatchers.IO) { enableGwhVectorModule(context) }
            refreshStatus()
        } finally {
            busy = false
        }
    }

    suspend fun installModules(context: Context, onProgress: (String) -> Unit) {
        if (busy) return
        busy = true
        notice = context.getString(R.string.message_preparing_module, "NeoZygisk")
        try {
            notice = withContext(Dispatchers.IO) { installFrameworkModules(context, onProgress) }
            refreshStatus()
        } finally {
            busy = false
        }
    }

    suspend fun setWalletRedirect(context: Context, enabled: Boolean) {
        if (busy) return
        busy = true
        try {
            val result = withContext(Dispatchers.IO) {
                runRoot("settings put global $ENABLED_SETTING ${if (enabled) 1 else 0}")
            }
            val success = context.getString(
                if (enabled) R.string.message_module_enabled else R.string.message_module_disabled,
            )
            notice = result.message(context, success)
            refreshStatus()
        } finally {
            busy = false
        }
    }

    suspend fun setFreecessHook(context: Context, enabled: Boolean) {
        if (busy) return
        busy = true
        try {
            val result = withContext(Dispatchers.IO) {
                runRoot("settings put global $FREECESS_ENABLED_SETTING ${if (enabled) 1 else 0}")
            }
            val success = context.getString(
                if (enabled) R.string.message_freecess_enabled else R.string.message_freecess_disabled,
            )
            notice = result.message(context, success)
            refreshStatus()
        } finally {
            busy = false
        }
    }

    suspend fun setFreecessLogging(context: Context, enabled: Boolean) {
        if (busy) return
        busy = true
        try {
            val result = withContext(Dispatchers.IO) {
                runRoot("settings put global $FREECESS_LOGGING_SETTING ${if (enabled) 1 else 0}")
            }
            val success = context.getString(
                if (enabled) R.string.message_freecess_logging_enabled
                else R.string.message_freecess_logging_disabled,
            )
            notice = result.message(context, success)
            refreshStatus()
        } finally {
            busy = false
        }
    }

    suspend fun setWearVpnRestore(context: Context, enabled: Boolean) {
        if (busy) return
        if (enabled && WearVpnRestore.firmwareStatus() != WearVpnRestore.FirmwareStatus.AUDITED_CODE_REMOVED) {
            notice = context.getString(R.string.wear_vpn_enable_blocked)
            return
        }
        busy = true
        try {
            val expected = if (enabled) "1" else "0"
            val results = withContext(Dispatchers.IO) {
                val write = runWearVpnRoot("settings put global ${WearVpnRestore.ENABLED_SETTING} $expected")
                val read = runWearVpnRoot("settings get global ${WearVpnRestore.ENABLED_SETTING}")
                write to read
            }
            notice = if (results.first.exitCode == 0 && results.second.exitCode == 0 &&
                results.second.output.trim() == expected
            ) {
                context.getString(if (enabled) R.string.wear_vpn_enabled else R.string.wear_vpn_disabled)
            } else {
                "write exit=${results.first.exitCode}\n${results.first.output}\n" +
                    "read exit=${results.second.exitCode}\n${results.second.output}"
            }
            refreshStatus()
        } finally {
            busy = false
        }
    }

    suspend fun setWearVpnConsent(context: Context, enabled: Boolean) {
        if (busy) return
        if (enabled && !WearVpnConfirmationRedirect.isApiSupported()) {
            notice = context.getString(R.string.wear_vpn_consent_api_blocked)
            return
        }
        busy = true
        try {
            val expected = if (enabled) "1" else "0"
            val results = withContext(Dispatchers.IO) {
                val write = runWearVpnRoot("settings put global ${WearVpnRestore.CONSENT_ENABLED_SETTING} $expected")
                val read = runWearVpnRoot("settings get global ${WearVpnRestore.CONSENT_ENABLED_SETTING}")
                write to read
            }
            notice = if (results.first.exitCode == 0 && results.second.exitCode == 0 &&
                results.second.output.trim() == expected
            ) {
                context.getString(if (enabled) R.string.wear_vpn_consent_enabled else R.string.wear_vpn_consent_disabled)
            } else {
                "write exit=${results.first.exitCode}\n${results.first.output}\n" +
                    "read exit=${results.second.exitCode}\n${results.second.output}"
            }
            refreshStatus()
        } finally {
            busy = false
        }
    }

    suspend fun loadWearVpnApplications(context: Context) {
        if (busy) return
        busy = true
        try {
            val applications = withContext(Dispatchers.IO) {
                vpnApplicationPackages(context).asSequence()
                    .mapNotNull { packageName ->
                        try {
                            val info = context.packageManager.getApplicationInfo(packageName, 0)
                            WearVpnApplicationState(
                                packageName = packageName,
                                label = info.loadLabel(context.packageManager).toString().ifBlank { packageName },
                            )
                        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                            null
                        }
                    }
                    .sortedWith(compareBy({ it.label.lowercase() }, { it.packageName }))
                    .toList()
            }
            wearVpnApplications = if (status.hasRoot) {
                withContext(Dispatchers.IO) {
                    applications.map { application ->
                        val result = runWearVpnRoot(vpnAppOpsCommand("get", application.packageName))
                        application.copy(
                            authorized = if (result.exitCode == 0) {
                                WearVpnAppOpsAuthorization.readbackAllowed(result.output)
                            } else null,
                        )
                    }
                }
            } else {
                applications
            }
        } catch (error: SecurityException) {
            wearVpnApplications = emptyList()
            notice = error.toString()
        } finally {
            busy = false
        }
    }

    suspend fun setWearVpnAuthorization(context: Context, packageName: String, allowed: Boolean) {
        if (busy || !status.hasRoot) return
        busy = true
        try {
            if (!withContext(Dispatchers.IO) { isVpnApplication(context, packageName) }) {
                notice = context.getString(R.string.wear_vpn_invalid_application)
                return
            }
            val results = withContext(Dispatchers.IO) {
                val write = runWearVpnRoot(vpnAppOpsCommand("set", packageName, if (allowed) "allow" else "ignore"))
                val read = runWearVpnRoot(vpnAppOpsCommand("get", packageName))
                write to read
            }
            val verified = if (results.second.exitCode == 0) {
                WearVpnAppOpsAuthorization.readbackAllowed(results.second.output)
            } else null
            wearVpnApplications = wearVpnApplications.map { application ->
                if (application.packageName == packageName) application.copy(authorized = verified) else application
            }
            notice = if (results.first.exitCode == 0 && verified == allowed) {
                context.getString(if (allowed) R.string.wear_vpn_authorization_granted else R.string.wear_vpn_authorization_revoked)
            } else {
                context.getString(R.string.wear_vpn_authorization_unverified)
            }
        } catch (error: SecurityException) {
            notice = context.getString(R.string.wear_vpn_authorization_unverified)
        } finally {
            busy = false
        }
    }

    suspend fun refreshFreecessLogs() {
        freecessLogs = withContext(Dispatchers.IO) {
            val result = runRoot("ls -1r $FREECESS_LOG_DIRECTORY/*.log 2>/dev/null")
            if (result.exitCode == 0) {
                result.output.lineSequence()
                    .map { it.substringAfterLast('/') }
                    .filter { LOG_FILE_NAME.matches(it) }
                    .toList()
            } else {
                emptyList()
            }
        }
    }

    suspend fun readFreecessLog(context: Context, fileName: String) {
        freecessLogContent = ""
        if (!LOG_FILE_NAME.matches(fileName)) {
            freecessLogContent = context.getString(R.string.freecess_log_unavailable)
            return
        }
        val result = withContext(Dispatchers.IO) {
            runRoot("cat ${shellQuote("$FREECESS_LOG_DIRECTORY/$fileName")}")
        }
        freecessLogContent = if (result.exitCode == 0 && result.output.isNotBlank()) {
            result.output
        } else {
            context.getString(R.string.freecess_log_unavailable)
        }
    }

    suspend fun saveWalletTarget(context: Context) {
        if (busy) return
        val input = targetPackage.trim()
        if (input.isBlank()) {
            notice = context.getString(R.string.error_invalid_target)
            return
        }
        val packageManager = context.packageManager
        val component = if ('/' in input) {
            parseTargetComponent(input)
        } else {
            if (!PACKAGE_NAME.matches(input)) {
                notice = context.getString(R.string.error_invalid_package)
                return
            }
            packageManager.getLaunchIntentForPackage(input)?.component
        }
        if (component == null) {
            notice = if ('/' in input) {
                context.getString(R.string.error_invalid_target)
            } else {
                context.getString(R.string.error_no_launcher, input)
            }
            return
        }
        if (component.packageName in BLOCKED_TARGET_PACKAGES) {
            notice = context.getString(R.string.error_invalid_target)
            return
        }
        val activityInfo = try {
            packageManager.getActivityInfo(component, 0)
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            null
        }
        if (activityInfo == null || !activityInfo.enabled || !activityInfo.applicationInfo.enabled ||
            !activityInfo.exported || activityInfo.permission != null
        ) {
            notice = context.getString(R.string.error_invalid_target)
            return
        }

        busy = true
        try {
            val result = withContext(Dispatchers.IO) {
                runRoot("settings put global $TARGET_SETTING ${shellQuote(component.flattenToString())}")
            }
            notice = result.message(context, context.getString(R.string.message_target_saved, component.flattenToShortString()))
            refreshStatus()
        } finally {
            busy = false
        }
    }
}

fun moduleSummary(modules: String, context: Context): String {
    if (modules.isBlank()) return context.getString(R.string.status_unavailable)
    val found = listOf("zygisksu", "zygisk_vector").filter { modules.contains(it, ignoreCase = true) }
    return if (found.isEmpty()) context.getString(R.string.status_no_matching_modules) else found.joinToString(" · ")
}

private fun readModuleStatus(): ModuleStatus {
    val root = runRoot("id -u")
    val hasRoot = root.exitCode == 0 && root.output.trim() == "0"
    if (!hasRoot) return ModuleStatus(isChecking = false)

    val installed = runRoot("pm path $GWH_PACKAGE")
    val target = runRoot("settings get global $TARGET_SETTING").output.trim().takeUnless { it == "null" }.orEmpty()
    val enabled = runRoot("settings get global $ENABLED_SETTING").output.trim() != "0"
    val freecessEnabled = runRoot("settings get global $FREECESS_ENABLED_SETTING").output.trim() == "1"
    val freecessLoggingEnabled = runRoot("settings get global $FREECESS_LOGGING_SETTING").output.trim() == "1"
    val wearVpnConfig = runWearVpnRoot("settings get global ${WearVpnRestore.ENABLED_SETTING}")
    val wearVpnValue = wearVpnConfig.output.trim()
    val wearVpnConfigReadable = wearVpnConfig.exitCode == 0 && wearVpnValue in setOf("null", "0", "1")
    android.util.Log.i(
        "GWSystemServerHook",
        "[WearVpn] app configuration: " + when {
            !wearVpnConfigReadable -> "unreadable, treated as disabled"
            wearVpnValue == "1" -> "enabled request; runtime compatibility checks required"
            else -> "disabled"
        },
    )
    val consentConfig = runWearVpnRoot("settings get global ${WearVpnRestore.CONSENT_ENABLED_SETTING}")
    val consentValue = consentConfig.output.trim()
    val consentConfigReadable = consentConfig.exitCode == 0 && consentValue in setOf("null", "0", "1")
    android.util.Log.i(
        "GWSystemServerHook",
        "[WearVpnConsent] app configuration: " + when {
            !consentConfigReadable -> "unreadable, treated as disabled"
            consentValue == "1" -> "enabled request; API compatibility checks required"
            else -> "disabled"
        },
    )
    val modules = runRoot("$KSU module list").output.trim()
    return ModuleStatus(
        isChecking = false,
        hasRoot = true,
        gwhInstalled = installed.exitCode == 0,
        targetPackage = ComponentName.unflattenFromString(target)?.flattenToShortString().orEmpty(),
        enabled = enabled,
        freecessEnabled = freecessEnabled,
        freecessLoggingEnabled = freecessLoggingEnabled,
        wearVpnRequested = wearVpnConfigReadable && wearVpnValue == "1",
        wearVpnConfigReadable = wearVpnConfigReadable,
        wearVpnConsentRequested = consentConfigReadable && consentValue == "1",
        wearVpnConsentConfigReadable = consentConfigReadable,
        modules = modules,
    )
}

private fun enableGwhVectorModule(context: Context): String {
    val root = runRoot("id -u")
    if (root.exitCode != 0 || root.output.trim() != "0") {
        return context.getString(R.string.error_root_unavailable, root.output.ifBlank { context.getString(R.string.error_permission_denied) })
    }

    if (runRoot("test -x $VECTOR_CLI").exitCode != 0) {
        return context.getString(R.string.error_vector_cli_missing)
    }
    if (runRoot("pm path $GWH_PACKAGE").exitCode != 0) {
        return context.getString(R.string.error_gwh_not_installed)
    }

    // Retain received stdout/stderr on failure or timeout for these setup steps.
    fun vectorCommand(arguments: String) = WearVpnServiceCheck.capture(
        Arrays.asList("su", "-c", "$VECTOR_CLI $arguments"),
        30_000,
    )
    fun commandError(result: WearVpnServiceCheck.Result) = "exit=${result.exitCode}\n${result.output}"

    val module = shellQuote(GWH_PACKAGE)
    val enable = vectorCommand("modules enable $module")
    if (enable.exitCode != 0) {
        return context.getString(R.string.error_enable_failed, commandError(enable))
    }

    val scopes = GWH_VECTOR_SCOPES.joinToString(" ", transform = ::shellQuote)
    val scope = vectorCommand("scope set $module $scopes")
    if (scope.exitCode != 0) {
        return context.getString(R.string.error_scope_failed, commandError(scope))
    }
    // `scope ls MODULE_PKG` is supported by the bundled Vector v2.2-3080 CLI.
    // Display its actual output; do not infer a scope list from the write alone.
    val readback = vectorCommand("scope ls $module")
    val scopeVerified = readback.exitCode == 0 &&
        GWH_VECTOR_SCOPES.all { required -> readback.output.contains(required) }
    if (!scopeVerified) {
        return context.getString(R.string.error_scope_readback_failed, commandError(readback))
    }
    return context.getString(R.string.message_gwh_enabled, GWH_VECTOR_SCOPES.joinToString(", ")) +
        "\n\n" + context.getString(R.string.message_gwh_scope_readback, readback.output)
}

private fun installFrameworkModules(context: Context, onProgress: (String) -> Unit): String {
    val modules = listOf(
        FrameworkAsset("NeoZygisk", "NeoZygisk-v2.4-289-release.zip", NEOZYGISK_SHA256),
        FrameworkAsset("Vector", "Vector-v2.2-3080-Release.zip", VECTOR_SHA256),
    )
    for (module in modules) {
        val path = "/data/local/tmp/${module.assetName.lowercase()}"
        try {
            onProgress(context.getString(R.string.message_preparing_module, module.label))
            if (!writeAssetAsRoot(context, module.assetName, path, module.sha256)) {
                return context.getString(R.string.error_zip_staging_failed, module.label)
            }
            onProgress(context.getString(R.string.message_installing_module, module.label))
            val install = runRoot("$KSU module install ${shellQuote(path)}")
            if (install.exitCode != 0) {
                return context.getString(R.string.error_module_install_failed, module.label, install.output.ifBlank { "exit ${install.exitCode}" })
            }
            if (module.label == "Vector") {
                onProgress(context.getString(R.string.message_installing_vector_manager))
                val manager = runRoot("unzip -p ${shellQuote(path)} manager.apk > /data/local/tmp/vector-manager.apk && test -s /data/local/tmp/vector-manager.apk && pm install -r /data/local/tmp/vector-manager.apk")
                if (manager.exitCode != 0) {
                    return context.getString(R.string.error_vector_manager_failed, manager.output.ifBlank { "exit ${manager.exitCode}" })
                }
            }
        } catch (error: Exception) {
            return context.getString(R.string.error_module_failed, module.label, error.message ?: error.javaClass.simpleName)
        } finally {
            runRoot("rm -f ${shellQuote(path)}")
            if (module.label == "Vector") runRoot("rm -f /data/local/tmp/vector-manager.apk")
        }
    }
    return context.getString(R.string.message_frameworks_installed)
}

private data class FrameworkAsset(val label: String, val assetName: String, val sha256: String)

private fun writeAssetAsRoot(context: Context, asset: String, destination: String, expectedSha256: String): Boolean {
    val process = ProcessBuilder("su", "-c", "cat > ${shellQuote(destination)}").redirectErrorStream(true).start()
    val reader = Thread { process.inputStream.bufferedReader().use { it.readText() } }.apply { isDaemon = true; start() }
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
    return process.exitValue() == 0 && actualSha256 == expectedSha256
}

private data class ShellResult(val exitCode: Int, val output: String)

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

private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+$")
private val LOG_FILE_NAME = Regex("^[0-9]{8}_[0-9]{6}_[0-9]{3}(?:_[0-9]+)?\\.log$")
private val BLOCKED_TARGET_PACKAGES = setOf("android", "com.samsung.android.samsungpay.gear")
/** Only called with a package revalidated against the current user's PackageManager. */
private fun vpnAppOpsCommand(action: String, packageName: String, mode: String? = null): String {
    val userId = android.os.Process.myUid() / 100_000
    return WearVpnAppOpsAuthorization.command(action, packageName, userId, mode)
}

private fun runWearVpnRoot(command: String): WearVpnServiceCheck.Result =
    WearVpnServiceCheck.capture(Arrays.asList("su", "-c", command), 15_000)

private fun parseTargetComponent(value: String): ComponentName? {
    val separator = value.indexOf('/')
    if (separator <= 0 || separator == value.lastIndex || value.indexOf('/', separator + 1) >= 0) return null
    val packageName = value.substring(0, separator)
    if (!PACKAGE_NAME.matches(packageName)) return null
    val activityName = value.substring(separator + 1)
    val qualifiedName = when {
        activityName.startsWith('.') -> packageName + activityName
        '.' !in activityName -> "$packageName.$activityName"
        else -> activityName
    }
    return ComponentName(packageName, qualifiedName)
}

private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

private fun ShellResult.message(context: Context, success: String): String =
    if (exitCode == 0) success else output.ifBlank { context.getString(R.string.error_command_failed, exitCode) }
