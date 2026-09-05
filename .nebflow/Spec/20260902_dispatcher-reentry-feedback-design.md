# Node → 分发器反馈路径与分发器重入协议（blocked 终态）设计

> 纯设计文档（作者 2026-09-02 21:29 立项）。不改任何产品代码。
> 代码锚点全部核对自主仓 main @ `c467592f`（barrier 投递根因修复后的现状）。
> 前置阅读：`20260831_project-node-architecture.md`（v5）、`20260901_project-node-contract.md`（阶段 0 契约）。

---

## 0. 背景缺口与实证

### 0.1 缺口

Node 被注入任务后若无法完成（任务定义问题 / 上游依赖冲突 / 缺少必要条件），当前唯一出路是把说明写进最终输出文本 → status=completed → 沿 out 投递（或悬空）→ 会话随即消失。而分发器是**单次会话**、早已结束、无记忆——没有任何机制响应这份说明，拓扑无人修正。现有兜底仅 `out="Nebula"` 时 Nebula 人工看到再重派：**机制化不足**。

### 0.2 实证案例（2026-09-02 灰色孤儿，已逐一核对）

主仓 `.nebflow/flow-map.json`（活动区）与 `.nebflow/flow-map-archive.json`（归档区）实证：

| 节点 | 状态 | 事实 |
|---|---|---|
| `n-95271231`（设计-阶段2架构演进方案总文档） | `wiring` 挂起 | `in=[n-8a481bd0, n-7b56096d]`，两上游早已 completed 并归档，但归档态 `out` 为字面串 `"null"`（陈旧副本回写覆盖）→ 投递悬空永不触发 |
| `n-c90d1140`（修复-dispatcher会话卡死与stuck管理） | `wiring` 挂起 | `in=[n-ab4a884f]` 同因（上游归档 `out` 陈旧覆盖）→ 悬空 |
| `n-8a481bd0` / `n-7b56096d`（归档区） | completed | `result` 全文完好保留，`out="null"`（字面串，即 FlowMapStore.loadInitial `normalizeOut` 注释所引实证） |

这类节点只能靠分发器重建/改接救活——而原分发器会话早已结束。**本次是人工充当了重入**；本设计把「重入」机制化。

### 0.3 现状代码事实（设计基线，均已在 c467592f 核对）

