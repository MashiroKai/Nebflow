# core / sandbox

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

沙箱配置与运行时。

**入口建议**：先读 [`SandboxPolicy.scala`](SandboxPolicy.scala)（本模块最大文件）。

## 文件（4）

| 文件 | 说明 |
|---|---|
| [`ContainerLifecycle.scala`](ContainerLifecycle.scala) | per-task 容器生命周期**骨架**（沙箱统一底座设计方案 §2.3 / §4-T6，2026-09-11 实施批）。 权威源： |
| [`FileSandbox.scala`](FileSandbox.scala) | 文件工具层路径接缝（阶段 2a 沙箱 → 沙箱拆围栏批 S2 退役形态）。 [沙箱拆围栏批 S2，2026-09-10] 本对象已不再做围栏判定**：写根（`writableRoots` |
| [`SandboxBackend.scala`](SandboxBackend.scala) | 执行环境 provider（拆围栏批 design §4.2 / R4=d2，2026-09-10 作者裁定）： 语义从「宿主 Bash 的 OS 强制后端」改为「**执行环境 provider**」——回答的问题是 |
| [`SandboxPolicy.scala`](SandboxPolicy.scala) | 阶段 2a 沙箱（设计文档 §A.2）：单一策略源。 root = 会话沙箱根（sessionRoot 唯一推导）。[2026-09-05 21:05 作者裁定——worktree |

## 依赖的兄弟模块

[`shared/`](../../shared/module-doc.md)、[`core/tools/`](../tools/module-doc.md)、[`actor/`](../../actor/module-doc.md)
