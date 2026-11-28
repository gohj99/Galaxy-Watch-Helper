package com.gohj99.galaxywatchhelper.utils;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Command construction and conservative readback parsing for one selected package. */
final class WearVpnAppOpsAuthorization {
    private static final Pattern PACKAGE = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+$");
    private static final Pattern MODE = Pattern.compile(
        "(?:^|\\n)\\s*(?:Uid mode:\\s*)?ACTIVATE_VPN:\\s*(allow|ignore|deny|default)\\b");

    static boolean isPackageName(String packageName) {
        return packageName != null && PACKAGE.matcher(packageName).matches();
    }

    static String command(String action, String packageName, int userId, String mode) {
        if (!isPackageName(packageName) || userId < 0
            || !("get".equals(action) && mode == null
                || "set".equals(action) && ("allow".equals(mode) || "ignore".equals(mode)))) {
            throw new IllegalArgumentException("Invalid VPN authorization command");
        }
        // The package whitelist excludes shell metacharacters and quotes.
        return "cmd appops " + action + " --user " + userId + " '" + packageName + "' ACTIVATE_VPN"
            + (mode == null ? "" : " " + mode);
    }

    static Boolean readbackAllowed(String output) {
        Set<String> modes = new HashSet<>();
        Matcher matcher = MODE.matcher(output);
        while (matcher.find()) modes.add(matcher.group(1));
        if (modes.size() != 1) return null;
        if (modes.contains("allow")) return true;
        if (modes.contains("ignore") || modes.contains("deny")) return false;
        // Missing, default, or conflicting UID/package modes are not a confirmed grant.
        return null;
    }
}
