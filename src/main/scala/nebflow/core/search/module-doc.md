# core / search

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

检索索引服务（含 chat 适配）。

**入口建议**：先读 [`SearchIndexService.scala`](SearchIndexService.scala)（本模块最大文件）。

## 文件（3）

| 文件 | 说明 |
|---|---|
| [`CjkBigram.scala`](CjkBigram.scala) | CJK bigram 变换（xerj CJKAnalyzer 同款；stand-recall 已趟平路线照抄—— 设计稿 §4.6 R2 与 §5.4）。FTS5 unicode61 对中文退化为整句 token， |
| [`SearchIndexAdapter.scala`](SearchIndexAdapter.scala) | SearchIndexAdapter —— 数据层检索骨架的 adapter 注册面（设计稿 §4.1 接口形态 的落地；datalayer-spec 批 2026-10-30，C1）。 |
| [`SearchIndexService.scala`](SearchIndexService.scala) | SearchIndexService —— 数据层检索骨架的索引服务单例（设计稿 §4.1-4.4； datalayer-spec 批 2026-10-30，C1+C2 的服务端半边）。 |

## 子模块（1）

- [`chat/`](chat/module-doc.md) — chat 会话检索适配（写入钩子 + 指纹重建）。

## 依赖的兄弟模块

[`shared/`](../../shared/module-doc.md)
