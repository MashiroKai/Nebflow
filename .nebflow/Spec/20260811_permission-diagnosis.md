# Nebflow 权限系统设计缺陷诊断报告（Issue #12）

> 分支：`archive/scala`（`/Users/dev/Claude code/Nebflow`）
> 范围：仅分析，未修改任何代码
> 现象：Team agent（如 nebflow-project 的 Frontend）执行 Edit/Write 等不可逆工具时，权限请求被拒 4 次——用户看到权限卡片但批准无效，或卡片根本没到达用户窗口。

---

## 0. 结论摘要

权限系统存在 **3 个确定性缺陷** 和 **2 个次要缺陷**，共同导致"被拒 4 次"：

| # | 缺陷 | 位置 | 严重度 |
|---|------|------|--------|
| D1 | **ForwardPermission 无向上中继**（ForwardAskUser 有，ForwardPermission 没有），team 成员权限被 Manager 截留 | `AgentActor.scala` L353-367 / L1206-1220 | 致命 |
| D2 | **PermissionAnswered 路由断裂**：Mail 激活的 team agent 不在 `subAgentRegistry`/`rootAgents`，批准信号被路由到 ensureRootAgent 新建的 ghost agent 并静默丢弃 | `WebSocketRoutes.scala` L211-224 + `MailTool.scala` L446-468 | 致命 |
| D3 | **父 agent turn 生命周期清空 `pendingPermission`**：Manager/root 收到 ForwardPermission 保存的 deferred，在 `finishTurn`/`ToolsComplete` 时被 `interaction = None` 重置而丢失 | `AgentActor.scala` L847-859 / L1704-1705 | 致命 |
| D4 | **ForwardPermission 冲突时静默自动拒绝**：父已有 pendingPermission 时直接 `deferred.complete(false)`，第二个起的权限请求用户根本看不到 | `AgentActor.scala` L356-358 / L1207-1209 | 高 |
| D5 | **MailTool.activateAgent 硬编码 `safetyMode = "confirm-edits"`**：用户设置的 auto-edits 对 Mail 激活的 team 成员无效 | `MailTool.scala` L464 | 中 |

**被拒 4 次的机制**：team 成员（Mail 激活，`depth=1, parentRef=Manager`）Edit → `askUserPermission` 走 ForwardPermission 分支 → Manager 截留并广播卡片 → 用户批准 → 路由断裂（D2）或 pendingPermission 已被 turn 重置（D3）→ deferred 永不完成 → 5 分钟超时自动拒绝（`AgentCore.scala` L27, L590-613）→ LLM 收到 "Permission timed out, auto-denied" 后重试 → 反复被拒。

---

## 1. 权限请求完整链路图（实际行为，红色为断点）

![权限请求链路](/tmp/perm-flow.svg)

### 1.1 实际链路（故障路径）

```
Frontend(team) Edit ──1──▶ Manager ──2──▶ (截留, 无中继) ──3──▶ wsSend 卡片(sessionId=Manager)
                                                                        │
用户窗口 ◀──4── 按 sessionId 渲染（Manager 窗口未打开 → 仅持久化，不弹出）
用户允许/拒绝 ──5──▶ permissionAnswer{sessionId: Manager}
                        │
WebSocket routeToAgent ──6──▶ subAgentRegistry? 无 ── rootAgents? 无
                        ▼
              ensureRootAgent 新建 Ghost agent (depth=0, pendingPermission=None)
                        ▼
              7. PermissionAnswered 静默丢弃
                        ▼
              Frontend deferred 永不完成 → 5min 超时 → auto-denied → LLM 重试 ×4
```

### 1.2 对照：Delegate 子 agent（正确路径，修复过）

