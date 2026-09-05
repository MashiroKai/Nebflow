# Mail queue 投递时机改进方案 —— 目标 agent 完全空闲时才投递

- 日期：2026-08-25（用户裁定 19:53）· **2026-08-28 01:00 统一裁定修订**
- 状态：v1.1 · 主语义已实施（#407），08-28 统一裁定已实施（feat/mail-queue-unified-idle）
- 类型：阶段文档（冻结）
- 涉及仓库：主仓 `/Users/dev/Claude code/Nebflow`（Scala + Pekko 风格自研 actor）

> **⚠ 2026-08-28 01:00 统一裁定（覆盖本方案 §2 的「按发送者区分」设计）**：
> queue 投递判定**统一为「当事（目标）agent 自身+子树全空闲」，无论发送者是谁**。
> 08-25 的按发送者区分——「Nebula（root sender）→ Manager 等全 team 停 vs team 内
> 等目标单独停」——废除。理由：全 team 停的旧语义让**无关成员的忙碌无限期扣住
> 投递**（实证：Nebula→Manager 的「好友功能就绪度评估」queue 邮件因 team 长期
> 有人活跃而永不投递，见 §2.1）。代码落点：`MailIdleGate.isTeamTreeIdle` 删除、
> `AgentActor.fullyIdle` 的 senderIsRoot 分支删除；AC-6 wiring 用例翻转为
> 「兄弟成员忙不再阻塞 Nebula→Manager 投递」。Q3（RunningFlow.sessionId 关联）
> 与「不做 MailQueueDrainer」裁定不变。

---

## 0. 结论摘要

**问题**：queue 模式 Mail 在目标 agent turn 结束即投递，但 turn 结束 ≠ 任务结束——目标 agent 可能正在等待其派遣的子 agent（Delegate/SubTask worker / flow 节点）完成。此时新任务被注入「等待子 agent」窗口，造成认知负担甚至漏执行。

**根因**（实证）：AgentActor 的两个 queue drain 点——idle 态 `MailQueued` handler 与 `finishTurnCont` 的 turn 边界 drain——**均无子 agent barrier 检查**；而 `finishTurnCont` 明确允许 turn 结束回 idle 时 `outstandingSubagentResults > 0`（#25 设计：子 agent 结果跨 turn 边界保留）。

**方案**（最小改动）：在**接收端（AgentActor 的两个 drain 点）加「完全空闲 gate」**，不空闲则延迟投递（不消费 queue）；新增事件驱动重检（子 agent 完成 → ExternalEvent → turn 结束 → 重检）+ 周期兜底扫描器（MailQueueDrainer，仿 TaskStuckWatcher）处理卡死/僵死子 agent 的 force 超时。判定函数为纯函数，可单测。immediate/ask 模式与 FIFO/持久化语义完全不受影响。

---

## 1. 现状分析（代码实证）

### 1.1 Mail queue 投递链路

**发送端**（`src/main/scala/nebflow/core/tools/MailTool.scala`）：

| 函数 | 行号 | 职责 |
|---|---|---|
| `deliverQueue` | L500-528 | 路由 + 权限（canMailNebula / checkTeamScope） |
| `resolveAndQueue` | L531-571 | team 名→lead session；短名→`resolveSessionId`；"Nebula"→`queueToNebula` |
| `queueToNebula` | L587-603 | 从 agentRegistry 找 Root kind 记录的 sessionId |
| `queueToSession` | **L611-676** | 核心：四步投递 |

`queueToSession`（L611）四步：
1. `MailQueueStore.append(sessionId, item)`（L636）—— 持久化到 `~/.nebflow/sessions/<sid>/mail-queue.json`（重启不丢）
2. `onMailDelivered`（L638）—— mailbox 记录 + WS 事件
3. `liveActorOrActivate`（L641）—— 取活 actor 或冷激活（`activateAgent` L945-1092）
4. **`ref ! AgentCommand.MailQueued(item, senderSessionId)`（L643）无条件发送** ← **投递时机点，无任何空闲检查**

**接收端**（`src/main/scala/nebflow/agent/AgentActor.scala`）—— 三个状态三种处理：

