# Nebflow Actor 模型性能瓶颈分析

## 结论先行

**瓶颈不是 Actor 模型本身，而是每个 Agent 在每轮交互中的 CPU 密集型操作。**

Actor 模型确实是轻量的——每个 actor 仅需一个 unbounded Queue + 一个 Fiber，开销约 2KB。但问题在于：Nebflow 的每个 Agent Actor 不是"纯消息传递"——它是一个完整的 LLM 客户端，每轮交互都在做大量 JSON 序列化、文件 I/O、SSE 流解析和 WS 事件发射。

10 个 agent 同时活跃 = 10 个并发 LLM 流 × (JSON 序列化 + SSE 解析 + WS 事件构造 + 磁盘写入) = CPU 被密集的 GC + JSON 处理饱和。

---

## 架构事实

### Nebflow 不使用 Pekko/Akka

**关键发现**：虽然 `project/Dependencies.scala` (line 48-50) 定义了 `pekkoActorTyped` 依赖，但 `build.sbt` 的 `libraryDependencies` 中 **没有引用它**。整个 actor 系统是自研的，基于 cats-effect IO + fs2 构建。

### Actor 系统实现

每个 Actor（`ActorSystem.scala:55-77`）= ：
| 组件 | 实现 | 空间 |
|------|------|------|
| 消息队列 | `Queue.unbounded[IO, Msg]` | ~100 bytes (空) |
| 系统信号队列 | `Queue.unbounded[IO, SystemSignal]` | ~100 bytes |
| 行为引用 | `Ref[IO, Behavior[Msg]]` | ~200 bytes |
| 轮次 Fiber 追踪 | `Ref[IO, Map[String, Fiber]]` | ~200 bytes |
| 子 Actor 追踪 | `Ref[IO, List[ActorRef]]` | ~200 bytes |
| Fiber (消息循环) | `actorLoop` 的 Fiber | ~1-2KB |
| **Actor 基础开销** | | **~2KB** |

Actor 消息处理是单线程的——一个 Fiber 从队列中取出消息，调用 `behavior.receive()`，返回新行为。`forkTurn` 方法在 Fiber 上下文中启动额外的 IO Fiber（用于 LLM 调用、WS 发送等异步操作），但不阻塞消息处理。

### 并发模型

整个进程使用：
- **1 个 `Dispatcher.parallel[IO]`**（`GatewayMain.scala:277`）——所有 `unsafeRunAndForget` 调用的共享调度器
- **1 个 `HttpClientFs2Backend`**（`LlmInterface.scala:91`）——所有 LLM 请求共享的 HTTP 后端
- **1 个 cats-effect IO 运行时**——计算线程池默认 = CPU 核心数

所有 agent 的 IO 操作都在同一个 cats-effect 计算池上调度。当 10 个 agent 同时做 JSON 序列化和 SSE 解析时，线程池被密集的计算工作饱和。

---

## 架构图

![Actor Architecture](/tmp/actor_model.svg)

---

## 1. Agent 状态大小分析

### 1.1 AgentState 完整结构

`protocol.scala:565-571` 定义了 AgentState 的三段式结构：

```scala
case class AgentState(
  session: SessionContext,       // 会话级配置 + WS 连接
  execution: ExecutionContext,   // 消息历史 + 运行时状态
  compaction: CompactionState,   // 压缩状态 + 用量追踪
  agentSessions: List[AgentSessionInfo],  // 持久子 Agent 列表
  planMode: Option[PlanModeState]  // 计划模式状态
)
```

### 1.2 messages 大小

`ExecutionContext.messages`（`protocol.scala:485`）是完整的消息列表。

每条 `Message` 的结构：
```
Message {
  role: MessageRole              // enum, ~0 bytes
  content: Either[String, List[ContentBlock]]
  timestamp: Long                // 8 bytes
}
```

