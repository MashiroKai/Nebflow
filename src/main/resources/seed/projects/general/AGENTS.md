# general — AGENTS.md

项目级 agent 指令（取代 team rules.md，工作区根 AGENTS.md）。分发器任务文本可引用本文件。

- 工作区：<DATA_ROOT>/projects/general
- Flow Map：`<DATA_ROOT>/projects/general/.nebflow/flow-map.json`
- 节点规则：节点是 leaf（无记忆、**无消息工具**（工具面不挂 `Mail`）、ephemeral）；结果沿 out 边投递、终态走 `node_report`。消息原语（`Mail`）只给 Nebula（→ 项目分发器）与项目分发器（→ `Nebula` / `node:<id>`）。
- **终态申报**：节点收尾前调用 `node_report` 申报终态（值域、未申报语义与提醒阶梯见 `node_report` 工具 description 与引擎 always-on 段，本文件不复述）。
- 能力域准入：落本项目的交付物制作类任务必须挂对应能力域插件——按**当前有效 Plugin Catalog** 解析（**若本会话首条消息含 Catalog 段**：以该段为准，后续提醒以新者为准；**若不含 Catalog 段**：该解析通道对本会话不成立，不得据此推断能力），点名一律用 Catalog 中的插件 `name`，**禁硬编码插件名**（交付物域如 PPT/deck、卡片/社图、设计规格、前端、文档）；插件不可用 ⇒ 上报，禁静默绕过。
