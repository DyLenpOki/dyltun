# dyltun API 使用文档

本文面向将 dyltun 接入自己 Android 应用的开发者，按当前 `0.1.0-SNAPSHOT` 公开 API 编写。所有库类型均位于 `com.dylenpoki.dyltun` 包中。

当前支持 IPv4 TCP/UDP 转发、明文 HTTP/1.1 请求与响应修改、本地 HTTP 响应，以及基于 TLS ClientHello / MQTT CONNECT 的连接路由。库使用 Kotlin 实现，最低支持 Android API 21。版本仍在开发中，完整能力与限制见 [IMPLEMENTATION.md](IMPLEMENTATION.md)。

## 目录

- [1. 添加依赖](#1-添加依赖)
- [2. 在 VpnService 中接入](#2-在-vpnservice-中接入)
- [3. 初始化 API 与配置](#3-初始化-api-与配置)
- [4. HTTP Hooks 与消息操作](#4-http-hooks-与消息操作)
- [5. Body 配置与默认值](#5-body-配置与默认值)
- [6. 协议识别与连接路由](#6-协议识别与连接路由)
- [7. 自定义协议与规则](#7-自定义协议与规则)
- [8. Session 生命周期与诊断](#8-session-生命周期与诊断)
- [9. 常见问题与当前边界](#9-常见问题与当前边界)

## 1. 添加依赖

### 1.1 源码模块

在本仓库内，`sample` 通过 Android Library 源码模块接入，在宿主模块的 `build.gradle.kts` 添加：

```kotlin
// settings.gradle.kts 中已 include(":dyltun")。
dependencies {
    implementation(project(":dyltun"))
}
```

库当前构建使用 JDK 21、compileSdk 36 和 AGP 9.2.1 的内置 Kotlin 支持，Java 字节码目标为 17。移入其他工程时还需适配其插件和版本目录配置；独立工程建议先使用下节的本地 Maven 接入。详细工具链见 [BUILDING.md](BUILDING.md)。

### 1.2 本机 Maven 依赖

当前没有发布到远程仓库。先在 dyltun 仓库根目录执行：

```powershell
.\gradlew.bat :dyltun:publishReleasePublicationToMavenLocal
```

Linux/macOS 使用 `bash ./gradlew`。该步骤只发布到当前用户的本地 Maven 仓库；CI 和其他开发者的机器需要各自构建，不能直接从 Maven Central 下载此坐标。独立宿主已验证 Kotlin 2.2.10，接入方需使用兼容本库 Kotlin 元数据的编译器。

然后在同一台机器上，为宿主的 `settings.gradle.kts` 配置依赖仓库：

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenLocal {
            content { includeModule("com.dylenpoki", "dyltun") }
        }
        google()
        mavenCentral()
    }
}
```

宿主模块的 `build.gradle.kts`：

```kotlin
dependencies {
    implementation("com.dylenpoki:dyltun:0.1.0-SNAPSHOT")
}
```

Maven 发布包含传递依赖和源码包。直接复制 `dyltun-release.aar` 时，宿主需另外提供 Kotlin 标准库与 kotlinx-coroutines；建议使用模块或 Maven 依赖。协程当前版本为 1.9.0。

## 2. 在 VpnService 中接入

### 2.1 宿主准备

dyltun 接收已经建立的 TUN FD。宿主仍负责 Android VPN 生命周期：

1. 在 Activity 调用 `VpnService.prepare()`；有授权 Intent 时，等待用户授权成功后再启动服务。
2. 在 Manifest 声明自己的 `VpnService`，配置 `android.permission.BIND_VPN_SERVICE` 和 `android.net.VpnService` intent-filter。
3. 根据 Android 版本与 targetSdk，启动前台服务、创建通知渠道并调用 `startForeground()`。
4. 使用 `VpnService.Builder` 配置地址、路由、DNS、MTU、应用允许/排除列表，再调用 `establish()`。
5. 把 FD 交给 dyltun，保存返回的 Session；停止或撤销 VPN 时关闭它。

库 Manifest 合并 `INTERNET` 与 `ACCESS_NETWORK_STATE` 权限。前台服务声明与通知由宿主提供；本仓库 sample 使用 `specialUse`，完整声明见 [sample Manifest](../sample/src/main/AndroidManifest.xml)。实际接入还需按宿主用途配置适用的前台服务类型。

### 2.2 服务中的初始化

以下代码先独立构造配置，服务内只负责建立 FD 和启动会话。宿主应在授权并调用 `startForeground()` 后调用 `startTunnel()`；授权界面、启动命令和通知实现见可运行的 [DemoVpnService.kt](../sample/src/main/kotlin/com/dylenpoki/dyltun/sample/DemoVpnService.kt) 与 [MainActivity.kt](../sample/src/main/kotlin/com/dylenpoki/dyltun/sample/MainActivity.kt)。

```kotlin
package example.vpn

import android.net.VpnService
import com.dylenpoki.dyltun.BodyLimit
import com.dylenpoki.dyltun.BodyLimits
import com.dylenpoki.dyltun.DyltunSession
import com.dylenpoki.dyltun.Endpoint
import com.dylenpoki.dyltun.HttpFlow
import com.dylenpoki.dyltun.HttpHeaders
import com.dylenpoki.dyltun.HttpHooks
import com.dylenpoki.dyltun.HttpResponse
import com.dylenpoki.dyltun.RoutingDecision
import com.dylenpoki.dyltun.dyltun
import com.dylenpoki.dyltun.dyltunConfig

private object AppHttpHooks : HttpHooks {
    override fun request(flow: HttpFlow) {
        flow.request.headers["X-Dyltun-Request"] = "enabled"
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

private val appConfig = dyltunConfig {
    hooks = AppHttpHooks
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

class AppVpnService : VpnService() {
    private var session: DyltunSession? = null

    // 在授权与 startForeground() 完成后，由宿主的启动逻辑调用。
    fun startTunnel() {
        check(session == null) { "VPN session already exists" }
        val fd = Builder()
            .setSession("dyltun")
            .setMtu(1500)
            .setBlocking(false)
            .addAddress("10.77.0.1", 32)
            .addRoute("198.18.0.1", 32)
            .establish() ?: error("VPN permission is unavailable")

        session = try {
            dyltun(fd, appConfig)
        } catch (error: Throwable) {
            // 同步启动调用未返回 Session 时，由宿主关闭 FD。
            fd.close()
            throw error
        }
    }

    override fun onRevoke() {
        session?.close()
        super.onRevoke()
    }

    override fun onDestroy() {
        session?.close()
        super.onDestroy()
    }
}
```

这个 Builder **只捕获 `198.18.0.1/32` 的测试流量**。启动后可从设备访问 `http://198.18.0.1/local-health` 验证本地响应；即使该地址没有运行 HTTP 服务，也能收到 Hook 生成的 JSON。

测试客户端需要允许明文 HTTP；若请求被客户端的 Android 网络安全配置拦截，数据不会进入 VPN。

要扩大捕获范围，由宿主调整路由，例如 `addRoute("0.0.0.0", 0)` 捕获 IPv4。域名识别规则只处理实际进入 TUN 的 TCP 流量；上面的单地址路由不会自动捕获所有访问 `blocked.example.com` 的流量。DNS 也由宿主配置，UDP/TCP 53 可通过普通路由规则重定向，见第 6 节。

`127.0.0.1:1883` 指 Android 设备自身，需要宿主另行运行 MQTT broker。dyltun 不启动 broker。

### 2.3 只需要转发

在 `VpnService` 内，把已经建立的 FD 交给库即可：

```kotlin
session = dyltun(fd)
```

`hooks` 默认为 `null`，不启用 HTTP 语义修改；TCP/UDP 仍可转发。协议识别和路由也都是可选配置。

## 3. 初始化 API 与配置

### 3.1 配置工厂与启动入口

以下为公开函数签名，省略实现：

```kotlin
fun dyltunConfig(
    configure: DyltunConfig.Builder.() -> Unit = {}
): DyltunConfig

fun VpnService.dyltun(
    fd: ParcelFileDescriptor,
    config: DyltunConfig
): DyltunSession

fun VpnService.dyltun(
    fd: ParcelFileDescriptor,
    configure: DyltunConfig.Builder.() -> Unit = {}
): DyltunSession

fun VpnService.dyltun(
    fd: ParcelFileDescriptor,
    hooks: HttpHooks?
): DyltunSession
```

`ParcelFileDescriptor` 来自 `android.os`。这是 `VpnService` 扩展函数，库通过接收者自动保护上游 TCP/UDP Socket，避免流量回到自己的 VPN。调用者无需传 `protect` 回调。

调用形式任选其一，同一个 FD 只能交给一个 Session：

```kotlin
// 无配置。
dyltun(fd)

// 可复用配置；可在 VpnService 之外构造。
val config = dyltunConfig { hooks = httpHooks }
dyltun(fd, config)

// 少量配置也可直接使用行内 DSL。
dyltun(fd) { hooks = httpHooks }

// 便捷重载。
dyltun(fd, httpHooks)

// 显式关闭 HTTP Hooks。
dyltun(fd) { hooks = null }
```

没有 `dyltun(fd, hooks) { ... }` 重载。DSL 内若宿主属性也叫 `hooks`，应使用 `hooks = this@AppVpnService.hooks`，或将宿主属性命名为 `httpHooks`。

### 3.2 根配置

`DyltunConfig.Builder` 的可配置成员：

| 入口 | 类型 / 调用形式 | 默认值与作用 |
|---|---|---|
| `hooks` | `HttpHooks?` | `null`；非空时自动启用内置明文 HTTP/1.1 处理 |
| `bodyLimits` | `BodyLimits` | `BodyLimits()`；限制 HTTP Body 聚合、解码与修改 |
| `protocols { ... }` | `ProtocolConfig.Builder.() -> Unit` | 默认空；`install()` 显式注册；内置值动作规则可补齐默认识别器 |
| `routing { ... }` | `RoutingConfig.Builder.() -> Unit` | 空规则集；默认转发 |

配置块同步执行并生成不可变配置快照。重复调用 `protocols {}` / `routing {}` 会追加条目。Hooks、识别器和规则实例保留引用，共享状态应满足各自的并发约束。配置对象不提供运行中热更新入口；变更配置需要关闭旧 Session 后创建新 Session。

`dyltunConfig {}` 不访问 FD、服务或网络，可在普通 Kotlin 代码中构造和校验。同一个配置可启动多个 Session；各会话独立管理连接和预算，但共享 Hooks、识别器和规则对象，宿主须保证这些对象的并发安全。每次启动仍需新的 FD。行内 `dyltun(fd) { ... }` 通过同一工厂构造配置，之后走相同启动路径。

### 3.3 FD 所有权与启动结果

- FD 必须是非阻塞 TUN：保留 Builder 默认值，或显式 `setBlocking(false)`。尤其在 API 21–29，不要传入由 `setBlocking(true)` 建立的 FD。
- DSL 或配置校验同步抛错时，宿主负责关闭 FD，例如第 2 节的 `try/catch`。
- `dyltun()` 成功返回后，FD 所有权属于 Session。宿主不能再读写、关闭或把它传给另一个 Session；不应使用 `fd.use { dyltun(it) }`。
- 返回时可能仍处于 `Starting`。运行时异步启动失败通过 `state` 的 `Failed` 上报，并清理 FD 和其他资源。
- Session 关闭后 FD 不能复用；重新启动需重新 `establish()`。

## 4. HTTP Hooks 与消息操作

### 4.1 回调与 Flow

```kotlin
interface HttpHooks {
    fun request(flow: HttpFlow) {}
    fun response(flow: HttpFlow) {}
    fun error(flow: HttpFlow, cause: Throwable) {}
}
```

三个方法都有默认空实现，可以只覆盖需要的回调。`HttpFlow` 由库创建，一次 HTTP 事务对应一个 Flow，同一 TCP 连接可以产生多个 Flow。

| `HttpFlow` 属性 | 类型 | 说明 |
|---|---|---|
| `id` | `Long`，只读 | Session 内 HTTP 事务编号 |
| `connection` | `ConnectionInfo`，只读 | 原始 TCP 连接信息，见第 7 节 |
| `request` | `HttpRequest`，只读引用 | 请求对象，其字段可修改 |
| `response` | `HttpResponse?`，可赋值 | 请求回调开始时为 `null`；赋值可生成或替换响应 |
| `responseSource` | `ResponseSource?`，只读 | 未有响应时为 `null`；随后为 `UPSTREAM` 或 `LOCAL` |

典型顺序：聚合请求 → `request()` → 上游收发或本地响应 → `response()` → 发送给客户端。Body 进入流式模式时不等待完整 Body，详情见第 5 节。

同一客户端连接按顺序处理事务，不同连接的回调可能并发执行。回调运行在独立的有界工作执行器上，不在 UI 线程；不要执行阻塞网络请求、长时间计算或等待，也不要在回调返回后异步修改 Flow。Hooks 自身的共享状态需要保证线程安全。

未捕获的 Hook 异常会终止该连接并产生诊断事件。关闭 Session 后返回的迟到 Hook 结果不会继续发送；已经阻塞的同步 Hook 无法安全强制终止，可能延迟 `awaitClosed()`。

上游连接/读写失败，或接收响应时提前 EOF，且最终响应尚未提交时，会调用 `error(flow, cause)`。可以在其中设置 `flow.response = HttpResponse.make(...)` 生成回退响应；随后正常调用一次 `response()`，来源为 `LOCAL`，发送后关闭该客户端连接。回退仍报告原始 `ConnectionError`，并继续遵守 Body 限额。如果没有设置响应，则保留原始失败并终止连接。

`error()` 不是通用异常拦截器：请求/响应 Hook 异常、路由拒绝、Body 限额拒绝、畸形 HTTP、客户端读写错误、取消和已提交最终响应后的故障都不会变成本地回退。不要用它重发业务请求；上游可能已经收到原请求。

例如，只对允许离线返回的路径生成本地响应：

```kotlin
override fun error(flow: HttpFlow, cause: Throwable) {
    if (flow.request.path == "/offline-status") {
        flow.response = HttpResponse.make(200, "offline")
    }
}
```

此方法位于 `HttpHooks` 实现中，执行线程和生命周期约束与其他 Hooks 相同。

### 4.2 修改请求与响应

以下示例为可直接赋给 `hooks` 的对象。它修改路径、Header，以及可用的文本 Body：

```kotlin
import com.dylenpoki.dyltun.HttpFlow
import com.dylenpoki.dyltun.HttpHooks

val rewriteHooks: HttpHooks = object : HttpHooks {
    override fun request(flow: HttpFlow) {
        val request = flow.request
        request.headers["X-Client"] = "my-android-app"
        if (request.path == "/v1/items?debug=1") {
            request.path = "/v2/items?debug=1"
        }
        val type = request.headers["Content-Type"].orEmpty()
        if (type.startsWith("text/", ignoreCase = true)) {
            val text = request.text
            if (text != null) request.text = text.replace("old-value", "new-value")
        }
    }

    override fun response(flow: HttpFlow) {
        val response = flow.response ?: return
        response.headers["X-Processed-By"] = "dyltun"
        val type = response.headers["Content-Type"].orEmpty()
        if (type.startsWith("text/", ignoreCase = true)) {
            val text = response.text
            if (text != null) response.text = text.replace("old-value", "new-value")
        }
    }
}
```

`text == null` 可能表示 Body 未缓冲、编码不支持或文本解码失败，不能当作空字符串。需要 JSON 语义修改时，宿主自行选择 JSON 库。

### 4.3 本地响应：HttpResponse.make

在 `request()` 设置 `flow.response`，该请求将跳过上游连接；库仍调用一次 `response()`，其中 `responseSource == ResponseSource.LOCAL`。第 2 节的 `/local-health` 就是完整示例。

公开工厂签名：

```kotlin
fun make(
    statusCode: Int = 200,
    content: ByteArray = byteArrayOf(),
    headers: HttpHeaders = HttpHeaders()
): HttpResponse

fun make(
    statusCode: Int = 200,
    content: String,
    headers: HttpHeaders = HttpHeaders(),
    charset: Charset = Charsets.UTF_8
): HttpResponse
```

这两个函数位于 `HttpResponse` 的 companion object，调用时写 `HttpResponse.make(...)`。`Charset` 来自 `java.nio.charset`。

| 用途 | 调用 |
|---|---|
| 空的 200 响应 | `HttpResponse.make()` |
| 文本 403 响应 | `HttpResponse.make(403, "Forbidden")` |
| 无 Body 的 204 响应 | `HttpResponse.make(statusCode = 204)` |
| 二进制响应 | `HttpResponse.make(200, bytes, HttpHeaders.of("Content-Type" to "application/octet-stream"))` |

文本重载默认添加 `Content-Type: text/plain; charset=UTF-8`，使用其他 `charset` 时相应调整；手动指定的 Content-Type 字符集必须与编码一致。Header 和字节数组会复制。工厂只接受 `200..599`，不能用来生成 `100 Continue` 或 `101 Switching Protocols`。

每次事务都应新建响应对象；同一个可变 `HttpResponse` 不能跨 Flow 复用。`response()` 也可替换 `flow.response`，但不能赋 `null`。直接修改现有响应字段保留来源标识；给 `flow.response` 赋非空值会把来源标为 `LOCAL`。

### 4.4 HttpRequest

公开构造函数：

```kotlin
HttpRequest(
    method: String,
    url: String,
    headers: HttpHeaders = HttpHeaders(),
    content: ByteArray = byteArrayOf()
)
```

Hook 中通常直接修改 `flow.request`。它继承 `HttpMessage`，另外提供：

| 属性 | 类型 | 规则 |
|---|---|---|
| `method` | `String`，可写 | 合法 HTTP token，如 `GET` / `POST` |
| `url` | `String`，可写 | 绝对 `http://` URL，不允许 userinfo、fragment 或非法端口 |
| `path` | `String`，可写 | 包含 query 的 origin-form，例如 `/items?page=2`；以 `/` 开头，不允许 `//` 开头或 `#` |

修改 `url` 的主机或端口会更新 Host，并更新候选上游地址；只改 `headers["Host"]` 不改变网络目的地。显式路由的 `Redirect` 可以覆盖 URL 的候选地址，最终目的地仍会检查拒绝规则。路径和 query 中的 Unicode 会转换为 ASCII 百分号编码。

向客户端发送响应时，仍遵守客户端原始 HEAD 请求的无 Body 语义，即使 Hook 修改过请求方法。

### 4.5 HttpMessage 与 HttpResponse

`HttpRequest` 和 `HttpResponse` 共享以下 API：

| 属性 | 类型 | 语义 |
|---|---|---|
| `headers` | `HttpHeaders`，只读引用 | 可通过集合方法修改 |
| `bodyState` | `BodyState`，对外只读 | `BUFFERED` / `STREAMING` / `OPAQUE` |
| `content` | `ByteArray?`，可写 | 可用时为解码后的 Body；getter 与 setter 均复制字节 |
| `text` | `String?`，可写 | 按 Content-Type charset 严格解码/编码，缺省 UTF-8 |

`HttpResponse.statusCode: Int` 可修改为 `200..599`。204 不允许非空 Body；HEAD、204、304 的实际发送遵守 HTTP 无 Body 规则。

注意：

- 清空 Body 使用 `content = byteArrayOf()` 或 `text = ""`，不能赋 `null`。
- `content` getter 返回副本。修改该数组后，必须重新赋给 `content` 才会生效。
- `text` 对不支持的 charset 或非法文本字节返回 `null`；setter 的非法 charset、无法编码字符等会抛异常。
- identity / gzip Body 可自动解码和重新编码。修改 Body 后，库重算 framing 并移除失效的内容摘要/缓存校验字段，无需手写 Content-Length 或传入已经 gzip 压缩的字节。
- `STREAMING` / `OPAQUE` 的 Body 不可读取或修改；可修改普通 Header，但不能改变 `Content-Length`、`Transfer-Encoding`、`Content-Encoding`、`Trailer`。流式请求还不能改变方法，流式原响应不能改变状态码；需要替换响应时可赋一个新的、有界 `HttpResponse`。
- 每次读取 `content` 或解码 `text` 都可能占用 Body 总预算，建议读取一次保存局部变量。

收到合法的上游 101 时，`response()` 可以观察它，或替换成普通最终响应来终止升级；保留 101 时不能修改 framing、添加 Body 或破坏已协商的 Upgrade。升级成功后转为 TCP 字节流转发，不提供 WebSocket 消息修改 Hook。

### 4.6 HttpHeaders

Header 名大小写不敏感，支持重复字段，保留条目顺序。

| API | 行为 |
|---|---|
| `headers[name]` | 返回第一个匹配值，缺失为 `null` |
| `headers[name] = value` | 替换该名称的全部条目 |
| `add(name, value)` | 追加条目，适合重复的 Set-Cookie |
| `getAll(name): List<String>` | 返回全部匹配值的只读快照 |
| `remove(name)` | 删除所有匹配条目 |
| `entries(): List<Pair<String, String>>` | 返回全部条目的只读快照 |
| `copy(): HttpHeaders` | 独立副本 |
| `HttpHeaders.of(vararg entries: Pair<String, String>)` | 用键值对创建集合 |

```kotlin
val headers = HttpHeaders.of("Content-Type" to "text/plain; charset=utf-8")
headers.add("Set-Cookie", "a=1; Path=/")
headers.add("Set-Cookie", "b=2; Path=/")
headers["X-Trace"] = "sample"
```

名称必须是合法 HTTP token；值不能包含 CR/LF，按 HTTP/1.1 Latin-1 字段范围校验，不直接接受任意中文字符串。需要携带非 ASCII 内容时按具体字段规范编码。

## 5. Body 配置与默认值

### 5.1 类型定义

以下为数据类型签名，省略参数校验实现：

```kotlin
enum class BodyOverflow { STREAM, REJECT }

data class BodyLimit(
    val maxBufferedBytes: Long,
    val maxDecodedBytes: Long,
    val maxBufferingMillis: Long = 2_000,
    val overflow: BodyOverflow = BodyOverflow.STREAM
)

data class BodyLimits(
    val request: BodyLimit = BodyLimit(1L * 1024 * 1024, 4L * 1024 * 1024),
    val response: BodyLimit = BodyLimit(2L * 1024 * 1024, 8L * 1024 * 1024),
    val maxTotalBufferedBytes: Long = 32L * 1024 * 1024
)
```

| 参数 | 含义 | 默认请求 / 响应 |
|---|---|---|
| `maxBufferedBytes` | 去除 chunk framing 后、解压前的 Body 上限 | 1 MiB / 2 MiB |
| `maxDecodedBytes` | 解压后、暴露给 Hook 的 Body 上限 | 4 MiB / 8 MiB |
| `maxBufferingMillis` | 单次 Body 聚合的时间上限 | 2,000 ms / 2,000 ms |
| `overflow` | 收包聚合超限时的处理 | `STREAM` / `STREAM` |
| `maxTotalBufferedBytes` | Session 内所有事务共享的受管 Body 缓冲预算 | 32 MiB |

两个单消息字节上限必须在 `1..Int.MAX_VALUE`，聚合时间在 `1..Long.MAX_VALUE / 1_000_000`，总预算大于 0。恰好达到单消息字节上限可以接受，超过才触发策略。

总预算包含受管的原始/解码 Body、API 副本与临时容量，容量按保守方式记账并在事务结束时释放。因此可能在 Body 内容字节数之和低于 32 MiB 时触发总预算。它不是整个进程的堆内存上限；Header、Socket、IP/TCP 缓冲和宿主自己创建的对象不全部计入。

### 5.2 BodyState 与超限行为

| 状态 | 含义 | Hook 能否修改 Body |
|---|---|---|
| `BUFFERED` | 完整可解码 Body 已在限制内聚合 | 可以；修改结果仍必须符合限制 |
| `STREAMING` | 超过聚合大小、时间或预算后流式转发 | 不可以；`content` / `text` 为 `null` |
| `OPAQUE` | 内容编码不支持，保留原始流 | 不可以；`content` / `text` 为 `null` |

默认 `BodyOverflow.STREAM` 保留已读取的前缀并继续转发原始 Body，正常调用一次对应方向的 Hook。适合只改 Header，或允许大文件原样通过的场景。

设置 `BodyOverflow.REJECT` 后，接收原始请求/上游响应时的聚合失败会生成以下空 Body 响应并关闭连接，同时报告 `ConnectionError`：

| 聚合失败原因 | 请求方向 | 上游响应方向 |
|---|---|---|
| 编码或解码后的 Body 超过大小上限 | 413 | 502 |
| Session 总 Body 预算不足 | 503 | 503 |
| 聚合超时 | 408 | 504 |

被拒绝的该方向不会执行正常 Hook；响应方向被拒绝时，先前的 `request()` 可能已经执行。该处理发生在最终响应头发出之前。

**Hook 主动写入 Body、替换响应或本地响应的结果始终受硬上限约束。** 这些写入不会因 `STREAM` 而绕过限制，也不会截断；超限可能在赋值或后续编码阶段抛错，未处理的错误会终止该连接。公开异常 `BodyLimitExceededException` 继承 `IllegalArgumentException`；非法状态、编码和参数也可能产生其他异常。

例如严格限制请求 Body，并允许大响应流式通过：

```kotlin
bodyLimits = BodyLimits(
    request = BodyLimit(
        maxBufferedBytes = 512L * 1024,
        maxDecodedBytes = 2L * 1024 * 1024,
        maxBufferingMillis = 1_500,
        overflow = BodyOverflow.REJECT
    ),
    response = BodyLimit(
        maxBufferedBytes = 2L * 1024 * 1024,
        maxDecodedBytes = 8L * 1024 * 1024,
        overflow = BodyOverflow.STREAM
    ),
    maxTotalBufferedBytes = 32L * 1024 * 1024
)
```

此片段位于 `dyltun(fd) { ... }` 中。限制也适用于 gzip 解码后的膨胀结果，避免仅检查压缩大小。

## 6. 协议识别与连接路由

### 6.1 内置协议

`protocols` 只负责识别连接前缀并提供元信息；`routing` 根据元信息和连接信息决定转发、重定向或拒绝。配置 HTTP Hooks 不需要安装额外的 HTTP 识别器。

| 识别器 | 元信息 | 作用范围 |
|---|---|---|
| `TlsClientHelloInspector()` | `TlsClientHello(serverName: String?)` | TCP 上可见的 TLS ClientHello；`serverName` 可以为 `null` |
| `MqttInspector()` | `MqttConnect(version: Int, clientId: String)` | 明文 MQTT CONNECT，版本 3 / 4 / 5 对应 MQTT 3.1 / 3.1.1 / 5 |

推荐使用第 2 节的值动作规则：

- `tlsSni(domains, ..., action = ...)`：匹配给定域名中的任意一个；默认精确匹配，`includeSubdomains = true` 时也匹配以点分隔的子域名，不是任意字符串包含匹配。
- `mqtt(action = ...)`：匹配已识别的 MQTT 连接。
- 两者的 `action` 只接受 `RoutingDecision.Forward`、`Reset` 或 `Redirect(Endpoint)`；`Continue` / `Drop` 在配置时拒绝。
- 这两种值动作重载自动补齐所需默认识别器；显式 `install()` 相同 ProtocolId 的对象始终优先，与配置块顺序无关。每个 ProtocolId 只保留一个识别器。

`tlsSni` 支持域名集合，并保留原有单域名重载。以下为签名摘录：

```kotlin
fun tlsSni(
    domains: Iterable<String>,
    includeSubdomains: Boolean = false,
    action: RoutingDecision
)

fun mqtt(action: RoutingDecision)

// 以下动作块入口继续兼容；需要显式安装识别器。
fun tlsSni(
    domain: String,
    includeSubdomains: Boolean = false,
    action: TcpActionBuilder.() -> Unit
)

fun mqtt(action: TcpActionBuilder.() -> Unit)

fun tlsSni(
    domains: Iterable<String>,
    includeSubdomains: Boolean = false,
    action: TcpActionBuilder.() -> Unit
)
```

在 `dyltun(fd) { ... }` 中传入域名集合：

```kotlin
routing {
    tlsSni(
        domains = listOf("blocked.example.com", "blocked.example.org"),
        includeSubdomains = true,
        action = RoutingDecision.Reset
    )
}
```

一组域名共享同一个动作和 `includeSubdomains` 设置，接受 `List<String>`、`Set<String>` 等 `Iterable<String>`，不提供多字符串重载。原有 `tlsSni("example.com", true) { reset() }` 和 `domain = "example.com"` 调用保持兼容。

域名在配置时复制、按 IDN/大小写/末尾点规范化并去重；后续修改传入集合不会改变规则。空域名组或任意一个非法域名都会使该次配置失败，不会只安装其中的合法域名。不支持通配符模式。

旧动作块 `tlsSni(...) { reset() }` / `mqtt { redirect(...) }` 保留原校验方式，须在 `protocols` 中显式安装依赖，或由另一条值动作规则补齐。动作块必须且只能选择一个 `forward()`、`reset()` 或 `redirect(host, port)`。自定义规则不自动安装识别器。补齐默认值后仍有缺失依赖、重复显式 ProtocolId、缺失动作或多个动作，均在接管 FD 前报错。

探测目前仅针对客户端 TCP 流前缀，最多 65,535 字节 / 握手后 1 秒；不完整或未知协议回退普通转发。TLS 识别不解密 HTTPS；ECH、无 SNI、QUIC、MQTTS 不保证识别，不能把这套规则视为所有域名流量的完整阻断能力。

### 6.2 TCP / UDP 通用规则

例如将进入 TUN 的 TCP 和 UDP 53 都重定向到指定 DNS 服务：

```kotlin
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.dylenpoki.dyltun.DyltunSession
import com.dylenpoki.dyltun.Endpoint
import com.dylenpoki.dyltun.RoutingDecision
import com.dylenpoki.dyltun.dyltun

fun VpnService.startWithDnsRedirect(fd: ParcelFileDescriptor): DyltunSession =
    dyltun(fd) {
        routing {
            rule { context ->
                if (context.connection.destinationPort == 53) {
                    RoutingDecision.Redirect(Endpoint("1.1.1.1", 53))
                } else {
                    RoutingDecision.Continue
                }
            }
        }
    }
```

这里不依赖协议识别，不需要 `protocols {}`。仅匹配端口，不修改 DNS 报文内容。

lambda 入口为 `rule(requiredProtocols: Set<ProtocolId> = emptySet(), evaluate: (RoutingContext) -> RoutingDecision)`；依赖集合在注册时复制。已有 `rule(rule: ConnectionRule)` 保持兼容，适合复用具名策略。两种形式采用相同的依赖、传输协议动作校验和拒绝优先规则。

| `RoutingDecision` | TCP | UDP | 含义 |
|---|---|---|---|
| `Continue` | 支持 | 支持 | 本规则不作决定，继续检查 |
| `Forward` | 支持 | 支持 | 选择当前候选目的地 |
| `Redirect(Endpoint)` | 支持 | 支持 | 改连指定目的地 |
| `Reset` | 支持 | 不支持 | 拒绝 TCP，向客户端发送 RST |
| `Drop` | 不支持 | 支持 | 丢弃 UDP，不发送业务响应 |

所有适用规则中的拒绝动作优先；否则采用第一个 `Forward` / `Redirect`，没有决定时默认 `Forward`。因此 `Forward` 不能覆盖后面的拒绝规则。

HTTP Hook 改写 URL 后会根据新候选地址再次检查规则，重定向后的最终目的地也检查拒绝规则；不会递归执行重定向链。UDP 决策按连接缓存，不是逐包调用规则。

`Forward` 使用库保护的上游 Socket 转发。若要让特定应用完全不进入 VPN，应在宿主 `Builder.establish()` 前使用 `addDisallowedApplication()` 等平台配置。

## 7. 自定义协议与规则

### 7.1 公开扩展契约

以下为签名摘录：

```kotlin
@JvmInline
value class ProtocolId(val value: String) // 内置常量：ProtocolId.TLS、ProtocolId.MQTT

interface ProtocolMetadata {
    val protocolId: ProtocolId
}

data class InspectionContext(val connection: ConnectionInfo)

sealed interface InspectionResult {
    data object NeedMore : InspectionResult
    data object NoMatch : InspectionResult
    data class Matched(val metadata: ProtocolMetadata) : InspectionResult
}

interface ProtocolInspector {
    val protocolId: ProtocolId
    fun inspect(context: InspectionContext, prefix: ByteBuffer): InspectionResult
}

interface ConnectionRule {
    val requiredProtocols: Set<ProtocolId> get() = emptySet()
    fun evaluate(context: RoutingContext): RoutingDecision
}

data class RoutingContext(
    val connection: ConnectionInfo,
    val metadata: ProtocolMetadata? = null,
    val destination: Endpoint = connection.originalDestination,
    val detectedProtocols: List<ProtocolMetadata> = listOfNotNull(metadata)
)
```

`ByteBuffer` 来自 `java.nio`。`ProtocolId.value` 必须匹配 `[a-z0-9][a-z0-9._-]{0,63}`，一个 Session 中只能注册一个同 ID 的识别器。Matched 的元信息 ID 必须与识别器 ID 一致。

`prefix` 是只读的、从连接起点累计的前缀，不是单个 IP 包或新增分片。`NeedMore` 表示当前前缀不足，`NoMatch` 表示排除该协议，`Matched` 表示已识别并提交元信息。不得保留 Buffer 引用；可使用 `duplicate()` 独立调整读取位置。

识别器和规则应快速、确定地返回，不得做阻塞 I/O。实例可能被多个连接并发调用，避免在实例上保存单连接解析进度，返回的元信息应保持不可变。

`requiredProtocols` 声明规则依赖，未识别出全部依赖时跳过规则。`metadata` 提供一个相关元信息，多协议规则应读取 `detectedProtocols`。`destination` 是当前候选目的地；`connection` 始终保留原始连接信息。

### 7.2 最小自定义识别器

下面以开头固定为 `PING\r\n` 的演示协议为例，识别后转向设备上的服务：

```kotlin
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.dylenpoki.dyltun.ConnectionRule
import com.dylenpoki.dyltun.DyltunSession
import com.dylenpoki.dyltun.Endpoint
import com.dylenpoki.dyltun.InspectionContext
import com.dylenpoki.dyltun.InspectionResult
import com.dylenpoki.dyltun.ProtocolId
import com.dylenpoki.dyltun.ProtocolInspector
import com.dylenpoki.dyltun.ProtocolMetadata
import com.dylenpoki.dyltun.RoutingContext
import com.dylenpoki.dyltun.RoutingDecision
import com.dylenpoki.dyltun.dyltun
import java.nio.ByteBuffer

private val pingId = ProtocolId("example-ping")

private data object PingMetadata : ProtocolMetadata {
    override val protocolId: ProtocolId = pingId
}

private class PingInspector : ProtocolInspector {
    override val protocolId: ProtocolId = pingId
    private val signature = "PING\r\n".toByteArray(Charsets.US_ASCII)

    override fun inspect(
        context: InspectionContext,
        prefix: ByteBuffer
    ): InspectionResult {
        val view = prefix.duplicate()
        val size = minOf(view.remaining(), signature.size)
        repeat(size) { index ->
            if (view.get() != signature[index]) return InspectionResult.NoMatch
        }
        return if (size < signature.size) InspectionResult.NeedMore
        else InspectionResult.Matched(PingMetadata)
    }
}

fun VpnService.startWithPingRoute(fd: ParcelFileDescriptor): DyltunSession =
    dyltun(fd) {
        protocols { install(PingInspector()) }
        routing {
            rule(object : ConnectionRule {
                override val requiredProtocols = setOf(pingId)
                override fun evaluate(context: RoutingContext): RoutingDecision =
                    RoutingDecision.Redirect(Endpoint("127.0.0.1", 9000))
            })
        }
    }
```

扩展点支持“识别元信息 → 决定连接动作”。当前没有面向任意协议的双向 Payload 修改插件接口；MQTT 消息修改、TLS 解密、UDP 内容变换不能仅靠 `ProtocolInspector` 实现。

### 7.3 连接信息类型

```kotlin
enum class TransportProtocol { TCP, UDP }

data class Endpoint(val host: String, val port: Int)

data class ConnectionInfo(
    val id: Long,
    val transport: TransportProtocol,
    val sourceAddress: InetAddress,
    val sourcePort: Int,
    val destinationAddress: InetAddress,
    val destinationPort: Int
) {
    val originalDestination: Endpoint
}
```

以上为签名摘录，`InetAddress` 来自 `java.net`。`Endpoint` 接受 IP 或主机名，不接受带 scheme、路径的 URL，端口范围为 `1..65535`。连接 ID 在 Session 内标识连接，HTTP Flow ID 标识该连接中的某次事务，两者用途不同。

`sourceAddress/sourcePort` 是发起应用的源地址/端口；`destinationAddress/destinationPort` 和 `originalDestination` 是进入 TUN 时的原始目标，不随重定向改变。UDP 源端口允许 0，TCP 源端口与目的端口为 `1..65535`。

## 8. Session 生命周期与诊断

### 8.1 公开接口

```kotlin
interface DyltunSession : Closeable {
    val state: StateFlow<DyltunState>
    val events: SharedFlow<DyltunEvent>
    override fun close()
    suspend fun awaitClosed()
}

sealed interface DyltunState {
    data object Starting : DyltunState
    data object Running : DyltunState
    data object Stopping : DyltunState
    data object Stopped : DyltunState
    data class Failed(val cause: Throwable) : DyltunState
}

sealed interface DyltunEvent {
    data class ConnectionError(
        val connection: ConnectionInfo?,
        val cause: Throwable,
        val diagnostics: ConnectionDiagnostics = ConnectionDiagnostics()
    ) : DyltunEvent
    data class PacketRejected(val reason: String) : DyltunEvent
}
```

`Closeable` 来自 `java.io`，`StateFlow` / `SharedFlow` 来自 `kotlinx.coroutines.flow`。

| 操作 / 状态 | 语义 |
|---|---|
| `Starting` | Session 已创建，运行时仍在启动 |
| `Running` | 运行时已启动，不表示任意远端都可访问 |
| `close()` | 线程安全、幂等、非阻塞地请求关闭 |
| `Stopping` | 正在停止并释放资源 |
| `Stopped` | 已正常完成关闭 |
| `Failed(cause)` | Session 启动或运行失败；通过 `awaitClosed()` 等待清理结束 |
| `awaitClosed()` | 挂起等待关闭完成；不主动发起关闭，也不代表运行成功 |

`ConnectionError` 通常只影响某个连接，`connection` 可能为 `null`；`PacketRejected` 表示无法处理的数据包。单个事件不等于整个 Session 失败，应结合 `state` 判断。

### 8.2 收集状态和事件

从宿主管理的协程作用域调用下列函数，并在服务结束时取消该作用域：

```kotlin
import android.util.Log
import com.dylenpoki.dyltun.DyltunEvent
import com.dylenpoki.dyltun.DyltunSession
import com.dylenpoki.dyltun.DyltunState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

fun observeSession(scope: CoroutineScope, session: DyltunSession) {
    scope.launch {
        session.state.collect { state ->
            if (state is DyltunState.Failed) {
                Log.e("MyVpn", "dyltun session failed", state.cause)
                // 在宿主生命周期中关闭会话、更新通知并结束服务。
            }
        }
    }
    scope.launch {
        session.events.collect { event ->
            when (event) {
                is DyltunEvent.ConnectionError -> {
                    val details = event.diagnostics
                    Log.w(
                        "MyVpn",
                        "Connection ${event.connection?.id} failed: " +
                            "host=${details.host} source=${details.hostSource} " +
                            "dnsCandidates=${details.dnsCandidates} " +
                            "upstream=${details.upstream} resolved=${details.resolvedUpstream} " +
                            "stage=${details.stage} tcpState=${details.tcpState}",
                        event.cause
                    )
                }
                is DyltunEvent.PacketRejected ->
                    Log.w("MyVpn", "Packet rejected: ${event.reason}")
            }
        }
    }
}
```

事件流当前保留最近 16 条，额外缓冲 48 条，过载时丢弃最旧项；用于诊断，不是可靠审计队列。库不主动把 HTTP Body 放入事件，但宿主自己抛出的异常仍可能带有敏感内容，记录日志时应自行控制。

### 8.3 关闭与重新启动

服务的停止命令、`onRevoke()` 和 `onDestroy()` 应调用 `session.close()`。需要确保完全释放，或准备替换 Session 时，在协程中捕获旧引用并等待：

```kotlin
suspend fun closeSession(session: DyltunSession) {
    session.close()
    session.awaitClosed()
    // 可检查 session.state.value 是否为 DyltunState.Failed。
}
```

不要在主线程使用 `runBlocking` 等待。完成等待后再 `establish()` 新 FD 并创建新 Session；宿主应串行化启停命令，防止旧回调关闭新 Session。

库不管理前台通知，也不调用宿主的 `stopSelf()`。Android 对 VpnService 的绑定可能在 `stopService()` 后继续存在，建议像 sample 一样使用显式停止命令，先关闭 Session，再处理通知和服务退出。

### 8.4 连接错误上下文

`ConnectionError.diagnostics` 是错误发生时的尽力快照，原始异常仍保留在 `cause`。原来的两参数构造调用可继续使用。所有新增类型位于 `com.dylenpoki.dyltun`：

```kotlin
enum class ConnectionStage {
    HANDSHAKE, INSPECTION, ROUTING, PROCESSING, CONNECTING, FORWARDING
}

enum class HostSource { HTTP_AUTHORITY, TLS_SNI }

data class ConnectionDiagnostics(
    val protocol: String? = null,
    val host: String? = null,
    val hostSource: HostSource? = null,
    val dnsCandidates: List<String> = emptyList(),
    val upstream: Endpoint? = null,
    val resolvedUpstream: Endpoint? = null,
    val stage: ConnectionStage? = null,
    val tcpState: String? = null,
    val httpMethod: String? = null,
    val httpPath: String? = null,
    val httpFlowId: Long? = null,
    val durationMillis: Long = 0,
    val bytesFromClient: Long = 0,
    val bytesToClient: Long = 0
)
```

| 字段 | 含义 |
|---|---|
| `protocol` | 已识别协议，如 `http/1.1`、`tls`、`mqtt`；未识别时为 `tcp` / `udp` |
| `host` / `hostSource` | 已处理 HTTP 请求的原始 authority（Host 或绝对 URL），或已安装识别器读到的 TLS SNI；不因 Hook URL 改写而覆盖原始主机 |
| `dnsCandidates` | 原始目标 IP 对应的被动 DNS 候选名，最多 4 个；不能证明当前连接实际使用其中某个域名 |
| `upstream` | 最后选择并尝试打开的上游，可与 `connection.originalDestination` 不同；本地响应或尚未选出目标时为 `null` |
| `resolvedUpstream` | 成功打开上游时从现有 Socket 观察到的数字 IP/端口；未建连成功或无法读取时为 `null` |
| `stage` | 最近到达的粗粒度阶段：握手、探测、路由、协议处理、打开上游、转发；不是 HTTP 并发上传/下载的精确阶段或失败归因 |
| `tcpState` | 最后观察到的 TCP 状态；客户端 RST 保留复位前的状态，UDP 为 `null` |
| `httpMethod` / `httpPath` / `httpFlowId` | 最近成功解析的 HTTP 请求方法、原始路径和事务 ID；路径不含 query/fragment，不记录 Header 或 Body。聚合失败可能尚未分配 Flow ID |
| `durationMillis` | 从创建该连接记录到快照的单调时钟耗时；不是某笔 HTTP 或 connect 的耗时 |
| `bytesFromClient` / `bytesToClient` | TCP 为提交到转发/处理器的有序输入，以及提交到 TCP endpoint 的输出字节数；UDP 为提交转发的数据报 Payload 和接纳的回复 Payload。均不含 IP/TCP/UDP 头，不表示已上网或已被应用 ACK |

HTTP 域名需要请求进入 HTTP 处理器（`hooks` 非空且成功解析请求头）；TLS 域名需要配置中存在相应识别器并成功读到 SNI。80 端口也可能是二进制协议，没有 Host/SNI 时 `host` 保持 `null`。

DNS 关联只观察本 Session 转发的 UDP 53 查询与匹配响应中的 IPv4 A/CNAME，按 TTL 过期，最多保留 1 小时；不反向解析 IP，也不发起额外 DNS 请求。连接创建前的系统缓存、VPN 外的 DNS、TCP DNS、DoH/DoT、IPv6 与加密的 SNI 不会因此变得可见。共享 IP 会产生多个候选，空集合也不意味着目标没有域名。

HTTP 每解析一笔新请求就刷新事务字段并清除旧上游信息；连接级计数继续累计。快照不随后续请求修改，跨线程状态和计数为尽力观测。字段缺失不影响转发。宿主输出日志时还应限制字段长度、转义控制字符，并按业务需求控制路径和异常的可见性。

## 9. 常见问题与当前边界

| 现象 / 需求 | 检查或处理 |
|---|---|
| 初始化就抛 `Missing protocol inspectors` | 为旧动作块和自定义 `requiredProtocols` 安装对应识别器；内置值动作规则会补齐其默认依赖 |
| `dyltun()` 返回后不转发 | 收集 `state` 与 `events`，检查异步启动失败、VPN 路由及底层网络 |
| HTTP Hook 没有执行 | 确认 hooks 非空、流量进入 TUN、内容为明文 HTTP/1.1，且未先被路由拒绝 |
| 大 Body 的 `text` / `content` 为 `null` | 检查 `bodyState`、方向限额和总预算；未知 Content-Encoding 为 `OPAQUE` |
| 小 Body 读取时报预算不足 | getter 副本、文本解码与临时容量也计费；避免重复读取，调整总预算 |
| 改 Host 后仍连原服务器 | 使用 `request.url` 改候选目标，或配置 `Redirect` |
| 要给 HTTPS 加/改 Header | 当前不支持 TLS 解密；SNI 仅用于连接路由 |
| 要把 MQTT 转到本地 | 使用 `mqtt(action = RoutingDecision.Redirect(Endpoint("127.0.0.1", 1883)))`；确保设备端 broker 已启动 |
| 退出后 VPN 仍在运行 | 显式向服务发送停止命令并 `close()`，不要仅依赖 `stopService()` |

当前版本还有以下使用边界：

- 数据面仅支持 IPv4 TCP/UDP；IPv6 和其他 IP 协议不会正常转发，不提供完整 ICMP、PMTU 与网络切换迁移。仅设置 IPv4 路由不能视为对 IPv6 流量的完整 VPN 保护。
- HTTP/2、HTTP/3、HTTPS 内容修改不受支持；未知 TCP 协议按普通字节流转发。
- 公共扩展为 ProtocolInspector 与 ConnectionRule。内部已拆出 TCP 处理器选择与生命周期边界，但没有公共内容处理器注册 API；本轮也未增加 MQTT 消息修改。维护者扩展说明见 [STREAM_PROCESSORS.md](STREAM_PROCESSORS.md)。
- HTTP/1.1 客户端连接支持 keep-alive 与按序处理 pipelining，但每笔事务单独建立上游连接，没有上游连接池。
- HTTP 解析当前限制单行 8 KiB、头部区 32 KiB、最多 128 个字段；这些不是公开可调配置。Hop-by-hop Header 会规范化，不能依赖其原样保留。
- 主机名解析当前可能阻塞网络选择器工作线程；高并发与长期真机性能仍需验证。

需要可运行宿主时，从 [sample](../sample/src/main/kotlin/com/dylenpoki/dyltun/sample/) 开始；内部设计见 [DESIGN.md](DESIGN.md)，构建命令见 [BUILDING.md](BUILDING.md)。
