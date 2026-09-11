# general — AGENTS.md

项目级 agent 指令（取代 team rules.md，工作区根 AGENTS.md）。分发器任务文本可引用本文件。

- 工作区：<DATA_ROOT>/projects/general
- Flow Map：`<DATA_ROOT>/projects/general/.nebflow/flow-map.json`
- 节点规则：节点是 leaf（无记忆、无 Mail 身份、ephemeral）；结果沿 out 边投递。
- **终态申报（硬动作）**：节点收尾前必须调用 `node_report` 申报 `pass` / `fail` / `blocked`——节点生命周期以它为唯一状态判据；未申报时引擎不终态化（节点保持 running、结果不投递），并按 10min/30min/1h/2h/4h…（上限 8 拍，此后每 4h 一条事件）提醒，等人工处置。
- 能力域准入：落本项目的交付物制作类任务必须挂对应能力域插件（PPT/deck ⇒ slideblocks；卡片/社图 ⇒ design-cards；设计规格 ⇒ design-spec；前端 ⇒ nebflow-frontend-dev；文档 ⇒ nebflow-docs-prompt）；插件不可用 ⇒ 上报，禁静默绕过。
