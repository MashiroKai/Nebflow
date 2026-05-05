# 重构：消除 Agent Actor 中的 unsafeRunSync，统一 IO 异步边界

> **[必填]** 标记为必须填写，其余按需填写。
> 术语定义见 [`templates/_glossary.md`](./templates/_glossary.md)。
> **必填时机说明**：`[创建]` = 创建时必须填写；`[结束]` = 关闭/发布/完成/偿还前必须填写；未标注 = 按需填写
> 状态流转见 `templates/_glossary.md` → Engineering Task。

---

## [创建] 元信息

| 字段 | 内容 |
|------|------|
| 负责人 | @MashiroKai |
| 状态 | 草稿 |
| 标签 | backend, refactor |
| 优先级 | P0-阻塞 |
| 创建日期 | 2025-06-12 |
| 目标日期 | 未确定 |
| 预估工时 | 6h |
| 实际工时 | 进行中 |

---

## [创建] 一句话描述

消除 `AgentActor` 和 `AgentCore` 中所有 `unsafeRunSync` 阻塞调用，将 `unsafeRunAndForget` 的 fire-and-forget IO 全部补全异常处理路径，使 actor 线程永不阻塞，状态机不会因静默异常而卡住。

---

## 背景（可选）

Issue `015-merge-session-agent-actor.md` 合并了 SessionActor 与 AgentActor，session 管理逻辑收归 AgentActor。合并后所有 SessionStore IO 操作都在 actor 线程上执行，混合使用了 `unsafeRunSync`（同步阻塞）和 `unsafeRunAndForget`（异步无回调）两种模式。

Issue `016-flatten-agent-state.md` 将拆分 `AgentState` 为嵌套子结构。本 issue 解决并发模型的根本问题，可与 016 并行或先后执行，互不阻塞。

---

## [创建] 动机

**现状是什么？为什么它是个问题？**

Pekko Actor 处理消息运行在共享线程池上，核心约束是**不可阻塞**。但当前代码在 actor 线程上直接同步执行 Cats Effect IO：

| 位置 | 调用方式 | 阻塞时长 | 影响 |
|------|---------|---------|------|
| `AgentCore.scala:170` | `unsafeRunSync(remindersIO)` | 10-100ms（读配置+文件变更+技能匹配） | actor 线程被占，mailbox 消息排队 |
| `AgentActor.scala:218` | `unsafeRunSync(archiveIO)` | 10-50ms（磁盘写入压缩对比文件） | 同上，且在上下文压缩关键路径上 |

此外，`unsafeRunAndForget` 被大量使用但未处理异常路径，导致 IO 失败被静默吞掉：

```scala
// AgentActor.scala:167 — 历史保存失败，actor 完全不知道
resources.dispatcher.unsafeRunAndForget(persistIfSession(resources, updatedState))

// AgentCore.scala:443 — 提醒状态更新失败被吞
resources.dispatcher.unsafeRunAndForget(
  resources.reminderStateRef.update { rs => ... }
)
```

**关键代码矛盾：**

```scala
// AgentCore.scala:170 — 在 actor 消息处理线程上同步阻塞
val reminders = resources.dispatcher.unsafeRunSync(remindersIO)
// 这行代码占着 actor 线程，直到 fileChangeTracker + runtimePrefs + skillDiscovery 全部完成

// AgentActor.scala:218 — 压缩归档同步阻塞
resources.dispatcher.unsafeRunSync(archiveIO)
// 占着线程写磁盘，前端等待 compact 完成的消息被延迟
```

**后果：**
- actor 线程阻塞期间，该 session 的所有新消息（包括 `Interrupt`）无法被处理
- `unsafeRunAndForget` 异常静默 → 持久化失败 actor 不知情 → 状态与实际存储不一致
- 混合模式导致维护者无法判断"这里为什么 sync、那里为什么 async"

---

## [创建] 目标

`AgentActor` 中所有 IO 操作异步执行，结果通过 `ctx.self ! Message` 回传；所有 `unsafeRunAndForget` 补全 `handleErrorWith` 日志路径；彻底消灭 `unsafeRunSync`。

---

## [创建] 范围

### In Scope