ContentBlock 变体（`shared/protocol.scala`）：
| 类型 | 典型大小 | 说明 |
|------|---------|------|
| `Text(text)` | 0.5-5 KB | 助手回复文本 |
| `ToolUse(id, name, input)` | 0.2-2 KB | 工具调用参数 (JsonObject) |
| `ToolResult(toolUseId, content, isError)` | **1-50 KB** | 工具返回内容（文件内容、搜索结果等）|
| `Thinking(thinking, signature)` | 0.5-10 KB | 思考过程 |
| `Image(data, mediaType)` | 50-500 KB | base64 图片数据 |

**关键数字**（基于 `Defaults.scala:72-79`）：
- 单个工具结果最大：`DefaultMaxResultSizeChars = 50,000` chars (~50KB)
- 单轮工具结果总量上限：`MaxToolResultsPerMessageChars = 200,000` chars (~200KB)
- 工具结果预览大小：`ToolResultPreviewSize = 2,048` chars

**50 轮对话估算**：
- 每轮 ~2-3 条消息（用户 + 助手 + 工具结果）
- 助手文本平均 2KB，工具结果平均 3KB（被 guard 截断后）
- 100-150 条消息 × 平均 2KB = **200-300 KB**

但极端情况下（大文件读取、大量搜索结果），单轮可以产生 200KB 的消息，50 轮可达 **2-5 MB**。

### 1.3 execution 中的积累

`ExecutionContext`（`protocol.scala:484-512`）：
```scala
case class ExecutionContext(
  messages: List[Message],           // 主内存消耗（见上）
  pendingEvents: List[ExternalEvent], // 等待注入的外部事件
  pendingImmediateInputs: List[...],  // 排队的即时输入
  ...
)
```

`pendingEvents` 和 `pendingImmediateInputs` 正常情况下为空或很小。但在 agent 处理中收到大量外部事件时（如多个 Mail、Delegate 完成通知），它们会积累。不过这些在下次 LLM 调用前会被清空注入。

### 1.4 System Prompt 大小

系统提示每轮重新构建（`AgentCore.scala:285-323`），不存储在状态中。

组成成分（基于实测文件大小）：
| 组成 | 来源 | 大小 |
|------|------|------|
| system-prefix-for-all.md | `~/.nebflow/prompts/` | 1.6 KB（用户）/ 5 KB（JAR 默认）|
| system-prefix-for-teams.md | `~/.nebflow/prompts/` | 6.1 KB（Team agent）|
| manager-prefix.md | `~/.nebflow/prompts/` | 1.8 KB（仅 Manager）|
| agent system.md | `~/.nebflow/agents/*/` | 2-16 KB（Explorer 最大 16 KB）|
| 条件块（tools, devices, sessions, language, tasks, rules） | 运行时构建 | 5-15 KB |
| Skill catalog | `~/.nebflow/skills/*/SKILL.md` | ~15 KB（3 个 skill）|
| Team catalog | 运行时构建 | 0.3-1 KB |
| Memory block | `User.md` + agent memory | **15-20 KB** |
| Conditional reminders | `SystemReminders.collectAll` | 0.5-2 KB |

**总系统提示**：Nebula 约 30-40 KB，Team Agent 约 40-60 KB，Explorer 约 50-80 KB。

### 1.5 Tools 列表

`AgentCore.scala:722-724` 每轮从 `ToolRegistry.ALL_TOOLS` 构建工具定义列表：

```scala
protected def buildToolList(agentDef: AgentDef, depth: Int = 0): Option[List[ToolDefinition]] =
    val allowedSet = buildAllowedToolSet(agentDef, depth)
    Some(ToolRegistry.ALL_TOOLS.filter(t => allowedSet.contains(t.name)))
```

20 个内置工具（`registry.scala:13-52`），每个包含 `name + description + inputSchema`。
- Bash 工具描述：~1.5 KB
- Delegate 工具描述：~1.2 KB
- 其他工具：0.1-0.5 KB

工具定义 JSON 总计约 **15-25 KB**（发送给 LLM API 时序列化）。

### 1.6 Context Window 配置

```scala
Defaults.ContextWindow = 128000  // tokens
Defaults.MaxTokens = 16384       // 输出上限
```

