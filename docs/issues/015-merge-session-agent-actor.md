# 重构：合并 SessionActor 与 AgentActor 为统一的 AgentActor

> **[必填]** 标记为必须填写，其余按需填写。
> 术语定义见 [`templates/_glossary.md`](./templates/_glossary.md)。
> **必填时机说明**：`[创建]` = 创建时必须填写；`[结束]` = 关闭/发布/完成/偿还前必须填写；未标注 = 按需填写
> 状态流转见 `templates/_glossary.md` → 对应类型。

---

## [创建] 元信息

| 字段 | 内容 |
|------|------|
| 负责人 | @MashiroKai |
| 状态 | 草稿 |
| 标签 | refactor, actor, architecture |
| 优先级 | P1-高 |
| 创建日期 | 2025-06-03 |
| 目标日期 | 未确定 |
| 预估工时 | 10h |
| 实际工时 | 进行中 |

---

## [创建] 一句话描述

合并 `SessionActor` 与 `AgentActor` 为统一的 `AgentActor`，实现每个 session 对应一个常驻的根 `AgentActor`，消除全局单例 mailbox 串行瓶颈，支持父子层级通信与同级 AgentActor 直接 Mailbox 通信。

---

## 背景（可选）

当前架构中 `SessionActor` 是全局单例，通过 `sessionStore.getActiveId` 获取当前活跃 session，负责去重、历史加载、spawn 临时 `AgentActor`、推理完成后保存历史。所有 WebSocket 连接共享同一个 `SessionActor` mailbox，所有用户消息（`UserMessage`、`Interrupt`、`AskUserResponse` 等）都经过这个单点，形成天然串行瓶颈。

`AgentActor` 则是临时创建的 per-session 推理 actor，推理完成后即终止。两者职责割裂：session 管理逻辑分散在 `SessionActor`，而推理状态在 `AgentActor`；消息协议冗余（`SessionCommand` / `AgentCommand` 两套）；`AgentActor` 推理结果需先回传 `SessionActor` 再写 `SessionStore`，多一跳转发。

---

## [创建] 动机

**现状是什么？为什么它是个问题？**

当前代码中存在两套并行的 actor 层级：`SessionActor`（全局单例）和 `AgentActor`（临时推理 actor）。这带来了以下问题：

1. **全局 mailbox 串行瓶颈**：所有 WebSocket 连接的用户输入、`Interrupt`、`AskUserResponse` 都挤在 `SessionActor` 单一 mailbox 中处理。即使不同 session 的推理由不同 `AgentActor` 执行，所有消息仍要先经过 `SessionActor` 排队。
2. **协议冗余**：`SessionCommand` 与 `AgentCommand` 两套消息协议，中间存在大量转换（`SpawnAgent`、`AgentTurnCompleted` 等）。
3. **路由 bug 根因——信息缺失**：`AskUserResponse` / `PermissionResponse` 的 WebSocket 消息**不带 `sessionId`**，`SessionActor` 也不知道"当前哪个 session 在等待用户回答"，被迫用 `agentStates.headOption` 盲路由。这不是简单的实现 bug，而是"全局单例不持有会话上下文"的架构缺陷。
4. **多一跳持久化**：`AgentActor` 推理完成后需回传 `SessionActor`，再由 `SessionActor` 写 `SessionStore`。
5. **Agent 重复创建**：切换回已有 session 时，`SessionActor` 会重新 spawn `AgentActor` 并重新加载历史，无法复用已有 actor 的状态。

| Concern | Current Location | Should Be |
|---------|------------------|-----------|
| Session 管理（去重、busy 标记、历史加载/保存）| `SessionActor` | `AgentActor`（根 agent）|
| LLM 推理循环（idle/processing）| `AgentActor`（临时）| `AgentActor`（常驻）|
| 消息路由到前端 | `SessionActor` → `AgentActor` → `WsHub` | 根 `AgentActor` 直接 `WsHub.broadcast` |
| 子 agent 创建 | `SessionActor` spawn | 父 `AgentActor` 直接 `ctx.spawn` |
| 用户回答/权限响应路由 | `SessionActor.headOption` | 直接发给对应根 `AgentActor` |

