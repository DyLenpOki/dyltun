# dyltun 设计基线

版本：v0.5。日期：2026-11-02（Asia/Shanghai）。状态：实现进行中；本文保留完整目标行为，当前实际能力见 [IMPLEMENTATION.md](IMPLEMENTATION.md)。

公共签名、默认值和完整 DSL 以 [API.md](API.md) 为准；阶段进度以 [ROADMAP.md](ROADMAP.md) 为准；历史变化单独保存在 [CHANGELOG.md](CHANGELOG.md)。本文件只定义当前架构和行为，不混入已撤回方案。

## 1. 目标、范围与交付

| 要求 | 当前决定 |
|---|---|
| 其他 Android 应用易于接入 | 一个 :dyltun Android Library，对外 Kotlin API；另有 :sample 演示宿主 |
| 只使用 Kotlin | 库源码和构建脚本使用 Kotlin/Kotlin DSL；允许 Android/JDK API、Kotlin 标准库和协程，不引入自定义 Java/native 协议实现 |
| 简洁初始化 | VpnService 扩展 dyltun(fd) { ... }，隐式取得 protect 能力 |
| 完整配置面 | 同一个 Builder 包含 hooks、bodyLimits、protocols、routing |
| 首版核心功能 | IPv4 TCP/UDP 转发、明文 HTTP/1.1 修改、HttpResponse.make 本地响应 |
| 后续协议能力 | 开放协议识别/处理和连接策略接口；用 TLS SNI 阻断、MQTT 重定向验证 |
| 可维护和有界资源 | 状态归属明确、预算前置、背压、分阶段正确性验收 |

工程最低 API 为 21，工具链见 [BUILDING.md](BUILDING.md)。工程/API、IPv4 TCP/UDP、HTTP 流处理与 TLS/MQTT 连接规则均已接通。完整发布验收仍需多 API 真机、网络变化和长期资源测量；具体证据见 IMPLEMENTATION.md。

首版核心不包含 IPv6 完整处理、通用 ICMP 转发/Ping、HTTPS 内容解密、HTTP/2 或 HTTP/3 修改。未知应用协议默认透明转发；未支持的 IP 地址族应由演示宿主明确配置处理，不能宣称已受同等规则保护。

## 2. 模块边界与数据路径

先使用一个 Library 内的包和 internal 可见性隔离职责，不为每个职责新建 Gradle 模块。

| 区域 | 职责 |
|---|---|
| api / spi | 公共配置、Session、HTTP 模型、协议与路由扩展契约 |
| internal.android | TUN FD、Socket protect、物理网络和 DNS 适配 |
| internal.packet | IP/TCP/UDP 头、长度、校验和、分片 |
| internal.stack | Kotlin TCP endpoint、UDP 会话、回包生成 |
| internal.engine | 启停、调度、连接表、预算和任务归属 |
| internal.inspection | 有界协议探测、元信息和前缀重放 |
| internal.routing | 放行、重定向、RST/Drop 决策 |
| internal.protocol.http1 | HTTP/1.1 编解码、Hooks、本地响应 |
| internal.transport | 上游 TCP/UDP I/O、连接与物理网络解析 |

实现依赖公共模型和小接口，由引擎组装。HTTP 不直接操作 TUN 或 TCP 序列号，协议插件不依赖连接表内部结构。

当前公共 API/SPI 位于根包；实现已有 internal.android / packet / stack / engine / inspection / protocol.http1 / transport。路由契约及评估器在根包 RoutingConfig.kt 中。IpPacketRuntime 组装包循环、TCP/UDP 与 HTTP；不使用 native 后端。

```text
TUN 出站 IP 包
  → IP 校验/解析/必要分片重组
  → Kotlin TCP endpoint 或 UDP 会话
  → 有序 TCP 字节流或有边界的 UDP 数据报
  → 协议识别、连接规则、协议处理
  → 受保护的上游 Socket

上游数据 / HTTP 本地响应
  → 协议处理与编码
  → TCP endpoint / UDP 回包
  → IP 包与校验和
  → TUN 写入
```

应用侧由 Kotlin TCP endpoint 终止 TCP，上游由系统 Socket 协议栈终止另一条连接。HTTP 长度变化由独立序列空间吸收，不对原 TCP 流做长度差映射。上游 Socket 已提供有序流，不再次按 TCP 序列号重组。

## 3. 入口、FD 所有权与关闭

