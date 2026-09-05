# Flow 体系「互补」修正 + 运行进展可恢复查看（方案）

> 需求提出：用户 2026-08-25 23:59 / 08-26 00:09 修正裁定——「之前说用 dynamic flow 替代目前的 flow，但我觉得这两种是互补的。需要重新出方案。而且现在 dynamic flow 的标签一旦关闭，还无法进入 flow 进展查看了」。
> 本文是 `20260825_flow-redesign-research.md`（v4 取代架构版）的**方向修正**：v4「动态 flow 取代预定义 flow」→「两者互补」；并新增**进展重入机制**设计（核心缺口）。
> 状态：draft v1.0 · 只读分析产出，不触碰产品代码 · 待用户/Manager 确认后派发实施
> 代码证据：当前主仓 `/Users/dev/Claude code/Nebflow`（v1.4.1-beta.51，archive/scala 分支）

---

## 0. 人类可读摘要（TL;DR）

**你说了什么（08-25 23:59 / 08-26 00:09 两条修正）**：

1. **不是取代，是互补**。动态 flow（agent 临时写一个、用一次就扔）和预定义 flow（写好的固定流水线）各有适用场景，两者并存。v4 方案里「存量 9 个预定义 flow 逐个迁移、最终退役」的路径作废——预定义 flow 保留为正式能力。
2. **进展看不到是缺口**。现在 flow 一旦跑起来，弹出 flow-run 标签页；**用户把标签页一关，就再也进不去了**——flow 还在跑，但没有任何入口重新打开查看进展。需要设计「运行中 flow 随时可重新打开查看」。

**现状核对（代码里实际是什么）**：

- **FlowExecute 工具已落地**（`FlowExecuteTool.scala`，ea8262a4）：agent 内联 DAG JSON → 引擎校验 → 即用即弃执行，不落盘 `flows/`。Team 成员与 Nebula 机制层默认注入。
- **运行时弹出标签页已实现**（flowCanvas.js）：`flowStarted` → 自动弹出 `flow-run-<instanceId>` 标签页（solar-system DAG 视图）。
- **「关闭后不复活」已实现**（flowCanvas.js `dismissedFlowRuns` Set）：标签页关闭 → 标记 dismissed → 后续 `flowProgress` 不再自动重开。**但没有任何手动重开入口**——这就是你看不到进展的直接原因。
- **数据其实都在**：前端 `runningFlows` 数组持续更新（标签页开闭不影响）；后端 `GET /api/running-flows` 接口已有；`RunningFlowRegistry` 保留运行中 flow（只清理终态 5 分钟）。
- **9 个预定义 flow 全部健在**（`~/.nebflow/flows/`），实际消费者：Nebula（`flows:["*"]`）、nebflow-project/Manager（code-review / git-merge / nebflow-review-merge / entity-creator）、nebflow-rust（code-review / git-merge）、czt-project（research）。

**本方案两件事**：

1. **互补边界**——一套判据（3 个问题）区分「预定义 flow / dynamic flow / skill」三类；9 个存量 flow 逐个评估：**8 个留预定义、1 个（entity-creator）维持转 skill 裁定**。工具分界写进 FlowExecute/FlowTrigger 描述与 flow-execute skill。
2. **进展重入**——参考 Sub-Agents 面板的成熟模式（运行中 agent 列表可随时打开）：聊天窗口顶部加「运行中 flows」指示器（badge + 下拉列表）→ 点击某 flow 行重新打开其 flow-run 标签页。关闭不自动重开（v4 裁定保留），但**随时可手动重开**。

**分 2 阶段**：① 进展重入（前端为主，小后端）→ ② 互补边界落地（文档 + skill 修订）。验收冒烟测试硬性首位。

---

## 1. 现状核对（v4 方案落地情况——只读代码验证）

### 1.1 已落地（阶段 1 核心已实现）

| 能力 | 代码证据 | 状态 |
|---|---|---|
| FlowExecute 工具（内联 DAG 即用即弃） | `core/tools/FlowExecuteTool.scala`（208 行：内联 FlowDagDef → `FlowStructure.validate` + `EntityLoader.validateFlow`（agent 存在性）→ spawn `FlowDagRunner(dynamic=true)`） | ✅ 已落地（ea8262a4） |
| 机制层注入（Team 成员 + Nebula 默认具备） | `AgentCore.fixedToolsFor`（`case "team" => BaseTools + Mail + SubTask + FlowExecute`；Nebula 特判） | ✅ |
| leaf 剥离（flow 节点 agent 不可再触发 flow） | `AgentCore.buildAllowedToolSet`（剥离 FlowExecute/FlowTrigger/SubTask/Delegate） | ✅ |
| FlowReport 执行上下文注入 | `FlowDagRunner` spawn 节点 agent 时统一注入 | ✅ |
| dynamic 实例 id（`inline-<uuid>`）+ 不写 flows/ | `FlowDagRunner.scala:43-45`（`if dynamic then s"inline-…"`） | ✅ |
| 运行时弹出 flow-run 标签页 | `flowCanvas.js` `maybeAutoOpenFlowsTab` / `openFlowRunTab` / `onFlowStarted` | ✅ |
| 结束态关闭（停留可手动关闭） | `flowCanvas.js:228-231`（`dag-card-close` 按钮） | ✅ |
| flow-execute skill（动态创建指导） | `~/.nebflow/skills/flow-execute/SKILL.md`（订阅：Nebula + nebflow-project/slideblocks/html-deck-studio Manager） | ✅ |

