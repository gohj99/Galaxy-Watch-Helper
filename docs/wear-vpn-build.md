# Wear VPN 本地构建与验证记录

> 本文件为最初受阻版本的历史构建记录，APK哈希不适用于后续重建版本。
> 当前实现见 [恢复说明](wear-vpn-restoration.md)。

2026-11-26（Asia/Singapore）。这是受阻状态及保护入口的构建记录，**不是 VPN 修复成功记录**。
没有安装到手表、执行设备命令或重启设备。

## 实际命令和结果

仓库：`H:/Android/AndroidStudioProjects/GalaxyWatchHelper`。

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleRelease :app:lintDebug --console=plain
```

初次联合命令中 APK 组装完成，但 lint 失败，最终命令状态为失败，不能称全部检查通过。
随后针对最终源码分别运行：

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleRelease --console=plain
```

**退出码 0，BUILD SUCCESSFUL in 42s**；85 actionable tasks，14 executed、71 up-to-date。
包括 Debug/Release Kotlin、Java 编译、DEX、package、assemble 及 release lintVital。
日志：`F:/gw7/root/vpn/analysis-L315FXXS3BZF4/evidence/gradle-assemble.log`。

```powershell
.\gradlew.bat :app:lintDebug --console=plain
```

**退出码 1，1 error、14 warnings**。唯一 error 为本次开始时已有的
`AndroidManifest.xml:12` 的 `QueryAllPackagesPermission`；未修改 Manifest 或增加 lint 抑制。
警告涉及现有反射、依赖版本和未使用资源；没有指向新 WearVpn 文件的问题。
报告：`app/build/reports/lint-results-debug.txt`；
日志：`F:/gw7/root/vpn/analysis-L315FXXS3BZF4/evidence/gradle-lint-final.log`。

## APK

| 产物 | 文件大小 | SHA-256 |
| --- | --- | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | 43,734,853 字节 | `50841cb73af7d8141d8d7889fc84a8a54601d3ff5a20eef669c66eb8a1522448` |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | 34,985,768 字节 | `b7c7bb083f22ae4ef27351f8c3039b045366e48a3b28589df7bc89389bdca76f` |

Debug 使用本地 Android Debug 签名；Release 因未配置 CI 签名密钥为 unsigned，不能直接安装。
没有复制成旧 `app/release/` 的文件或覆盖旧手动发布包。
不要假定调试签名能覆盖已安装的不同签名 APK。

```powershell
& 'D:\Android\Sdk\build-tools\36.0.0\apksigner.bat' verify --verbose --print-certs 'app\build\outputs\apk\debug\app-debug.apk'
```

退出码 0，`Verifies`，APK v2 签名有效，证书 SHA-256：
`548b04a35f3a4ca9d1ea83cc5ffa76b1ef87371fc1239cbfca27f6177a9c3be1`。

## 本地行为和封装检查

对实际应用代码 `WearVpnServiceCheck.java` 使用本地 JDK 编译并运行
`F:/gw7/root/vpn/analysis-L315FXXS3BZF4/tools/WearVpnCheckProbe.java`。
测试仅启动本地 Java 子进程，从未调用 su、ADB 或手表：

```powershell
& 'C:\Program Files\Android\Android Studio\jbr\bin\javac.exe' -d 'F:\gw7\root\vpn\analysis-L315FXXS3BZF4\tools\probe-classes' 'app\src\main\java\com\gohj99\galaxywatchhelper\utils\WearVpnServiceCheck.java' 'F:\gw7\root\vpn\analysis-L315FXXS3BZF4\tools\WearVpnCheckProbe.java'
& 'C:\Program Files\Android\Android Studio\jbr\bin\java.exe' -cp 'F:\gw7\root\vpn\analysis-L315FXXS3BZF4\tools\probe-classes' com.gohj99.galaxywatchhelper.utils.WearVpnCheckProbe
python 'F:\gw7\root\vpn\analysis-L315FXXS3BZF4\tools\verify_apks.py'
```

结果：全部通过。保留非零退出码、完整合并 stdout/stderr、超时前已接收输出；
超时返回有界且明确标记，无法启动命令时保留异常信息。
Android/KernelSU 的实际 su 行为、授权、管道行为仍未实测。

两个 APK 均只有原有 `assets/xposed_init` 入口：
`com.gohj99.galaxywatchhelper.utils.SystemServerHook`。
assets 中仍为项目原有 NeoZygisk、Vector ZIP；没有 services.jar、framework.jar、
ODEX/VDEX/ART 或固件压缩包。新增安全门不调用 hook、deoptimize 或服务注册。
`git diff --check` 通过，Git index 的会话前后快照 SHA-256 相同，未更改暂存状态。
原有侧键/Freecess 函数保留，仅在 system_server 入口增加独立 VPN 安全门调用。

## 尚未验证

- 手表端 system_server 注入、保护日志和设置页实际渲染：未实测。
- 未解锁时配置读取：本次未实现；所有固件在读配置前拒绝恢复。
- 恢复 Hook 命中、ART 去优化：未安装，也没有可验证的服务端调用者。
- 完整重启后 `vpn_management` found：未实测，未达到。
- VPN 正常授权、隧道建立及实际联网：未实测，未达到。

原因和进一步需要的固件材料见 `wear-vpn-analysis.md`。
