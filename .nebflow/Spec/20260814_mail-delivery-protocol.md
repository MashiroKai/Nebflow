# Nebflow Mail 交付协议重构方案

> 2026-08-14 · Explorer 设计

---

## 1. 问题分析：当前 Mail 机制的不足

### 1.1 架构现状

当前 `MailTool.scala` 有两种模式，通过 `ask: boolean` 参数切换：

![当前 Mail 数据流](/tmp/mail-design/diagrams/current-flow.svg)

| 模式 | 参数 | 机制 | 系统语义 |
|------|------|------|----------|
| **Ask** | `ask: true` | Fork 目标 agent 上下文 → 临时 agent → Deferred 60s race | 同步等待（已有，不改） |
| **Default** | `ask: false` | `ImmediateInput` → 注入 `pendingImmediateInputs` 队列 | 异步，在 turn 边界逐条注入 |

### 1.2 三个核心问题

**问题 1：Queue 和 Immediate 无区分**

当前所有非 ask 的 Mail 都走 `ImmediateInput` → `pendingImmediateInputs`。用户无法表达两种语义截然不同的场景：
- "做完当前任务后再处理这个"（串行链）vs "现在就知道这个信息"（即时补充）
- 两者被混在同一个缓冲队列中，无法区分优先级和调度策略

**问题 2：Pending mails 不持久化——重启即丢失**

`pendingImmediateInputs` 是 `ExecutionContext` 中的内存 `List[ImmediateInput]`：
```scala
// protocol.scala L621
case class ExecutionContext(
  ...
  pendingImmediateInputs: List[AgentCommand.ImmediateInput] = Nil,
  ...
)
```
如果 Nebflow 重启（Ctrl+C / 崩溃 / 进程重启），所有排队中的 Mail 全部丢失。对比 `TurnStateStore` 和 `FlowMailStore` 都做了原子写盘，但 pending 消息没有。

**问题 3：文本层 vs 工具层混淆**

`type` 参数（`INFO` / `FOLLOW_UP` / `INTERRUPT` / `RESULT`）是**纯文本层约定**——只在 prompt 描述里指导 LLM 如何处理，系统层面没有任何对应的交付行为差异。一个 `[INTERRUPT]` 和一个 `[INFO]` 的实际投递路径完全相同（都是 `ImmediateInput`）。用户要求的是**工具层**的三种模式。

### 1.3 当前 vs 新设计对比

![对比图](/tmp/mail-design/diagrams/comparison.svg)

---

## 2. 三模式协议设计

### 2.1 模式总览

| 模式 | `delivery` 参数 | 交付时机 | 持久化 | 场景 |
|------|-----------------|----------|--------|------|
| **Ask** | `"ask"` | 同步阻塞（fork+等待） | 无（临时 session） | 快速问答、进度查询 |
| **Queue** | `"queue"` | 当前 turn 完全结束后，FIFO 逐条 | **是**（写盘） | 串行任务链：A 做完 → B 的结果排入 → A 空闲后处理 |
| **Immediate** | `"immediate"` (默认) | 下一 turn 边界（ToolsComplete / finishTurn）即注入 | 否（内存） | 补充信息、中断、并行任务 |

### 2.2 状态图

![三模式状态流转](/tmp/mail-design/diagrams/new-protocol-state.svg)

**关键设计原则**：

1. **Queue = 严格串行**：每条 queue mail 触发一个独立 turn，turn 完全结束（回到 idle）后才处理下一条。一条 queue mail 不会被中途注入正在进行的 turn。

2. **Immediate = turn 边界合并**：immediate mail 在下一个 `ToolsComplete` 或 `finishTurn` 时注入到**当前 turn 的消息流**中（不结束当前 turn）。这与现有 `pendingImmediateInputs` 行为一致——"补充信息，让你继续做"。

3. **Queue 优先级低于 Immediate**：在 turn 结束回到 idle 时，先检查 immediate buffer（紧急），再检查 mail queue（下一个任务）。

### 2.3 工具参数变更

