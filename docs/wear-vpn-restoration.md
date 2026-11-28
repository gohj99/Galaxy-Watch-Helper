# Wear OS VPN 实验性恢复（当前实现）

更新：2026-11-27，SM-L315F / fresh7ul，Android 16 / SDK 36，增量 L315FXXS3BZF4。
**代码已集成进原 GWH。普通 VpnService 的服务端路径已实现；这版恢复代码尚未在手表上运行，不能称修复成功。**

2026-11-28：用户已尝试上一版，报告创建NetworkAgent失败。已修复直接实例化抽象类的错误，
改为GWH具体子类；新版构建及15项本地测试通过，重新上表结果待确认。
见 [本次修复和产物](wear-vpn-networkagent-fix.md)。

用户在固件确认被裁剪后扩大任务范围，授权通过 GWH 补回缺失逻辑，并由 GWH 为所选 VPN 应用设置
ACTIVATE_VPN。本实现据此新增自身 Binder 后端，不再尝试拦截一个已经从服务端消失的标志调用。
没有独立 Xposed 模块，没有替换 services.jar、全局改 watch 特征或关闭 SELinux。

## 已在手表上确认的事实

在用户连接的手表上执行了只读 Root 查询，并把文件拉到
`F:/gw7/root/vpn/live-20261127/`；之后按用户要求停止访问手表，可以断开 ADB。

- `service check vpn_management`：`Service vpn_management: not found`。
- `cmd vpn_management help`：退出码20，`cmd: Can't find service: vpn_management`。
- services.jar/framework.jar 的哈希与最初附件完全相同。
- 实际 boot/system-server/standalone classpath，加上 system_server 已映射的其他系统 JAR，
  共85个 JAR、91个 DEX、93597个 class_defs。全部扫描未发现主 VpnManagerService
  或任何 IVpnManager.Stub 的子类；service-connectivity.jar 中也没有迁移后的实现。
- 原 `com.android.server.connectivity.Vpn` 只剩部分方法，没有 prepare/establish 主路径；
  所以单独补注册、Hook 标志或 ART 去优化都不够。
- `/dev/tun` 存在，标签 `tun_device`，权限0660、system:vpn；内核 `CONFIG_TUN=y`。
- connectivity、netd、network_management、**netpolicy**、ipsec、network_stack、dnsresolver 均 found。
  初次查询中的 `network_policy` 不是该服务实际 Binder 名，后续已用 netpolicy 纠正。
- `/system/lib/libandroid_servers.so` 仍导出 VPN JNI 注册函数；7个 JNI 方法的名字、签名、
  ARM 实现和原 Vpn 类相符。这些函数不访问 receiver 的 Java 字段。
- 系统 VPN 授权组件实际指向
  `com.google.android.wearable.frameworkpackagestubs/.Stubs$VpnStub`；
  它继承的 BaseActivity.onCreate 只显示 Toast 然后 finish，没有授权实现。
  因此授权由用户在 GWH 中选应用后经 Root 设置 AppOps。
- 实际 `ro.build.id` 是 **BP2A.250325.020**，`ro.build.display.id` 才包含三星增量后缀。
  已修正最初安全门中错误拼接的 Build.ID。

关键原始证据：`classpath-dex-audit.txt`、`vpn-surface-audit.txt`、
`jar-inventory.json`、`extra-jar-inventory.json`、`vpn-dialog-smali/`、
`kernel-vpn-config.stdout.txt`、`firmware-hashes.stdout.txt`。
所有采集命令及退出码另存同目录 JSON，stdout/stderr 分开保留；没有过滤掉错误文件。
启动日志缓冲中未找到目标历史日志，不能把该负结果当作没有执行过启动代码的证据。

原件及 Smali 的更早分析见 [固件分析记录](wear-vpn-analysis.md)。其中的“受阻开关”描述是
用户扩大范围前的初次版本，当前行为以本文件为准。

## 恢复路径和启动安全

仍使用传统 Xposed API82和项目打包的Vector v2.2-3080，不加入libxposed。

1. `SystemServerHook` 的原 android/system_server 入口调用 `WearVpnRestore`。
   仅 SDK36、SM-L315F、fresh7ul、Build.ID及增量版本精确匹配时继续。
   若 activity Binder 已存在，按晚注入跳过，不在已运行系统里补注册。
2. Hook实际的 `SystemServiceManager.startServiceFromJar(String,String)`，
   仅匹配 `ConnectivityServiceInitializer` 和对应 APEX JAR，且原方法没有异常、当前phase200。
   此处 SettingsProvider 已安装，connectivity/ipsec 已同步发布，且在原来被删除的 VPN 位置之前。