关键代码矛盾：

```scala
// SessionActor.scala:215 — 路由 bug：headOption 在任意多 session 场景都不正确
// 根本原因是 WebSocket 的 askUserAnswer 消息不带 sessionId
// SessionActor 也不追踪"哪个 session 正在等待回答"
case SessionCommand.AskUserResponse(_, answers) =>
  data.agentStates.headOption.foreach { case (_, agentState) =>
    agentState.agentRef ! AgentCommand.UserAnswered(answers)
  }
```

---

## [创建] 目标

删除 `SessionActor` 和 `SessionCommand`，将所有 session 管理职责合并到 `AgentActor` 中。`AgentActor` 成为系统中唯一的 actor 类型，每个 session 对应一个**常驻**的根 `AgentActor`（而非临时创建），AgentActor 之间可通过 Mailbox 直接通信（父子 + 同级）。

---

## [创建] 范围

### In Scope

- [ ] 删除 `SessionActor.scala`，将其 session 管理逻辑（去重、busy 标记、历史加载/保存）合并到 `AgentActor`
- [ ] 删除 `SessionCommand`，统一为 `AgentCommand`（保留 `AgentEvent`）
- [ ] 在 `AgentCommand` 中新增同级通信消息（基于 `ActorRef`，非字符串 id）
- [ ] 改造 `AgentActor` 使其兼具根 agent（`depth=0`，`wsSend→WsHub.broadcast`）和子 agent（`depth>0`，静默）两种形态
- [ ] 改造 `WebSocketRoutes`：从全局 `sessionActorRef` 变为按需创建/复用根 agent，并发安全的 `Ref[IO, Map[sessionId, ActorRef[AgentCommand]]]`
- [ ] 实现根 AgentActor 的生命周期清理（session 删除时停止 actor 并移除引用）
- [ ] 调整 `GatewayMain` 启动流程，不再创建 `SessionActor`
- [ ] 适配现有测试 `AgentActorCompactionSpec`
- [ ] 新增 session 管理测试和多 session 并行测试

### Out of Scope

- [ ] 不修改前端渲染逻辑（前端接收的 `AgentStreamEvent` 消息格式保持不变）
- [ ] 不修改 LLM 推理核心逻辑（idle/processing 状态机行为不变）
- [ ] 不新增 WebSocket 消息类型（前端仍不带 `sessionId`，继续依赖 `getActiveId`）
- [ ] 本次不实现跨 session 的 agent 发现/注册中心（agent 间通信通过已知 `ActorRef`）
- [ ] 本次不重新设计 `SharedResources.askSemaphore` 的语义（保留全局 semaphore，后续单独评估）

> 本次不处理的关联问题（如有，拆分为新 issue）：
> - 前端 WebSocket 消息增加 `sessionId` 字段，彻底解耦全局 `getActiveId`
> - 跨 session agent 协作的高级场景（如 agent 向另一个 session 的 agent 发送消息后如何在前端展示）
> - `askSemaphore` 是否应从全局改为 per-session/per-agent

---

## 当前架构（可选）

### 组件关系

```
WebSocket连接1 ──┐
WebSocket连接2 ──┼──→ SessionActor（全局单例 mailbox）──→ AgentActor（临时，推理完销毁）
WebSocket连接3 ──┘         ↓                           ↓
                      SessionStore                  WsHub.broadcast
```

| 组件 | 职责 | 问题 |
|------|------|------|
| `SessionActor` | 全局 session 代理：去重、加载历史、spawn 临时 agent、保存历史 | 单例 mailbox 串行；`headOption` 路由 |
| `AgentActor` | LLM 推理循环（idle/processing、工具执行）| 临时 actor，推理完销毁；切换 session 需重新 spawn |
| `WebSocketRoutes` | 持有 `sessionActorRef` 单例，所有消息发往同一 actor | 无法并行处理多连接消息 |

### 具体问题

