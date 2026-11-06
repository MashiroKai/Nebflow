# core / task

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

文件任务存储。

**入口建议**：先读 [`TaskStore.scala`](TaskStore.scala)（本模块最大文件）。

## 文件（2）

| 文件 | 说明 |
|---|---|
| [`TaskModel.scala`](TaskModel.scala) | 任务工具重做（2026-08-30 作者规格）：进展展示语义——四态状态机 pending → in_progress → completed / failed。needs_confirmation（用户确认 |
| [`TaskStore.scala`](TaskStore.scala) | Scope-key helpers for the team-domain task tools (spec 20260825_team-manager-task-tool-spec.md §4.2 方案 A): the |

## 依赖的兄弟模块

[`core/`](../module-doc.md)、[`shared/`](../../shared/module-doc.md)
