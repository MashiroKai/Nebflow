# core / wiki

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

词条 / wiki 图。

**入口建议**：先读 [`WikiService.scala`](WikiService.scala)（本模块最大文件）。

## 文件（4）

| 文件 | 说明 |
|---|---|
| [`MdHtmlGate.scala`](MdHtmlGate.scala) | 白名单封闭枚举（四扩展名，小写存储）。禁写第二份——ask 侧与词条侧一律 经 [[isMdHtml]]，前端 `web/js/mdHtmlGate.js` 的 `MD_HTML_EXTENSIONS` 与 |
| [`TermIndex.scala`](TermIndex.scala) | Incremental term registry index（B5-2 文档增强腿，2026-11-01）。 改造前：`GET /api/wiki/term` 走 `graph(project)`——那是**整个 va |
| [`WikiIndex.scala`](WikiIndex.scala) | Pure parsing layer for the project wiki graph (Obsidian-compatible vaults). A vault is a `docs/` folder inside |
| [`WikiService.scala`](WikiService.scala) | Wiki graph service — turns every registered project's `docs/` vault (Obsidian-compatible) into per-project doc |

## 依赖的兄弟模块

[`core/`](../module-doc.md)、[`core/project/`](../project/module-doc.md)、[`core/tools/`](../tools/module-doc.md)、[`shared/`](../../shared/module-doc.md)