1. 同步执行 DSL、检查数值和规则依赖，再构建不可变配置。
2. 配置失败时抛异常，不接管 FD，调用者负责关闭。
3. 成功返回 Session 后转交 FD；调用者不得继续读写、复用或关闭。
4. 转发异步启动；接管后的启动失败进入 Failed，并回收已创建资源。
5. close 幂等且非阻塞：禁止新工作、唤醒等待、关闭网络资源并停止任务；awaitClosed 等待清理结束。
6. 失败原因不会因清理被覆盖为普通成功。重新建立 VPN 时先停止旧会话，复用资源前等待其完成清理。

宿主负责授权、前台服务、Builder、地址/MTU/DNS/路由、应用排除，以及在停止、onRevoke、onDestroy 时关闭 Session。库不代建宿主的 VPN 服务。

所有上游 Socket 在 connect/send 前 protect，并检查结果；失败即关闭并报告。域名解析使用选定物理 Network，避免解析和转发回到 VPN。网络变化不承诺迁移已建立 TCP；失败连接有界关闭，新连接使用新的可用网络。

TUN 使用平台 Os.poll 和非阻塞 Unix datagram socketpair 唤醒，不忙轮询、不把 FileChannel 注册到 Selector。Selector 只处理可选择的 Socket channel。宿主使用 Builder 默认非阻塞 FD 或显式 setBlocking(false)；API 21–29 没有公开的 fcntlInt 供库切换外部 FD 标志。

## 4. 协议识别与路由执行

### 4.1 职责与前置条件

- hooks 处理 HTTP 消息；protocols 注册识别器；routing 根据元信息控制连接。
- HTTP Hooks 非空时自动启用内置 HTTP/1.1 路径。
- 额外识别器只读取有界客户端前缀，返回匹配/不匹配/需要更多数据；数据完整重放给后续处理器。
- 探测必须有时间和字节上限。未知协议默认转发，不能无限等待服务端先发数据协议的客户端首包。
- 规则依赖的识别器必须在配置完成后可用；缺失依赖在 FD 接管前报错，不能让规则静默失效。

### 4.2 决策顺序

1. 尽早检查可由连接信息决定的拒绝规则。
2. 对需要内容元信息的规则完成有界探测；在向原目标发送应用数据前决定阻断或改目标。
3. 同阶段命中的拒绝动作优先：TCP Reset / UDP Drop 是终止决策，不被 Forward/Redirect 或 Hook 覆盖。
4. 没有拒绝时，按显式注册顺序选择首个匹配的 Forward/Redirect；无匹配默认 Forward。拒绝检查不因较早的 Forward 而短路。
5. HTTP 请求先执行 request Hook。若走上游，候选 Endpoint 为原目标 → URL authority 改写 → 显式 Redirect；Redirect 优先。
6. 对最终候选补做适用的拒绝检查；最多应用一次 Redirect，不递归重定向。
7. 最终 Endpoint 确定后才发送该请求。发出数据后不能悄悄迁移同一原始流。

已拒绝连接不进入 HTTP Hooks。本地响应不能撤销已经产生的拒绝。HTTP 持久连接内，目标不同的请求可以使用不同上游连接；原始流协议的 Redirect 则按连接生效。

未改变目标时沿用原 IP/端口；单独改 Host 不隐式改网络目标。修改 URL authority 同步规范化 Host，并触发最终目标检查。当前每笔 HTTP 事务单独连接上游并关闭其 Socket，客户端持久连接仍可继续处理下一请求。未来连接池必须按实际 Endpoint、网络和协议参数隔离。首版不通过 URL 改成 https 自动提供 TLS 代理。

### 4.3 扩展验证场景

| 扩展 | 识别信息 | 动作与边界 |
|---|---|---|
| TLS SNI 阻断 | 跨 TCP 分段/TLS record 的 ClientHello 中可见 SNI | 域名集合中任意完整域名/子域匹配后有效 RST；不解密 TLS |
| MQTT 重定向 | CONNECT 等协议特征 | 改连本地 Broker，重放前缀并双向转发；不只按端口猜测 |

ECH、无 SNI、QUIC 和 MQTTS 密文不能承诺提取真实目标域名/MQTT 内容；需要明确未知策略，默认仍遵守未知协议转发规则。通用双向协议处理 SPI 保留，具体签名在扩展实现前细化。

## 5. HTTP Hooks 与本地响应

### 5.1 正常和本地响应路径

