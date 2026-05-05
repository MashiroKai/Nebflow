# 重构：拆分 AgentState 为嵌套子结构，消除扁平大对象

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
| 优先级 | P1-高 |
| 创建日期 | 2025-06-12 |
| 目标日期 | 未确定 |
| 预估工时 | 8h |
| 实际工时 | 进行中 |

---

## [创建] 一句话描述

将扁平的 `AgentState`（20+ 字段）拆分为嵌套的 `SessionContext`、`ExecutionContext`、`SafetyContext`、`CompactionContext` 四个子结构，消除跨领域字段耦合，降低 `state.copy` 遗漏风险。

---

## 背景（可选）

Issue `015-merge-session-agent-actor.md` 已完成 SessionActor 与 AgentActor 的合并，session 管理逻辑收归 `AgentActor`。合并后 `AgentState` 承载了原本分散在两端的全部状态，字段数膨胀至 20+ 个，成为当前代码中最大的单体数据结构。

---

## [创建] 动机

**现状是什么？为什么它是个问题？**

`AgentState` 目前有 20 个字段，横跨 5 个业务域：

| 业务域 | 相关字段 | 当前问题 |
|--------|---------|---------|
| Session 管理 | `sessionId`, `recentMessageIds`, `wsSend` | 与推理状态混在一起，idle 和 processing 都持有 |
| 执行状态 | `messages`, `status`, `turnIdx`, `activeStreamFiber` | `activeStreamFiber` 只在 processing 有意义，idle 时必须是 `None` |
| 子 Actor 管理 | `subagents`, `depth` | 子 agent ref 表在 finish 后必须清空，容易遗漏 |
| 安全/防循环 | `recentToolCalls`, `recentFilesRead`, `hasInjectedAntiLoop`, `stagnationCount`, `stage`, `progressStreak` | 防循环逻辑修改时需要同时改 6 个字段的 copy |
| 上下文压缩 | `pendingCompaction`, `compactionFailures`, `latestUsage` | `latestUsage` 被 LLM 循环和压缩检查两处共用，职责不清 |
| 用户交互 | `pendingAskUser`, `pendingPermission` | 等待状态与主状态机正交，用 `Option` 掩盖了状态错配 |

**关键代码矛盾：**

```scala
// AgentActor.scala:443-448 — finishTurn 需要手动重置 6 个字段
val updatedState = state.copy(
  messages = newMessages,
  status = AgentStatus.Idle,
  pendingAskUser = None,       // 必须清
  pendingPermission = None,    // 必须清
  recentFilesRead = Set.empty, // 必须清
  // 如果新增字段，必须在这里也重置，否则脏状态带入下一 turn
)

// AgentActor.scala:313 — Interrupt 也需要重置，但和 finishTurn 的字段集合不同
stash.unstashAll(idle(...,
  state.copy(
    status = AgentStatus.Idle,
    activeStreamFiber = None,
    subagents = Map.empty,      // 子 agent 被 stop，但 map 在这里清
    pendingCompaction = None,
    pendingAskUser = None,
    pendingPermission = None
  ), ...
))
```

`finishTurn`、`Interrupt`、`LlmFailed` 三条路径各自维护一份"reset 字段列表"，新增字段时需要在三处同时更新，极易遗漏。

---

## [创建] 目标

`AgentState` 变为 4-5 个嵌套子结构的组合，每个子结构只在所属业务域内修改；回到 `idle` 时只需替换对应的子结构，无需手动枚举 6+ 个字段。

---

## [创建] 范围

### In Scope

- [ ] 将 `AgentState` 拆分为 `SessionContext`、`ExecutionContext`、`SafetyContext`、`CompactionContext` 四个嵌套 case class
- [ ] 将 `pendingAskUser` / `pendingPermission` 从 `AgentState` 提升到 `ExecutionContext` 内部或单独封装为 `InteractionState`
- [ ] 修改 `AgentActor` 所有 `state.copy(...)` 调用点，使用子结构级别的 copy
- [ ] 修改 `AgentCore` 和 `AgentSession` 中所有访问 `AgentState` 字段的代码，改为访问子结构
- [ ] 适配现有测试 `AgentActorCompactionSpec` 中 `AgentState` 的构造
- [ ] 新增单元测试验证：回到 idle 时所有临时状态均被正确重置

