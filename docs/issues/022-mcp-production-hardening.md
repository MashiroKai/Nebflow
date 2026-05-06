# Refactor: MCP 子系统生产级加固

> **[必填]** 标记为必须填写，其余按需填写。
> 术语定义见 [`_glossary.md`](./templates/_glossary.md)。
> **必填时机说明**：`[创建]` = 创建时必须填写；`[结束]` = 关闭前必须填写；未标注 = 按需填写
> 状态流转见 `_glossary.md` → 对应类型。
>
> **升级规则**：见 `_glossary.md` → Quick Fix 升级规则。涉及行为变更时，关闭 quickfix，改用本模板。
> 小型重构可跳过可选区块。

---

## [创建] 元信息

| 字段 | 内容 |
|------|------|
| 负责人 | @MashiroKai |
| 状态 | 草稿 |
| 标签 | `backend`, `api` |
| 优先级 | P1-高 |
| 创建日期 | 2025-06-27 |
| 目标日期 | 未确定 |
| 预估工时 | 16h |
| 实际工时 | 进行中 |

---

## [创建] 一句话描述

修复 `StdioTransport` 的进程重定向 bug 和不可取消 IO，完善 MCP 协议握手、错误处理和 notification 支持，使 MCP 子系统从 demo 级达到生产可用。

---

## [创建] 动机

**现状是什么？为什么它是个问题？**

MCP 子系统的架构设计是合理的（分层清晰、与 Tool 系统融合自然），但实现层面存在多个阻碍生产使用的问题：

**1. StdioTransport 进程重定向矛盾**

`ProcessBuilder` 同时调用 `inheritIO()` 和后续 `redirectXxx()`，行为在不同 JVM 上未定义：

```scala
// transports.scala:21-25
private val process = new ProcessBuilder((command :: args)*)
  .inheritIO()                                          // 重定向所有流到父进程
  .redirectOutput(ProcessBuilder.Redirect.PIPE)         // 覆盖 stdout ← 可能无效
  .redirectInput(ProcessBuilder.Redirect.PIPE)          // 覆盖 stdin  ← 可能无效
  .redirectError(ProcessBuilder.Redirect.INHERIT)       // stderr 直接打到 nebflow 控制台
```

在某些 JVM 实现上，`inheritIO()` 生效后后续 redirect 调用被忽略，导致 MCP server 的 stdout 没有被 PIPE 到 reader thread，工具调用永久挂起。

**2. IO 操作不可取消**

`StdioTransport.send` 使用 `IO.async` 但返回 `Some(IO.unit)` 作为取消逻辑，意味着如果 MCP server 响应卡住，fiber 永远不会终止：

```scala
// transports.scala:64-81
def send(request: JsonRpcRequest): IO[JsonRpcResponse] = IO.async { cb =>
  IO {
    // ...
    Some(IO.unit)  // 不可取消！server hang 时 fiber 永久阻塞
  }
}
```

`McpManager.startAll` 有 5 秒超时，但运行时 `callTool` 没有任何超时保护。

**3. 协议握手不完整**

- initialize 后不发送 `notifications/initialized`，握手流程不符合 MCP 规范
- capabilities 协商为空对象，server 无法判断 client 支持什么
- 不处理 server 发来的 notification（如 `notifications/tools/list_changed`），静默丢弃

**4. 错误处理薄弱**

- reader thread 异常时只设 `running = false`，pending promise 永远不会完成 → 调用方永久挂起
- `listTools` 解析失败的 tool 被静默跳过，无日志
- `callTool` 把 error code 丢弃，只保留 message 字符串
- `HttpTransport` 无超时控制，无重试逻辑

**5. 并发安全隐患**

`StdioTransport.send` 中 `pending.put` 和 `stdin.println` 之间没有 happens-before 保证。极端情况下 server 极快返回 response 时，promise 可能还没注册：

```scala
// transports.scala:70-73
pending.put(Json.fromInt(id).toString, p)  // step 1
stdin.println(line)                         // step 2 — server 可能在 step 1 前就返回了
stdin.flush()
```

---

## [创建] 目标

**一句话定义成功状态。**

MCP 子系统在 stdio 和 http 两种 transport 下均可稳定运行：进程重定向正确、IO 可取消、错误有日志可追溯、协议握手完整、一个 server 挂不影响其他 server 和主流程。