1. **Mixed concerns across actors**: `SessionActor` 既管 session 生命周期又管消息路由，`AgentActor` 只推理不管保存。
2. **Double protocol**: `SessionCommand` 与 `AgentCommand` 大量重复概念（`UserMessage` vs `UserInput`、`Interrupt` 等）。
3. **`agentStates.headOption` bug**: `AskUserResponse` 和 `PermissionResponse` 用 `headOption` 路由，根本原因是 WebSocket 消息缺少 `sessionId`，SessionActor 也不维护"等待回答的 session"映射。
4. **Active session 全局耦合**: 所有 WebSocket 连接共享 `sessionStore.getActiveId`，一个连接切换 session 影响所有连接。

---

## 设计原则（可选）

> **AgentActor = Session = 独立 Mailbox。** 每个 session 对应一个常驻的 `AgentActor`（根 agent），它有独立 mailbox，自行管理去重、历史、busy 状态。子 `AgentActor` 由父 agent 通过 `ctx.spawn` 创建，前端不可见。`AgentActor` 是系统中唯一的 actor 类型。

---

## 目标架构（可选）

```
WebSocket连接1 ──┐
WebSocket连接2 ──┼──→ WebSocketRoutes ──→ Ref[IO, Map[sessionId, rootAgentRef]]
WebSocket连接3 ──┘         ↓                          │
                       getActiveId                     │
                            ↓                          │
                    消息路由到对应根 agent              │
                            ↓                          │
              ┌─────────────────────┐                  │
              │ 根 AgentActor（常驻） │──→ WsHub.broadcast
              │   depth=0           │
              └─────────────────────┘
                        │ ctx.spawn
              ┌─────────┴──────────┐
              │ 子 AgentActor（临时）│──→ 父 AgentActor mailbox
              │   depth>0          │
              └────────────────────┘
```

| 组件 | 技术栈 | 职责 |
|------|--------|------|
| `WebSocketRoutes` | http4s + cats-effect | 维护 `Ref[IO, Map[sessionId, ActorRef[AgentCommand]]]`，按 `getActiveId` 路由用户输入到对应根 agent，管理生命周期 |
| `AgentActor`（根）| Pekko Typed | 常驻，session 管理 + LLM 推理，通过 `WsHub.broadcast` 发送 `AgentStreamEvent` |
| `AgentActor`（子）| Pekko Typed | 临时，只与父 agent Mailbox 通信，`wsSend = _ ⇒ IO.unit` |
| `AgentCommand` | ADT | 统一消息协议（含 `UserInput`、`Report`、`AskParent`、同级通信等）|

---

## [结束] 详细变更

### 1. 删除 SessionActor，合并逻辑到 AgentActor

**Current:** `SessionActor` 是全局单例，负责 `UserMessage` 去重、`SpawnAgent`、保存消息历史到 `SessionStore`。

**New:** 根 `AgentActor` 接收 `UserInput` 时自行去重（通过 `clientMessageId`）、从 `SessionStore` 加载历史、推理完成后直接保存。

```scala
// AgentActor idle state 新增 UserInput 处理
Behaviors.receiveMessage:
  case AgentCommand.UserInput(text, replyTo, clientMessageId) =>
    if state.status != AgentStatus.Idle then
      // 已经在处理中，发送 busy 事件（复用当前 wsSend）
      state.wsSend(
        Json.obj("type" -> "sessionBusy".asJson, "sessionId" -> state.sessionId.asJson, "busy" -> true.asJson)
      )
      Behaviors.same
    else if clientMessageId.exists(state.recentMessageIds.contains) then
      Behaviors.same
    else
      val userMsg = Message(MessageRole.User, Left(text))
      val newMessages = state.messages :+ userMsg
      val newIds = clientMessageId match
        case Some(id) => (state.recentMessageIds :+ id).takeRight(100)
        case None => state.recentMessageIds
      pipeLlmCall(
        state.copy(messages = newMessages, recentMessageIds = newIds, status = AgentStatus.Processing),
        ...
      )
```

**Rationale:** 去重、历史加载、保存都属于单个 session 的内部状态，不应由全局单例管理。

### 2. 统一消息协议

**Current:** `SessionCommand.UserMessage` / `SessionCommand.Interrupt` / `SessionCommand.SpawnAgent` / `AgentCommand.UserInput` / `AgentCommand.Interrupt` 等重复概念。

