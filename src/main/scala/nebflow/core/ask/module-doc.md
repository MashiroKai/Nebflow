# core / ask

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

Canvas ask 模式：会话注册表、活体检索源、提示缓存与投递面。

**入口建议**：先读 [`AskSessions.scala`](AskSessions.scala)（本模块最大文件）。

## 文件（4）

| 文件 | 说明 |
|---|---|
| [`AskContextAssembler.scala`](AskContextAssembler.scala) | 单路预算（机制稿 §3.2「截断上限 = 单路预算」）。 |
| [`AskGatewayServices.scala`](AskGatewayServices.scala) | spawn 面：ask 会话的运行时 AgentDef（name/category 仅为身份占位——工具面 由 buildAllowedToolSet 的 askSession 早退集机制固定，不读声明）。 |
| [`AskService.scala`](AskService.scala) | Inline Ask service — builds the ask-reminder message for injection into the agent's pipeLlmCall path.  No inde |
| [`AskSessions.scala`](AskSessions.scala) | 会话 id 前缀单点——工具面/提示词/spawn 判据全部经本判据，禁写第二份。 |

## 依赖的兄弟模块

[`shared/`](../../shared/module-doc.md)、[`core/`](../module-doc.md)、[`core/wiki/`](../wiki/module-doc.md)
