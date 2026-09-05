# Plan Mode 残链全链审计报告（2026-09-05）

- 任务：Plan mode 残链全链审计 + 条件处置（QC 82af52e5「Seeds.all 收敛」指认的潜在死链）
- 执行：general（原 Coder/design-engineer 已随 F.3 归档退役，基础工具面执行）
- 审计基线：**main@59aba2ff**（worktree plan-mode-retire 基线 cc506071；实测 cc506071→59aba2ff 仅 friends/neblink 链路变动，plan 相关文件在两基线间**零差异**，locales 为纯追加 friend 键——本报告行号对两基线均有效）
- worktree 状态：**零写入，保留 cc506071 现场**；`git merge main` 尝试因沙箱 EPERM 失败（`ORIG_HEAD.lock` 不可写——worktree gitdir 在主仓 `.git` 下，任务书预言的先例常态，**非冲突**）

---

## 一、判定结论（先行）

**分支 K（保持 + 结构化报告，零改码）。**

可达性裁决：**运行时不可达（双重死）**——UI 零入口 + 后端 Explorer 解析必 None。
退役条件（R 三条件需同时满足）：① 不可达实锤 ✓；② 四事件仅 plan 链内 ✓；③ **作者明确废弃证据 ✗——反证压倒性**（见 §五）。→ K。

---

## 二、全链图（每段含 文件:行 证据）

```
[UI 入口] ✗ 不存在
   enterPlanMode 定义 input.js:224 —— web/ 全域唯一出现处（穷尽 grep：无 onclick/data-action/
   快捷键/动态 import/字符串调用；src/main/scala 零引用）
   ↓（planMode 标志唯一置位点 = input.js:226，仅在 enterPlanMode 体内 → 永false）
[发送端]（不可达）
   input.js:553-564  sendMessage 内 `if (v.stream.planMode)` 守卫 →
   input.js:560      sendWs({type:'command', command:'plan', sessionId, task})   ← plan 命令唯一发送点
   （REST API 零 plan 入口——RestApiRoutes.scala grep 零命中）
   ↓
[WS 路由] WebSocketRoutes.scala:1217-1224  case "plan" → :1223 ensureAgent(...)!AgentCommand.StartPlan(planTask)
[命令定义] protocol.scala:153  case class StartPlan(task) extends AgentCommand
   ↓
[AgentActor 主入口] AgentActor.scala:1080-1119
   :1081 logAgentEvent "plan-start"（纯日志 AgentCore.scala:55-67，不达前端）
   :1084 resources.agentLibrary.get("Explorer")   ← ★ 断点：硬编码 Explorer
   :1086-1087 PlanningPrompt + explorerDef.systemPrompt 拼接
   :1089-1100 PlanAgent.spawn
   :1102-1103 PlanModeState(...) → planWaiting(...)
   :1105-1118 None → wsSend error "Explorer agent not found — cannot start plan mode"  ← F.3 后必走此支
   ↓
[PlanAgent] PlanAgent.scala（全 160 行）
   :30-40 PlanningPrompt（read-only 规划提示词）
   :48-104 spawn：plan-agent-<uuid8> 子 AgentActor（:65-87，freezeExempt=true :84）+
     adapter（:88-91，AgentEvent→PlanTurnComplete/PlanFailed 回传主 agent :111-140）
   :94-101 wsSend planStart 事件（type/sessionId/agentId/task）  ← 事件① 产生点（唯一）
   ↓
[planWaiting 状态机] AgentActor.scala:1272-1492（含 V7 缓冲 2026-09-03）
   :1281-1293 PlanTurnComplete → wsSend planReady（:1287）  ← 事件② 产生点（唯一）
   :1295-1309 PlanFeedback → 转发 UserInput 给 plan agent
   :1311-1369 PlanApproved → stop plan agent + wsSend planEnd(reason=approved)（:1328）→
              批准计划注入为 user message → pipeLlmCall 执行计划
   :1371-1389 PlanCancelled → wsSend planEnd(reason=cancelled)（:1380）→ exitPlanWindow
   :1391-1419 PlanFailed → wsSend planEnd(reason=failed,error)（:1398）
   :1421-1434 Interrupt → wsSend planEnd(reason=cancelled)（:1427）→ exitPlanWindow
   :1463-1483 V7 真缓冲：UserInput/ExternalEvent/ImmediateInput → pending* 队列
   :1505-1539 exitPlanWindow：四出口统一 F1 排空（drainQueuesAfterCompaction）
[冻结态排队] AgentActor.scala:4047-4055  frozen 态下 StartPlan → pendingUserInputs 排队不唤醒
   （★ 分发器预侦察称「planWaiting 态」——实为 frozen 态，已纠正；planWaiting 自身排队在 :1463-1483）
   ↓
[前端接收]（planStart 到达后即为活管道）
   main.js:3202-3205  onMessage 挂 planStart/planReady/planEnd/_planAgent 四 handler
   planMode.js:37-64  onPlanStart（state.planAgentId/planSessionId 置位 state.js:239-242）/
     onPlanReady（:50-56 内联卡片渲染 :119-135）/ onPlanEnd（:58-64 清理）
   ws.js:570-578  `_planAgent` 非后端事件——是前端拦截别名：agentTextDelta 且
     msg.agentId===state.planAgentId 时改道 planMode.onPlanAgentEvent（planMode.js:44-48 累积 plan 文本）
   动作环（后端已全套接线）：planMode.js:68-115 approvePlan/cancelPlan/sendFeedback →
     sendWs planApprove/planCancel/planFeedback → WebSocketRoutes.scala:997-1016 →
     AgentCommand.PlanApproved/PlanCancelled/PlanFeedback → planWaiting 各分支
   index.html:45 planMode.css link；:270 plan-indicator 指示器；input.js:243-260 指示器显隐、
     :1588-1595 indicator 取消钮绑定（仅 planMode=true 时可见）
```