**New:** 全部合并到 `AgentCommand`：

```scala
sealed trait AgentCommand
object AgentCommand:
  case class UserInput(
    text: String,
    replyTo: Option[ActorRef[AgentEvent]] = None,
    clientMessageId: Option[String] = None
  ) extends AgentCommand

  case class Interrupt() extends AgentCommand
  case class UserAnswered(answers: List[String]) extends AgentCommand
  case class PermissionAnswered(approved: Boolean) extends AgentCommand

  // 父子通信（已有，保持不变）
  case class DelegateResult(subagentId: String, result: Either[AgentError, String]) extends AgentCommand
  case class SubagentQuestion(
    subagentId: String,
    question: String,
    replyTo: ActorRef[ParentAnswer]
  ) extends AgentCommand
  case class SubagentStreamEvent(subagentId: String, event: AgentStreamEvent) extends AgentCommand

  // 同级通信（新增）—— 通过 ActorRef 直接传递，不经过字符串 id 查找
  case class MessageToAgent(
    targetRef: ActorRef[AgentCommand],
    payload: String,
    replyTo: Option[ActorRef[AgentCommand]] = None
  ) extends AgentCommand

  // 内部状态机消息（已有）
  case class LlmComplete(result: ConsumeResult, replyTo: Option[ActorRef[AgentEvent]]) extends AgentCommand
  case class LlmFailed(error: Throwable, replyTo: Option[ActorRef[AgentEvent]]) extends AgentCommand
  case class ToolsComplete(...) extends AgentCommand
  case class CompactionDefLoaded(defn: Option[AgentDef]) extends AgentCommand
  case class TriggerCompaction(mode: String, ...) extends AgentCommand
  case class SubagentDefLoaded(...) extends AgentCommand

  // 生命周期
  case class Stop(reason: String) extends AgentCommand
end AgentCommand
```

> **Note:** `AgentCommand.Stop` 已存在于当前代码中，无需新增。同级通信使用 `ActorRef` 而非 `targetId: String`，避免引入不必要的查找层（子 agent 的 ref 只存在于父 agent 上下文中，无全局注册表）。

**Rationale:** 消除冗余协议，所有 actor 间通信统一使用 `AgentCommand`。

### 3. WebSocketRoutes 职责变更（并发安全）

**Current:** 持有单一 `sessionActorRef: ActorRef[SessionCommand]`，所有消息发给全局 SessionActor。

**New:** 使用 `Ref[IO, Map[String, ActorRef[AgentCommand]]]` 实现并发安全，按需创建/复用根 `AgentActor`。

```scala
class WebSocketRoutes(
  ...,
  actorSystem: ActorSystem[Nothing], // 新增构造参数
  sharedResources: SharedResources,  // 改为必填
  ...
):
  private val rootAgents: Ref[IO, Map[String, ActorRef[AgentCommand]]] =
    Ref.unsafe(Map.empty) // WebSocketRoutes 在 IO 中实例化，但 val 初始化是同步的，Ref.unsafe 安全

  /** 获取或创建根 agent。幂等：同一 sessionId 只创建一次。 */
  private def ensureRootAgent(sessionId: String): IO[ActorRef[AgentCommand]] =
    rootAgents.get.flatMap { agents =>
      agents.get(sessionId) match
        case Some(ref) => IO.pure(ref)
        case None =>
          val broadcastWsSend = (json: Json) => wsHub.broadcast(json)
          val agentIo = for
            metaOpt <- sharedResources.sessionStore.getSessionMeta(sessionId)
            agentDef <- metaOpt.flatMap(_.agentName) match
              case Some(name) =>
                sharedResources.agentLibrary.get(name).flatMap {
                  case Some(d) => IO.pure(d)
                  case None => sharedResources.agentLibrary.get("default").map(_.get)
                }
              case None =>
                sharedResources.agentLibrary.get("default").map(_.get)
            readTracker <- nebflow.core.tools.ReadTracker.create
          yield actorSystem.systemActorOf(
            AgentActor(
              agentDef,
              sharedResources,
              broadcastWsSend,
              depth = 0,
              parentRef = None,
              sessionId = Some(sessionId),
              readTracker = Some(readTracker)
            ),
            s"agent-$sessionId"
          )
          agentIo.flatMap { ref =>
            rootAgents.update(_ + (sessionId -> ref)).as(ref)
          }
    }

  /** 停止并移除根 agent。 */
  private def removeRootAgent(sessionId: String): IO[Unit] =
    rootAgents.modify { agents =>
      agents.get(sessionId) match
        case Some(ref) =>
          ref ! AgentCommand.Stop(s"session $sessionId deleted")
          (agents - sessionId, IO.unit)
        case None => (agents, IO.unit)
    }.flatten

  /** 路由消息到当前活跃 session 的根 agent。 */
  private def withActiveAgent[A](f: ActorRef[AgentCommand] => IO[A]): IO[Unit] =
    sessionStore.getActiveId.flatMap { sessionId =>
      ensureRootAgent(sessionId).flatMap(ref => f(ref).void)
    }.handleErrorWith { e =>
      logger.warn(s"Failed to route message to active agent: ${e.getMessage}")
    }
```