### 1.2 未落地 / 已过时（互补修正影响面）

| v4 计划 | 现状 | 互补修正后 |
|---|---|---|
| 阶段 3-5：存量 flow 逐个迁移 → 退役（转 skill 指导 + 动态创建） | **未开始**（9 个 flow.json 全在，FlowTrigger 白名单全在） | **作废**——预定义 flow 保留为正式能力（§3） |
| 阶段 5：FlowTrigger / flows/ 退役、常驻 UI 移除（侧边栏 Flows 区 + Flows 标签页） | 未开始（flowTeams.js Flows 区 + flowList.js Flows 标签页仍在） | **作废**——预定义 flow 的常驻入口保留（§4.2） |
| 阶段 4：entity-creator skill 化 | 未开始（skill 未建，flow 仍在） | **保留裁定**——entity-creator 转 skill（§3.3，互补框架下仍成立） |
| v4 §4.10「flow 不可见除非运行中」 | 已按此实现（dismissedFlowRuns 抑制重开） | **部分修正**——运行中 flow 增加**常驻轻量指示器**（badge + 下拉），非完整常驻 UI（§5） |

### 1.3 进展重入缺口的代码级根因

```
flow-run-<instanceId> 标签页被用户关闭
  → canvas.js dispatch 'canvas-tab-closed'
  → flowCanvas.js:34-39 把 instanceId 加入 dismissedFlowRuns（Set）
  → 后续 flowProgress/flowStarted：maybeAutoOpenFlowsTab 检查
      if (f.status === 'running' && !dismissedFlowRuns.has(f.instanceId) && !hasTab(...))
      → dismissed 的 flow 永不自动重开 ✅（v4 裁定正确）
  → 但全前端没有任何 UI 入口调用 openFlowRunTab(instanceId)
      → 用户无法手动重开 ❌（缺口）
```

附带不一致（同根因）：`dismissedFlowRuns` 是**内存 Set**——页面刷新后清空。刷新前关闭的标签页，刷新后 `maybeAutoOpenFlowsTab` 会**自动重开**（与「关闭后不复活」语义矛盾）。

**好消息**：数据链路完整——`runningFlows` 数组在标签页关闭后仍持续更新（`onFlowProgress` 更新数组，与标签页开闭无关）；后端 `GET /api/running-flows`（RestApiRoutes.scala:1219-1221）返回 `RunningFlowRegistry.listJson`；`cleanupStale` 只清终态 5 分钟，运行中 flow 永留。**缺口纯在 UI 入口**。

---

## 2. 互补边界：三类形态的判据

### 2.1 判据（3 问决策树）

```
任务需要一个「多 agent 协作流程」，问三个问题：

Q1 结构会变吗？——这个流程的 DAG 形状（节点/路由/分支）是固定的，还是随任务变化？
   固定 → 候选「预定义 flow」
   随任务变 → 候选「dynamic flow」

Q2 会以相同形状反复调用吗？——同形状调用 ≥3 次，还是用一次就完？
   ≥3 次 → 强化「预定义 flow」
   一次性 → 强化「dynamic flow」

Q3 需要纪律/质量闸门吗？——需要 verdict 门禁/评审轮次/版本化/验收点，还是纯执行？
   需要 → 预定义 flow（或 skill，见 §2.3）
   纯执行 → dynamic flow

结果：
   Q1 固定 + Q2 反复 + Q3 需要纪律 → 预定义 flow（FlowTrigger）
   Q1 随任务 / Q2 一次性 / 纯执行     → dynamic flow（FlowExecute）
   Q3 需要的本质是「行为纪律」而非「并行编排」→ skill
```

### 2.2 预定义 flow = 固定流水线（Fixed Pipeline）

**定义**：磁盘 `flows/<name>/flow.json` 定义的、形状预先可枚举的固定流水线，经 FlowTrigger 白名单触发，反复以相同形状调用。