- [ ] 消除 `AgentCore.scala:170` 的 `unsafeRunSync(remindersIO)`，将 reminders 构建并入 LLM 调用的 for-comprehension
- [ ] 消除 `AgentActor.scala:218` 的 `unsafeRunSync(archiveIO)`，归档改为 fire-and-forget 副作用
- [ ] 全量扫描 `src/main/scala/nebflow/agent/`，为所有 `unsafeRunAndForget` 补全 `handleErrorWith` 日志
- [ ] `persistIfSession`、`sessionStore.saveMessagesForSession`、`flushIndex` 等关键持久化 IO 加错误日志
- [ ] `reminderStateRef.update`、`fileChangeTracker` 等辅助 IO 加错误日志
- [ ] 新增编译期/静态检查脚本，防止未来引入 `unsafeRunSync`
- [ ] 新增 `AgentActorIoBoundSpec` 验证：IO 失败时状态机仍能推进（通过 mock 的 failing IO + 消息回传）

### Out of Scope

- [ ] 不改动 `AgentCommand` / `AgentStreamEvent` 消息协议
- [ ] 不改动 LLM 推理核心逻辑（`pipeLlmCall`、`pipeToolExecutions` 行为不变）
- [ ] 不拆分 `AgentState`（由 issue 016 负责）
- [ ] 不引入 Scala 3 enum 状态机
- [ ] 不改造 `askSemaphore` 全局锁（未来独立 issue）

> 本次不处理的关联问题：
> - `AgentState` 嵌套拆分 — issue 016
> - 状态机用 Scala 3 enum 表达（`Idle` / `Processing` 持有不同数据）— 未来评估
> - `askSemaphore` 全局锁改为 per-session — 未来独立 issue

---

## 当前架构（可选）

### 并发模型混用现状

```
Actor 消息处理线程
├── unsafeRunSync(remindersIO)          ← 阻塞！占着线程等 IO
├── unsafeRunSync(archiveIO)            ← 阻塞！占着线程等磁盘
├── unsafeRunAndForget(persistIfSession) ← 异步但不处理异常
├── unsafeRunAndForget(reminderStateRef) ← 异步但不处理异常
└── unsafeRunAndForget(io = LLM流+回传)  ← 唯一正确的模式
```

### 具体问题

1. **`unsafeRunSync` 阻塞 actor 线程**：reminders 构建涉及文件系统读取、配置查询、可能的向量检索，阻塞时长不可控。
2. **`unsafeRunSync` 异常直接炸线程**：如果 `remindersIO` 抛出异常，会直接在 actor 线程上抛出，触发 Pekko supervisor restart，可能导致状态丢失。
3. **`unsafeRunAndForget` 异常静默**：`persistIfSession` 失败 → 用户以为历史已保存，实际磁盘无写入；下次加载历史时数据不一致。
4. **维护者心智负担**：同一文件中 sync 和 async 混用，无法一眼判断调用是否安全。

---

## 设计原则（可选）

**Actor 线程永不阻塞；所有 IO 结果必须通过消息回传；所有 fire-and-forget 必须有观测面。**

---

## 目标架构（可选）

```
Actor 消息处理线程
├── ! LlmComplete(result)               ← IO 完成后的消息回传
├── ! LlmFailed(error)                  ← IO 失败后的消息回传
├── ! CompactionDefLoaded(defn)         ← IO 完成后的消息回传
├── unsafeRunAndForget(persistIfSession + handleErrorWith)  ← 后台执行，失败有日志
├── unsafeRunAndForget(archiveIO + handleErrorWith)         ← 后台执行，失败有日志
└── unsafeRunAndForget(reminderUpdate + handleErrorWith)    ← 后台执行，失败有日志
```

| 模式 | 使用场景 | 要求 |
|------|---------|------|
| 异步回传 (`IO.flatMap(_ => IO(self ! Msg))`) | 需要结果才能推进状态机 | 必须有 `Right` 和 `Left` 两条消息路径 |
| fire-and-forget + `handleErrorWith` | 纯副作用，失败不影响主流程 | 必须打日志，不可静默吞异常 |
| **禁用** `unsafeRunSync` | 无 | 无例外 |

---

## [结束] 详细变更

### 1. 消除 AgentCore.scala:170 `unsafeRunSync(remindersIO)`

**Current:**

```scala
val remindersIO = for
  fileChangesOpt <- resources.fileChangeTracker.checkChanges()
  currentPolicy  <- resources.runtimePrefs.getPolicy
  userInput = state.messages.reverseIterator.collectFirst { ... }.getOrElse("")
  skillMatchOpt <- resources.skillDiscovery match ...
  reminders <- SystemReminders.collectAll(...)
yield reminders
val reminders = resources.dispatcher.unsafeRunSync(remindersIO)  // <-- 阻塞！
val remindersText = SystemReminder.renderAll(reminders)
val fullSystem = if remindersText.nonEmpty then ...
```

**New:**