`handleMessage` 中的路由调整示例：

```scala
case "askUserAnswer" =>
  parse(text).flatMap(_.hcursor.downField("answers").as[List[String]]).toOption match
    case Some(answers) =>
      withActiveAgent(_ ! AgentCommand.UserAnswered(answers))
    case None => IO.unit

case "permissionAnswer" =>
  val approved = parse(text).flatMap(_.hcursor.downField("approved").as[Boolean]).getOrElse(false)
  withActiveAgent(_ ! AgentCommand.PermissionAnswered(approved))

case "interrupt" =>
  withActiveAgent(_ ! AgentCommand.Interrupt())

// 默认 case（用户消息）
case _ =>
  ...
  withActiveAgent(_ ! AgentCommand.UserInput(content, None, clientMessageId))
```

**Rationale:** `var` 在 cats-effect 并发场景下有 race condition，`Ref` 是标准做法。`ensureRootAgent` 幂等创建避免重复 spawn。

### 4. IO/Actor 桥接：SessionStore 操作在 AgentActor 内如何执行

`AgentActor` 是 Pekko Typed Actor，而 `SessionStore` 操作是 Cats Effect `IO`。当前 `SessionActor` 中使用 `resources.dispatcher.unsafeRunAndForget(...)` 在 Actor 线程中执行 IO。合并后，`AgentActor` 继续使用同一模式：

```scala
// AgentActor 中保存历史（原 SessionActor 的逻辑）
private def saveAndFinish(state: AgentState): Behavior[AgentCommand] =
  state.sessionId.foreach { sid =>
    resources.dispatcher.unsafeRunAndForget(
      resources.sessionStore.saveMessagesForSession(sid, state.messages) *>
        resources.sessionStore.flushIndex *>
        // 发送 sessionBusy=false
        state.wsSend(
          Json.obj("type" -> "sessionBusy".asJson, "sessionId" -> sid.asJson, "busy" -> false.asJson)
        )
    )
  }
  state.parentRef match
    case Some(parent) => parent ! AgentCommand.DelegateResult(...)
    case None =>
      // 根 agent：已直接保存，无需额外操作
      Behaviors.same
```

**注意：** 所有 `SessionStore` IO 操作必须通过 `resources.dispatcher` 执行，不可在 Actor 线程中直接阻塞。若未来需要 IO 结果反馈给 Actor，使用 `unsafeRunSync` 获取结果后 `ctx.self ! InternalMessage`，或改用 `ctx.ask` + `Future` 桥接。

**Rationale:** 复用现有 `dispatcher.unsafeRunAndForget` 模式，与 `SessionActor` 当前做法一致，降低迁移风险。

### 5. AgentActor Behavior 拆分（强烈建议，非备选）

当前 `AgentActor` 约 1485 行，合并 session 管理后预计增至 ~1700 行。为避免 behavior 过长，**在 Phase 2 即实施拆分**：