```text
有效 HTTP 请求 → Body 策略 → request(flow)
  ├─ response 为 null：取得上游连接 → 发送请求 → 接收响应 → Body 策略
  └─ response 非 null：校验本地响应；该请求零上游发送
                         ↓
                  response(flow)，至多一次
                         ↓
                 最终校验/编码 → TCP endpoint → TUN
```

本地响应来源为 LOCAL；不为该请求新建上游连接。本地响应仍执行一次 response Hook，不递归调用。

普通 HTTP 上游请求以 Content-Length/chunked 等 framing 标识结束，发送完后不得主动 shutdownOutput/发送 FIN；某些 HTTP 前端会将其视为客户端中止请求。当前每笔事务独占上游 Socket，等待响应处理结束后再关闭。101 升级后的原始双向流按真实输入 EOF 进行半关闭。

上游 I/O 或响应提前 EOF 在最终响应提交前失败时，可进入 error(flow, cause) Hook。若它设置 response，则按 LOCAL 回退执行一次 response Hook，发送后关闭客户端连接，同时保留原始连接错误事件。未设置 response 时保留原始失败。路由拒绝、协议/Body 校验、客户端错误、Hook 异常、取消和已提交最终响应后的故障不能触发此回退；不得自动重发已可能提交给上游的业务请求。

本地响应初始内容和 response Hook 修改后的结果都检查响应限额。response Hook 不能把 response 设回 null 并重新发请求。若替换尚未消费完的上游响应，要关闭或有界排空旧上游，不能错误复用剩余数据。

请求 Body 已消费完且持久连接条件成立时，本地响应可保持客户端连接；未消费完时默认发送本地响应后关闭，不能把剩余 Body 当下一条请求。流水请求仍遵守响应顺序，关闭时不承诺继续处理后续请求。

### 5.2 回调与模型契约

- 一个 Flow 对应一次 HTTP 事务；正常完成时 request/response 各一次。解析失败或 Hook 失败不伪造正常完成。
- 同一客户端连接按序处理，流水请求不无界排队。不同连接可并发调用同一 Hooks 实例。
- Hooks 在受限工作执行器运行，不占主线程或 TCP 状态机线程；回调返回后不得异步修改 Flow。
- Hook 异常终止当前连接并报告，不发送修改到一半的数据；其他连接继续运行。
- 超时/停止后不提交晚到结果，不提前复用仍被回调引用的对象。同步阻塞代码不能被安全强制终止，可能延迟 awaitClosed；close 仍需先停止网络活动。
- content 使用替换语义，getter 副本与编解码临时内容纳入受管预算。不可用 Body 和空 Body 必须区分。
- 头字段保留重复值；解析和写入拒绝非法换行及有歧义的 framing。

### 5.3 HTTP/1.1 必须处理的语义

增量解析独立于 I/O，允许头部跨多个 read、一条 read 包含多个消息。覆盖 Content-Length、chunked/trailers、关闭分隔响应、持久连接、HEAD/204/304、1xx 和 Expect: 100-continue。

要完整缓冲请求体时，引擎在校验头部后先回复 100，再读取 Body；转发移除已消费的 Expect，100 不触发最终 response Hook。非预期中间状态按协议处理，不能等待造成死锁。

修改后按实际字节计算长度并规范化编码，不同时发送冲突的 Transfer-Encoding/Content-Length。内容变化后移除或重新计算失效的摘要/缓存验证字段。HEAD/204/304 的内容与长度遵循各自规则；即使 request Hook 修改方法，下行仍尊重客户端原请求的 HEAD 语义。

101 Upgrade 后切回有序原始流，不把后续 WebSocket 数据当 HTTP。响应 Hook 不能为 101 插入消息 framing 或改变已协商的 Upgrade 协议。CONNECT 首版回退为到原目标的透明连接，不提供新的通用 CONNECT 服务，不执行该连接的 HTTP 内容修改。HTTP/1.0、HTTP/2、HTTP/3、TLS 不进入 HTTP/1.1 修改器。

## 6. Body 限额与流式行为