```scala
// reminders 构建直接并入 LLM 调用的 for-comprehension，一步异步到底
val io = for
  fileChangesOpt <- resources.fileChangeTracker.checkChanges()
  currentPolicy  <- resources.runtimePrefs.getPolicy
  userInput = state.messages.reverseIterator.collectFirst { ... }.getOrElse("")
  skillMatchOpt <- resources.skillDiscovery match
    case Some(sd) => sd.findRelevantSkill(userInput)
    case None     => IO.pure(None)
  reminders <- SystemReminders.collectAll(
    resources.reminderStateRef, state.latestUsage, agentDef.contextWindow,
    fileChangesOpt, currentPolicy, skillMatchOpt
  )
  remindersText = SystemReminder.renderAll(reminders)
  fullSystem = if remindersText.nonEmpty then s"$systemPrompt\n\n$remindersText" else systemPrompt
  messagesWithSystem = Message(MessageRole.System, Left(fullSystem)) :: state.messages
  // ... 后续 LLM 调用逻辑保持不变
  request = LlmRequest(messages = messagesWithSystem, ...)
  result <- resources.llm.sendStream(request, ...)
    .through(streamEmitter(...))
    .compile.toList
    .map(aggregateChunks)
    .attempt
  _ <- result match
    case Right(r) => IO(ctx.self ! LlmComplete(r, replyTo))
    case Left(e)  => IO(ctx.self ! LlmFailed(e, replyTo))
yield ()

resources.dispatcher.unsafeRunAndForget(io)
processing(agentDef, resources, depth, parentRef, state, stash, ctx)
```

**Rationale:** `remindersIO` 与 LLM 调用天然串行（reminders 构建完成后才能发 LLM 请求），直接并入同一个 for-comprehension，消除一次线程切换和一次阻塞。

### 2. 消除 AgentActor.scala:218 `unsafeRunSync(archiveIO)`

**Current:**

```scala
val comparisonPath = state.sessionId.flatMap { sid =>
  val archiveIO = resources.historyArchiver
    .archiveComparison(sid, pending.mode, state.messages, compacted)
    .map { case Right(path) => Some(path); case Left(err) => None }
    .handleError { _ => None }
  resources.dispatcher.unsafeRunSync(archiveIO)  // <-- 阻塞！
}
logAgentEvent(ctx, ..., s"... comparison=${comparisonPath.getOrElse("-")}")
resources.dispatcher.unsafeRunAndForget(
  emitStreamIO(state.wsSend, ctx,
    AgentStreamEvent.CompactComplete(..., comparisonPath), ...)
)
```

**New:**

```scala
// 主流程：emit 不带 comparisonPath，归档是副作用，不阻塞状态机
logAgentEvent(ctx, agentDef, depth, state.sessionId, "compaction-complete",
  s"subId=$subId mode=${pending.mode} before=${state.messages.size} after=${compacted.size}")
resources.dispatcher.unsafeRunAndForget(
  emitStreamIO(state.wsSend, ctx,
    AgentStreamEvent.CompactComplete(state.messages.size, compacted.size, None, None),
    isSubagent = depth > 0, state.sessionId)
    .handleErrorWith(_ => IO.unit)
)

// 归档在后台做，成败不影响状态机推进
state.sessionId.foreach { sid =>
  resources.dispatcher.unsafeRunAndForget(
    resources.historyArchiver.archiveComparison(sid, pending.mode, state.messages, compacted)
      .flatMap {
        case Right(path) =>
          IO(logAgentEvent(ctx, agentDef, depth, Some(sid), "archive-complete", s"path=$path"))
        case Left(err) =>
          IO(logger.warn(s"Comparison archive failed: $err"))
      }
      .handleErrorWith { e =>
        IO(logger.warn(s"Comparison archive error: ${e.getMessage}"))
      }
  )
}
```

**Rationale:** 归档文件是调试/审计用的副作用，不传前端不影响用户功能。从状态机关键路径摘除，避免阻塞压缩完成后的 LLM 重调用。

### 3. 全量 `unsafeRunAndForget` 补 `handleErrorWith`

**关键位置清单：**

| 文件 | 位置 | IO 操作 | 补全方案 |
|------|------|---------|---------|
| `AgentActor.scala:167` | `ToolsComplete` | `persistIfSession` | `handleErrorWith(e => IO(logger.warn(...)))` |
| `AgentActor.scala:453` | `finishTurn` | `saveMessagesForSession *> flushIndex` | 外层包 `handleErrorWith` |
| `AgentCore.scala:443` | `pipeToolExecutions` | `reminderStateRef.update` | `handleErrorWith(_ => IO.unit)` |
| `AgentCore.scala:108` | `maybeAutoCompact` | `emitStreamIO` | 已有 `unsafeRunAndForget`，补 `handleErrorWith` |
| `AgentCore.scala:110` | `maybeAutoCompact` | `agentLibrary.get` | 同上 |

