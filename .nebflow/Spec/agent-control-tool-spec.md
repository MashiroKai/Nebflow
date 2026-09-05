# AgentControl 工具设计规格书 — 后台 agent 管控（list / status / cancel / restart）

> 版本日志：
> - v1.1（2026-08-19 11:50）独立探索复核后增量修订：所有行号/机制引用经二次核实无误；补 §3.2b persistent delegate 取消语义；补 §3.6 turn 时长上限检测（「活性≠进度」假说 a 的对策）；§4 矩阵细化 persistent 行
> - v1 draft（2026-08-19 11:33）初稿 · 待用户批准
>
> 起因：用户原话 2026-08-19 11:21「后台卡死的 agent，你能控制去掉或者重启吗？」——Nebula 派出 3 个 LowCost Explorer 6 小时无产出只能干等；1 个 Explorer 返回纯空白静默结束；TaskStuckWatcher 只自动处理 Processing 卡死，Nebula 无主动查看/取消/重启手段。

---

## 0. 预期变更展示

### 行为变更说明（现在 vs 改完后）

| 场景 | 现在 | 改完后 |
|---|---|---|
| 派出去的 sub-agent 6 小时没消息 | Nebula 只能干等，或翻日志猜状态。无法回答"它还活着吗、卡在哪" | Nebula 调 `AgentControl(action=list)` 一次拿到所有后台 agent 的类型/状态/启动时间/最后活动时间/卡死判定，可以自己决定处置 |
| 某个后台 agent 确认废了 | 没有任何手段终止。它会一直占着会话和 token 直到自然结束 | `AgentControl(action=cancel, sessionId=...)` — 中断它当前正在生成的 LLM 调用，终止其会话，父会话立刻收到「已取消」通知（不会一直等它的结果） |
| agent 大概率是卡死但任务还有价值 | 只能等 TaskStuckWatcher 10 分钟阈值自动重启，或放弃整个任务重派 | `AgentControl(action=restart, sessionId=...)` — 立即终止当前卡死的 turn，但保留它已完成的全部工作记录，从断点继续跑（复用现有崩溃自愈机制） |
| TaskStuckWatcher 自动重启了一个卡死 sub-agent | 前端和 Nebula 都不知道发生过（只有日志） | 自动重启时同步广播 `taskStuck` WS 事件（前端可见），Nebula 事后用 list 也能看到 retryCount |

### 消息流架构图

![AgentControl 消息流](/tmp/agent-control-flow.svg)

---

## 1. 现状盘点（复用面）

### 1.1 agentRegistry — 统一注册表（list 的数据源，已基本够用）

`SharedResources.agentRegistry: Ref[IO, Map[String, AgentRecord]]`（`src/main/scala/nebflow/agent/protocol.scala:218`）：

```scala
case class AgentRecord(
  sessionId: String,                      // 唯一身份（taskId 同源）
  ref: ActorRef[AgentCommand],            // 运行时 actor
  kind: AgentKind,                        // Root | Team | Flow | Delegate | Ephemeral | Plan | SubTask
  rootSessionId: String,                  // 权限桶 + 继承链锚点
  parentRef: Option[ActorRef[AgentCommand]],
  startedAt: Long = 0L,                   // ⚠ 大部分注册点没传 → 恒 0
  status: AgentStatus = Idle,             // Idle | Processing | WaitingForUser | Error
  lastActivityMs: Long = 0L               // 由 touchRegistryActivity 维护
)
```

活动戳维护（`AgentCore.touchRegistryActivity`，`AgentCore.scala:1317`）：LLM 调用开始置 Processing、每个流 chunk touch、工具执行完成 touch、turn 完成回 Idle、BashTool 长命令有进展时 touch（#319）。**即 status/lastActivityMs 已经是实时可靠的"活着吗/多久没动"数据源。**

注册点 5 处：DelegateTool（`DelegateTool.scala:417`）、SubTaskTool（`SubTaskTool.scala:290`）、FlowDagExecutor（`FlowDagExecutor.scala:744`）、EphemeralAgentRunner（`EphemeralAgentRunner.scala:71`）、WebSocketRoutes 根会话（`WebSocketRoutes.scala:162`）+ 团队挂载（Team）。

**缺口**：① startedAt 全部为 0（注册点用位置参数没传）；② 不存 supervisor（BackoffSupervisor）的 ref —— cancel 无法直达 supervisor；③ 无任务描述字段（在 SubAgentTaskStore 里）。

