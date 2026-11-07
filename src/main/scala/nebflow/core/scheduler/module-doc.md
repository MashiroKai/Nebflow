# core / scheduler

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

定时任务：服务、存储、周报生成。

**入口建议**：先读 [`ScheduledTaskActor.scala`](ScheduledTaskActor.scala)（本模块最大文件）。

## 文件（8）

| 文件 | 说明 |
|---|---|
| [`AskTodoBridge.scala`](AskTodoBridge.scala) | Ask→待办 bridge（待办分家批 2026-10-30，作者令「待办接 AskUserQuestion： Ask 产生的事项自动入待办，闭环 Ask→待办→用户答→销账」； |
| [`ConditionEvaluators.scala`](ConditionEvaluators.scala) | 评估判定。Met = 条件为真（注入）；Unmet = 未满足（静默顺延，零注入）。 |
| [`DoneRecordStore.scala`](DoneRecordStore.scala) | 打卡 done 历史（认知二期产品批·周报生成器数据源）：待办/打卡项被 done 销账时 追加一条**只增不改**的 JSONL 记录。此前引擎只删行不留痕——「本周打卡 N/M」 |
| [`ScheduledTaskActor.scala`](ScheduledTaskActor.scala) | 待办浮出 digest 文案（纯函数，spec 钉内容契约）：含每条待办的 id（销账 句柄）、内容与创建日期，结尾给出 done/cancel 用法。todos 为空 ⇒ 空串 |
| [`ScheduledTaskModel.scala`](ScheduledTaskModel.scala) | A scheduled task attached to a session — fires once or on a recurring schedule. |
| [`ScheduledTaskStore.scala`](ScheduledTaskStore.scala) | Scheduled-task persistence（引擎侧文件存储：每会话一个 `<sessionId>.json`）。 🔒 全库互斥（2026-10-29 调度丢失 bug 根因修复，事故 f2ef6ff1）：本类的 |
| [`SessionToolActivity.scala`](SessionToolActivity.scala) | 会话工具活动时钟（2026-10-29 例程自检降频批）：记录每个会话最近一次工具 调用成功的墙钟——例程空转判据的数据源。由 AgentSessionExecution 的工具成功 |
| [`WeeklyReport.scala`](WeeklyReport.scala) | 周报生成器（认知二期产品批·设计稿 §6.5 第 5 段 + §9-12 裁定：引擎生成＋ 收件面浮出＋可归档 reports/；模板 = 附录 C 实例化）。 |

## 依赖的兄弟模块

[`shared/`](../../shared/module-doc.md)、[`actor/`](../../actor/module-doc.md)、[`core/`](../module-doc.md)、[`db/`](../../db/module-doc.md)