3. 通过此时的系统 Context 读取 Settings.Global：`gw_wear_vpn_restore_enabled`。
   缺失、非1或读取失败均关闭。配置不依赖用户解锁、应用私有目录或UI预先启动。
   为读取该配置存在一个临时启动回调；关闭时不会创建恢复后端、JNI桥接或NetworkAgent回调。
4. 先检查已有 vpn_management，存在则跳过。验证平台 API、全部29个 Binder transaction、
   原7个 native签名，然后只读检查loopback JNI。准备自身线程和生命周期处理后才发布自有Binder。
5. `startBootPhase(TimingsTraceAndSlog,int)` 的phase600 before、前phase550执行自身systemReady。
   此时 ConnectivityManager.systemReady 已完成，且 SystemUI/第三方应用尚未启动。
   写入调用在ready前拒绝；必要的只读空状态查询可安全返回。
6. 两个精确去优化目标为 `SystemServer.startOtherServices(TimingsTraceAndSlog)` 和固件里
   已确认的16参数合成ready调用者 `$r8$lambda$_Ik9fEJxxT_3L9POeIJQOWRQjbw`。
   没有遍历去优化SystemServer。Vector包装返回void，日志只称调用完成，不声称获得ART成功返回。

安装、读取、反射、去优化失败只记录本功能错误，不替换平台原方法result/throwable。
注册后ready失败会关闭自有后端；此时Binder名字可能仍存在但不可建立隧道，必须查看ready日志，
不能只用found判断成功。Android没有供该模块可靠撤回已发布服务的公开API。

## 普通 VPN 应用路径

`WearVpnBinder`遵循实际固件的IVpnManager wire协议，使用平台VpnConfig/Network Parcelable，
本功能不在APK中打包IVpnManager、NetworkAgent、VpnConfig等平台实现或三星二进制。

- prepare：核对调用UID、包名、所属用户及ACTIVATE_VPN；切换owner时撤销旧owner的netd protect许可，
  为当前owner允许protect。跨用户与管理员调用保留对应平台权限检查。
- establish：重新核对授权、用户限制、包UID和受BIND_VPN_SERVICE保护的服务声明；
  创建TUN、设置地址、绑定实际VpnService、注册VPN NetworkAgent，并返回PFD给原应用。
- NetworkAgent设置VPN transport、owner/admin UID、当前用户及受限子用户的UID范围、
  allowed/disallowed应用及SDK sandbox范围、DNS、路由、代理、MTU、计费和underlying网络。
  未允许的地址族添加unreachable默认路由，避免地址族意外绕过。
- 支持动态add/removeAddress、setUnderlyingNetworks、getVpnConfig和基本状态查询。
- 应用关闭TUN、服务断开、授权撤销、包卸载/重装、用户停止/移除会清理自身会话。
  接口关闭通过只读JNI监测，最长约3秒；NetworkAgent断开回调按自有实例过滤。
  netd保护许可撤销失败保留待重试UID，阻止在未处理旧授权时准备新owner。
- **不支持平台内置IKEv2/旧式profile、profile存储、always-on/lockdown。**
  查询返回相应空状态；请求启用这些功能明确抛不支持，绝不返回虚假成功。
  软件名称里的WireGuard/OpenVPN等不影响判断，关键是它使用应用VpnService处理自身协议。

JNI桥接仅Unsafe.allocateInstance原Vpn作receiver，不执行原构造器或原Java业务方法。
实际ARM代码确认7个native不依赖该receiver字段。它复用system_server已经加载的native绑定，
不再次System.loadLibrary或重复注册。
JNI预检只能证明check已绑定且loopback查询有效；创建TUN以及其余JNI功能要由实际establish验证。

## GWH 内的应用授权

设置 → 恢复 Wear OS VPN 管理服务：默认关闭，提示“实验性，需要重启生效”。
“选择VPN应用”复用既有软件列表，仅列出声明受BIND_VPN_SERVICE保护的VpnService的应用。
选中记录仅用于UI，不参与开机早期配置读取。

点击授权/撤销时重新校验包名和VPN服务，限定当前Android用户及所选包执行：

```sh
su -c "cmd appops set --user 0 <已选择且验证的包名> ACTIVATE_VPN allow"
su -c "cmd appops get --user 0 <同一包名> ACTIVATE_VPN"
su -c "cmd appops set --user 0 <同一包名> ACTIVATE_VPN ignore"
```