### 1.2 BackoffSupervisor — restart 的现成机制（@c85a2655 #317）

`src/main/scala/nebflow/agent/BackoffSupervisor.scala`：

- death-watch 子 actor；收到 `Terminated` → 退避（5s→60s，5min 内 maxRestarts=2 熔断）→ `sessionStore.loadMessagesForSession(subagentId)` 恢复消息 → `childSpawnFn(sys, recoveredMessages)` 重生 → 注入「Your previous turn was interrupted by a crash. Please continue your task from where you left off.」→ 无恢复消息时回退重注入原始 prompt（`BackoffSupervisor.scala:151-255`）。
- **TaskStuckWatcher 对卡死子 agent 就是发 `Stop` 走这条路**（`TaskStuckWatcher.scala:85-94`）——restart 语义已存在，只是没有人为入口。
- 终态统一走 `notifyParentAndStop`（`BackoffSupervisor.scala:273`）：父 `ExternalEvent(source="delegate"/"subtask", eventType, correlationId=subagentId)` + `subAgentTaskStore.updateStatus` + registry 移除 + 子 Stop + 自身退出。
- **缺口**：① 只认 Completed/Failed 两种终态，没有"取消"；② `notifyParentAndStop` 里 taskStatus 硬编码 completed/failed（`:284`）。

### 1.3 SubAgentTaskStore — 任务元数据（status 命令的第二个数据源）

`src/main/scala/nebflow/agent/SubAgentTaskStore.scala:21`：`taskId(=subagentId)/parentSessionId/agentName/prompt/description/status(running|completed|failed|restarting)/retryCount/spawnedAt/completedAt/lastError/source(delegate|subtask)`。按 `~/.nebflow/subagent-tasks/<parentSessionId>.json` 落盘，带写锁。
**缺口**：没有 public 的按 parent 查列表方法（readTasksSync 是 private）——需加 `listByParent`。

### 1.4 TaskStuckWatcher — 卡死自动检测

`src/main/scala/nebflow/core/processor/TaskStuckWatcher.scala`：30s 扫一轮；判定 = kind ∈ {Delegate, Ephemeral, Flow, SubTask} ∪ {Root} ∧ status==Processing ∧ lastActivityMs>0 ∧ 超 10min（`Defaults.scala:129`）。恢复：子 agent 发 Stop（→ supervisor 重启）；根 agent 广播 `taskStuck` WS 由用户决定。

**重要事实修正**：sub-agent（Delegate 类）的 Processing 卡死**已在覆盖内**——sub-agent 自己的 pipeLlmCall 会 touch 它的 registry 记录。6 小时事故不在"Processing 无活动"这一类，假说（需日志验证）：(a) provider 快速失败重试链每次重新 dispatch 都 touch 活动戳，永不判卡死，直到 TurnBudgetExceeded；(b) agent 早已 Idle/完成但返回空白（静默死亡），Nebula 没感知；(c) 挂在 barrier 等其他兄弟 agent。**这正是 AgentControl(list) 的价值——Nebula 主动可见，不依赖被动检测。**

### 1.5 可复用的取消/中断原语

- `AgentCommand.Stop(reason)`：三状态（idle/processing/planWaiting）都有 handler。processing 态 = `ctx.cancelCurrentTurn()`（杀 LLM 流/工具执行 fiber，`AgentActor.scala:1459`）→ `Behaviors.stopped`。
- `AgentCommand.Interrupt()`：打断当前 turn 但 actor 存活、回 idle（`AgentActor.scala:1380`）——不适用于 cancel（agent 会作为幽灵留在 registry）。
- `ExternalEvent`：父会话结果注入通道 + `outstandingSubagentResults` barrier（`isSubagentResult` 认 `source=="delegate"||"subtask"`，`AgentActor.scala:39`）——**cancelled 通知必须用 source="delegate"/"subtask" 才能正确释放 barrier 槽位，否则父会话如果正在等这批结果会永久挂起**。
- Flow 取消参照系：`RunningFlowRegistry.cancel(instanceId)`（`RunningFlowRegistry.scala:79`）+ 网关 `cancelFlow` 路由（`WebSocketRoutes.scala:2159`）——DAG 级取消会正确标记节点 cancelled 并穿透运行中节点；**单杀 Flow agent 会破坏 DAG 状态机，Flow 不走本工具的 cancel**。
- 工具注册：`ToolRegistry`（`registry.scala:39`）+ `AgentCore.NebulaExclusiveTools`（`AgentCore.scala:1342`，Nebula 专用注入点）。
- 前端参照：`activeAgents` 快照（`WebSocketRoutes.scala:2087` + `filterActiveAgents:4281`）已驱动 Sub-agents 下拉（`main.js` sessionBgAgents / bgagent-dropdown）——二期挂 stuck 徽标和取消按钮的现成挂点。