**适用特征**：
- **结构可枚举**（flows/GUIDE.md A1：能预先画出完整节点图 + 每个 switch 的 case keys）
- **同形状高频复用**（A3：≥3 次）
- **输入同质**（A2：每次调用 `$task` 形状一致，参数走 `params` schema）
- **需要纪律/质量闸门**：verdict 门禁、评审轮次（maxLoop）、strictVerdict、失败重做
- **角色职责固定**：每个节点绑定领域角色（reviewer/coder/packager/scanner…）
- **有持久身份**：flow 名稳定，供白名单、团队挂载、Schedule 定时、文档引用

**典型**：release-stable（五步发布）、code-review（scan→review→fix 循环）、git-merge、nebflow-review-merge、weekly-summary（Schedule 定时）、memory-consolidation、research（骨架固定：planner→r→verify→writer）、presentation-prep（骨架固定：planner→恰 4 路→content-planner→visual-designer→verifier）。

### 2.3 dynamic flow = 一次性任务编排（Ad-hoc Orchestration）

**定义**：FlowExecute 内联 DAG JSON，即用即弃——定义是执行调用的参数，无持久身份、不落盘。

**适用特征**：
- **结构随任务变化**：每次按任务重写 DAG（v3 裁定「flow 流程由 agent 根据任务动态创建，无固定模板」）
- **一次性 / 低频**：用完即弃，不沉淀复用
- **并行规模由任务决定**：64 页 deck → 64 worker + 64 QA；N 路调研 → N 个 researcher（maxFanout 是 flow 声明的规模，非系统限制）
- **纯执行**：无跨调用身份需求，不需要版本化/白名单

**典型**：64 页 deck 两级并行（worker → QA）、多路调研 fanout、批量验证、临时多阶段 pipeline、任何「形状随任务变」的编排。

### 2.4 skill = 行为指导（Behavioral Guide）——第三类

**定义**：`~/.nebflow/skills/<name>/SKILL.md`，教 agent「怎么做」的行为指导，不依赖引擎编排。

**与 flow 的本质区别**：flow 解决「**多个角色节点如何并行/串行编排**」；skill 解决「**单一 agent 按什么步骤/纪律完成**」。当流程的并行结构是表象、本质是行为纪律时 → skill。

**典型**：entity-creator——architect→builder→reviewer 循环的本质是「设计 spec → 构建 → 自测 → 评审 → 迭代」的行为纪律（验收点二值化、callerScope 门禁、最多 2 轮 revise），单一调用者按 skill 步骤即可承担全部角色，不需要引擎编排三个串行角色。用户 08-25 15:03 已裁定转 skill，互补框架下**该裁定保留**（§3.3）。

### 2.5 边界交叉情况（重要——不是非此即彼）

| 交叉场景 | 判定 | 归属 |
|---|---|---|
| 骨架固定 + 规模可变（research：planner→r×N→verify→writer） | 骨架可枚举（Q1 固定）、同形状复用（Q2）、fanout 数量走 `maxFanout` 参数 | **预定义**（research 已如此：maxFanout=2） |
| 骨架固定 + 内容随任务（presentation-prep：恰 4 路正交检索是硬约束） | 路由可枚举、4 路并行是固定结构 | **预定义** |
| 结构固定但一次性（某次临时 review 想用 code-review 形状） | Q2 一次性 → 但**复用现有预定义 flow 即可**（不必重写 DAG） | **预定义（触发）**——一次性不等于必须 dynamic |
| 结构随任务 + 反复用（每次不同的多路调研） | Q1 随任务变 → dynamic；若反复用同一类调研，沉淀**指导**（skill）而非固定 DAG | **dynamic**（+ 可选 skill 指导） |
| 64 页 deck | 结构其实固定（planner→worker→qa→aggregator），但规模（64）、内容、频次均随任务 | **dynamic**（无持久身份需求；用 FlowExecute 每次现写） |

**关键原则**：「一次性」≠「必须 dynamic」——如果某次任务恰好匹配一个已存在的预定义 flow 形状，直接用 FlowTrigger 触发即可；dynamic 的价值在于**形状不匹配现有预定义 flow** 时现写。同理，「预定义」≠「每次都要新建文件」——沉淀是降低成本，不是约束。

---

## 3. 存量 flow 逐个评估表（读实际 flow.json）

> 依据：`~/.nebflow/flows/<name>/flow.json` 实际内容（节点/路由/maxLoop/maxFanout）+ 白名单引用（agents/Nebula `flows:["*"]`；teams/nebflow-project/Manager：code-review/git-merge/nebflow-review-merge/entity-creator；teams/nebflow-rust：code-review/git-merge；teams/czt-project：research）。

