# TCP 处理器边界

本轮整理内部处理器选择、字节流与生命周期接口。保留全部公共 Kotlin API、TCP/UDP 行为、HTTP Hooks 与 TLS/MQTT 路由，不增加 MQTT 消息修改或新的公共处理器 SPI。

## 实施边界

- TcpRelay 只维护 TCP endpoint、探测/路由和统一 StreamSession 的数据交换，不直接判断 HTTP 或操作 HTTP 桥接。
- StreamRuntime 按识别结果选择内部 StreamHandler；没有匹配处理器时使用 RawStreamSession。
- HTTP 通过 HttpStreamHandler 接入，HTTP 消息语义、Body 预算和 error Hook 恢复条件仍由 HTTP 模块维护。
- 处理器获得有序输入/输出、连接信息、探测结果、候选目的地、受保护的上游连接能力和事件报告入口。
- StreamRuntime 管理协程取消、有界桥接、输出结束与处理器级关闭；每连接拥有通过上下文打开的上游资源，正常结束、失败和停止都回收。
- 原始 TCP 保留直接转发：上传按实际上游写入释放信用，下行按应用 ACK 释放读取额度。处理器路径使用独立有界队列，不改变 TCP 算法。
- UDP 保留独立数据报路径，不使用 TCP 的有序字节流接口。

## 选择规则

1. TcpEndpoint 先提供有序客户端字节；ProtocolProbe 只查看有界前缀，不从流中消费它。
2. ProtocolDetection 保存公开识别器产生的元信息，并单独保存内置 HTTP/1.1 识别标记，避免占用或冲突于公开 ProtocolId。
3. 路由先确定拒绝或候选目的地。拒绝连接不启动处理器，也不打开上游。
4. StreamRuntime 按当前 Session 注册表顺序选择首个匹配处理器，只选择一次；matches 不执行 I/O 或修改连接状态。
5. 没有处理器匹配时返回 RawStreamSession。当前只有 hooks 非空时安装 HttpStreamHandler；TLS/MQTT 按原始 TCP 流转发。

## 内部契约

| 类型 | 职责与约束 |
|---|---|
| StreamHandler | Session 级实例；多个连接可并发调用 handle，共享状态须并发安全。requestStop 停止共享工作，close 在任务退出后释放共享资源 |
| StreamContext | 每连接上下文；connection 保留原始五元组，destination 是连接路由目标，detection 是完整识别结果 |
| ByteSource / ByteSink | 有序字节流，每方向一个读者/写者，可双向并发；read 的 null 表示 EOF，write 在背压下挂起；提交后不得修改数组 |
| StreamPeer / StreamConnector | 按需打开受保护的上游；finish 排空已排队写入后只半关闭写端，读端仍可用；close 结束整个 peer |
| OwnedStreamConnector | 每连接追踪 peers；显式关闭幂等，未关闭的 peer 在连接结束时回收；打开与关闭竞态中的晚到 peer 也会被关闭 |
| StreamSession | 包循环的非阻塞端口：提交输入、确认消费、保留/提交输出、EOF、完成和失败；处理器不持有此端口 |

客户端输入、处理器输出和上游读取块最大 16 KiB。处理器上下文的 sink 每次提交不得超过该大小；上游 sink 可接受更大的数组，由 ForwarderStreamConnector 切块并背压发送。下游输出在 TCP 发送缓冲接纳前保留，不能因暂时写不进去而丢失。

处理器返回表示处理结束；运行时关闭其上游、关闭输出并发布完成。finishOutput 只结束向应用发送的一半，处理器仍可读取应用后续输入。HTTP 普通请求结束不调用上游 finish；HTTP framing 已表示消息结束，只有升级后的原始流在真实 EOF 时半关闭。

## 生命周期与错误

- 单连接任务使用父作用域的结构化并发；handle 内启动的子协程应归该作用域，禁止另建脱离连接的后台任务。
- 单连接异常保存为 failure，由包循环终止该 TCP 并报告 ConnectionError；SupervisorJob 隔离其他连接。可恢复的协议错误由处理器自行处理，HTTP 的 error Hook 资格仍在 HTTP 模块内判断。
- 正常结束、失败和主动关闭都回收所有已登记上游；一个资源关闭失败仍继续尝试其他资源，保留原始异常及清理异常。
- Session 停止先禁止新工作，取消任务、唤醒有界 I/O 并关闭上游，然后等待任务/子协程结束，最后关闭处理器共享资源。
- 同步 Hook 无法安全强杀；未返回时仍保留 Body 及连接名额，晚到输出丢弃。awaitClosed 可能等待该 Hook，网络已先停止。
- 连接登记、移除、容量检查和停止快照共享同一锁；避免最后一个任务完成时与停止快照发生竞态。连接上限为 64，已取消但尚未退出的处理器仍计入。
- pending 输出与 TCP 状态只由包循环修改；其他线程通过有界队列和原子/可见性状态通信。UDP 的生命周期和队列保持独立。

## 接入后续协议

新增 TCP 内容协议时，先实现有界 ProtocolInspector 生成元信息，再在对应 internal.protocol 包中实现 StreamHandler。由 IpPacketRuntime 的组装入口创建每 Session 的处理器注册表；无需给 TcpRelay 新增协议分支。协议模块自己管理消息 framing、编解码、内容限额和业务修改；通用层只管理流与任务。

公共 API 保持 dyltun(fd, config)、HttpHooks 和现有 routing。当前内部边界不承诺外部二进制兼容；公共处理器 SPI 应在实际业务协议验证后再确定。UDP 后续单独定义数据报处理契约，保留一次收发一个数据报的边界和独立限额。

## 验证

新增测试覆盖处理器选择顺序和注册表快照、原始回退、入/出队列背压、输出提交、半关闭、失败隔离、上游回收、取消子协程、晚到输出、关闭竞态与未退出任务容量。完整包循环使用测试协议验证分段探测前缀完整重放、路由目标和真实 localhost Socket 收发；HTTP 413/FIN 连续 100 次启停覆盖完成/停止竞态。

执行结果（2026-11-02）：

- 136 项 JVM 测试、8 项 API 34 Android 测试通过，无失败或跳过；原有 124 项保留，新增 12 项回归。
- 完整 HTTP 413/FIN 场景连续 100 次启停通过；验证期间修复了最后一个任务退出与停止快照的竞态。
- 与 CI 相同的 145 个 Gradle 任务完成，库/示例 Lint 无错误，保留 1 / 4 条既有提醒；release AAR、源码包、本地 Maven、示例与 Android 测试 APK 构建通过。
- 独立 Android 宿主编译通过；既有 Kotlin 2.2.10 / AGP 8.13.2 宿主的 10 项测试和 debug APK 构建通过。
- 本轮未重跑真实 VPN 或业务服务器请求；公共 API 与当前协议范围保持不变。

其他验证记录与限制见 [IMPLEMENTATION.md](IMPLEMENTATION.md)，流程图见 [FLOW.md](FLOW.md)。