---

## 2. 工具 Schema

```
AgentControl（Nebula 专用，加入 NebulaExclusiveTools）
```

```json
{
  "action": { "type": "string", "enum": ["list", "status", "cancel", "restart"],
              "description": "list=列出所有后台 agent；status=单个详情；cancel=取消终止；restart=断点重启" },
  "sessionId": { "type": "string",
                 "description": "status/cancel/restart 必填。AgentRecord 的 sessionId（= subagentId = taskId），list 输出里给出" },
  "reason": { "type": "string",
              "description": "cancel/restart 可选。记录进任务文件 lastError 与父会话通知，供追溯" }
}
"required": ["action"]
```

### list 输出（工具结果文本，给 LLM 读）

```
Background agents (3):

| sessionId | kind | agent | status | stuck? | up | idle | retries | task |
|---|---|---|---|---|---|---|---|---|
| delegate-Explorer-a1b2c3d4 | Delegate | Explorer | Processing | ⚠ 47min | 2h13m | 47min | 1 | 调研 X 库的 API |
| subtask-Coder-e5f6a7b8 | SubTask | Coder | Processing | no | 8m | 12s | 0 | 实现解析函数 |
| team-member... (Manager busy) | Team | swift-dev | Processing | no | 1h | 3s | - | （只读） |

stuck? 判定 = Processing 且 idle 超过 10min（与 TaskStuckWatcher 同阈值）。
```

数据合成：agentRegistry（kind/status/startedAt/lastActivityMs）⋈ SubAgentTaskStore.listByParent（description/retryCount，按 taskId 关联）⋈ SessionStore.getSessionMeta（agentName）。

### status 输出

单个 agent 的完整卡片：registry 字段 + 任务元数据（prompt 前 200 字、retryCount、lastError）+ stuck 判定 + 若已死但任务文件还在（孤儿任务：registry 无记录但 task status=running）给出提示（「actor 已不存在，任务文件残留，可忽略或重启网关后由 findRunningTasks 清理」）。

---

## 3. cancel / restart 精确机制（actor 消息级）

### 3.1 前置数据结构改动（`protocol.scala`）

```scala
// 1) AgentRecord 加 supervisor 引用（Delegate/SubTask spawn 时填）
case class AgentRecord(
  ...,
  supervisorRef: Option[ActorRef[AgentEvent]] = None   // 新增，默认 None 不破坏现有注册点
)

// 2) AgentEvent 加取消终态（sealed trait，编译器强制所有 adapter 处理）
sealed trait AgentEvent
object AgentEvent:
  case class Completed(...) extends AgentEvent
  case class Failed(...) extends AgentEvent
  case class Cancelled(sessionId: String, reason: String) extends AgentEvent   // 新增
```

### 3.2 cancel 流程（Delegate / SubTask，有 supervisor）

```
AgentControl(cancel, sessionId, reason)
  ├─ 校验：registry 命中 ∧ kind ∈ {Delegate, SubTask} ∧ sessionId != 调用者自身 ∧ rootSessionId == 调用者 rootSessionId
  ├─ supervisorRef match
  │   Some(sup) → sup ! AgentEvent.Cancelled(sessionId, reason)     ← 正路
  │   None      → 降级：childRef ! Stop + registry 移除 + taskStore "cancelled"
  │               + 父 ExternalEvent(cancelled)（自补通知，见下）     ← 兜底（旧记录/异常路径）
  └─ 返回："已发送取消指令"（不等待，终态由 supervisor 异步落定）

BackoffSupervisor 新增 case：
  AgentEvent.Cancelled(sid, reason) →
    notifyParentAndStop(
      eventType   = "cancelled",
      payload     = "\"<description>\": cancelled by Nebula via AgentControl" + reason,
      metadata    = { failedSessionId: sid, retryable: false, failureType: "cancelled",
                      cancelled: true, reason: reason },
      taskStatus  = "cancelled"                       ← notifyParentAndStop 参数化（原硬编码 completed/failed）
    )
```

