# Wear OS VPN：L315FXXS3BZF4 本地分析与交付边界

> 历史阶段记录：此文件描述最初只有固件附件时的分析和受阻版本。
> 2026-11-27 已完成设备文件补采；用户随后授权GWH重建普通VpnService服务端及管理单应用授权。
> 当前代码与限制请看 [当前实现](wear-vpn-restoration.md)，下方字节码证据仍有效。

分析日期：2026-11-26。目标：SM-L315F / fresh7ul，Android 16 / SDK 36，
BP2A.250325.020.L315FXXS3BZF4，增量 L315FXXS3BZF4。

**结论：服务端属于 C，无法通过标志 Hook 或 ART 去优化恢复。**
`SystemServer` 的 VPN 启动分支和 ready 调用已不存在；提供的两个 JAR 的全部
DEX 中也没有 `com.android.server.VpnManagerService` 主类。
当前没有经过验证的局部 Xposed 恢复路径，因此本次交付的是集成在原模块里的
**受阻状态入口、默认关闭且禁止启用的开关、固件安全门和服务检查**，不是已完成的 VPN 修复。

## 原件、范围与工具

原件：`F:/gw7/root/vpn/wear-vpn-L315FXXS3BZF4-20261125-142331.tar.gz`。
仅解压到独立目录 `F:/gw7/root/vpn/analysis-L315FXXS3BZF4/firmware/`；
完整 Smali、类表、工具脚本、构建日志保存在同级 `evidence/`、`tools/`，不进入 APK。
未上传框架到分析平台，未向 Git 添加框架二进制，未执行 Git 提交、推送或合并。
附件里的内容仅作为待分析数据，不作为操作指令。

| 文件 | SHA-256 |
| --- | --- |
| 原始 tar.gz | `2f763b899e9c612465e14dd3dac1a4bfa2e4470fa2ff8ae0441a9874f28b1812` |
| services.jar | `30d5efae122d3bbbf6779fa522b2fdd3c7539d7e7131d805501e2dc165353ef9` |
| framework.jar | `4a193bf78b75a29d19fda1b6038759f0e4ec92f08e5dd923686199037d8dd5d5` |
| oat/arm/services.art | `7f8fc52030551526bcda8f142e8685dbf7090e6376b46c13a9eb4723517d4ab9` |
| oat/arm/services.odex | `7272040e8df983260709ce064ac85d703acdb7c9404152929d109bd1fbb903b9` |
| oat/arm/services.vdex | `d117073640f1d68a28c872889b451f0e6ec0370f6c8c035776c7a921b419bc30` |

services.jar 自带 2 个 DEX，class_defs 分别为 9262、2740；framework.jar 自带
6 个 DEX，分别为 6540、7077、8660、6644、7287、1161。
无需从 OAT/VDEX 重建 DEX；本次没有把这些配套文件的存在误当成主类仍存在。
也没有分析或声称确认 OAT 中某个调用已被 ART 内联。

使用已有本地 JADX 1.5.6 包里的 Google baksmali/dexlib2 **3.0.9**，以 API 36
直接反汇编原始 DEX，并用 `tools/DexVpnAudit.java` 对全部 8 个 DEX 的类定义、
父类和指令引用作穷尽扫描。该脚本调用成熟 dexlib2，不自行解析 DEX 格式。
`evidence/dex-vpn-audit.txt` 同时保存每个 DEX 的 SHA-256、计数、方法签名和代码单元偏移。
REA 的 Android 接口只接受独立 APK，拒绝了 JAR 输入；因此改用本地工具。
JADX 的两个 Java 反编译任务没有产出目标文件，已终止；结论仅依据成功导出的 Smali 和 dexlib2 扫描。

复现示例（PowerShell，以下均仅操作本地文件）：

```powershell
Get-FileHash -Algorithm SHA256 -LiteralPath 'F:\gw7\root\vpn\wear-vpn-L315FXXS3BZF4-20261125-142331.tar.gz'
tar -tzf 'F:\gw7\root\vpn\wear-vpn-L315FXXS3BZF4-20261125-142331.tar.gz'
& 'C:\Program Files\Android\Android Studio\jbr\bin\java.exe' -cp 'F:\gw7\root\research-tools\jadx-1.5.6\lib\jadx-1.5.6-all.jar' com.android.tools.smali.baksmali.Main disassemble -a 36 --classes 'Lcom/android/server/SystemServer;' -o 'F:\gw7\root\vpn\analysis-L315FXXS3BZF4\evidence\services-smali' 'F:\gw7\root\vpn\analysis-L315FXXS3BZF4\firmware\services.jar/classes.dex'
& 'C:\Program Files\Android\Android Studio\jbr\bin\java.exe' -Xmx2g -cp 'F:\gw7\root\research-tools\jadx-1.5.6\lib\jadx-1.5.6-all.jar' 'F:\gw7\root\vpn\analysis-L315FXXS3BZF4\tools\DexVpnAudit.java' 'F:\gw7\root\vpn\analysis-L315FXXS3BZF4\firmware\services.jar' 'F:\gw7\root\vpn\analysis-L315FXXS3BZF4\firmware\framework.jar'
```