- **终态化三口**：`NodeEngine.completeNode`（NodeEngine.scala:262）/ `failNode`（:284）/ `cancelNode`（:307），全部事务内现读 fresh 节点写回；终态路由在 `spawnAndRun` 尾部（:243-249）：`Right(messages) → completeNode`；`Left(FailOutcome)` 含 "cancelled" → `cancelNode`，否则 `failNode`。
- **投递**：`deliverOut`（:330，completed 沿 out）/ `deliverFailed`（:357，failed 也结算 barrier 占位）/ `deliverToNebula`（:389，out="Nebula" → ImmediateInput source="node"）；悬空（out=None）结果保留，靠 NodeEdit D1 补投递（NodeTools.scala:406-414 create 路径、:539-555 edit 路径）。
- **D1 竞态修复已落地**：completeNode/failNode 现读 fresh；editNode in 追加补投递 + barrier 结算复查。
- **生命周期**：`NodeLifecycle`（ProjectTypes.scala:16-24）= wiring/pending/running/completed/failed/cancelled，`Terminal` 含后三者；`NodeDef`（:27-47）含 `retries/maxRetries` 字段。
- **重要现实核查**：`retries/maxRetries` 在项目 Node 引擎中**声明但未实现**——全 project/* 与 core/node/* 无任何 `retries` 递增或失败重跑循环（grep 核对；带 checkpoint 重试的 BackoffSupervisor/FlowDagExecutor 属旧 dag- flow 体系，`EntityTypes.scala:464` 的 `NodeResult` 同为旧 flow 执行器模型，项目 Node 不经过它）。**今天的 failed 是一次性终态。** 本设计按此现实划界（见 §5.1）。
- **分发器 spawn**：`ProjectActor.ProjectCommand.TriggerDispatcher`（ProjectActor.scala:133）→ `spawnDispatcher`（:185-263）：读 Flow Map 快照 → 注入 prompt（:196-203）→ `NodeRunner.spawnAgentActor`（单次会话 `dispatcher-<uuid8>`）→ 观察桥（:232-242，终态清 registry + 停 agent）+ `supervisorRef` 注册（:247-259，卡死处置通道）。触发入口：TaskTool.scala:78（Task 工具）与 MailTool.scala:195（Mail→project 路由）。
- **卡死处置（并行修复已落地）**：TaskStuckWatcher.scala:177-213 `kind=Flow + supervisorRef` 分支——分发器/节点会话 Processing 零活动 10min → 硬取消在飞 LLM → StopAttempts+2 轮仍卡 → 经桥 Cancelled 全链清理。
- **前端**：状态契约 `nodeData.js` NODE_STATUS/NODE_STATUS_CLS（:34-44）；Flow Map 渲染 `flowMapTab.js`（nodeHtml 状态图标 :126-128、summaryParts :208、visibleNodes :199、WS 增量 :825-856）；节点详情 `flowViewers.js openNodeResultViewer`（:79）；Nebula 注入气泡标签 `chat.js` NODE_STATUS_LABELS（:380-386，fallback `toUpperCase()`——"blocked" 无需改动即渲染为 BLOCKED）。

---

## 1. 结构化反馈语义：blocked 终态

### 1.1 语义定义与三态边界

| 终态 | 判定 | 可否重试 | 后续 |
|---|---|---|---|
| `completed` | agent turn 正常结束，产出可用 | — | 沿 out 投递（现状） |
| `failed` | **执行层错误**：turn 异常结束（AgentEvent.Failed）、agent 不存在、基础设施故障——机器可判定，重试同参数有意义 | 是（执行层重试） | 现状：failNode + deliverFailed（一次性终态；执行层重试见 §5.1） |
| `blocked`（新） | **turn 正常结束但节点声明无法继续**：继续执行无意义，需调整任务/拓扑/外部条件——语义判定，只有分发器能解 | **否**（重跑同样 blocked） | 停止传播 + 触发重入（§2） |

边界一句话：**failed = 试过了但失败（同样输入值得再试）；blocked = 试过了且判定再试无意义（必须改任务或改拓扑）**。cancelled 仍是人工/系统停止，无反馈语义，不入本协议。

### 1.2 声明机制：三种候选对比

| 方案 | 机制 | 评估 |
|---|---|---|
| ① 终态参数 | 节点 agent 无「终态 API」——turn 自然结束即终态（架构 v4 裁定：最终输出文本即结果） | **不存在挂载点**，排除 |
| ② 专门工具（如 NodeReport） | leaf 会话注入新工具上报 blocked | 违反架构 v4「去 FlowReport/上报工具」裁定（节点结果=最终输出）；工具不调用时仍需文本兜底 → 双路径复杂化；**排除** |
| ③ 结束文本结构化约定 | 最终输出以 `BLOCKED` 标记开头 + JSON 体，NodeEngine 终态化时解析 | 与「最终输出即结果」裁定同构；解析失败优雅降级为 completed（不劣于现状）；零工具面变更；**采纳** |

### 1.3 约定文法（③ 的精确设计）

节点最终输出文本（`completeNode` 收到的 `resultText`，即 `extractLastAssistantText` 取的最后一条 assistant 消息）满足：

- **锚定**：trim 后以 `BLOCKED` 开头（后跟 `:` 或换行/空白）。只检查最后一条消息的开头——正文中出现该词不误判。
- **JSON 体**（可选但强烈期望）：首个 `{` 到末个 `}` 之间尝试 circe 解析：

```json
{
  "category": "upstream-incomplete | task-underspecified | agent-mismatch | external-dependency | needs-split | other",
  "detail": "人类可读的具体说明（什么缺、为什么无法继续）",
  "suggestion": "对拓扑/任务的建议（如：需上游 X 先完成 / 任务应拆为 A+B / 换 agent / 需外部条件 Y）"
}
```

- **解析规则**：category 不在枚举 → 归 `other`；JSON 缺失/畸形 → `BlockedFeedback("other", 其余全文截断, "")`；非 BLOCKED 开头 → 走 completed 原路径（误报率≈0，漏报率=现状，无损降级）。
- **落库形态**：节点 `result` 写人类可读渲染串（`[blocked:<category>] <detail> — 建议: <suggestion>`），`blockedFeedback` 字段存结构化体（§1.4）——NodeList 摘要与前端详情窗两个消费方各取所需。

### 1.4 数据模型扩展（ProjectTypes.scala）

```scala
object NodeLifecycle:
  // …现有七态…
  val Blocked = "blocked"          // 新终态
  val Terminal: Set[String] = Set(Completed, Failed, Cancelled, Blocked)

case class BlockedFeedback(
  category: String,                // §1.3 枚举
  detail: String,
  suggestion: String
)

case class NodeDef(
  // …现有字段…
  blockCount: Int = 0,             // 该节点身份累计被 blocked 轮数（防循环计数，§3）
  blockedFeedback: Option[BlockedFeedback] = None  // 最近一次 blocked 的结构化反馈
)
```

**blocked 不设 ttlExpireAt**（=活动图常驻，直到分发器处置）：`sweepExpired`（FlowMapStore.scala:82-88）按 `ttlExpireAt.exists(_ <= now)` 过滤，None 天然豁免——后端零改动。前端 `visibleNodes`（flowMapTab.js:199-205）按 `Number.isFinite(ttlLeftSec)` 判断，None 同样天然常显。blocked 节点是待办事项，从图上消失 = 无人响应，违背本设计初衷。

### 1.5 声明约定的告知通道（关键细节，易漏）

节点 agent 是通用全局 agent（Coder/Explorer…），其 system prompt 不含 BLOCKED 约定——**约定必须随输入注入**。落点：`NodeEngine.buildInput`（NodeEngine.scala:116-126）在输入末尾追加固定协议脚注（单点注入，覆盖所有节点）：

```
── 节点协议 ──
若你判定任务无法完成（上游依赖未就绪/任务定义不完整/能力不匹配/缺外部条件），
不要硬造结果：把最终输出的第一行写为 BLOCKED，随后给出 JSON：
{"category":"…","detail":"…","suggestion":"…"}
category ∈ upstream-incomplete | task-underspecified | agent-mismatch |
external-dependency | needs-split | other。可完成时正常输出结果，勿以 BLOCKED 开头。
```

成本：每个节点输入 +6 行 token；收益：声明能力全节点可用且不依赖分发器转述。

---

## 2. 反馈路由与分发器重入

### 2.1 共同前置：blockedNode 终态化（NodeEngine 新增，与 completeNode 同构）

`completeNode` 入口处先经 BlockedReader 解析：命中 → `blockedNode(nodeId, feedback)`，否则原路径。`blockedNode` 动作序列：

1. 事务内现读 fresh（同 c467592f 纪律）→ 写 `status=Blocked`、`result=渲染串`、`blockedFeedback`、`blockCount+1`、`completedAt=Some(now)`、`ttlExpireAt=None`；节点已消失 → 拒写。
2. `emitEvent("nodeUpdated", …)`（复用现有 WS 类型与 NodePayload 同构载荷——前端按 status 字段感知，无需新增事件类型；WS 四类型集合不变）。
3. **不结算下游**：`out=节点` 时不做 barrier 占位投递（与 `deliverFailed` 相反——blocked 的语义就是「下游不该带着缺口继续」，传播停止是特性不是缺陷；下游 pending 节点由重入分发器处置，NodeList 可见其 pending + in 指向 blocked 节点）。
4. 调用 FeedbackRouter（§2.3）。

### 2.2 档位 A（自动档）：blocked → 自动重入分发器会话

**触发路径**：`blockedNode` → FeedbackRouter 判定允许 → `ProjectRuntimeRegistry.get(projectName)` 取 `actorRef` → `actorRef ! ProjectCommand.ReenterDispatcher(nodeId, feedback, blockCount)`（新增命令；或 TriggerDispatcher 加可选 feedback 参数，二选一见 §7.5）→ `spawnDispatcher` 复用现有路径 spawn **全新**分发器会话。

> **单例化修订（2026-09-02 作者裁定）**：重入请求与 Task/Mail 新任务一致——项目存在活跃分发器会话（观察桥登记未终态清理者）时，优先注入现有会话（turn 边界串行消费，不并行 spawn 重入会话）；会话已不活跃时才走本节 spawn 路径。实现见主仓 `ProjectActor.dispatchReentry`（activeRef 原子 check-and-inject + 观察桥 pendingInjected 延迟拆除）。

**与 ProjectActor spawn 路径的关系（重入语义的精确表述）**：分发器单次会话、无记忆——「重入」不是恢复旧会话，而是**新 spawn 一次会话并注入上下文**。`spawnDispatcher`（ProjectActor.scala:185-263）原样复用：新 `dispatcher-<uuid8>` 会话、Flow Map 快照注入、观察桥 + supervisorRef 接线——**卡死处置（TaskStuckWatcher Flow 分支）对重入会话自动生效，零额外工作**。唯一差异是 prompt 形态（下）。

**重入 prompt（spawnDispatcher 双形态之二）**：

```
你是项目「<name>」的任务分发器——本轮是【节点反馈重入调整】，不是新任务。

节点 <node-name>（<node-id>）报告 blocked（第 <blockCount> 轮）：
  原因分类：<category>
  说明：<detail>
  对拓扑的建议：<suggestion>

当前 Flow Map 快照（NodeList 数据源）：
```json
<snapshot>
```

先 NodeList 读现状（重点关注：status=blocked 节点、其下游 pending 节点），
再从以下动作中选择并执行（NodeEdit / NodeCancel）：
1. 任务可修 → NodeEdit 编辑该节点（改 task/agent/in/out）触发重激活（blockCount 自动 +1）；
2. 任务应拆分 → 建新节点子图替换，NodeEdit abandon=true 标记旧节点放弃；
3. agent 能力不匹配 → 换 agent 重建；
4. 需外部条件 / 无法提出与上轮实质不同的调整 → abandon + 不重派（避免无效循环）。
所有 Node 工具调用必须带 project=<name>。无需回报——拓扑与状态已落 Flow Map。
```

**幂等与并发**：分发器会话之间无互斥（现状 Mail 连发也是并行 spawn）。NodeEdit 校验层（目标 running 拒改 / D2 已消费拒改接 / loop detect 同 agent+task 拒重建，NodeTools.scala:43-48、119-129、451-459）天然防止两个分发器会话把拓扑改坏；多 blocked 同时发生 → 多个重入会话并发的窗口由 §3.2 频率保护兜住，会话级合并缓冲作为 v1.1 加固（§7.10）。

### 2.3 档位 B（半自动档）：blocked → 投 Nebula 决策

**触发路径**：`blockedNode` → `deliverToNebula("[Node '<name>' blocked]\n<渲染串 + 建议>", nodeName, "blocked")`（NodeEngine.scala:389 现函数，eventType="blocked"）→ Nebula 根会话蓝气泡（NODE · <项目> · <节点> · BLOCKED，chat.js:402-411 fallback 自动渲染）→ Nebula 判断后用 Task 工具重派（TaskTool.scala:78 现路径，即触发新分发器会话）。

- 优点：Nebula 有记忆与全局判断，天然循环熔断；零新机制（全部复用）。
- 缺点：时延不可控（root 会话空闲数小时无人看）；依赖 Nebula 主动行动——**实证案例（§0.2 灰孤儿）正是这条路径的失败**：反馈连结构化都没有，Nebula 看不到、看到了也无从机械处置。

### 2.4 对比与推荐

| 维度 | A 自动档 | B 半自动档 |
|---|---|---|
| 响应时延 | 秒级（turn 结束即触发） | 人工时延（分钟～小时/天） |
| 上下文 | 反馈详情 + 快照注入，分发器专职调整 | Nebula 需自行读 NodeList 重建上下文 |
| 循环风险 | 有 → §3 防循环机制兜底 | 天然低（人不会重复犯） |
| 机制成本 | FeedbackRouter + 重入 prompt + 防循环（≈1 人日） | ≈0（deliverToNebula 现成） |
| 实证教训 | 正面解决灰孤儿模式 | 即今日现状，已证不足 |

**推荐：A 为默认档，B 保留为升级通道与可配置回退档**（§3.1 循环超限、§3.2 频率超限、用户配置 escalate-only 时走 B）。理由：① 分发器单次会话架构下，唯一持有「如何调整拓扑」知识的执行体就是新分发器会话本身，绕道 Nebula 只增加一跳不增加知识；② 灰孤儿实证了「等人看到」的失效模式；③ 防循环（§3）给 A 封了顶，最坏情形 = 2 轮无效重入后升级，风险有界。project 级配置项 `feedbackMode: auto | escalate-only`（project.json 可选字段，默认 auto）保留回退能力。

---

## 3. 防循环

### 3.1 节点级：同一节点 blocked→调整→再 blocked

- **计数**：`NodeDef.blockCount`，`blockedNode` 内 +1；NodeEdit 重激活**不清零**——同一节点身份的累计轮次（换新节点重建的场合由 loop detect + 分发器 abandon 动作覆盖，不计入此数）。
- **上限：2 次重入**（`MaxBlockRoundsPerNode = 2`，NodeEngine 常量）。时序：第 1 次 blocked（count=1）→ 重入调整 → 再 blocked（count=2）→ 第 2 次重入调整 → 再 blocked（count=3 > 2）→ **不再重入，升级 Nebula**：`deliverToNebula`（内容附全轮次反馈历史与 blockCount）+ 节点保持 blocked 常驻活动图，等待 Nebula/用户面板处置。
- 数值依据：任务定义类问题的修复通常一轮可判（行/不行）；第 2 轮给「换思路」机会；第 3 轮几乎必然是系统性问题（需求本身矛盾/外部条件缺失），继续自动轮只是烧 token——2 是信号量与成本的平衡点（可配置，见 §7.2）。

### 3.2 项目级：频率保护

- **实现位置**：ProjectActor 内存状态（`Ref[IO, ProjectGuardState]`，滚动窗口计数）。重启丢失计数可接受——限流器是成本保护不是安全机制，不值得为此加存储 schema。
- **阈值建议**：滚动 10min 窗口内 blocked 事件 ≥5 → 进入 30min cooldown：期间一切 blocked 不触发重入、合并为**单条**升级消息投 Nebula（防消息风暴），cooldown 结束自动恢复 auto 档。
- **分发器侧文本防呆**（§2.2 prompt 第 4 条）：无法提出实质不同调整时 abandon + 不重派；配合现有 loop detect（同 agent+归一化 task 重复建节点直接拒，NodeTools.scala:119-129）双保险。

---

## 4. 用户可见性

### 4.1 数据源（后端单序列化点）

`NodePayload.buildNodeJson`（ProjectTypes.scala:58-78）追加字段：`blockCount`（Int）、`blockedFeedback`（blocked 态才有：`{category, detail, suggestion}`）。NodeList 工具、REST flow-map、WS 事件三路自动同步（同一函数）。

### 4.2 Flow Map 徽标（前端）

| 位置 | 改动 |
|---|---|
| `nodeData.js:34,37-44` | NODE_STATUS 增 `'blocked'`；NODE_STATUS_CLS 增 `blocked: 'blocked'` |
| `flowMapTab.js nodeHtml :126-128` | statusIcon 增 blocked 分支：`<span class="solar-node-status warn">⚑</span>`（区别于 failed 的 ✗） |
| `flowMapTab.js summaryParts :208-220` | 增 blocked 计数段（「N blocked」，i18n key `flowmap.blocked`） |
| `flowCss.js` / `flowMap.css` | `.fm-node.blocked` 琥珀色描边 + `.solar-node-status.warn` 配色（blocked=需要人/分发器动作的警示，非 failed 的红色终结感） |
| `edgeStateOf :161-165` / `nodeContentKey :384` / `visibleNodes :199` | 无需改：blocked 归 idle 边；status 已入 contentKey；blocked 无 ttlLeftSec 天然常显 |

### 4.3 反馈内容查看

点击节点卡片（`openNodeDetail`，flowMapTab.js:626）→ `flowViewers.openNodeResultViewer`（flowViewers.js:79）：status=blocked 时结构化渲染——category 标签 + detail + suggestion + 「已被阻断 N 轮（blockCount）」，result 原文折叠可展开。payload 字段已由 §4.1 就位，openNodeDetail 透传即可。

### 4.4 重入调整动作的审计记录

两级落点：

1. **结构化审计日志（新增，后端）**：`<workspace>/.nebflow/flow-map-events.jsonl` 追加式 JSONL，每行一事件：`{ts, type, project, nodeId, summary}`，type ∈ `blocked / reentry-triggered / reactivated / abandoned / escalated / cooldown-on`。写入点：blockedNode、FeedbackRouter、NodeEdit 重激活/abandon 分支。0 schema 迁移（独立文件不碰 flow-map.json 契约）、append-only、重启保留、grep 友好。
2. **重入会话本身（现有能力，零改动）**：重入分发器会话 `dispatcher-*` 经路由 wsSend（ProjectActor.scala:216）在 bg-agent 面板全程可见（Processing + 工具调用流），会话转写即「分发器这轮做了什么」的完整审计。

前端 v1 不为 events 文件加 REST 端点（徽标 + 详情窗 + Nebula 升级气泡已覆盖日常可见性；events 文件面向排查，可选后续在 Flow Map 头部加「最近调整」行，见 §7.8）。

---

## 5. 与现有机制的边界

| 机制 | 职责 | 与本设计的关系 |
|---|---|---|
| **maxRetries（执行层重试）** | 同参数重跑执行错误 | **现实核查：项目 Node 引擎尚未实现重试循环**（§0.3）——`retries/maxRetries` 仅为 NodeDef 声明字段。本设计不实现它、不依赖它；边界先立：**若未来补执行层重试，只对 failed（瞬时错误）生效，blocked 永不重试**（重跑 blocked 是确定性浪费）。blocked 落在 turn 正常结束后，与执行层重试无交集 |
| **NodeCancel** | 运行中节点的人工/分发器止损 → cancelled | 正交：cancelled 是主动停止、无反馈语义、不触发重入。分发器处置 blocked 节点的「放弃」动作走 NodeEdit `abandon=true`（§6），不是 NodeCancel（其现状仅接受 running，NodeTools.scala:639-641） |
| **悬空结果投递（D1/edit 补投递）** | 终态+有结果的上游被接线 → 立即投递（NodeTools.scala:406-414/539-555） | 正交且互补：blocked 无 result 语义体可投（result 是反馈串），**不得**被 D1 当结果投给下游——D1 的守卫条件是 `result.isDefined && Terminal.contains(status)`，blocked 加入 Terminal 后**必须**收紧为「completed 才投」或按 `result` 渲染串显式排除 blocked（实现注意点，见 §6 风险 R1） |
| **out=Nebula 人工路径** | completed/failed 结果投根会话 | 保留不动；blocked 的升级通道复用同一条 deliverToNebula 通道（eventType="blocked"，前端 label 自动 BLOCKED） |
| **TaskStuckWatcher 10min 零活动** | 会话挂起不结束的处置（Processing + 零活动 → 硬取消 → 桥 Cancelled） | 正交：blocked 声明发生在 turn **正常结束**后（桥收 Completed → completeNode 分流），会话已被观察桥清理（ProjectActor.scala:232-242）；watcher 只管「不结束」。重入会话作为新 spawn 的 Flow 会话自动纳入 watcher 覆盖 |
| **分发器会话卡死与 stuck 管理（并行修复，已落地）** | 分发器 turn 挂死不结束（supervisorRef 接线，ProjectActor.scala:227-259 + TaskStuckWatcher.scala:177-213） | **正交关系**：它处理「会话挂起不结束」；本设计处理「会话正常结束但拓扑需调整」。二者覆盖分发器生命周期的两个失败象限，互不侵入；本设计的重入会话直接继承其全部卡死处置能力 |

---

## 6. 实现落点评估（文件级清单 + 工作量）

> 行号基于 main @ c467592f。规模：S=≤0.5 人日，M=0.5–1 人日。

| # | 文件 | 改动 | 规模 |
|---|---|---|---|
| 1 | `ProjectTypes.scala` | NodeLifecycle.Blocked + Terminal 扩展；BlockedFeedback + Codec；NodeDef + blockCount/blockedFeedback；NodePayload + 2 字段 | S |
| 2 | `NodeEngine.scala` | BlockedReader（§1.3 解析，独立 object 便于单测）；buildInput 协议脚注（§1.5）；completeNode 入口分流；blockedNode（§2.1，含不结算下游）；升级投递复用 deliverToNebula | M |
| 3 | `FeedbackRouter.scala`（新，core/project） | 档位决策：blockCount 上限判定 → ReenterDispatcher 命令 / 升级 Nebula；项目级滚动窗口 + cooldown（状态可挂 ProjectActor Ref 或 router 自持 Ref） | M |
| 4 | `ProjectActor.scala` | 新命令 `ReenterDispatcher(nodeId, feedback, blockCount)`；spawnDispatcher prompt 双形态（新任务 / 重入调整）；项目守卫状态 | M |
| 5 | `NodeTools.scala`（NodeEditTool.editNode/proceed） | blocked 重激活：编辑 blocked 节点且 task/agent/in/out 实际变更 → status 回 wiring/pending、deliveredTo 清空、completedAt/ttlExpireAt/startedAt 复位、blockCount 保留，随后走现有 D1 补投递链；新可选参数 `abandon: true`（终态节点 → status=cancelled + TTL，审计事件） | M |
| 6 | 前端五文件 | nodeData.js（状态常量）/ flowMapTab.js（徽标+计数）/ flowCss.js+flowMap.css（blocked 配色）/ flowViewers.js（结构化反馈详情）/ locales en+zh-CN | M |
| 7 | `chat.js` | **零改动**（nodeStatusLabel fallback 已渲染 BLOCKED；如需配色再补一行） | S |
| 8 | 审计落点 | flow-map-events.jsonl 追加（NodeEngine/FeedbackRouter/NodeTools 各 1 行调用） | S |
| 9 | 测试 | BlockedReader 单测（合法/畸形/正文误含/降级）；重入触发 spec（blocked → ReenterDispatcher 收到）；loop-cap spec（count=3 → 升级不重入）；cooldown spec；NodeEdit 重激活 + abandon spec；D1 不投 blocked 守卫 spec | M |

**合计 ≈ 3.5–4.5 人日**（含联调）。风险点：

- **R1（必须处理）**：blocked 加入 `NodeLifecycle.Terminal` 后，`findDuplicateDispatch`（:127）、D1 投递守卫（:409/:543）、`ensureTargetNotRunning` 等所有 `Terminal.contains` 消费点需逐一复核——D1 必须排除 blocked（反馈串不是可投结果），findDuplicateDispatch 应包含 blocked（防重复派发同任务）。
- R2：blockedNode 与 NodeEdit 重激活的并发竞态——沿用 c467592f 事务内现读 fresh 纪律即可（mutate 单事务）。
- R3：重入会话与初次分发会话并发的拓扑竞争——NodeEdit 校验层兜底（§2.4），v1.1 可加会话合并缓冲（§7.10）。

---

## 7. 待确认点清单（逐条：选项 + 建议）

| # | 决策点 | 选项 | 建议 |
|---|---|---|---|
| 1 | **触发档位** | A 自动重入 / B 投 Nebula / project 级可配 | **A 为默认 + 配置项 `feedbackMode`（auto/escalate-only）**；理由见 §2.4 |
| 2 | **循环上限数值** | 1 / 2 / 3 次重入 | **2**；1 太急（误报一次即升级），3 成本高收益薄 |
| 3 | **项目级频率阈值** | 窗口/数量/cooldown 多种组合 | **10min ≥5 次 blocked → 30min cooldown + 单条合并升级** |
| 4 | **升级消息形态** | 每 blocked 即时投 / 汇总投 | 即时投（单节点循环超限是高信号事件）；仅 cooldown 期合并 |
| 5 | **重入会话载体** | 复用 project-dispatcher + 重入 prompt / 新 agent（project-adjuster） | **复用 + 双形态 prompt**；新 agent 徒增定义面，行为差异全在 prompt |
| 6 | **blocked 节点 TTL** | 永不过期 / 长 TTL（24h） | **永不过期**（待办语义）；abandon/cancelled 后走正常 5min TTL 消失 |
| 7 | **放弃动作载体** | NodeEdit 加 abandon 参数 / 放宽 NodeCancel 接受终态节点 / 新工具 | **NodeEdit abandon=true**；NodeCancel 语义是「停止运行」，污染它代价大 |
| 8 | **events JSONL 前端化** | v1 不做 / Flow Map 头部「最近调整」行 | v1 不做；徽标+详情+升级气泡够用，后续按需加 |
| 9 | **failed 是否也走反馈路由** | 仅 blocked / failed 也重入 | **仅 blocked**；failed 无建议语义且可能瞬时自愈，强行重入会放大 token 消耗；failed 处置待执行层重试落地后另议 |
| 10 | **多 blocked 合并重入（coalescing）** | v1 实现 / v1.1 加固 | **v1.1**；v1 靠频率保护兜底，NodeEdit 校验层保正确性 |
| 11 | **blocked 节点的下游处理** | 停止传播（本设计）/ 像 failed 一样结算占位 | **停止传播**；带着已知缺口跑下游是灰孤儿的反面教材 |
| 12 | **buildInput 协议脚注的 token 成本** | 全节点注入 / 仅分发器在 task 里转述 | **全节点注入**；转述不可靠（分发器会忘），6 行成本可忽略 |

---

## 附：自检核对

- [x] 6 项范围逐项有落点章节（§1–§6）
- [x] 两档路由方案对比明确含推荐（§2.2–§2.4，推荐 A 默认 + B 升级/回退）
- [x] 防循环含具体数值建议（§3.1 节点级 2 次；§3.2 10min≥5 → 30min cooldown）
- [x] 实现落点含文件级清单与工作量估算（§6，9 项 + 3 风险）
- [x] 待确认点完整（§7，12 条，逐条选项+建议）
- [x] 代码锚点全部实核 @ c467592f（§0.3 清单；实证案例含归档/活动区 JSON 逐节点核对）
- [x] 主仓零写入；唯一产出本文件
