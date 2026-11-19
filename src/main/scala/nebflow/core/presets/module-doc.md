# core / presets

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

模型预设存储与迁移。

**入口建议**：先读 [`PresetStore.scala`](PresetStore.scala)（本模块最大文件）。

## 文件（2）

| 文件 | 说明 |
|---|---|
| [`ModelChainRegistry.scala`](ModelChainRegistry.scala) | ModelChainRegistry —— preset→model-chain 迁移批（2026-10-30，作者令 08:29「做」） 的**命名链注册表**：`model-presets.json` 的 prese |
| [`PresetStore.scala`](PresetStore.scala) | A named model preset — a reusable `{preferred, fallbacks}` chain stored in `~/.nebflow/model-presets.json`. Ag |

## 依赖的兄弟模块

[`core/`](../module-doc.md)、[`shared/`](../../shared/module-doc.md)
