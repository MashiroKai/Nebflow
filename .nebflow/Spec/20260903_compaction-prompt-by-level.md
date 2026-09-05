# 按层级定制的上下文压缩提示词——现状审计与设计规格书

> 阶段文档（2026-09-03，审计 + 设计，未改任何产品代码）。
> 依据：主仓源码逐行核对（CompactService / CompactionProfile / AgentCore / AgentActor / FullCompact / ProjectActor / NodeEngine）、20260831_project-node-architecture.md、CompactionProfileSpec 现有测试约定。

---

## 0. 结论摘要

1. **提示词分层机制已存在**（`CompactionProfile`：Root/Manager/Worker/Legacy，按 depth + isLead 分发），但**新架构 Project+Node 会话全部以 depth=1 spawn**，落进旧体系判据：dispatcher 会话拿到 **Worker** 提示词；Node 会话拿到 **Worker** 提示词（极端情况下节点 agent 名恰好是某旧 team 的 lead 时拿到 **Manager** 提示词）。
2. 需要的最小改动：`fromDepth`/`buildCompactReminder` 增加一个 `sessionId: Option[String]` 参数，用既有跨层契约前缀 `dispatcher-` / `node-` 在 depth 1 上优先分流出两个新 profile（Dispatcher / ProjectNode）。调用点只改一行。
3. 三版提示词草稿见 §2，均与现状同风格（英文指令 + `<summary>` 分段模板 + 复用共享前言/后记），可直接复制进 `CompactService.scala`。

---

## 1. 现状审计

### 1.1 压缩管线全貌（提示词在链路中的位置）

```
触发（3 处，全部汇入同一入口）
  ├─ 自动压缩  AgentCore.maybeAutoCompact (AgentCore.scala:168, mode="full")
  ├─ 响应后压缩 AgentActor.scala:4224 / 4277（mode 传入）
  └─ （emergencyCompact 是纯规则清理，不走 LLM 提示词，与本设计无关）
        ↓
startDirectCompaction (AgentCore.scala:270-347)
  ├─ depth==1 时查 isTeamLeadForCompaction (AgentCore.scala:245-255)
  ├─ profile = CompactionProfile.fromDepth(depth, isLead)   ← 分发判据
  ├─ hook   = PreCompactionHooks.forProfile(profile)        ← Root→NebulaMemoryHook，其余 NoOp
  ├─ input  = CompactUtils.prepareCompactionInput(...)      ← 先剔除超大 ToolResult（#38 Layer B）
  └─ reminder = CompactService.buildCompactReminder(depth, isLead)  ← 提示词构建（AgentCore.scala:301）
        ↓  reminder 作为最后一条 User 消息追加（AgentCore.scala:308-314）
压缩轮（tools disabled，文本响应流式到前端）
        ↓
handleCompactResponse (AgentActor.scala:4031-4055) → FullCompact.parseResponse
  ├─ 剥离 <analysis>，提取 <summary>
  ├─ 产物 = 单条 <context-compact mode="full" preservedRounds=0> User 消息，替换全部历史
  ├─ + ContinuationPrompt（防寒暄/复述）+ ReadTracker 文件恢复段
  └─ 空响应/解析失败 → Left → handleCompactFailure 重试（熔断上限 circuitBreakerMax=3）
        ↓
AgentCommand.CompactionComplete (AgentActor.scala:2096-2215)
  └─ HistoryArchiver.archiveCompaction 落盘 before/after 报告（mode + agentName）→ 压缩后摘要可事后审计
```

压缩期注入屏蔽（F0-F3 / CompactionQueueStore / 窗口期队列扣留 + 完成后统一 drain）与本设计正交，全部不动。

### 1.2 提示词本体（`src/main/scala/nebflow/core/compact/CompactService.scala`）

