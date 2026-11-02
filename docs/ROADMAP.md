# dyltun 实施与验收

设计基线：v0.5。工程/API、IPv4 TCP/UDP、HTTP/Body/本地响应及 TLS/MQTT 路由已接通。下方逐项区分已验证行为与发布门槛；证据及限制见 [IMPLEMENTATION.md](IMPLEMENTATION.md)。P2–P6 尚未整体通过。

签名与默认值见 [API.md](API.md)，行为依据见 [DESIGN.md](DESIGN.md)。复选框仅在实现和相关验证通过后勾选。阶段可以交错开发，但不得跳过依赖门槛宣称已支持功能。

## 阶段与交付边界

| 阶段 | 交付 | 依赖 |
|---|---|---|
| P0 | 工程、完整根 DSL、配置/所有权契约 | 无 |
| P1 | TUN、报文、有限预算和物理网络基础 | P0 |
| P2 | 纯 Kotlin TCP/UDP 转发 | P1 |
| P3 | HTTP/1.1 编解码与 Hooks | P2 |
| P4 | Body 配额、流式策略、本地响应 | P3，可与 P3 交错实现 |
| P5 | 协议 SPI、TLS SNI/MQTT 扩展验证 | P2 和 P0 扩展契约 |
| P6 | 宿主接入、实测和发布 | 所声明功能对应阶段通过 |

P0 必须完整表达 hooks/bodyLimits/protocols/routing；P5 才交付具体 TLS/MQTT 行为。分阶段实现不能成为文档省略根配置的理由。首版核心完成以 P2–P4 验收为准；完整扩展设计还需 P5 证据。

## P0 工程与公共契约

- [x] 建立 :dyltun / :sample、Kotlin DSL、Wrapper 和锁定的工具链，记录 minSdk。
- [x] 核对 Kotlin 源码约束及依赖，不引入自定义 Java/native 协议栈。
- [x] 实现 API.md 中四个根入口、便捷重载和不可变配置快照。
- [x] 增加独立 dyltunConfig 工厂、配置启动重载、内置值动作规则与 lambda rule，保留旧调用形式。
- [x] 实现协议注册/规则容器、依赖/数值/动作校验；没有执行能力时不能静默忽略规则。
- [x] 定义 Session 状态、FD 接管、close/awaitClosed 和单连接错误观察契约。
- [x] 定义 HTTP Flow/Headers/make/BodyLimits，明确空内容与不可用内容。
- [x] 定义内部后端、时钟、传输、预算、缓冲所有权接口。
- [ ] 在实现扩展前细化 ProtocolInspector/ConnectionRule 上下文、结果及通用流/数据报 SPI。

验收：无 Hooks、显式 null、内联/独立 Hooks、两参数简写及四块组合的调用可编译，无重载歧义。配置失败不接管 FD；Builder 逃逸修改不影响 Session。注册阶段可用有行为的测试扩展验证容器，不以占位模块冒充协议功能。

## P1 TUN、报文与资源基础

- [x] 实现 TUN 有界读写、poll/唤醒和停机，避免忙循环和并发写入。
- [x] 实现 IPv4/TCP/UDP 头、长度、选项和校验和检查。
- [x] 实现有界分片重组、重叠拒绝、丢片和超时处理。
- [ ] 确定内部 Header/探测/分片/传输预算，分配前申请配额。
- [x] 实现 Socket protect、物理网络选择和指定网络 DNS（TCP/UDP API 34 VPN 冒烟；可取消 DNS 仍待完善）。

验收：有效/畸形报文边界、短包、坏校验和、MTU、分片和预算测试通过。反复启停不积累 FD、等待线程和缓冲。protect 失败不继续发包。

## P2 可靠 TCP/UDP

已有 TCP endpoint、受保护的系统 Socket 和 UDP 链路；ICMP/PMTU、网络变化、多 API 真机和长期资源验收仍缺失。

- [x] 实现完整五元组连接表、握手和独立序列空间。
- [x] 实现 ACK/回绕、重复/乱序、有界缓冲和接收/发送窗口。
- [x] 实现背压、零窗口探测、RTO/RTT、超时/快速重传、基本拥塞控制。
- [x] 实现 FIN 半关闭、RST、TIME_WAIT、上游失败和主动有效 RST。
- [ ] 实现 UDP 多响应、会话超时、MTU/DF、IPv4 分片及必要 ICMP 错误。
- [x] 空 Hooks 时通过受保护的上游 TCP/UDP 正确转发。

