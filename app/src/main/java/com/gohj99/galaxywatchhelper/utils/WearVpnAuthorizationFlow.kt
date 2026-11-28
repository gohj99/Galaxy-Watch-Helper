package com.gohj99.galaxywatchhelper.utils

import android.content.Context
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Arrays

/** Performs one user-approved VPN AppOps change and verifies the result. */
internal object WearVpnAuthorizationFlow {
    data class Result(
        val success: Boolean,
        val allowed: Boolean?,
        val output: String,
    )

    suspend fun grant(context: Context, packageName: String): Result = withContext(Dispatchers.IO) {
        if (!isVpnApplication(context, packageName)) {
            return@withContext Result(false, null, "The requested package is not a VPN service")
        }
        val userId = Process.myUid() / 100_000
        val command = WearVpnAppOpsAuthorization.command("set", packageName, userId, "allow")
        val write = WearVpnServiceCheck.capture(Arrays.asList("su", "-c", command), 15_000)
        val read = WearVpnServiceCheck.capture(
            Arrays.asList("su", "-c", WearVpnAppOpsAuthorization.command("get", packageName, userId, null)),
            15_000,
        )
        val allowed = if (read.exitCode == 0) WearVpnAppOpsAuthorization.readbackAllowed(read.output) else null
        Result(
            success = write.exitCode == 0 && read.exitCode == 0 && allowed == true,
            allowed = allowed,
            output = "write exit=${write.exitCode}\n${write.output}\nread exit=${read.exitCode}\n${read.output}",
        )
    }
}