| 构件 | 位置 | 内容 |
|---|---|---|
| `buildCompactReminder(depth, isLead)` | :36-42 | profile → 提示词映射的唯一入口 |
| `CompactPreamble`（共享前言） | :49-66 | 英文。停当前任务 / 工具禁用 / 必须非空文本（空=失败重试）/ `<analysis>`+`<summary>` 双块结构 / **LANGUAGE RULE（摘要语言跟随用户消息语言）** |
| `CompactEpilogue`（共享后记） | :69-91 | 英文。`<files>` 段（≤5 文件）/ 路径反引号+行号 / 保留决策与权衡 / 用户指令逐字引用 / 图片附件一行格式 `[图片: path \| 描述]` |
| `RootCompactReminder` | :97-131 | 9 段通用编码助手模板：Primary Request / Key Technical Concepts / **Files and Code Sections（含代码片段）** / Errors and Fixes / Problem Solving / All User Messages / Pending Tasks / Current Work / Optional Next Step |
| `ManagerCompactReminder` | :143-174 | "You are a FLOW MANAGER"，Mail 语义，7 段：Project Goal / Dispatched Tasks and Results / Pending Dispatches / Files Modified by Agents / Key Decisions / Current Work / Next Step。2026-08-31 注释已声明单级压缩、无记忆兜底 |
| `WorkerCompactReminder` | :186-214 | "You are a FLOW WORKER"，5 段：Current Task / Work Done So Far / Key Findings / Pending Steps / Result to Report。同款无记忆兜底声明 |

**所有提示词全部为英文**（摘要输出语言由 LANGUAGE RULE 保证跟随用户语言）。

### 1.3 Profile 分发与调用点可得的层级信息

`CompactionProfile.scala:35-38`：

```scala
def fromDepth(depth: Int, isLead: Boolean = false): CompactionProfile = depth match
  case 0 => Root
  case 1 => if isLead then Manager else Worker
  case _ => Worker
```

调用点 `startDirectCompaction` 上**已经拿得到**的信息（无需任何新概念）：

| 字段 | 值（新架构三层） | 说明 |
|---|---|---|
| `depth: Int` | Nebula=0，dispatcher=1，Node=1 | 区分不了 dispatcher 与 Node |
| `state.sessionId` | `dispatcher-<uuid8>` / `node-<uuid>` / root 常规 id | **前缀是既有跨层契约**（`ProjectActor.DispatcherSessionPrefix` :151、`NodeEngine.SessionPrefix` :563；`AgentControlTool.isProjectFlowSession` :103-105 与前端 `utils.isBgAgentId` 均按此识别） |
| `agentDef.name` | `"project-dispatcher"` / 节点所选全局 agent 名 | 备选判据（`DispatcherAgentName` 常量 :146） |
| `state.sessionName` | `dispatcher/<project>` / 节点名 | 展示用，判据不如前缀稳 |
| `state.isFlowNode` | 两者均 true | 区分不了（旧 flow 节点 dag-* 也是 true） |
| `isLead`（depth 1 专查） | `TeamSessionRegistry.isManager(sid)` 或 `EntityLoader.listTeams().exists(_.lead == agentDef.name)` | **team 时代判据**，对新架构会话语义错位 |

### 1.4 核心缺口：新架构三层在现状下的实际路由

- **Nebula（depth 0）** → Root ✓ 正常。
- **dispatcher**（ProjectActor.spawnDispatcher :398-421：depth=1、isFlowNode=true、sessionId=`dispatcher-*`）：`isTeamLeadForCompaction` → registry 无此会话 → nameIsLead 查 `"project-dispatcher"` 不是任何 team 的 lead → false → **拿到 Worker 提示词**。而 dispatcher 需要的是「本次触发任务 + 已建拓扑 + 收尾事项」，与 Worker 的「单任务执行」模板不匹配。
- **Node**（NodeEngine :263-284：depth=1、isFlowNode=true、sessionId=`node-*`）：
  - 常规情况 → Worker 提示词（模板接近需求但缺「验证结果 / commit / 失败教训」等要素，见 §2.3）；
  - **错位风险**：nameIsLead 按全局 agent 名匹配——若某节点运行名为 `Manager` 的 agent（现存多个 team 的 lead 恰好都叫 Manager），`isLead=true` → **拿到 Manager 提示词**，一个执行节点被要求写「协调状态摘要」。