**模式：**

```scala
// Before
resources.dispatcher.unsafeRunAndForget(persistIfSession(resources, state))

// After
resources.dispatcher.unsafeRunAndForget(
  persistIfSession(resources, state)
    .handleErrorWith { e =>
      IO(logger.warn(s"Persist session failed: ${e.getMessage}"))
    }
)
```

**Rationale:** fire-and-forget 不是"不需要处理错误"，而是"不需要等待结果"。错误必须被观测（日志），否则生产环境故障无法排查。

---

## 风险与缓解

| 风险 | 严重程度 | 缓解措施 |
|------|----------|---------|
| `remindersIO` 并入后异常路径未覆盖 | Medium | `sendStream` 的 `.attempt` 已覆盖；reminders 构建中的异常也会进入 `Left(e) => LlmFailed` |
| 归档摘除 `comparisonPath` 后前端显示缺失 | Low | `CompactComplete` 的 `comparisonPath` 字段改为 `None`，前端已有 `fold` 处理可空字段 |
| `handleErrorWith` 日志风暴 | Low | 错误日志频率低（只在 IO 失败时），且不会重试导致循环 |
| 编译期无法阻止未来引入 `unsafeRunSync` | Medium | 新增 CI 静态检查脚本 `grep -rn "unsafeRunSync" src/main/scala/nebflow/agent/` |

---

## 兼容性

- **向后兼容：** Yes（消息协议、外部行为、前端展示均不变）
- **行为变更：** 
  - 压缩完成后不再等待归档文件写入才推进状态机（延迟降低）
  - IO 失败时会多一条 warn 日志
- **迁移指南：** 无需调用方调整

---

## 关联

- Depends on `015-merge-session-agent-actor.md` — 前置依赖（SessionActor 已合并）
- Related to `016-flatten-agent-state.md` — 可并行执行，互不阻塞
- Blocks future issue — 消除 `unsafeRunSync` 后，方可安全引入 `ActorSource` / `Stream` 桥接

---

## 迁移步骤（渐进式，每步可编译）

> 风险等级：Medium（需监控）
>
> **关键约束：每步都必须 `sbt compile` 通过。**

### Phase 1: 消除 AgentCore.scala:170 `unsafeRunSync`（Medium，可编译）

1. **将 `remindersIO` 并入 `pipeLlmCall` 的 `io` for-comprehension**
   - 删除独立的 `val reminders = unsafeRunSync(...)`
   - 将 `remindersText` / `fullSystem` / `messagesWithSystem` 构建移入 for-comprehension
2. **验证异常路径**：`remindersIO` 中的异常会被 `sendStream.attempt` 捕获并转为 `LlmFailed`
3. **编译检查**：`sbt compile` 通过
4. **运行时验证**：发送消息，确认 reminders 构建 + LLM 调用流程正常

### Phase 2: 消除 AgentActor.scala:218 `unsafeRunSync`（Low，可编译）

1. **将归档 IO 从主流程摘除**，改为 fire-and-forget 副作用
2. **`CompactComplete` 消息不再带 `comparisonPath`**（设为 `None`）
3. **编译检查**：`sbt compile` 通过
4. **运行时验证**：触发大输入压缩，确认压缩完成后 LLM 重调用无延迟

### Phase 3: 全量 `unsafeRunAndForget` 补 `handleErrorWith`（Low，可编译）

1. **扫描所有 `unsafeRunAndForget` 调用点**（约 15 处）
2. **逐处补全 `handleErrorWith`**，打 warn 日志
3. **编译检查**：`sbt compile` 通过
4. **静态检查**：确认所有 `unsafeRunAndForget` 后都有错误处理

### Phase 4: 静态检查脚本 + 测试回归（Medium）

1. **新增 `scripts/check-no-unsafeRunSync.sh`**：
   ```bash
   #!/bin/bash
   if grep -rn "unsafeRunSync" src/main/scala/nebflow/agent/; then
     echo "ERROR: unsafeRunSync found in agent package"
     exit 1
   fi
   echo "OK: no unsafeRunSync in agent package"
   ```
2. **新增 `AgentActorIoBoundSpec`**：mock 一个总是失败的 `SessionStore`，验证 `LlmComplete` / `finishTurn` 流程仍能推进（不 crash）
3. **新增 `AgentActorErrorRecoverySpec`**：验证 IO 失败后状态机回到 `idle` 而非卡住
4. **端到端验证**：发送消息、触发工具、中断、压缩，确认行为无回归
5. **全量 `sbt test` 通过**

