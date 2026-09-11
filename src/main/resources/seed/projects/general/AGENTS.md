# general — AGENTS.md

项目级 agent 指令（取代 team rules.md，工作区根 AGENTS.md）。分发器任务文本可引用本文件。

- 工作区：<DATA_ROOT>/projects/general
- Flow Map：`<DATA_ROOT>/projects/general/.nebflow/flow-map.json`
- 节点规则：节点是 leaf（无记忆、无 Mail 身份、ephemeral）；结果沿 out 边投递。
- 能力域准入：落本项目的交付物制作类任务必须挂对应能力域插件（PPT/deck ⇒ slideblocks；卡片/社图 ⇒ design-cards；设计规格 ⇒ design-spec；前端 ⇒ nebflow-frontend-dev；文档 ⇒ nebflow-docs-prompt）；插件不可用 ⇒ 上报，禁静默绕过。