| 状态 | 位置 | 行为 |
|---|---|---|
| **idle** | L881-923 | `MailQueued` → 磁盘 head 匹配（幂等守卫 L885-886）→ `removeHead`（L902）+ `UserInput`（L908, delivery="queue"）→ **立即开始新 turn** |
| **processing** | L2067-2079 | 仅 `pendingMailQueueCount + 1`，不注入 |
| **frozen** | L3408-3421 | 计数 +1 排队，恢复后处理 |

**turn 边界 drain**（`finishTurnCont`，L2609-2690）：processing → idle 时若 `pendingMailQueueCount > 0` → load head → `removeHead`（L2642）→ 注入 `UserInput`（新 turn）。**同样无 barrier 检查**。

### 1.2 「空闲」判定现状 —— 现有数据全在，但无组合查询

| 数据 | 位置 | 内容 | 局限 |
|---|---|---|---|
| `busyMap` | `core/flow/FlowTreeActor.scala` L45, L227-229 | sessionId → busy（turn 级） | **单层**：`markTeamBusy/Idle`（AgentActor L2817-2825）只在 turn 开始/结束切换，不含子 agent |
| `agentRegistry` | `agent/SharedResources.scala` L69 | `Ref[IO, Map[String, AgentRecord]]` | 统一注册表，含 status/parentRef/parentSessionId/outstandingSubagents/pendingEventCount |
| `AgentRecord.status` | `agent/protocol.scala` L234-296 | Idle / Processing / WaitingForUser / Frozen / Error | `touchRegistryActivity`（AgentCore L1626-1638）在 pipeLlmCall 写 Processing、finishTurnCont 回 idle 写 Idle |
| `AgentRecord.outstandingSubagents` | protocol.scala L276 | barrier 快照 | `touchBarrierSnapshot`（AgentActor L194-207）在 spawn 计数 / ExternalEvent 三分支刷新 |
| `RunningFlowRegistry` | `core/flow/RunningFlowRegistry.scala` L152 | `list: IO[List[RunningFlow]]`，status Running/Completed/Failed/Cancelled | **`RunningFlow.sessionId` 恒为 None**（`registerFlow` FlowDagExecutor L880-891 未传）——无法直接反查 flow 归属 |

**子 agent 在飞的权威数据源**（关键实证）：

| 子 agent 类型 | 注册 | 终态清理 | parentRef |
|---|---|---|---|
| Delegate（background） | `DelegateTool.spawnBackground` L449-463 → `AgentKind.Delegate` | `BackoffSupervisor.notifyParentAndStop` L356：`agentRegistry.update(_ - subagentId)` 与父 ExternalEvent **同步** | 派遣者 ref |
| SubTask worker | `SubTaskTool.spawnWorker` L315-330 → `AgentKind.SubTask` | 同上（BackoffSupervisor） | 派遣者 ref |
| Flow 节点 | `FlowDagExecutor.executeAgent` L985-989 → `AgentKind.Flow` | L1018：`agentRegistry.update(_ - sessionId)` + stop | **触发 agent 的 ref**（`RunFlow.replyTo`） |
| Ephemeral | `EphemeralAgentRunner` L92-101 → `AgentKind.Ephemeral` | L110 移除 | **当前 None**（独立运行） |

⇒ **「子记录存在（parentRef = 目标）＝ 子树未收尾」成立**：Delegate/SubTask/Flow 终态时 registry 移除与结果通知原子同步，不存在「子 agent 已完成但记录残留」的正常窗口。

**flow 归属**：flow 节点记录（AgentKind.Flow，parentRef = 触发 agent）覆盖 flow 运行期绝大部分时间；`RunningFlow.sessionId = None` 造成**节点间隙窗口**（串行节点 A 完成 → B 未启动的毫秒~秒级间隙）无法关联。需补 `RunningFlow.sessionId`（改动 8）。

### 1.3 问题机制（实证链）

1. Manager turn 结束 → `finishTurnCont` L2761-2765：`markTeamIdle` + `touchRegistryActivity(Idle)` → **busyMap 清除、registry 显示 Idle**
2. 但 Manager 派遣的子 agent 可能仍在飞：L2756-2793 明确 `subagentsInFlight` 时 Completed 债务 **PARKED**、`outstandingSubagentResults` **跨 turn 边界保留**（#25 设计）——**turn 结束 ≠ 任务结束**
3. idle 态 `ExternalEvent` handler（L644-751）：`outstanding > 0` 时子 agent 结果 **HOLD** 在 `pendingEvents`（L710-728）——idle + 子 agent 在飞 = 「等待子 agent batch」状态
4. 此状态 queue Mail 到达 → idle `MailQueued` handler（L881）**无 gate 直接 drain** → 新任务注入等待窗口 ← **用户裁定的问题点**