---

## [创建] 范围

### In Scope

- [ ] 修复 `StdioTransport` 进程重定向 bug
- [ ] 为 `send` 添加可取消性（超时 + 取消回调）
- [ ] 完善协议握手（发送 `notifications/initialized`，声明 capabilities）
- [ ] 处理 server notification（至少处理 `notifications/tools/list_changed`）
- [ ] 加固错误处理（reader thread 异常通知 pending、listTools 日志、callTool 保留 error code）
- [ ] `HttpTransport` 添加超时控制
- [ ] `McpManager.stopAll` 添加 per-server 关闭超时

### Out of Scope

- [ ] MCP 运行时动态添加/删除 server（仍为启动时静态加载）
- [ ] MCP Resource / Prompt 能力支持（仅 Tools）
- [ ] 自动重连机制（独立 issue）
- [ ] `ping` / `keepalive` 心跳（可随动态管理一起实现）
- [ ] UI 管理界面

> 本次不处理自动重连和动态管理，聚焦于现有功能的正确性和健壮性。

---

## 当前架构

### 组件关系

| 组件 | 文件 | 职责 | 问题 |
|------|------|------|------|
| `McpTransport` | `transports.scala` | 传输层抽象 | 接口合理，实现有 bug |
| `StdioTransport` | `transports.scala` | stdio 进程通信 | 重定向矛盾、不可取消、并发隐患 |
| `HttpTransport` | `transports.scala` | HTTP JSON-RPC | 无超时、同步阻塞 |
| `McpClient` | `McpClient.scala` | MCP 协议客户端 | 握手不完整、错误处理弱 |
| `McpManager` | `McpManager.scala` | 生命周期管理 | stopAll 无超时 |
| `JsonRpc` | `JsonRpc.scala` | JSON-RPC 类型 | 无 notification 类型 |

### 关键问题汇总

| # | 问题 | 影响 | 严重程度 |
|---|------|------|----------|
| P1 | `inheritIO()` 后 `redirectXxx()` 行为未定义 | stdio transport 在某些 JVM 上完全不可用 | Critical |
| P2 | `IO.async` 不可取消 | server hang → fiber 永久阻塞 | High |
| P3 | reader thread 异常不通知 pending promise | 调用方永久挂起 | High |
| P4 | 不发送 `notifications/initialized` | 部分严格 server 拒绝后续请求 | Medium |
| P5 | notification 被静默丢弃 | 工具列表变更不可感知 | Medium |
| P6 | `pending.put` 和 `stdin.println` 无 happens-before | 极端条件下 response 丢失 | Low |
| P7 | `callTool` 丢弃 error code | 上层无法区分错误类型 | Low |
| P8 | `HttpTransport` 无超时 | HTTP server hang 时永久阻塞 | High |

---

## 目标架构

### 变更概览

| 组件 | 变更类型 | 核心改动 |
|------|----------|----------|
| `StdioTransport` | Rewrite | 修复重定向、可取消 IO、并发安全 |
| `HttpTransport` | Modify | 添加超时 |
| `McpClient` | Modify | 完善握手、增强错误处理 |
| `McpManager` | Modify | stopAll 超时、notification 回调 |
| `JsonRpc.scala` | Modify | 新增 notification 类型 |
| `McpClientSpec.scala` | Create | 单元测试 |

---

## [结束] 详细变更

### 变更点 1：修复 StdioTransport 进程重定向

**Current:** `inheritIO()` 与后续 `redirectXxx()` 混用，行为未定义。

**New:** 去掉 `inheritIO()`，显式设置每个流的重定向；stderr 重定向到 nebflow logger 而非直接 INHERIT。

```scala
private val process = new ProcessBuilder((command :: args)*)
  .redirectInput(ProcessBuilder.Redirect.PIPE)
  .redirectOutput(ProcessBuilder.Redirect.PIPE)
  .redirectError(ProcessBuilder.Redirect.PIPE)  // 不再 INHERIT，通过 logger 输出
```

**Rationale:** 显式重定向行为确定，跨 JVM 一致。stderr 不直接打到控制台，而是通过 logger 按级别输出，避免和 nebflow 自身日志混在一起。

