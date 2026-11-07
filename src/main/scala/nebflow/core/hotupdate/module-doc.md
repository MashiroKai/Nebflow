# core / hotupdate

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

统一更新编排：更新请求、七相位执行、回滚。

**入口建议**：先读 [`UpdateOrchestrator.scala`](UpdateOrchestrator.scala)（本模块最大文件）。

## 文件（5）

| 文件 | 说明 |
|---|---|
| [`RemoteUpdateAction.scala`](RemoteUpdateAction.scala) | Runs the remote update install script and returns the result. Shared logic used by: |
| [`Rollback.scala`](Rollback.scala) | 指针化回滚（hotupdate 批 2 G4 · 设计 §6②）。 回滚动作**（逐条对齐设计）：① 指针切回上一版（[[InstallPointer.flip]]——承重件： |
| [`UpdateOrchestrator.scala`](UpdateOrchestrator.scala) | 统一更新编排器（hotupdate 批 1 · 设计 §4 设计 A）：所有入口只发「更新请求」， 由它独占执行。本件是**串链件**——每一个相位都落到既有复用点上，零平行机制： |
| [`UpdateTypes.scala`](UpdateTypes.scala) | 更新请求来源（设计 §4 请求契约：设置页｜设备列表｜中继｜官网｜命令行）。 本批（批 1）只有设置页一个入口接线（D3）；设备列表/中继/官网/命令行的入口恢复 |
| [`VersionCheck.scala`](VersionCheck.scala) | 版本指针读取与比对——**单一实现**。 复用点（设计附录 B #29）：既有设置页版本检查的「读版本指针 → 剥前导 v → 与当前 |

## 依赖的兄弟模块

[`shared/`](../../shared/module-doc.md)、[`core/hotrestart/`](../hotrestart/module-doc.md)、[`neblink/relay/`](../../neblink/relay/module-doc.md)、[`core/`](../module-doc.md)