---

## [结束] 成功标准

- [ ] `grep -rn "unsafeRunSync" src/main/scala/nebflow/agent/` 返回空
- [ ] 所有 `unsafeRunAndForget` 调用后都有 `handleErrorWith`（静态检查通过）
- [ ] `remindersIO` 不再独立阻塞 actor 线程，已并入 LLM 调用 for-comprehension
- [ ] 归档 IO 从压缩完成后的状态机关键路径摘除
- [ ] `sbt test` 全部通过（含 `AgentActorCompactionSpec`）
- [ ] 新增 `AgentActorIoBoundSpec` 验证 IO 失败时状态机不卡住
- [ ] 新增 `AgentActorErrorRecoverySpec` 验证异常路径正确回传
- [ ] 前端交互无回归（发送消息、流式渲染、中断、工具展示、压缩）
- [ ] CI 新增 `check-no-unsafeRunSync.sh` 静态检查

---

## [结束] 验证

### 编译检查

```bash
sbt compile
```

### 静态检查

```bash
# 确认无 unsafeRunSync 残留
grep -rn "unsafeRunSync" src/main/scala/nebflow/agent/
# Expected: no output

# 确认所有 unsafeRunAndForget 都有 handleErrorWith
grep -n "unsafeRunAndForget" src/main/scala/nebflow/agent/*.scala | wc -l
# 记为 N
grep -B1 -A1 "unsafeRunAndForget" src/main/scala/nebflow/agent/*.scala | grep -c "handleErrorWith"
# Expected: >= N（每个 unsafeRunAndForget 后都有 handleErrorWith）
```

### 运行时检查

- [ ] 步骤一：启动应用，发送一条消息，确认流式渲染正常
- [ ] 步骤二：触发工具调用（如 Read），确认工具结果展示正常
- [ ] 步骤三：点击中断，确认推理停止且状态正确重置
- [ ] 步骤四：触发上下文压缩（大输入），确认压缩完成后无延迟继续推理
- [ ] 步骤五：创建新 session，切换后发送消息，确认两个 session 独立运行
- [ ] 步骤六：触发 AskUser / Permission，确认用户回答后状态正确恢复

### Code Review Checklist

- [ ] `AgentCore.scala` 中是否还有 `unsafeRunSync`？
- [ ] `AgentActor.scala` 中是否还有 `unsafeRunSync`？
- [ ] 每个 `unsafeRunAndForget` 是否都有 `handleErrorWith` 打日志？
- [ ] `remindersIO` 是否已并入 LLM 调用的 for-comprehension？
- [ ] 归档 IO 是否已从 `CompactComplete` 消息构造中摘除？
- [ ] `persistIfSession` / `saveMessagesForSession` 失败时是否有 warn 日志？
- [ ] 新增测试是否覆盖了 IO 失败 + 消息回传路径？

---

## 变更文件

| File | Action | Notes |
|------|--------|-------|
| `AgentCore.scala` | **Rewrite** | 消除 `unsafeRunSync(remindersIO)`，全量 `unsafeRunAndForget` 补 `handleErrorWith` |
| `AgentActor.scala` | **Modify** | 消除 `unsafeRunSync(archiveIO)`，`finishTurn`/`ToolsComplete` 补错误处理 |
| `protocol.scala` | **Modify** | `CompactComplete` 的 `comparisonPath` 字段不再由归档 IO 同步提供 |
| `AgentActorCompactionSpec.scala` | **Modify** | 适配归档逻辑变更（comparisonPath 为 None） |
| `AgentActorIoBoundSpec.scala` | **Create** | 验证 IO 失败时状态机不卡住 |
| `AgentActorErrorRecoverySpec.scala` | **Create** | 验证异常路径通过消息正确回传 |
| `scripts/check-no-unsafeRunSync.sh` | **Create** | CI 静态检查脚本 |

> 创建时填写"计划"，关闭前更新为"实际"。

---

## 权衡总结（可选）

| 指标 | 重构前 | 重构后 |
|------|--------|--------|
| `unsafeRunSync` 数量 | 2 | 0 |
| actor 线程阻塞点 | 2（reminders + archive） | 0 |
| `unsafeRunAndForget` 无错误处理 | ~10 处 | 0 |
| 压缩完成到重调用延迟 | 10-50ms（等归档） | ~0ms（异步归档） |
| 状态机卡住风险（静默 IO 失败） | 中 | 低 |

---

## [结束] 关闭原因

> 见 `templates/_glossary.md` → 关闭原因。必须选择一项。

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
