---
type: entities
status: current
---

# 实体登记表

长期存在的具名对象（人名/机构/系统/产品）的**身份声明面**：一条 = 一个段落块锚 `^ent-<slug>`，形态「规范名（别名1 / 别名2） — 一句话身份 ^ent-<slug>」。本表与概念词表**分两层**、命名空间不交叉（`^ent-*` vs `^<slug>`），概念定义只在 [[glossary#^vault|词表]] 那边。

visual-report（可视化报告包 / visual report plugin） — Nebflow 官方可视化报告插件包（skills 面 17 个场景 + core/wiki-docs/motion-toolkit），本 MCP 面即它的词条层扩展。
来源：src/main/resources/seed/plugins/visual-report/plugin.json ^ent-visual-report

Nebflow（stand / Nebflow 引擎） — 本仓（Scala 3 + http4s + cats-effect）的引擎与产品名，数据根 `~/.stand`，控制台网关默认 8080。
来源：README.md / src/main/scala/nebflow/ ^ent-nebflow

neblink（互联子系统） — 设备互联子系统：Scala 侧 `nebflow/neblink/` 与 Rust 侧 `triple-star/cap/apps/stand/src/neblink/` 共用一套 relay-ws 帧契约。 ^ent-neblink