`notifyParentAndStop` 复用点（顺序）：父 `ExternalEvent(source="delegate"/"subtask", eventType="cancelled", correlationId=subagentId)`（source 保持原值 → barrier 正确释放）→ taskStore `status="cancelled", completedAt=now, lastError=reason` → registry 移除 → `childRef ! Stop`（processing 态即杀流 fiber）→ supervisor 自停。

**竞态说明**：Cancel 与 Completed/Failed 同时在 supervisor 邮箱 → 首个被处理的终态胜出，supervisor 停止后其余消息被丢弃（actor 框架语义），无双重通知、无泄漏。可接受，不引入额外协议。

### 3.2b cancel 流程（persistent Delegate，adapter ≠ BackoffSupervisor）

persistent delegate 的 replyTo 是 `persistentAdapter`（`DelegateTool.scala:620`），它 watch 子 actor 且**不重启**——当前 Terminated 一律通知 parent "Session crashed"（`:661-670`）。直接对 persistent 子 actor 发 Stop 会把用户取消误报为 crash。因此 `AgentEvent.Cancelled` 同样加进 persistentAdapter：

```
persistentAdapter 收 Cancelled(sid, reason) →
  parent ! ExternalEvent(source=地址, eventType="cancelled", payload="\"<desc>\": cancelled by Nebula", correlationId=sid)
  parent ! SessionUpdate(address, "cancelled")
  registry 移除 sid → child ! Stop → adapter 自停（不停 parent）
```

注意：persistent 完成事件的 source 是 actor 地址（非 "delegate"），`isSubagentResult` 不匹配——**无 barrier 槽位问题**（`countBarrierIncrements` 本就不给 persistent 计数，`AgentActor.scala:49-55`），通知路径自洽。

persistent restart：无 supervisor（Terminated 只报 crash），一期返回错误并建议「cancel 后重新 Delegate」；其会话历史仍持久化，用户可 Mail 重新激活或重新 spawn。

### 3.3 cancel 流程（Ephemeral，无 supervisor）

前置修复：`EphemeralAgentRunner` 的 bridge（`EphemeralAgentRunner.scala:39-48`）**必须 `ctx.watch(agentRef)`，`Terminated` 时 `deferred.complete(Left("cancelled"))`**——否则直接杀 agent 会让 runner 的 `resultDeferred.get` 永久挂起（调用方 fiber 泄漏）。这是 Ephemeral cancel 的硬前提。

修复后 cancel = `child ! Stop` → Terminated → bridge 完成 deferred（Left）→ runner 走正常清理（registry 移除、session 删除）→ 调用方收到 `[Agent '...' failed: cancelled]`。

### 3.4 restart 流程（Delegate / SubTask）

```
AgentControl(restart, sessionId, reason)
  ├─ 校验：同 cancel ∧ kind ∈ {Delegate, SubTask}（supervised 才可重启）
  ├─ taskStore.updateStatus(parentSessionId, sessionId, "restarting")   ← 立即可见
  └─ childRef ! AgentCommand.Stop("agent-control-restart")

此后 100% 走现有机制（零新代码）：
  Stop → cancelCurrentTurn（杀卡死的流/工具 fiber）→ actor stopped
       → supervisor 收 Terminated → 退避 5s
       → loadMessagesForSession(subagentId) 恢复全部历史（#317）
       → respawn → 注入 "[system] ... continue your task from where you left off."
```

**语义透明化**：手动 restart 消耗 supervisor 的 maxRestarts 计数（5min 窗口 2 次，第 3 次熔断为 failed 并通知父会话）。这是有意设计——反复手动重启说明任务本身有问题，熔断是止损。文档与工具返回值中都写明。

**parentSessionId 获取**：restart 需要 parentSessionId 写 taskStore。AgentRecord 没有该字段——从 `taskStore.findRunningTasks` 按 taskId 反查（量小、有索引文件），或 AgentRecord 顺手加 `parentSessionId: String` 字段（注册点已有值，推荐后者，一步到位且 list 也能用）。

### 3.5 卡死检测扩展（小改，TaskStuckWatcher）

现有 recover（`TaskStuckWatcher.scala:85`）对子 agent 只发 Stop + 日志。扩展：