### Out of Scope

- [ ] 不引入 Scala 3 enum / ADT 状态机（保持 Pekko Typed 的 `Behavior[AgentCommand]` 签名不变）
- [ ] 不改动 Actor 之间的消息协议（`AgentCommand` / `AgentStreamEvent` 保持不变）
- [ ] 不改动 LLM 推理核心逻辑（`pipeLlmCall`、`pipeToolExecutions` 行为不变）
- [ ] 不拆分 `messages` 和 `wsSend` 的存储方式
- [ ] 本次不处理 `unsafeRunSync/unsafeRunAndForget` 问题（拆分为独立 issue）

> 本次不处理的关联问题：
> - Actor 线程阻塞 IO（`unsafeRunSync`）— 拆分为新 issue
> - 状态机用 Scala 3 enum 表达（`Idle` / `Processing` / `WaitingForUser` 持有不同数据）— 未来评估

---

## 当前架构（可选）

### 数据结构

```
AgentState (flat, 20 fields)
├── Session 管理: sessionId, recentMessageIds, wsSend
├── 执行状态: messages, status, turnIdx, activeStreamFiber
├── 子 Actor: subagents, depth
├── 安全/防循环: recentToolCalls, recentFilesRead, hasInjectedAntiLoop, stagnationCount, stage, progressStreak
├── 上下文压缩: pendingCompaction, compactionFailures, latestUsage
└── 用户交互: pendingAskUser, pendingPermission
```

### 具体问题

1. **Reset 字段分散在 3+ 处**：`finishTurn`、`Interrupt`、`LlmFailed` 各自维护一份"清理列表"，新增字段时需同步更新所有路径。
2. **测试构造痛苦**：构造一个 `AgentState` 需要填 20 个字段，其中 15 个与当前测试场景无关。
3. **子结构无法独立传递**：`AgentCore` 的方法签名接收整个 `AgentState`，但只读取其中 3-4 个字段，接口不透明。

---

## 设计原则（可选）

**按业务域拆分，按生命周期隔离。** 同一业务域的字段封装在一起；跨 turn 的持久状态与单 turn 的临时状态分离。

---

## 目标架构（可选）

```
AgentState
├── session: SessionContext        // 跨 turn 持久，不随 turn 结束重置
│   ├── sessionId
│   ├── recentMessageIds
│   └── wsSend
├── execution: ExecutionContext    // 每 turn 重置
│   ├── messages
│   ├── status
│   ├── turnIdx
│   ├── activeStreamFiber
│   ├── subagents
│   └── interaction: Option[InteractionState]  // pendingAskUser / pendingPermission
├── safety: SafetyContext          // 跨 turn 累积，但可独立重置策略
│   ├── recentToolCalls
│   ├── recentFilesRead
│   ├── hasInjectedAntiLoop
│   ├── stagnationCount
│   ├── stage
│   └── progressStreak
└── compaction: CompactionContext  // 压缩专用
    ├── pendingCompaction
    ├── compactionFailures
    └── latestUsage
```

| 组件 | 技术栈 | 职责 |
|------|--------|------|
| `SessionContext` | case class | session 级别持久状态，actor 生命周期内不重置 |
| `ExecutionContext` | case class | 单 turn 执行状态，finish/Interrupt 时整体替换 |
| `SafetyContext` | case class | 安全策略状态，防循环 + 自适应阶段 |
| `CompactionContext` | case class | 上下文压缩状态，可独立清空 |
| `InteractionState` | case class | 等待用户响应的 deferred，嵌套在 ExecutionContext 内 |

---

## [结束] 详细变更

### 1. 拆分 AgentState

**Current:**

