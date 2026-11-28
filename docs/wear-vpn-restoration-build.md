# VPN 恢复后端与确认页构建记录（2026-11-28）

对应 [当前实现](wear-vpn-restoration.md)。此次构建已包含恢复后端、GWH内单应用授权和 API36
系统 VPN 确认页；
不是先前不可启用的受阻版本。**尚未安装或在手表上执行新后端。**

## 实际执行结果

仓库根目录：`H:/Android/AndroidStudioProjects/GalaxyWatchHelper`。

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain
```

最终退出码0，Debug Kotlin、Java、DEX、APK 组装及单元测试通过。
单元测试共15项，0失败/0错误/0跳过：

- `VpnUidPolicyTest`：8项，用户边界、allowed/denied列表、SDK sandbox、重复UID、范围排序及溢出。
- `WearVpnAuthorizationTest`：3项，包名Shell注入拒绝、单包/用户授权命令及保守读回。
- `VpnReflectionTest`：4项，已审计 VPN 平台反射路径。

日志：`F:/gw7/root/vpn/live-20261127/vpn-restoration-build.log`。
XML结果：`app/build/test-results/testDebugUnitTest/`。

```powershell
.\gradlew.bat :app:lintDebug --console=plain
```

退出码1：**1 error、26 warnings**。唯一error仍是原Manifest第12行
`QUERY_ALL_PACKAGES`的`QueryAllPackagesPermission`，没有修改权限或设置全局lint屏蔽。
新增反射、进程生命周期Context、TargetApi等警告仍保留，不能称lint通过。
实际API36 system_server中访问已审计的NetworkAgentHandler.this$0，为该方法单独说明并抑制
BlockedPrivateApi（UI进程不会执行）；不影响运行期的失败保护。

报告：`app/build/reports/lint-results-debug.txt`。
日志：`F:/gw7/root/vpn/live-20261127/vpn-restoration-lint.log`。

## 产物与签名

| APK | 字节数 | SHA-256 |
| --- | ---: | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | 44130680 | `DE5E3622A80F0595907230788F556A6FD6EF89CADC26A1CD824863237DFCFFC5` |

Debug是本地调试签名，不能假定能覆盖不同签名的已安装GWH。未签名Release不能直接安装。
未改变CI密钥配置、版本号或现有`app/release/`目录。

```powershell
& 'D:\Android\Sdk\build-tools\36.0.0\apksigner.bat' verify --verbose --print-certs 'app\build\outputs\apk\debug\app-debug.apk'
```

退出码0，v2签名`Verifies`。证书SHA-256：
`548b04a35f3a4ca9d1ea83cc5ffa76b1ef87371fc1239cbfca27f6177a9c3be1`。

## 实提固件与APK核对

本地dexlib2验证程序：

```powershell
& 'C:\Program Files\Android\Android Studio\jbr\bin\java.exe' -Xmx2g -cp 'F:\gw7\root\research-tools\jadx-1.5.6\lib\jadx-1.5.6-all.jar' 'F:\gw7\root\vpn\analysis-L315FXXS3BZF4\tools\VerifyVpnRestoreAbi.java' 'F:\gw7\root\vpn\live-20261127\jars-to-audit.txt'
& 'C:\Program Files\Android\Android Studio\jbr\bin\java.exe' -Xmx2g -cp 'F:\gw7\root\research-tools\jadx-1.5.6\lib\jadx-1.5.6-all.jar' 'F:\gw7\root\vpn\analysis-L315FXXS3BZF4\tools\AppRestorationAudit.java' 'app\build\outputs\apk\debug\app-debug.apk' 'app\build\outputs\apk\release\app-release-unsigned.apk'
python 'F:\gw7\root\vpn\analysis-L315FXXS3BZF4\tools\verify_apks.py'
```

此前后端 ABI 审计结果保留有效；本轮另确认：

- 35项实际三星平台方法签名匹配；29项IVpnManager事务编号全部匹配。
- 两个APK包含自有恢复后端，未打包IVpnManager、NetworkAgent、VpnConfig、com.android.server实现或Xposed API实现。
- 原有`assets/xposed_init`入口保持为SystemServerHook；无services.jar/framework.jar/ART/OAT/VDEX或固件压缩包。
- APK manifest 包含导出的 `com.gohj99.galaxywatchhelper.ui.vpn.VpnConsentActivity`，并设置
  `excludeFromRecents`/`noHistory`。
- AOSP派生逻辑的NOTICE和Apache2许可证已随APK打包。
- `git diff --check`通过，Git index的会话前后快照SHA-256相同，未改变原暂存状态。

补充保留了命令输出/超时测试和授权命令本地探针；它们没有启动su或访问手表。

## 仍需真机验证

最初设备只读采集已确认固件、服务缺失、内核TUN、JNI二进制和授权占位组件。
此后尚未执行安装、修改设备配置、授权应用或重启。

| 层级 | 当前状态 |
| --- | --- |
| APK与本地测试 | 通过；完整lint仍有原有错误 |
| API36 确认页重定向、显示与 AppOps 读回 | 未实测 |
| 新后端system_server注入、精确启动Hook命中 | 未实测 |
| 自有Binder注册且ready=true | 未实测 |
| JNI创建TUN、PFD传递、NetworkAgent及授权按钮 | 未实测 |
| VPN应用真实联网、重连、撤销与清理 | 未实测 |

后续必须在用户允许安装/配置且手表重新连接后逐层验证。若签名不符，不可擅自卸载或清数据。
