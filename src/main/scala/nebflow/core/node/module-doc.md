# core / node

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

Flow 节点运行器。

**入口建议**：先读 [`NodeRunner.scala`](NodeRunner.scala)（本模块最大文件）。

## 文件（1）

| 文件 | 说明 |
|---|---|
| [`NodeRunner.scala`](NodeRunner.scala) | NodeRunner 共享执行内核（统一分析方案 A，阶段 0 —— #28）。 Delegate / SubTask / flow 节点三处重复的 spawn 逻辑收敛于此： |

## 依赖的兄弟模块

[`actor/`](../../actor/module-doc.md)、[`core/`](../module-doc.md)、[`shared/`](../../shared/module-doc.md)