1. Stop 前先广播 `taskStuck` WS 事件（现在只有根 agent 广播）：`{sessionId, kind, idleSecs, action: "restart"}` → 前端可提示「后台 agent 卡死，正在自动重启」。
2. supervisor 的 Terminated 分支已写 `status="restarting"`（`BackoffSupervisor.scala:197`），无需重复。

（可选 P2）Completed 且 extractLastAssistantText 为空 → metadata 加 `hasOutput: false`，父会话提示「空输出完成，可考虑 re-delegate」——对应"静默死亡"场景。

### 3.6 （可选 P1.5）turn 时长上限 ——「活性≠进度」检测

§1.4 假说 (a)（重试/循环链每步都 touch 活动戳 → 永不判卡）在现有 watcher 下是检测盲区：lastActivityMs 只证明"活着"，不证明"有进度"。对策（改动极小，但依赖假说被日志证实后启动）：

1. `AgentRecord` 加 `turnStartedAt: Long = 0L`；
2. `touchRegistryActivity`（`AgentCore.scala:1317` 的 modify 块）内部判断 Idle→Processing 转换时写入 `turnStartedAt = now`（无需改签名，registry 内部自洽）；finishTurn 回 Idle 时清零；
3. `TaskStuckWatcher.scan` 加第二判定：`Processing ∧ turnStartedAt > 0 ∧ now - turnStartedAt > Defaults.MaxTurnMs`（建议默认 2h，`config.maxTurnMs` 可配）→ 恢复动作与现有 stuck 完全相同（子 agent Stop 重启 / 根 agent 广播）。

**代价**：protocol 一个字段 + AgentCore 三行 + watcher 一个 filter 分支。**前置**：先按 §8.4 用本工具的 list 观测一轮真实事故轨迹，确认假说 a 成立再实施——若 6 小时事故其实是假说 b/c，此扩展无的放矢。

---

## 4. 安全边界矩阵

| kind | list | status | cancel | restart | 说明 |
|---|---|---|---|---|---|
| Delegate (ephemeral) | ✔ | ✔ | ✔（§3.2 supervisor 路径） | ✔（§3.4） | Nebula 直系，全权 |
| Delegate (persistent) | ✔ | ✔ | ✔（§3.2b adapter 路径，SessionUpdate 通知） | ✖（无 supervisor；提示 cancel+重新 Delegate） | 存活期内可 Mail，历史持久化 |
| SubTask | ✔ | ✔ | ✔ | ✔ | 团队 worker；取消时父(Manager)同样收到 cancelled ExternalEvent，不会干等 |
| Ephemeral | ✔ | ✔ | ✔（需 §3.3 前置修复） | ✖ | 无 supervisor，无断点恢复载体；返回明确错误说明 |
| Flow | ✔ | ✔ | ✖ | ✖ | DAG 生命周期归 FlowDagExecutor；错误信息指引「flow 取消走 cancelFlow(instanceId) / RunningFlowRegistry」 |
| Team | ✔（busy 的） | ✔ | ✖（一期） | ✖（一期） | Manager 正在协作中，乱杀破坏团队状态机；一期只读，二期评估"stuck 专属取消" |
| Root / Nebula 自身 | ✔ | ✔ | ✖ | ✖ | 自杀守卫：`sessionId == ctx.sessionId || kind == Root` → 拒绝 |

统一守卫（cancel/restart 共用）：
1. registry 查无此 sessionId → 自描述错误（附当前可管理列表的 sessionId）
2. kind 白名单（上表）→ 违反即拒绝并说明正确通道
3. `sessionId == 调用者 sessionId` → 拒绝（不可自杀）
4. `rec.rootSessionId != 调用者 rootSessionId` → 拒绝（权限桶越界，P2 原则）

---

## 5. 实施清单（文件级）

