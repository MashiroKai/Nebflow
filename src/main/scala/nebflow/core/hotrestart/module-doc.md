# core / hotrestart

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

热重启编排：intent、后继门、端口让渡、安装指针。

**入口建议**：先读 [`HotRestart.scala`](HotRestart.scala)（本模块最大文件）。

## 文件（4）

| 文件 | 说明 |
|---|---|
| [`HealthProbe.scala`](HealthProbe.scala) | 健康端点**载荷构造的唯一来源**（hotupdate 批 2 G3）。 既有健康端点（`RestApiRoutes.scala:76`，挂载点 `/api/health`）的响应体就是本方法 |
| [`HotRestart.scala`](HotRestart.scala) | 触发器无关的热重启编排器（hot-restart 批，设计 hot-restart 设计件（内部留档））。 一次热重启的编排（旧实例视角，设计 §3.1 [1]-[8]）： |
| [`InstallPointer.scala`](InstallPointer.scala) | 安装目录的**版本留存 + 指针**布局（hotupdate 批 2 G4 · 设计 §6②）。 盘上布局（本设计**唯一必须新增**的盘上机制）： |
| [`SuccessorGate.scala`](SuccessorGate.scala) | 握手 intent（hot-restart 批设计 §3.3）——旧实例 write-ahead 落盘、后继进程读它 获知旧 pid 与目标端口；phase 字段由后继回写（readyToBind）或旧实例中止时改写 |

## 依赖的兄弟模块

[`shared/`](../../shared/module-doc.md)、[`core/`](../module-doc.md)、[`core/project/`](../project/module-doc.md)、[`core/tools/`](../tools/module-doc.md)
