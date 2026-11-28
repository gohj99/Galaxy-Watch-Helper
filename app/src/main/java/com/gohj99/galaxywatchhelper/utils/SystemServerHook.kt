package com.gohj99.galaxywatchhelper.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Message
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Xposed entry point for the side-key/freezer hooks and Wear VPN safety gate. */
class SystemServerHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(load: XC_LoadPackage.LoadPackageParam) {
        if (load.packageName == GWH_PACKAGE) {
            HookRuntimeStatus.markSelfInjected()
            Log.i(TAG, "Installed self hook in ${load.processName}")
            return
        }
        if (Process.myUid() != 1000 || load.packageName != ANDROID_PACKAGE ||
            (load.processName != ANDROID_PACKAGE && load.processName != SYSTEM_SERVER_PROCESS)
        ) {
            return
        }

        HookRuntimeStatus.markSystemInjected(load.classLoader)
        WearVpnRestore.onSystemServerEntry(load.classLoader)
        WearVpnConfirmationRedirect.onSystemServerEntry(load.classLoader)
        installSideKeyHook(load)
        installFreecessHook(load)
    }

    private fun installSideKeyHook(load: XC_LoadPackage.LoadPackageParam) {
        try {
            val policy = Class.forName(POLICY_EXT, false, load.classLoader)
            val rule = Class.forName(BACK_KEY_RULE, false, load.classLoader)
            val contextField = policy.getDeclaredField("mContext").apply { isAccessible = true }
            val launch = policy.getDeclaredMethod(
                "startActivityAsUser",
                Intent::class.java,
                Boolean::class.javaPrimitiveType,
            )

            // ART can inline this final launch method into Samsung's long-press callback.
            val longPress = rule.getDeclaredMethod(
                "onLongPress",
                Long::class.javaPrimitiveType,
                KeyEvent::class.java,
            )
            XposedBridge::class.java
                .getMethod("deoptimizeMethod", Member::class.java)
                .invoke(null, longPress)
            XposedBridge.hookMethod(launch, object : XC_MethodHook() {
                override fun beforeHookedMethod(call: MethodHookParam) {
                    (contextField.get(call.thisObject) as? Context)?.let(HookRuntimeStatus::markSystemInjected)
                    val original = call.args[0] as? Intent
                    if (original == null || original.component != WALLET ||
                        original.action != Intent.ACTION_MAIN || call.args[1] != true || !fromLongPress()
                    ) {
                        return
                    }

                    try {
                        val context = contextField.get(call.thisObject) as? Context
                        val target = readTarget(context)
                        if (target == null) {
                            Log.i(TAG, "Side-key shortcut matched, no valid target configured")
                            return
                        }
                        // The original policy adds its task flags and handles the lock screen.
                        call.args[0] = Intent(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_LAUNCHER)
                            .setComponent(target)
                        Log.i(TAG, "Side-key shortcut redirected to ${target.flattenToShortString()}")
                    } catch (error: ReflectiveOperationException) {
                        Log.w(TAG, "Side-key shortcut left unchanged", error)
                    } catch (error: RuntimeException) {
                        Log.w(TAG, "Side-key shortcut left unchanged", error)
                    }
                }
            })
            Log.i(TAG, "Installed side-key hook in ${load.processName}, pid=${Process.myPid()}")
        } catch (error: ReflectiveOperationException) {
            Log.e(TAG, "Could not install side-key hook", error)
        } catch (error: RuntimeException) {
            Log.e(TAG, "Could not install side-key hook", error)
        } catch (error: LinkageError) {
            Log.e(TAG, "Could not install side-key hook", error)
        }
    }

    private fun installFreecessHook(load: XC_LoadPackage.LoadPackageParam) {
        try {
            val supervisorClass = XposedHelpers.findClass(SUPERVISOR_CLASS, load.classLoader)
            val handlerClass = XposedHelpers.findClass(HANDLER_CLASS, load.classLoader)
            val timeoutField = supervisorClass.getDeclaredField("LAUNCH_TIMEOUT_MSG").apply {
                isAccessible = true
            }
            val timeoutWhat = timeoutField.getInt(null)
            val target = findMessageMethod(handlerClass, "handleMessageInner")
                ?: findMessageMethod(handlerClass, "handleMessage")
                ?: throw NoSuchMethodException(
                    "$HANDLER_CLASS.handleMessageInner(Message)/handleMessage(Message)",
                )

            XposedBridge.hookMethod(target, object : XC_MethodHook() {
                override fun beforeHookedMethod(call: MethodHookParam) {
                    val message = call.args.firstOrNull() as? Message ?: return
                    if (message.what != timeoutWhat) return
                    val supervisor = enabledSupervisor(call.thisObject) ?: return
                    recordTimeout(supervisor, message.what)
                }
            })
            Log.i(
                TAG,
                "Installed freecess hook in system_server, pid=${Process.myPid()} " +
                    "method=${target.name} timeoutWhat=$timeoutWhat",
            )
        } catch (error: Throwable) {
            Log.e(TAG, "Could not install freecess hook", error)
        }
    }

    private fun enabledSupervisor(handler: Any): Any? {
        val supervisor = findSupervisor(handler) ?: return null
        val service = readField(supervisor, "mService") ?: return null
        val context = readField(service, "mContext") as? Context ?: return null
        val resolver = context.contentResolver
        return try {
            if (Settings.Global.getInt(resolver, FREECESS_ENABLED_SETTING, 0) != 1) null
            else if (Settings.Global.getInt(resolver, FREECESS_LOGGING_SETTING, 0) != 1) null
            else supervisor
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun recordTimeout(supervisor: Any, messageWhat: Int) {
        try {
            val service = readField(supervisor, "mService")
            val timeoutElapsedMs = SystemClock.elapsedRealtime()
            val timeoutWallClockMs = System.currentTimeMillis()
            val globalLock = readField(service, "mGlobalLock")
            val snapshot = if (globalLock != null) {
                synchronized(globalLock) { takeSnapshot(supervisor) }
            } else {
                takeSnapshot(supervisor)
            } ?: return

            LOGGER.execute {
                writeTimeout(snapshot, messageWhat, timeoutElapsedMs, timeoutWallClockMs)
            }
        } catch (_: RejectedExecutionException) {
            Log.w(TAG, "EVENT=LAUNCH_TIMEOUT snapshot_dropped=logger_busy what=$messageWhat")
        } catch (error: Throwable) {
            Log.e(TAG, "EVENT=LAUNCH_TIMEOUT snapshot_failed what=$messageWhat", error)
        }
    }

    private fun writeTimeout(
        snapshot: Snapshot,
        messageWhat: Int,
        timeoutElapsedMs: Long,
        timeoutWallClockMs: Long,
    ) {
        try {
            val captureDelayMs = SystemClock.elapsedRealtime() - timeoutElapsedMs
            val frozen = dumpFrozenProcesses()
            val record = buildString {
                append("EVENT=LAUNCH_TIMEOUT")
                append(" what=").append(messageWhat)
                append(" timeoutWallClockMs=").append(timeoutWallClockMs)
                append(" timeoutElapsedMs=").append(timeoutElapsedMs)
                append(" captureDelayMs=").append(captureDelayMs)
                append(" wakeLockHeld=").append(snapshot.wakeLockHeld ?: "unknown")
                append(" systemServerPid=").append(Process.myPid())
                append(" topActivity=").append(snapshot.activity)
                append(" topProcess=").append(snapshot.process)
                append(" freezerState=").append(frozen.state)
                append(" frozenPids=").append(frozen.pids)
                append(" frozenProcesses=").append(frozen.processes)
            }.replace('\n', ' ').replace('\r', ' ')
            val file = writeRecord(timeoutWallClockMs, record)
            Log.i(TAG, "EVENT=LAUNCH_TIMEOUT saved=$file what=$messageWhat")
        } catch (error: Throwable) {
            Log.e(TAG, "EVENT=LAUNCH_TIMEOUT freezer_capture_failed what=$messageWhat", error)
        }
    }

    private fun writeRecord(timestampMs: Long, record: String): File {
        val directory = File(FREECESS_LOG_DIRECTORY)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Could not create log directory=$FREECESS_LOG_DIRECTORY")
        }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(timestampMs))
        var suffix = 0
        while (true) {
            val name = if (suffix == 0) "$timestamp.log" else "${timestamp}_$suffix.log"
            val file = File(directory, name)
            if (file.createNewFile()) {
                try {
                    file.writeText("$record\n")
                    return file
                } catch (error: Throwable) {
                    file.delete()
                    throw error
                }
            }
            if (!file.exists()) {
                throw IllegalStateException("Could not create log file=${file.absolutePath}")
            }
            suffix++
        }
    }

    private fun dumpFrozenProcesses(): FrozenSnapshot {
        val state = readFirstLine(FROZEN_STATE)?.trim() ?: "unavailable"
        val pidList = readFile(FROZEN_PROCS)?.trim()?.replace('\n', ',') ?: "unavailable"
        if (pidList == "unavailable") return FrozenSnapshot(state, pidList, "unavailable")

        val details = ArrayList<String>()
        var count = 0
        for (pidText in pidList.split(',')) {
            if (pidText.isBlank()) continue
            if (count++ >= MAX_FROZEN_PROCESSES) break
            val pid = pidText.trim().toIntOrNull() ?: continue
            details += processDetails(pid)
        }
        return FrozenSnapshot(state, pidList, details.joinToString(" || ").ifBlank { "none" })
    }

    private fun processDetails(pid: Int): String {
        val status = readFile("/proc/$pid/status")
        val cmdline = readFile("/proc/$pid/cmdline")
        val cgroup = readFile("/proc/$pid/cgroup")
        val name = statusValue(status, "Name")
        val processState = statusValue(status, "State")
        val uid = statusValue(status, "Uid")
        val command = cmdline?.replace('\u0000', ' ')?.trim() ?: "unavailable"
        val freezerLine = cgroup?.lineSequence()
            ?.firstOrNull { it.contains("freezer", ignoreCase = true) }
            ?.trim() ?: "unavailable"
        return "pid=$pid uid=${safe(uid)} state=${safe(processState)} " +
            "name=${safe(name)} cmd=${safe(command)} freezerCgroup=${safe(freezerLine)}"
    }

    private fun takeSnapshot(supervisor: Any): Snapshot? {
        val wakeLock = readField(supervisor, "mLaunchingActivityWakeLock")
        val wakeLockHeld = invokeNoArgs(wakeLock, "isHeld")
        if (wakeLockHeld == false) return null
        val held = wakeLockHeld as? Boolean

        var activity = readField(supervisor, "mTopResumedActivity")
        if (activity == null) {
            val root = readField(supervisor, "mRootWindowContainer")
            val focusedRoot = invokeNoArgs(root, "getTopDisplayFocusedRootTask")
            activity = invokeNoArgs(focusedRoot, "getTopResumedActivity")
        }
        if (activity == null) return Snapshot(held, "unavailable", "unavailable")

        val process = readField(activity, "app")
        val activityInfo = "component=${value(activity, "mActivityComponent", "activityComponent")}," +
            "package=${value(activity, "packageName")}," +
            "state=${value(activity, "mState", "state")}," +
            "reportedDrawn=${findReportedDrawn(activity)}"
        val processInfo = if (process == null) {
            "unattached"
        } else {
            "name=${value(process, "processName", "mName", "name")}," +
                "pid=${value(process, "mPid", "pid")}," +
                "uid=${value(process, "mUid", "uid")}"
        }
        return Snapshot(held, activityInfo, processInfo)
    }

    private fun findSupervisor(handler: Any): Any? {
        readField(handler, "this\$0")?.let { return it }
        for (type in generateSequence(handler.javaClass) { it.superclass }) {
            for (field in type.declaredFields) {
                if (Modifier.isStatic(field.modifiers) || field.type.name != SUPERVISOR_CLASS) continue
                try {
                    field.isAccessible = true
                    return field.get(handler)
                } catch (_: Throwable) {
                    return null
                }
            }
        }
        return null
    }

    private fun findMessageMethod(type: Class<*>, name: String): Method? =
        type.declaredMethods.firstOrNull { method ->
            method.name == name && method.parameterTypes.size == 1 &&
                Message::class.java.isAssignableFrom(method.parameterTypes[0])
        }

    private fun fromLongPress(): Boolean = Thread.currentThread().stackTrace.any { frame ->
        frame.className == BACK_KEY_RULE && frame.methodName == "onLongPress"
    }

    private fun readTarget(context: Context?): ComponentName? {
        if (context == null || Settings.Global.getInt(context.contentResolver, ENABLED_SETTING, 1) == 0) {
            return null
        }

        val value = Settings.Global.getString(context.contentResolver, TARGET_SETTING) ?: return null
        val target = ComponentName.unflattenFromString(value.trim()) ?: return null
        if (target.packageName == WALLET_PACKAGE || target.packageName == ANDROID_PACKAGE) return null

        return try {
            val info: ActivityInfo = context.packageManager.getActivityInfo(target, 0)
            target.takeIf {
                info.exported && info.enabled && info.applicationInfo.enabled && info.permission == null
            }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun value(obj: Any, vararg names: String): String = names.firstNotNullOfOrNull {
        readField(obj, it)?.toString()
    } ?: "unavailable"

    private fun readField(obj: Any?, name: String): Any? {
        if (obj == null) return null
        for (type in generateSequence(obj.javaClass) { it.superclass }) {
            try {
                val field = type.getDeclaredField(name)
                field.isAccessible = true
                return field.get(obj)
            } catch (_: NoSuchFieldException) {
                // Continue with the superclass.
            } catch (_: Throwable) {
                return null
            }
        }
        return null
    }

    private fun invokeNoArgs(obj: Any?, name: String): Any? {
        if (obj == null) return null
        for (type in generateSequence(obj.javaClass) { it.superclass }) {
            try {
                val method = type.getDeclaredMethod(name)
                method.isAccessible = true
                return method.invoke(obj)
            } catch (_: NoSuchMethodException) {
                // Continue with the superclass.
            } catch (_: Throwable) {
                return null
            }
        }
        return null
    }

    private fun readFirstLine(path: String): String? = readLines(path)?.firstOrNull()

    private fun readFile(path: String): String? = readLines(path)?.joinToString("\n")

    private fun readLines(path: String): List<String>? {
        val file = File(path)
        if (!file.canRead()) return null
        return try {
            BufferedReader(FileReader(file)).use { it.readLines() }
        } catch (_: Throwable) {
            null
        }
    }

    private fun statusValue(status: String?, key: String): String? = status?.lineSequence()
        ?.firstOrNull { it.startsWith("$key:") }
        ?.substringAfter(':')
        ?.trim()

    private fun findReportedDrawn(activity: Any): String {
        for (type in generateSequence(activity.javaClass) { it.superclass }) {
            for (field in type.declaredFields) {
                if (!field.name.contains("reporteddrawn", ignoreCase = true)) continue
                try {
                    field.isAccessible = true
                    return field.get(activity)?.toString() ?: "null"
                } catch (_: Throwable) {
                    return "inaccessible"
                }
            }
        }
        return "field_unavailable"
    }

    private fun safe(value: String?): String = value?.replace('\n', ' ')?.replace('\r', ' ') ?: "unavailable"

    private data class Snapshot(val wakeLockHeld: Boolean?, val activity: String, val process: String)

    private data class FrozenSnapshot(val state: String, val pids: String, val processes: String)

    private companion object {
        const val TAG = "GWSystemServerHook"
        const val ANDROID_PACKAGE = "android"
        const val GWH_PACKAGE = "com.gohj99.galaxywatchhelper"
        const val SYSTEM_SERVER_PROCESS = "system_server"
        const val POLICY_EXT = "com.android.server.policy.PhoneWindowManagerExt"
        const val BACK_KEY_RULE = $$"com.android.server.policy.PhoneWindowManager$BackKeyRule"
        const val SUPERVISOR_CLASS = "com.android.server.wm.ActivityTaskSupervisor"
        const val HANDLER_CLASS = "com.android.server.wm.ActivityTaskSupervisor\$ActivityTaskSupervisorHandler"
        const val FROZEN_STATE = "/dev/freezer/frozen/freezer.state"
        const val FROZEN_PROCS = "/dev/freezer/frozen/cgroup.procs"
        const val FREECESS_LOG_DIRECTORY = "/data/system/app_freecess_timeout"
        const val FREECESS_ENABLED_SETTING = "gw_freecess_hook_enabled"
        const val FREECESS_LOGGING_SETTING = "gw_freecess_logging_enabled"
        const val TARGET_SETTING = "gw_wallet_redirect_target"
        const val ENABLED_SETTING = "gw_wallet_hook_enabled"
        const val WALLET_PACKAGE = "com.samsung.android.samsungpay.gear"
        const val MAX_FROZEN_PROCESSES = 128
        val WALLET = ComponentName(WALLET_PACKAGE, "$WALLET_PACKAGE.app.MainActivityWallet")
        val LOGGER by lazy {
            ThreadPoolExecutor(
                0,
                1,
                15,
                TimeUnit.SECONDS,
                LinkedBlockingQueue(),
                { runnable ->
                    Thread({
                        try {
                            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                        } catch (_: SecurityException) {
                            // Keep the inherited priority if the process denies the change.
                        }
                        runnable.run()
                    }, "GWFreecessLogger")
                },
            )
        }
    }
}