---

## 2. 目标语义细化（可执行定义）

### 规则 ② —— team 成员间的 queue Mail（目标 agent T 及其所有子 agent 全停）

```
isAgentIdle(T) =
  registry(T).status == Idle                                    // ① 自身不在 turn
  ∧ registry(T).outstandingSubagents == 0                        // ② 无在飞子 agent batch（barrier 快照）
  ∧ ¬∃ c ∈ registry: c.parentRef == T.ref                        // ③ 无在飞子 agent（递归由存在性覆盖）
       ∧ c.kind ∈ {Delegate, SubTask, Flow, Ephemeral, Plan}
  ∧ ¬∃ f ∈ RunningFlowRegistry: f.status == Running              // ④ 无关联 flow running（需改动 8）
       ∧ f.sessionId == Some(T.sid)
```

**关于递归**：子记录存在 = 子树未收尾（§1.2 实证），**存在性检查即足够**，无需递归展开整棵子树。防御性增强：对每个子记录再查 `outstandingSubagents == 0`（子 agent 自身 idle 但孙 agent 在飞的残留场景）——一阶即可，深度受 `MaxDepth = 5` 限制不会爆炸。

**kind 白名单（必须精确）**：只计 `{Delegate, SubTask, Flow, Ephemeral, Plan}`。**必须排除 Team / Root**——`MailTool.activateAgent` 注册 Mail 激活的 team 成员时 `AgentRecord.parentRef = ctx.agentActorRef`（发送者 ref，MailTool L1068-1077）：若不排除 Team kind，A 激活 B 后 gate 检查 A 会误判「A 有下属在飞」→ 永远不投递。

### 规则 ① —— Nebula → Manager 的 queue Mail（等整个 team 全停）

```
isTeamIdle(team) = ∀ agent ∈ TeamSessionRegistry.sessionIdsOf(team): isAgentIdle(agent)
```

`sessionIdsOf(instance)` 已有（FlowTreeActor L205-206），返回 team 内全部 sessionId（Manager + 成员）。

**统一解释（建议，待用户确认）**：目标 agent 是某 team 的 Manager（`TeamSessionRegistry.isManager(sid)`）时一律用 team 范围 gate（覆盖 Nebula→Manager 与成员→Manager）。成员→成员仍用自身子树。这是裁定 ① 精神的推广，避免「Nebula 发的要等全 team、成员发的只等 Manager 自身」的语义裂缝。

---

## 3. 改动方案（最小改动）

### 3.0 总体思路：gate 放接收端，发送端零改动

发送端 persist + 发 `MailQueued` 的契约不变（`activateAgent` 冷激活恢复 L1080-1083 依赖 head 触发）；**gate 在 AgentActor 两个 drain 点拦截**，可同时覆盖冷启动、重启恢复、frozen 恢复全部路径。判定所需数据（agent 内部 `outstandingSubagentResults` + registry 快照）接收端两者都有。

### 改动 1 —— 判定函数（新增，纯函数可单测）

新文件 `src/main/scala/nebflow/core/flow/MailIdleGate.scala`（或并入 MailTool）：

```scala
object MailIdleGate:
  /** kind 白名单：只计任务型子 agent；Team/Root 是协作对等体，绝不计数 */
  private val taskKinds = Set(AgentKind.Delegate, AgentKind.SubTask, AgentKind.Flow,
                              AgentKind.Ephemeral, AgentKind.Plan)

  /** 规则②：目标 agent 自身子树完全空闲 */
  def isAgentTreeIdle(
    sid: String,
    registry: Map[String, AgentRecord],
    runningFlows: List[RunningFlow]  // 可选：改动 8 后传入
  ): Boolean =
    registry.get(sid).exists { rec =>
      rec.status == AgentStatus.Idle &&
      rec.outstandingSubagents == 0 &&
      !registry.values.exists(c => c.parentRef.exists(_ == rec.ref) && taskKinds.contains(c.kind) &&
                                   c.outstandingSubagents == 0) // 一阶防御
      // && !runningFlows.exists(f => f.status == NodeStatus.Running && f.sessionId.contains(sid))
    }

  /** 规则①：整个 team（Manager + 成员）及其子树完全空闲 */
  def isTeamTreeIdle(teamName: String, registry: Map[String, AgentRecord],
                     runningFlows: List[RunningFlow]): IO[Boolean] =
    TeamSessionRegistry.sessionIdsOf(teamName).map(_.forall(isAgentTreeIdle(_, registry, runningFlows)))
```