```scala
// AgentActor.scala — 只保留消息路由和生命周期
def apply(...): Behavior[AgentCommand] = ...

// AgentCore.scala — 状态机核心逻辑（LLM 循环、工具执行、compaction）
private[agent] trait AgentCore:
  protected def pipeLlmCall(...): Unit = ...
  protected def handleToolsComplete(...): Behavior[AgentCommand] = ...
  // ...

// AgentSession.scala — session 管理逻辑（去重、历史加载/保存）
private[agent] trait AgentSession:
  protected def checkDuplicate(...): Boolean = ...
  protected def loadHistory(...): IO[List[Message]] = ...
  protected def saveHistory(...): IO[Unit] = ...
```

`AgentActor` 对象混入 `AgentCore` + `AgentSession`：

```scala
object AgentActor extends AgentCore with AgentSession:
  def apply(...): Behavior[AgentCommand] = ...
```

**Rationale:** 1700 行的单一 behavior 难以维护和 Code Review。拆分为 trait 后，每个文件职责单一，且不影响现有测试（`AgentActor.apply` 签名不变）。

---

## 风险与缓解

| 风险 | 严重程度 | 缓解措施 |
|------|----------|----------|
| Actor 数量增加（从 1 全局 + N 临时 到 M 常驻 + N 临时）| Low | Pekko Actor 极轻量，100 个常驻 session 也微不足道 |
| `AgentActor` behavior 过长（~1700 行）| Medium | **Phase 2 即拆分**为 `AgentCore` + `AgentSession` trait |
| `rootAgents` Ref 使用不当导致并发 bug | Medium | 使用 `modify` 保证原子性；Code Review 重点检查 |
| 生命周期清理遗漏导致 actor 泄漏 | Medium | Code Review 检查 session 删除/断连路径；运行时监控 actor 数量 |
| 渐进式迁移的兼容代码开销 | Low | 兼容代码存在时间短（1-2 个 commit），可接受 |
| 测试适配成本（`AgentActorCompactionSpec` 等）| Medium | 保留原有测试场景，只替换 actor 构造方式 |
| 多 session 并发场景新增 bug | Medium | 新增测试覆盖多 session 并行推理 |

---

## 兼容性

- **向后兼容：** Yes（前端接收的 `AgentStreamEvent` 消息格式不变，单 session 场景行为一致）
- **行为变更：**
  - 多 session 场景：从"所有消息串行经过 SessionActor"变为"各 session 消息并行处理"
  - Session 切换：从"每次重新 spawn AgentActor"变为"复用常驻根 agent"
- **迁移指南：** 无需调用方调整；前端需确认 `sessionBusy` 状态的处理逻辑在多 session 并行时仍正确

---

## 关联

- Related to `010-plugin-system-architecture.md` — 插件系统中的 agent 定义机制
- Related to `architecture-proposal-a.html` — 本 issue 的详细架构设计文档

---

## 迁移步骤（渐进式，每步可编译）

> 风险等级：Medium（需监控）
>
> **关键约束：每步都必须 `sbt compile` 通过。** 采用"先新增、后迁移、再删除"的渐进式策略，避免长时间处于编译中断状态。

### Phase 1: AgentActor 增强 + 协议扩展（Low，可编译）

1. **在 `AgentCommand` 中新增消息**：
   - `UserInput` 增加 `clientMessageId` 参数（用于去重）
   - 新增 `MessageToAgent`（同级通信）
   - 确认 `Stop` 已存在
2. **在 `AgentState` 中新增 `recentMessageIds`**：
   - 默认 `Nil`，上限 100
3. **在 `AgentActor` 中新增 session 管理逻辑（不删除 SessionActor）**：
   - `idle` 状态新增 `UserInput` 处理：去重、加载历史（通过 `dispatcher.unsafeRunAndForget`）、启动推理
   - `finishTurn` 时根 agent 直接保存历史到 `SessionStore`
   - 新增 `busy` 状态检查（如果 `status != Idle`，返回 `sessionBusy=true`）
4. **编译检查**：`sbt compile` 通过。`SessionActor` 和 `SessionCommand` 仍然存在，未被引用。

### Phase 2: AgentActor Behavior 拆分（Low，可编译）