## 三星实际字节码证据

1. `services.jar/classes.dex` 的
   `SystemServer.startOtherServices(TimingsTraceAndSlog):void`：
   完成 ConnectivityServiceInitializer 和 SecurityStateManagerService 后，直接执行以下代码。
   `Not starting VpnManagerService` 的 const-string 位于方法代码单元偏移 **0x9e3**。
   对 SystemServer 全方法及 services 全 DEX 的扫描均未发现 VPN 标志调用、
   主服务构造、`vpn_management` 注册或 VPN ready 调用。

```smali
:goto_9de
invoke-virtual {v2}, Landroid/util/TimingsTraceLog;->traceEnd()V
const-string v0, "SystemServer"
const-string v7, "Not starting VpnManagerService"
invoke-static {v0, v7}, Landroid/util/Slog;->i(Ljava/lang/String;Ljava/lang/String;)I
invoke-static {}, Lcom/android/modules/utils/build/SdkLevel;->isAtLeastB()Z
```

2. 同一 DEX 的 SystemServer 合成 ready 方法
   `$r8$lambda$_Ik9fEJxxT_3L9POeIJQOWRQjbw(...)V`：
   **0x157** 的 `MakeVpnManagerServiceReady` 只留下 traceBegin/traceEnd，
   中间没有非空判断或 `VpnManagerService.systemReady()`。

```smali
const-string v0, "MakeVpnManagerServiceReady"
invoke-virtual {v2, v0}, Lcom/android/server/utils/TimingsTraceAndSlog;->traceBegin(Ljava/lang/String;)V
invoke-virtual {v2}, Landroid/util/TimingsTraceLog;->traceEnd()V
const-string v0, "MakeNetworkPolicyServiceReady"
```

3. framework.jar/classes3.dex 的 `android.server.Flags` 访问器确实保留。
   `<clinit>` 创建 `FeatureFlagsImpl`；访问器通过接口委托，而实现直接返回 true。

```smali
# Flags.allowRemovingVpnService()Z
sget-object v0, Landroid/server/Flags;->FEATURE_FLAGS:Landroid/server/FeatureFlags;
invoke-interface {v0}, Landroid/server/FeatureFlags;->allowRemovingVpnService()Z
move-result v0
return v0

# FeatureFlagsImpl.allowRemovingVpnService()Z
const/4 v0, 0x1
return v0
```

`CustomFeatureFlags.allowRemovingVpnService()` 也保留，调用
`getValue("android.server.allow_removing_vpn_service", predicate)`，但不是上述默认实现。
`wear_frameworks` 命名空间及 aflags 输出来自用户设备实测；不把它当作 Hook 后运行值的证据。

4. 有一个真实调用者在 **framework.jar/classes.dex**：
   `android.app.SystemServiceRegistry$19.createService(ContextImpl):VpnManager`，
   **0x19** 调用 `Flags.allowRemovingVpnService()`。
   它先取 `vpn_management` binder，binder 为 null 且为 watch 且标志为 true 时返回 null；
   否则用现有 binder 创建客户端 VpnManager。这里没有服务端构造或 addService。
   把这个调用改为 false 只会改变客户端获取对象的行为，不能创建缺失的 Binder 服务。

5. 全部 8 个 DEX 的 class_defs：没有 `Lcom/android/server/VpnManagerService;`，
   没有其 Lifecycle，没有任何以 `Landroid/net/IVpnManager$Stub;` 为直接父类的服务实现。
   仅留 `VpnManagerService$Dependencies`，它只有调用 Object 构造器的空构造。
   `com.android.server.connectivity.Vpn` 及若干内部类仍在，不能替代缺失的管理服务。

