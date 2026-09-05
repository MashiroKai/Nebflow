# 旧体系→Project+Node 新架构迁移漏洞系统审计（migration-audit）

- 日期：2026-09-05 ｜ 审计基线：main@533a2a0a（worktree taskpanel-audit 实测）
- 触发：作者 2026-09-05 08:40「任务面板 0 任务/暂无任务而 Flow Map 有活跃节点」报告 + 同日裁定「节点按任务列表设计语言入面板」
- 性质：**只读盘点**（机制层/scala 零改动）。分级：【立即修复·本批已修】【待作者裁定】【既有裁定·非新发现】【在飞批处置中】
- 在飞批 awareness：n-625b7217 nebula-toolface（AgentCore/registry/CardTool/RestApiRoutes+spec）、n-12e2b5f9 skill2plugins（~/.nebflow skills/plugins staging + 主仓 .gitignore 同规格修改 + 本目录 skills-to-plugins.md）。两批与本文零文件冲突。

## 0. 结论速览

| 级别 | 数量 | 要点 |
|---|---|---|
| 立即修复·本批已修 | 1 | 任务面板整合缺口（统计不计节点/空态噪音/held·blocked 无徽章）——纯前端，已修+27/27 隔离取证 |
| 待作者裁定 | 14 | 见 §2 分级清单（每项选项+建议+工作量） |
| 既有裁定·非新发现 | 3 | sidebar 封存 / plan 退役待落地 / clear·compact·fork 删UI留API 家族 |
| 排除域 | 2 | 好友域（contacts/messages/neblink=新体系）、canvas 文件查看器 |

**关键证伪**：分发器「整合没做完」假设不成立——节点入面板整合已于 09-02 完整落库（99d8b1c8 + aca03858），feat/task-progress-2 分支 0 commits ahead（tip ee7b95fa 即 main 祖先，「第七件 phase 2」契约早已合并）。作者所见症状的真实根因是三处收尾缺口：①头部统计只数旧任务；②无旧任务时仍渲染「任务/暂无任务」误导空态；③held（09-03 hold 闸门新增状态）与 blocked 未映射徽章、降级为中性点。

## 1. 立即修复（本批已修，纯前端）

### 1.1 任务面板节点整合收尾 ✅ 已修
- 症状：面板「0 任务/暂无任务」而 Flow Map 有活跃节点。
- 根因（file:line）：
  - `src/main/resources/web/js/taskList.js` redraw：`stats.textContent = t('task.statsProgress', { progress: progress.length })` ——统计只数旧任务，节点不计；
  - 同文件：`progress.length === 0` 时 `buildEmpty(t('task.progressEmpty'))`（「暂无任务」）无条件渲染——节点区块存在时形成「0 任务+暂无任务」噪音；
  - 同文件 `NODE_WORD_KEY`：仅 wiring/pending/running/completed/failed/cancelled 六态，`buildNodeGlyph` 对 held/blocked 走中性降级（`.task-node-dot-neutral`、无状态词）——held 是 20260903 hold 闸门（`NodeTools.scala:150,267-270`）落地的新状态，晚于 09-02 面板整合。
- 修复（同文件四处 + css + i18n）：
  - NODE_WORD_KEY 按 09-05 裁定全映射：wiring/pending→待处理、running→进行中（复用 `task.inProgressShort`）、held→待放行、blocked→阻塞、completed→已完成（复用 `flowmap.done`）、failed→失败（`flowmap.fail`）、cancelled→已取消（`flows.status.cancelled`）；
  - buildNodeGlyph：held→`.task-node-box-held`（琥珀虚线方框）、blocked→`.task-node-dot-blocked`（琥珀实心点），色相复用 `--amber`（与 `flowmap.css:206 .fm-node.blocked` 同源 token，禁造新轮子）；
  - 统计=旧任务+节点合计；progress 区块仅在有旧任务时渲染（节点=任务主列表）；
  - i18n 新增三键 zh/en 成对：`task.nodePending/nodeHeld/nodeBlocked`（parity 1001=1001）。
- **取舍申明**（裁定「旧 taskListUpdate 路径不动；空态文案保留兜底」）：旧任务数据路径与渲染全保留（有旧任务时与节点区块并存，T7 断言）；`buildEmpty`+`task.progressEmpty` 键保留不删（member 会话无节点面板与 team 区块退役清理文档 §1.1 后续可用），当前两空时面板整体隐藏（既有行为，比常驻空卡干净）。
- 验证：tests/tasklist-nodes.spec.mjs **7/7 绿**（T1 七状态+统计+空态、T6 未知降级改 'draft'、T7 新增并存）；隔离实例取证 **27/27**（beta.54 assembly jar+前端补丁，NEBFLOW_HOME=/tmp 隔离 home，端口 8301，fixture 项目 8 节点全状态经 REST 挂载，Playwright 断言徽章/状态词/统计/时间+点击跳转 Flow Map fm-node-flash，截图 evidence-panel.png/evidence-flowmap-click.png）。宿主生效需 esbuild 重建+宿主重启（本批不做）。