1. **提取 `AgentCore` trait**：将 LLM 循环、工具执行、compaction 逻辑从 `AgentActor` 中提取到 `AgentCore.scala`
2. **提取 `AgentSession` trait**：将去重、历史加载/保存逻辑提取到 `AgentSession.scala`
3. **`AgentActor` 混入两个 trait**，主文件只保留 `apply`、`idle`、`processing` 状态入口和消息路由
4. **编译检查**：`sbt test` 通过（`AgentActorCompactionSpec` 不应受影响）

### Phase 3: WebSocketRoutes 引入 rootAgents（Medium，可编译）

1. **改造 `WebSocketRoutes`**：
   - 新增 `actorSystem: ActorSystem[Nothing]` 和 `sharedResources: SharedResources` 构造参数
   - 新增 `rootAgents: Ref[IO, Map[String, ActorRef[AgentCommand]]]`
   - 新增 `ensureRootAgent`、`removeRootAgent`、`withActiveAgent`
2. **双轨运行**：`WebSocketRoutes` 同时保留旧的 `sessionActorRef` 参数（改为 `Option`）和新的 `rootAgents`
   - 当 `sessionActorRef = None` 时，走新逻辑（发给根 agent）
   - 当 `sessionActorRef = Some(ref)` 时，走旧逻辑（兼容）
3. **调整 `GatewayMain`**：先不传 `sessionActorRef`（传 `None`），验证新逻辑
4. **编译检查**：`sbt compile` 通过
5. **运行时验证**：单 session 场景端到端测试

### Phase 4: 删除旧组件（Low，可编译）

1. **删除 `SessionActor.scala`**
2. **从 `WebSocketRoutes` 移除 `sessionActorRef` 参数**（不再需要兼容）
3. **从 `GatewayMain` 移除 `SessionActor` 创建逻辑**
4. **编译检查**：`sbt compile` 通过

### Phase 5: 测试回归（Medium）

1. **适配 `AgentActorCompactionSpec`** — 构造参数变化（`AgentState` 新增 `recentMessageIds`）
2. **新增 `AgentActorSessionSpec`** — 验证单 agent 的 session 管理（去重、历史加载/保存）
3. **新增 `MultiSessionActorSpec`** — 验证多 session 并行推理互不阻塞
4. **新增 `SubagentSilentSpec`** — 验证子 agent `wsSend` 静默、父 agent 正确接收 `DelegateResult`
5. **端到端验证**：前端消息发送、流式渲染、中断、切换 session、删除 session
6. **静态检查**：确认 `SessionActor` / `SessionCommand` 无残留引用

---

## [结束] 成功标准

- [ ] `SessionActor.scala` 被删除，无残留引用
- [ ] `SessionCommand` 类型被删除，`AgentCommand` 包含所有必要消息
- [ ] `AgentActor` 同时承担 session 管理和 LLM 推理，单 session 场景行为与重构前一致
- [ ] `WebSocketRoutes` 使用 `Ref[IO, Map[sessionId, ActorRef[AgentCommand]]]` 管理根 agent，无 `var`
- [ ] Session 删除时对应的根 AgentActor 被正确停止并从 Map 移除
- [ ] 多 session 可并行推理，互不阻塞
- [ ] `sbt test` 全部通过（含 `AgentActorCompactionSpec`）
- [ ] 新增测试覆盖：session 去重、多 session 并行、子 agent 静默
- [ ] 前端交互无回归（发送消息、流式渲染、中断、工具展示、session 切换/删除）
- [ ] `AgentActor` behavior 已拆分为 `AgentCore` + `AgentSession` trait，主文件不超过 800 行

---

## [结束] 验证

### 编译检查

```bash
sbt compile
```

### 静态检查

```bash
# 确认 SessionActor 无残留引用
grep -rn "SessionActor" src/main/scala/
# Expected: no output (except possibly in comments/docs)

grep -rn "SessionCommand" src/main/scala/
# Expected: no output

# 确认 WebSocketRoutes 无 var rootAgents
grep -n "var rootAgents" src/main/scala/nebflow/gateway/WebSocketRoutes.scala
# Expected: no output

# 确认 AgentCommand 统一使用
grep -rn "AgentCommand" src/main/scala/nebflow/agent/ | wc -l
# Expected: > 0

# 确认 AgentActor 主文件已拆分
grep -c "def " src/main/scala/nebflow/agent/AgentActor.scala
# Expected: < 30（仅保留路由和生命周期方法）
```