```
Delegate 子 agent ──▶ parentRef=caller ──▶ ForwardPermission ──▶ caller(root) 保存 deferred
用户批准 ──▶ routeToAgent(callerSessionId) ──▶ rootAgents 命中 ──▶ caller.complete(deferred) ──▶ 子 agent 继续
```
Delegate 子 agent 注册到 `subAgentRegistry`（`DelegateTool.scala` L331），且 `routeWsSend` 注入父 sessionId（L38-60）——**这套修复没有同步到 team agent 路径**。

### 1.3 对照：ForwardAskUser（有中继的正确设计）

`AgentActor.scala` L369-377 / L1223-1229：`parentRef match { case Some(grandParent) => relay; case None => render }`——AskUser 问题能一路中继到 root 渲染。**ForwardPermission 缺少同样的中继分支**。

---

## 2. 疑点逐项验证

### 疑点 a：Team agent 的 parentRef 是否正确设置？

**结论：缺陷（创建路径分裂 + 缺注册）**

team agent 有**两套创建路径**，行为不一致：

1. **`MailTool.activateAgent`（团队协作主路径，缺陷所在）** — `MailTool.scala` L414-474：
   ```scala
   AgentActor(
     agentDef, resources,
     wsSend = teamWsSend,          // L446: ctx.wsSend（发送者的 WS 链，最终 broadcast）
     depth = 1,                    // L459
     parentRef = ctx.agentActorRef,// L460: 发送 Mail 的 agent（Manager）！
     sessionId = Some(session.id),
     safetyMode = "confirm-edits"  // L464: 硬编码！
   )
   _ <- TeamSessionRegistry.registerActor(session.id, ref)   // L468: 只注册 TeamSessionRegistry
   ```
   团队链：`Nebula(depth=0) → Manager(depth=1, parentRef=Nebula) → Frontend(depth=1, parentRef=Manager)`。
   **关键**：`TeamSessionRegistry.registerActor` ≠ `subAgentRegistry`/`rootAgents`。

2. **`ensureRootAgent`（用户直接对话/WS 路由时）** — `WebSocketRoutes.scala` L89-168：`depth = 0, parentRef = None`，注册 `rootAgents`，wsSend=broadcast。

同一 team 会话，两种激活方式产生两种不同的权限行为：
- ensureRootAgent 激活 → depth=0 → 权限本地处理（`AgentCore.scala` L587 else 分支）→ 路由通（rootAgents 命中）
- MailTool 激活 → depth=1 + parentRef=Manager → ForwardPermission 发给 Manager → **路由断裂（D1+D2）**

Delegate 子 agent 参照（修复过）：`DelegateTool.scala` L331 `resources.subAgentRegistry.update(_ + (subagentId -> subagentRef))` + L38-60 `routeWsSend` 注入父 sessionId。**team agent 没有这层处理**。

### 疑点 b：权限请求转发时 sessionId 覆盖是否正确？

**结论：覆盖本身正确，但覆盖后的路由目标不可达（缺陷 D1/D2）**

- 子 agent 构造 permJson 时 sessionId=自己的 sessionId：`AgentCore.scala` L569 `"sessionId" -> state.sessionId.asJson`
- 父 agent 收到 ForwardPermission 覆盖为父的 sessionId：`AgentActor.scala` L360-365 / L1211-1216
- 前端卡片 `targetSid = permSessionId || activeView.sessionId`：`chat.js` L1401 → 批准发往**父 sessionId**

问题不在覆盖逻辑，而在：
1. **父是 team agent（Manager）时**：`routeToAgent(managerSid)`（`WebSocketRoutes.scala` L211-224）查 `subAgentRegistry`（只有 Delegate/Ephemeral agent，`SharedResources.scala` L58）→ 无；查 `rootAgents`（ensureRootAgent 维护）→ 无（Manager 由 MailTool 激活，没注册）→ **`ensureRootAgent` 新建一个幽灵 root agent** → PermissionAnswered 送达时该 agent `pendingPermission = None` → `AgentActor.scala` L351 静默丢弃。
2. **父是 root（Nebula）时**：路由命中，但 pendingPermission 会被 D3 清掉。

