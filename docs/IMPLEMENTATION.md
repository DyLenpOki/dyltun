# 当前实现与验证

日期：2026-11-03。设计基线 v0.5，开发版本 0.1.0-SNAPSHOT，未发布远程。本页记录已实现的行为；目标验收清单见 [ROADMAP.md](ROADMAP.md)。

## 能力矩阵

| 范围 | 已实现 | 主要边界 |
|---|---|---|
| Kotlin Android Library | :dyltun、:sample、AAR/Maven、本地独立宿主编译 | minSdk 21；运行证据目前集中于 API 34 模拟器 |
| 根 DSL | dyltunConfig 独立配置、dyltun(fd, config)、行内/可空 Hooks 重载、配置快照与依赖校验 | 扩展对象保留引用，宿主保证共享状态的并发安全 |
| Session/TUN | FD 接管、状态/有界事件、poll/唤醒、非阻塞关闭、清理异常保留 | API 21–29 要求 Builder 默认非阻塞 FD 或 setBlocking(false) |
| 连接诊断 | 错误快照、HTTP authority/TLS SNI 来源、被动 DNS 候选、选定/已连接上游、阶段、TCP 状态、方法/路径/Flow ID 与耗时/计数 | 尽力观测；DNS 候选不证明实际域名，不主动反查，不含 query/Header/Body |
| IPv4/UDP | 长度/校验和/选项、有限分片重组、转发/Redirect/Drop、空包/多响应 | 没有完整 ICMP 错误转换、PMTU、网络切换迁移；不转发 IPv6 |
| TCP | 独立序列空间、握手、窗口、乱序/重复、RTT/RTO/快速重传、基本拥塞控制、半关闭/RST/TIME_WAIT | 不协商窗口缩放、SACK、时间戳；尚无长期真机/性能结论 |
| 协议路由 | 开放 inspector/rule SPI、lambda rule、TLS 域名集合/MQTT 值动作自动默认识别器、完整前缀重放 | 显式识别器优先；旧动作块/自定义规则保留依赖校验；探测最多 65,535 字节/1 秒 |
| 内部 TCP 处理器 | 按识别结果选择、统一有序流、背压、结构化取消、每连接上游资源归属；HTTP 已接入 | internal 接口；未匹配时原始转发，尚无公共内容处理 SPI 或 MQTT 修改 |
| HTTP/1.1 | 增量头部/Body 编解码、chunked/trailers、Content-Length、关闭分隔、持久客户端连接与顺序流水请求 | 当前每笔事务使用独立上游 Socket，尚不做上游连接池 |
| HTTP Hooks | 请求/响应 URL、Header、Body 修改、独立有界执行器、连接内顺序、上游失败时的 error Hook 本地回退 | 仅明文 HTTP/1.1；error 不恢复 Hook/协议/Body 拒绝或已提交响应；同步 Hook 无法被安全强杀，可能延迟 awaitClosed |
| 本地响应 | request 中设置 flow.response、HttpResponse.make、LOCAL 来源、零上游连接、一次 response Hook | 未读完上传时发送响应后关闭；旧上游响应被替换时关闭其 Socket |
| Body 限额 | 方向编码/解码限额、聚合时间、Session 总预算、identity/gzip、STREAM/REJECT、OPAQUE | 预算是受管容量而非整个 JVM 堆；保守计费可能提前转流式/拒绝 |
| HTTP 特殊语义 | HEAD/204/304、最多 16 个 1xx、100-continue、早到最终响应、101 后双向原始流 | 首条 CONNECT、HTTP/1.0、HTTP/2、TLS 不进入修改器；不提供 CONNECT 代理服务 |
| 示例宿主 | 授权/通知、四项 DSL、TCP/UDP DNS、HTTP 本地响应、显式停止 | 仅捕获 198.18.0.1/32 测试地址，不代表全局流量保护 |

配置失败发生在 FD 接管前；返回 Session 后由库负责 FD。当前 Hooks 和识别器均已接入运行时，不再因为非空配置而拒绝启动。

## 职责与资源归属