---

### 变更点 2：可取消的 IO.async

**Current:** `IO.async` 返回 `Some(IO.unit)`，不可取消。

**New:** 为每个 pending 请求绑定一个 `cancelToken: IO[Unit]`，取消时完成 promise（返回超时错误）并从 pending map 移除。同时在 `McpClient` 层为 `callTool` 添加默认超时。

```scala
// send 签名不变，内部实现改为：
def send(request: JsonRpcRequest): IO[JsonRpcResponse] =
  IO.defer {
    val id = counter.incrementAndGet()
    val reqWithId = request.copy(id = Json.fromInt(id))
    val idStr = Json.fromInt(id).toString
    val p = Promise[JsonRpcResponse]()
    pending.put(idStr, p)

    val cancelToken: IO[Unit] = IO {
      pending.remove(idStr)
      p.failure(new RuntimeException(s"Request $id cancelled"))
      ()
    }

    // 先写入 stdin，再注册 callback
    IO.blocking {
      stdin.println(reqWithId.asJson.deepDropNullValues.noSpaces)
      stdin.flush()
    } *> IO.async { (cb: Either[Throwable, JsonRpcResponse] => Unit) =>
      p.future.onComplete {
        case Success(r) => cb(Right(r))
        case Failure(e) => cb(Left(e))
      }(ExecutionContext.global)
      Some(cancelToken)
    }
  }
```

同时在 `McpClient` 中为 `callTool` 添加默认超时（120s）：

```scala
def callTool(name: String, arguments: JsonObject): IO[String] =
  transport.send(request)
    .timeout(120.seconds)  // 默认超时
    .map(response => /* ... */)
```

**Rationale:** 保证任何 MCP 调用都可在有限时间内终止。120s 与内置工具的默认超时一致。

---

### 变更点 3：reader thread 异常处理

**Current:** reader thread catch 异常后只设 `running = false`，pending promise 不会被完成。

**New:** 异常时遍历所有 pending promise，用 failure 通知调用方。

```scala
catch {
  case e: Exception =>
    running = false
    pending.values().forEach(_.failure(e))
    pending.clear()
}
```

**Rationale:** 保证任何情况下 pending 的调用方都能收到通知，不会永久挂起。

---

### 变更点 4：完善协议握手

**Current:** initialize 后不发送 `notifications/initialized`，capabilities 为空。

**New:** initialize 成功后发送 `notifications/initialized`；声明合理的 capabilities。

```scala
def initialize(): IO[Unit] =
  val initRequest = JsonRpcRequest(
    id = nextId,
    method = "initialize",
    params = Some(JsonObject(
      "protocolVersion" -> "2025-11-05".asJson,
      "capabilities" -> Json.obj(),   // 暂不声明 sampling/roots
      "clientInfo" -> Json.obj("name" -> "nebflow".asJson, "version" -> Version.string.asJson)
    ))
  )
  for
    response <- transport.send(initRequest)
    _ <- response.error match
      case Some(err) => IO.raiseError(new RuntimeException(s"MCP initialize failed: ${err.message}"))
      case None => IO.unit
    // 发送 initialized notification（无 id，不需要 response）
    _ <- transport.sendNotification(JsonRpcNotification(
      method = "notifications/initialized"
    ))
  yield ()
```

在 `JsonRpc.scala` 中新增 `JsonRpcNotification`：

```scala
case class JsonRpcNotification(
  jsonrpc: String = "2.0",
  method: String,
  params: Option[JsonObject] = None
)
```

在 `McpTransport` 中新增 `sendNotification`：

```scala
trait McpTransport:
  def send(request: JsonRpcRequest): IO[JsonRpcResponse]
  def sendNotification(notification: JsonRpcNotification): IO[Unit]  // 新增
  def close(): IO[Unit]
```

**Rationale:** 符合 MCP 规范的握手流程。部分严格 server 在收到 `initialized` notification 后才开始处理请求。

---

### 变更点 5：处理 server notification

**Current:** reader thread 丢弃无 `id` 的消息（notification）。

**New:** reader thread 区分 response 和 notification。`McpManager` 注册 notification handler，至少处理 `notifications/tools/list_changed`。