- 结论：**分发判据必须升级为「depth + sessionId 前缀」**，且前缀判断优先于 isLead。

### 1.5 其他审计发现

1. **`<files>` 段无消费者**：`CompactEpilogue` 要求模型输出 `<files>` 列表（:71-77），但 `FullCompact.parseResponse` 只提取 `<summary>`（:89-92），文件恢复段实际只消费 ReadTracker 路径（`buildFileRestoreSection(recentReadPaths, Set.empty, …)` :55，`preservedFilePaths` 恒为空集）——模型输出的 `<files>` 被静默丢弃。属现状缺陷，与本设计正交（建议后续二选一：解析 `<files>` 并入 preservedFilePaths，或从后记中删除该要求；本批不改）。
2. `isTeamLeadForCompaction` 的 nameIsLead fallback（AgentCore.scala:248）是旧体系兜底，新前缀判据落地后对其无影响（前缀分支先行短路）。
3. 现有三版提示词的 2026-08-31 单级压缩注释（「save turn 已删、摘要是唯一恢复载体」）语义正确，新两版必须继承同一声明。
4. 压缩轮复用 agent 自身 system prompt（CompactService 头注释 :10-22），因此角色声明句（"You are NEBULA…"）与注入的系统人设一致，不冲突。
5. 现有测试 `CompactionProfileSpec` 用「提示词包含 marker 字符串」断言路由（如 `contains("FLOW WORKER")`），新测试沿用此约定即可，全部可纯单测覆盖。

---

## 2. 分层设计——三版提示词草稿

**语言说明**：现状压缩提示词全英文（§1.2），按约束「以现状为准」，草稿用英文、可直接落码；摘要输出语言由共享前言 LANGUAGE RULE 保证跟随用户语言（中文会话产出中文摘要），无需在正文重复。三版均复用 `CompactPreamble` + `CompactEpilogue`，下面只写各自的角色段 + `<summary>` 模板。

### 2.0 设计原则（作者需求 → 模板结构映射）

| 层级 | 必保 | 必丢 |
|---|---|---|
| Nebula | 全局任务台账（每条线状态+归属）、在飞派发、事实与裁定（逐字）、待办门槛 | 已完成子任务的过程细节、长工具输出、已被结论吸收的探索过程 |
| dispatcher | 本次触发任务、本会话拓扑改动、未完成缺口 | 全图复制（Flow Map 是权威，磁盘不灭）、节点结果正文 |
| Node | 任务目标（逐字、不变形）、已完成证据（路径/commit/验证）、剩余步骤、阻塞与教训 | 无教训的死胡同探索、冗长工具输出 |

### 2.1 Nebula 版（替换 `RootCompactReminder` 的 summary 模板部分）

> 定位：全局编排者的**状态台账**，不是编码日志。durable 用户事实已由 NebulaMemoryHook 在压缩前另路提取进 User.md，提示词明示不重复。