```json
{
  "delivery": {
    "type": "string",
    "enum": ["ask", "queue", "immediate"],
    "default": "immediate",
    "description": "Delivery mode: 'ask' = sync fork (wait for answer), 'queue' = serialized FIFO (survives restart, processed one at a time after current task completes), 'immediate' = inject like user input (merged into current turn at next boundary)."
  }
}
```

**向后兼容**：`ask: true` → `delivery: "ask"`，`ask: false` → `delivery: "immediate"`。两个参数可以共存：如果同时给出，`delivery` 优先；旧调用不传 `delivery` 时按 `ask` 回退。

### 2.4 整体架构

![整体架构图](/tmp/mail-design/diagrams/architecture.svg)

---

## 3. 持久化设计

### 3.1 MailQueueStore（新增）

与 `TurnStateStore` / `FlowMailStore` 完全一致的原子写盘模式。

**文件路径**：`~/.nebflow/sessions/<sessionId>/mail-queue.json`

**数据结构**：
```json
[
  {
    "id": "mail-q-a3f8b2",
    "from": "Backend",
    "fromSession": "sess-backend-01",
    "message": "API endpoint /api/users completed. Response format attached.",
    "type": "RESULT",
    "timestamp": 1723604400000
  }
]
```

**操作**：
| 方法 | 说明 |
|------|------|
| `append(sessionId, item)` | 追加到尾部，原子写盘（tmp + move） |
| `load(sessionId)` | 读取全部 queue items |
| `removeHead(sessionId)` | 移除头部，原子写盘剩余 items |
| `removeById(sessionId, id)` | 按 ID 移除（前端手动取消） |
| `size(sessionId)` | 返回队列长度 |

### 3.2 重启恢复流程

```
Agent re-mounted (activateAgent)
    ↓
Load mail-queue.json from disk
    ↓
If nonEmpty: set pendingMailQueueCount = N
    ↓
Enter idle state → drain head → new turn → idle → drain next → ...
```

在 `MailTool.activateAgent()` 方法中（L659-763），spawn AgentActor 后，异步加载 `MailQueueStore.load(sessionId)`。如果队列非空，发送 `MailQueued` 命令触发 drain。

### 3.3 与现有存储的关系

| 存储组件 | 路径 | 作用 | 本方案 |
|----------|------|------|--------|
| `SessionStore` | `sessions/<sid>/messages.jsonl` | 消息历史 | 不变 |
| `FlowMailStore` | `sessions/<sid>/flow-mailbox/<flow>.json` | Mail 记录历史（mailbox viewer） | 不变，继续追加 |
| `TurnStateStore` | `sessions/<sid>/turn-state.json` | 崩溃恢复标记 | 不变 |
| **`MailQueueStore`** | **`sessions/<sid>/mail-queue.json`** | **Pending queue mails** | **新增** |

---

## 4. 后端代码改动清单

### 4.1 新增文件

| 文件 | 说明 |
|------|------|
| `src/main/scala/nebflow/core/flow/MailQueueStore.scala` | 队列持久化，模式同 TurnStateStore |

### 4.2 修改文件

#### 4.2.1 `protocol.scala` — 新增命令和状态字段

```scala
// 新增 AgentCommand
case class MailQueued(
  item: MailQueueItem,
  fromSessionId: String
) extends AgentCommand

// 新增 case class
case class MailQueueItem(
  id: String,
  from: String,
  fromSession: String,
  message: String,
  type: String,       // "INFO" | "RESULT" etc. (advisory)
  timestamp: Long
)

// ExecutionContext 新增字段
case class ExecutionContext(
  ...
  // 现有
  pendingImmediateInputs: List[AgentCommand.ImmediateInput] = Nil,
  // 新增：pending queue 计数（实际内容在磁盘上）
  pendingMailQueueCount: Int = 0,
  ...
)
```

