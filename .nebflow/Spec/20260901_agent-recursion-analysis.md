# 后台 agent「递归 / 11 个 / 无法结束」根因分析

> 日期：2026-09-01　取证人：Explorer（单次调用）　状态：只读分析，未改动任何代码/会话
> 现象：用户报告后台 agent 一直递归、同时 11 个、无法结束（09:44）。后端 AgentControl list 仅 6 会话。

## 结论（可直接转述给用户）

**这不是引擎级递归死循环，而是一个真实的「反馈循环」异常**：试点执行者 qa-backend 自己创建了项目（ProjectCreate），导致节点结果被投回给它自己——每个节点完成就把它唤醒一次，它又建下一个节点，形成自维持循环（12 分钟建了 12 个节点，逐次递增 cancel-test-N，仍在继续）。前端显示的「11 个」不是 11 个并发 agent，而是 11 个已完成节点的**幽灵行**（前端计数只增不减的显示缺陷），同一时刻真实运行只有 1 个节点 + 1 个驱动器。

**根因一句话**：`ProjectCreate` 挂载项目时把 `rootSessionId` 设成了**调用者自己的会话**（qa-backend），而节点的 `out="Nebula"` 投递目标正是这个 rootSessionId——于是「Nebula」变成了 qa-backend 自己，节点完成消息全部注入给它，把它变成了自己节点的消费者 + 生产者。

## 现象解释

### 1. 为什么前端显示 11 个而 AgentControl 只有 6 个 —— 幽灵行累计

- 前端「后台子智能体」面板计数 = `Object.keys(sessionBgAgents[sid]).length`（`web/js/main.js` updateBgAgentIndicator），行在 `agentStart` 时新增、在 `agentDone` 时 2 秒后删除。
- 节点会话（`node-*`）由 NodeEngine 直接 spawn，`parentRef=None`（「节点无 Mail 身份」设计）→ AgentActor 里 `isSubagent = parentRef.isDefined = false`（`AgentActor.scala:2767`）→ 完成时发射的事件是**会话级 `type:"done"`**（`protocol.scala:581`），**不是**子代理级 `type:"agentDone"`。
- 前端只在 `agentDone` 时删行 → 节点行的清理逻辑**永远不触发** → 每个 spawn 的节点 = 一行永久残留的「Processing」幽灵行。
- 09:33:49–09:44 共 spawn 11 个节点（lit-survey-1 + cancel-test ~ cancel-test-10），面板恰好显示 11。**11 = 幽灵行累计数，非并发数**。节点实际是串行跑的（前一个完成才 spawn 下一个），同一时刻真实活动：1 节点 + qa-backend + Manager + 若干 Idle 成员。

### 2. 执行链为什么「递归」——rootSessionId 接线错误导致的反馈循环

证据链（`~/.nebflow/logs/nebflow.log`）：

```
09:32:33  Manager Mail(→qa-backend) [PARALLEL]          ← 委托阶段1试点
09:32:57  qa-backend Tool ProjectCreate(phd-notebook)   ← 挂载，rootSessionId=qa-backend自身会话
09:33:49  node lit-survey-1 spawn → completed
09:34:17  immediate-input-injected-at-tools-complete: [Node 'lit-survey-1' completed] → 注入 95eeea2c
09:34:26  node cancel-test spawn → completed → 再注入 95eeea2c
09:34:36  node cancel-test-2 spawn … （cancel-test-3 … cancel-test-11，逐次递增，仍在进行）
```

- 代码路径：`ProjectCreateTool.call` → `ProjectRuntimeRegistry.mount(..., ctx.sessionId)`（`NodeTools.scala:535`）→ `NodeEngine.rootSessionId = qa-backend 的会话 (95eeea2c)`。
- 节点完成：`NodeEngine.completeNode` → `deliverOut` → `out="Nebula"` → `deliverToNebula` 查 `agentRegistry.get(rootSessionId)` → **找到 qa-backend 自己** → `ImmediateInput("[Node 'xxx' completed]\n<result>")` 注入（日志 `immediate-input-injected-at-tools-complete` 目标 95eeea2c）。
- qa-backend 每被唤醒一次 → 按其试点任务（验证 NodeEdit/NodeCancel，节点命名 cancel-test-N）继续建下一个验证节点 → 完成 → 再注入 → **自维持循环**。
- 引擎自身无自动建节点路径：`NodeEngine.deliverOut` 只启动**已存在**的下游节点（barrier 归零），`startNode` 幂等（终态跳过）；无任何代码路径在节点完成后自动创建新节点。循环的「再触发」发生在 agent 层（qa-backend 收到完成消息后自主行动），非引擎层。