```scala
/**
 * Nebula (Root) — the long-lived global orchestrator. Focus: a GLOBAL STATUS
 * LEDGER — every project/task line, in-flight dispatches, verbatim facts and
 * rulings. Process detail of finished sub-tasks is discarded aggressively.
 * 2026-09-03 (per-level compaction): replaces the generic coding-assistant
 * template; durable user facts are extracted separately by NebulaMemoryHook
 * before compaction, so the summary must NOT duplicate them.
 */
private val NebulaCompactReminder = CompactPreamble +
  """You are NEBULA — the global orchestrator. Your session is long-lived and
    |spans every project, team and task line. After compaction you resume
    |steering ALL of them from this summary alone (your durable user facts are
    |extracted separately into memory before compaction — do not duplicate
    |them here).
    |
    |Your summary must read like a GLOBAL STATUS LEDGER, not a coding log.
    |Discard aggressively: completed sub-task process details, long tool
    |outputs, and exploratory back-and-forth whose conclusion is already
    |captured below. Keep the conclusion, drop the journey.
    |
    |<summary>
    |1. Global Mission Board:
    |   For EACH active project/task line (one bullet per line):
    |   - [Project/team name]: [status: in-flight / awaiting-review / done / failed]
    |     → [what was last dispatched, to whom, and what is expected back]
    |
    |2. In-Flight Dispatches (waiting on results):
    |   - [Team/project/agent] ← [what was asked] → [expected deliverable;
    |     any threshold/retry/escalation rule attached to it]
    |
    |3. Facts, Decisions and Rulings (preserve VERBATIM):
    |   - [User decisions, acceptance verdicts, corrections — quote exactly]
    |
    |4. Pending / Blocked Items and their Gates:
    |   - [Item]: [what gates it — e.g. waiting for X before dispatching Y;
    |     retry policy; when to escalate to the user]
    |
    |5. Completed This Session (one line each, no process detail):
    |   - [Task line]: [final outcome + key artifact path if any]
    |
    |6. User Preferences Stated This Session:
    |   - [Working-style instructions, language, tool preferences]
    |
    |7. Current Work:
    |   [What you were doing immediately before this summary request.]
    |
    |8. Next Step:
    |   [The single immediate orchestration action. Include direct quotes from
    |   the most recent user instruction if relevant.]
    |</summary>
    |""".stripMargin + CompactEpilogue
```

### 2.2 Dispatcher 版（新增 `DispatcherCompactReminder`）

> 定位：单次会话分发器。**Flow Map（磁盘 flow-map.json）是权威状态**，摘要不替代它，只承载「本次触发做了什么改动 + 还差什么收尾」。分发器会话是单例多注入的（2026-09-02 裁定：重入注入同一会话），所以触发任务按轮列出。

```scala
/**
 * Project Dispatcher — single-session task dispatcher for one project.
 * 2026-09-03 (per-level compaction): the Flow Map on disk (flow-map.json:
 * nodes, wiring, statuses, results) is the AUTHORITATIVE state and survives
 * this session. The summary does NOT replace the Flow Map — it only carries
 * what THIS trigger changed and what remains to wrap up.
 */
private val DispatcherCompactReminder = CompactPreamble +
  """You are a PROJECT DISPATCHER — a single-session task dispatcher for one
    |project. The Flow Map on disk (flow-map.json: nodes, wiring, statuses,
    |results) is the AUTHORITATIVE state of this project — it survives after
    |this session dies. This summary does NOT replace the Flow Map; it only
    |needs to carry enough for you to finish the CURRENT trigger cleanly
    |(finalize wiring, answer a re-entry injection, wrap up). Never copy the
    |whole Flow Map into this summary — call NodeList fresh if you need it.
    |
    |<summary>
    |1. Trigger Task(s) (this session, one bullet per injection round):
    |   - [The task text injected by Nebula/ProjectActor — quote verbatim;
    |     for re-entry rounds, state which node triggered it and why]
    |
    |2. Topology Changes Made This Session (per NodeEdit executed):
    |   - [nodeId/name]: agent=[agent], task=[yes/no], in=[upstream ids],
    |     out=[downstream id or "Nebula"] → [created / rewired / cancelled]
    |
    |3. Nodes Started or Affected This Session:
    |   - [nodeId/name]: [status at last NodeList — running/pending/blocked/cancelled]
    |
    |4. Unfinished Work (gaps the next trigger resumes from):
    |   - [Node/edge planned but NOT created or wired, and why — name the exact gap]
    |
    |5. Worktree / Git Actions:
    |   - [worktrees created or merged this session; git commands with outcomes]
    |
    |6. Wrap-up State:
    |   [What remains before this trigger is fully settled — final NodeList
    |   self-check done? any node still expected to start?]
    |</summary>
    |""".stripMargin + CompactEpilogue
```

### 2.3 Node 版（新增 `NodeCompactReminder`）

