# db / format

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

数据层记录格式（编解码）。

**入口建议**：先读 [`PendingRecord.scala`](PendingRecord.scala)（本模块最大文件）。

## 文件（5）

| 文件 | 说明 |
|---|---|
| [`EventEnvelope.scala`](EventEnvelope.scala) | 事件流存储 envelope（设计 §3.4(a) + D-12 对齐令）。 分层（D-12 工作默认）： |
| [`GuardDocs.scala`](GuardDocs.scala) | 冷却/软避窗小档（设计 §3.4(f)，案 B 类⑥，D-10 采纳）：`guard/` 下锁内 RMW 整档 五步写（低频，无需 journal）。 |
| [`PendingRecord.scala`](PendingRecord.scala) | 待审单记录（设计 §3.4(c)，案 B 类③）：内置权限单 + 待答 ask / MCP 权限单。 现状 7 字段（`PendingAskLedger.Entry` 派生）保留；〔提案〕新增 6：`seq`、 |
| [`ScheduleRecord.scala`](ScheduleRecord.scala) | 调度任务记录（设计 §3.4(d)，案 B 类④）：envelope 4 字段〔提案〕， `taskDef` = 现状 `ScheduledTask` 载荷原样（opaque Json，须携带 `id`——现状面恒有）。 |
| [`SessionEnvelope.scala`](SessionEnvelope.scala) | 会话消息记录 envelope（设计 §3.4(b)/(e)，案 B 类①）。 envelope 6 字段〔提案〕；`payload` 零改动——现状 `Message`/`UiMessage` JSON 原样 |