```scala
// McpTransport 新增 callback 注册
trait McpTransport:
  def send(request: JsonRpcRequest): IO[JsonRpcResponse]
  def sendNotification(notification: JsonRpcNotification): IO[Unit]
  def onNotification(handler: JsonRpcNotification => IO[Unit]): IO[Unit]
  def close(): IO[Unit]
```

```scala
// McpManager 中处理 tools/list_changed
transport.onNotification { notification =>
  if notification.method == "notifications/tools/list_changed" then
    // 重新 listTools，更新 ToolRegistry
    refreshServerTools(serverId, client)
  else IO.unit
}
```

**Rationale:** MCP server 可能动态更新工具列表。目前静默丢弃意味着用户必须重启 nebflow 才能感知变更。

---

### 变更点 6：增强错误处理

**Current:**

- `listTools` 解析失败静默跳过
- `callTool` 把 error code 丢弃
- `HttpTransport` 无超时

**New:**

1. `listTools` 解析失败的 tool 打 warn 日志：

```scala
val name = t.hcursor.downField("name").as[String].getOrElse("")
if name.nonEmpty then Some(McpTool(name, desc, schema))
else
  logger.warn(s"Skipping malformed MCP tool from server '$serverId': missing 'name' field")
  None
```

2. `callTool` 返回结构化错误信息：

```scala
response.error match
  case Some(err) => s"[MCP Error ${err.code}] ${err.message}"
  case None => ""
```

3. `HttpTransport` 添加读取超时（30s 默认）：

```scala
// 使用 sttp 的 readTimeout
basicRequest
  .post(uri"$baseUrl")
  .readTimeout(30.seconds)
  // ...
```

**Rationale:** 生产环境需要可追溯的错误信息。error code 是区分网络问题和业务问题的关键。

---

### 变更点 7：McpManager.stopAll 超时

**Current:** `stopAll` 逐个调用 `client.close()`，无超时。

**New:** per-server 关闭超时 3 秒。

```scala
def stopAll(): IO[Unit] =
  serversRef.get.flatMap { servers =>
    servers.values.toList.parTraverse_ { case (client, _) =>
      client.close()
        .timeout(3.seconds)
        .handleErrorWith(_ => IO.unit)
    }
  }
```

**Rationale:** 保证 shutdown 流程在有限时间内完成，不因一个 hang 的 MCP server 阻止整个应用退出。

---

## 风险与缓解

| 风险 | 严重程度 | 缓解措施 |
|------|----------|----------|
| StdioTransport 重写引入新 bug | Medium | 保持 send 接口不变，仅改内部实现；添加单元测试 |
| notification handler 中 refreshServerTools 与正在进行的 callTool 竞争 | Medium | ToolRegistry.registerTool 使用并发安全集合；refresh 期间正在进行的调用用旧引用，下次调用自动切换 |
| 协议版本号 `2025-11-05` 与实际 server 不兼容 | Low | initialize 响应中检查 server 返回的 protocolVersion，不匹配时 warn 日志但不阻断 |
| HttpTransport 超时设 30s 对慢工具不够 | Low | 可在 McpServerConfig 中增加可选的 timeout 字段覆盖默认值 |

---

## 兼容性

- **向后兼容：** Yes — 所有改动在 MCP 子系统内部，`Tool` trait、`ToolRegistry`、`createMcpToolWrapper` 接口不变
- **行为变更：**
  - MCP error 现在包含 error code 前缀 `[MCP Error -32600] ...`，之前只有 `Error: ...`
  - stderr 现在通过 nebflow logger 输出，之前直接打到控制台
- **迁移指南：** 无需用户操作

---

## 关联

- Supersedes `011-mcp-tool-auto-discovery.md` — 011 已实现但存在上述质量问题，本次加固补全
- Related to `010-plugin-system-architecture.md` — MCP 配置来自插件 manifest

---

## 迁移步骤

> 风险等级：Medium（需监控）

### Phase 1: 修复 StdioTransport 核心缺陷（Medium）

1. 修复进程重定向 bug → 验证 stdio transport 在 macOS/Linux 上可用
2. 实现可取消 IO.async → 验证超时能正确终止 fiber
3. 加固 reader thread 异常处理 → 验证 server 崩溃时调用方收到错误

### Phase 2: 完善协议和错误处理（Low）