```scala
case class AgentState(
  messages: List[Message],
  status: AgentStatus,
  depth: Int,
  subagents: Map[String, ActorRef[AgentCommand]],
  activeStreamFiber: Option[Fiber[IO, Throwable, Unit]],
  sessionId: Option[String] = None,
  pendingCompaction: Option[CompactionContext] = None,
  compactionFailures: Int = 0,
  latestUsage: Option[TokenUsage] = None,
  pendingAskUser: Option[Deferred[IO, List[String]]] = None,
  pendingPermission: Option[Deferred[IO, Boolean]] = None,
  recentToolCalls: List[ToolCallRecord] = Nil,
  turnIdx: Int = 0,
  wsSend: Json => IO[Unit] = _ => IO.unit,
  hasInjectedAntiLoop: Boolean = false,
  recentFilesRead: Set[String] = Set.empty,
  stagnationCount: Int = 0,
  stage: AdaptiveStage = AdaptiveStage.Normal,
  progressStreak: Int = 0,
  readTracker: Option[ReadTracker] = None,
  recentMessageIds: List[String] = Nil
)
```

**New:**

```scala
case class AgentState(
  session: SessionContext,
  execution: ExecutionContext,
  safety: SafetyContext,
  compaction: CompactionContext
)

case class SessionContext(
  sessionId: Option[String],
  recentMessageIds: List[String],
  wsSend: Json => IO[Unit]
)

case class ExecutionContext(
  messages: List[Message],
  status: AgentStatus,
  turnIdx: Int,
  subagents: Map[String, ActorRef[AgentCommand]],
  activeStreamFiber: Option[Fiber[IO, Throwable, Unit]],
  interaction: Option[InteractionState] = None,
  depth: Int = 0
)

case class InteractionState(
  pendingAskUser: Option[Deferred[IO, List[String]]],
  pendingPermission: Option[Deferred[IO, Boolean]]
)

case class SafetyContext(
  recentToolCalls: List[ToolCallRecord],
  recentFilesRead: Set[String],
  hasInjectedAntiLoop: Boolean,
  stagnationCount: Int,
  stage: AdaptiveStage,
  progressStreak: Int,
  readTracker: Option[ReadTracker]
)

case class CompactionContext(
  pendingCompaction: Option[CompactionContextInner],
  compactionFailures: Int,
  latestUsage: Option[TokenUsage]
)
```

**Rationale:** 每个子结构代表一个独立的业务域，修改时只需 copy 对应的子结构，不会意外遗漏其他域的字段。

### 2. 统一回到 idle 的 reset 逻辑

**Current:**

```scala
// finishTurn 中手动枚举 reset
state.copy(
  messages = newMessages,
  status = AgentStatus.Idle,
  pendingAskUser = None,
  pendingPermission = None,
  recentFilesRead = Set.empty
)

// Interrupt 中另一份 reset
state.copy(
  status = AgentStatus.Idle,
  activeStreamFiber = None,
  subagents = Map.empty,
  pendingCompaction = None,
  pendingAskUser = None,
  pendingPermission = None
)
```

**New:**

```scala
// ExecutionContext 提供工厂方法
object ExecutionContext:
  def idle(messages: List[Message], depth: Int): ExecutionContext =
    ExecutionContext(
      messages = messages,
      status = AgentStatus.Idle,
      turnIdx = 0, // 或继承上一 turn
      subagents = Map.empty,
      activeStreamFiber = None,
      interaction = None,
      depth = depth
    )

// finishTurn 中
val newExecution = ExecutionContext.idle(newMessages, state.execution.depth)
  .copy(turnIdx = state.execution.turnIdx) // 保留需要的字段
state.copy(execution = newExecution)

// Interrupt 中
state.copy(
  execution = ExecutionContext.idle(state.execution.messages, state.execution.depth)
    .copy(turnIdx = state.execution.turnIdx),
  compaction = state.compaction.copy(pendingCompaction = None)
)
```

**Rationale:** `idle` 工厂方法集中定义了"临时状态清零"的规则，新增字段时只需改一处。

### 3. AgentCore 方法签名精简

**Current:**

```scala
protected def pipeLlmCall(
  agentDef: AgentDef,
  resources: SharedResources,
  depth: Int,
  parentRef: Option[ActorRef[AgentCommand]],
  state: AgentState,  // 接收整个大对象
  ...
): Behavior[AgentCommand]
```

