# nebflow-qa — 插件使用指导（Plugin.md）

> 本文件是 nebflow-qa 插件的**包根使用指导件**（Plugin.md，插件必要文件）：**插件分配时注入给 agent 的提示词 = 本文件内容**（作者令 2026-10-21「plugin.md = 插件必要文件机制」）；SKILL.md 回归按需查阅件（面板胶囊点击打开，不再随分配整文注入）。选配判定仍看 plugin.json 的 description。**本文件是导览不是手册：与 SKILL.md 不一致时以 SKILL.md 为准并回改本文。** 文内路径均为自包根的文件相对形式。

## 1. 速览

nebflow-qa 是 Nebflow 验证域知识包：节点获得前后端五维审核、隔离真实例冒烟、资源可达性校验与验红实证/变异安全的验证纪律。适用于交付前 QA、合并前质量把关、验收条件逐项核对、代码审查、跑隔离实例冒烟等任务。

- **后端验证**：`skills/nebflow-qa-backend/SKILL.md`（五维审核：compile/test/验收逐项/代码质量三维/回归）。
- **前端验证**：`skills/nebflow-qa-frontend/SKILL.md`（隔离真实例三步验证、资源可达性实测、禁静态服务器替身、双端契约静态审核）。
- **自验 harness 方法论**：`skills/frontend-verification/SKILL.md`（wire 探针/契约夹具/Playwright 坑/结构化盲点补验）。
- **隔离冒烟法证**：`skills/isolated-smoke-verification/SKILL.md`（隔离实例搭建、kill 前 PID 验身、沙盒泄漏三信号、易失证据替代法证）。
- **验证严谨性**：`skills/verification-rigor/SKILL.md`（验红实证、变异安全、spec 载力、sbt/异步排障）。

## 2. 边界

- **只审不改**：验证节点只报告问题，不修复（Write 仅用于写验证报告）。
- 质量评分审查另配 Plugin Catalog 的 `nebflow-pipelines`（review skill）。

## 3. 组件面

无 mcp.json、无工具扩展（纯 skill 包）。