## 2. 待作者裁定清单（每项：证据 → 选项 → 建议 → 工作量）

> 总原则：机制层/scala 侧一律不动；team 体系**在役**（~/.nebflow/teams/ 9 team 仍由 Nebula 日常 Mail 编排使用），凡 team 域残留定性为「跟随 20260902 phase2 设计 §F.7 team/flow 退役时序整批处理」，不单点零敲。

### F1 ｜ tests/tmp-verify-todo.mjs —— 唯一建议本轮顺手删的杂项
- 证据：`tests/tmp-verify-todo.mjs:1-4`——「re-verify A3/A4/A6/A13 after qa-frontend 打回修复」，验证对象是 **#15 已退役的 todo 面板**（2026-08-30 裁定「不再有待办」）；且 `import { chromium } from 'playwright'`（非 @playwright/test，现依赖下已不可跑）。
- 选项：a) 删除；b) 移入 tests/fixtures 留档；c) 保留。
- 建议：**a**（功能已死+脚本已跑不动，零保留价值）。同类 `tmp-*` 前缀脚本共 11 个（tmp-canvas-html-repro/tmp-canvas-zoom-verify/tmp-msg-jump-verify/tmp-msg-search-shot/tmp-preset-hotload-verify/tmp-project-nav-verify/tmp-real-verify/tmp-recursion-depth/tmp-recursion-proof/tmp-regress-normal-html/tmp-usage-display-verify/tmp-usage-e2e/tmp-verify-friends）——建议另开「tests 卫生批」统一盘点，本批不扩大面。
- 工作量：零（一行 git rm）。

### F2 ｜ 分支卫生（只读 git branch -v + rev-list 盘点）
- 已全并入 main（0 ahead，删分支零损失）：`feat/task-progress-2`（ee7b95fa 已在 main）、`feat/node-agentfile`（0d7b1af6）、`red-baseline`（5d1909eb）。
- 未并入（git cherry `+` 非等价，真有内容不在 main）：`pr-41-search-jump`（e843c998 fix(web): search jump fails for tool results under non-English locale）、`pr-44-send-btn`（2f95bea6 fix(web): make send button read as actionable）。
- 选项（pr-41/pr-44）：a) 审查后合并；b) 内容已过时→删分支；c) 搁置。
- 建议：pr-41/pr-44 各 1 commit 小改动，安排一次轻量 review 后二选一；三个 0-ahead 分支直接 `git branch -d`。
- worktree 残留（`git worktree list` 共 16 个）：已合并分支的 worktree 可清（proc-residue-governance/qc-2d-refactor/plugin-protocol/plugin-panel-redesign/seed-defaults-converge 等 + /tmp/nb-node-agentfile）；在飞四支（nebula-toolface/skill2plugins/taskpanel-audit/plan-mode-retire 及窗口壳等）保留。
- 工作量：小（半小时批量审查+清理批）。

### F3 ｜ flows/presentation-prep.archived 目录
- 证据：`~/.nebflow/flows/` 下 `presentation-prep.archived/`（内含 agents/ + flow.json）——已归档 flow 的目录体残留，与规范命名不符（flows/ 无 .archived 惯例位）。
- 选项：a) 移入 ~/.nebflow/.archived-flows/（对齐 .archived-tools-2d 惯例）；b) 直接删除；c) 原位保留。
- 建议：**a**（presentation-prep 作为 flow 实体已死但 html-deck-studio team 仍在役，留冷档防回溯）。工作量：微小。

### F4 ｜ nebflow-project team 空壳
- 证据：`~/.nebflow/teams/nebflow-project/agents/` = 0 agent（域 agent 已全部迁 Project 架构），team 目录本体仍在（Manager 定义）。团队列表仍注入 Nebula 系统提示词（Teams & Flows 段无 nebflow-project——已摘）。
- 选项：a) 删除 teams/nebflow-project/ 目录；b) 保留至 phase 3 整批。
- 建议：**a**（零成员空壳，无运行时引用）。工作量：微小。若删，需同步核对 Nebula 侧 Teams 列表注入源（本轮见列表已无此项，删除无注入面影响）。

