# core / flow

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

事件/邮件流与投递去重。

**入口建议**：先读 [`MailIdleGate.scala`](MailIdleGate.scala)（投递闸单一判据；团队树 `FlowTreeActor`/`FlowTreeRegistry`/`FlowTreeTypes` 已随团队面退役删除）。

## 文件（7）

| 文件 | 说明 |
|---|---|
| [`FlowMailStore.scala`](FlowMailStore.scala) | Persistent mail history per flow instance. Each Mail between flow agents is recorded so users can review commu |
| [`FlowSemantics.scala`](FlowSemantics.scala) | Unified semantics for Flow DAG nodes — single source of truth for node lifecycle status and switch-verdict nor |
| [`MailDeliveryDedup.scala`](MailDeliveryDedup.scala) | Fingerprint dedup for the queue-delivery layer (P0 投递层指纹去重). Root cause this plugs: the SAME queue mail can be |
| [`MailIdleGate.scala`](MailIdleGate.scala) | #407 (2026-08-25 用户裁定 19:53) + 2026-08-28 01:00 统一裁定：queue 模式 Mail 的投递时机 gate。 |
| [`MailQueueStore.scala`](MailQueueStore.scala) | Persistent FIFO queue for **legacy** queue-mode mails. ⚠ 退役登记（delivery 退役批，2026-09-15 作者裁定 (b)「**保留字段、退役 queue |
| [`RunningFlowRegistry.scala`](RunningFlowRegistry.scala) | Tracks running flow DAG instances for the frontend. Each entry represents a one-shot flow triggered via the Fl |
| [`TurnStateStore.scala`](TurnStateStore.scala) | Deletes a session's turn-state marker file. R4 legacy-hygiene batch (2026-10-29): this object used to be the d |

## 依赖的兄弟模块

[`shared/`](../../shared/module-doc.md)、[`core/`](../module-doc.md)、[`core/entity/`](../entity/module-doc.md)、[`actor/`](../../actor/module-doc.md)、[`core/tools/`](../tools/module-doc.md)
