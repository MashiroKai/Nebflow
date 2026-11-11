# core / project

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

项目/Flow Map：节点引擎、任务账本、合并队列、崩溃恢复（最大子模块）。

**入口建议**：先读 [`ProjectActor.scala`](ProjectActor.scala)（本模块最大文件）。

## 文件（46）

| 文件 | 说明 |
|---|---|
| [`BootDispatcherWake.scala`](BootDispatcherWake.scala) | BootDispatcherWake —— 宿主启动「自动重入」腿（方案件选项 A 档 A1「控制面唤醒腿」）。 设计正本（只读引用）： |
| [`BootWakeInventory.scala`](BootWakeInventory.scala) | BootWakeInventory —— 宿主启动自动重入的「落盘事实清单」构建器（选项 A 档的输入面， 采用方案件 B 档格式）。 |
| [`ChainLedger.scala`](ChainLedger.scala) | ChainLedger —— **链号台账**（chainmodel 批二：链号台账 + 链号不变 + 退出机制三轴）。 设计正本（只读引用）：`~/.nebflow/docs/Nebflow/20260919_1218 |
| [`ChainLedgerStore.scala`](ChainLedgerStore.scala) | ChainLedgerStore —— **链号台账的持久面与拍点**（chainmodel 批二）。 判据/算法全部在 [[ChainLedger]]（纯函数，可单测）；本类只负责三件事： |
| [`ChainsReadFace.scala`](ChainsReadFace.scala) | ChainsReadFace —— **per-task 只读读面**（N1 刀3 · 设计 v3 §3 A8 / §9；#1228 stub §四.3「per-task 读面」的唯一硬依赖落位）。两条 REST 臂（挂 |
| [`CompletionGate.scala`](CompletionGate.scala) | 产物完整性闸门（worktree 审计 20260905 机制建议落地）——节点 completed 出口的 worktree 三合法态校验，堵「节点自报 completed 但产物滞留（沙箱 EPERM 未 commi |
| [`DelegateFlowHosts.scala`](DelegateFlowHosts.scala) | DelegateFlowHosts —— **delegate 作用域宿主注册表与生命周期**（N1 刀1 · 设计 v3 §6/A3： 「NodeEngine 宿主从 ProjectActor 挂载链抽出 delega |
| [`DeviceProjectMount.scala`](DeviceProjectMount.scala) | 设备项目挂载编排（跨设备项目挂载批 2026-10-25）。 语义（作者令：项目挂载到设备 KAI 验证跨设备项目功能）：项目 workspace 本体在 |
| [`DispatchNotify.scala`](DispatchNotify.scala) | dispatch-notify —— 节点终态结果回流任务分发器的单一通知通道。 目标语义：显式开启 notifyDispatcher 标志的节点 |
| [`DocIndexConsumer.scala`](DocIndexConsumer.scala) | 文档索引维护消费者（P3 引擎侧归档联动批 2026-09-10，spec `20260910_process-doc-chain-attribution-spec.md` §6.2/§6.3/§9.2 项 9）。 |
| [`FeedbackRouter.scala`](FeedbackRouter.scala) | Retry-cap escalation router (P2 retry budget, spec §2.3 gate G12): when a failed node has exhausted its automatic bounce-back retries, a single escalation notice |
| [`FileChangeReport.scala`](FileChangeReport.scala) | Per-node file-change report: the durable artifact behind the frontend diff view for completed nodes. |
| [`FlowMapEventLog.scala`](FlowMapEventLog.scala) | Flow Map 调整事件审计日志。 `<workspace>/.nebflow/flow-map-events.jsonl` 追加式 JSONL，每行一事件： |
| [`FlowMapStore.scala`](FlowMapStore.scala) | FlowMapStore —— Flow Map 存储（#28 阶段 0，方案 §2.6；**N1 刀1 作用域化**：per-project 旧面（[[open]]）＋ per-delegate 作用域面（[[open |
| [`LoopBudget.scala`](LoopBudget.scala) | loop 预算判定单点（nrloop 一期 2026-09-12；设计 §3.6「轮次帽 + 时间帽」维）。 纯函数（无 IO / 无状态）：引擎两条调用腿共用同一判据—— |
| [`MergeMutexPolicy.scala`](MergeMutexPolicy.scala) | 合并窗 FIFO 互斥策略（**mergefifo-engine 批** 2026-09-13，作者 A-4 裁决收窄落地）。 真源（设计与规格的关系，逐字沿设计件 §11 A-8）： |
| [`MergeNodePolicy.scala`](MergeNodePolicy.scala) | 合并节点策略（merge-node 批 20260905，方案 merge-node-plan 方案件）。 背景：任务节点各自在 worktree/分支产出，completed 后产物滞留——落地全靠宿主 |
| [`NodeCanceller.scala`](NodeCanceller.scala) | 节点是否正在运行（ProjectActor 状态查询用）。 |
| [`NodeCompletion.scala`](NodeCompletion.scala) | 节点终态分流（node_report 结构化申报消费单点）：declared = 节点会话经 node_report 工具申报的结构化反馈 |
| [`NodeDelivery.scala`](NodeDelivery.scala) | U3/B 兜底腿（2026-09-11 作者拍板）：L3 resume 失败且**活动区查无**该会话归属节点 时的收口——把「找不到节点归属」从静默死路改成有账的迟到摘除。 |
| [`NodeEngine.scala`](NodeEngine.scala) | NodeEngine —— 节点执行内核（#28 阶段 0，方案 §2.7 + §3.1）。 职责： |
| [`NodeEngineContract.scala`](NodeEngineContract.scala) | `cascadeCancelledIds` 的 FIFO 上界：见该 Ref 头注—— 只为防长跑进程无界增长；超限丢最旧，最坏后果 = 已取消下游被补一条 `pendingSuccession` |
| [`NodeGating.scala`](NodeGating.scala) | deps 满足判定（deps 设计 §1.3）：声明式状态查询（幂等、零记账），非 deliveredTo 式事件累计。findNode 归档兜底——TTL 归档不影响「完成」事实（归档上游可触发， |
| [`NodeLoopRunner.scala`](NodeLoopRunner.scala) | Loop 会话常驻桥行为（与 runWithAgent bridge 同构，但**每轮完成不停止**——只 complete 本轮 Deferred、保持存活等下一轮注入）。失败/取消/会话死亡 → complete |
| [`NodeRecovery.scala`](NodeRecovery.scala) | 未申报提醒阶梯扫描腿（ProjectActor.TtlTick 30s 驱动，与 `settleStaleRunningNodes` / `settleRunnableSweep` 同族——复用既有心跳点零新调度器）。 |
| [`NodeReportRegistry.scala`](NodeReportRegistry.scala) | 节点终态语义申报登记表（20260909 blocked-signal 设计 spec 方案 A 改造点 #1； 同日作者裁定泛化：BlockedSignalRegistry → NodeReport 统一三语义命名—— |
| [`NodeStarter.scala`](NodeStarter.scala) | eng-deferred-cancel batch (2026-10-02): bounded grace for a session to stop itself AFTER the deferred-cancel i |
| [`NotificationHeader.scala`](NotificationHeader.scala) | 注入气泡顶栏（浅蓝注入气泡 header）的**单点格式化函数**—— 「气泡四段式统一」批（作者 2026-09-15 12:33 令；引擎面）。 |
| [`PluginInjection.scala`](PluginInjection.scala) | PluginInjection —— **分配→注入等价路径**（jev-plugins 批 #1226 增补 §3.1/§3.2）. == 本件是什么 == |
| [`ProjectActor.scala`](ProjectActor.scala) | ProjectActor —— 每项目一个的常驻 actor（#28 阶段 0，方案 §1.1）。 职责： |
| [`ProjectCrashRecovery.scala`](ProjectCrashRecovery.scala) | ProjectCrashRecovery —— boot-time 崩溃断点恢复 sweep（crash-recovery 批 2026-09-07， 设计 boot-time-crash-recovery 设计件 §3 |
| [`ProjectCreateService.scala`](ProjectCreateService.scala) | Project create core — the single implementation behind BOTH creation faces: ① `ProjectCreateTool.createChain`  |
| [`ProjectMemory.scala`](ProjectMemory.scala) | ProjectMemory —— 项目级记忆文件（project-memory 批 2026-09-05）单点。 文件落位：`<workspace>/.nebflow/memory.md`（每项目 workspace 一 |
| [`ProjectMountPreflight.scala`](ProjectMountPreflight.scala) | 挂载预检（跨设备项目挂载批 2026-10-25 · 现场事故必修）。 两条铁律（作者令 2026-10-25 00:30 逐字）： |
| [`ProjectStore.scala`](ProjectStore.scala) | Project 实体存储（#28 阶段 0）： - 磁盘 `~/.nebflow/projects/<name>/project.json`（定义） |
| [`ProjectTypes.scala`](ProjectTypes.scala) | Project + Node + Flow Map 数据模型（#28 阶段 0，方案文档 20260831_project-node-architecture.md §2.1）。 - Node 本质 = 一个 agent |
| [`ProjectUnmount.scala`](ProjectUnmount.scala) | Project unmount 三步序（N2 project-tool 批 2026-10-06 · S17「先停任务、文件保留」+ ⑩A 软硬分档：unmount＝硬卸载、语义严于 archive）。 |
| [`ShutdownState.scala`](ShutdownState.scala) | 优雅关机「排水中」标志（中断恢复语义批 2026-09-13，spec interrupt-recovery-semantics 规格件（内部留档） §2.3）。 |
| [`TaskBoardHistory.scala`](TaskBoardHistory.scala) | TaskBoardHistory —— 任务板变更史（TaskBoard 升级批，2026-09-11）。 定位：TaskBoard 的「变更史」数据面——append-only JSONL，**per-workspac |
| [`TaskBoardRenderer.scala`](TaskBoardRenderer.scala) | TaskBoardRenderer —— 任务板渲染单点（TaskBoard 设计规格 §3a/§3b，2026-09-08）。 两消费者共用：分发器注入块（renderDispatcher）与节点注入块（renderN |
| [`TaskBoardStore.scala`](TaskBoardStore.scala) | TaskBoardStore —— 项目任务板存储（TaskBoard 设计规格 §1f/§1g/§2，2026-09-08， 规格稿 project-task-board 规格件（内部留档））。批 1 纯引擎层：sto |
| [`TaskLedgerHistory.scala`](TaskLedgerHistory.scala) | TaskLedgerHistory -- the change history of the unified ledger (taskunify batch, 2026-09-24). |
| [`TaskLedgerRenderer.scala`](TaskLedgerRenderer.scala) | TaskLedgerRenderer -- the single rendering point of the unified ledger (taskunify batch, 2026-09-24). |
| [`TaskLedgerStore.scala`](TaskLedgerStore.scala) | A unified-ledger entry (wire schema, three states). 🔴 **No `note` field**: the note timeline is **not on the e |
| [`VerdictReader.scala`](VerdictReader.scala) | verify 判定文法解析器（LoopNode 批 2026-09-06，主设计 20260902_flowmap-engine-evolution-design.md §2.3； 独立 object |
| [`WakeSensor.scala`](WakeSensor.scala) | 宿主睡眠/唤醒感知器（hostresume 批 2026-09-22，设计卡 `~/.nebflow/docs/Nebflow/20260921_235342_host-interrupt-resume-design__ |

## 依赖的兄弟模块

[`shared/`](../../shared/module-doc.md)、[`core/`](../module-doc.md)、[`actor/`](../../actor/module-doc.md)、[`core/tools/`](../tools/module-doc.md)、[`core/delegate/`](../delegate/module-doc.md)、[`core/plugin/`](../plugin/module-doc.md)、[`core/node/`](../node/module-doc.md)、[`core/entity/`](../entity/module-doc.md)、[`db/`](../../db/module-doc.md)、[`db/format/`](../../db/format/module-doc.md)、[`core/skill/`](../skill/module-doc.md)、[`core/jev/`](../jev/module-doc.md)