实际 context window 从配置的模型中读取（`GatewayMain.scala:253-261`）。

---

## 2. Actor 生命周期和并发

### 2.1 Actor 创建方式

所有 Actor 通过 `ActorSystem.spawn` 创建（`ActorSystem.scala:55-77`），每次 spawn：
1. 创建两个 unbounded Queue
2. 创建 LocalActorContext（含 turn fibers ref + children ref）
3. 启动 `actorLoop` Fiber
4. 注册到全局 registry

### 2.2 Root Agent（depth=0）

**持久存在**。`WebSocketRoutes.scala:85-164` 中 `ensureRootAgent` 创建根 agent，缓存在 `rootAgents: Ref[IO, Map[String, ActorRef]]` 中。

只有在以下情况停止：
- 用户删除会话（`removeRootAgent`）
- 用户停止 agent（`AgentCommand.Stop`）
- 服务关闭

### 2.3 Team Agent

**持久存在**。`FlowAgentActivator.scala:196-213` 创建 Team Agent，注册到 `FlowMembership`。只要 Team 的 session 存在，Agent Actor 就活着。

一个 Team（如 nebflow-project）有 Manager + Backend + Frontend + Docs + qa-frontend + qa-backend + prompt-engineer + tool-engineer = **8 个 Agent Actor**，它们全部同时活着。

### 2.4 Flow DAG Node Agent

**临时存在**。`FlowDagExecutor.scala:298-391` 中 `executeAgent` 在节点执行后立即 stop agent + bridge actor。但 Flow 是串行执行的（`runNode` 一个接一个），所以同一时刻只有 1 个 DAG 节点 agent 活跃。

### 2.5 Delegate Sub-Agent

**临时存在**。`DelegateTool.scala:261-311` 的 `spawnBackground` 创建子 agent + adapter actor。子 agent 完成后 adapter 发送 `AgentCommand.Stop` 停止它。

但关键问题：**多个 Delegate 调用在同一轮中通过 `parTraverse` 并行执行**（`AgentCore.scala:457`）：

```scala
freshResults <- filteredCalls.parTraverse { call => ... }
```

如果 Nebula 在一个 LLM 回复中调用 5 个 Delegate，会同时 spawn 5 个子 agent + 5 个 adapter = **10 个 Actor**。

### 2.6 并发量估算

典型场景——用户同时运行 2 个 Team（各 5 个 member）+ 3 个独立 session：
- 3 个 Root Agent
- 10 个 Team Agent（2 × 5）
- 0-5 个 Delegate Sub-Agent（临时）
- 每个还有 1 个 FlowTreeActor

**总计：~25-30 个 Actor 同时活跃**

但只有真正在处理消息的 Agent 才消耗 CPU——大部分 Team Agent 在 `idle` 状态（等待 Mail），不执行 LLM 调用。真正的并发 LLM 调用通常不超过 5-10 个。

---

## 3. LLM 连接和流式处理

### 3.1 HTTP 连接共享

所有 LLM 请求通过 **同一个 `HttpClientFs2Backend`**（`LlmInterface.scala:91`）：

```scala
HttpClientFs2Backend.resource[IO]().allocated.flatMap { case (backend, release) =>
    val registry = ProviderRegistry(cfgRef, backend)
    ...
}
```

这个 backend 使用 Java 标准 `HttpClient`，默认连接池无显式配置。所有 provider adapter 共享同一个 backend 实例。

### 3.2 SSE 解析 CPU 消耗

`AnthropicAdapter.scala:296-318` 的 `parseSseIncrementally` 是一个 hot path：

```scala
byteStream
    .through(fs2.text.utf8.decode)    // 字节 → 字符串
    .through(fs2.text.lines)           // 行分割
    .filter(_.nonEmpty)
    .evalMap { line =>
        if line.startsWith("event:") then eventTypeRef.set(...)
        else if line.startsWith("data:") then
            val data = line.drop(5).trim
            eventTypeRef.getAndSet(None).flatMap {
                case Some(et) => processAnthropicEvent(et, data, ...)
                ...
            }
    }
```