### 改动 2 —— idle 态 `MailQueued` handler 加 gate（AgentActor L881-923）

gate 不通过 → **不 removeHead、不注入**，仅 `pendingMailQueueCount = 1`（保持「有货未投递」状态），回 idle 等事件驱动重检：

```scala
case AgentCommand.MailQueued(item, _, _) =>   // 签名加 force: Boolean
  val sid = state.sessionId.getOrElse("")
  if item.force || fullyIdle(state) then
    // —— 现有 drain 逻辑原样（L884-922）——
  else
    // 延迟：不消费 queue，保持计数，等待子 agent 完成事件驱动重检
    logAgentEvent(..., "mail-queued-deferred", s"from=${item.from} subtree busy")
    IO.pure(idle(agentDef, resources, depth, parentRef,
      state.copy(execution = state.execution.copy(pendingMailQueueCount = 1))))
```

`fullyIdle(state)`：用 `resources.agentRegistry.get` 快照 + `state.execution.outstandingSubagentResults`（agent 内部权威）调 `MailIdleGate.isAgentTreeIdle`；目标是 Manager 时（`TeamSessionRegistry.isManager`）调 `isTeamTreeIdle`。

### 改动 3 —— `finishTurnCont` queue drain 分支加 gate（L2609-2690）

进入 `pendingMailQueueCount > 0` 分支前先查 `fullyIdle`；不空闲 → **跳过 drain**（保留 count），直接落到回 idle 分支（L2691+）。注意分支顺序：gate 检查放在「drainedEvents / pendingImmediateInputs」之后、queue drain 之前，保持优先级不变（事件 > immediate > queue）。

### 改动 4 —— 事件驱动重检（已有路径天然覆盖，零新增）

- 子 agent 完成 → `ExternalEvent`（L644-751）→ batch 完成注入 turn → turn 结束 → `finishTurnCont` queue drain 分支（改动 3 覆盖）
- flow 完成 → `ImmediateInput`（FlowDagRunner L58-72）→ 注入 turn → 同上
- **静默期兜底**（无任何事件到达，如子 agent 卡死 / phantom barrier）→ 改动 5

### 改动 5 —— 周期兜底 `MailQueueDrainer`（新增，GatewayMain 挂载）

仿 `TaskStuckWatcher`（GatewayMain L654-662 挂载点旁）：

```scala
object MailQueueDrainer:
  /** 周期扫描：queue 有货 → 空闲则发 MailQueued 触发 drain；
    *  非空闲且超过 forceThreshold → 发 MailQueued(force=true) 强制投递 */
  def run(resources: SharedResources, interval: FiniteDuration, forceThreshold: FiniteDuration): IO[Unit]
```

- **扫描范围**：内存注册表 `Ref[IO, Set[String]]`（`MailQueueStore.append` 时登记；`removeHead` 后队列空时注销）+ 启动时全量扫 `~/.nebflow/sessions/*/mail-queue.json` 一次（重启恢复注册表）
- **动作**：queue 有货 → `isAgentTreeIdle`（Manager 用 team 范围）→ 空闲：`ref ! MailQueued(force=false)`（经 `liveActorOrActivate` 保活）；非空闲且注册超 `forceThreshold`：`ref ! MailQueued(force=true)`
- **force 阈值**：默认 **10min**（> TaskStuckWatcher 恢复链 ~10min：Stop→hard-cancel→supervisor Cancelled），`nebflow.json` 可配
- 错误自愈 + `>>` 递归栈安全（同 TaskStuckWatcher 模式）

### 改动 6 —— `MailQueued` 消息签名加 force（protocol.scala L207-210）

```scala
case class MailQueued(item: MailQueueItem, fromSessionId: String, force: Boolean = false)
```

