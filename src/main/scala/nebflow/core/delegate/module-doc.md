# core / delegate

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

跨会话 delegate 任务：分发、生命周期、终态结算与设备腿（引擎不托管工作树；合并由作者显式决定）。

> **dispatcher 模式封存**（2026-11-18，dispatcher-seal 批）：dispatcher 会话反复挂死——起链正常，节点到终态后父会话不再被唤醒（不继续、不交回、不报错，一挂 2h+）。`mode=dispatcher` 派发被显式拒（错误码 `DELEGATE_DISPATCHER_SEALED`，绝不静默降级 kernel）；实现全保留，恢复 = `stand.json` 顶层 `delegate` 节 `dispatcherMode` 置 `"enabled"`（缺省/非法词 = `"sealed"`，fail-closed；派发点热读即席生效）。详见 [`DispatcherSeal.scala`](DispatcherSeal.scala)。

**入口建议**：先读 [`DelegateRegistry.scala`](DelegateRegistry.scala)（持久身份的单一地址簿）。

## 文件（24）

| 文件 | 说明 |
|---|---|
| [`CancelReceipt.scala`](CancelReceipt.scala) | CancelReceipt —— **cancel 后回执确认**（R1 缝隙 A 修，2026-10-28 实施批 · 报告二 §1.3）：`AgentControlTool.doCancel` 监督者路径的原 fir |
| [`ChainStore.scala`](ChainStore.scala) | ChainStore —— 新轻量 per-task 链存储（⑤A 定稿，worktree-judge-fifo 批 #1224 增补 §3.5：**本批建存储本体＋写入行**）. |
| [`DelegateDeliveryReceipts.scala`](DelegateDeliveryReceipts.scala) | DelegateDeliveryReceipts —— delegate 终态交付回执 + 「父未确认」重投监测 （诊断修复批 2026-10-24 · delegate-nebflow-52308ee4 完成结果投递丢 |
| [`DelegateFlowMapLifecycle.scala`](DelegateFlowMapLifecycle.scala) | DelegateFlowMapLifecycle —— **delegate 作用域图生命周期**（双模式批 2026-10-28 · 设计 §6「flowmap 生命周期」；第 4 步实施件）。 |
| [`DelegateJudge.scala`](DelegateJudge.scala) | DelegateJudge —— delegate 判定机的 core 侧（worktree-judge-fifo 批 #1224 增补 §3.1/§3.2；jev-retire 批 2026-11-16 起判定 = SmallJudge（LLM）单 |
| [`DelegateLedgerReconcile.scala`](DelegateLedgerReconcile.scala) | DelegateLedgerReconcile —— **台账 ↔ delegate 面板两层状态打通**（监督链自动恢复 批增补，2026-10-22 作者补充需求；设计 |
| [`DelegateNotify.scala`](DelegateNotify.scala) | DelegateNotify —— 通知改向通道与规则（S19①⑥ ＋ ⑬A 兜底；worktree-judge-fifo 批 #1224 增补 §3.6）. |
| [`DelegateRegistry.scala`](DelegateRegistry.scala) | DelegateRegistry —— 统一 delegate 的持久地址簿（unified-delegate 批 2026-10-03）。 |
| [`DelegateTerminal.scala`](DelegateTerminal.scala) | DelegateTerminal —— delegate 终态判定钩子（R6 终态判定 + S12 合并轮；worktree -judge-fifo 批 #1224 增补 §3.1/§3.3；#566 修复批 2026- |
| [`DelegateWorkReport.scala`](DelegateWorkReport.scala) | DelegateWorkReport —— 合并轮 finish 交付的工作结果附页（delegate-finish-report 修复批 2026-10-25 · 作者令逐字：「delegate report merg |
| [`DeviceDelegate.scala`](DeviceDelegate.scala) | 设备腿 delegate 通道（v2.1 阶段 1 §A.3 / §E 阶段 1「项目分布执行」）。 通道**：presence WS 结构化消息（既有数据通道单点，`sendData`）+ relay 兜底 |
| [`DeviceResultOutbox.scala`](DeviceResultOutbox.scala) | DeviceResultOutbox —— **跨设备完成件的持久待发区**（台账 #944 跨设备回报丢件腿）。 == 病灶（2026-10-26 实证链，Windows 侧日志 + Mac 侧日志双侧对账）== |
| [`DispatcherSeal.scala`](DispatcherSeal.scala) | DispatcherSeal —— **dispatcher 模式封存闸**（dispatcher-seal 批 2026-11-18 · 作者令「dispatcher模式封存…让kernel来做」）：dispatcher 会话在节点终态后父会话反复不再被唤. |
| [`DualMode.scala`](DualMode.scala) | DualModeConfig —— **delegate 双模式总闸**（设计 `docs/design/20261028_213056_delegate-dual-mode-redesign__delegate-neb |
| [`ExternalJudgeHook.scala`](ExternalJudgeHook.scala) | ExternalJudgeHook —— 外部 delegate 终态判定的**接线缝**（external-executors 批 #1225 留缝；判定机本体归 worktree-judge-fifo 批 #1224 |
| [`MergeAcceptance.scala`](MergeAcceptance.scala) | MergeAcceptance —— 合并轮终态的**机械验收闸**（引擎加固批 2026-10-09，上游审计 #31 结论 P1-1①）. |
| [`MergeArtifactCare.scala`](MergeArtifactCare.scala) | MergeArtifactCare —— 合并闸前**生成工件处置面**（merge-queue 滞留两病灶修复批 2026-10-25，作者令腿①「工件处置兑现」）。 |
| [`MergeQueue.scala`](MergeQueue.scala) | MergeQueue —— delegate 合并队列：**交通引导员**（台账 #896 · 2026-10-26 作者裁定 「主要是你的重试和复活的逻辑写的不好……我们的合并排队系统 应该就是一个交通引导员的 |
| [`MergeQueueCare.scala`](MergeQueueCare.scala) | MergeQueueCare —— 合并队列（交通引导员制）的**扫描拍驱动面**（台账 #896 2026-10-26 作者裁定落地批；消费点 = `TaskStuckWatcher.scan` 尾部，搭既有扫描拍 |
| [`MergeQueueChannel.scala`](MergeQueueChannel.scala) | MergeQueueChannel —— 合并队列的**write-ahead 通道**（R5 重启丢队修复 · 报告二 §4.3 / 设计稿 §1 P5 行；报告二建议「DeviceResultOutbox 同型可搬」 |

## 依赖的兄弟模块

[`shared/`](../../shared/module-doc.md)、[`core/`](../module-doc.md)、[`actor/`](../../actor/module-doc.md)、[`core/tools/`](../tools/module-doc.md)、[`core/project/`](../project/module-doc.md)、[`neblink/core/`](../../neblink/core/module-doc.md)、[`neblink/social/`](../../neblink/social/module-doc.md)、[`core/device/`](../device/module-doc.md)
