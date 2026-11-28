package com.gohj99.galaxywatchhelper.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Redirects the platform VPN confirmation component to GWH's user consent Activity. */
internal object WearVpnConfirmationRedirect {
    private const val TAG = "GWSystemServerHook"
    private const val ANDROID_PACKAGE = "android"
    private const val AMS_CLASS = "com.android.server.am.ActivityManagerService"
    private const val ATMS_CLASS = "com.android.server.wm.ActivityTaskManagerService"
    private const val SYSTEM_SERVICE_MANAGER = "com.android.server.SystemServiceManager"
    private const val VPN_BOOTSTRAP_SERVICE = "com.android.server.ConnectivityServiceInitializer"
    private const val CONFIG_CONFIRM_COMPONENT = "config_customVpnConfirmDialogComponent"
    private const val GWH_PACKAGE = "com.gohj99.galaxywatchhelper"

    // Keep this list as the API compatibility table. Adding Android 17 later only requires
    // auditing its launch signature and adding the API level here.
    private val supportedApiLevels = setOf(36)
    private val bootstrapInstalled = AtomicBoolean(false)
    private val installed = AtomicBoolean(false)
    private val hookedMethods = Collections.newSetFromMap(IdentityHashMap<Method, Boolean>())
    private val contextUnavailableLogged = AtomicBoolean(false)
    private val runtimeConfigFailureLogged = AtomicBoolean(false)
    @Volatile private var bootstrapUnhook: XC_MethodHook.Unhook? = null

    fun isApiSupported(): Boolean = Build.VERSION.SDK_INT in supportedApiLevels