向后兼容：现有调用点（MailTool L643、activateAgent L1082）默认 false 零改动。head-check 幂等守卫（L885-886）天然防双触发（Drainer 与发送端并发发同一 item 时仅 head 胜出）。

### 改动 7 —— Manager 的 team 范围 gate

判定「自身是 Manager」在 AgentActor 内完成（`TeamSessionRegistry.isManager(selfSid)`），**发送端零改动**。规则① 自动覆盖；规则② 成员→成员走自身子树。语义统一问题见 §5.3。

### 改动 8 —— `RunningFlow.sessionId` 关联（可选增强，补节点间隙窗口）

- `FlowDagRunner.RunFlow` 加 `callerSessionId: String = ""`（FlowDagRunner.scala L21-34）
- `FlowTriggerTool.call`（L169）传 `ctx.sessionId.getOrElse("")`
- `FlowDagExecutor.registerFlow`（L880-891）设置 `RunningFlow.sessionId = Option(callerSessionId).filter(_.nonEmpty)`
- `MailIdleGate` ④ 查 `RunningFlowRegistry.list`

向后兼容（默认 None → 旧记录无关联，gate 退化只靠 flow 节点记录）。

---

## 4. 边界处理

| 边界 | 处理 |
|---|---|
| **子 agent 卡死（Processing 态）** | 现有 `TaskStuckWatcher` 恢复链（Stop → hard-cancel → supervisor Cancelled → barrier 释放 → registry 移除）→ gate 自然放行 |
| **子 agent 僵死（idle 态残留 / phantom barrier, issue #31）** | TaskStuckWatcher 只判 Processing，不覆盖；由 **MailQueueDrainer force 超时**兜底——queue 不因下属清理失败而永卡 |
| **多级嵌套（孙 agent 在飞）** | 子记录存在 = 忙（§1.2）；一阶 outstanding 检查兜底 |
| **flow 运行中** | flow 节点记录（AgentKind.Flow, parentRef）覆盖主要时段；改动 8 补节点间隙 |
| **跨 team** | queue mail 发送端已有 `checkTeamScope`（L870-890）权限；gate 只查目标侧子树，无跨 team 语义 |
| **immediate 模式** | `sendMail`（L1137-1165）走 `ImmediateInput`，不经 MailQueued/gate——**完全不受影响**（busy 时合并当前 turn 语义不变） |
| **INTERRUPT** | `MailTool.call` 已强制 INTERRUPT 走 immediate（L109 schema 描述）；queue 模式不会出现 INTERRUPT，打断语义不受影响 |
| **frozen 态** | 冻结期间 MailQueued 计数排队（L3408-3421），恢复后走 idle/processing gate——一致 |
| **FIFO 顺序** | gate 拦截**不 removeHead** → 磁盘队列顺序保持 |
| **重启不丢** | `MailQueueStore` 磁盘持久化不变；`activateAgent` 恢复 head 触发（L1080-1083）过 gate（冷启动无子 agent → 立即投递） |
| **dedup** | `MailDeliveryDedup` 在 drain 时执行（L892）；gate 拦截不消费指纹窗口；Drainer 与发送端并发双触发由 head-check（L885-886）幂等守卫 |
| **Mail 激活的 team 成员** | `AgentRecord.parentRef = 发送者 ref`（MailTool L1068-1077）——**kind 白名单排除 Team 是硬性要求**（§2） |

---

## 5. 验收条件（全部二值可断言）

### 5.1 冒烟测试（硬性条件）

- [ ] **AC-1 编译**：`sbt compile` 通过
- [ ] **AC-2 现有测试全绿**：`sbt test`（含 MailQueueNebulaSpec / ColdQueueActivationSpec / MailActivateLifecycleSpec / MailDedupWiringSpec 等 mail 相关既有 spec）
- [ ] **AC-3 真实启动 + 端到端**：启动 gateway → Nebula 向全空闲 Manager 发 queue Mail → Manager 收到并执行（curl/WS 断言 mailbox 记录 + 消息注入）

### 5.2 单元测试（`MailIdleGateSpec`，munit 纯函数）