**四事件产生点穷尽结论**：`planStart` 仅 PlanAgent.scala:96；`planReady` 仅 AgentActor.scala:1287；`planEnd` 仅 :1328/:1380/:1398/:1427（均在 planWaiting 及其出口路径）；`_planAgent` 后端零产生（前端内部别名）。**全部仅在 plan 链内** ✓。plan 专属 logAgentEvent 标签（plan-start/plan-feedback/plan-approved/plan-cancelled/plan-failed/plan-wait-buffer-user/plan-wait-drain/freeze-queue-plan）为纯生命周期日志（AgentCore.scala:55-67 → logger），不达前端。

---

## 三、可达性裁决（本任务核心）

① **enterPlanMode 触达面穷尽确认：零真实触发路径。**
- web/ 全域（含 index.html 内联、css、locales、vendor）grep `enterPlanMode`：唯一命中 input.js:224 定义处。
- src/main/scala 全域：零命中（后端无间接触发）。
- tests/（含 tests/*.spec.mjs）：`plan-mode`/`enterPlanMode` 零命中。
- 无快捷键注册、无动态 import、无字符串形式调用。

② **planMode 标志唯一置位点** = input.js:226（enterPlanMode 体内）。其余出现全是读取（:225/:233/:251/:553）或防御性取消（:336 enterCompactMode）；`state.planAgentId`（另一标志）仅 planMode.js:38（onPlanStart，由 planStart 事件驱动）置位——而 planStart 永不产生（上游死）。

③ **裁决：运行时不可达（UI 零入口，发送端/接收端为互引死代码）——且为「双重死」**：即使经 API 直发 WS `command:plan`，后端 ：1084 `get("Explorer")` 因 F.3 归档必得 None，落 ：1105-1118 error 应答。前端动作环（planApprove 等）同理永不触发。

对照系：/ask（input.js:59-64 slash 表内）与技能命令（:93）有接线但 **slash 系统整体被封存**（input.js:53-57，author ruling 2026-08-29 22:56，'/' 视为纯文本，代码保留待重启用）；plan 的 slash 条目则在更早的 28044553（08-11）被物理删除——plan 是「被单挑移除入口」的唯一模式。

---

## 四、耦合面与删除面预估（选项二执行清单）

### 后端（删除则需）
| 目标 | 位置 | 零孤儿引用？ |
|---|---|---|
| WS case "plan" | WebSocketRoutes.scala:1217-1224 | ✓ |
| WS planApprove/planFeedback/planCancel | WebSocketRoutes.scala:997-1016 | ✓ |
| :492 注释（Interrupt, PlanApproved） | WebSocketRoutes.scala:492 | 改词即可 |
| StartPlan 命令 | protocol.scala:153 | ✓ |
| PlanTurnComplete/PlanFailed/PlanApproved/PlanFeedback/PlanCancelled | protocol.scala:156-168 | ✓（PlanTurnComplete 生产/消费仅 PlanAgent:125 + AgentActor:1281） |
| PlanModeState case class | protocol.scala:1035-1046 | ✓ |
| planMode 字段 + withPlanMode | protocol.scala:1065, :1302 | ✓ |
| freezeExempt 文档句提 plan | protocol.scala:872-875 | 改词（**字段保留**：ask 轮共用 AgentActor:3471-3473） |
| StartPlan 入口分支 | AgentActor.scala:1080-1119 | ✓ |
| planWaiting 状态机（含 V7 缓冲） | AgentActor.scala:1268-1492 | ✓ |
| exitPlanWindow + 文档 | AgentActor.scala:1494-1539 | ✓（drainQueuesAfterCompaction 本体**保留**：compaction :2284 共用） |
| frozen 态 StartPlan 排队 | AgentActor.scala:4047-4055 | ✓ |
| 注释行 :26 / :349 / :543 / :1083 / :2342 / :4048-4050 | AgentActor.scala | 改词 |
| PlanAgent.scala 全文件 | 160 行 | ✓ |

### 前端
| 目标 | 位置 | 零孤儿引用？ |
|---|---|---|
| planMode.js | 全文件 173 行 | ✓ |
| css/planMode.css | 全文件 271 行 | ✓ |
| input.js plan 段 | :223-237（enter/cancelPlanMode）、:251-260+:262+:272（indicator 分支）、:336、:553-565（含 :562 硬编码 "Plan mode started — analyzing..."）、:1588-1595 | ✓ |
| state.js | :239-242 planAgentId/planSessionId | ✓ |
| ws.js 拦截块 | :565-578 | ✓ |
| main.js | :80 import、:3194 init、:3202-3205 四挂载 | ✓ |
| index.html | :45 css link、:270 plan-indicator | ✓ |
| locales | zh-CN.js:1007 / en.js:1004 `input.planPlaceholder`（唯一 plan 键） | ✓ |

### spec 影响（R 时逐条处置）
- **PlanWaitingBufferSpec.scala（209 行）——整体删除**。plan 专属（V7 planWaiting 缓冲语义 + F1 排空验证，:107-113 自建 tmp Explorer fixture，:158 StartPlan、:159 planReady、:204 planEnd 断言）。
- AgentActorCompactionSpec.scala:289 —— 仅注释提及 StartPlan（freeze/compact 排队同族说明），**无断言受影响**，改词即可。
- 其余 Explorer 字样 spec（AgentControlToolSpec:120/144、PromptSectionsSpec:87/171、ContextRefresherFlowInjectSpec、Phase2dSkillCatalogSpec:195、SeedDefaultsConvergeSpec:24、ConfigServiceSpec:95-148、SessionStoreUnindexedSpec:111）——均自建 tmp fixture 引用 "Explorer" 名字，**与 plan mode 无关，删除不受影响**。
- 共享基建（pendingUserInputs/pendingEvents/pendingImmediateInputs、drainQueuesAfterCompaction、freezeExempt、emitSessionBusy、emitInjectedBubbles）均有 compaction/ask 等其他使用方——**保留**。

**结论：删除面约 800-900 行，逐项「删除后零孤儿引用」成立**（②条件满足）。但见 §五，③不成立。

---

## 五、Explorer 断链逐点复核（QC 指认 100% 坐实）

1. **装载判据**：AgentLibrary.scala:205-211 `scanDisk()` 列目录→:159-161 `loadFromDir`：`if !os.exists(dir/"agent.json") then None`——`agent.json` 存在为唯一判据。
2. **归档现状**：`~/.nebflow/agents/Explorer/` 仅含 `agent.json.archived`（8-28 内容：tools=WebSearch/WebFetch/Pop/AskUserQuestion/CheckIssues/Curl——确系 read-only 面）+ memory.md + system.md；目录 mtime 09-04 23:32（F.3 归档重命名时刻）。LIVE agent 仅 general / Nebula / project-dispatcher。
3. **防复活闭环**：82af52e5（09-05 00:48）Seeds.all 收敛为 `List(Nebula)`，AgentLibrary.scala:380-383 注释明确归档目录不得被 seedDefaults 复活。**该 commit message 的「零引用」声称范围仅限 AgentLibrary.scala 种子代码（原 :377-453）——AgentActor.scala:1084 正是漏网点**，QC 指认准确。
4. **None 行为**：AgentActor.scala:1105-1118 → wsSend `{"type":"error","message":"Explorer agent not found — cannot start plan mode"}` → 主 agent 回 idle。planWaiting 不进入、planStart/planReady/planEnd 均不产生。

---

## 六、分支判定依据（K vs R）

R 需 ①②③ 同时满足；①② 已满足（§二/§三/§四），**③ 明确不成立，反证四条**：

1. **28044553（2026-08-11，作者 MashiroKai）commit message 原文**："Backend WS handlers (clear/compact/plan/fork) are **kept for API capability** but no longer exposed in UI. enterCompactMode/**enterPlanMode functions retained for other UI entries**." —— 删 UI 入口时刻意声明保留机制，是「封存」不是「废弃」。
2. **V7（2026-09-03）刚投入打磨**：planWaiting 吞消息丢失向量修复（AgentActor:1455-1483 大段 V7 注释）+ PlanWaitingBufferSpec 专项 spec —— 废弃中的功能不会三天前还在修语义补测试。
3. **a686798a 先例**：Planner agent 被移除时，作者选择把 plan mode **重接**到 Explorer（"refactor: plan mode uses Explorer instead of removed Planner agent"）而非退役 plan mode —— agent 消亡≠plan mode 消亡，本次 Explorer 归档属同类情形。
4. **作者封存惯例**：slash 系统（08-29 ruling）「SEALED / 封存待启用，代码保留」；dispatcher 先例（Team/Flow 入口 flag 封存非删除）同型。

与 82af52e5 的关系：该 commit 裁定的是 **Explorer agent 退役**（F.3 归档），从未裁定 **plan mode 退役**——断链是 agent 收敛的**连带损伤**，非计划内废弃。

**→ 分支 K：等作者裁定，本批零改码，worktree 保留现场。**

---

## 七、两个处置选项的精确落地清单（供作者裁定）

### 选项一「接活」（成本极低，分两层）

**A 层：agent 解析修复（三案任选一）**
| 方案 | 改动 | 工作量 | 风险 |
|---|---|---|---|
| A1 磁盘恢复 Explorer | `~/.nebflow/agents/Explorer/agent.json.archived` → 新写/恢复 `agent.json`（read-only tools） | 零代码，1 个磁盘文件 | 与 F.3 归档裁定张力（=部分推翻归档）；Seeds.all 不含 Explorer → 不会被 seed 覆盖/再归档（AgentLibrary:380-383），手动恢复可长期存活 |
| A2 改查 general | AgentActor.scala:1084 `"Explorer"` → `"general"` | 1 行 | **general 全工具面（Write/Edit/Bash）破坏 read-only 安全不变量**（:1083 注释 + PlanningPrompt「you have no write tools」与实际工具面矛盾，提示词非强制）——不推荐 |
| A3 新建专职 Planner | 新建 `~/.nebflow/agents/Planner/agent.json`（Read/Grep/Glob/WebSearch/WebFetch）+ :1084 改 `"Planner"` | 1 行代码 + 1 磁盘定义 | 最符合语义、不触碰归档裁定；PlanAgent.scala:13-14 注释本就指 Planner（历史名，a686798a 前旧主） |

**B 层：UI 入口补点（二选一，可只做其一进入「API-only」形态）**
- B1 slash 表加条目（input.js:58-72 模式）：`'/plan': { desc: () => t('slash.plan'), run: () => enterPlanMode() }` ≈5 行 + locales 键。注意 slash 系统当前封存（input.js:53-57）——与 /ask 同待遇，封存解除时一起复活。
- B2 输入框模式按钮（ask/skill/compact indicator 同款互斥切换）≈10-20 行。
- **发送端/接收端/动作环零改动**（已全套接线，见 §二）；planMode.js 卡片/反馈/批准/取消即插即用。

**接活验证路径**：PlanWaitingBufferSpec 不依赖 runtime agents 目录（自建 tmp fixture）→ 保持绿；运行时冒烟 = 输入 plan 命令 → 应见 planStart 事件 + 卡片出现，而非 "Explorer agent not found" error。

### 选项二「整体退役」
即 §四 删除面清单逐项执行（约 800-900 行），外加：
- `git checkout -b plan-mode-retire`（worktree 已备）；sbt compile + 受影响 spec 子集 = 删除 PlanWaitingBufferSpec 后全量 `nebflow.agent.*` 回归（82af52e5 批次实测 351/351 绿的基线上减一文件）。
- 提交（沙箱 EPERM 先例）：worktree 内 `git add` 具体文件 + commit 预期 EPERM（gitdir 在主仓 `.git` 下）→ 保持 commit-ready，宿主侧执行（命令见 §九）。
- 若退役，建议整批审视同族「API capability 保留」：/clear /compact /fork 的 WS case（WebSocketRoutes.scala:1190-1224、:1225-1256）同属 28044553 删 UI 留 API 家族——但**须作者明示**，严禁夹带。

---

## 八、遗留问题

1. **Rust 版同源死代码**：9a74471b（Rust-standalone 分支）把 web 前端 bundle 进 nebflow-rs/web，plan 死链大概率随包携带——归 nebflow-rust 团队审计，超出本任务范围。
2. `_planAgent` 拦截（ws.js:570-578）依赖 agentTextDelta 携带 `agentId` 字段——后端事件结构若重构会静默失效；接活时建议冒烟验证卡片文本累积。
3. input.js:562 "Plan mode started — analyzing..." 硬编码英文（唯一未双语化的 plan 文案）。
4. PlanAgent.scala:13-14 类注释仍指 "Planner agent definition"（a686798a 后过时）；:44-46 @param 同。
5. 审计期间 main 仍在推进（在飞收口批）——落地前须宿主侧 `git merge main`（worktree gitdir EPERM，见 §九）。
6. EnterPlanMode 若经 B1 接活，受 slash 封存牵制——作者解除封存（`nebflow_slash.enabled=1`）前仍不可达，属「双层封存」需一并知悉。

---

## 九、宿主侧落地命令（本批 K：仅归档报告，零代码落地）

```bash
mkdir -p ~/.nebflow/docs/Nebflow
cp /tmp/nb-planmode-audit/20260905_planmode-deadchain-audit.md ~/.nebflow/docs/Nebflow/20260905_planmode-deadchain-audit.md
cd ~/.nebflow && git add docs/Nebflow/20260905_planmode-deadchain-audit.md && git commit -m "docs: plan mode 残链全链审计报告（判定 K 保持，等作者裁定接活/退役）"

# （仅当作者裁定接活/退役后另行安排；worktree 基线前移需宿主执行——沙箱对 worktree gitdir EPERM）
cd "/Users/dev/Claude code/Nebflow/.nebflow/worktrees/plan-mode-retire" && git merge main --no-edit
```

---

*审计执行：general（worktree plan-mode-retire 节点）· 2026-09-05 01:0x · 零改码 · 主仓/worktree 运行时文件零写入*