### 疑点 c：用户拒绝/批准后信号如何路回子 agent？

**结论：缺陷（两条断点，D2 + D3）**

正确设计意图：`ForwardPermission(deferred...)` 把 `Deferred[IO, Boolean]` 从子 agent 传给父（`protocol.scala` L96-101 注释明确说明"track the Deferred and route the frontend answer back"），用户回答后父 `deferred.complete(approved)`（`AgentActor.scala` L346-351 / L1186-1199），子 agent 的 `deferred.get`（`AgentCore.scala` L590-591）返回，继续执行工具。

实际断点：
- **D2**：`PermissionAnswered` 经 `routeToAgent` 找不到 Mail 激活的 Manager → ghost agent 丢弃（见疑点 b）
- **D3**：即使找到 Manager，若 Manager 的 turn 已结束，`finishTurnCont`（L1704-1705）`ExecutionContext.idle(newMessages, turnIdx)` 把 `interaction` 重置为 None；或 `ToolsComplete`（L847-859）`interaction = None` → `state.pendingPermission` 丢失 → `deferred.complete` 无人调用。

补充：`UserAnswered → pendingAskUserReplyTo` 链路（AskUser 工具）本身完整——`ForwardAskUser` 中继（L369-397/L1223-1250）→ root 渲染 → `UserAnswered`（L319-323/L1169-1183）→ `pendingAskUserReplyTo ! answers` → 子 agent 的 `AskUserQuestionTool` deferred。**但同样受 D3 影响**：root 在 processing 收到 ForwardAskUser 后若 turn 结束，`pendingAskUserReplyTo` 也会被清掉。

### 疑点 d：是否有静默吞掉权限请求的路径？

**结论：缺陷（3 条静默路径）**

1. **冲突自动拒绝（D4）**：`AgentActor.scala` L356-358（idle）/ L1207-1209（processing）：
   ```scala
   if state.pendingPermission.isDefined then
     deferred.complete(false)   // ← 子 agent 立即收到"拒绝"，用户根本没看到卡片
   ```
   多个 team 成员并发请求权限时，第 1 个占用父的 `pendingPermission`（单槽位），后续全部被静默拒绝。
2. **ghost agent 丢弃（D2）**：PermissionAnswered 路由到新建 agent，`pendingPermission=None` → `case None => IO.pure(idle(...))` 忽略。
3. **卡片到达但不可见**：前端 `main.js` L893-896——非活跃 session 的 askPermission **只 `saveMsg` 持久化，不渲染**。卡片 sessionId=Manager 的 sessionId，若 Manager 窗口未打开，用户看不到 → 超时拒绝（"权限请求没到达用户"的直接原因）。

### 疑点 e：askUserPermission 的超时/重试行为——被拒 4 次是用户真拒绝还是重复触发？

**结论：不是用户拒绝，是超时/静默拒绝的重复触发**

- `PermissionTimeout = 5.minutes`（`AgentCore.scala` L27）；超时后自动拒绝：L598-613 发 `permissionExpired` 并返回 "Permission timed out — auto-denied"
- `askUserPermission` 的 `permissionDeferredRef.modify`（L548-618）对**同一工具批次**是原子的（同一时刻一个 pending），但**跨批次**由 `AgentState.pendingPermission` 单槽位管理——D3 清空后，新批次的权限请求会重新走一遍 ForwardPermission，重新触发超时
- 每次 Edit 尝试：ForwardPermission → 卡片（可见或不可见）→ deferred 永不完成 → 5min 超时 → LLM 收到错误 → 换一种方式重试（再 Edit / Write）→ 再次超时 → **4 次即 4 轮超时/静默拒**，用户真拒绝的可能性低（用户看到卡片时点允许也没用）

### 补充验证：buildAllowedToolSet / safetyMode / ToolReversibility