每个 SSE 事件：
1. UTF-8 解码
2. 行分割
3. JSON 解析（`parse(data)`）
4. JSON 字段提取（hcursor.downField...）
5. StreamChunk 构造

一个典型的 LLM 回复（1000 tokens 输出）约产生 500-1000 个 SSE delta 事件。每个 delta 都经过上述完整处理链。

### 3.3 每个 Agent 的流式处理

每个活跃的 LLM 调用（`AgentCore.scala:339-390`）通过 `forkTurn` 在后台运行：

```scala
ctx.forkTurn(
    resources.llm.sendStream(request, onAttempt = Some(onAttemptCb))
        .through(streamEmitter(stateForLlm.wsSend, isSubagent, sessionIdOpt, isAskTurn, isCompactTurn))
        .compile
        .toList
        .flatMap { chunks =>
            val cr = aggregateChunks(chunks)
            ...
        }
)
```

`streamEmitter`（`AgentCore.scala:752-798`）对每个 SSE chunk 做 `wsSend(json)`：

```scala
stream.evalTap {
    case StreamChunk.TextDelta(delta) if delta.nonEmpty && !isCompactTurn =>
        val json = ... // JSON 构造
        wsSend(json)   // WS 发送
    case StreamChunk.ThinkingDelta(delta) =>
        val json = ...
        wsSend(json)
    ...
}
```

**对于子 agent**（`DelegateTool.scala:37-46`），每个 WS 事件还额外做两次 `json.deepMerge`：

```scala
def routeWsSend(...): Json => IO[Unit] =
    json => base(json.deepMerge(Json.obj("sessionId" -> sid.asJson)).deepMerge(routeJson))
```

**这意味着每个 SSE delta → JSON 构造 + 2次 deepMerge + WS 发送**。

### 3.4 流式处理总结

每个活跃 LLM 流的数据流：
```
LLM Provider → SSE → fs2 解码 → JSON 解析 → StreamChunk → JSON 构造 → WS 发送
     ↓
(每个 delta 经过 5 步处理)
```

10 个并发 LLM 流 = 10 条完整的 SSE 解析管线同时运行。

---

## 4. 线程池和 Dispatcher

### 4.1 Dispatcher 配置

`GatewayMain.scala:277`：
```scala
cats.effect.std.Dispatcher.parallel[IO].use { dispatcher =>
```

`Dispatcher.parallel` 创建一个内部线程池用于 `unsafeRunAndForget`。这主要用于将 IO 回调注册到 actor 消息队列。

### 4.2 cats-effect IO 运行时

GatewayMain 继承 `IOApp.Simple`，使用默认的 cats-effect 运行时。默认计算线程池大小 = CPU 核心数（在 M1/M2 Mac 上通常 8-12 个线程）。

所有 IO 计算（JSON 序列化、文件读写、SSE 解析）都在这个共享线程池上调度。

### 4.3 阻塞 IO

**Session 持久化**使用 `IO.blocking`（`SessionStore.scala:405-411`）：

```scala
private def saveSessionMessages(id: String, msgs: List[Message]): IO[Unit] =
    IO.blocking {
        val f = sessionFile(id)
        val tmp = sessionsDir / s"$id.json.tmp.${java.util.UUID.randomUUID()}"
        os.write.over(tmp, msgs.asJson.spaces2, createFolders = true)
        os.move.over(tmp, f, replaceExisting = true)
    }
```

`IO.blocking` 将操作调度到 cats-effect 的阻塞线程池（独立于计算池），不会阻塞计算线程。但 `msgs.asJson.spaces2`（JSON 序列化）是在**阻塞线程上**执行的——这是一个 CPU 密集操作放在了阻塞池上。

### 4.4 无 Pekko Dispatcher 配置

项目中**没有** `application.conf`、`reference.conf` 或任何 Pekko/Akka dispatcher 配置文件。整个线程管理完全依赖 cats-effect 默认行为。

