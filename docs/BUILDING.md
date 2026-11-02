# 构建与运行

## 工具链

| 项目 | 固定配置 |
|---|---|
| Gradle Daemon JDK | 21，由 gradle/gradle-daemon-jvm.properties 声明；推荐设置 JAVA_HOME 为 JDK 21 |
| Java 源码/字节码目标 | 17，与构建使用的 JDK 版本分开配置 |
| Gradle | Wrapper 9.4.1，分发 ZIP 固定 SHA-256 |
| Android Gradle Plugin | 9.2.1，使用其内置 Kotlin 支持 |
| Android SDK | Platform 36 / Build Tools 36.0.0；库 minSdk 21；示例 targetSdk 36 |
| kotlinx-coroutines | 1.9.0，通过库 api 传递给宿主 |
| 测试 | JUnit 4.13.2、AndroidX runner 1.7.0 / junit 1.3.0 |

先安装 JDK 21，并设置 JAVA_HOME。通过 Android Studio 或 Android SDK Command-line Tools 安装 SDK，再设置 ANDROID_HOME；也可让 Android Studio 生成本机 local.properties，文件不会提交。使用命令行 SDK 时执行：

```shell
sdkmanager --licenses
sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"
```

Gradle 的 Daemon JVM 条件优先于 Launcher 的 JAVA_HOME：即使从另一受支持 JDK 启动 Wrapper，构建仍会选择 JDK 21，必要时下载。使用 `bash ./gradlew --version`（Windows 为 `.\gradlew.bat --version`）核对。Java 源码与字节码目标仍为 17。

代码和 Gradle 脚本只使用 Kotlin；AndroidManifest/XML 与 GitHub YAML 是平台配置。Wrapper JAR 是标准 Gradle 启动器，不包含项目自定义 Java/native 协议实现。

## 构建与测试

Linux/macOS：

```shell
bash ./gradlew :dyltun:testDebugUnitTest :dyltun:assembleRelease :sample:assembleDebug
bash ./gradlew :dyltun:lintDebug :sample:lintDebug
```

Windows PowerShell：

```powershell
.\gradlew.bat :dyltun:testDebugUnitTest :dyltun:assembleRelease :sample:assembleDebug
.\gradlew.bat :dyltun:lintDebug :sample:lintDebug
```

设备测试需要已连接的 Android 设备/模拟器，可先通过 `adb devices` 确认，再执行 `:dyltun:connectedDebugAndroidTest`。仅构建测试 APK 使用 `:dyltun:assembleDebugAndroidTest`，不代表已运行设备测试。

首次构建需要网络下载依赖。缓存完整后可加 --offline；不要用跳过检查的方式处理缺失依赖。Linux/macOS 文档使用 `bash ./gradlew`，从 Windows 创建仓库时也可运行；若要使用 `./gradlew`，先设置其可执行权限。

## GitHub CI

[CI 工作流](../.github/workflows/ci.yml) 在 push、pull request 和手动触发时，使用 Ubuntu 24.04 / JDK 21：

- 校验 Wrapper JAR，缓存 Gradle 依赖；PR 只读取缓存。
- 执行 JVM 测试与库/示例 Lint。
- 构建 Android 测试 APK、示例 APK、release AAR，并验证本地 Maven publication。
- 上传报告、AAR、源码包、publication 元数据与 APK，保留 7 天。

Actions 固定到已核实的 commit SHA，Dependabot 每月提出更新；工作流只申请 contents:read，不向公共 Maven 或 GitHub Release 发布。CI 未配置设备测试或真实 VPN 冒烟，远程执行结果需上传仓库后在 Actions 页面确认。

本地执行与 CI 相同的 Gradle 任务：

```shell
bash ./gradlew --no-daemon --continue :dyltun:testDebugUnitTest :dyltun:lintDebug :dyltun:assembleDebugAndroidTest :dyltun:publishReleasePublicationToMavenLocal :sample:lintDebug :sample:assembleDebug
```

