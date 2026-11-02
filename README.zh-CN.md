# dyltun

[English](README.md) | 简体中文

面向 Android `VpnService` 的纯 Kotlin 网络处理模块。

由 GPT 6.1 Sol 在极高思考模式下创造。

**当前状态：实验性版本 `0.1.0-SNAPSHOT`，API 可能调整。** IPv4 TCP/UDP、明文 HTTP/1.1 修改与本地响应已接通，TLS SNI 阻断和 MQTT 重定向已参与实际运行。尚未完成多版本真机、长期负载与稳定版本验收。

支持 request/response Hooks、HttpResponse.make、上游失败时的 error Hook 回退、identity/gzip、Body 方向/总预算及超限流式回退。本轮 124 项 JVM 测试和 8 项 API 34 Android 测试通过；此前真实 VPN 验证 TCP/UDP DNS 和 HTTP 本地响应。实现范围、资源上限和验证见 [IMPLEMENTATION.md](docs/IMPLEMENTATION.md)。

接入自己的 Android 应用，请从 [API 使用文档](docs/API.md) 开始，包含依赖配置、VpnService 初始化和可复制的调用示例。

## 获取与构建

构建环境为 JDK 21、Android SDK Platform 36 / Build Tools 36.0.0；仓库自带 Gradle 9.4.1 Wrapper，使用 AGP 9.2.1 的内置 Kotlin 支持。库最低 API 21，Java 字节码目标为 17；目前运行验证集中于 API 34。

在仓库根目录执行：

```powershell
.\gradlew.bat :dyltun:testDebugUnitTest :dyltun:lintDebug :sample:lintDebug :sample:assembleDebug
.\gradlew.bat :dyltun:publishReleasePublicationToMavenLocal
```

Linux/macOS 将 `.\gradlew.bat` 换成 `bash ./gradlew`。先配置 Android SDK；完整步骤见 [BUILDING.md](docs/BUILDING.md)。