- [ ] **AC-4**：`isAgentTreeIdle` 判定矩阵——自身 Idle/Processing、子记录存在/不存在、子记录 kind ∈ {Delegate, SubTask, Flow} 计数 / {Team, Root} **不计数**、`outstandingSubagents` 0/n、一阶防御（子记录 outstanding > 0 仍判忙）
- [ ] **AC-5**：`isTeamTreeIdle`——team 内任一成员（含其子树）忙 → false；全空 → true

### 5.3 集成测试（新 spec，仿 ColdQueueActivationSpec 风格）

- [ ] **AC-6 场景 A（规则①）**：Manager processing 中 queue Mail → 不投递（count+1）；turn 结束但 SubTask worker 在飞（outstanding=1）→ **仍不投递**；worker 完成（ExternalEvent → barrier 归零 → 注入 → turn 结束）→ **投递**（head 被消费、UserInput 注入）
- [ ] **AC-7 场景 B（规则②）**：目标成员派遣 SubTask 后 idle（outstanding=1）→ queue Mail 不投递；`ExternalEvent` 到达（outstanding=0）→ 投递
- [ ] **AC-8 场景 C（flow）**：Manager 触发 flow（RunningFlowRegistry running + Flow 节点在 registry）→ queue Mail 不投递；flow 完成（节点移除 + status Completed）→ 投递
- [ ] **AC-9 场景 D（force 兜底）**：构造 phantom barrier（registry 残留 idle 子记录）→ 模拟时间超 forceThreshold → MailQueueDrainer 发 `MailQueued(force=true)` → **强制投递**
- [ ] **AC-10 场景 E（重启恢复）**：queue 有货 + target 冷 → `activateAgent` 恢复 head 触发 → gate 判定（冷启动无子 agent）→ 立即投递
- [ ] **AC-11 场景 F（回归）**：多件 queue FIFO 顺序保持；immediate Mail 在目标 busy 时照常合并当前 turn（不经 gate）；dedup 窗口行为不变

### 5.4 自查清单

- [ ] 冒烟测试用真实启动（非 oneshot）
- [ ] 每条可自动化、二值判断
- [ ] 覆盖启动流程（gate 在冷激活/重启恢复路径同样生效）
- [ ] 覆盖迁移/兼容（immediate 语义、FIFO、持久化、dedup 不受影响）

---

## 6. 风险与取舍

| 风险 | 说明 | 取舍 |
|---|---|---|
| **R1 投递延迟增加** | queue Mail 可能等子 agent 完成（秒~分钟级），串行任务链的总时长增加 | queue 语义本就是「串行任务链，前序完成后才处理」——等目标空闲符合语义；关键路径可用 immediate/ask 绕过（immediate 不受影响） |
| **R2 force 兜底双刃** | 超时强制投递可能打断长挂子 agent 场景（如 run_in_background 长命令） | queue Mail 卡死比打断更糟（Manager 认知负担的根源正是「任务到达却不处理」）；force 阈值可配（默认 10min） |
| **R3 规则① 范围歧义** | 裁定② 字面「目标 agent 及其子 agent」与 ①「Nebula→Manager 等全 team」在「成员→Manager」场景冲突 | **建议统一：目标是 Manager 一律 team 范围**（① 精神的推广）——待用户确认 |
| **R4 kind 误判** | Mail 激活的 team 成员 `parentRef = 发送者`——gate 若不排除 Team kind 会永久误判忙 | kind 白名单硬编码 + AC-4 单测锁死 |
| **R5 RunningFlow.sessionId 补丁** | 运行中旧 flow 无 sessionId → gate 退化（只靠 flow 节点记录） | 可接受（节点记录覆盖绝大多数时间）；新 flow 全量关联 |
| **R6 静默期** | 「idle + gate 拦截 + 零事件」时 queue 悬挂（改动 4 无触发） | MailQueueDrainer 周期兜底（间隔默认 60s，仿 TaskStuckWatcher） |

---

## 7. 待用户确认点

1. **Q1**：规则① 范围统一——「目标是 Manager（含成员→Manager）一律等全 team 空闲」是否符合意图？（还是严格按裁定字面：仅 Nebula→Manager 用 team 范围？）
2. **Q2**：force 兜底阈值默认 10min 是否可接受？（可配）
3. **Q3**：改动 8（RunningFlow.sessionId 关联）是否纳入本期？（不纳入则 flow 判定只靠节点记录，节点间隙有极小的误投递窗口）
4. **Q4**：MailQueueDrainer 周期兜底是否本期实现？（不实现则依赖事件驱动 + TaskStuckWatcher，卡死子 agent 场景下 queue 会永卡——不推荐省略）