| # | 文件 | 改动 | 规模 |
|---|---|---|---|
| 1 | `agent/protocol.scala` | AgentRecord + `supervisorRef`、`parentSessionId` 字段；AgentEvent + `Cancelled` | 小 |
| 2 | `agent/BackoffSupervisor.scala` | 处理 `Cancelled`；`notifyParentAndStop` 的 taskStatus 参数化（支持 "cancelled"） | 小 |
| 2b | `core/tools/DelegateTool.scala`（persistentAdapter） | 处理 `Cancelled`：ExternalEvent + SessionUpdate("cancelled") + registry 移除 + child Stop + 自停（§3.2b） | 小 |
| 3 | `core/tools/DelegateTool.scala` | spawnBackground/spawnPersistent 注册 AgentRecord 时传 `startedAt=now, lastActivityMs=now, supervisorRef=Some(adapterRef), parentSessionId` | 小 |
| 4 | `core/tools/SubTaskTool.scala` | 同上（SubTask 注册点） | 小 |
| 5 | `core/flow/EphemeralAgentRunner.scala` | bridge `ctx.watch(agentRef)` + Terminated → deferred Left("cancelled")（Ephemeral cancel 前置修复，独立有价值） | 小 |
| 6 | `agent/SubAgentTaskStore.scala` | + `listByParent(parentSessionId): IO[List[SubAgentTask]]` | 极小 |
| 7 | `core/tools/AgentControlTool.scala`（新） | 四个 action 实现 + 校验 + 输出格式化 | 中 |
| 8 | `core/tools/registry.scala` | 注册 `"AgentControl" -> AgentControlTool` | 极小 |
| 9 | `agent/AgentCore.scala` | `NebulaExclusiveTools` + `"AgentControl"` | 极小 |
| 10 | `core/processor/TaskStuckWatcher.scala` | 子 agent 分支广播 taskStuck WS | 极小 |
| 11 | （可选 P1.5）`agent/AgentActor.scala` + protocol | `AgentCommand.QueryStatus(replyTo)` → 回 `AgentStatusSnapshot(turnIdx, currentTurnId, lastDispatchIsTool, messages.size, status, lastActivityMs)`，idle/processing 两态都安全应答——status 命令升级为"registry 快照 + 实时询问"合并视图 | 小 |
| 12 | （二期）前端 `main.js`/`bgAgentPopup.js` + `WebSocketRoutes` | activeAgents payload 扩展 status/lastActivityMs/startedAt；下拉 stuck 徽标 + 取消按钮；WS 路由 `cancelAgent` → 复用 supervisorRef 路径 | 中（本方案只列不做） |

死代码清理：`DelegateTool.backgroundAdapter`（`:463`，已被 BackoffSupervisor 取代）在加 AgentEvent case 时会被编译器点名——顺手删除。

---

## 6. 验收条件（全部二值化）

### A. 冒烟测试（硬性，第一项）

1. `sbt compile` 通过；`sbt Test/compile` 通过。
2. 真实启动：`sbt run`（或 `sbt stage` 后 `bin/nebflow`）→ 网关 8080 起来，`curl -sf http://localhost:8080/api/health`（或等效健康端点）返回 200。
3. WS 冒烟：连接 ws://localhost:8080/ws → 发送 `{"type":"getActiveAgents"}` → 收到 `activeAgents` 回包（list 数据链路的网关侧验证，不依赖 LLM）。

### B. 单元测试（新增，逐条列出断言）

1. **BackoffSupervisorCancelSpec**（参照 BackoffSupervisor 现有测试的 actor harness）：
   - 发送 `AgentEvent.Cancelled(sid, "test")` → 父测试 actor 收到 `ExternalEvent(source="delegate", eventType="cancelled", correlationId=sid)`，metadata 含 `failureType="cancelled"` ✔/✖
   - 同轮断言：agentRegistry 中 sid 已移除 ✔/✖；subagent-tasks 测试目录下对应 json `status=="cancelled"` ✔/✖；child 收到 Stop（actor 死亡）✔/✖
   - Cancelled 与 Completed 先后到达 → 只产生一次父通知 ✔/✖
2. **AgentControlToolSpec**（stub registry/taskStore）：
   - list：预置 Delegate+SubTask+Team 记录 → 输出含前两者行、Team 行标注只读 ✔/✖；startedAt/lastActivityMs>0 渲染出 up/idle 列 ✔/✖
   - cancel：kind=Root → ToolError ✔/✖；kind=Team → ToolError ✔/✖；kind=Flow → ToolError 且错误文本含 "cancelFlow" ✔/✖；sessionId=调用者自身 → ToolError ✔/✖；不存在 → ToolError 含现存可管理 id ✔/✖
   - restart：kind=Ephemeral → ToolError ✔/✖；合法 Delegate → taskStore 出现 "restarting" 且 Stop 已发 ✔/✖