**New:**

```scala
protected def pipeLlmCall(
  agentDef: AgentDef,
  resources: SharedResources,
  parentRef: Option[ActorRef[AgentCommand]],
  state: AgentState,
  execution: ExecutionContext,  // 方法只读取 execution + compaction
  compaction: CompactionContext,
  ...
): Behavior[AgentCommand]
```

或保持 `AgentState` 签名不变，内部通过 `state.execution` / `state.safety` 访问。

**Rationale:** 保持 `AgentState` 签名以降低改动面，内部按子结构访问即可。

---

## 风险与缓解

| 风险 | 严重程度 | 缓解措施 |
|------|----------|---------|
| `state.copy` 调用点遗漏导致编译错误 | Low | 编译器会报错缺失字段，按错误提示逐处修复 |
| 测试构造 `AgentState` 的改动面大 | Medium | 为每个子结构提供 `default` 工厂方法，测试只需覆盖关心的子结构 |
| 子结构 copy 嵌套过深影响可读性 | Medium | 在 `AgentState` 上提供便捷方法（如 `withMessages`、`withStatus`）做一层代理 |
| 运行时行为变更（字段默认值差异） | Medium | Code Review 重点检查所有 `AgentState(...)` 构造点，确保默认值等价 |

---

## 兼容性

- **向后兼容：** Yes（`AgentCommand` / `AgentStreamEvent` 协议不变，外部行为不变）
- **行为变更：** 无
- **迁移指南：** 无需调用方调整

---

## 关联

- Depends on `015-merge-session-agent-actor.md` — 前置依赖（SessionActor 已合并）
- Related to future issue — Actor 线程阻塞 IO（`unsafeRunSync` 清理）
- Related to future issue — 状态机用 Scala 3 enum 表达

---

## 迁移步骤（渐进式，每步可编译）

> 风险等级：Medium（需监控）
>
> **关键约束：每步都必须 `sbt compile` 通过。**

### Phase 1: 新增子结构，保留旧 AgentState（Low，可编译）

1. **在 `protocol.scala` 中新增 4 个子结构 case class**（`SessionContext`、`ExecutionContext`、`SafetyContext`、`CompactionContext`、`InteractionState`）
2. **在 `AgentState` 上新增 companion object 工厂方法** `fromLegacy(...)` 和便捷访问器（如 `state.messages` 代理到 `state.execution.messages`）
3. **编译检查**：`sbt compile` 通过，旧代码无需修改

### Phase 2: 内部访问改为子结构（Low，可编译）

1. **修改 `AgentActor` 中所有 `state.copy(...)`** 为子结构级别的 copy
2. **修改 `AgentCore` 中所有 `state.xxx` 字段访问** 为 `state.execution.xxx` / `state.safety.xxx`
3. **修改 `AgentSession` 中所有字段访问**
4. **编译检查**：`sbt compile` 通过

### Phase 3: 删除旧扁平字段（Low，可编译）

1. **从 `AgentState` 中删除旧扁平字段**，保留 4 个嵌套子结构
2. **删除 companion object 中的 legacy 代理访问器**
3. **编译检查**：`sbt compile` 通过

### Phase 4: 测试适配与回归（Medium）

1. **适配 `AgentActorCompactionSpec`** — 使用新的子结构构造 `AgentState`
2. **新增 `AgentStateResetSpec`** — 验证 `finishTurn`、`Interrupt`、`LlmFailed` 三条路径均正确重置临时状态
3. **新增 `AgentStateConstructionSpec`** — 验证子结构 default 工厂方法覆盖所有必要字段
4. **端到端验证**：发送消息、中断、工具调用、压缩，确认行为无回归
5. **静态检查**：确认无残留的旧字段访问

---

## [结束] 成功标准

