# core / jev

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

JEV（jev）事件与判定。

**入口建议**：先读 [`JevShadow.scala`](JevShadow.scala)（本模块最大文件）。

## 文件（8）

| 文件 | 说明 |
|---|---|
| [`JevAllocatorPort.scala`](JevAllocatorPort.scala) | Narrow port of the JeV allocation face (Face C / P2). ==Why a port and not a direct call== |
| [`JevConfigReader.scala`](JevConfigReader.scala) | Hot-read snapshot of the `jev` block (Face C / P2). Mirrors `PluginsConfig.enabled` (`core/plugin/PluginsConfi |
| [`JevDeclarationDuty.scala`](JevDeclarationDuty.scala) | Declaration-duty semantics (JeV integration Face B① / P3, per the #8 adjudication). |
| [`JevFallback.scala`](JevFallback.scala) | The typed outcome of an allocation attempt (Face C / P2). "Fall back" is a first-class result, not an exceptio |
| [`JevGate.scala`](JevGate.scala) | The coexistence gate (JeV integration Face C / P2, per the P0 #3 posture). ==What this file is== |
| [`JevShadow.scala`](JevShadow.scala) | Offline shadow evaluation of the allocation point (Face B① / P3). ==What a shadow is and why it is the right s |
| [`PluginAllocator.scala`](PluginAllocator.scala) | PluginAllocator —— **插件分配面接线端口**（jev-plugins 批 #1226；M2 注册器形态 = `DelegateJudge.DelegateJudgePort` 同款）. |
| [`PluginCatalog.scala`](PluginCatalog.scala) | PluginCatalog —— 分配面的 **CatalogEntry 目录构建单点**（jev-plugins 批 #1226）. 原文链条第②步「启用插件 → CatalogEntry 目录」：目录 = **可注入 |

## 依赖的兄弟模块

[`shared/`](../../shared/module-doc.md)、[`core/plugin/`](../plugin/module-doc.md)
