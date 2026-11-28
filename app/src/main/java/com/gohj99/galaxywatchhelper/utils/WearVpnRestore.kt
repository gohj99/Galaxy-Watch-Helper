package com.gohj99.galaxywatchhelper.utils

import android.content.Context
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import com.gohj99.galaxywatchhelper.utils.vpn.WearVpnService
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Member
import java.util.concurrent.atomic.AtomicBoolean

/** Restores app VpnService using the audited firmware's JNI and Connectivity ABI. */
internal object WearVpnRestore {
    const val ENABLED_SETTING = "gw_wear_vpn_restore_enabled"
    const val CONSENT_ENABLED_SETTING = "gw_wear_vpn_consent_enabled"
    private const val TAG = "GWSystemServerHook"
    private const val INITIALIZER = "com.android.server.ConnectivityServiceInitializer"
    private const val CONNECTIVITY_JAR = "/apex/com.android.tethering/javalib/service-connectivity.jar"
    private const val READY_CALLER = "\$r8\$lambda\$_Ik9fEJxxT_3L9POeIJQOWRQjbw"
    private val entered = AtomicBoolean(false)
    private var service: WearVpnService? = null

    enum class FirmwareStatus {
        AUDITED_CODE_REMOVED,
        NOT_AUDITED,
    }

    fun firmwareStatus(): FirmwareStatus = if (
        Build.VERSION.SDK_INT == 36 && Build.MODEL == "SM-L315F" &&
        Build.DEVICE == "fresh7ul" && Build.ID == "BP2A.250325.020" &&
        Build.VERSION.INCREMENTAL == "L315FXXS3BZF4"
    ) {
        FirmwareStatus.AUDITED_CODE_REMOVED
    } else {
        FirmwareStatus.NOT_AUDITED
    }

    fun onSystemServerEntry(loader: ClassLoader) {
        Log.i(TAG, "[WearVpn] system_server entry reached")
        if (!entered.compareAndSet(false, true)) return
        if (firmwareStatus() != FirmwareStatus.AUDITED_CODE_REMOVED) {
            Log.w(TAG, "[WearVpn] unsupported firmware; restoration skipped")
            return
        }
        var startHook: XC_MethodHook.Unhook? = null
        var readyHook: XC_MethodHook.Unhook? = null
        try {
            val sm = Class.forName("android.os.ServiceManager", false, loader)
            val check = sm.getMethod("checkService", String::class.java)
            if (check.invoke(null, "activity") != null) {
                Log.w(TAG, "[WearVpn] late injection; boot restoration skipped")
                return
            }
            val manager = Class.forName("com.android.server.SystemServiceManager", false, loader)
            val timings = Class.forName("com.android.server.utils.TimingsTraceAndSlog", false, loader)
            val contextField = manager.getDeclaredField("mContext").apply { isAccessible = true }
            val phaseField = manager.getDeclaredField("mCurrentPhase").apply { isAccessible = true }
            val start = manager.getDeclaredMethod("startServiceFromJar", String::class.java, String::class.java)
            val phase = manager.getDeclaredMethod("startBootPhase", timings, Int::class.javaPrimitiveType)
            val server = Class.forName("com.android.server.SystemServer", false, loader)
            val startCaller = server.getDeclaredMethod("startOtherServices", timings)
            val readyCaller = server.declaredMethods.single {
                it.name == READY_CALLER && it.parameterCount == 16 && it.returnType == Void.TYPE
            }
            val deopt = XposedBridge::class.java.getMethod("deoptimizeMethod", Member::class.java)
            readyHook = XposedBridge.hookMethod(phase, object : XC_MethodHook() {
                override fun beforeHookedMethod(call: MethodHookParam) {
                    if (call.args[1] != 600) return
                    try {
                        if (phaseField.getInt(call.thisObject) != 550) {
                            Log.w(TAG, "[WearVpn] unexpected ready phase; app calls remain disabled")
                        } else service?.systemReady()
                    } catch (error: Throwable) {
                        Log.e(TAG, "[WearVpn] own systemReady failed; app calls disabled", error)
                        try { service?.shutdown() } catch (cleanup: Throwable) {
                            Log.e(TAG, "[WearVpn] ready cleanup failed", cleanup)
                        }
                    } finally { readyHook?.unhook() }
                }
            })
            startHook = XposedBridge.hookMethod(start, object : XC_MethodHook() {
                override fun afterHookedMethod(call: MethodHookParam) {
                    if (call.args[0] != INITIALIZER || call.args[1] != CONNECTIVITY_JAR) return
                    var candidate: WearVpnService? = null
                    try {
                        if (call.hasThrowable()) {
                            Log.w(TAG, "[WearVpn] connectivity initializer failed; original error preserved")
                            return
                        }
                        if (phaseField.getInt(call.thisObject) != 200) {
                            Log.w(TAG, "[WearVpn] unexpected startup phase; restoration skipped")
                            return
                        }
                        val context = contextField.get(call.thisObject) as Context
                        val configured = try {
                            Settings.Global.getString(context.contentResolver, ENABLED_SETTING)
                        } catch (error: Throwable) {
                            Log.w(TAG, "[WearVpn] configuration unreadable; disabled", error)
                            return
                        }
                        if (configured != "1") {
                            Log.i(TAG, "[WearVpn] disabled; no restoration backend created")
                            return
                        }
                        if (check.invoke(null, "vpn_management") != null) {
                            Log.w(TAG, "[WearVpn] existing vpn_management found; duplicate registration skipped")
                            return
                        }
                        Log.i(TAG, "[WearVpn] exact boot hook hit; creating app VPN backend")
                        candidate = WearVpnService(context, loader)
                        sm.getMethod("addService", String::class.java, IBinder::class.java)
                            .invoke(null, "vpn_management", candidate.asBinder())
                        if (check.invoke(null, "vpn_management") == null) {
                            throw IllegalStateException("vpn_management registration check returned null")
                        }
                        service = candidate
                        candidate = null
                        Log.i(TAG, "[WearVpn] registration check: vpn_management found (tunnel/network not yet tested)")
                    } catch (error: Throwable) {
                        Log.e(TAG, "[WearVpn] own startup failed; restoration skipped", error)
                    } finally {
                        try { candidate?.shutdown() } catch (error: Throwable) {
                            Log.e(TAG, "[WearVpn] partial startup cleanup failed", error)
                        }
                        startHook?.unhook()
                        if (service == null) readyHook?.unhook()
                    }
                }
            })
            for (target in listOf(startCaller, readyCaller)) {
                deopt.invoke(null, target)
                Log.i(TAG, "[WearVpn] deoptimization called for ${target.name}; framework returns void")
            }
            Log.i(TAG, "[WearVpn] precise boot callbacks installed; awaiting early Settings.Global configuration")
        } catch (error: Throwable) {
            startHook?.unhook()
            readyHook?.unhook()
            Log.e(TAG, "[WearVpn] boot hook installation/deoptimization failed; restoration skipped", error)
        }
    }
}