## Windows 临时目录问题

本机 Windows 使用默认短 TEMP 路径时，Gradle/JDK 的 NIO Unix socket 可能报 `Unable to establish loopback connection` / `Invalid argument: connect`。已验证的会话级处理如下，不需要修改全局系统设置：

```powershell
$socketTemp = Join-Path $PWD '.gradle/socket-tmp'
New-Item -ItemType Directory -Force -Path $socketTemp | Out-Null
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$($socketTemp -replace '\\', '/')"
.\gradlew.bat :dyltun:testDebugUnitTest --console=plain --no-daemon
```

JAVA_HOME 仍指向自己的 JDK 21。没有该错误时无需 JAVA_TOOL_OPTIONS 处理。

| 输出 | 路径（相对仓库） |
|---|---|
| Android Library AAR | dyltun/build/outputs/aar/dyltun-release.aar |
| Library 源码包 | dyltun/build/intermediates/source_jar/release/release-sources.jar |
| 示例 APK | sample/build/outputs/apk/debug/sample-debug.apk |
| JVM 测试报告 | dyltun/build/reports/tests/testDebugUnitTest/index.html |
| Android 测试报告 | dyltun/build/reports/androidTests/connected/debug/index.html |
| Lint | 各模块 build/reports/lint-results-debug.html |

库配置了 Maven release publication 和 sources JAR，可使用 `:dyltun:publishReleasePublicationToMavenLocal` 在本机验证集成。尚未上传任何远程仓库。建议宿主以 Maven 依赖接入，直接复制 AAR 还需自行提供 Kotlin 标准库/协程依赖。

本地 Maven publication 坐标为 `com.dylenpoki:dyltun:0.1.0-SNAPSHOT`。宿主需先在同一用户下运行 publication 任务，再通过 mavenLocal() 依赖；克隆源码不会自动使该坐标可从公共仓库下载。独立宿主已验证 Kotlin 2.2.10 / AGP 8.13.2，接入方编译器须兼容库 Kotlin 元数据。该版本仍具有 IMPLEMENTATION.md 中的开发限制。

## 运行示例

安装 sample-debug.apk，启动应用，点击授权并启动，然后执行 TCP/UDP DNS 与 HTTP 本地响应测试。宿主只捕获 198.18.0.1/32；DNS 转向 1.1.1.1:53，回复仍表现为来自原测试目标。/local-health 在 request Hook 中生成 JSON，response Hook 添加 X-Dyltun。DNS 成功依赖物理网络允许访问该服务，本地 HTTP 不需要访问上游。

DemoVpnService 包含授权后启动、前台通知、Builder、单次 dyltun 初始化、状态/事件观察和关闭。使用非阻塞 TUN，显式写出 setBlocking(false)；这也是 API 21–29 的宿主要求。DemoHttpHooks 独立展示 Hooks，服务内实际启用 TLS/MQTT 识别与连接规则。

停止按钮通过 ACTION_STOP 命令让服务先关闭 Session，再 stopSelf。Android 系统可能仍绑定 VpnService，不能只依赖 Activity.stopService 触发 onDestroy。API 34 已验证 DNS 成功并停止后无活跃 VPN/服务。

FullApiExample.kt 单独编译完整四块配置和 Body 自定义值；该函数未被示例服务调用。四项入口现均已接通，不因非空 Hooks/识别器而启动失败。

当前仍需补齐 ICMP/PMTU、网络变化和长期真机验收；示例没有声明 IPv6 路由或全局流量保护。范围和资源限制见 IMPLEMENTATION.md。

独立 Android 宿主已在本地以 Maven 坐标验证无配置、DSL、两参数简写、完整四项配置与自定义 SPI 的编译，不需要引用源码工程。本地额外集成环境不属于仓库交付内容。

独立宿主使用自己的 SDK 配置，主工程的 local.properties 不会自动继承。可为构建进程设置 ANDROID_HOME，或在宿主工程自己的 local.properties 中指定 sdk.dir。