| flow | 实际结构（flow.json） | Q1 结构 | Q2 复用 | Q3 纪律 | 判定 | 理由 |
|---|---|---|---|---|---|---|
| **release-stable** | reviewer→coder→packager（switch 循环 maxLoop=3） | 固定 | 高频（每次发版） | 强（真实 release 风险，需构建验证） | **留预定义** | 五步发布流水线是固定形状 + 最高风险需要纪律闸门；v4「最后迁移」的定位在互补框架下直接取消 |
| **code-review** | scanner→reviewer→fixer（switch 循环 maxLoop=5） | 固定 | 高频（评审是常态动作） | 强（3 维评分 + check-fix 循环） | **留预定义** | 评审纪律（verdict 门禁、轮次）正是预定义 flow 的价值；nebflow-project/nebflow-rust 已在白名单 |
| **git-merge** | scanner→evaluator→merger（switch 循环 maxLoop=3） | 固定 | 高频（合并是常态动作） | 中（质量检查 + 冲突处理） | **留预定义** | 固定三步流水线；nebflow-rust 已在白名单 |
| **nebflow-review-merge** | scanner→reviewer→merger（maxLoop=1） | 固定 | 高频（Nebflow 项目日常） | 强（编译+测试+review+merge+worktree 清理） | **留预定义** | Nebflow 项目专用固定流水线；nebflow-project/Manager 白名单已引用 |
| **research** | planner→r1,r2→verify→writer（parallel maxFanout=2） | 骨架固定（fanout 规模参数化） | 高频（调研是常态动作） | 中（verify 核验） | **留预定义** | 骨架固定 + 规模走 maxFanout；czt-project 白名单已引用；fanout 数量可调（2→N）不必重写 DAG |
| **presentation-prep** | planner→4×r→content-planner→visual-designer→verifier（parallel maxFanout=4） | 骨架固定（恰 4 路正交检索是硬约束） | 高频（html-deck-studio 每次 deck） | 强（verifier 独立校验/禁占位符） | **留预定义** | 4 路并行是固定结构；v4 曾计划「领域角色迁全局库」——互补框架下角色留在 flow 内，无需迁移 |
| **weekly-summary** | summarizer→reporter（2 节点 maxLoop=1） | 固定 | 高频（每周定时） | 中（格式纪律） | **留预定义** | Schedule 定时触发依赖 flow 持久身份 |
| **memory-consolidation** | scanner→consolidator（2 节点 maxLoop=1） | 固定 | 中 | 中（清理纪律） | **留预定义** | 固定两步流水线；形状稳定无迁移必要 |
| **entity-creator** | architect→3×builder→3×reviewer（switch 评审循环 maxLoop=3） | 固定 | 中 | 强（验收点二值化 + callerScope 门禁 + 2 轮 revise 纪律） | **转 skill（维持 08-25 裁定）** | 循环本质是行为纪律（§2.4），单一 agent 按 skill 步骤可承担全部角色；7 个专用角色职责并入 skill 正文，不残留僵尸角色 |

**结论**：9 个 flow 中 **8 个留预定义、1 个（entity-creator）转 skill**。v4 的「其余 8 个 → skill 指导 + agent 动态创建」路径全部作废——预定义 flow 保留为正式能力，**不迁移、不退役**。

---

## 4. 共存方式

### 4.1 工具层：FlowTrigger vs FlowExecute 何时用哪个

**分界写入两个工具的 description + flow-execute skill**（这是「互补」落地到 agent 行为的关键）：

| 场景 | 工具 | 一句话判据 |
|---|---|---|
| 任务匹配**已存在的预定义 flow** 形状（release-stable / code-review / git-merge / nebflow-review-merge / research / presentation-prep / weekly-summary / memory-consolidation） | **FlowTrigger** | 有现成固定流水线，直接触发，别重写 |
| 任务需要**形状随任务变**的编排 / **并行规模由任务决定**（64 页 deck、N 路调研、批量验证、临时 pipeline） | **FlowExecute** | 没有匹配的预定义 flow，现写 DAG 即用即弃 |
| 创建/修改 agent/team/flow 实体 | **entity-creator skill**（转 skill 后） | 行为纪律，不是流水线 |
| 单个自主 worker | Delegate / SubTask | 不需要 flow |

**决策提示**（写进 FlowExecute description 的 Transition 段，替换现「过渡期」表述）：

```
FlowTrigger: 固定流水线（flows/ 定义、白名单触发、反复同形状调用、需纪律闸门）
FlowExecute: 一次性编排（内联 DAG 即用即弃、形状随任务、并行规模按任务定）
先检查是否有匹配的预定义 flow —— 有则 FlowTrigger；没有且任务需要多 agent 编排 → FlowExecute。
两者共享同一执行引擎，可放心混用；flow 节点内不可再触发 flow（leaf 规则）。
```

### 4.2 UI 层：flow-run 标签页 + 预定义 flow 入口如何共存

**v4 裁定（去侧边栏 flow 列表/去 Flows 管理标签页）在互补框架下的修订**：

