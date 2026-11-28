package com.gohj99.galaxywatchhelper.utils.vpn;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class VpnUidPolicyTest {
    private boolean contains(List<int[]> ranges, int uid) {
        return ranges.stream().anyMatch(r -> r[0] <= uid && uid <= r[1]);
    }

    @Test public void fullUserNeverCapturesAnotherUser() {
        var ranges = VpnUidPolicy.ranges(10, null, null);
        assertFalse(contains(ranges, 999999));
        assertTrue(contains(ranges, 1000000));
        assertTrue(contains(ranges, 1099999));
        assertFalse(contains(ranges, 1100000));
    }

    @Test public void excludedAppsAndTheirSdkSandboxesAreExcluded() {
        var ranges = VpnUidPolicy.ranges(0, null, Arrays.asList(10001, 10002, 99999, 0));
        for (int uid : new int[] {0, 10001, 10002, 20001, 20002, 99999}) assertFalse(contains(ranges, uid));
        for (int uid : new int[] {1, 10000, 10003, 20000, 20003, 99998}) assertTrue(contains(ranges, uid));
    }

    @Test public void allowedListDoesNotExposeOtherAppsOrAnotherUser() {
        var ranges = VpnUidPolicy.ranges(10, Arrays.asList(1010017, 1010017), null);
        for (int uid = 1000000; uid <= 1099999; uid++) {
            assertEquals(uid == 1010017 || uid == 1020017, contains(ranges, uid));
        }
    }

    @Test public void emptyAllowedListRoutesNothing() {
        assertTrue(VpnUidPolicy.ranges(0, Collections.emptyList(), null).isEmpty());
    }

    @Test public void exclusionsStaySortedAndDoNotOverlap() {
        var ranges = VpnUidPolicy.ranges(0, null, Arrays.asList(10000, 10000, 10001, 19999));
        int end = -1;
        for (int[] range : ranges) {
            assertTrue(range[0] > end);
            assertTrue(range[0] <= range[1]);
            end = range[1];
        }
    }

    @Test(expected = IllegalArgumentException.class) public void crossUserAppUidIsRejected() {
        VpnUidPolicy.ranges(0, Arrays.asList(1010000), null);
    }

    @Test(expected = IllegalArgumentException.class) public void contradictoryPoliciesAreRejected() {
        VpnUidPolicy.ranges(0, Collections.emptyList(), Collections.emptyList());
    }

    @Test(expected = IllegalArgumentException.class) public void userOverflowIsRejected() {
        VpnUidPolicy.ranges(21474, null, null);
    }
}