---

## 5. 内存和 GC 压力

### 5.1 JSON 序列化热点

每个 LLM 轮次中，以下 JSON 操作发生：

| 操作 | 代码位置 | 数据大小 | 频率 |
|------|---------|---------|------|
| messages → JSON | `AnthropicAdapter.toAnthropicMessages` | 200-500KB | 每轮 1 次 |
| system blocks → JSON | `buildSystemBlocks` | 30-80KB | 每轮 1 次 |
| tools → JSON | `toAnthropicTools` | 15-25KB | 每轮 1 次 |
| SSE delta → JSON | `processAnthropicEvent` | ~100 bytes | 每 delta 1 次 |
| WS event JSON | `AgentStreamEvent.toJson` | 100-500 bytes | 每 delta 1 次 |
| messages → disk JSON | `SessionStore.saveSessionMessages` | 200-500KB | 每次 tool complete |
| session index JSON | `SessionStore.saveIndex` | ~5KB | 每次创建/删除 session |

**单轮 JSON 分配量**：~500KB-1MB 的临时 JSON 对象，等待 GC 回收。

**10 个并发 agent**：~5-10MB 的临时 JSON 对象每秒产生，造成频繁 Minor GC。

### 5.2 WS 事件频率

每个 SSE delta 触发一个 WS 事件。典型 LLM 回复的 delta 数量：
- 1000 token 文本输出：~300-500 个 text_delta SSE 事件
- 工具调用（3 个工具）：~50-100 个 tool 相关 SSE 事件
- Thinking（5000 token）：~200-300 个 thinking_delta SSE 事件

单个 agent 一轮 LLM 调用产生 ~500-1000 个 WS 事件。

**10 个并发 agent** = 5000-10000 个 WS 事件同时构造和排队发送。每个事件都是一次 JSON 构造 + WebSocket 帧写入。

### 5.3 Session 持久化频率

`persistIfSession`（`AgentCore.scala:64-67`）在每次 `ToolsComplete` 时调用：

```scala
protected def persistIfSession(resources: SharedResources, state: AgentState): IO[Unit] =
    state.sessionId match
        case Some(sid) => resources.sessionStore.saveMessagesForSession(sid, state.messages)
```

`saveMessagesForSession` 将**完整的消息列表**序列化为 JSON 并写入磁盘。不是增量写入——每次都重写整个文件。

10 个 agent 各做 3 轮工具调用 = 30 次全量 JSON 序列化 + 磁盘写入。

---

## 6. 单 Agent 内存估算

![Memory Estimate](/tmp/memory_estimate.svg)

### 详细估算表

| 组件 | 计算依据 | 大小 |
|------|---------|------|
| Actor 基础（Queue × 2 + Fiber + Refs） | 代码分析 | ~2 KB |
| AgentState 框架（SessionContext + ExecutionContext + CompactionState） | 字段计算 | ~1.5 KB |
| messages（50 轮，每轮 2-3 条，每条 ~2KB） | 150 条 × 2KB | ~300 KB |
| ReadTracker + FileHistory | 内部状态 | ~5-10 KB |
| WS send 闭包 | 函数引用 | ~0.5 KB |
| **持久状态** | | **~310 KB** |
| | | |
| System Prompt（每轮重建，临时） | 文件大小实测 | ~40-60 KB |
| Tools 定义（每轮重建，临时） | 20 个工具 | ~20 KB |
| LLM 请求 JSON body（每轮序列化） | msgs + system + tools | ~400 KB |
| LLM 流式 chunks 列表（累积中） | ~500 chunks × ~200 bytes | ~100 KB |
| HTTP 连接缓冲 | sttp/fs2 内部 | ~50 KB |
| **临时峰值** | | **~630 KB** |
| | | |
| **单 Agent 总计** | | **~940 KB** |

### 10 Agent 估算