### 3. Manager 是否驱动循环 —— 否

Manager（73527280）当前 Idle：09:32:33 委托后即休眠（`note="turn NOT auto-resumed"`），循环期间无 Manager 活动。观察一致：**循环由 qa-backend 自驱**（重启后新增 Node 工具授权，自主执行试点时踩中接线错误）。

## 处置建议

### 立即动作（止血，按优先级）

1. **停止循环驱动器**：停止/冻结 qa-backend 会话（95eeea2c）——通过 AgentControl stop/restart，或让 Manager 对其发「立即停止建节点、结束试点验证」指令。这是唯一能停循环的动作（节点 cancel 不会阻止 qa-backend 再造新节点）。
2. **可选的补充**：NodeCancel 当前运行节点（node-eeaebc49 / cancel-test-11）——只停当前节点，不停循环，非必需。
3. **无需动 Manager**（非驱动方）；重启后先修好 rootSessionId 接线再重跑试点，否则同样问题会复现。

### 根因修复建议

1. **rootSessionId 接线（主修复）**：`ProjectCreateTool.call` 的 mount 应传调用者的**上链根会话**（AgentActor 已持有 rootSessionId 参数，thread 到 ToolContext 即可），而不是 `ctx.sessionId`。这样 `out="Nebula"` 投递到真正的顶层 Nebula 会话（qa-backend → Manager → Nebula 的链条），节点完成消息回到用户侧而非执行者侧，循环从设计上不可能成立。可选加固：ProjectCreate 仅允许根会话调用，或「Nebula」目标解析为常量指向顶层主会话，禁止落到中继 agent。
2. **前端幽灵行（计数修复）**：节点会话 `isSubagent=false` 导致完成事件是 `done` 而非 `agentDone`，前端删行逻辑永不触发。三选一：(a) NodeEngine 在 completeNode/failNode/cancelNode 时随 WS `nodeCompleted`/`nodeUpdated` 附带节点会话 id，前端据此删除对应幽灵行；(b) 前端对 `nodeCompleted`/`nodeUpdated` 终态清理 `sessionBgAgents` 中 `sessionId` 以 `node-` 前缀的行；(c) 节点 Done 事件显式按 `agentDone` 发射（给节点会话一个显式 isSubagent 标记，不再依赖 `parentRef.isDefined`）。
3. **试点纪律**：阶段 1 试点中 ProjectCreate 由顶层会话（Nebula）执行，或走 ProjectActor.TriggerDispatcher 并传入正确 rootSessionId；分发器/执行者不应成为项目的 rootSessionId 持有者。

## 涉及文件

- `src/main/scala/nebflow/core/tools/NodeTools.scala`（ProjectCreateTool.call → mount 传 ctx.sessionId，L535）
- `src/main/scala/nebflow/core/project/NodeEngine.scala`（deliverToNebula 按 rootSessionId 注入，L304-317）
- `src/main/scala/nebflow/core/project/ProjectActor.scala` / `ProjectRuntimeRegistry.mount`（rootSessionId 参数来源）
- `src/main/scala/nebflow/agent/AgentActor.scala:2767`（isSubagent = parentRef.isDefined）
- `src/main/scala/nebflow/agent/protocol.scala:581-584`（Done 事件按 isSubagent 区分 done/agentDone）
- `src/main/resources/web/js/main.js`（agentStart 增行 / agentDone 2s 删行）

## 证据索引

- 节点串行 spawn/complete 序列：`~/.nebflow/logs/nebflow.log` L7755-8474（lit-survey-1 → cancel-test → … → cancel-test-11）
- 完成消息注入 95eeea2c：L7833/7862/8007/8027/8046/8065/8083/8106/8254/8310/8449（`immediate-input-injected-at-tools-complete`）
- ProjectCreate 执行者：L7662 `qa-backend Tool ProjectCreate(phd-notebook) OK`
- Manager 委托：L7536 AUDIT（restart qa-backend，turn NOT auto-resumed）+ L7630-ish Mail(→qa-backend)
- 会话 95eeea2c = qa-backend：`router/2026-09-01_summary.jsonl`（agent=qa-backend，00:48 起持续存在）
- flow-map：`~/.nebflow/projects/phd-notebook/.nebflow/flow-map.json`（4 活动节点全 out="Nebula"）+ flow-map-archive.json（8 归档节点，共 12）