**设计决策**：`pendingMailQueueCount` 只是一个计数器，不缓存具体内容。Drain 时从 `MailQueueStore` 读取 head。原因：
1. 避免 state 中持有大量数据
2. 确保磁盘和内存一致（如果其他进程修改了文件）
3. `MailQueued` 命令携带完整 item，idle 状态可直接处理无需读盘

#### 4.2.2 `MailTool.scala` — 参数化和路由

```scala
// inputSchema 新增 delivery 参数
"delivery" -> Json.obj(
  "type" -> "string".asJson,
  "enum" -> Json.arr("ask".asJson, "queue".asJson, "immediate".asJson),
  "default" -> "immediate".asJson,
  "description" -> "..."
)

// call() 方法路由
val delivery = input("delivery").flatMap(_.asString)
  .orElse {
    // 向后兼容：ask:true → "ask", ask:false → "immediate"
    val ask = input("ask").orElse(input("fork")).flatMap(_.asBoolean).getOrElse(false)
    if ask then Some("ask") else Some("immediate")
  }.getOrElse("immediate")

delivery match
  case "ask"      => forkAndAsk(address, message, ctx)        // 不变
  case "queue"    => deliverQueue(address, message, mailType, ctx, system)  // 新增
  case _          => deliverImmediate(address, message, mailType, ctx, system) // 重命名自 deliverToShortName 链
```

新增 `deliverQueue` 方法：
```scala
private def deliverQueue(...): IO[Either[ToolError, String]] =
  // 1. 解析目标 session（复用现有 resolveSessionId 逻辑）
  // 2. 构造 MailQueueItem
  // 3. MailQueueStore.append(targetSid, item)  ← 持久化
  // 4. FlowMailStore.append(...)               ← 历史记录（不变）
  // 5. 发送 AgentCommand.MailQueued(item, senderSid) 到目标 actor
  // 6. WS event: flowMail + mailQueued（新增 pendingCount）
  // 7. 返回 Right("Queued to <target>. Will be processed after current work.")
```

现有 `sendMail` 方法重命名为 `deliverImmediate`（逻辑不变，仍然走 `ImmediateInput`）。

#### 4.2.3 `AgentActor.scala` — idle 和 processing 状态处理

**idle 状态**新增 `MailQueued` handler：
```scala
case AgentCommand.MailQueued(item, _) =>
  // Agent 空闲 → 立即处理 queue item
  // 1. MailQueueStore.removeHead(sessionId)  ← 从磁盘移除
  // 2. 构造 UserInput（source=Some("mail-queue"), sender=Some(item.from)）
  // 3. 触发新 turn（与现有 UserInput 处理相同）
  for
    _ <- MailQueueStore.removeHead(state.sessionId.getOrElse(""))
    userMsg = Message(MessageRole.User, Left(item.message))
      .copy(source = Some("mail-queue"))
    // ... 触发 turn
  yield ...
```

**processing 状态**新增 `MailQueued` handler：
```scala
case AgentCommand.MailQueued(item, _) =>
  // Agent 忙 → 只计数，不缓存内容（内容在磁盘上）
  val updated = state.copy(execution =
    state.execution.copy(pendingMailQueueCount = state.execution.pendingMailQueueCount + 1)
  )
  IO.pure(processing(agentDef, resources, depth, parentRef, updated, pending))
```

**finishTurnCont** 末尾（L1804 附近），在检查 `pendingImmediateInputs` 之后，新增 queue drain：
```scala
// 现有：检查 pendingImmediateInputs
else if state.pendingCompaction.isEmpty && state.execution.pendingImmediateInputs.nonEmpty then
  ... // 注入 immediate（不变）

// 新增：检查 pendingMailQueueCount
else if state.execution.pendingMailQueueCount > 0 then
  // 从磁盘读取 head，注入为新 turn
  for
    queueItems <- MailQueueStore.load(state.sessionId.getOrElse(""))
    headItem = queueItems.headOption
    _ <- headItem.traverse(item => MailQueueStore.removeHead(state.sessionId.getOrElse("")))
    // 构造新 turn
    ...
  yield ...
```