- **预定义 flow 的常驻入口保留**：Flows 管理标签页（flowList.js + flows-btn）与团队卡片 Flows 区（flowTeams.js）**不移除**——预定义 flow 有持久身份（flow.json 文件），需要浏览/管理/查看 DAG 的入口。v4 阶段 5「常驻 UI 移除」作废。
- **dynamic flow 无常驻管理入口**（保持 v4）：dynamic flow 定义即执行、用完即弃，无持久身份可管理——不进入 Flows 列表。它的运行视图 = flow-run 标签页（运行时弹出）。
- **运行中 flow 的统一入口**：新增「运行中 flows」指示器（§5）——覆盖**两种 flow 的运行中查看**（预定义与 dynamic 的 flow-run 标签页渲染逻辑完全一致，indicator 统一列出）。
- **flow-run 标签页本身不变**：运行时弹出、solar-system DAG 视图、结束态停留可手动关闭——两种 flow 共用。

### 4.3 文档层：两处修订

| 文档 | 现状 | 修订 |
|---|---|---|
| `docs/Nebflow/20260825_flow-redesign-research.md`（v4） | 取代架构：阶段 3-5 迁移退役、阶段 5 常驻 UI 移除、entity-creator skill 化 | **文件头加 §0.4 v4→v5 偏差对照**（互补裁定逐条落位：迁移/退役路径作废、常驻 UI 保留、entity-creator 裁定维持、动态 flow 增加运行中指示器）；正文不改（保留版本轨迹，v5 以本文为准） |
| `~/.nebflow/skills/flow-execute/SKILL.md` | §1 工具关系分界：「固定流水线（过渡期，尚未迁移）」→ FlowTrigger；正文称「预定义 flow/FlowTrigger 过渡期保留，后续退役」 | **§1 改为互补分界**（§4.1 表格）；删除「过渡期/后续退役」表述；新增「先检查是否有匹配的预定义 flow → FlowTrigger；没有且需多 agent 编排 → FlowExecute」决策提示；补充 9 flow 现成清单（flow 名 → 用途），让调用者先查后写 |

---

## 5. 进展重入机制（核心缺口设计）

### 5.1 设计目标与原则

| 原则 | 说明 |
|---|---|
| **关闭不自动重开**（保留 v4 裁定） | dismissedFlowRuns 抑制自动重开——用户主动关闭的标签页不复活 |
| **运行中随时可手动重开**（新能力） | 常驻轻量指示器（badge + 下拉）→ 点击 flow 行 → 重新打开 flow-run 标签页 |
| **刷新后不丢** | 页面刷新 → 从 `/api/running-flows` 恢复运行中 flow 列表 → badge 可见 → 可重开 |
| **不违背 v4 UI 形态** | 指示器是**瞬态运行状态**（badge + 下拉），不是 flow 列表/管理面板——与 Sub-Agents 的 bgagent 指示器同构，不恢复侧边栏 flow 区 |
| **复用成熟先例** | 完全照抄 Sub-Agents 面板模式（d21ec3df 等，运行中 agent 列表可随时打开）：常驻指示器 + 后端快照查询 + 点击打开查看器 |

### 5.2 参考先例：Sub-Agents 面板模式（已验证）

| Sub-Agents 面板（d21ec3df 等） | flow 进展重入（本设计） |
|---|---|
| 聊天窗口顶部 bgagent 指示器（count badge，**常驻**） | 同位置新增 flows 指示器（count badge，常驻） |
| 点击 → dropdown 列出运行中 sub-agents（状态点/名字/任务/uptime/retries） | 点击 → dropdown 列出运行中 flows（状态点/flow 名/节点进度/耗时） |
| 点击行 → bgAgentPopup（实时消息查看器） | 点击行 → openFlowRunTab（重新打开 flow-run 标签页，实时 DAG 视图） |
| 后端 `getActiveAgents` WS 快照（reconnect 恢复） | 后端 `GET /api/running-flows` **已有**（refresh/初始化时 fetch） |
| hidden ChatView 继续收事件（dirtyWhileHidden，重开刷新历史） | `runningFlows` 数组继续更新（与标签页开闭无关）——重开时直接渲染最新数据 |

### 5.3 设计详情

**入口形态**（聊天窗口顶部指示器区，与 bgagent badge 并列）：

```
[Chat 窗口头部]
  … [⚙ 设置] [后台任务 ⚙ 2] [运行中 flows ◈ 1] [后台 agents ● 3] …
                     └─ 点击展开 dropdown：
                        ┌──────────────────────────────────┐
                        │ Running flows (1)                │
                        │ ◈ deck-64p   运行中  12/64 节点  2:34 │
                        │   └ 点击行 → 重新打开 flow-run 标签页    │
                        └──────────────────────────────────┘
```

