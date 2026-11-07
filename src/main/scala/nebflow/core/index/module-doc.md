# core / index

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

检索索引（任务/会话）增量维护。

**入口建议**：先读 [`TaskSearchIndex.scala`](TaskSearchIndex.scala)（本模块最大文件）。

## 文件（4）

| 文件 | 说明 |
|---|---|
| [`EmbeddingService.scala`](EmbeddingService.scala) | EmbeddingService —— OpenAI 兼容 embeddings 端点客户端（task-rag 批 #1227 / §Batch 7）。 |
| [`TaskSearchIndex.scala`](TaskSearchIndex.scala) | TaskSearchIndex —— 任务检索索引（task-rag 批 #1227 / §Batch 7）。 ==索引源（原文口径）== |
| [`TaskSearchRerank.scala`](TaskSearchRerank.scala) | TaskSearch 重排注册器端口（task-rag 批 #1227 / §Batch 7「可选 Jev 重排」）。 分层纪律（core 零 `nebflow.llm` import——`core/jev/JevAll |
| [`VectorStore.scala`](VectorStore.scala) | VectorStore —— 检索向量落盘（task-rag 批 #1227 / §Batch 7）。 单文件 `~/.nebflow/index/vectors.json`（`PathUtil.dataRoot / " |

## 依赖的兄弟模块

[`shared/`](../../shared/module-doc.md)、[`core/project/`](../project/module-doc.md)、[`core/delegate/`](../delegate/module-doc.md)、[`core/`](../module-doc.md)