- [ ] `AgentState` 字段数从 20+ 减少到 4 个嵌套子结构
- [ ] `finishTurn`、`Interrupt`、`LlmFailed` 三条路径使用统一的 `ExecutionContext.idle` 重置临时状态
- [ ] 新增字段时只需在对应子结构的工厂方法中处理，无需修改 3+ 处 reset 逻辑
- [ ] 单元测试构造 `AgentState` 的字段数从 20 减少到 <= 4（只需构造关心的子结构）
- [ ] `sbt test` 全部通过（含 `AgentActorCompactionSpec`）
- [ ] 新增 `AgentStateResetSpec` 覆盖所有 reset 路径
- [ ] 前端交互无回归（发送消息、流式渲染、中断、工具展示）
- [ ] `AgentState` 主定义不超过 20 行

---

## [结束] 验证

### 编译检查

```bash
sbt compile
```

### 静态检查

```bash
# 确认 AgentState 字段数 <= 5
grep -A 20 "case class AgentState" src/main/scala/nebflow/agent/protocol.scala | grep -c "^  [a-z]"
# Expected: <= 5

# 确认无旧扁平字段残留（示例）
grep -n "pendingAskUser\|pendingPermission" src/main/scala/nebflow/agent/AgentActor.scala
# Expected: 无直接访问，应通过 execution.interaction 访问

# 确认所有 state.copy 已改为子结构级别
grep -n "state.copy(" src/main/scala/nebflow/agent/AgentActor.scala
# Expected: 数量减少，且参数为子结构名而非零散字段
```

### 运行时检查

- [ ] 步骤一：启动应用，发送一条消息，确认流式渲染正常
- [ ] 步骤二：触发工具调用（如 Read），确认工具结果展示正常
- [ ] 步骤三：点击中断，确认推理停止且状态正确重置
- [ ] 步骤四：触发上下文压缩（大输入），确认压缩前后行为正常
- [ ] 步骤五：创建新 session，切换后发送消息，确认两个 session 独立运行
- [ ] 步骤六：触发 AskUser / Permission，确认用户回答后状态正确恢复

### Code Review Checklist

- [ ] `AgentState` 是否只有 4 个嵌套子结构字段？
- [ ] `ExecutionContext.idle` 是否集中定义了所有临时状态的 reset 规则？
- [ ] 所有 `state.copy(...)` 是否已改为子结构级别的 copy？
- [ ] `AgentState` 是否提供了便捷的代理访问器（如 `state.messages`）以减少代码噪音？
- [ ] 测试中的 `AgentState` 构造是否使用了子结构的 default 工厂方法？
- [ ] 三条 reset 路径（finishTurn / Interrupt / LlmFailed）是否使用了统一的 reset 逻辑？

---

## 变更文件

| File | Action | Notes |
|------|--------|-------|
| `protocol.scala` | **Modify** | 新增 SessionContext / ExecutionContext / SafetyContext / CompactionContext / InteractionState，重构 AgentState |
| `AgentActor.scala` | **Modify** | 所有 state.copy 改为子结构级别；统一使用 ExecutionContext.idle 重置 |
| `AgentCore.scala` | **Modify** | 字段访问改为 state.execution.xxx / state.safety.xxx |
| `AgentSession.scala` | **Modify** | 字段访问改为 state.session.xxx |
| `AgentActorCompactionSpec.scala` | **Modify** | 使用新的子结构构造测试数据 |
| `AgentStateResetSpec.scala` | **Create** | 验证 finishTurn / Interrupt / LlmFailed 三条路径正确重置 |
| `AgentStateConstructionSpec.scala` | **Create** | 验证子结构 default 工厂方法 |

> 创建时填写"计划"，关闭前更新为"实际"。

---

## 权衡总结（可选）

| 指标 | 重构前 | 重构后 |
|------|--------|--------|
| AgentState 顶层字段数 | 20 | 4 |
| 回到 idle 的 reset 路径数 | 3 处（各自维护字段列表） | 1 处（ExecutionContext.idle） |
| 测试构造必填字段数 | 20 | <= 4（只需关心子结构） |
| 子结构数量 | 0（扁平） | 4（嵌套） |
| 跨域耦合度 | 高（一个 copy 改多个域） | 低（只 copy 涉及的子结构） |

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