- badge 显示**运行中**（status=running）flow 数；全部终态 → 隐藏。
- dropdown 每行：状态点（running/completed/failed 色阶）+ flow 名 + 节点进度（`x/y done`）+ 耗时 + cancel 按钮（复用现有 `dag-card-cancel` 的 `cancelFlow` WS 消息）。
- 点击行 → `openFlowRunTab(instanceId, flowName)`：标签页已开则 `setActiveTab`；未开则新建 + `renderFlowRunTab`；**同时从 `dismissedFlowRuns` 移除该 instanceId**（手动重开 = 用户显式意图，清除 dismiss 标记，之后 flowProgress 更新正常渲染）。
- 终态 flow 在 dropdown 保留短暂可见（completed/failed 灰显）或直接消失——沿用 RunningFlowRegistry 5 分钟清理语义，前端在 flowCompleted 后从 dropdown 移除运行中标记。

**dismissed 语义修正**：

| 场景 | 修正前（v4 实现） | 修正后 |
|---|---|---|
| 用户关闭 flow-run 标签页 → 后续 flowProgress | 不自动重开 ✅ | 不自动重开（不变）✅ |
| 用户关闭 → **手动从指示器重开** | 无入口 ❌ | **可重开**（清除 dismissed 标记）✅ |
| 页面刷新 → 之前 dismissed 的 flow | dismissedFlowRuns 内存清空 → **自动重开**（不一致）❌ | dismissed 标记改存 **sessionStorage** → 刷新后仍不自动重开（语义一致）；但指示器 badge 可见、**可手动重开** ✅ |

**数据流（无需新事件协议）**：

```
FlowExecute / FlowTrigger 触发
  → FlowDagRunner 生成 instanceId（inline-<uuid> / flow-<name>-<uuid>）
  → FlowDagExecutor.execute: registerFlow → RunningFlowRegistry（内存保留运行中）
  → WS: flowStarted / flowNodesAdded / flowProgress / flowCompleted（全局广播至调用者窗口）
  → 前端 runningFlows 数组持续更新（与标签页开闭无关）✅ 已具备
  → 新增：flows 指示器 badge 从 runningFlows 派生；dropdown 从 runningFlows 渲染
  → 刷新恢复：fetchRunningFlows()（GET /api/running-flows）✅ 接口已具备
```

**后端改动（最小）**：

| 改动 | 现状 | 为什么 |
|---|---|---|
| `RunningFlowRegistry.registerFlow` 设置 `sessionId`（= rootSessionId） | `RunningFlow.sessionId = None`（registerFlow 未传） | 前端按会话归属显示 badge（「我的会话触发的 flow」）；WS 事件已按调用者窗口路由，sessionId 补齐一致性 |
| `flowStarted`/`flowCompleted` 事件带 `sessionId` | 不带 | 前端 `onFlowStarted` 记录归属；badge 可按会话过滤（可选增强，MVP 可全局显示） |
| `GET /api/running-flows` | ✅ 已有（RestApiRoutes.scala:1219-1221） | 不改 |
| `RunningFlowRegistry.cleanupStale` | ✅ 只清终态 5 分钟，运行中永留 | 不改 |

**前端改动**：

| 文件 | 改动 |
|---|---|
| `flowCanvas.js` | ① 新增 `renderFlowsIndicator()`：badge 计数 + dropdown 渲染（复用 flowTeams.js 卡片样式或轻量行样式）；② 点击行 → `openFlowRunTab` + `dismissedFlowRuns.delete(instanceId)`；③ `dismissedFlowRuns` 改 **sessionStorage 持久化**（`dismissedFlowRuns` 初始化从 sessionStorage 读，`canvas-tab-closed` 时写回）；④ `flowCompleted` 后刷新 badge（已有 `fetchRunningFlows` 调用）；⑤ 初始化（页面加载/onSessionChange）时 `fetchRunningFlows()` + 更新 badge |
| `chatView.js` / `input.js`（顶部指示器区） | 加 flows badge DOM（与 bgagent badge 并列）；样式复用 bgagent 指示器 |
| `ws.js` | `onFlowStarted`/`onFlowCompleted` 透传 `sessionId`（可选）；其余不动 |
| `flowDag.js` | 复用 `dagCardHtml`（dropdown 行可简化版）；`renderFlowRunInto` 不动 |

### 5.4 与 v4 裁定的关系（明确边界）

| v4 裁定 | 本设计 | 关系 |
|---|---|---|
| 去掉侧边栏 flow 列表/入口 | 保留（预定义 flow 需管理入口） | 互补修正（§4.2） |
| 去掉 Flows 管理标签页 | 保留 | 互补修正（§4.2） |
| 去掉常驻标签页，仅运行时弹出 | flow-run 运行时弹出保留；**新增运行中指示器**（badge+下拉） | 指示器是瞬态状态指示（同 bgagent badge），非 flow 列表/管理面板——不构成「常驻 flow UI」 |
| flow 不可见除非运行中 | 运行中 flow **可见**（指示器列出运行中的），终态后消失 | 这正是用户要的修正：「标签关闭还能进 flow 进展查看」 |