| 指标 | 计算 | 值 |
|------|------|-----|
| 持久内存 | 10 × 310 KB | ~3 MB |
| 临时峰值内存 | 10 × 630 KB | ~6.3 MB |
| 总内存（活跃时） | 持久 + 临时 | **~9-10 MB** |
| 每 LLM 轮的 JSON 临时分配 | 10 × 400 KB | **~4 MB / 轮** |
| 每轮 WS 事件 | 10 × 500-1000 | **5000-10000 事件** |

**内存不是瓶颈**——10 MB 对 JVM 来说微不足道。

---

## 7. CPU 热点分析

![CPU Bottleneck](/tmp/cpu_bottleneck.svg)

### 为什么 CPU 饱和导致发热

**根因链**：
```
10 个并发 Agent × 每轮 LLM 调用
  → 10 条 SSE 流并行解析（每条 ~500-1000 delta）
    → 每 delta: JSON 解析 + StreamChunk 构造 + WS 事件 JSON + WS 发送
      → JSON 对象高速分配（~4 MB / 轮的临时对象）
        → Minor GC 频繁触发（每秒多次）
          → CPU 核心被 GC + JSON 序列化 + SSE 解析占满
            → 散热风扇满载
```

### 具体量化

| 操作 | 单 Agent 单轮 | 10 Agent 并发 |
|------|-------------|-------------|
| SSE 事件解析 | ~500 次 JSON.parse | ~5000 次 |
| WS 事件 JSON 构造 | ~500 次 Json.obj | ~5000 次 |
| LLM 请求 body 序列化 | ~400 KB JSON | ~4 MB |
| 消息列表 JSON（持久化） | ~300 KB JSON | ~3 MB |
| 文件 stat()（上下文刷新） | ~10 次 | ~100 次 |
| 磁盘写入 | ~300 KB | ~3 MB |

**每秒约 4-8 MB 的临时 JSON 分配**，cats-effect 线程池上同时运行 10 条 SSE 解析管线，每条管线在做 evalMap（JSON 解析 + WS 发送）——这就是 CPU 满载的直接原因。

---

## 8. 当前行为 vs 预期行为

### 当前行为

当你同时跑 10 个 agent 时：
- 每个 agent 各自维护完整的消息历史（~300 KB）
- 每个 agent 每轮将完整消息列表序列化为 JSON 发给 LLM API
- 每个 agent 的 SSE 流被逐事件解析，每事件触发 JSON 构造 + WS 发送
- 每个 agent 完成工具调用后，将完整消息列表写入磁盘
- 所有 JSON 操作产生大量临时对象，GC 频繁运行
- cats-effect 计算池上的 10 条 SSE 解析管线竞争 CPU
- 结果：CPU 持续高负载，风扇满转

### 预期行为

Actor 模型本应支持大量轻量 actor——消息传递本身几乎零开销。瓶颈应该是 LLM API 的网络延迟，而不是本地 CPU。理想情况下，10 个 agent 同时等 LLM 响应时，本地 CPU 使用率应该很低（只有 SSE 解析的轻量开销），因为大部分时间在等待网络。

---

## 9. 解决方案（按优先级排序）

### P0 — 短期优化（1-2 天）

#### 9.1 WS 事件批量化（减少 50%+ CPU）

**问题**：每个 SSE delta 触发一次 `wsSend(json)`，1000 个 delta = 1000 次 JSON 构造 + WS 写入。

**方案**：在 `streamEmitter` 中加入 batching——累积 delta，每 50ms 或 20 个 delta 发送一次。

```scala
// 当前：每 delta 一个 WS 事件
stream.evalTap { case StreamChunk.TextDelta(delta) => wsSend(json) }

// 优化后：累积 batch
stream
  .groupWithin(20, 50.millis)
  .evalMap { batch =>
    // 合并 delta，单个 WS 事件
    wsSend(combinedJson)
  }
```

**预期收益**：WS 事件数量减少 90%+，JSON 构造减少 90%+。

**涉及文件**：`AgentCore.scala`（`streamEmitter` 方法）

#### 9.2 消息列表 append 优化