- 公共根包：DSL、HTTP 模型、Session、ProtocolInspector/ConnectionRule。
- internal.android：TUN、Socket protect、物理网络选择与指定网络 DNS。
- internal.packet：IPv4/TCP/UDP 校验、报文生成、分片与重组。
- internal.stack：纯 Kotlin TCP endpoint、序列空间、重传、窗口和有界环形缓冲。
- internal.transport：TCP 与 UDP 各一个 Selector worker，有界发送/接收邮箱。
- internal.inspection：保留原始前缀的有界探测与多协议元信息。
- internal.diagnostics：线程安全的连接观察快照、有界 UDP DNS 名称关联；不参与路由或流资源管理。
- internal.engine：会话、五元组表、预算、唯一 TUN 写入线程与处理器组装。
- internal.stream：处理器选择、统一 TCP 流端口、有界协程桥接、取消与上游资源归属。
- internal.protocol.http1：HTTP 处理器适配、增量编解码、Body 策略、Hooks 与本地响应。

HTTP 编排已拆分：Http1Connection 负责连接循环和事务清理，HttpExchange 负责单次事务及恢复阶段，HttpResponseWriter 统一上游/本地/错误回退的响应处理。事务结果使用明确枚举，上游响应上下文使用类型封装。TCP/UDP 算法、唯一包循环和 TUN 写线程归属不变；完整兼容性检查见 [REFACTORING.md](REFACTORING.md)。

Session 拥有原始 PFD；TunDevice 仅关闭自己的唤醒 socketpair。包循环唯一操作 TCP 状态和写入 TUN。TcpRelay 通过统一 StreamSession 交换数据，StreamRuntime 选择内部处理器并管理任务与上游资源；HTTP 处理器只接触有序字节流，通过有界 Channel 与包循环交换数据。原始转发保持直接路径。应用侧 TCP 与系统上游 Socket 使用独立序列空间，修改长度无需在原 TCP 序列号上做差量映射。详见 [STREAM_PROCESSORS.md](STREAM_PROCESSORS.md)。

所有上游 Socket 在 connect 前 protect 并检查结果，再绑定物理 Network（API 22+）并连接。DNS 仍在各自 Selector worker 中同步解析；解析延迟会影响该 worker 的其他连接，独立可取消 DNS 是后续缺口。

## 内部资源上限

| 资源 | 当前上限 |
|---|---|
| TCP 活跃传输 | 64；含关闭保留项的连接表总数 320 |
| 单 TCP 接收/发送信用 | 65,535 / 65,536 字节；乱序和未确认分段各最多 256 项 |
| TCP 上游邮箱 | 8 个发送块、4 个接收块，每块最多 16 KiB；另有一个当前写入块 |
| TCP 超时 | connect 10 秒；空闲 300 秒；FIN_WAIT_2/TIME_WAIT/关闭排空 60 秒 |
| 协议探测 | 客户端前缀 65,535 字节、握手后 1 秒、EOF 回退 |
| TCP 处理器桥接 | 每连接入/出各 4 块，块最大 16 KiB，另有当前游标/待发送块；取消未退出任务仍计入 64 个连接名额 |
| HTTP 头部 | 单行 8 KiB、头字段区 32 KiB、128 字段；编码输出也检查上限 |
| HTTP Hooks | 最多 4 个执行线程、64 个待执行任务；过载只终止所属连接 |
| HTTP Body | BodyLimits 配置；每笔事务最多 16,384 个受管分配记录 |
| UDP | 128 活跃连接，空闲 30 秒；128 个发送、64 个回复数据报 |
| IPv4 分片 | 64 组、每组 256 片、4 MiB 内容预算、15 秒绝对超时；重叠整组拒绝 |
| Session 事件 | 回放 16 项 + 额外 48 项，慢消费者丢最旧项 |
| 被动 DNS 诊断 | 128 项待匹配查询、10 秒超时；256 项 IP/名称映射、按 TTL 过期且最多 1 小时；每个快照最多 4 个候选 |

TCP 只在实际上游写入或处理器消费后释放应用上传信用。原始 TCP 下行只在应用 ACK 后补充上游读取信用；处理器下行经独立有界队列与 TCP 发送窗口背压。

Body 预算在聚合、连续数组、解压/重编码和模型副本分配前申请。请求内容保留到响应 Hook 结束；本地响应挂载时计费，修改结果不能用 STREAM 绕过限额。content getter 副本、text 解码暂存按保守容量计费，直到事务结束释放。调用者离线 make、自己创建的临时字符串或故意保留的外部对象不属于可控制的总堆内存。