3. **EphemeralBridgeWatchSpec**：spawn ephemeral → 直接 Stop agent → runner IO 正常返回 `Left("cancelled")` 而非挂起（`timeout(5s)` 断言）✔/✖
4. **TaskStuckWatcherSpec 扩展**：构造 Processing + lastActivityMs 超阈值的 Delegate 记录 → scan 后 WS hub 收到 `taskStuck` 广播（含 sessionId/kind）✔/✖（现有自动 Stop 断言不回归）✔/✖
5. **SubAgentTaskStatusSpec 回归**：现有用例全绿 ✔/✖

### C. 端到端测试（真实 ActorSystem + 真实文件存储，参照 StopHangTurnSpec/SubAgentTaskStatusSpec 模式）

1. **cancel 全链路**：spawn background delegate（LLM stub 挂起不返回）→ 父 turn 发起 → AgentControl(list) 含该 sid 且 status=Processing ✔/✖ → AgentControl(cancel) → 父测试 actor 收到 `cancelled` ExternalEvent ✔/✖ → AgentControl(list) 不再含该 sid ✔/✖ → 任务文件 status="cancelled" ✔/✖ → 父的 outstandingSubagentResults 归零（barrier 释放：后续 turn 正常注入不挂起）✔/✖
2. **restart 全链路**：spawn delegate（先完成 1 个工具 turn 产生持久化消息，再进入挂起 stub）→ AgentControl(restart) → ≤15s 内任务文件出现 "restarting" → 再出现 respawn 后 actor 存活（registry 记录刷新）✔/✖ → 日志含 `recovered N messages (resuming from checkpoint)` 且 N≥1 ✔/✖ → 子 actor 收到 "continue from where you left off" 注入 ✔/✖

### D. 回归

- `sbt test` 全量绿（重点：TaskStuckWatcherSpec、StopHangTurnSpec、SubAgentTaskStatusSpec、MailActivateLifecycleSpec、FlowDagExecutorCancelSpec）
- Delegate/SubTask 正常完成路径不受影响（ExternalEvent source 未变）

---

## 7. 风险与回滚

| 风险 | 等级 | 缓解 |
|---|---|---|
| AgentEvent 加 case → sealed 穷尽 match，遗漏 adapter 编译失败 | 低 | 编译器兜底；本方案已列出全部 4 处 match 点（BackoffSupervisor、persistentAdapter、EphemeralAgentRunner bridge、死代码 backgroundAdapter 顺手删） |
| Cancel 与自然完成竞态 | 低 | actor 邮箱串行 + 首终态胜出 + supervisor 退出后消息丢弃（§3.2），无双重通知路径 |
| 误杀 Team/Flow 破坏协作状态机 | 中 | 一期 kind 白名单硬禁（§4 矩阵），Flow 指引 cancelFlow；Team 二期单独评估 |
| restart 熔断误伤（用户想多次手动重启） | 低 | 语义即设计（§3.4）：5min/2 次上限；熔断后有明确 failed 通知，可重新 Delegate |
| Ephemeral 取消造成 runner fiber 挂起 | 中 | §3.3 前置修复为独立验收项 B3，未修不许开 Ephemeral cancel |
| supervisorRef 泄漏已停 supervisor 的引用 | 低 | notifyParentAndStop 先移除 registry 记录，后续 AgentControl 查不到即拒绝，不会向死 actor 发消息 |
| **回滚** | — | 全部为增量改动（新字段默认值、新 case、新工具、新文件），单 commit revert 即完全回滚；不动任何现有消息语义（source 字符串、barrier 判定、退避参数均不变） |

## 8. 遗留与二期

1. **前端配套**（二期）：bgagent-dropdown stuck 徽标 + 手动取消按钮 + taskStuck toast；activeAgents payload 扩展。
2. **QueryStatus 实时询问**（P1.5 可选）：status 命令拿 agent 内存态（turnIdx/lastDispatch"卡在 LLM 还是工具"），§5-11。
3. **turn 时长上限检测**（P1.5 可选，见 §3.6）：turnStartedAt + MaxTurnMs，对策「活性≠进度」盲区；先验证假说再实施。
4. **空白完成治理**（P2）：hasOutput=false 元数据 + 父会话 re-delegate 提示，对应"静默死亡"。
5. **6 小时事故根因**：建议另派诊断（读 nebflow-agent lifecycle 日志确认三个 Explorer 当时 registry status/lastActivityMs 轨迹，验证 §1.4 假说 a/b/c），本工具的 list 即为后续此类诊断的标配手段。