## 用户裁定（2026-08-25 19:57，AskUser 确认）

「按我的要求来，Nebula是全Team，Team内是看单独的那个agent，不要有强制，我们只要能保证mail不丢失就行。Q3纳入。子agent卡死我们已经有检测和管理机制了。那是其他系统设计的问题。我们专注我们的实现。」

| 项 | 裁定 | 实施含义 |
|---|---|---|
| Q1 | **不统一，按发送者区分** | Nebula（root）→ Manager 的 queue：等**全 team** 空闲投递；team 内（任意成员间，含成员→Manager）：等**目标 agent 单独**（自身 + 子树）空闲投递。无强制统一 |
| Q2 | **不适用** | 无 force 阈值（MailQueueDrainer 不做，见 Q4） |
| Q3 | **纳入** | RunningFlow.sessionId 关联本期做（flow 在飞检测完整） |
| Q4 | **不做兜底** | 不做 MailQueueDrainer。子 agent 卡死由既有检测管理机制负责（TaskStuckWatcher 恢复链），那是其他系统设计问题；本实现专注「queue 不丢」（持久化已保证）+ 空闲 gate 投递 |

**边界澄清**：卡死子 agent 由 TaskStuckWatcher 清掉后，子 agent 完成事件 → turn 结束 → finishTurnCont 事件驱动重检自然恢复投递（方案 §4.3 无需周期兜底）。实施时删除 MailQueueDrainer 相关设计（§4.4），保留 §4.1-4.3 + 改动 8（flow 关联）。

---

## 附：关键代码索引

| 文件 | 行号 | 内容 |
|---|---|---|
| `core/tools/MailTool.scala` | L500-676 | queue 投递链路（deliverQueue/resolveAndQueue/queueToNebula/queueToSession） |
| `core/tools/MailTool.scala` | L945-1092 | activateAgent（冷激活 + 恢复 head 触发 L1080-1083 + Team 注册 parentRef L1068-1077） |
| `agent/AgentActor.scala` | L881-923 | idle 态 MailQueued drain（无 gate） |
| `agent/AgentActor.scala` | L2067-2079 | processing 态 MailQueued 计数 |
| `agent/AgentActor.scala` | L2609-2690 | finishTurnCont queue drain（无 gate） |
| `agent/AgentActor.scala` | L2756-2793 | #25：子 agent 在飞时 Completed PARKED / outstanding 跨 turn 保留 |
| `agent/AgentActor.scala` | L2761-2765 | turn 回 idle：markTeamIdle + touchRegistryActivity(Idle) |
| `agent/AgentActor.scala` | L644-751 | idle 态 ExternalEvent（子 agent 结果 barrier hold L710-728） |
| `agent/AgentActor.scala` | L194-207 | touchBarrierSnapshot（registry.outstandingSubagents） |
| `core/flow/FlowTreeActor.scala` | L32-45, L205-229 | TeamSessionRegistry（busyMap / sessionIdsOf / markBusy/Idle） |
| `agent/protocol.scala` | L207-210, L223-296 | MailQueued 消息 / AgentKind / AgentRecord |
| `agent/BackoffSupervisor.scala` | L319-361 | notifyParentAndStop（registry 移除与通知同步） |
| `core/entity/FlowDagExecutor.scala` | L985-1018 | flow 节点注册/移除（AgentKind.Flow, parentRef） |
| `core/entity/FlowDagExecutor.scala` | L880-891 | registerFlow（sessionId 未传 = None） |
| `core/flow/FlowDagRunner.scala` | L21-73 | RunFlow / 完成 ImmediateInput |
| `core/flow/RunningFlowRegistry.scala` | L112-172 | register/list/status |
| `agent/AgentCore.scala` | L1626-1638 | touchRegistryActivity（Processing/Idle 写入） |
| `gateway/GatewayMain.scala` | L650-673 | 扫描器挂载点（TaskStuckWatcher / FreezeScheduler） |
| `core/processor/TaskStuckWatcher.scala` | L53-252 | 卡死恢复链（Stop/hard-cancel/supervisor Cancelled） |