- `buildAllowedToolSet`（`AgentCore.scala` L702-740）**不拦截** Edit/Write——只要 agentDef.tools 包含即可用。权限由 `ToolReversibility.isReversible`（`permissions.scala` L61-78）+ safetyMode 决定：confirm-edits 下 Edit/Write 不可逆 → 必须确认。**此部分设计正常**。
- **D5**：`MailTool.activateAgent` L464 硬编码 `safetyMode = "confirm-edits"`，忽略 session meta 中用户设置的 safetyMode（`createSession` 时 `cfg.safetyMode` 传入，见 `FlowTreeActor.scala` L376-381）。用户把 team 会话设为 auto-edits 后，Mail 激活的成员仍按 confirm-edits 要求 Edit 确认 → "已经统一过权限系统设计了"却行为不一致的典型来源。

---

## 3. 设计缺陷的根因分析

> 为什么"统一过权限系统设计"仍然坏？

**权限系统存在两套"子 agent"创建逻辑，只修复了其中一套：**

| 维度 | Delegate 子 agent（已修复） | Team 成员 agent（Mail 激活，未同步） |
|------|---------------------------|-------------------------------------|
| 创建 | `DelegateTool.scala` L308-331 | `MailTool.activateAgent` L454-468 |
| 注册 | `subAgentRegistry` ✓ | 仅 `TeamSessionRegistry` ✗ |
| wsSend | `routeWsSend` 注入父 sessionId ✓ | 仅注入 nodeSessionId，sessionId 走 toJson ✗ |
| parentRef | caller（单级） | Manager（可能仍是子 agent，需要多级） |
| 权限转发 | ForwardPermission → caller | ForwardPermission → Manager（截留，无中继） |

`ForwardPermission` 与 `ForwardAskUser` 的设计不对称：AskUser 有完整的"多级中继到 root"协议（`AgentActor.scala` L369-377/L1223-1229），Permission 只支持单级且无中继、无排队、冲突即拒。

---

## 4. 最小修复方案

### 修复 1（致命）：ForwardPermission 增加向上中继，对齐 ForwardAskUser

**文件**：`src/main/scala/nebflow/agent/AgentActor.scala`
**函数**：idle 的 `case AgentCommand.ForwardPermission`（L353-367）和 processing 的同一 case（L1206-1220）

把两个 handler 改为（与 ForwardAskUser L369-377 相同模式）：
```scala
case AgentCommand.ForwardPermission(deferred, permJson, sourceAgent, sourceSession) =>
  parentRef match
    case Some(grandParent) =>
      // 自己仍是子 agent：向上一级中继，不在本级截留
      (grandParent ! AgentCommand.ForwardPermission(deferred, permJson, sourceAgent, sourceSession)) *>
        IO.pure(idle(agentDef, resources, depth, parentRef, state))
    case None =>
      // root：渲染卡片 + 保存 deferred（现有逻辑，含 D4 冲突处理改进见修复 4）
      if state.pendingPermission.isDefined then
        deferred.complete(false).void *> IO.pure(idle(...))
      else
        val modifiedJson = permJson.deepMerge(Json.obj(
          "sessionId" -> state.sessionId.asJson,
          "sourceAgent" -> sourceAgent.asJson,
          "sourceSession" -> sourceSession.asJson))
        state.wsSend(modifiedJson).handleErrorWith(_ => IO.unit) *>
          IO.pure(idle(..., state.withPendingPermission(Some(deferred))))
```
（processing 版同理，返回 `processing(...)`。）

**效果**：`Frontend → Manager → Nebula(root)` 一路中继，deferred 最终由 root 持有；root 在 `rootAgents` 中，`PermissionAnswered` 可靠路由（配合修复 3）。

### 修复 2（致命）：pendingPermission 与 turn 生命周期解耦

**文件**：`src/main/scala/nebflow/agent/AgentActor.scala`
**函数**：`finishTurnCont`（L1704-1705）、`ToolsComplete` case（L847-859）