**问题**：`state.messages :+ msg` 在 Scala List 上是 O(n) 操作。50 轮对话后每次 append 要复制整个列表。

**方案**：改用 `Vector` 或在 `ExecutionContext` 中维护一个反序的 prepend 缓冲区。

```scala
// 当前（O(n) per append）
val newMessages = state.messages :+ userMsg

// 方案 A：Vector（O(1) amortized append）
case class ExecutionContext(messages: Vector[Message], ...)

// 方案 B：prepend buffer + reverse（最小改动）
// 在需要时才 reverse，平时 O(1) prepend
```

**预期收益**：消除 append 的 O(n) 复制，在长会话中减少 GC 压力。

**涉及文件**：`protocol.scala`（ExecutionContext 定义）+ 全量替换 `.messages` 操作

#### 9.3 LLM 请求体增量序列化

**问题**：每轮将完整 messages 列表（300 KB）序列化为 JSON。

**方案**：缓存上一次的 JSON 字节，只追加新增消息的 JSON。Anthropic API 支持在请求体中追加最后几条消息。

```scala
// 当前：每次从头序列化全部消息
Json.fromValues(toAnthropicMessages(params.messages))

// 优化：缓存前 N-2 条的 JSON，只序列化最后 2 条
val cached = lastRequestJson.take(messages.size - 2)
val newJson = cached ++ lastTwo.map(toJson)
```

**预期收益**：JSON 序列化工作量减少 80%+（大部分消息不变）。

**涉及文件**：`AnthropicAdapter.scala`、`OpenAiAdapter.scala`

### P1 — 中期优化（3-5 天）

#### 9.4 消息历史压缩

**问题**：50 轮对话后，messages 列表可达 300 KB+。这些消息每次 LLM 调用都被完整序列化。

**方案**：
- 对子 agent（depth >= 1）启用更激进的压缩（已有 `CompactionProfile.Worker` 做 `takeRight(10)`，但阈值太小）
- 对根 agent，在 token 估计超过 50% context window 时自动触发 micro-compaction（已有 `FastMicroCompact`，确认其生效）
- 考虑在 idle 状态时异步压缩消息历史

**涉及文件**：`AgentCore.scala`（`maybeAutoCompact`）、`compact/` 包

#### 9.5 Session 持久化优化

**问题**：每次 `ToolsComplete` 都全量写入磁盘。

**方案**：
- 增量写入（追加最新消息到文件尾部）
- 或使用 debounce（类似 `appendUiMessages` 已有的 `markDirty + scheduleFlush` 模式）
- 将 JSON 序列化移到阻塞线程池外——先在计算池上做序列化，只把字节数组写入交给阻塞池

**涉及文件**：`SessionStore.scala`（`saveSessionMessages`）

#### 9.6 子 Agent WS 事件优化

**问题**：子 agent 的每个 WS 事件经过 `routeWsSend`，做两次 `json.deepMerge`。

**方案**：预构建基础 JSON 结构，只修改需要覆盖的字段：

```scala
// 当前：每个事件两次 deepMerge
json.deepMerge(Json.obj("sessionId" -> sid.asJson)).deepMerge(routeJson)

// 优化：直接构造，避免 deepMerge
def injectFields(json: Json): Json = json match {
    case obj: JsonObject => JsonObject(obj.toMap ++ Map("sessionId" -> sid.asJson, "nodeSessionId" -> subagentId.asJson))
    case _ => json
}
```

**涉及文件**：`DelegateTool.scala`（`routeWsSend`）

### P2 — 长期优化（1-2 周）

#### 9.7 流式处理的背压机制

**问题**：SSE 流的处理速度完全取决于 LLM 的发送速度。如果 WS 客户端跟不上，WS 发送会积压。

**方案**：在 `streamEmitter` 中加入背压控制——如果 WS 发送队列过长，暂停 SSE 流处理。

#### 9.8 LLM 请求缓存（Prompt Caching）

**问题**：每轮的 system prompt + tools 定义 + 历史消息的前缀是重复的，但每次都完整发送。