验收：用模拟链路/时钟验证丢包、乱序、重复、窗口耗尽和重传；真机/模拟器验证双向流、半关闭、多连接、UDP/DNS。相同源端口不同目标不能串流。慢读内存有界，网络切换/撤销正常关闭，无 VPN 递归。单次网页访问不算完整验收。

## P3 HTTP/1.1 与 Hooks

- [x] 实现与 I/O 分离的 Kotlin 增量解析/编码，覆盖任意 read 边界。
- [x] 覆盖 Content-Length、chunked/trailers、关闭分隔、持久客户端连接和响应关联。
- [x] 处理 HEAD/204/304、1xx、Expect: 100-continue。
- [x] 拒绝有歧义 framing、冲突长度和非法 Header 换行。
- [x] 实现受限 Hook 执行器、顺序、异常/晚到结果隔离。
- [x] 实现 URL 改目标、Redirect 优先级和拒绝优先。
- [ ] 实现按最终 Endpoint/网络隔离的上游连接池（当前每事务独立 Socket）。
- [x] 实现 101 后交回原始流；首条 CONNECT 在探测阶段透明回退。

验收：头部跨 read、多条消息同 read 和长度变化后的持久通信正常。Hook 不占主线程/状态机线程，不同连接互不污染。非法消息只关闭所属连接，拒绝规则不被 Forward 或 Hook 绕过。

## P4 Body、流式与本地响应

- [x] 实现 API.md 的方向限额、聚合时间和 Session 总预算。
- [x] 实现 identity/gzip、BUFFERED/STREAMING/OPAQUE 和内容修改。
- [x] 实现已知/未知长度超限、解码超限、资源不足和超时路径。
- [x] 实现无损前缀重放与流式 framing 修改保护。
- [x] 实现 make 文本/字节/空响应、本地分支和一次 response Hook。
- [x] 检查本地初始内容及 Hook 修改后的限额，规范化长度/编码/验证字段。
- [x] 实现未消费请求体的安全关闭、被替换上游响应的清理。
- [x] 为 REJECT 补齐安全可发时的 413/502/503/408/504 引擎错误响应，FIN 关闭并报告事件。

验收：

- [x] 各字节阈值覆盖 limit-1、limit、limit+1；时间使用可控时钟。
- [x] 多连接累计预算、请求/响应并存内容、副本和压缩临时缓冲均计费。
- [x] 固定长度/chunked/关闭分隔、identity/gzip 编码/解码超限矩阵，以及聚合超时和预算不足的 STREAM/REJECT。
- [x] 本地响应/Hook 结果不能通过 STREAM 绕过限额；不截断坏包。
- [x] 上游探针证实本地请求零发送，request/response 顺序、次数和 LOCAL 来源正确。
- [x] 完整请求的本地响应可复用客户端连接；未消费 Body 不被当作下一请求。
- [x] null/空 Body 不混淆；不支持编码不假装解压。
- [ ] 覆盖大上传、SSE、100-continue、慢读、停止时回调和晚到结果。

P3/P4 均验收后才声明支持 HTTP Body 修改及本地响应。

## P5 协议扩展验证

- [x] 开放识别/规则 SPI，并验证独立 Maven 宿主可实现（尚无远程发布）。
- [x] 实现有界探测、未知回退和完整前缀重放。
- [x] 实现 TLS ClientHello/SNI inspector，集成跨分段/record 阻断前零连接验证。
- [x] 实现 MQTT inspector、到本地 Broker 的重定向和双向转发。
- [x] 实现 install/tlsSni/mqtt/rule 与 TCP 动作；验证 UDP 使用 Drop 而非 RST。
- [x] 验证缺失识别器、重复 ID、无效动作在 FD 接管前失败。

验收：两个扩展不修改 TCP/IP 或 HTTP 核心；向原目标发送应用数据前完成阻断/改目标。未知和服务端先发协议继续工作；不宣称 ECH、无 SNI、QUIC、MQTTS 透明可识别。

## P6 宿主与发布

- [x] 完成可读的 VpnService 示例，覆盖授权、通知、初始化、失败和关闭（单地址路由，TCP/UDP DNS 与 HTTP 本地响应冒烟通过）。
- [x] 验证独立 Android 宿主通过本地 Maven 接入。
- [x] 明确 IPv4/IPv6、MTU、路由、未知协议及关闭限制。
- [ ] 测量真机长时运行、内存、FD、CPU、吞吐和延迟，包括空 Hooks 路径。
- [x] 检查公开签名、README 四块组合示例和实现一致。

发布时给出已通过的阶段证据及未支持范围，不能把设计示例当作已经交付的能力。