> 定位：一次性任务节点。作者四要素：任务目标（不变形）→ 已完成（路径/commit/验证）→ 下一步 → 阻塞与失败教训。附加 Node 特有事实：**最终输出文本 = 节点结果**，由 NodeEngine 捕获写入 result 字段并沿 out 边投递——摘要必须保住「下游拿到的东西长什么样」。

```scala
/**
 * Node Worker — a one-shot task node inside a project Flow Map.
 * 2026-09-03 (per-level compaction): the node's FINAL OUTPUT TEXT becomes the
 * node result (captured by NodeEngine, delivered downstream), so whatever
 * downstream needs must survive this summary. No memory, no fallback — this
 * summary is the ONLY record of the in-progress task.
 */
private val NodeCompactReminder = CompactPreamble +
  """You are a NODE WORKER — a one-shot task node inside a project Flow Map.
    |Your final output text becomes the node result and is delivered to the
    |downstream node, so anything downstream needs from your work must survive
    |this summary. You have NO persistent memory and NO memory fallback after
    |compaction — this summary is the ONLY record of your in-progress task.
    |Never write "see memory" or assume anything survives outside it.
    |
    |Your ONE job after compaction: resume exactly the task below and finish it.
    |
    |<summary>
    |1. Task Goal (IMMUTABLE — do not reinterpret, narrow, or expand it):
    |   [The task text this node was started with — quote verbatim, including
    |   acceptance criteria and constraints]
    |
    |2. Completed So Far (evidence-based):
    |   - `path/to/file` (line X-Y): [what was done]
    |   - Commits: [hash + one-line message; branch/worktree if any]
    |   - Verification: [commands run + actual results — tests passed/failed,
    |     build ok, screenshots taken]
    |
    |3. Blockers and Lessons:
    |   - [What blocked you and how you worked around it; failed approaches
    |     with WHY they failed — they must not be retried blindly]
    |
    |4. Remaining Steps (ordered):
    |   1. [next concrete action]
    |   2. [...]
    |
    |5. Result Statement So Far:
    |   [If you had to report now: the one-paragraph result downstream would
    |   receive, plus what is still missing from it]
    |</summary>
    |
    |Discard freely: dead-end exploration without a lesson, verbose tool
    |outputs, and completed-and-verified details beyond the evidence lines
    |above.
    |""".stripMargin + CompactEpilogue
```

### 2.4 旧体系角色映射（一句话建议）

旧 team Manager/Worker（含旧 flow dag-* 节点、Delegate/SubTask）在过渡期**保持现有 Manager/Worker 提示词与路由完全不动**；待旧体系退役（架构方案阶段 3）时连同 `ManagerCompactReminder`/`WorkerCompactReminder` 一并删除。

---

## 3. 分发机制（最小改动）

**判据 = depth + sessionId 前缀，前缀优先于 isLead**。前缀复用既有跨层契约常量（`AgentControlTool.isProjectFlowSession` 已确立此先例，前端 `utils.isBgAgentId` 同值硬编码），不发明新概念、不加 spawn 参数。

### 3.1 `CompactionProfile.scala` 改动

```scala
enum CompactionProfile:
  case Root, Manager, Worker, Dispatcher, ProjectNode, Legacy

object CompactionProfile:

  def fromDepth(depth: Int, isLead: Boolean = false, sessionId: Option[String] = None): CompactionProfile =
    if depth == 0 then Root
    else if depth == 1 then
      // New-architecture sessions first: prefix contract from
      // ProjectActor.DispatcherSessionPrefix / NodeEngine.SessionPrefix.
      // MUST take precedence over isLead — a node running an agent whose name
      // happens to be a legacy team lead would otherwise get Manager.
      if sessionId.exists(_.startsWith(nebflow.core.project.ProjectActor.DispatcherSessionPrefix)) then Dispatcher
      else if sessionId.exists(_.startsWith(nebflow.core.project.NodeEngine.SessionPrefix)) then ProjectNode
      else if isLead then Manager
      else Worker
    else Worker
```