**activateAgent** 方法（MailTool.scala L659）—— 重启恢复：
```scala
// 在 AgentActor spawn 之后，异步检查 mail-queue
_ <- ctx.forkTurn(
  MailQueueStore.load(session.id).flatMap { items =>
    if items.nonEmpty then
      // 发送 MailQueued 触发 drain
      ref ! AgentCommand.MailQueued(items.head, "")
    else IO.unit
  }
)
```

#### 4.2.4 `RestApiRoutes.scala` — 新增 API

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/teams/mail-queue/:sessionId` | GET | 获取 pending mail queue items |
| `/api/teams/mail-queue/:sessionId/:itemId` | DELETE | 取消单个 pending mail |

#### 4.2.5 WS 事件

新增 WS 事件类型 `mailQueued`：
```json
{
  "type": "mailQueued",
  "from": "Backend",
  "to": "Frontend",
  "flowName": "nebflow-project",
  "preview": "API endpoint completed...",
  "pendingCount": 3,
  "sessionId": "sess-xxx"
}
```

---

## 5. 前端 UI 设计

### 5.1 Mailbox 面板改进

当前 mailbox 只展示历史记录（`FlowMailStore`）。新增 **Pending** 分区，展示 queue 中等待处理的 mails。

**布局**：
```
┌─────────────────────────────────────────────┐
│  Inbox — nebflow-project                     │
├─────────────────────────────────────────────┤
│  ◉ Pending (3)                               │  ← 新增分区
│  ┌─────────────────────────────────────────┐ │
│  │ Backend → Frontend · Queue · 2 min ago  │ │
│  │ API endpoint /api/users completed...    │ │
│  │                              [Cancel]   │ │
│  ├─────────────────────────────────────────┤ │
│  │ Backend → Frontend · Queue · 1 min ago  │ │
│  │ Fix the login bug in auth.ts...         │ │
│  │                              [Cancel]   │ │
│  └─────────────────────────────────────────┘ │
├─────────────────────────────────────────────┤
│  ✓ History                                   │  ← 已有分区
│  ┌─────────────────────────────────────────┐ │
│  │ Backend → Frontend · 5 min ago          │ │
│  │ Database schema updated...    ▾         │ │
│  └─────────────────────────────────────────┘ │
└─────────────────────────────────────────────┘
```

### 5.2 文件改动

| 文件 | 改动 |
|------|------|
| `flowViewers.js` `openMailbox()` | 新增 Pending 分区渲染，并行加载 `FlowMailStore` + `MailQueueStore` |
| `flowTeams.js` | mailbox 按钮添加 pending count badge |
| `main.js` | `onMessage('mailQueued', ...)` → 刷新 pending count |
| `ws.js` | 注册 `mailQueued` 事件类型 |

---

## 6. 向后兼容方案

### 6.1 参数兼容

| 旧调用 | 映射到 | 说明 |
|--------|--------|------|
| `Mail(address="X", message="Y")` | `delivery="immediate"` | 默认值不变 |
| `Mail(address="X", message="Y", ask=true)` | `delivery="ask"` | Fork 行为不变 |
| `Mail(address="X", message="Y", ask=false)` | `delivery="immediate"` | 不变 |
| `Mail(address="X", message="Y", type="RESULT")` | `delivery="immediate"`, type 保留 | type 仍作为 prompt 提示 |

### 6.2 已持久化的 agent prompt

现有 `.ui.json` 和 agent 定义中的 Mail 调用模式不会自动更新。处理策略：
- `ask` 参数保留至少一个大版本
- 在 `call()` 方法中做参数归一化（`delivery` 优先，回退到 `ask`）
- Prompt 中的 type 标签说明保持不变（从 prompt 中移除 [RESULT] 等文本层约定是后续可选优化）

### 6.3 文本层标签

`type` 参数和对应的 `[INFO]` / `[FOLLOW_UP]` / `[INTERRUPT]` / `[RESULT]` / `[PARALLEL]` 标签**保留**。它们仍作为 prompt-level 提示存在，帮助 LLM 理解消息性质。只是系统不再依赖它们做交付路由——交付模式由 `delivery` 参数控制。

---

## 7. 验收标准

### 7.1 冒烟测试（硬性条件）

```bash
# 1. 启动 Nebflow
sbt run

