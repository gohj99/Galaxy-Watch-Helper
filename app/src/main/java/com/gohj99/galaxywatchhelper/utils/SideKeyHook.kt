package com.gohj99.galaxywatchhelper.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Member

/** Redirects the Galaxy Watch side-key wallet shortcut to a configured launcher activity. */
class SideKeyHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(load: XC_LoadPackage.LoadPackageParam) {
        if (Process.myUid() != 1000 || load.packageName != "android" ||
            (load.processName != "android" && load.processName != "system_server")
        ) {
            return
        }

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
            Log.i(TAG, "Installed side-key hook in system_server, pid=${Process.myPid()}")
        } catch (error: ReflectiveOperationException) {
            Log.e(TAG, "Could not install side-key hook", error)
        } catch (error: RuntimeException) {
            Log.e(TAG, "Could not install side-key hook", error)
        } catch (error: LinkageError) {
            Log.e(TAG, "Could not install side-key hook", error)
        }
    }

    private fun fromLongPress(): Boolean = Thread.currentThread().stackTrace.any { frame ->
        frame.className == BACK_KEY_RULE && frame.methodName == "onLongPress"
    }

    private fun readTarget(context: Context?): ComponentName? {
        if (context == null || Settings.Global.getInt(
                context.contentResolver,
                ENABLED_SETTING,
                1,
            ) == 0
        ) {
            return null
        }

        val value = Settings.Global.getString(context.contentResolver, TARGET_SETTING) ?: return null
        val target = ComponentName.unflattenFromString(value.trim()) ?: return null
        if (target.packageName == WALLET_PACKAGE || target.packageName == "android") {
            return null
        }

        return try {
            val info: ActivityInfo = context.packageManager.getActivityInfo(target, 0)
            target.takeIf {
                info.exported && info.enabled && info.applicationInfo.enabled && info.permission == null
            }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    private companion object {
        const val TAG = "GWSideKeyHook"
        const val TARGET_SETTING = "gw_wallet_redirect_target"
        const val ENABLED_SETTING = "gw_wallet_hook_enabled"
        const val POLICY_EXT = "com.android.server.policy.PhoneWindowManagerExt"
        const val BACK_KEY_RULE = $$"com.android.server.policy.PhoneWindowManager$BackKeyRule"
        const val WALLET_PACKAGE = "com.samsung.android.samsungpay.gear"
        val WALLET = ComponentName(WALLET_PACKAGE, "$WALLET_PACKAGE.app.MainActivityWallet")
    }
}
