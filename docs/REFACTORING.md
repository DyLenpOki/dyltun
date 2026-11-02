# 兼容性简化

本轮保留 IPv4 TCP/UDP、HTTP/1.1、Body 配额、本地响应、错误回退、TLS SNI/MQTT 路由与所有旧入口。

## 配置与 API

- 增加独立的 dyltunConfig 工厂和 dyltun(fd, config)，配置可在 VpnService 之外构造。
- 保留行内配置、可空 Hooks 简写、旧路由动作 DSL 和自定义 SPI。
- 增加 action 参数形式的 TLS 域名集合和 MQTT 规则；只有新入口自动补齐对应默认识别器，宿主显式注册优先。
- 增加 lambda 形式的自定义 rule，保留 ConnectionRule 接口。
- 保留配置快照、拒绝优先和 FD 接管前校验；无效 TCP 动作立即拒绝。

## 内部边界

- Http1Connection 只管理有序事务循环和事务资源作用域。
- HttpExchange 编排请求准备、本地/上游分支、错误回退及升级。
- HttpResponseWriter 统一响应 Hook、Body 准备、framing、输出及引擎拒绝响应。
- 使用 ExchangeResult 表达复用、关闭或升级；使用明确的响应阶段控制回退资格。
- 本地和上游响应输入通过类型区分，不允许头部与 Body 上下文出现无效的可空组合。

## 必须保留的行为

- 单一包循环拥有 TCP 状态和 TUN 写入；本轮不调整 TCP/UDP 算法或线程归属。
- HTTP 上传与响应读取继续并发；普通请求完成不发送提前 FIN。
- 本地响应零上游连接，正常 response Hook 每笔只执行一次。
- 100-continue、早到最终响应、101、HEAD/204/304、流水请求及未知协议回退不变。
- 已开始输出最终响应、Hook 错误、协议错误和 Body 拒绝不进入上游错误回退。
- 配额、超时、流式回退、关闭时取消和资源所有权不变。

## 验收

执行库 JVM 回归、库与示例 Lint、Android 测试 APK、release publication 和示例 APK 构建。
新测试覆盖独立配置快照、浅层规则的默认识别器与显式覆盖、依赖校验、动作校验和 lambda 规则。
独立宿主与既有业务宿主验证源码兼容性；设备业务回归与构建结果分开记录。

## 执行结果（2026-11-02）

- 已交付独立配置工厂、配置启动重载、内置值动作规则、lambda rule；旧启动形式与路由动作块保留。
- 示例和 API/README 已切换到推荐写法；自定义 inspector/rule SPI 与原有协议范围不变。
- 原有 114 项 JVM 回归保留，新增 10 项测试；当前 124 项全部通过，无失败或跳过。新增覆盖配置快照、识别器覆盖顺序、非法动作、域名校验顺序、中间响应后 EOF 回退和 101 后禁止追加 HTTP 回退。
- 8 项 API 34 Android 测试全部通过，无失败或跳过；包括新增预构造配置启动、关闭及 FD 所有权验证。
- 与 CI 相同的 145 个 Gradle 任务完成：JVM、库/示例 Lint、release AAR、源码包、本地 Maven publication、Android 测试 APK 与示例 APK。Lint 无错误，保留库 1 条、示例 4 条既有提醒。
- 独立 Android 宿主编译新旧启动重载、值动作/lambda 规则、旧 DSL 和自定义 SPI；API 文档中的服务与 DNS 示例也在宿主中编译。
- 既有业务宿主以 Kotlin 2.2.10 / AGP 8.13.2 编译本地 Maven 新版本，10 项宿主单元测试及 debug APK 构建通过。
- 重构验证中发现过响应聚合超时的待读取作用域错误，已修正为事务作用域；现有 408/504 及取消回归通过。

本轮没有重新执行真实 VPN 网络冒烟或业务服务器请求，不将编译和单元测试视为业务运行验收。已有真实 VPN 记录与多版本/长期负载缺口仍见 [IMPLEMENTATION.md](IMPLEMENTATION.md)。未进行远程发布。

后续 TCP 处理器选择与生命周期整理见 [STREAM_PROCESSORS.md](STREAM_PROCESSORS.md)；上述测试数量记录本次简化完成时的结果。