### B1+A3+E2 ｜ /api/agents 三层聚合 × agentManager 面板收敛
- 证据：`RestApiRoutes.scala:495-556` GET /api/agents = global + team（扫 `teams/<n>/agents/`）+ flow（扫 `flows/<n>/agents/`）三层；`agentManager.js:211-214` 按 layer team/flow 分组渲染、`:37-38` 按 category 拼 team/flow 工具清单（Mail/SubTask/FlowExecute/FlowReport）。
- 归属判断（任务指派核实项）：RestApiRoutes 在 **nebula-toolface 批文件清单内** → 若该批已涵盖面板收敛，本项随批关闭；若无，则独立裁定。
- 选项：a) 收敛 global 单层（team/flow 层随退役时序删）；b) 三层保留至 phase 3；c) 仅删 flow 层（flows 已不可新建实体）。
- 建议：**先看 nebula-toolface 落库内容再定**；未涵盖则选 c 过渡（flow 层纯死数据：flows/agents/ 目录多为空）+ b 保留 team 层（在役）。工作量：中。
- 后续失效面：列表收敛后 agentManager 的 team/flow 分组与详情/模型编辑入口自然失效——同批清理。

### B2 ｜ REST /api/teams/mounted + /api/teams/status
- 证据：`RestApiRoutes.scala:1484`（NOTE 注释自证 /api/teams/mounted）、`:1571` /api/teams/status/:sessionId；消费方 `flowCanvas.js:557`、`main.js:2217`。
- 定性：team 运行时 API，flowCanvas/flowTeams 面板的数据源——team 在役期间合法存活。
- 选项：a) 随 phase 3 team 退役整批删（含前端 flowCanvas/flowTeams/flowList/flowDag/flowAgentPopup 可视化域）；b) 提前摘前端入口留 API。
- 建议：**a**（与 A1 sidebar 封存同批，一次干净）。工作量：大（整批，属 phase 3 主菜）。

### B5 ｜ WS team 域事件
- 证据：`WebSocketRoutes.scala:1688` teamList 推送（前端 `main.js:2871` onMessage→state.teams/flows→flowCanvas.refresh）；`TaskToolHelper.scala:33` emitTeamTaskListUpdate（team 域任务帧，前端 teamTaskListUpdate handler→state.teamTasks→面板合并区）。
- 定性：同 B2，team 在役存活；team 域任务面板合并区已有退役清理文档（`~/.nebflow/docs/Nebflow/20260902_tasklist-team-retirement-cleanup.md` 全清单，phase 3 执行）。
- 选项/建议：随 phase 3；无提前动作。工作量：并入 phase 3。

### C1 ｜ 旧编排工具注册面（registry 全套在位）
- 证据：`registry.scala:37-39` TeamTaskCreate/Update/List、`:43` Mail、`:45` Delegate、`:50` FlowTrigger、`:53` FlowExecute、`:55` SubTask、`:64` Load、`:66` FlowReport、`:76` Task；授能面 `AgentCore.scala:1640-1655`（isSubTaskWorker/isFlowNode/category=="flow" 三级剥离链）、`:2150`（category "team" → BaseTools+Mail+SubTask+FlowExecute+TeamTaskTools）；AgentActor.scala:69-70,2006,2038,2644 多处 Mail/SubTask/Delegate 特判。
- 定性：**在役工具**（9 team 的 Manager/成员日常使用）——非死代码，退役=phase 3 时序，注册面本身无需动作。
- 【在飞批处置中】Nebula 面的 −Mail/Delegate/FlowTrigger/FlowExecute 由 nebula-toolface 批处置（AgentCore/registry 在其文件清单），不重复列。
- 选项（phase 3 时）：a) 整批删注册+授能链+AgentActor 特判；b) 保留 registry 逐工具摘授能。
- 建议：a（授能链三处过滤与 AgentActor 特判是同一张网，零敲必漏）。工作量：大（phase 3 主菜）。

### C3 ｜ TaskTool（session 任务）+ TaskToolHelper.emitTaskListUpdate
- 证据：`TaskTool.scala` 在位、`TaskToolHelper.scala:14` emitTaskListUpdate（调用方仅 getTaskList 拉取/clear 清空/completeTask 回写三处 WS 路径，WebSocketRoutes.scala:1190,1202,1887,1909）。
- 定性：旧任务体系数据路径。本批裁定「不动不破坏」✓ 已遵守；team 区块退役清理文档（§1-3）已含其前端半边清单。
- 选项：a) phase 3 连后端 TaskStore 一起删；b) 保留 API 仅供未来会话级轻量任务。
- 建议：**b 备选观察**——TaskStore 机制本身与 team 无耦合（session 域），若未来 AgentControl/Node 体系需要会话内任务面可复用；倾向 a 但不急。工作量：中。

