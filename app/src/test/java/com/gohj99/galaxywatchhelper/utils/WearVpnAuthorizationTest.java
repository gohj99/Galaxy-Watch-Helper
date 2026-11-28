package com.gohj99.galaxywatchhelper.utils;

import org.junit.Test;
import static org.junit.Assert.*;

public class WearVpnAuthorizationTest {
    @Test public void neverBuildsCommandsForUnvalidatedPackages() {
        for (String pkg : new String[] {"a;reboot", "a.b'", "a.b\nreboot", "$(id)", "a.b c", ""}) {
            assertFalse(WearVpnAppOpsAuthorization.isPackageName(pkg));
            assertThrows(IllegalArgumentException.class, () -> WearVpnAppOpsAuthorization.command("set", pkg, 0, "allow"));
        }
    }

    @Test public void grantIsLimitedToOneUserAndOnePackage() {
        assertEquals("cmd appops set --user 10 'com.example.vpn' ACTIVATE_VPN allow",
                WearVpnAppOpsAuthorization.command("set", "com.example.vpn", 10, "allow"));
        assertThrows(IllegalArgumentException.class, () -> WearVpnAppOpsAuthorization.command("set", "com.example.vpn", -1, "allow"));
        assertThrows(IllegalArgumentException.class, () -> WearVpnAppOpsAuthorization.command("set", "com.example.vpn", 0, "allow;reboot"));
    }

    @Test public void missingDefaultOrConflictingReadbackIsNeverSuccess() {
        assertNull(WearVpnAppOpsAuthorization.readbackAllowed("No operations."));
        assertNull(WearVpnAppOpsAuthorization.readbackAllowed("ACTIVATE_VPN: default"));
        assertNull(WearVpnAppOpsAuthorization.readbackAllowed("Uid mode: ACTIVATE_VPN: ignore\nACTIVATE_VPN: allow"));
        assertEquals(Boolean.TRUE, WearVpnAppOpsAuthorization.readbackAllowed("ACTIVATE_VPN: allow; time=+2s ago"));
        assertEquals(Boolean.FALSE, WearVpnAppOpsAuthorization.readbackAllowed("ACTIVATE_VPN: ignore"));
    }
}
