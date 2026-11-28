# Galaxy Watch Helper

The Wear OS app bundles the NeoZygisk v2.4-289 and Vector v2.2-3080 KernelSU
release ZIPs in `app/src/main/assets`. The GWH APK itself is also the Xposed
module: it contains the **Side Key Hook** and optional **App Freeze Fix**
diagnostics, and declares `android` as its Vector scope.

The installer uses `/data/adb/ksud module install` to install NeoZygisk and
Vector in sequence, then installs Vector's manager APK from its ZIP.

After installing GWH, open Vector and enable **GalaxyWatchHelper** for
**Android System**, then reboot. The app can set `gw_wallet_redirect_target`
from a package name and switch redirection with `gw_wallet_hook_enabled`.
If the standalone **GW Wallet Hook** module was installed before, disable it
in Vector to avoid loading the same hook twice.

The App Freeze Fix setting enables handling of `LAUNCH_TIMEOUT_MSG` only while
the launching-activity wake lock is held. A separate logging switch writes one
file per event under `/data/system/app_freecess_timeout/`, named with the event
time; the log viewer lists and opens those files through root access.

The Settings page also includes **Enable GWH in Vector**. It runs these commands
through root, displays the scope readback, then asks the user to reboot:

```sh
/data/adb/modules/zygisk_vector/cli modules enable com.gohj99.galaxywatchhelper
/data/adb/modules/zygisk_vector/cli scope set com.gohj99.galaxywatchhelper system/0
/data/adb/modules/zygisk_vector/cli scope ls com.gohj99.galaxywatchhelper
```

`system/0` covers the side-key, Freecess, VPN backend, and VPN consent hooks in
system_server. No VPN application scope is required.
VPN applications use the restored Binder service and need their own VPN
authorization; they do not need to be added to the Xposed scope.

The combined hook implementation lives at
`app/src/main/java/com/gohj99/galaxywatchhelper/utils/SystemServerHook.kt` and
is loaded by Vector through `app/src/main/assets/xposed_init`.

The settings include an experimental **Restore Wear OS VPN management** option,
off by default. On the audited SM-L315F / L315FXXS3BZF4 firmware, GWH can restore
the app VpnService backend inside system_server using the retained platform JNI
and Connectivity APIs. GWH also reuses its application picker to grant or revoke
ACTIVATE_VPN for one selected VPN app through root, with readback verification.
Reboot is required for service restoration; platform/legacy profiles and lockdown
are unsupported. Device tunnel/network validation remains pending.
See [the implementation, evidence and recovery notes](docs/wear-vpn-restoration.md).