`finishTurnCont`：
```scala
// 原：val updatedState = state.copy(execution = ExecutionContext.idle(newMessages, state.execution.turnIdx))
val keptInteraction = state.execution.interaction.filter(_.pendingPermission.isDefined)
val updatedState = state.copy(
  execution = ExecutionContext.idle(newMessages, state.execution.turnIdx)
    .copy(interaction = keptInteraction)
)
```
`ToolsComplete`（L847-859）：`interaction = None` 改为保留 pendingPermission：
```scala
interaction = state.execution.interaction.filter(_.pendingPermission.isDefined),
```
**效果**：父 agent 在等待子 agent 权限期间 turn 结束，deferred 仍被持有，用户批准后 `PermissionAnswered` 能 complete 它。

### 修复 3（致命）：routeToAgent 识别 TeamSessionRegistry 中的 team agent

**文件**：`src/main/scala/nebflow/gateway/WebSocketRoutes.scala`
**函数**：`routeToAgent`（L211-224）

在查 `subAgentRegistry` 之后、`ensureRootAgent` 之前增加：
```scala
case None =>
  TeamSessionRegistry.getRunningActor(sessionId).flatMap {
    case Some(teamRef) => f(teamRef).handleErrorWith(...)
    case None => ensureRootAgent(sessionId).flatMap(f).handleErrorWith(...)
  }
```
**效果**：PermissionAnswered / UserAnswered 直达 Mail 激活的 team agent，不再创建 ghost agent。

### 修复 4（高）：ForwardPermission 冲突排队而非静默拒绝

**文件**：`src/main/scala/nebflow/agent/AgentActor.scala`
**函数**：idle/processing 的 ForwardPermission handler（与修复 1 同位置）

将 `deferred.complete(false)` 改为：当 pendingPermission 被占用时，把请求加入 `pendingPermissions: List[ForwardPermission]`（建议在 `InteractionState` 增加字段，`protocol.scala` L484-488），当前请求完成后逐个处理。最小版本：至少发一条明确消息回子 agent（"父 agent 正处理另一个权限请求"），避免子 agent 静默拿到 false 并重试。

### 修复 5（中）：MailTool.activateAgent 不再硬编码 safetyMode

**文件**：`src/main/scala/nebflow/core/tools/MailTool.scala`
**函数**：`activateAgent`（L414-474）

L464 `safetyMode = "confirm-edits"` 改为从 session meta 读取：
```scala
safetyMode = sessionOpt.flatMap(_.safetyMode).getOrElse("confirm-edits")
```
（`sessionOpt` 即 L421 `resources.sessionStore.getSessionMeta(sessionId)` 的结果。）

---

## 5. 验收标准

### 冒烟测试（硬性条件）
1. **真实启动**：`sbt run` 启动 Nebflow 服务（含 Gateway :8080），`curl /api/health` 返回 200。
2. **挂载团队**：通过 Load tool 挂载 nebflow-project，Nebula 窗口确认 `treeBranchMounted`。
3. **端到端权限链路**：Nebula 用 Mail 给 Frontend 下发"编辑 `test-fixtures/edit-me.md`"任务 → Frontend 执行 Edit → **Nebula 窗口出现权限卡片（sourceAgent=Frontend 标签正确）** → 点击允许 → 断言 `test-fixtures/edit-me.md` 内容已修改、Frontend 返回任务完成。

