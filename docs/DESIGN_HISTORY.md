# dyltun 设计变更记录

软件功能与修复的变更记录见仓库根目录 [CHANGELOG.md](../CHANGELOG.md)。本文保留设计基线与决策历史。

当前设计基线：v0.5。以下版本均为 2026-11-02 的设计记录，不代表软件发布。

## 0.1.0-SNAPSHOT 开始实现（2026-11-02，未发布）

- 兼容性简化：新增独立 dyltunConfig 工厂与配置启动重载；TLS 域名集合/MQTT 值动作规则补齐默认识别器，显式注册优先；新增 lambda rule。HTTP 编排拆成连接、单次事务和响应输出，保持协议、预算和线程归属契约。具体计划与验证见 [REFACTORING.md](REFACTORING.md)。
- 修复普通 HTTP 请求发送结束后提前 shutdownOutput/FIN 的问题：使用 HTTP framing 标识消息结束，等待响应后关闭本笔上游 Socket，避免 IIS/ARR 等前端提前中止请求并返回 400 或 EOF；101 原始流仍支持真实半关闭。新增 GET/POST 回归，并修正真实 Socket 测试以 HTTP framing 读取请求。
- 为宿主迁移上游错误回退，新增默认空实现 HttpHooks.error(flow, cause)；仅在最终响应提交前的上游 I/O/响应 EOF 故障中允许设置有界本地响应，并保持原始错误诊断。拒绝策略、Hook/协议错误、取消与已提交响应不进入回退。
- 库 namespace 与 Kotlin 包统一为 com.dylenpoki.dyltun，示例使用 com.dylenpoki.dyltun.sample，Maven 坐标更新为 com.dylenpoki:dyltun:0.1.0-SNAPSHOT；接入方需同步修改 imports 与依赖。
- tlsSni 新增 Iterable 域名集合重载，任意匹配执行共享动作；保留单域名重载，配置时复制/规范化/去重并拒绝空组或非法域名。
- 完成最新 AAR 的独立 Android Maven 宿主复验，包括完整 DSL 与外部自定义 SPI。
- REJECT 聚合失败现在安全返回 413/502/503/408/504 并 FIN 关闭，错误不进入正常 Hooks。
- 修正方法改写后的客户端 HEAD 语义，保护 101 的 framing 与协商协议。
- 补齐双连接 Session 预算竞争、请求/响应/副本/压缩容量和超限策略组合矩阵。

- 接通纯 Kotlin TCP endpoint、受保护的上游 Socket、窗口/重传/半关闭和有界 Selector 传输。
- 接通 TLS SNI 阻断、MQTT 本地 Broker 重定向、多协议元信息及前缀重放。
- 接通明文 HTTP/1.1、request/response Hooks、本地 make 响应、gzip、Body 限额/总预算和流式回退。
- 加入 HTTP framing、100-continue、101、早到最终响应、响应替换和停止期间 Hook 隔离测试。
- API 34 真实 VPN 验证 TCP/UDP DNS 与 HTTP 本地响应；示例改用实际 Hooks，文档记录当前资源上限和发布缺口。

以下为同日最初的工程/UDP 阶段记录，其“尚未接通”状态已由上述实现取代：

- 建立纯 Kotlin Android Library、sample、Gradle Wrapper 和固定工具链。
- 实现四块根 DSL、配置快照/依赖校验、Session/FD 生命周期和有界错误事件。
- 实现 HTTP 数据模型、make/本地响应归属及单消息限额校验；尚未连接 HTTP 网络处理。
- 实现 IPv4 报文校验、RST、有限分片重组和 UDP 双向转发、通用连接规则、多响应/空数据报。
- 实现独立 TLS ClientHello/MQTT CONNECT 识别器；流配置尚未接通时显式启动失败。
- 加入 JVM 和 Android 测试、单地址 UDP 示例、BUILDING/IMPLEMENTATION 文档，保留完整首版验收缺口。

## v0.5 文档重整

- README 展示 hooks、bodyLimits、protocols、routing 的统一完整示例。
- 新增 API.md，集中维护公共配置签名和默认值，避免分散定义遗漏。
- DESIGN.md 只保留当前架构和行为；ROADMAP.md 使用 P0–P6 对齐交付与验收。
- 历史决策迁至本文件，不再和当前定义混写。
- 明确接口设计、阶段交付、未实现状态和待细化 SPI，不引入新的根配置项。
- 明确拒绝优先、Redirect 选择、失败清理状态及同步 Hook 的关闭限制。

## v0.4 补齐协议扩展配置

- 将 protocols/routing 补入根 Builder，纠正只有 hooks/bodyLimits 的定义被称为完整入口的问题。
- 补充 install、tlsSni、mqtt、rule 和 TCP 动作签名。
- 规则依赖的识别器缺失时，要求 FD 接管前报错，不允许静默失效。

## v0.3 统一 Kotlin DSL

- 推荐 dyltun(fd) { hooks = ...; bodyLimits = ... }，hooks 默认 null。
- 保留 dyltun(fd, httpHooks) 便捷重载；撤回带第二配置来源的三参数形式。
- 配置执行后构建不可变快照，明确同名属性与重载问题。

## v0.2 确定纯 Kotlin 和内容边界

- 用户确认只使用 Kotlin；撤回 lwIP/JNI 和 Netty 候选，采用独立 Kotlin TCP/IP 后端与 HTTP 编解码。
- 将 request 中设置 flow.response / HttpResponse.make 确认为核心功能。
- 定义方向 Body 限额、编码/解码体积、Session 总预算、聚合时间和 STREAM/REJECT。
- 明确 FD 所有权、关闭、未消费请求体和本地响应行为。

## v0.1 初步架构

- 评估 Android Library、TCP/IP 后端、HTTP Hooks 和协议扩展分层。
- native 后端和 Java HTTP codec 曾是候选，后续已被纯 Kotlin 要求取代；不是当前依赖。
