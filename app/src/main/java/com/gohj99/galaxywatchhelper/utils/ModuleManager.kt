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
import java.util.concurrent.TimeUnit

private const val KSU = "/data/adb/ksud"
private const val VECTOR_CLI = "/data/adb/modules/zygisk_vector/cli"
private const val TARGET_SETTING = "gw_wallet_redirect_target"
private const val ENABLED_SETTING = "gw_wallet_hook_enabled"
private const val GWH_PACKAGE = "com.gohj99.galaxywatchhelper"
private const val NEOZYGISK_SHA256 = "93a1425c67bb89f58a0dcb9fc823ec93f6472214f221e667a70b67c6a6e061a1"
private const val VECTOR_SHA256 = "9ee8323575d615f7b3f1076ff60b2a63a49390ef11881b52632311a37f6f79cc"

data class ModuleStatus(
    val isChecking: Boolean = true,
    val hasRoot: Boolean = false,
    val gwhInstalled: Boolean = false,
    val targetPackage: String = "",
    val enabled: Boolean = true,
    val modules: String = "",
)

/** Root/module operations shared by every module-related screen. */
@Stable
object ModuleManager {
    var status by mutableStateOf(ModuleStatus())
        private set
    var targetPackage by mutableStateOf("")
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
    val modules = runRoot("$KSU module list").output.trim()
    return ModuleStatus(
        isChecking = false,
        hasRoot = true,
        gwhInstalled = installed.exitCode == 0,
        targetPackage = ComponentName.unflattenFromString(target)?.flattenToShortString().orEmpty(),
        enabled = enabled,
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

    val enable = runRoot("$VECTOR_CLI modules enable ${shellQuote(GWH_PACKAGE)}")
    if (enable.exitCode != 0) {
        return context.getString(R.string.error_enable_failed, enable.output.ifBlank { "exit ${enable.exitCode}" })
    }

    val scope = runRoot("$VECTOR_CLI scope set ${shellQuote(GWH_PACKAGE)} system/0")
    if (scope.exitCode != 0) {
        return context.getString(R.string.error_scope_failed, scope.output.ifBlank { "exit ${scope.exitCode}" })
    }
    return context.getString(R.string.message_gwh_enabled)
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
private val BLOCKED_TARGET_PACKAGES = setOf("android", "com.samsung.android.samsungpay.gear")

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
