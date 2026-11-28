package com.gohj99.galaxywatchhelper.utils

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.app.AndroidAppHelper
import java.io.File

/** Runtime handshake used by the app to tell whether the Xposed entry point is active. */
internal object HookRuntimeStatus {
    const val SYSTEM_HOOK_SETTING = "gw_gwh_system_hook_marker"
    const val SYSTEM_MARKER_FILE = "/data/system/gw_gwh_system_hook_marker"
    private const val TAG = "GWSystemServerHook"
    private const val GWH_PACKAGE = "com.gohj99.galaxywatchhelper"
    private const val SELF_PREFS = "gwh_hook_runtime"
    private const val SELF_PID = "self_hook_pid"
    private const val SELF_MARKER_FILE = "gwh_hook_runtime.pid"

    /** This is intentionally process-local so a disabled module cannot leave a stale self result. */
    @Volatile
    var selfInjected: Boolean = false
        private set

    fun markSelfInjected() {
        selfInjected = true
        try {
            // Vector exposes getSharedPreferencesForPackage as a read-only XSharedPreferences
            // view. Use the injected application's real context so the marker is writable and
            // can be read by the normal app class loader below.
            val application = AndroidAppHelper.currentApplication()
            val persisted = application?.getSharedPreferences(SELF_PREFS, Context.MODE_PRIVATE)
                ?.edit()
                ?.putInt(SELF_PID, Process.myPid())
                ?.commit() == true
            if (!persisted) {
                AndroidAppHelper.currentApplicationInfo()?.dataDir?.let { dataDir ->
                    File(dataDir, "files/$SELF_MARKER_FILE").apply {
                        parentFile?.mkdirs()
                        writeText(Process.myPid().toString())
                    }
                }
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Could not persist self hook marker", error)
        }
    }

    fun isSelfInjected(context: Context): Boolean = selfInjected || try {
        context.getSharedPreferences(SELF_PREFS, Context.MODE_PRIVATE)
            .getInt(SELF_PID, -1) == Process.myPid() ||
            context.filesDir.resolve(SELF_MARKER_FILE).let { file ->
                file.isFile && file.readText().trim() == Process.myPid().toString()
            }
    } catch (_: RuntimeException) {
        false
    }

    fun markSystemInjected(context: Context) {
        writeSystemMarker()
        try {
            val bootCount = Settings.Global.getInt(context.contentResolver, "boot_count", -1)
            val marker = "$bootCount:${SystemClock.elapsedRealtime()}"
            Settings.Global.putString(context.contentResolver, SYSTEM_HOOK_SETTING, marker)
        } catch (error: Throwable) {
            Log.w(TAG, "Could not persist system hook marker", error)
        }
    }

    fun markSystemInjected(loader: ClassLoader) {
        writeSystemMarker()
        try {
            val activityThread = Class.forName("android.app.ActivityThread", false, loader)
            val thread = activityThread.getDeclaredMethod("currentActivityThread").invoke(null) ?: return
            val context = activityThread.getDeclaredMethod("getSystemContext").invoke(thread) as? Context ?: return
            markSystemInjected(context)
        } catch (error: Throwable) {
            Log.d(TAG, "Could not resolve system context for hook marker", error)
        }
    }

    fun isCurrentSystemMarker(context: Context, marker: String): Boolean {
        val parts = marker.trim().split(':', limit = 2)
        if (parts.size != 2) return false
        val boot = parts[0].toIntOrNull() ?: return false
        val elapsed = parts[1].toLongOrNull() ?: return false
        if (elapsed <= 0L || elapsed > SystemClock.elapsedRealtime() + 5_000L) return false
        val currentBoot = try {
            Settings.Global.getInt(context.contentResolver, "boot_count", -1)
        } catch (_: RuntimeException) {
            -1
        }
        // A system process can run before boot_count is published and records -1. In that case
        // the uptime marker is still useful; rejecting it would make every fresh boot look stale.
        return boot < 0 || currentBoot < 0 || boot == currentBoot
    }

    private fun writeSystemMarker() {
        try {
            File(SYSTEM_MARKER_FILE).apply {
                parentFile?.mkdirs()
                writeText(systemMarker())
            }
        } catch (error: Throwable) {
            Log.d(TAG, "Could not persist system hook file marker", error)
        }
    }

    private fun systemMarker(): String {
        val bootCount = try {
            AndroidAppHelper.currentApplication()?.let {
                Settings.Global.getInt(it.contentResolver, "boot_count", -1)
            } ?: -1
        } catch (_: Throwable) {
            -1
        }
        return "$bootCount:${SystemClock.elapsedRealtime()}"
    }
}