---

## 6. 分阶段实施 + 验收点（二进制）

> 冒烟测试硬性首位（红线规则：涉及服务端/前端，必须真实启动 + curl/Playwright 断言）。

### 阶段 1：进展重入（核心缺口，前端为主 + 最小后端）

**改动**：
- 后端：`registerFlow` 传 `rootSessionId` 到 `RunningFlow.sessionId`；`flowStarted`/`flowCompleted` 事件带 `sessionId` 字段。
- 前端：flows 指示器（badge + dropdown + 点击重开 + dismiss 清除）；`dismissedFlowRuns` 改 sessionStorage 持久化；页面加载/刷新时 `fetchRunningFlows` + badge 更新。
- `flowCanvas.js`/`chatView.js`/`ws.js` 按 §5.3 改动表。

**验收**：
- [ ] **冒烟测试**：`sbt run` 真实启动 → FlowExecute 触发 2-worker 并行测试 flow（内联 DAG：planner 拆 2 topics → ParallelDynamic 展开 2 worker → barrier join → aggregator）→ 断言 `flowStarted` 事件出现、`flow-run-<instanceId>` 标签页自动弹出、节点逐个 running→done、`flowCompleted(success=true)`。
- [ ] **关闭后不自动重开**（Playwright）：flow 运行中关闭 flow-run 标签页 → 等待 `flowProgress` 事件 → 断言**无新标签页自动弹出**（v4 裁定保持）。
- [ ] **手动重开**（Playwright）：关闭后点击 flows 指示器 badge → dropdown 显示该 flow 行（含状态/节点进度）→ 点击行 → 断言 `flow-run-<instanceId>` 标签页重新打开且渲染节点状态与运行中一致。
- [ ] **刷新恢复**（Playwright）：flow 运行中刷新页面 → 断言 flows 指示器 badge 显示（count=1）→ 点击 dropdown → 点击行重开标签页 → 渲染正确；之前 dismissed 的 flow 刷新后**不自动重开**（sessionStorage 生效）。
- [ ] **dismissed 持久化单测**：关闭标签页 → sessionStorage 含 instanceId；手动重开 → 标记清除；后续 flowProgress 正常更新渲染。
- [ ] **sessionId 断言**：flowStarted 事件带 `sessionId` = 调用者 rootSessionId；`RunningFlow.sessionId` 正确设置（单测 + 日志验证）。
- [ ] **终态消失**：flow 完成后 badge 计数归零（或 dropdown 灰显 completed），不残留运行中标记。
- [ ] **回归**：预定义 flow（FlowTrigger 触发 research/code-review）端到端跑通一次，行为不变（flow-run 标签页照常弹出）。

### 阶段 2：互补边界落地（文档 + skill，无代码）

**改动**：
- `docs/Nebflow/20260825_flow-redesign-research.md` 头部加 §0.4 v4→v5 偏差对照（互补裁定逐条落位）。
- `~/.nebflow/skills/flow-execute/SKILL.md` §1 改为互补分界 + 9 flow 现成清单 + 决策提示。
- `FlowExecuteTool.description` 的 Transition 段改互补表述（「过渡期」→「互补分界」，替换现 `description` 中 "Predefined flows stay working during the transition" 类表述——确认现状后修订）。
- （可选）`FlowTriggerTool.description` 补充「本工具用于固定流水线；一次性/形状随任务编排用 FlowExecute」。

**验收**：
- [ ] **文档断言**：20260825 文档头部含 §0.4 偏差对照（互补裁定 4 条：迁移/退役作废、常驻 UI 保留、entity-creator 裁定维持、运行中指示器新增）。
- [ ] **skill 断言**：flow-execute SKILL.md §1 无「过渡期/后续退役」表述；含 9 flow 现成清单（名称+用途+触发条件）与决策提示。
- [ ] **工具描述断言**：FlowExecute description 含互补分界（先查预定义 → FlowTrigger；无匹配且需多 agent 编排 → FlowExecute）；无「dynamic 取代」残留表述。
- [ ] **无预定义 flow 迁移**：`git status` flows/ 目录 9 个 flow.json 全部未删（git 断言）。

### 阶段 3（backlog，可选）

- 运行中 flow 的节点详情快照（flow-run 标签页关闭期间节点输出只留最新——如需历史可在 dropdown 行展开显示节点最近输出；低优先级）。
- 多窗口/多会话 flow 归属过滤（badge 按会话显示自己触发的 flow；需 WS 事件 sessionId 先行，阶段 1 已带）。

---

## 7. 改动文件清单汇总

