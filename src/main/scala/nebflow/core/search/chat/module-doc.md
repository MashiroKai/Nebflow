# core / search / chat

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

chat 会话检索适配（写入钩子 + 指纹重建）。

**入口建议**：先读 [`ChatSearchAdapter.scala`](ChatSearchAdapter.scala)（本模块最大文件）。

## 文件（1）

| 文件 | 说明 |
|---|---|
| [`ChatSearchAdapter.scala`](ChatSearchAdapter.scala) | ChatSearchAdapter —— 聊天检索面 adapter（datalayer-spec 批 2026-10-30，C1）。 检索源 = `<dataRoot>/sessions/<sid>.ui.json`（ |

## 依赖的兄弟模块

[`core/search/`](../module-doc.md)、[`shared/`](../../../shared/module-doc.md)