说明：
- 引用常量而非裸字符串（单一事实源，`core.compact → core.project` 的依赖与 `core.tools → core.project`（AgentControlTool）同级，无初始化环）。
- 两个新参数都有默认值 → `CompactionProfileSpec` 既有调用、其他调用点零改动兼容。
- `PreCompactionHooks.forProfile` 的 `case _ => NoOpHook` 自动覆盖新 profile（dispatcher/Node 无记忆，符合 2026-08-31 裁定），**无需改动**。

### 3.2 `CompactService.scala` 改动

```scala
def buildCompactReminder(
  depth: Int = 0,
  isLead: Boolean = false,
  sessionId: Option[String] = None
): Message =
  val profile = CompactionProfile.fromDepth(depth, isLead, sessionId)
  val prompt = profile match
    case CompactionProfile.ProjectNode => NodeCompactReminder
    case CompactionProfile.Dispatcher  => DispatcherCompactReminder
    case CompactionProfile.Worker      => WorkerCompactReminder
    case CompactionProfile.Manager     => ManagerCompactReminder
    case _                             => NebulaCompactReminder   // Root
  Message(MessageRole.User, Left(prompt))
```

（枚举名 `ProjectNode` 避免与 `nebflow.core.node` / NodeDef 语义混淆；`Root` 分支同时兜住 Legacy。）

### 3.3 调用点改动（`AgentCore.scala:301`，一行）

```scala
reminder = CompactService.buildCompactReminder(depth, isLead, state.sessionId)
```

### 3.4 路由结果对照表（改动后）

| 会话 | depth | sessionId | 路由 | 提示词 |
|---|---|---|---|---|
| Nebula 根会话 | 0 | 常规 id | Root | Nebula 版 |
| project-dispatcher | 1 | `dispatcher-*` | Dispatcher | Dispatcher 版 |
| Project Node | 1 | `node-*` | ProjectNode | Node 版 |
| 旧 team lead | 1 | 常规 id | Manager | Manager 版（不动） |
| 旧 team 成员 / 旧 flow 节点 `dag-*` / delegate- / subtask- | 1 | 各自前缀 | Worker | Worker 版（不动） |
| 任何 depth≥2 | ≥2 | 任意 | Worker | Worker 版（不动） |

---

## 4. 验收标准

### 4.1 单测（扩展 `CompactionProfileSpec`，全部纯函数可断言）

```scala
// —— 路由表断言（fromDepth）——
test("depth 0 is Root regardless of sessionId/isLead")
test("depth 1 + dispatcher- prefix → Dispatcher (even if name would be a legacy lead)"):
  assertEquals(CompactionProfile.fromDepth(1, isLead = true,  Some("dispatcher-ab12cd34")), CompactionProfile.Dispatcher)
test("depth 1 + node- prefix → ProjectNode"):
  assertEquals(CompactionProfile.fromDepth(1, isLead = false, Some("node-ab12cd34")), CompactionProfile.ProjectNode)
test("legacy prefixes keep old routing"):
  assertEquals(CompactionProfile.fromDepth(1, isLead = false, Some("dag-flow1-n2-123456")), CompactionProfile.Worker)
  assertEquals(CompactionProfile.fromDepth(1, isLead = false, Some("delegate-x")), CompactionProfile.Worker)
  assertEquals(CompactionProfile.fromDepth(1, isLead = false, Some("subtask-y")), CompactionProfile.Worker)
  assertEquals(CompactionProfile.fromDepth(1, isLead = true,  None), CompactionProfile.Manager)
test("depth 2+ is always Worker")

// —— 提示词路由断言（marker 字符串，沿用现有约定）——
test("buildCompactReminder routes by sessionId"):
  assert(text(buildCompactReminder(0, sessionId = Some("s1"))).contains("NEBULA"))
  assert(text(buildCompactReminder(1, sessionId = Some("dispatcher-x"))).contains("PROJECT DISPATCHER"))
  assert(text(buildCompactReminder(1, sessionId = Some("node-x"))).contains("NODE WORKER"))
  assert(text(buildCompactReminder(1, isLead = true)).contains("FLOW MANAGER"))      // 回归：旧 marker 保留
  assert(text(buildCompactReminder(1)).contains("FLOW WORKER"))

// —— 三版要素清单断言（每版必含段落标题）——
test("Nebula prompt sections"):     // Global Mission Board / In-Flight Dispatches /
                                    // Facts, Decisions and Rulings / Pending / Blocked Items and their Gates
test("Dispatcher prompt sections"): // Trigger Task(s) / Topology Changes Made This Session /
                                    // Unfinished Work / AUTHORITATIVE（Flow Map 权威声明）
test("Node prompt sections"):       // Task Goal (IMMUTABLE / Commits: / Verification: /
                                    // Blockers and Lessons / Remaining Steps / Result Statement So Far
test("all prompts keep no-memory-fallback declaration"):  // Dispatcher/Node 版 contains
                                    // "NO memory fallback" 或 "NO persistent memory"
```