| 文件 | 阶段 | 改动 |
|---|---|---|
| `src/main/scala/nebflow/core/entity/FlowDagExecutor.scala` | 1 | `registerFlow` 接收 rootSessionId 并设置 `RunningFlow.sessionId`（execute 已有该参数）；`flowStarted`/`flowCompleted` 事件带 `sessionId` |
| `src/main/scala/nebflow/core/flow/RunningFlowRegistry.scala` | 1 | （可选）`toJson` 暴露 sessionId——已有字段，确认序列化包含 |
| `src/main/resources/web/js/flowCanvas.js` | 1 | flows 指示器（badge/dropdown/点击重开/dismiss 清除）；`dismissedFlowRuns` 改 sessionStorage；初始化 fetchRunningFlows |
| `src/main/resources/web/js/chatView.js`（或 input.js） | 1 | 顶部指示器区加 flows badge DOM + 绑定 |
| `src/main/resources/web/js/ws.js` | 1 | `onFlowStarted`/`onFlowCompleted` 透传 sessionId |
| `src/main/resources/web/js/flowDag.js` | 1 | （可选）dropdown 行渲染辅助 |
| `src/main/scala/nebflow/core/tools/FlowExecuteTool.scala` | 2 | description Transition 段改互补分界 |
| `src/main/scala/nebflow/core/tools/FlowTriggerTool.scala` | 2 | （可选）description 补互补分界 |
| `docs/Nebflow/20260825_flow-redesign-research.md` | 2 | 头部加 §0.4 v4→v5 偏差对照 |
| `~/.nebflow/skills/flow-execute/SKILL.md` | 2 | §1 互补分界 + 9 flow 清单 + 决策提示 |
| `~/.nebflow/docs/Nebflow/INDEX.md` | 1 | 本文登记一行 |

**明确不动的**：`flows/` 目录 9 个 flow.json（全保留）；`FlowTriggerTool` 工具本体；`flowTeams.js` Flows 区 + `flowList.js` Flows 标签页（预定义 flow 管理入口保留）；`AgentCore.fixedToolsFor`（FlowExecute 注入已生效）；`FlowDagExecutor` 引擎（两形态共享，无分叉）。

---

## 8. 本地代码证据（现状分析引用）

| 文件 | 关键位置 |
|---|---|
| `src/main/scala/nebflow/core/tools/FlowExecuteTool.scala` | 全文（208 行：内联 DAG 校验 + spawn dynamic runner；description 含 Transition 段「Predefined flows stay working during the transition」——阶段 2 修订对象） |
| `src/main/scala/nebflow/core/flow/RunningFlowRegistry.scala` | RunningFlow.sessionId L36；register/update/list/listJson L112-205；cleanupStale（终态 5 分钟清理，运行中保留）L164-172 |
| `src/main/scala/nebflow/core/entity/FlowDagExecutor.scala` | execute L119-138（rootSessionId 参数）；registerFlow L889-915（**未传 sessionId** —— 阶段 1 改动点）；flowStarted 广播 L827-852；flowCompleted L876-883 |
| `src/main/scala/nebflow/core/flow/FlowDagRunner.scala` | instanceId 生成 L43-45（dynamic → `inline-<uuid>`）；RunFlow L21-38 |
| `src/main/scala/nebflow/gateway/RestApiRoutes.scala` | GET /api/running-flows L1219-1221（**已有**，阶段 1 不改） |
| `src/main/resources/web/js/flowCanvas.js` | dismissedFlowRuns L27/L34-39（**内存 Set —— 刷新丢失**）；maybeAutoOpenFlowsTab L239-246（dismissed 抑制自动重开）；openFlowRunTab L206-210；renderFlowRunTab L212-235；onFlowStarted L250-267；onFlowProgress L324-332；onFlowCompleted L334-338；fetchRunningFlows L353-360；openTeams L434-448（已有 fetchRunningFlows + maybeAutoOpenFlowsTab 调用） |
| `src/main/resources/web/js/flowDag.js` | renderFlowRunInto L253-255；dagCardHtml L187；bindDagNodeClicks（cancelFlow WS）L292-303 |
| `src/main/resources/web/js/bgAgentPopup.js` | Sub-Agents 先例：hidden ChatView 收事件（dirtyWhileHidden）L115-120；openStepPopup L85+ |
| `src/main/scala/nebflow/gateway/WebSocketRoutes.scala` | getActiveAgents 快照恢复 L2503-2536；filterActiveAgents L4916-4924（先例：运行中任务列表可恢复） |
| `~/.nebflow/flows/*/flow.json` ×9 | §3 评估表依据（结构/路由/maxLoop/maxFanout 实测） |
| `~/.nebflow/skills/flow-execute/SKILL.md` | §1「固定流水线（过渡期，尚未迁移）」——阶段 2 修订对象 |
| `docs/Nebflow/20260825_flow-redesign-research.md` | v4 取代架构版——阶段 2 加 §0.4 偏差对照 |
