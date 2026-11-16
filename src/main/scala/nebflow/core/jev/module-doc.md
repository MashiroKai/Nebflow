# core / jev

> 模块向导（jev-retire 批 2026-11-16 收敛：JeV 专属四件已删，本模块只剩共享基础设施——分配端口/审计类型/目录，供小模型分配腿复用）。

## 功能

插件分配共享基础设施（分配端口、审计类型、能力目录）。

**入口建议**：先读 [`PluginAllocator.scala`](PluginAllocator.scala)（分配端口单点）。

## 文件（4）

| 文件 | 说明 |
|---|---|
| [`JevDeclarationDuty.scala`](JevDeclarationDuty.scala) | Declaration-duty semantics (JeV integration Face B① / P3, per the #8 adjudication). |
| [`JevFallback.scala`](JevFallback.scala) | The typed outcome of an allocation attempt (Face C / P2). "Fall back" is a first-class result, not an exceptio |
| [`PluginAllocator.scala`](PluginAllocator.scala) | PluginAllocator —— **插件分配面接线端口**（jev-plugins 批 #1226；M2 注册器形态 = `DelegateJudge.DelegateJudgePort` 同款）. |
| [`PluginCatalog.scala`](PluginCatalog.scala) | PluginCatalog —— 分配面的 **CatalogEntry 目录构建单点**（jev-plugins 批 #1226）. 原文链条第②步「启用插件 → CatalogEntry 目录」：目录 = **可注入 |

## 依赖的兄弟模块

[`shared/`](../../shared/module-doc.md)、[`core/plugin/`](../plugin/module-doc.md)