上例user0与本机手表对应，代码按当前UID计算用户号，不硬编码所有用户。
代码拒绝含Shell元字符的包名，保存写后读回的完整输出，只有结果相符才显示已确认。
按钮操作由用户主动执行；本次离线开发没有为任何设备应用实际授权。
授权不创建服务，也不自动启动VPN应用或VPN连接。

## 修改文件

| 文件 | 用途 |
| --- | --- |
| utils/WearVpnRestore.kt、SystemServerHook.kt | 原模块启动集成、固件与阶段保护、精确去优化、早期配置、注册/ready日志 |
| utils/vpn/NativeVpnBridge.java | 严格封装原7个已审计JNI方法 |
| utils/vpn/VpnPlatform.java | 精确平台反射、LinkProperties/NetworkCapabilities/NetworkAgent/用户范围 |
| utils/vpn/WearVpnService.java | 自有会话、权限、AppOps、TUN、生命周期和资源清理 |
| utils/vpn/WearVpnBinder.java | 实际IVpnManager协议及明确的不支持路径 |
| utils/vpn/VpnUidPolicy.java | 可独立测试的用户/应用/SDK sandbox UID路由计算 |
| utils/ModuleManager.kt、WearVpnApplications.kt、WearVpnAppOpsAuthorization.java | 配置、候选验证、选择、单应用Root授权及读回 |
| ui/main/settings/WearVpnSettingsPage.kt、WearVpnApplicationSelectionPage.kt | VPN恢复和应用授权界面 |
| WalletTargetSelectionPage.kt | 软件列表最小泛化，钱包原默认行为保持 |
| MainActivity.kt、Routes.kt、SettingsPage.kt、英/中文strings | 现有导航与设置集成 |
| app/build.gradle.kts、app/src/test/... | JUnit依赖及路由/命令安全测试 |

没有更换注入框架、修改Manifest权限或旧侧键/Freecess业务函数。原未提交改动和暂存状态保留。

## 验收与后续上表验证

本地实际命令：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease --console=plain
.\gradlew.bat :app:lintDebug --console=plain
```

本地测试覆盖UID范围/跨用户/SDK sandbox/排除列表、Shell包名注入、按用户及包限制授权、
不确定AppOps读回不报成功。另对实提固件核对35项平台方法签名和全部29个Binder transaction编号。
这些验证不能替代Android Binder、JNI、netd和Connectivity在手表上联动运行。
最终Debug/Release构建及11项测试通过；完整lint仍有原QUERY_ALL_PACKAGES错误。
APK哈希、签名、准确命令和日志位置见 [本轮构建记录](wear-vpn-restoration-build.md)。

手表上建议按以下顺序验收，均保留完整输出，未执行的不能算已通过：

1. 用户安装与现有签名相容的GWH APK，在Vector为原GWH模块选Android系统作用域。
2. 在GWH打开实验恢复开关，手动完整重启。
3. 检查入口、配置、精确Hook命中、JNI预检、注册和ready日志。
4. 在GWH选择VPN应用并主动授权，确认AppOps读回allow，再由该应用正常发起VPN连接。
5. 检查establish日志、实际隧道及真实网络访问；测试断开、重连、关闭TUN及撤销授权后的清理。

```sh
su -c "service check vpn_management"
su -c "dumpsys vpn_management"
su -c "logcat -d -v threadtime -s GWSystemServerHook:I '*:S'"
```

found只证明Binder名字存在；dumpsys应显示ready=true。建立TUN和实际联网要分别验证。
aflags仍显示enabled/read-only不是此实现失败的证据，因为没有修改该编译期配置记录。
日志不记录DNS、路由内容、流量、订阅、密钥或隧道配置。

禁用：GWH关闭恢复开关后手动完整重启；立即停用特定VPN可撤销该应用授权。
若需禁用整个GWH模块，实际Vector CLI为：

```sh
su -c "/data/adb/modules/zygisk_vector/cli modules disable com.gohj99.galaxywatchhelper"
```

之后完整重启。此命令需要可用Root shell与Vector daemon；无法开机时不保证可运行。
若能进入KSU管理器可禁用实际Vector模块；没有设备已验证的按键安全模式，不编造恢复步骤。

截至当前：APK构建及本地测试结果见最终构建记录；新后端system_server注入、JNI创建、
Binder返回PFD、实际VPN联网、Root授权按钮和撤销均**未实测**。未自行安装、改设备设置或重启。
