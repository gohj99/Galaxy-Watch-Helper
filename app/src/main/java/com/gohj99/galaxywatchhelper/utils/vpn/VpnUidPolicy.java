package com.gohj99.galaxywatchhelper.utils.vpn;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/** UID routing arithmetic; no Android dependencies. Each user owns exactly 100000 UIDs. */
final class VpnUidPolicy {
    static List<int[]> ranges(int user, Collection<Integer> allowed, Collection<Integer> denied) {
        if (user < 0 || user > 21473) throw new IllegalArgumentException("Invalid user");
        if (allowed != null && denied != null) throw new IllegalArgumentException("Conflicting app lists");
        int first = user * 100000, last = first + 99999;
        TreeSet<Integer> uids = new TreeSet<>();
        Collection<Integer> source = allowed != null ? allowed : denied;
        if (source != null) for (int uid : source) {
            if (uid < first || uid > last) throw new IllegalArgumentException("UID belongs to another user");
            uids.add(uid);
            int appId = uid % 100000;
            if (appId >= 10000 && appId <= 19999) uids.add(uid + 10000); // SDK sandbox UID
        }
        List<int[]> result = new ArrayList<>();
        if (allowed != null) {
            for (int uid : uids) result.add(new int[] {uid, uid});
        } else {
            int next = first;
            for (int uid : uids) {
                if (uid > next) result.add(new int[] {next, uid - 1});
                next = uid + 1;
            }
            if (next <= last) result.add(new int[] {next, last});
        }
        return result;
    }
}