数值和默认值只维护在 [API.md](API.md#5-body-配置与默认值)。以下规定执行语义。

### 6.1 预算与内容状态

- 分配、扩容、复制、解码前申请配额；请求内容仍被响应 Hook 使用时继续计费。当前按事务保守保留分配配额，getter 副本和文本暂存直到事务结束才释放，可能比单个 Body 字节阈值更早触发总预算。
- 总预算是 Session 级的受管 Body 容量，不是进程总堆上限，也不限制调用者离线构造响应/保存对象。
- BUFFERED：内容完整且可解码，可修改 content/text。
- STREAMING：不完整聚合，前缀重放后持续转发；content/text 为 null。
- OPAQUE：不支持的 Content-Encoding 保留原内容，content/text 为 null；仍受相应缓存/流式预算约束。
- 首版内容编码支持 identity 和 gzip；未知编码不能假装已解压。

### 6.2 超限决策

| 触发情况 | STREAM | REJECT |
|---|---|---|
| 已知/未知长度内容超过体积上限 | 不再聚合，保留前缀转流式 | 拒绝当前消息 |
| gzip 解码后超限 | 停止解码，保留原编码流转发 | 拒绝当前消息 |
| Session 预算不足 | 使用受限流式路径，不增加聚合缓存 | 拒绝当前消息 |
| 聚合时间超限，包括小流量长流 | 转流式，不无限等待结束 | 报聚合超时 |
| Hook 修改结果超限 | 报错并终止，不能流式绕过 | 同左 |
| make 生成的本地响应超限 | 报错并终止，不能截断 | 同左 |

最终头部在对应 Hook 返回并校验前不发送。STREAM 路径中，已知超限时在解析头部后调用一次 Hook；读取中超限时切换模式，调用一次 Hook 再重放缓存。REJECT 路径不为被拒绝消息调用正常内容 Hook。需要继续透传的原始编码前缀不能提前丢弃。

流式 Hook 可修改普通头部，但不能修改 Body 或任意改变 Content-Length/Transfer-Encoding/Content-Encoding；否则报契约错误。REJECT 在安全可发错误响应时使用请求过大 413、响应过大 502、资源耗尽 503、聚合超时 408/504，然后关闭；引擎错误不递归进入 Hooks。无法安全发送时直接终止并报告。

## 7. Kotlin TCP/IP 与资源模型

TCP/IP 后端位于 internal.stack / packet，由 IpPacketRuntime 和 TcpRelay 组装，通过 PacketDevice、StreamConnector 和数据回调连接平台 I/O。可靠 TCP endpoint 必须独立验收，不能用最小握手示例或一次网页访问代替正确性证明。

| 部分 | 必须具备的能力 |
|---|---|
| TCP | 完整五元组、握手、序列号回绕、ACK 校验、乱序/重复、窗口、背压、零窗口探测、RTT/RTO、重传、基本拥塞控制、MSS/合法选项、FIN/RST/TIME_WAIT |
| IPv4 | 可变头长、长度和校验和、有限分片重组及重叠/超时策略 |
| UDP | 数据报边界、五元组映射、多响应、空闲超时、MTU/DF 与必要 ICMP 错误 |
| 输出 | 正确 IP/传输头和校验和；不截断产生坏包 |

状态由单一执行上下文拥有，时钟可注入。网络 I/O、状态机和 Hooks 用有界队列连接；慢上游必须反馈到 TCP 接收窗口。每 Session 独立持有连接表、任务、预算和注册表，不使用 GlobalScope/全局连接表。

头部、探测前缀、分片、TCP/UDP 缓冲和连接数量有独立的内部有限预算，当前数值记录在 IMPLEMENTATION.md，不增加公开根 DSL 字段。错误通过 DyltunSession.events 观察，终止原因保留在 state。

停止时禁止新连接、唤醒等待、关闭 I/O，再清理任务和缓冲；晚到事件带连接代次校验。默认不记录 Body。空 Hooks 且无内容策略时不启动完整 HTTP 解析或 Body 聚合。

## 8. 验证与变更规则

公共签名变更先更新 API.md；行为变更更新本文件；对应验收更新 ROADMAP.md；设计变化记入 CHANGELOG.md。不能只改某个示例造成入口不一致。

阶段门槛依次为：工程与契约、报文基础、可靠 TCP/UDP、HTTP 编解码、Body/本地响应、协议扩展验证、宿主集成。性能承诺在真机测量之后再写入文档。

参考：[VpnService](https://developer.android.com/reference/android/net/VpnService)、[Network](https://developer.android.com/reference/android/net/Network)、[RFC 9293 TCP](https://www.rfc-editor.org/rfc/rfc9293.html)、[RFC 6298 RTO](https://www.rfc-editor.org/rfc/rfc6298.html)、[RFC 5681 拥塞控制](https://www.rfc-editor.org/rfc/rfc5681.html)、[RFC 9110 HTTP 语义](https://www.rfc-editor.org/rfc/rfc9110.html)、[RFC 9112 HTTP/1.1](https://www.rfc-editor.org/rfc/rfc9112.html)。