**当前没有公共 Maven 发布。** 本地 publication 的坐标为 `com.dylenpoki:dyltun:0.1.0-SNAPSHOT`。其他应用通过同一用户的 `mavenLocal()` 接入，配置见 [添加依赖](docs/API.md#1-添加依赖)。仅声明该坐标不会从 Maven Central 下载到本库。独立宿主已验证 Kotlin 2.2.10；接入时需使用兼容 Kotlin 元数据的编译器。

## 文档导航

| 文档 | 内容 | 查阅时机 |
|---|---|---|
| [API.md](docs/API.md) | 接入指南、HTTP 修改与本地响应、Body 限额、路由、Session 和扩展接口 | 将模块接入自己的 Android 应用 |
| [DESIGN.md](docs/DESIGN.md) | 模块职责、数据路径、生命周期、HTTP 与路由行为 | 理解和实现内部逻辑 |
| [FLOW.md](docs/FLOW.md) | 初始化、TCP/UDP 数据流、HTTP Hooks 和资源归属流程图 | 对照代码理解运行过程 |
| [ROADMAP.md](docs/ROADMAP.md) | 实施顺序、依赖关系、阶段验收清单 | 按阶段执行和核对进度 |
| [CHANGELOG.md](CHANGELOG.md) | 软件变更与尚未发布的内容 | 查看功能和修复记录 |
| [DESIGN_HISTORY.md](docs/DESIGN_HISTORY.md) | 设计版本和决策变化 | 对照历史决定 |
| [IMPLEMENTATION.md](docs/IMPLEMENTATION.md) | 已实现能力、资源上限、测试与后续缺口 | 判断当前代码能否使用 |
| [BUILDING.md](docs/BUILDING.md) | 工具链、构建、测试和宿主示例 | 本地运行 |
| [CONTRIBUTING.md](CONTRIBUTING.md) | 贡献方式与验证要求 | 提交问题和改动 |
| [RELEASING.md](docs/RELEASING.md) | GitHub 首次上传与后续发布检查 | 维护仓库 |

公共签名和默认值以 API.md 为准，运行语义以 DESIGN.md 为准，完成情况以 ROADMAP.md 为准。

## 完整配置示例

根配置提供四个可选入口：`hooks`、`bodyLimits`、`protocols`、`routing`。用 `dyltunConfig {}` 在服务之外构造配置，服务内只需 `dyltun(fd, config)`。内置规则使用 `action = ...` 时自动补齐默认识别器；需要自定义识别器时再使用 `protocols`。

下面完整示例已参与编译，四项入口均已接入运行时。可运行宿主见 [DemoVpnService.kt](sample/src/main/kotlin/com/dylenpoki/dyltun/sample/DemoVpnService.kt)，Hooks 示例见 [DemoHttpHooks.kt](sample/src/main/kotlin/com/dylenpoki/dyltun/sample/DemoHttpHooks.kt)。

```kotlin
import com.dylenpoki.dyltun.BodyLimit
import com.dylenpoki.dyltun.BodyLimits
import com.dylenpoki.dyltun.Endpoint
import com.dylenpoki.dyltun.HttpFlow
import com.dylenpoki.dyltun.HttpHeaders
import com.dylenpoki.dyltun.HttpHooks
import com.dylenpoki.dyltun.HttpResponse
import com.dylenpoki.dyltun.RoutingDecision
import com.dylenpoki.dyltun.dyltun
import com.dylenpoki.dyltun.dyltunConfig

val config = dyltunConfig {
    hooks = object : HttpHooks {
        override fun request(flow: HttpFlow) {
            if (flow.request.path == "/local-health") {
                flow.response = HttpResponse.make(
                    statusCode = 200,
                    content = """{"status":"ok"}""",
                    headers = HttpHeaders.of(
                        "Content-Type" to "application/json; charset=utf-8"
                    )
                )
            }
        }

        override fun response(flow: HttpFlow) {
            flow.response?.headers?.set("X-Dyltun", "enabled")
        }
    }

    // 示例自定义值；默认值见 API.md。
    bodyLimits = BodyLimits(
        request = BodyLimit(
            maxBufferedBytes = 512L * 1024,
            maxDecodedBytes = 2L * 1024 * 1024
        ),
        response = BodyLimit(
            maxBufferedBytes = 2L * 1024 * 1024,
            maxDecodedBytes = 8L * 1024 * 1024
        ),
        maxTotalBufferedBytes = 32L * 1024 * 1024
    )

    routing {
        tlsSni(
            domains = listOf("blocked.example.com", "blocked.example.org"),
            includeSubdomains = true,
            action = RoutingDecision.Reset
        )
        mqtt(action = RoutingDecision.Redirect(Endpoint("127.0.0.1", 1883)))
    }
}

// 位于 VpnService 子类；fd 来自 Builder.establish()。
session = dyltun(fd, config)
```

`TlsClientHelloInspector` 和 `MqttInspector` 提取可见的 SNI / MQTT CONNECT 元信息，在打开上游连接前执行 RST 或重定向。探测最多 65,535 字节/1 秒；不完整或未知协议默认转发。ECH、无 SNI、QUIC、MQTTS 不保证识别，也不提供 HTTPS 解密或 MQTT 消息修改。

## 简单调用与宿主职责

无需内容处理时调用 `dyltun(fd)`。已有 Hooks 对象时使用 `dyltun(fd) { hooks = httpHooks }`，或使用两参数简写 `dyltun(fd, httpHooks)`。

原有行内 DSL 和路由动作块保持兼容。通用规则可用 `rule { context -> ... }`；`ConnectionRule` 扩展接口继续保留。配置复用与内部 HTTP 职责拆分见 [简化记录](docs/REFACTORING.md)。

宿主负责 VPN 授权、前台服务、TUN 地址、路由和应用排除规则。库接管 FD 后负责转发及资源管理。宿主在停止、撤销或销毁 VPN 时调用 `session.close()`；完整关闭语义见 DESIGN.md。

建议先使用 sample 的单地址测试路由。当前只处理 IPv4，未实现完整 ICMP/PMTU 和网络切换迁移；HTTP 每笔事务单独连接上游，尚无连接池。API 21–29 宿主须保留 Builder 默认非阻塞 FD，或显式 setBlocking(false)。

首版核心目标为 IPv4 TCP/UDP 转发和明文 HTTP/1.1 修改、本地响应；协议扩展通过 TLS SNI 阻断和 MQTT 重定向验证。IPv6、TLS 内容解密、HTTP/2、HTTP/3 不属于首版核心支持范围。

## 项目结构与反馈

`dyltun/` 是库及 JVM/Android 测试，`sample/` 是可运行示例，`docs/` 是 API 与设计说明，`.github/` 包含 CI 和问题/PR 模板。CI 检查 Wrapper、JVM 测试、Lint、AAR、本地 Maven 打包、示例和 Android 测试 APK；设备测试需另行执行。

问题反馈可使用中文或英文；请提供版本/提交、环境、最小复现和脱敏日志。贡献说明见 [CONTRIBUTING.md](CONTRIBUTING.md)，漏洞报告方式见 [SECURITY.md](SECURITY.md)。

## 许可证状态

项目暂未选择许可证，仓库不包含项目 `LICENSE` 文件。Gradle Wrapper 自身的许可证声明保持原样。
