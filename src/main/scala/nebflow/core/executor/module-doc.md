# core / executor

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

外部执行器：外部会话运行与准备腿。

**入口建议**：先读 [`ExternalSessionRunner.scala`](ExternalSessionRunner.scala)（本模块最大文件）。

## 文件（6）

| 文件 | 说明 |
|---|---|
| [`ExecutorRegistry.scala`](ExecutorRegistry.scala) | ExecutorRegistry —— 统一 delegate 的执行器注册与检测（executor-registry 批 2026-10-03）。 |
| [`ExternalExecutor.scala`](ExternalExecutor.scala) | ExternalExecutor —— 外部 CLI 执行器适配层（external-executors 批 #1225， 2026-10-06）。R2/R11 外部腿真通路：Batch 1 的检测面（[[Executo |
| [`ExternalOpenNative.scala`](ExternalOpenNative.scala) | ExternalOpenNative —— 「打开原软件」命令模板（external-executors 批 #1225， 原书 AgentControl 面：外部行可从面板/弹窗打开原 CLI 的原生终端并 resum |
| [`ExternalOutputStore.scala`](ExternalOutputStore.scala) | ExternalOutputStore —— 外部执行器进度缓冲 ＋ 活体注册表 ＋ mailbox （external-executors 批 #1225，2026-10-06）。 |
| [`ExternalSessionRunner.scala`](ExternalSessionRunner.scala) | ExternalSessionRunner —— 外部执行器会话运行器（external-executors 批 #1225，2026-10-06）。R2/R11 外部腿真通路的执行半：子进程生命周期、流帧、 |
| [`ExternalSpawnConfig.scala`](ExternalSpawnConfig.scala) | ExternalSpawnConfig —— 外部执行器 spawn 环境配置（codex-spawn-fix 批 2026-10-19）。 |

## 依赖的兄弟模块

[`core/delegate/`](../delegate/module-doc.md)、[`shared/`](../../shared/module-doc.md)、[`actor/`](../../actor/module-doc.md)、[`core/`](../module-doc.md)、[`core/tools/`](../tools/module-doc.md)
