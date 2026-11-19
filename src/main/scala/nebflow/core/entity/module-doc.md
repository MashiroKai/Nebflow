# core / entity

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

实体/词条模型与解析。

**入口建议**：先读 [`FlowDagExecutor.scala`](FlowDagExecutor.scala)（本模块最大文件）。

## 文件（8）

| 文件 | 说明 |
|---|---|
| [`AgentIdentityStore.scala`](AgentIdentityStore.scala) | AgentIdentityStore —— 智能体**身份/展示面**的引擎侧单点读写（identity-sync 批 2026-10-23）。管辖三样东西，全部是**既有设施**（本批零新存储）： |
| [`BuiltinAgents.scala`](BuiltinAgents.scala) | BuiltinAgents —— 四个收敛 agent 的**代码级唯一定义源**（builtin-def batch 2026-10-03 作者令①：「全部的四个 agent：Nebula、dispatcher、gen |
| [`EntityLoader.scala`](EntityLoader.scala) | Loads Team/Flow/Agent definitions from disk. JSON-first: new format files are `teams/<name>/team.json`, `flows |
| [`EntityTypes.scala`](EntityTypes.scala) | Runtime representation of agent.json. |
| [`FlowDagCompiler.scala`](FlowDagCompiler.scala) | Flow DAG 编译前端（#424，用户裁定 2026-08-26「以编译器的思想去设计」）。 单一编译入口：在 FlowExecute 调用返回前（0 spawn / 0 token）完成全部 |
| [`FlowDagExecutor.scala`](FlowDagExecutor.scala) | Executes a Flow DAG deterministically. Unlike the Mail-driven flow model where agents coordinate emergently, |
| [`FlowStructure.scala`](FlowStructure.scala) | Static structure analysis of a FlowDagDef — single source of truth shared by the loader (structural validation |
| [`TeamCatalog.scala`](TeamCatalog.scala) | Builds Team catalog strings for system prompt injection. |

## 依赖的兄弟模块

[`shared/`](../../shared/module-doc.md)、[`actor/`](../../actor/module-doc.md)、[`core/`](../module-doc.md)、[`neblink/sync/`](../../neblink/sync/module-doc.md)、[`prompts/`](../../prompts/module-doc.md)、[`core/flow/`](../flow/module-doc.md)、[`core/node/`](../node/module-doc.md)、[`core/tools/`](../tools/module-doc.md)