证据文件：`evidence/services-smali/com/android/server/SystemServer.smali`、
`evidence/framework-3-smali/android/server/{Flags,FeatureFlagsImpl,CustomFeatureFlags}.smali`、
`evidence/framework-1-smali/android/app/SystemServiceRegistry$19.smali`、
`evidence/*-classes.txt`、`evidence/dex-vpn-audit.txt`。

分类：**服务端 C 已确认；客户端保留调用已确认；B（ART 是否内联客户端调用）未分析，
也不会改变服务端缺失这一结论。** 不能从最终 DEX 单独判断删码是哪个具体编译步骤完成的。
没有把 AOSP 示例当成三星实码。去优化只能使既有 DEX 重新执行，不能重建不存在的指令或类。

## 仓库与实际框架

工作区和上级目录没有 AGENTS.md。保留了会话开始时已有的全部未提交变更。
项目是传统 Xposed API **82**，`compileOnly(files("libs/xposed-api-82.jar"))`，
唯一入口仍为 assets/xposed_init 中的 `SystemServerHook`，未加入独立模块或 libxposed。
作用域仍为 Android 系统（Vector `system/0`），仅 UID 1000 的 android/system_server 入口。

已检查项目实际打包的 Vector v2.2-3080（ZIP SHA-256
`9ee8323575d615f7b3f1076ff60b2a63a49390ef11881b52632311a37f6f79cc`）：
正常路径在 `SystemServer.startBootstrapServices` 的原方法执行前，通过真实服务 ClassLoader
派发 legacy LoadPackageParam；另有 late injection 路径，所以不能仅凭回调认定一定足够早。
运行期 XposedBridge 有 `deoptimizeMethod(Member):void`，API82 编译 JAR 中没有，
现有侧键 Hook 使用反射的方式正确。该 void 包装忽略底层 boolean，不能将其返回描述为 ART 成功确认。
本功能没有调用去优化，因为不存在需要恢复的服务端调用者。
Vector 证据保存在 `F:/gw7/root/vpn/repo-architecture-analysis/`。

现有配置是 Root 写入 Settings.Global、Hook 有 Context 后读取。
本次沿用配置键 `gw_wear_vpn_restore_enabled`：缺失、读取失败或非 0/1 值均在 UI 按关闭处理；
只允许清除已保存请求，不允许写 1。**没有可支持的固件，所以安全门在读配置前就拒绝恢复，
没有增加未解锁时读取配置的 bootstrap Hook。早期配置读取及恢复生命周期未实现，不能算验收通过。**
这也保证 UI 从未启动、配置不存在、手工设置为 1、未知固件均不会意外启用恢复。
没有增加权限、篡改 watch 特征或其他标志、修改 SELinux、创建/注册 VpnManagerService 或替换框架。

## 代码改动

| 文件 | 原因 |
| --- | --- |
| utils/WearVpnRestore.kt | 精确识别已分析的固件为“代码已删除”；其他固件未审计；所有固件拒绝恢复，只记录自身入口和原因 |
| utils/SystemServerHook.kt | 在原有入口调用独立安全门，侧键和 Freecess 代码保持原逻辑 |
| utils/ModuleManager.kt | 默认关闭的配置状态、拒绝启用/允许清除请求、按需执行服务检查并保留退出码及完整合并输出 |
| utils/WearVpnServiceCheck.java | 单独的有超时限制的只读命令执行；保留已接收的 stdout/stderr，超时/读取不完整时明确标注；不改动原有执行工具 |
| ui/main/settings/WearVpnSettingsPage.kt | 独立入口、受阻开关、原因、服务检查及 found 的限制 |
| ui/main/settings/SettingsPage.kt、ui/Routes.kt、ui/MainActivity.kt | 接入既有导航 |
| res/values/strings.xml、res/values-zh-rCN/strings.xml | 英文和简体中文说明 |
| README.md、本文件 | 说明实际能力、证据与阻塞 |

日志仍用 `GWSystemServerHook`，前缀 `[WearVpn]`：system_server 入口、已审计删码/未知固件、
跳过配置读取、没有安装 Hook/没有去优化/没有命中；设置页实际检查才记录服务查询结果。
关闭、配置不可读及被拦截的手工请求在设置页分别显示，并在应用读配置时分别记录日志。
没有伪造 Hook 命中、去优化成功或开机注册结果。设置页查询是 UI 进程的 Root 命令，
不能充当 system_server 早期恢复 Hook 命中证据。日志不涉及用户流量、订阅或密钥。

## 构建与分层验收

