# neblink / sync

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

跨设备投影：后台子会话投影、智能体名称/头像收敛。

**入口建议**：先读 [`AgentSessionSync.scala`](AgentSessionSync.scala)（本模块最大文件）。

## 文件（2）

| 文件 | 说明 |
|---|---|
| [`AgentIdentitySync.scala`](AgentIdentitySync.scala) | AgentIdentitySync —— 智能体名称/头像的**跨引擎收敛通道**（identity-sync 批 2026-10-23）。跨机不同步的根因：展示面（`agents/<name>/agent.json`  |
| [`AgentSessionSync.scala`](AgentSessionSync.scala) | AgentSessionSync —— **后台子智能体会话的跨设备投影通道**（台账 #929，2026-10-26 作者令：「多设备设计的核心就是透明，跑在哪个设备上，那我们的显示都应该都能看到。 |

## 依赖的兄弟模块

[`neblink/core/`](../core/module-doc.md)、[`core/device/`](../../core/device/module-doc.md)、[`shared/`](../../shared/module-doc.md)、[`neblink/social/`](../social/module-doc.md)、[`core/entity/`](../../core/entity/module-doc.md)、[`agent/`](../../agent/module-doc.md)
