# core / workspace

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

工作区知识库。

**入口建议**：先读 [`FileTypeRegistry.scala`](FileTypeRegistry.scala)（本模块最大文件）。

## 文件（3）

| 文件 | 说明 |
|---|---|
| [`FileTypeRegistry.scala`](FileTypeRegistry.scala) | Single source of truth for file-extension → (viewer itemType, binary) mapping (F3 P1). Replaces three parallel |
| [`KnowledgeStore.scala`](KnowledgeStore.scala) | 定义: KnowledgeStore |
| [`WorkspaceItem.scala`](WorkspaceItem.scala) | A knowledge item stored in the workspace — markdown notes, code snippets, HTML cards, etc. |

## 依赖的兄弟模块

[`shared/`](../../shared/module-doc.md)