### 运行时检查

- [ ] 步骤一：启动应用，发送一条消息，确认流式渲染正常
- [ ] 步骤二：触发工具调用（如 Read），确认工具结果展示正常
- [ ] 步骤三：点击中断，确认推理停止
- [ ] 步骤四：创建新 session，切换后发送消息，确认两个 session 独立运行
- [ ] 步骤五：让一个 session 的 agent delegate 子 agent，确认子 agent 静默、结果回传正常
- [ ] 步骤六：删除一个 session，确认对应 agent 被停止（可通过 Pekko 日志或 actor 数量监控）
- [ ] 步骤七：断开 WebSocket 重连，确认无残留 actor 泄漏

### Code Review Checklist

- [ ] `AgentActor` 是否已拆分为 `AgentCore` + `AgentSession` trait？主文件是否 < 800 行？
- [ ] `rootAgents` 是否使用 `Ref[IO, Map]` 而非 `var`？
- [ ] 所有 `SessionStore` IO 操作是否都通过 `resources.dispatcher` 执行？
- [ ] 子 agent 的 `wsSend` 是否正确设置为静默（`_ => IO.unit`）？
- [ ] Session 删除路径是否清理了 actor？
- [ ] `ShellSession.destroySession` 是否在 `AgentCommand.Stop` 处理中被调用？
- [ ] `recentMessageIds` 队列是否有上限（100）？
- [ ] `ensureRootAgent` 是否幂等（同一 sessionId 不重复创建）？
- [ ] `UserInput` 的 busy 检查是否在去重检查之前（避免重复消息触发 busy）？

---

## 变更文件

| File | Action | Notes |
|------|--------|-------|
| `SessionActor.scala` | **Delete** | 逻辑合并到 AgentActor |
| `protocol.scala` | **Modify** | 删除 SessionCommand，统一为 AgentCommand，新增 MessageToAgent |
| `AgentActor.scala` | **Modify** | 主文件：保留路由和生命周期，拆出具体逻辑 |
| `AgentCore.scala` | **Create** | 从 AgentActor 提取：LLM 循环、工具执行、compaction |
| `AgentSession.scala` | **Create** | 从 AgentActor + SessionActor 提取：去重、历史加载/保存 |
| `WebSocketRoutes.scala` | **Modify** | sessionActorRef → Ref[IO, Map[...]]，新增 ensureRootAgent/removeRootAgent/withActiveAgent |
| `GatewayMain.scala` | **Modify** | 移除 SessionActor 创建，根 agent 按需 spawn，关闭时清理 rootAgents |
| `AgentActorCompactionSpec.scala` | **Modify** | 适配新的 AgentActor 构造参数 |
| `AgentActorSessionSpec.scala` | **Create** | 单 agent session 管理测试（去重、历史加载/保存）|
| `MultiSessionActorSpec.scala` | **Create** | 多 session 并行推理测试 |
| `SubagentSilentSpec.scala` | **Create** | 子 agent 静默 + DelegateResult 回传测试 |
| `015-merge-session-agent-actor.md` | **Modify** | 本 issue 文档 |

> 创建时填写"计划"，关闭前更新为"实际"。

---

## 权衡总结（可选）

| 指标 | 重构前 | 重构后 |
|------|--------|--------|
| Actor 类型数 | 2（SessionActor + AgentActor）| 1（AgentActor）|
| 消息协议数 | 2（SessionCommand + AgentCommand）| 1（AgentCommand）|
| 中间消息转发层 | 有（SessionActor spawn/routing）| 无（直接 ctx.spawn / mailbox）|
| 多 session 消息处理 | 串行（单一 mailbox）| 并行（独立 mailbox）|
| Session 切换开销 | 重新 spawn agent + 加载历史 | 复用常驻 agent |
| 单 actor behavior 行数 | SessionActor(~230) + AgentActor(~1485) | AgentActor(~700) + AgentCore(~800) + AgentSession(~200) |

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
