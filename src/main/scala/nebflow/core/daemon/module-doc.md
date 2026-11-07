# core / daemon

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

外部守护进程生命周期（声明托管、启停、健康握手）。

**入口建议**：先读 [`DaemonService.scala`](DaemonService.scala)（本模块最大文件）。

## 文件（5）

| 文件 | 说明 |
|---|---|
| [`DaemonModel.scala`](DaemonModel.scala) | A daemon configuration entry stored in daemons.json. |
| [`DaemonPanelSchema.scala`](DaemonPanelSchema.scala) | Declaration validator for daemon config panels (daemonpanel Phase A). Threat model: `~/.nebflow/daemons.json`  |
| [`DaemonPanelStore.scala`](DaemonPanelStore.scala) | Storage for daemon config-panel VALUES (daemonpanel Phase A). Discipline: **declaration inline, values externa |
| [`DaemonService.scala`](DaemonService.scala) | Manages lifecycle of external long-running processes (development servers, etc.) Architecture: pure cats-effec |
| [`DaemonStore.scala`](DaemonStore.scala) | File-based persistence for daemon configurations (~/.nebflow/daemons.json). |

## 依赖的兄弟模块

[`shared/`](../../shared/module-doc.md)、[`core/`](../module-doc.md)、[`core/util/`](../util/module-doc.md)
