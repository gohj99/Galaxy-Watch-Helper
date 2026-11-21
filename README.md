# Galaxy Watch Helper

The Wear OS app bundles the NeoZygisk v2.4-289 and Vector v2.2-3080 KernelSU
release ZIPs in `app/src/main/assets`. The GWH APK itself is also the Xposed
module: it contains the **Side Key Hook** and declares `android` as its Vector
scope.

The installer uses `/data/adb/ksud module install` to install NeoZygisk and
Vector in sequence, then installs Vector's manager APK from its ZIP.

After installing GWH, open Vector and enable **GalaxyWatchHelper** for
**Android System**, then reboot. The app can set `gw_wallet_redirect_target`
from a package name and switch redirection with `gw_wallet_hook_enabled`.
If the standalone **GW Wallet Hook** module was installed before, disable it
in Vector to avoid loading the same hook twice.

Run `build.ps1` to build the Wear OS APK. The hook implementation lives at
`app/src/main/java/com/gohj99/galaxywatchhelper/utils/SideKeyHook.kt` and is
loaded by Vector through `app/src/main/assets/xposed_init`.
