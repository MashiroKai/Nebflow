# core / tools

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

工具注册表与内置工具（最大子模块，83 个文件）。

**入口建议**：先读 [`NodeEditTool.scala`](NodeEditTool.scala)（本模块最大文件）。

## 文件（83）

| 文件 | 说明 |
|---|---|
| [`AgentControlTool.scala`](AgentControlTool.scala) | AgentControl — Nebula 专用后台 agent 管控（spec v1.1，2026-08-19）。 起因：派出的 sub-agent 6 小时无产出只能干等——Nebula 缺主动「查看/取消/重启」 |
| [`AgentFlowTool.scala`](AgentFlowTool.scala) | AgentFlow —— 内联 DAG 便捷面（N1 复名＋非阻塞薄壳化；v3 §5 S4 荐案候过目， 复名连带细案沿 v2 §3 原样引用＝案 A「仅 legacy 形态拒收」）。 |
| [`AskTools.scala`](AskTools.scala) | §7 频控状态：每 ask 会话最近一次成功转交时刻（内存态，重启即清——频控是 防刷屏软约束，不作持久语义）。 |
| [`AskUserQuestionTool.scala`](AskUserQuestionTool.scala) | 附件判据词汇（解析/探针/拒绝枚举）——**Pop 文件面同一套**（askuser-attach-align 批：禁第二套域规则/第二权威，作者 2026-10-07 09:41 令）。 |
| [`BashTool.scala`](BashTool.scala) | Bash output can be very large — persist early. |
| [`BgNotifyDelivery.scala`](BgNotifyDelivery.scala) | D1 后台任务通知投递可靠性（steward 批 2026-10-23；**作者补充裁定 2026-10-23 09:46**「目标会话死了就激活发送，不用发给root」——改道投 root 的方案废除）： |
| [`BgNotifyPayload.scala`](BgNotifyPayload.scala) | 后台任务终态通知负载的**纯组装面**（作者报障 2026-10-24「后台任务通知很不详细 甚至有截断——这里是不是存在问题」修复批）。 |
| [`BgTaskCancel.scala`](BgTaskCancel.scala) | BgTaskCancel —— 单任务精确取消的统一面（后台任务生命周 P2 · 设计稿 §4.1 「单任务取消是精确手术」）：N 条在册任务杀指定 1 条，其余存活且通知到位。 |
| [`BgTaskLedger.scala`](BgTaskLedger.scala) | 后台任务完成事件持久账本（修复批 2026-10-24，作者病历逐字「任务账目丢了， wake 路径也没了，永久空等」）。 |
| [`BgTaskLiveness.scala`](BgTaskLiveness.scala) | 后台任务活性判定（后台任务生命周 P2 · death-watch 批 · 设计稿 20261030 §4.2 + 2026-10-31 误报修复批（作者令「修」），根因 docs/20261031_owner-lost |
| [`BgTaskOutputDisk.scala`](BgTaskOutputDisk.scala) | 本地后台任务输出落盘层（作者令 2026-10-25 修复批需求④「若 job 框架本地侧未持久化 输出文件，补上——本地任务输出落盘 + 记录路径」）。 |
| [`BgTaskOutputStore.scala`](BgTaskOutputStore.scala) | 后台任务输出查看批（2026-09-09 作者需求「点进后台任务能看具体输出」）： 每个后台 Bash 任务的 stdout/stderr 逐行累积缓冲 + 终态留存区。 |
| [`BgTaskReaper.scala`](BgTaskReaper.scala) | BgTaskReaper —— death-watch 收割器（后台任务生命周 P2 · 设计稿 20261030 §4.2 「属主失联 ⇒ 代行终态记账 + 沿 bg-wait 链注入终态通知」+ 10-30 新病灶 |
| [`BgTaskRegistry.scala`](BgTaskRegistry.scala) | Global registry of active background tasks (local Bash + remote). Used by the WS reconnect handler to sync fro |
| [`BlockingSubagent.scala`](BlockingSubagent.scala) | BlockingSubagent —— 阻塞式只读侦察子会话的单一 spawn 点。 Explore 同型（外部 CLI agent 的 explore 型子代理）：spawn 一个 `subagent` |
| [`BrowseGetCursor.scala`](BrowseGetCursor.scala) | BrowseGetCursor —— 浏览取件**分块拉取腿**的收端游标（M2 · 设计 v1 §7 M2-1）。 作者令「Windows 下载进度条」的正主腿：改前浏览取件 = `RemoteExecutor.tra |
| [`ChainWriteTool.scala`](ChainWriteTool.scala) | ChainWrite —— **整链一次登记**（N1 刀2 · 设计 v3 §5/S4；⑴′ 代裁＝独立件，不塞 既有工具件）。 |
| [`CurlTool.scala`](CurlTool.scala) | 定义: CurlTool |
| [`DelegateReportTool.scala`](DelegateReportTool.scala) | delegate_report —— delegate 终态语义申报工具（S7/S8；worktree-judge-fifo 批 #1224 增补 §3.4）. |
| [`DelegateTool.scala`](DelegateTool.scala) | DelegateTool —— Nebula 唯一的派发入口（unified-delegate 批 2026-10-03）。 大改后的形态（作者令）：**只填 `task` 与 `project`（选填）**—— |
| [`DeviceProfile.scala`](DeviceProfile.scala) | 单个实测字段的值 + 探测时刻。`source` 恒为 "auto"（结构即声明，不另设键）。 |
| [`DiffUtil.scala`](DiffUtil.scala) | Shared utilities for EditTool and WriteTool. |
| [`EditResult.scala`](EditResult.scala) | A single hunk in unified diff format. |
| [`EditTool.scala`](EditTool.scala) | Structured failure of a single old→new edit applied to a buffer. Shared by EditTool and MultiEditTool — each m |
| [`ExternalToolConfig.scala`](ExternalToolConfig.scala) | External tool definition loaded from a JSON config file. `layer` and `scope` describe which layer the tool was |
| [`FileLockManager.scala`](FileLockManager.scala) | Per-file write lock shared by all agents. Each file path maps to a binary semaphore (permit count = 1). |
| [`FileOpenCache.scala`](FileOpenCache.scala) | 索引条目（持久层账面；`last` 变量字段供 LRU touch 原地更新）。 |
| [`FileRefs.scala`](FileRefs.scala) | Shared policy for local file references inside HTML that a tool hands to a browser context (Card → chat iframe |
| [`FleetRoster.scala`](FleetRoster.scala) | Fleet roster —— `# Devices` 段的花名册渲染器（台账 #913 ①，2026-10-26）。 纯函数件：输入 = 本机身份（deviceId/名称/platform/能力面/version）+  |
| [`FlowReportStore.scala`](FlowReportStore.scala) | Stored flow-node report payload: verdict + output + structured slots. FlowReportTool 随 flow 三工具退役删除，本数据 |
| [`FriendMessageTool.scala`](FriendMessageTool.scala) | SendMessage tool — A2A 一期 agent 发消息能力（friends-messaging-arch §7.1 冻结设计 + 方向补充 spec §2），附件腿批扩为**统一目标模型 |
| [`GenerateImageTool.scala`](GenerateImageTool.scala) | 内置生图工具（imagegen 双层批 2026-10-24；功能开放批 2026-10-30 正式产品面； 交互修正批 2026-10-30 作者令：非阻塞 job → **阻塞语义**——照 Codex 玩法定案）。 |
| [`GlobTool.scala`](GlobTool.scala) | /*.ts" - `*` matches a single path level only (e.g. "*.js" = files directly in path, NOT recursive); use "**"  |
| [`GrepTool.scala`](GrepTool.scala) | Grep results are typically large — lower threshold to persist early. |
| [`ImageInject.scala`](ImageInject.scala) | Shared image-preparation pipeline for LLM injection. Two consumers: |
| [`ListFriendsTool.scala`](ListFriendsTool.scala) | ListFriends — Nebula 编排面**只读**好友名册（好友消息改造批 ⑩；方案 `20260912_011320_好友消息改造批方案` §4.5 定稿 + 判词 |
| [`LoadTool.scala`](LoadTool.scala) | Agent-name set a team's references may resolve against: global agents ∪ the team's own local agents (teams/<na |
| [`MailTool.scala`](MailTool.scala) | Agent-to-agent communication tool. Delivery — **every Mail is immediate**: one mode, and no parameter to choos |
| [`MemoryChangeNotifier.scala`](MemoryChangeNotifier.scala) | Detects when agent modifies memory files via Write/Edit and pushes a `memoryChanged` WebSocket event so the fr |
| [`MemoryDirectWriteGuard.scala`](MemoryDirectWriteGuard.scala) | MemoryDirectWriteGuard — budget pre-check + over-budget system-reminder for the direct-write memory form ( |
| [`MultiEditTool.scala`](MultiEditTool.scala) | A single edit parsed from the MultiEdit edits array. |
| [`NfCacheInvalidator.scala`](NfCacheInvalidator.scala) | NfCacheInvalidator —— 引擎侧文件变更 ⇒ `nf-cache-invalidate {path}` WS 广播的 单一发射点（M2 · 设计 v1 §6.1 事件失效腿）。 |
| [`NodeCancelTool.scala`](NodeCancelTool.scala) | 定义: NodeCancelTool |
| [`NodeEditTool.scala`](NodeEditTool.scala) | notifyDispatcher 参数载体：call() 解析的 （flag, provided）经 implicit 从 call() 词法作用域自动填入 createNode/proceed/ |
| [`NodeListTool.scala`](NodeListTool.scala) | status 过滤参数解析（语义⑤a）：三形态宽容（单值 string / array / 逗号串， 复用 parseIn 语义），逐值对生命周期枚举校验——非法值给可行动错误（列出全部 |
| [`NodeReportTool.scala`](NodeReportTool.scala) | node_report — Flow Map 节点专属终态语义申报工具（NodeReport 统一三语义迁移）。 裁定结论（转述）：工具按 NodeReport 命名而非 report_blocked——Blocked  |
| [`NodeTools.scala`](NodeTools.scala) | Node 工具集（#28 阶段 0，方案 §2.2 —— 任务分发器用）。 全部走工具、0 文件写入（验收①）：拓扑变更经 FlowMapStore 单事务原子更新； |
| [`PerceptionMailTool.scala`](PerceptionMailTool.scala) | `EmailSend` 工具（r2 §7 / §13 agent 工具面，r3+ 增强）：感知邮件发送的 拟稿入口。**工具只负责「落草稿 + 弹快照卡」**——发送动作永远由用户在 |
| [`PointerPickup.scala`](PointerPickup.scala) | PointerPickup —— 浏览面单件取件的本地落盘（v2.1 阶段 1 §C.2「按需取件」的 落位单点；产物指针登记不迁移语义的本地读数端）。 |
| [`PopTool.scala`](PopTool.scala) | Pop tool — shows finished artifacts to the user (pop-upgrade batch, 2026-10-03). == 2026-10-03 author ruling ( |
| [`PresetResolver.scala`](PresetResolver.scala) | Tool-level preset override for sub-agent spawning (Delegate/SubTask `preset` param). |
| [`ProjectTool.scala`](ProjectTool.scala) | 前端卡片取消哨兵（chat.js 取消按钮回填 answers=['__cancelled__']）。 |
| [`ReadTool.scala`](ReadTool.scala) | Supported image extensions → MIME type mapping. |
| [`RelayExecAudit.scala`](RelayExecAudit.scala) | relay/P2P 远端执行审计（Q3 裁定批 T4，2026-09-11）。 WHY：`dangerLevel` 只在 confirm-edits / auto-edits 下渲染权限卡；**auto-all 下 |
| [`RemoteBgOutputLog.scala`](RemoteBgOutputLog.scala) | 后台任务输出查看补全批（作者令 2026-10-25「这个截图是我们后台任务的进展查看 面板，现在就是远端任务看不到，本地任务细节好像也看不到」）· 远端日志路径判据面。 |
| [`RemoteExecCancelRegistry.scala`](RemoteExecCancelRegistry.scala) | 对端 remote-exec 进程的 **jobId → OS 进程** 登记表（后台任务生命周 P2 批 · 单任务取消的远端腿）。 |
| [`RemoteExecutor.scala`](RemoteExecutor.scala) | Pure decision surface for the P2P-vs-relay choice (A) and path memory (C4), 2026-09-11 P2P 直连修复批. |
| [`ResetAudit.scala`](ResetAudit.scala) | D12-b（delegate reset 零闸治理，作者裁定 2026-10-23；arch-review 批 `reports/20261022_232018_delegate-arch-review` D12 选项  |
| [`RgHelper.scala`](RgHelper.scala) | Shared helpers for running ripgrep (rg) subprocesses. Used by GrepTool and GlobTool to avoid duplicating proce |
| [`ScheduleTool.scala`](ScheduleTool.scala) | yyyy-MM-dd local date — todo creation date display. |
| [`ScriptTool.scala`](ScriptTool.scala) | 定义: ScriptTool |
| [`SleepPollGuard.scala`](SleepPollGuard.scala) | SleepPollGuard —— sleep-poll 轮询护栏（sleep-poll 禁令批 B 段，作者令 2026-10-20「明令禁止使用 sleep 或轮询的方式等待后台任务完成」）。 |
| [`StringMatcher.scala`](StringMatcher.scala) | Normalize curly quotes to straight quotes for comparison. |
| [`SubTaskTool.scala`](SubTaskTool.scala) | SubTaskTool — team-agent task delegation (Delegate split, 方案 A). Lets a team member spawn a self-cloned, ephem |
| [`SubagentTool.scala`](SubagentTool.scala) | SubagentTool —— 只读侦察子代理入口（builtin-merge 批 2026-10-03，Explore 同型）。挂载面 = 合并执行 agent `nebflow`（[[nebflow.agent.Ag |
| [`TaskBoardTool.scala`](TaskBoardTool.scala) | TaskBoard 工具（TaskBoard 批 2 接线，规格 project-task-board 规格件 §1b/§1d/§1e/§2，2026-09-08）——项目内共享工作项看板的工具面。 |
| [`TaskListHistory.scala`](TaskListHistory.scala) | TaskList 变更史 —— 独立 append-only JSONL 数据面（TaskList 升级批）。 动机：`tasks.json` 是整库覆盖写（read-modify-write），前后值即弃；定案口径： |
| [`TaskListTool.scala`](TaskListTool.scala) | TaskList 任务工具。 动机（设计文档 ~/.nebflow/memory/tasklist-design.md）：「Nebula 把记忆当任务 |
| [`TaskSearchTool.scala`](TaskSearchTool.scala) | TaskSearch —— 任务语义检索工具（Nebula 面；task-rag 批 #1227 / §Batch 7）。 管线（原文口径）：query → embed → cosine top-k →（可选 Jev 重 |
| [`TaskTool.scala`](TaskTool.scala) | The `Task` tool -- the **single write face of the unified ledger** (Nebula-exclusive; taskunify batch 2026-09- |
| [`TaskToolHelper.scala`](TaskToolHelper.scala) | Shared helper for task-related tools to emit list-update events. 任务工具重做（2026-08-30）：TaskArchive/归档索引退役——事件推送保留 |
| [`TeamTaskCreateTool.scala`](TeamTaskCreateTool.scala) | Team task tool — create. 任务工具重做 (2026-08-30): injected into EVERY team member (progress-display semantics); th |
| [`TeamTaskListTool.scala`](TeamTaskListTool.scala) | Team Manager task tool — list (spec §2.1/§2.3). READ-ONLY by hard constraint: no mutation parameters exist on  |
| [`TeamTaskUpdateTool.scala`](TeamTaskUpdateTool.scala) | Team task tool — update. 任务工具重做 (2026-08-30): injected into every team member (progress-display). |
| [`ToolLoader.scala`](ToolLoader.scala) | Tracks external tool names currently registered in ToolRegistry, so we can cleanly unregister them before relo |
| [`ToolPathUtil.scala`](ToolPathUtil.scala) | 文件工具的路径语义 helper（沙箱拆围栏批 S2 / R5=e2「拆闸保解析」）。 背景：阶段 2a 把「闸门」（写根/读根 contain 判定）与「路径解析」混装在 |
| [`ToolResultGuard.scala`](ToolResultGuard.scala) | Guards against oversized tool results that would blow the LLM context window. Two layers: |
| [`WebFetchTool.scala`](WebFetchTool.scala) | 定义: WebFetchTool |
| [`WebSearchTool.scala`](WebSearchTool.scala) | Anti-scraping garbage fingerprints (WebSearch P0, C4). A result entry whose text hits one of these is an anti- |
| [`WriteTool.scala`](WriteTool.scala) | Maximum content size accepted by Write, in UTF-8 encoded bytes. Aligned with ReadTool.MAX_FILE_BYTES so anythi |
| [`registry.scala`](registry.scala) | 动态注册名快照（阶段 2b：plugin MCP allowedSet 追加源；轻量——不经 augmentSchema，纯键名）。 |
| [`shell.scala`](shell.scala) | T2（cwd 判据）：Bash cwd 不存在 / 非法 ⇒ **显式失败**，不做任何形式的静默回退——旧行为回退 `user.home`（或 `C:\\` |
| [`types.scala`](types.scala) | 顶层根会话 id（P0 接线修复，Explorer 取证 c759e8c）：AgentState 已持有 effectiveRootSessionId（spawn 时计算，非空用 rootSessionId 参数否则 s |

## 依赖的兄弟模块

[`shared/`](../../shared/module-doc.md)、[`core/`](../module-doc.md)、[`actor/`](../../actor/module-doc.md)、[`core/project/`](../project/module-doc.md)、[`neblink/core/`](../../neblink/core/module-doc.md)、[`core/sandbox/`](../sandbox/module-doc.md)、[`core/delegate/`](../delegate/module-doc.md)、[`core/entity/`](../entity/module-doc.md)、[`core/task/`](../task/module-doc.md)、[`core/flow/`](../flow/module-doc.md)、[`core/node/`](../node/module-doc.md)、[`neblink/social/`](../../neblink/social/module-doc.md)、[`neblink/misc/`](../../neblink/misc/module-doc.md)、[`agent/`](../../agent/module-doc.md)、[`llm/imagegen/`](../../llm/imagegen/module-doc.md)、[`core/presets/`](../presets/module-doc.md)、[`core/util/`](../util/module-doc.md)、[`core/ask/`](../ask/module-doc.md)、[`dropbox/`](../../dropbox/module-doc.md)、[`core/executor/`](../executor/module-doc.md)、[`core/device/`](../device/module-doc.md)、[`perception/`](../../perception/module-doc.md)、[`neblink/identity/`](../../neblink/identity/module-doc.md)、[`neblink/relay/`](../../neblink/relay/module-doc.md)、[`core/scheduler/`](../scheduler/module-doc.md)、[`core/index/`](../index/module-doc.md)、[`core/hooks/`](../hooks/module-doc.md)