4. 新增 `JsonRpcNotification` 类型，transport 支持 `sendNotification`
5. 发送 `notifications/initialized`，声明 capabilities
6. `McpClient.callTool` 添加默认超时
7. 增强 `listTools` 和 `callTool` 的错误信息

### Phase 3: Notification 支持（Low）

8. transport 支持 `onNotification` 回调注册
9. reader thread 区分 response 和 notification
10. `McpManager` 处理 `notifications/tools/list_changed`

### Phase 4: HttpTransport 加固（Low）

11. 添加读取超时
12. `McpManager.stopAll` 添加 per-server 超时

---

## [结束] 成功标准

- [ ] StdioTransport 在 macOS 和 Linux 上均能正确与 MCP server 通信
- [ ] MCP server hang 时，`callTool` 在 120 秒内返回超时错误，不阻塞 fiber
- [ ] MCP server 进程崩溃时，正在进行的调用收到异常通知，不永久挂起
- [ ] MCP 协议握手完整：initialize → initialized notification
- [ ] `callTool` 返回的错误信息包含 error code
- [ ] `listTools` 跳过解析失败的 tool 时有 warn 日志
- [ ] `HttpTransport` 请求在 30 秒超时
- [ ] `McpManager.stopAll` 在 3 秒内完成（即使某个 server hang）
- [ ] 所有现有功能不受影响（无 MCP 配置时行为一致）

---

## [结束] 验证

### 编译检查

```bash
sbt compile
```

### 静态检查

```bash
# 确认不再使用 inheritIO
grep -rn "inheritIO" src/main/scala/nebflow/core/mcp/
# Expected: no output

# 确认不再有空的 cancel token
grep -rn "Some(IO.unit)" src/main/scala/nebflow/core/mcp/
# Expected: no output

# 确认 JsonRpcNotification 类型存在
grep -rn "JsonRpcNotification" src/main/scala/nebflow/core/mcp/
# Expected: 类型定义和使用处
```

### 运行时检查

- [ ] 配置 filesystem MCP server（stdio transport），启动 nebflow，调用 `mcp__filesystem__list_directory` 成功
- [ ] 配置一个不存在的 command，确认 nebflow 正常启动，日志有错误信息
- [ ] 配置一个 hang 的 MCP server（如 `sleep infinity`），确认 120 秒后 callTool 返回超时
- [ ] 杀掉正在运行的 MCP server 进程，确认后续调用返回错误而非永久挂起
- [ ] 配置 HTTP MCP server，确认正常工作

### Code Review Checklist

- [ ] `StdioTransport` 不使用 `inheritIO()`
- [ ] 所有 `IO.async` 的 cancel token 有实际清理逻辑
- [ ] reader thread 的 catch 块通知所有 pending promise
- [ ] `sendNotification` 在 `HttpTransport` 中为 fire-and-forget（不期望 response）
- [ ] `McpServerConfig` 可选 timeout 字段有合理默认值

---

## 变更文件

| File | Action | Notes |
|------|--------|-------|
| `core/mcp/transports.scala` | **Rewrite** | StdioTransport 核心重写、HttpTransport 添加超时 |
| `core/mcp/McpClient.scala` | **Modify** | 完善握手、callTool 超时、增强错误处理 |
| `core/mcp/McpManager.scala` | **Modify** | stopAll 超时、notification 回调处理 |
| `core/mcp/JsonRpc.scala` | **Modify** | 新增 JsonRpcNotification 类型 |
| `core/mcp/McpClientSpec.scala` | **Create** | 单元测试：连接、超时、错误处理 |

> 创建时填写"计划"，关闭前更新为"实际"。

---

## [结束] 关闭原因

> 见 `_glossary.md` → 关闭原因。必须选择一项。

- [ ] 已完成
- [ ] 重复 — 重复 issue：
- [ ] 不予处理 — 理由：
- [ ] 已过时
- [ ] 已取消 — 理由：

---

## [结束] 复盘（可选）

> `[结束]` 时填写。若关闭原因为"重复"/"无法复现"/"已过时"，可跳过。

| 问题 | 回答 |
|------|------|
| 目标达成了吗？ | |
| 有什么意外？ | |
| 代码质量改善可量化吗？ | |
| 工时预估偏差原因？ | |