# 2. 挂载一个 team
# （通过 UI 或 API 挂载 nebflow-project team）

# 3. 验证 Queue 模式端到端
# - 向 team agent 发送 delivery=queue 的 Mail
# - 验证 mail-queue.json 文件生成
curl http://localhost:8080/api/teams/mail-queue/<sessionId> | jq .
# 预期：返回 pending items 数组

# 4. 验证 pending count 在 mailbox 中可见
# - 打开 mailbox viewer，检查 Pending 分区

# 5. 验证 immediate 模式（回归）
# - 发送 delivery=immediate 的 Mail
# - 验证消息正常注入到 agent turn
```

### 7.2 持久化验证

| 测试 | 步骤 | 预期 |
|------|------|------|
| Queue 重启不丢失 | 1. 发送 3 条 queue mail 到 busy agent<br>2. 停止 Nebflow (Ctrl+C)<br>3. 重新启动<br>4. 重新挂载 team | 3 条 queue mails 恢复，agent 回到 idle 后逐条处理 |
| Queue 原子写入 | 发送 queue mail 后立即 kill -9 | mail-queue.json 完整（tmp + move 保证原子性） |
| Queue 取消 | 通过 DELETE API 取消 pending mail | item 从 mail-queue.json 移除，不会被处理 |

### 7.3 模式语义验证

| 测试 | 验证点 |
|------|--------|
| Queue 串行 | 连续发送 3 条 queue mail → 每条触发独立 turn，不并发 |
| Queue 不打断 | Agent 正在处理 turn A → 发送 queue mail → turn A 正常完成后才处理 |
| Immediate 合并 | Agent 在 turn A 中 → 发送 immediate mail → 在下一个 ToolsComplete 时注入到 turn A 继续执行 |
| Immediate 打断 | Agent 在 idle → 发送 immediate mail → 立即触发新 turn |
| Ask 同步 | `delivery="ask"` → fork agent → 阻塞等待 → 返回答案（行为不变） |

### 7.4 编译和测试

```bash
# 编译通过
sbt compile

# 现有测试不回归
sbt test

# 新增 MailQueueStoreSpec
# - append + load 往返一致
# - removeHead 后剩余 items 正确
# - removeById 精确移除
# - 空 sessionId 返回空
# - 超大队列（200 items）正常工作
```

### 7.5 前端验证

- `curl /api/teams/mail-queue/:sessionId` → 200 + JSON array
- `curl -X DELETE /api/teams/mail-queue/:sessionId/:itemId` → 200 + 更新后的 queue
- Mailbox viewer 同时显示 Pending 和 History 两个分区
- `mailQueued` WS 事件到达时 pending count 实时更新
- Pending 分区中每条 mail 有 Cancel 按钮，点击后条目消失

### 7.6 向后兼容验证

- 旧 prompt 中 `Mail(address="X", message="Y")`（无 delivery 参数）→ 按 immediate 投递
- `Mail(address="X", message="Y", ask=true)` → 按 ask 模式 fork
- `type` 参数仍被接受，不出错

---

## 8. 风险和注意事项

| 风险 | 缓解措施 |
|------|----------|
| Queue 无限堆积 | 队列上限 100 条，超过时 Mail 返回 warning |
| Disk I/O 延迟 | append/removeHead 是原子操作但非高频，且单文件 < 100 条，性能可接受 |
| 竞态：finishTurn 和 MailQueued 同时到达 | Actor model 保证消息串行处理，无竞态 |
| mailQueueStore 文件损坏 | JSON 解析失败时返回空列表（与 FlowMailStore 同策略），log warn |
| Agent 永远不 idle（死循环） | Queue drain 依赖 agent 回到 idle；如果 agent 卡在 processing，需要 supervisor RestartAgent 清理 |
