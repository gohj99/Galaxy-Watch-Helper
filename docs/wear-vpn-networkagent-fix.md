# NetworkAgent 创建失败修复（2026-11-28）

用户实测错误：`Cannot create VPN dependency android.net.NetworkAgent`。
已定位并修正上一版实现错误；新版尚未重新上表验证，不代表VPN已成功联网。

## 根因与修正

三星实际`framework-connectivity.jar`的类声明是：

```smali
.class public abstract Landroid/net/NetworkAgent;
```

此前仅验证了构造器签名存在，随后直接`Constructor.newInstance()`创建抽象类，
会得到`InstantiationException`，被包装成用户看到的错误。
这与作用域、ACTIVATE_VPN和TUN权限无关；增加作用域不能修复该错误。

修复：

- 新增具体子类`GwhNetworkAgent`，调用已核对的原平台构造器；
  `VpnPlatform.newAgent`改为创建此子类，原实例过滤和网络清理流程保持不变。
- 初始化时检查实际父类身份、子类非抽象及其构造器，避免仅凭签名检查通过。
- 新增`VpnReflection`，对抽象类明确报错，构造失败保留真实类型、消息和cause；
  establish失败在服务端记录异常堆栈后清理自身资源。
- 新增四份手写的编译期平台签名，独立编译为compileOnly输入，**不会进入APK**。
  没有新增APK模块、替换系统类或打包三星框架二进制。
- 新增四项回归测试，包括“有构造器、无抽象方法但类仍abstract”的原始失败条件。

## 本地验证

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease --console=plain
```

退出码0，`BUILD SUCCESSFUL in 50s`；15项测试全部通过：原11项及新增4项。
Debug签名`apksigner verify`通过。

`lintDebug`仍未通过：1 error、26 warnings，唯一error为原Manifest的
QUERY_ALL_PACKAGES检查。本次未添加新的lint错误，也未全局屏蔽检查。

另用Google dexlib2 3.0.9同时检查实际三星DEX和两个最终APK：

- 平台NetworkAgent确实abstract，目标构造器public，且没有待实现的abstract方法。
- APK中的GwhNetworkAgent非abstract，直接继承原类，构造器调用原平台的正确签名。
- 两个APK中均没有四个编译桩类：NetworkAgent、NetworkScore、NetworkAgentConfig、NetworkProvider。
- 原xposed_init保持不变，包内没有三星框架、OAT/ART/VDEX文件。

证据和完整日志在`F:/gw7/root/vpn/network-agent-fix-20261128-*`。
复现验证工具为
`F:/gw7/root/vpn/analysis-L315FXXS3BZF4/tools/VerifyConcreteNetworkAgent.java`，
输入实际`framework-connectivity.jar`和生成的Debug/Release APK。

| APK | SHA-256 |
| --- | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | `85acba4ca3e4dde55b2e9248f2d143ff22a934317625e6dc77c336e112eb1f4c` |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | `f171715f5966e1c9a24a2f847f728616edc05131139661757cce2a06b70950a4` |

Debug是本地调试签名，Release仍未签名。没有更改用户已有依赖版本、暂存状态，
也没有访问ADB、安装、改手表配置或重启。

## 用户验证

覆盖安装签名兼容的新APK后，**必须完整重启手表**，让system_server加载新代码。
保留原GWH的Android系统作用域以及VPN恢复开关；在GWH确认目标应用授权后，再连接VPN。

如果仍失败，服务端现在记录establish错误堆栈。可提供以下命令的完整输出：

```sh
su -c "logcat -d -v threadtime -s GWSystemServerHook:I '*:S'"
```

更新前旧system_server中的Hook不会仅因覆盖安装而自动换成新版。
创建成功、隧道建立和真实联网仍需分别确认。