    fun onSystemServerEntry(loader: ClassLoader) {
        Log.i(TAG, "[WearVpnConsent] system_server entry reached")
        if (!isApiSupported()) {
            Log.i(TAG, "[WearVpnConsent] API ${Build.VERSION.SDK_INT} is not in the compatibility table; hook skipped")
            return
        }
        if (!bootstrapInstalled.compareAndSet(false, true)) return

        try {
            val manager = Class.forName(SYSTEM_SERVICE_MANAGER, false, loader)
            val method = manager.getDeclaredMethod("startServiceFromJar", String::class.java, String::class.java)
            bootstrapUnhook = XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(call: MethodHookParam) {
                    if (call.args.getOrNull(0) != VPN_BOOTSTRAP_SERVICE) return
                    try {
                        if (call.hasThrowable()) {
                            Log.w(TAG, "[WearVpnConsent] connectivity initializer failed; redirect hook skipped")
                            return
                        }
                        val context = findContext(call.thisObject)
                        if (context == null) {
                            Log.w(TAG, "[WearVpnConsent] boot configuration context unavailable; hook skipped")
                            return
                        }
                        HookRuntimeStatus.markSystemInjected(context)
                        val setting = try {
                            Settings.Global.getString(
                                context.contentResolver,
                                WearVpnRestore.CONSENT_ENABLED_SETTING,
                            )
                        } catch (error: Throwable) {
                            Log.w(TAG, "[WearVpnConsent] boot configuration unreadable; disabled", error)
                            return
                        }
                        val promptEnabled = setting == null || setting == "1"
                        if (!promptEnabled) {
                            val state = if (setting == "0") "consent prompt disabled" else "invalid consent value; disabled"
                            Log.i(TAG, "[WearVpnConsent] $state; Activity launch hooks were not installed")
                            return
                        }
                        Log.i(TAG, "[WearVpnConsent] early boot configuration enabled; VPN provider remains the runtime master gate")
                        installActivityLaunchHooks(loader)
                    } catch (error: Throwable) {
                        Log.e(TAG, "[WearVpnConsent] bootstrap failed; Activity launch hooks skipped", error)
                    } finally {
                        bootstrapUnhook?.unhook()
                        bootstrapUnhook = null
                    }
                }
            })
            Log.i(TAG, "[WearVpnConsent] waiting for early global configuration at $VPN_BOOTSTRAP_SERVICE")
        } catch (error: Throwable) {
            bootstrapInstalled.set(false)
            Log.e(TAG, "[WearVpnConsent] boot callback installation failed; redirect skipped", error)
        }
    }

    private fun installActivityLaunchHooks(loader: ClassLoader) {
        if (!isApiSupported()) return
        if (!installed.compareAndSet(false, true)) return

        var count = 0
        for ((className, methodName) in listOf(
            AMS_CLASS to "startActivityAsUserWithFeature",
            ATMS_CLASS to "startActivityAsUser",
        )) {
            try {
                count += hookClass(loader, className, methodName)
            } catch (error: Throwable) {
                Log.w(TAG, "[WearVpnConsent] unable to inspect $className.$methodName", error)
            }
        }
        if (count == 0) {
            installed.set(false)
            Log.w(TAG, "[WearVpnConsent] no compatible Activity launch method was found; hook skipped")
        } else {
            Log.i(TAG, "[WearVpnConsent] installed $count Activity launch hook(s), API=${Build.VERSION.SDK_INT}")
        }
    }

    private fun hookClass(loader: ClassLoader, className: String, methodName: String): Int {
        val type = Class.forName(className, false, loader)
        var count = 0
        for (method in type.declaredMethods) {
            if (method.name != methodName || !isSupportedLaunchSignature(method)) continue
            if (!hookedMethods.add(method)) continue
            try {
                check(deoptimize(method)) { "deoptimization failed for ${type.name}.${method.name}" }
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(call: MethodHookParam) {
                        redirectIfEnabled(call, method)
                    }
                })
                count++
                Log.i(TAG, "[WearVpnConsent] hooked ${type.name}.${method.name}${method.parameterTypes.contentToString()}")
            } catch (error: Throwable) {
                hookedMethods.remove(method)
                Log.w(TAG, "[WearVpnConsent] could not hook ${type.name}.${method.name}${method.parameterTypes.contentToString()}", error)
            }
        }
        if (count == 0) {
            Log.w(TAG, "[WearVpnConsent] no $methodName overload matched in $className")
        }
        return count
    }

    private fun isSupportedLaunchSignature(method: Method): Boolean {
        val parameters = method.parameterTypes
        val intentIndex = parameters.indexOf(Intent::class.java)
        if (intentIndex < 1 || parameters[intentIndex - 1] != String::class.java) return false
        val resultToIndex = parameters.indices.firstOrNull { index ->
            index > intentIndex && IBinder::class.java.isAssignableFrom(parameters[index])
        } ?: return false
        val requestCodeIndex = parameters.indices.firstOrNull { index ->
            index > resultToIndex && parameters[index] == Int::class.javaPrimitiveType
        } ?: return false
        return requestCodeIndex > resultToIndex
    }

    private fun redirectIfEnabled(call: XC_MethodHook.MethodHookParam, method: Method) {
        try {
            val context = findContext(call.thisObject)
            if (context == null) {
                if (contextUnavailableLogged.compareAndSet(false, true)) {
                    Log.w(TAG, "[WearVpnConsent] ${method.declaringClass.name} context unavailable; launch unchanged")
                }
                return
            }
            val enabled = try {
                val promptEnabled = Settings.Global.getString(
                    context.contentResolver,
                    WearVpnRestore.CONSENT_ENABLED_SETTING,
                )?.let { it == "1" } ?: true
                val providerEnabled = Settings.Global.getString(
                    context.contentResolver,
                    WearVpnRestore.ENABLED_SETTING,
                ) == "1"
                providerEnabled && promptEnabled
            } catch (error: Throwable) {
                if (runtimeConfigFailureLogged.compareAndSet(false, true)) {
                    Log.w(TAG, "[WearVpnConsent] runtime configuration unreadable; original launch preserved", error)
                }
                false
            }
            if (!enabled) return
            val intentIndex = method.parameterTypes.indexOf(Intent::class.java)
            if (intentIndex <= 0) return
            val original = call.args.getOrNull(intentIndex) as? Intent ?: return
            // AMS includes callingPackage and callingFeatureId before Intent; ATMS's shorter
            // overload includes only callingPackage. Use the actual parameter types to choose.
            val callingPackageIndex = if (
                intentIndex >= 2 &&
                method.parameterTypes[intentIndex - 1] == String::class.java &&
                method.parameterTypes[intentIndex - 2] == String::class.java
            ) intentIndex - 2 else intentIndex - 1
            val callingPackage = call.args.getOrNull(callingPackageIndex) as? String ?: return
            if (callingPackage.isBlank() || callingPackage == ANDROID_PACKAGE) return

            val resultToIndex = method.parameterTypes.indices.firstOrNull { index ->
                index > intentIndex && IBinder::class.java.isAssignableFrom(method.parameterTypes[index])
            } ?: return
            val resultTo = call.args.getOrNull(resultToIndex) as? IBinder ?: return
            val requestCodeIndex = method.parameterTypes.indices.firstOrNull { index ->
                index > resultToIndex && method.parameterTypes[index] == Int::class.javaPrimitiveType
            } ?: return
            val requestCode = call.args.getOrNull(requestCodeIndex) as? Int ?: return
            if (requestCode < 0) return

            val configured = resolveConfiguredComponent(context) ?: return
            if (original.component != configured) return
            if (!isVpnApplication(context, callingPackage)) return

            val target = ComponentName(GWH_PACKAGE, WearVpnConsentContract.ACTIVITY_CLASS)
            if (!isUsableActivity(context, target)) {
                Log.w(TAG, "[WearVpnConsent] consent Activity is unavailable; launch unchanged")
                return
            }

            val redirected = Intent(original)
                .setComponent(target)
                .putExtra(WearVpnConsentContract.EXTRA_VPN_PACKAGE, callingPackage)
            call.args[intentIndex] = redirected
            Log.i(
                TAG,
                "[WearVpnConsent] redirected ${configured.flattenToShortString()} for $callingPackage " +
                    "to ${target.flattenToShortString()} result=$resultToIndex/$requestCode",
            )
        } catch (error: Throwable) {
            // A VPN launch must retain the platform's original behavior if any reflective detail
            // changes. Never turn an optional consent feature into a system_server crash.
            Log.e(TAG, "[WearVpnConsent] redirect failed; launch unchanged", error)
        }
    }

    private fun resolveConfiguredComponent(context: Context): ComponentName? {
        val resourceId = context.resources.getIdentifier(CONFIG_CONFIRM_COMPONENT, "string", ANDROID_PACKAGE)
        if (resourceId == 0) {
            Log.w(TAG, "[WearVpnConsent] $CONFIG_CONFIRM_COMPONENT is missing; launch unchanged")
            return null
        }
        val value = context.resources.getString(resourceId).trim()
        val component = ComponentName.unflattenFromString(value)
        if (component == null) {
            Log.w(TAG, "[WearVpnConsent] invalid configured component=$value; launch unchanged")
        }
        return component
    }

    private fun isUsableActivity(context: Context, component: ComponentName): Boolean = try {
        val info = context.packageManager.getActivityInfo(component, 0)
        info.enabled && info.applicationInfo.enabled && info.exported
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun findContext(receiver: Any?): Context? {
        if (receiver == null) return null
        for (type in generateSequence(receiver.javaClass) { it.superclass }) {
            try {
                val field = type.getDeclaredField("mContext").apply { isAccessible = true }
                return field.get(receiver) as? Context
            } catch (_: NoSuchFieldException) {
                // Continue with the superclass.
            } catch (_: Throwable) {
                return null
            }
        }
        return null
    }

    private fun deoptimize(member: Member): Boolean = try {
            XposedBridge::class.java.getMethod("deoptimizeMethod", Member::class.java).invoke(null, member)
            Log.i(TAG, "[WearVpnConsent] deoptimization requested for ${member.name}")
            true
        } catch (error: Throwable) {
            Log.w(TAG, "[WearVpnConsent] deoptimization failed for ${member.name}; method hook skipped", error)
            false
        }
}