实际构建命令（仓库根目录 PowerShell）：

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleRelease :app:lintDebug --console=plain
```

最终 Debug/Release 组装成功（退出码 0，42 秒）；Debug 签名验证及命令输出测试通过。
`lintDebug` 因原有 QUERY_ALL_PACKAGES 权限检查失败（1 error、14 warnings）。
结果、命令、APK 路径/哈希见 [构建验证记录](wear-vpn-build.md) 和
`F:/gw7/root/vpn/analysis-L315FXXS3BZF4/evidence/` 中的构建日志。
编译目标沿用现有 SDK 37，最低 SDK 30；release 不修改签名配置，缺少 CI 密钥时输出 unsigned APK。
debug 是调试签名，不能假定能覆盖使用不同签名安装的现有应用。

| 验收层级 | 状态 |
| --- | --- |
| 1 APK 构建 | **Debug/Release 成功；lintDebug 有原有错误** |
| 2 system_server 注入及恢复 Hook 命中 | **未实测；恢复 Hook 因固件 C 被禁止安装** |
| 3 完整重启后 vpn_management found | **未实测，未达到** |
| 4 VPN 正常授权、隧道和实际联网 | **未实测，未达到** |

未安装 APK、修改设备配置或重启手表。静态分析和构建不代表手表修复成功。

## 启用、验证、禁用与救援

设置 → 恢复 Wear OS VPN 管理服务。当前显示不可启用；不提供强制启用命令。
将来有经验证的恢复路径后也必须由用户在 Vector 中为原 GWH 模块启用 Android 系统作用域并完整重启。
现有模块的管理行为不由本次构建自动执行。

以下仅为提供给用户的手表本地终端单行命令，**本次均未执行，未实测**，保留错误输出：

```sh
su -c "service check vpn_management"
su -c "logcat -d -v threadtime -s GWSystemServerHook:I '*:S'"
su -c "settings get global gw_wear_vpn_restore_enabled"
su -c "settings put global gw_wear_vpn_restore_enabled 0"
```

`service check` 的进程退出码不替代文本结果；found 只证明存在。即使未来 Hook 成功，
`aflags list` 仍可能显示 enabled/read-only，它显示配置记录，不代表 Hook 后进程返回值。
完整 VPN 测试仍需正常授权并检查实际网络访问。

如果需要停用整个 GWH 模块，项目实际打包的 Vector CLI 支持以下命令，之后由用户完整重启：

```sh
su -c "/data/adb/modules/zygisk_vector/cli modules disable com.gohj99.galaxywatchhelper"
```

该路径和 `modules disable` 来自实际 Vector ZIP 的 CLI、module.prop、daemon ModulesCommand，
不是臆造的 KSU 模块目录。它会停用 GWH 的全部 Xposed 功能，要求 Root shell 和 Vector daemon 可用。
恢复原模块及系统作用域的已证实命令为：

```sh
su -c "/data/adb/modules/zygisk_vector/cli modules enable com.gohj99.galaxywatchhelper"
su -c "/data/adb/modules/zygisk_vector/cli scope set com.gohj99.galaxywatchhelper system/0"
```

不能启动 Android 时不能保证 CLI 可用。若仍能进入 KSU 管理器，可禁用实际安装的 Vector 模块；
如果连可用 Root shell/管理器都进不去，恢复依赖设备已经具备的恢复环境。
当前没有该手表按键安全模式或离线恢复环境证据，不编造按键步骤或额外目录。

## 剩余阻塞与最小补充材料

已提供的 JAR/OAT/VDEX 足够确认这两个 JAR 的最终 DEX 状态，但不足以证明设备所有 classpath
都没有被搬迁的实现。实际 SystemServer 在 VPN 被跳过前加载
`/apex/com.android.tethering/javalib/service-connectivity.jar`，该文件不在附件中。
若继续评估“是否迁入其他 JAR”这一唯一尚可本地调查的方向，需同一构建的此 JAR，
以及设备实际 SYSTEMSERVERCLASSPATH / STANDALONE_SYSTEMSERVER_JARS / BOOTCLASSPATH 清单，
再按清单检查相关 JAR。可先在手表读取环境；命令输出是否完整仍需核实：

```sh
su -c "printenv SYSTEMSERVERCLASSPATH STANDALONE_SYSTEMSERVER_JARS BOOTCLASSPATH"
```

获得额外文件也不保证存在可恢复路径；若仍无主服务及完整生命周期，不能在本任务约束内
凭空补齐一个平台服务。没有用手动构造/注册、系统框架替换或透明代理绕过此阻塞。
