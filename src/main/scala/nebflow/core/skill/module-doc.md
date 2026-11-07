# core / skill

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

技能服务。

**入口建议**：先读 [`SkillService.scala`](SkillService.scala)（本模块最大文件）。

## 文件（2）

| 文件 | 说明 |
|---|---|
| [`SkillAudit.scala`](SkillAudit.scala) | One agent that declares skills in its agent.json. scope = "global" or the team name. |
| [`SkillService.scala`](SkillService.scala) | Owning team (frontmatter `audience`): real team name, e.g. nebflow-project — not a namespace. |

## 依赖的兄弟模块

[`shared/`](../../shared/module-doc.md)、[`core/entity/`](../entity/module-doc.md)
