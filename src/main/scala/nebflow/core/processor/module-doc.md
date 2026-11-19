# core / processor

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

处理器：循环守卫、卡死看护、冻结调度、投递看门狗。

**入口建议**：先读 [`TaskStuckWatcher.scala`](TaskStuckWatcher.scala)（本模块最大文件）。

## 文件（8）

| 文件 | 说明 |
|---|---|
| [`BgLifecycleReconciler.scala`](BgLifecycleReconciler.scala) | BgLifecycleReconciler —— 后台任务健康轨 reconcile 控制器（后台任务生命周期批 P1，设计稿 20261030_background-task-lifecycle §3，台账 #1120 |
| [`DeliveredReceiptLedger.scala`](DeliveredReceiptLedger.scala) | DeliveredReceiptLedger —— **已投递回执标记的持久台账**（#886 完成回执重启去重批， 作者令 2026-10-25「完成回执重启去重」修法要求 1/2 的数据面）。 |
| [`FreezeScheduler.scala`](FreezeScheduler.scala) | 冻结恢复扫描器（freeze-schedule spec v1.1 ⑤，TaskStuckWatcher 的姊妹模式）。 每 interval（默认 30s，Defaults.FreezeCheckIntervalSec |
| [`HeldDeliveryWatchdog.scala`](HeldDeliveryWatchdog.scala) | HeldDeliveryWatchdog —— held 回执投递可靠性自检（平台修复批「delegate 回执投递 可靠性」作者令 2026-10-21，fix 1/2/3 的调度面）。 |
| [`HeldReceiptStore.scala`](HeldReceiptStore.scala) | HeldReceiptStore —— 「待投递回执」持久台账（平台修复批：delegate 回执投递可靠性， 作者令 2026-10-21「分析完成了没回报后的治本」第 1/2 件的数据面）。 |
| [`LoopGuard.scala`](LoopGuard.scala) | Block 3（supervision trio §D, 2026-08-27；简化重构 2026-08-30 作者拍板）： 循环检测器。 |
| [`TaskStuckWatcher.scala`](TaskStuckWatcher.scala) | P0 阶段 3（2026-08-18，设计 §4.4）：卡死识别与恢复扫描器。 背景：12:39 现场事故——7 个并发 Delegate 撞 API 限流后各自进入 retry/fallback |
| [`WatchdogEventLog.scala`](WatchdogEventLog.scala) | R8 方向①（看门狗自身监测，2026-09-10 设计 §2.3）：卡死看门狗**自身**每次开火的 结构化事件流。 |

## 依赖的兄弟模块

[`shared/`](../../shared/module-doc.md)、[`actor/`](../../actor/module-doc.md)、[`core/`](../module-doc.md)、[`core/tools/`](../tools/module-doc.md)