### B3 ｜ plan 家族【既有裁定·非新发现】
- 证据：`WebSocketRoutes.scala:997-1011` planApprove/planFeedback/planCancel、`:1217` plan、`AgentActor.scala:1080,4047` StartPlan、前端 `input.js:225-251,553-560` planMode 全链、`planMode.js`、`main.js:80` import。
- 状态：已裁定退役；plan-mode-retire 分支已建（当前 533a2a0a，0 commits，工作未落库）。**当前 main 仍含 plan mode 全链**——落地前任何 plan 相关回归仍需护航。
- 动作：无（待该批落地；落地时建议连带 grep 前端 planMode 残留与 i18n 键）。

### B4 ｜ clear/compact/fork【既有裁定·非新发现】
- 证据：`WebSocketRoutes.scala:1190` clear（附 taskStore.deleteAll+taskListUpdate 空帧）、`:1208` compact、`:1225` fork（会话复制广播 sessionList）。
- 状态：28044553「删 UI 留 API」家族，待整批裁定。补充信息供裁定：fork 是纯会话操作（与 team/flow 无关），clear/compact 是会话生命周期操作——三者并非 team/flow 语义残留，建议裁定「API 保留、与迁移解耦」以免误伤。工作量：零（不动）。

### F5 ｜ prompts 层残留（~/.nebflow/prompts/）
- 证据：`system-prefix-for-teams.md`（team/Mail 35 处）、`manager-prefix.md`（25 处）、`system-prefix-for-flows.md`（1 处）、`sections/visual-reporting.md.disabled-2d`、`sections/read-live.md.disabled-2d`（.disabled-2d 后缀=阶段 2d 已停用留档）。
- 定性：teams 前缀在役（team 会话注入源）；flows 前缀使用面随 flows 退役萎缩。disabled 文件=合规留档。
- 选项：a) 全随 phase 3；b) .disabled-2d 两个文件删除（已停用且 git 有历史）。
- 建议：b 可随手（微），a 随时序。工作量：微/零。

### A1 ｜ sidebar Team/Flow 入口【既有裁定·非新发现】
- 证据：`activityBar.js:63` `SIDEBAR_LEGACY_ENTRIES = false`（:73 条件摘除入口）。
- 状态：已封存，将来整批处理。无新动作，列出备查。

## 3. 定义层目录盘点（~/.nebflow/，只读实测）

| 目录 | 现状 | 定性 |
|---|---|---|
| teams/ | 9 team；域 agent 计 42：czt-project 4、html-deck-studio 14、**nebflow-project 0**、nebflow-rust 6、nebflow-website 3、ReminderIsland 3、sipm-paper 4、slideblocks 4、voice-recognition-test 4 | 在役体系（分发器口径「9 team×51」修正为 42 实测）；空壳见 F4 |
| flows/ | code-review、entity-creator、git-merge、memory-consolidation、nebflow-review-merge、release-stable、research、weekly-summary 在用 + presentation-prep.archived（见 F3）+ GUIDE.md | 在用；自举与周报流活跃 |
| agents/ | 14 目录：Backend、Coder、design-engineer、Explorer、Frontend、general、html-builder、Mail、Manager、Nebula、project-dispatcher、qa-frontend、sipm-writer、visual-reviewer | 【在飞批处置中】skill2plugins 批正盘点 agents 域——引用其口径，本文不重复展开；实测数 14（分发器口径「8 惰性残留」偏少，其中 general/Nebula/project-dispatcher 等为 Nebula 注入在用） |
| tools/ | 仅 tools.md | 合规 ✓ |
| .archived-tools-2d/ | check-issues、issue、screenshot | 归档合规 ✓（对应 scala 类已删净：core/tools/ 无 issue 文件、registry 无注册） |

## 4. 排除域（新体系，非遗留）
- 好友域：contacts.js / messages.js / neblink.js / friendsApi.js——NL 好友链新体系，不属本审计。
- canvas 文件查看器（fileViewers/canvas-html 系列）——新体系。

## 5. 审计方法与证据边界
- 全部结论基于 main@533a2a0a worktree 只读 grep/sed/git + ~/.nebflow 只读 ls/grep；未执行任何机制层写入。
- 两个在飞批（nebula-toolface/skill2plugins）分支当前均在 533a2a0a（0 commits），其「处置中」标注以任务指派文本为据，落地内容未見——B1/C1 归属判断须在其落库后复核。
- 本文件与 skill2plugins 批的 .nebflow/Spec/skills-to-plugins.md 同目录不同文件，零冲突；.gitignore 的 `.nebflow/*` + `!.nebflow/Spec/` 例外按与兄弟批完全同规格落库（check-ignore 双向实测通过）。