### 逐条验收
| # | 验收项 | 验证方式 |
|---|--------|----------|
| A1 | team 成员 Edit 权限卡片出现在 root 窗口，来源标签正确 | 冒烟测试 3；检查卡片 `sourceAgent`/`sourceSession` 字段 |
| A2 | 允许后工具真正执行，子 agent 继续 | 冒烟测试 3；断言文件已修改 + Frontend agentDone |
| A3 | 拒绝后工具不执行，子 agent 收到 "Permission denied by user" | 冒烟测试 3 中改点拒绝；断言文件未修改 + Frontend 输出含 denied |
| A4 | **无 ghost agent**：批准后 `rootAgents` 大小不增长、无新 `agent-<sid>` 日志 | 检查日志 spawn 记录 |
| A5 | **无超时拒绝**：允许后子 agent 在 5 分钟内完成（不触发 permissionExpired） | 日志断言无 "Permission timed out" |
| A6 | 并发：两个 team 成员同时请求权限 → 两张卡片依次出现，或第二个排队，无静默拒 | 日志断言无 `deferred.complete(false)` 静默路径 |
| A7 | team 会话 safetyMode=auto-edits 时，Mail 激活成员 Edit 不再请求确认 | 设置 auto-edits → 发任务 → 断言无 askPermission 事件 |
| A8 | Delegate 子 agent 权限链路不回归（原正常路径） | 回归测试：Delegate 子 agent Edit → 卡片 → 批准 → 执行 |
| A9 | AskUser 工具链路不回归 | team 成员 AskUserQuestion → root 窗口渲染 → 回答回传 |
| A10 | 编译 + 现有测试通过 | `sbt compile` + `sbt test`（或项目约定命令） |

### 验收条件自查
- [x] 有冒烟测试（真实启动 + curl + 端到端 Edit 权限链路）
- [x] 冒烟测试使用真实组件（真实 agent 系统、真实 WS、真实文件系统）
- [x] 每条可自动化验证（日志断言 / curl / 文件断言）
- [x] 每条二值判断
- [x] 覆盖启动流程（sbt run 完整启动）
- [x] 覆盖回归场景（Delegate / AskUser 不回归）

---

## 6. 附：证据文件索引

| 位置 | 内容 |
|------|------|
| `AgentCore.scala` L542-618 | `askUserPermission`：ForwardPermission 发送条件（L583-587）、5min 超时（L27, L590-613） |
| `AgentCore.scala` L569-575 | permJson 构造：sessionId=子 agent 自己的 sessionId |
| `AgentActor.scala` L353-367 | idle ForwardPermission handler：无中继、覆盖 sessionId、冲突即拒 |
| `AgentActor.scala` L1206-1220 | processing ForwardPermission handler：同上 |
| `AgentActor.scala` L369-377 / L1223-1229 | ForwardAskUser handler：**有中继的正确参照** |
| `AgentActor.scala` L346-351 / L1186-1199 | PermissionAnswered：`state.pendingPermission` 找 deferred |
| `AgentActor.scala` L847-859 | ToolsComplete：`interaction = None` 清空 pendingPermission |
| `AgentActor.scala` L1704-1705 | finishTurnCont：`ExecutionContext.idle` 清空 interaction |
| `WebSocketRoutes.scala` L211-224 | routeToAgent：只查 subAgentRegistry + rootAgents，不知 TeamSessionRegistry |
| `WebSocketRoutes.scala` L89-168 | ensureRootAgent：depth=0, parentRef=None, wsSend=broadcast |
| `WebSocketRoutes.scala` L690-694 | WS permissionAnswer → routeToAgent(sessionId) |
| `MailTool.scala` L414-474 | activateAgent：depth=1, parentRef=ctx.agentActorRef, 硬编码 confirm-edits, 只注册 TeamSessionRegistry |
| `DelegateTool.scala` L38-60, L308-331 | Delegate 子 agent：routeWsSend 注入父 sessionId + subAgentRegistry 注册（修复参照） |
| `SharedResources.scala` L58 | subAgentRegistry 定义 |
| `permissions.scala` L61-78 | ToolReversibility：confirm-edits 下 Edit/Write 需确认 |
| `chat.js` L1401-1452 | 前端卡片：targetSid=permSessionId；允许/拒绝发 permissionAnswer |
| `main.js` L893-896 | 非活跃 session 的 askPermission 仅持久化不渲染（卡片不可见） |
| `main.js` L866-879 | bypassSessions 自动批准逻辑 |