固定传输暂存、Header、TCP/UDP 队列、系统 Socket 缓冲与分片预算不计入 maxTotalBufferedBytes，它们有独立上限。当前尚未实现对象池和零分配处理路径。过于细碎的输入或反复读取/替换 Body 可能先触发分配记录/保守预算上限。

REJECT 在聚合阶段、最终响应头尚未发出时返回空 Body 的 413（请求过大）、502（响应过大）、503（Session 预算耗尽）、408/504（请求/响应聚合超时），然后用 FIN 关闭客户端并发出 ConnectionError。引擎错误不进入正常 Hooks，也不继续处理流水请求。畸形 HTTP、Hook 异常或 Hook/本地响应结果超限仍复位所属 TCP，不提交部分修改。

DNS 诊断仅解析本 Session 中匹配的 UDP 53 A/IN 查询与 A/CNAME 回答，单回复最多 64 个回答记录、别名链最多 8 步、单名称最多 128 次遍历。畸形或不匹配内容不进入缓存，原数据报仍按原规则转发。HTTP 诊断在 Hooks 前保存原始 authority、方法和无 query/fragment 的路径；新请求会清空旧上游/Flow ID，晚到的旧 connect 回调不能覆盖新请求。详细计数语义和信息缺失条件见 [API.md](API.md#84-连接错误上下文)。

## 验证证据

2026-11-03 连接诊断完成后，148 项 JVM 测试、8 项 API 34 Android 测试通过，无失败或跳过。新增 12 项覆盖：HTTP 原始 authority/无 query 路径、事务隔离、旧 connect 回调、TLS SNI 来源、DNS 查询匹配/CNAME/TTL/容量/畸形输入，以及完整包循环中的 HTTP Hook 失败、DNS 后二进制转发与客户端 RST、UDP 建连失败。现有 TLS 阻断回归也核对 SNI 和路由阶段。真实 localhost Socket 测试验证实际路由 peer，未访问外部业务服务器。

本轮 CI 对应的构建任务及 connectedDebugAndroidTest 完成，库/示例 Lint 无错误（3 / 4 条版本或示例配置提醒）；release AAR、源码包、本地 Maven、Android 测试与示例 APK 构建通过。上游地址读取兼容 minSdk 21。独立 Android 宿主及更新后的诊断文档示例编译通过；既有 Kotlin 2.2.10 / AGP 8.13.2 宿主两个 flavor 各 14 项测试与 debug APK 构建通过。发布到本地 Maven 的 AAR 与当前 release 产物 SHA-256 一致。本轮未安装业务应用 APK、重启其 VPN 或重跑真实业务请求。

此前 TCP 处理器边界整理后，136 项 JVM 测试、8 项 API 34 Android 测试均通过，无失败或跳过。保留原有 124 项测试，新增 12 项处理器/资源归属回归，并在完整 HTTP 413/FIN 测试中连续执行 100 次启停。覆盖选择顺序、注册表快照、背压、半关闭、失败隔离、子协程取消、上游关闭竞态、未退出任务容量，以及非 HTTP 测试协议的完整前缀、路由目标和真实 Socket 收发。修复验证中发现的连接完成与停止快照竞态。

当时与 CI 相同的 145 个 Gradle 任务完成；库/示例 Lint 无错误（1 / 4 条提醒），release AAR、源码包、本地 Maven publication、测试 APK 与示例 APK 构建通过。独立 Android 宿主编译通过；既有 Kotlin 2.2.10 / AGP 8.13.2 宿主的 10 项单元测试和 debug APK 构建通过。该次整理未改变公共 API，未增加 MQTT 修改或公共处理器 SPI，也未重跑真实 VPN 网络或业务请求。内部契约见 [STREAM_PROCESSORS.md](STREAM_PROCESSORS.md)。

此前兼容性简化完成时，124 项 JVM / 8 项 Android 测试及独立/既有宿主编译通过；新增配置工厂/启动重载、默认识别器优先级、lambda 规则、103 后 EOF 回退与 101 后禁止回退均有验证。拆分、兼容性约束及当时结果见 [REFACTORING.md](REFACTORING.md)。

以下保留此前的验证过程和覆盖范围：

JVM 测试覆盖配置/模型、IPv4、UDP、TCP endpoint、真实 localhost Socket、协议探测与 HTTP：

普通 HTTP 提前 FIN 修复后，114 项 JVM 回归通过。此次 GitHub 整理另从仅包含待提交文件的全新副本验证：不包含 local.properties、缓存或既有构建产物，使用环境变量配置 JDK 21/Android SDK，关闭构建缓存；与 CI 相同的 145 个 Gradle 任务全部执行成功。114 项 JVM 测试无失败或跳过，两个模块 lint 无错误（库 1 条、示例 4 条提醒，涉及依赖版本、备份规则、图标及 targetSdk）。release AAR、sources JAR、本地 Maven publication、Android 测试 APK 与示例 APK 均已生成。

坐标 com.dylenpoki:dyltun:0.1.0-SNAPSHOT 已通过独立 Android 宿主与现有应用的集成编译，宿主 Kotlin 2.2.10 / AGP 8.13.2。此前 7 项 API 34 Android 测试通过，本次未重跑设备测试。这些记录是本地验证结果；GitHub CI 配置需在仓库上传后实际运行。

- 丢 SYN-ACK/数据分段、乱序/重复上传、200 KB 下行、序列回绕、窗口耗尽、零窗口探测、重传/半关闭/有效 RST/TIME_WAIT。
- 相同源端口不同目标的隔离、上游失败只复位本连接、TLS 跨 record/分段阻断前零连接、真实本地 MQTT Broker 前缀及 CONNACK。
- TLS SNI 域名集合任意匹配、默认精确/可选子域边界、IDN/大小写规范化、List/Set 与配置快照、非法/空集合拒绝，以及既有单域名调用兼容。
- HTTP 任意分段、重复 Header、chunked/trailers、歧义 framing、gzip 修改、编码/解码阈值、预算耗尽、超时跨 chunk 行的无损回退。
- 本地流水响应零上游连接、一次 response Hook、URL/Redirect/最终拒绝、HEAD/304、100-continue、101 切换、早到最终响应及响应替换。
- error Hook：上游连接/响应 EOF 回退、一次 LOCAL response Hook、失败 peer 关闭、未设置响应时保留原始异常、回退 Body 限额，以及 Hook 错误和最终响应提交后的故障不得回退。
- 普通 GET/POST 完成后不提前半关闭上游，避免服务器返回响应前被中止；完整包循环中验证收到应用 FIN 后上游写端仍保持打开，随后可完整回传 200 KB 改写响应。
- 完整包循环中的 HTTP 本地响应和真实 Socket 请求/200 KB 响应改写；停止期间阻塞 Hook 不会发出晚到结果。
- REJECT 错误码、零正常 Hook/零后续流水请求、完整包循环中的 413/FIN；HEAD 方法改写后的下行语义及 101 协商字段保护。
- 固定长度/chunked/关闭分隔 × identity/gzip 编码超限/gzip 解码超限 × STREAM/REJECT 矩阵；聚合超时、预算不足的两种策略。
- 两条 HTTP 连接竞争同一预算，请求/响应并存、content 副本、gzip 解压与重编码临时容量，以及事务结束后的配额归零。

Android 测试使用 API 34 模拟器，覆盖真实 FD/Unix socketpair、TUN 唤醒/写背压停机、配置与接管边界、Hooks/识别器启动、空 UDP 与来源过滤。

真实 VPN 冒烟（API 34）：198.18.0.1:53 经 protect 和物理网络转到 1.1.1.1:53，TCP/UDP 查询 example.com 均收到 61 字节、RCODE=0。GET /local-health 收到 HTTP 200 JSON，response Hook 的 X-Dyltun Header 验证通过。显式停止后 VPN type=-1，服务列表为空。

构建命令、报告和 AAR 路径见 [BUILDING.md](BUILDING.md)。独立 Android 宿主已通过本地 Maven 依赖编译完整四项 DSL 与全部入口形式；无远程发布。

## 后续验收

先完善极慢读写、长时间 SSE/大上传、并发预算/过载和多 API 真机回归，再完成 ICMP/PMTU、网络切换、可取消 DNS 与长期 FD/CPU/内存/吞吐测量。当前代码具备核心功能链路，尚未达到完整 P2–P6 的发布验收。