### 4.2 编译与回归

- `sbt compile` 通过；`sbt "testOnly nebflow.core.compact.* nebflow.agent.AgentActorCompactionSpec"` 全绿（既有 CompactionProfileSpec 断言不改动即通过——新参数带默认值）。

### 4.3 运行时验收（三层各自压缩后摘要必含要素，以 HistoryArchiver 报告为断言载体）

- **Nebula 压缩一次**：报告 after 消息含每个活动 project/task 线一条状态 bullet；在飞派发含「谁 + 等什么」；用户裁定至少一条逐字引用；不含已完成子任务的过程性长文。
- **dispatcher 压缩一次**：摘要含本次触发任务原文；含本会话 NodeEdit 清单（node id + in/out）；含未完成缺口条目；**flow-map.json 与压缩前逐字节一致**（压缩不触碰磁盘状态）；摘要不复制全图（长度明显小于 NodeList 输出）。
- **Node 压缩一次**：摘要含任务目标原文（与节点 task 逐字一致）；已完成项含至少一条 `路径(行号)` / commit hash / 验证命令结果；含有序剩余步骤。
- **语言**：中文会话压缩后摘要为中文（LANGUAGE RULE 现状行为不回退）。
- **无回归**：旧 team 会话（depth 1 常规 id）压缩行为与改前一致（提示词 marker 不变）。

### 4.4 验收自查

- [x] 每条可自动化（单测 marker/section 断言、sbt 命令、报告 grep）
- [x] 二值判断（包含/不包含、一致/不一致）
- [x] 涉服务行为但压缩管线无启动流程改动 → 冒烟由既有 Nebflow 启动回归覆盖，无新增启动路径

---

## 5. 实施建议

| 文件 | 改动 | 规模 |
|---|---|---|
| `src/main/scala/nebflow/core/compact/CompactionProfile.scala` | enum +2 case；`fromDepth` +sessionId 参数与前缀分流 | ~15 行 |
| `src/main/scala/nebflow/core/compact/CompactService.scala` | +`NebulaCompactReminder`（替换 Root 模板主体）、+`DispatcherCompactReminder`、+`NodeCompactReminder`；`buildCompactReminder` +参数与分发 | ~130 行（三段草稿 ~110 行已给定） |
| `src/main/scala/nebflow/agent/AgentCore.scala` | :301 传 `state.sessionId` | 1 行 |
| `src/test/scala/nebflow/core/compact/CompactionProfileSpec.scala` | §4.1 断言 | ~60 行 |

- 总规模 **~200 行**，单 PR；无持久化/schema/前端/agent 定义改动，无迁移。
- **生效方式**：需重启 Nebflow（Scala 编译进主进程；重启后下一次压缩即用新提示词）。压缩期注入屏蔽、熔断、HistoryArchiver 全部不动。
- 遗留项（另开任务）：`<files>` 段无消费者（§1.5-1）——建议解析并入 `preservedFilePaths` 或删除该提示词要求。
