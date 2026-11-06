# service

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

配置服务与快照（ConfigService / ConfigSnapshot）。

**入口建议**：先读 [`ConfigService.scala`](ConfigService.scala)（本模块最大文件）。

## 文件（5）

| 文件 | 说明 |
|---|---|
| [`AgentService.scala`](AgentService.scala) | Read the system.md for an agent (for frontend prompt editor). |
| [`ConfigService.scala`](ConfigService.scala) | nebflow.json 全部 read-modify-write 写者的进程级串行锁。 写者 = updateConfig（WS/REST 全量保存）+ gateway 各 targeted write |
| [`ConfigSnapshot.scala`](ConfigSnapshot.scala) | Manages config file snapshots for crash recovery. Saves a backup on every successful config load/update. |
| [`NebflowBackup.scala`](NebflowBackup.scala) | Periodic backup of critical ~/.nebflow files. Backs up: sessions, folders, agent memory, User.md, nebflow.json |
| [`SessionService.scala`](SessionService.scala) | 会话服务层。 2026-09-13（permshield S1）：档位相关职责收敛为**出口 overlay** —— 会话列表里 |

## 依赖的兄弟模块

[`shared/`](../shared/module-doc.md)、[`actor/`](../actor/module-doc.md)、[`core/`](../core/module-doc.md)、[`agent/`](../agent/module-doc.md)、[`neblink/core/`](../neblink/core/module-doc.md)