**方案**：利用 Anthropic 的 prompt caching（`cache_control: ephemeral`）——已在 `toAnthropicTools` 和 `buildSystemBlocks` 中部分实现，但消息历史没有缓存。

确保所有 agent 的请求都正确利用 cache breakpoint：
- System prompt（stable 部分）→ cache ✓（已实现）
- Tools 定义 → cache ✓（已实现，最后一个工具加了 cache_control）
- 消息历史前缀 → 需要在最后一个稳定的消息上加 cache_control

#### 9.9 可配置的并发限制

**问题**：没有全局并发控制——10 个 agent 可以同时发起 LLM 调用。

**方案**：添加全局信号量限制并发 LLM 流数量。超过限制的请求排队等待。

```scala
// SharedResources 中添加
llmConcurrencyLimiter: Semaphore[IO] = Semaphore[IO](5).unsafeRunSync()

// pipeLlmCall 中获取许可
for {
  permit <- resources.llmConcurrencyLimiter.acquire
  result <- resources.llm.sendStream(request)...compile.toList
  _ <- permit.release.as(permit)
} yield result
```

#### 9.10 JVM GC 调优

**问题**：默认 GC 参数不适合高吞吐 JSON 处理场景。

**方案**：在 `build.sbt` 中添加 JVM 选项：
```scala
javaOptions ++= Seq(
  "-XX:+UseG1GC",
  "-XX:MaxGCPauseMillis=100",
  "-XX:+UseStringDeduplication",  // 去重重复字符串（如 JSON 字段名）
  "-Xmx2g"                        // 明确堆上限
)
```

---

## 10. 验收条件

### 分析报告验收
- [x] 根因分析完成：CPU 密集型操作（JSON 序列化 + SSE 解析 + WS 事件）是瓶颈，不是 Actor 数量
- [x] 数据支撑完成：基于代码的具体数字估算
- [x] 解决方案：分 P0/P1/P2 三个优先级

### 优化实施验收（如果执行优化）
- [ ] WS 事件 batching：事件数量减少 50%+（可通过日志统计验证）
- [ ] JSON 序列化缓存：单轮 LLM 请求构造时间减少 50%+（可通过添加计时日志验证）
- [ ] 10 agent 并发时 CPU 使用率降低 30%+（可通过 `Activity Monitor` 验证）
- [ ] 单元测试通过：`sbt test`
- [ ] 编译通过：`sbt compile`
- [ ] 现有功能不受影响：消息历史、工具调用、流式输出均正常工作

---

## 附录：代码引用索引

| 关键文件 | 行号 | 内容 |
|---------|------|------|
| `ActorSystem.scala` | 55-77 | Actor spawn 实现 |
| `LocalActorRef.scala` | 17-42 | Actor 引用实现（Queue + Fiber） |
| `ActorContext.scala` | 77-85 | forkTurn 实现 |
| `AgentActor.scala` | 24-91 | Agent Actor 创建 + 初始状态 |
| `AgentCore.scala` | 211-403 | pipeLlmCall（LLM 调用核心） |
| `AgentCore.scala` | 405-532 | pipeToolExecutions（工具执行） |
| `AgentCore.scala` | 752-798 | streamEmitter（WS 事件发射） |
| `protocol.scala` | 565-571 | AgentState 结构 |
| `protocol.scala` | 484-512 | ExecutionContext 结构 |
| `LlmInterface.scala` | 86-91 | LLM Handle + HTTP Backend 创建 |
| `AnthropicAdapter.scala` | 240-294 | sendMessageStream（流式请求） |
| `AnthropicAdapter.scala` | 296-318 | parseSseIncrementally（SSE 解析） |
| `SessionStore.scala` | 405-411 | saveSessionMessages（磁盘持久化） |
| `GatewayMain.scala` | 277 | Dispatcher.parallel 创建 |
| `DelegateTool.scala` | 37-46 | routeWsSend（子 agent WS 路由） |
| `Defaults.scala` | 10-83 | 关键配置常量 |
