package com.gohj99.galaxywatchhelper.utils

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService

/** Uses the same package visibility as the existing installed-app picker. */
internal fun vpnApplicationPackages(context: Context): Set<String> =
    context.packageManager.queryIntentServices(
        Intent(VpnService.SERVICE_INTERFACE),
        PackageManager.MATCH_ALL,
    ).asSequence()
        .mapNotNull { it.serviceInfo }
        .filter {
            it.enabled && it.applicationInfo.enabled &&
                it.permission == Manifest.permission.BIND_VPN_SERVICE
        }
        .map { it.packageName }
        .toSet()

internal fun isVpnApplication(context: Context, packageName: String): Boolean =
    WearVpnAppOpsAuthorization.isPackageName(packageName) && context.packageManager.queryIntentServices(
        Intent(VpnService.SERVICE_INTERFACE).setPackage(packageName),
        PackageManager.MATCH_ALL,
    ).any { resolved ->
        val service = resolved.serviceInfo
        service != null && service.packageName == packageName && service.enabled &&
            service.applicationInfo.enabled && service.permission == Manifest.permission.BIND_VPN_SERVICE
    }
