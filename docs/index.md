---
type: index
status: current
---

# 文档库索引

本目录（`docs/`）是 Stand 项目仓的文档库（[[glossary#^vault]]）：Obsidian 兼容，图谱视图（[[glossary#^graph-view]]）自动从 wiki-link（[[glossary#^wiki-link]]）抽取引用关系（[[glossary#^citation-edge]]）。引用可精确到段落锚（[[glossary#^paragraph-anchor]]）——名词解释直达词表那一段。新文档从这里挂入。

## 按目录

**在库文档**（链接可达，图谱边有效）：

- `design/` — 设计方案、设计稿与静态样张（样张见 [[glossary#^mockup-sample]]）；含 [[wiki-graph-redesign-design.zh-CN]]（图谱极简化设计稿，伴生样张 [[design/wiki-graph-redesign-sample.html]]）、[[design/wiki-graph-paragraph-ref-design.zh-CN]]（段落级引用设计稿）、[[plugin-md-required-file-spec.zh-CN]]（plugin.md 必要文件机制 spec）、[[design/visual-report-gate-plan.zh-CN]]（机械门禁方案）、[[design/device-provenance-spec.zh-CN]]（入站消息设备标记 spec）、[[design/subagent-row-display-name-spec.zh-CN]]（面板行显示名 spec）、[[design/turn-liveness-spec.zh-CN]]（对话层活性 spec）与 [[design/delegate-supervision-revival-design.zh-CN]]（delegate 监督链自动恢复设计）。2026-10-28 画布 ask 批三件：[[design/20261028_canvas-ask-mode-design.zh-CN]]（Canvas 文件 ask mode 设计稿·前端确认前草案）、伴生样张 [[design/20261028_canvas-ask-mode-mockup.html]]（可交互效果演示）与 token 资产页 [[design/20261028_canvas-ask-mode-tokens.zh-CN]]（玻璃样式可复用规格）。
- `templates/` — 文档批范本：按 wiki-docs 纪律改写的 Obsidian 格式报告模板，见 [[console-403-diagnosis]]（诊断报告范本）与 [[console-load-perf]]（性能验证报告范本）。
- 根散件 — 运维检查单、并行会话资源规范等专项报告：[[production-ops-checklist.zh-CN]]（生产操作检查单）、[[parallel-session-resources.zh-CN]]（并行会话资源预分配规范）。

## 存目（v1.0.0 清仓移出，git 史可考）

以下条目在 v1.0.0 release 清仓时随旧库形态整体移出仓库（删除见 `Prepare v1.0.0 release` 一类提交，git 历史可找回），目标已不在本文档库内——按**存目**记录、路径一律反引号普通文本、**不作图谱链接**；若未来重新入库，再改回 wiki-link。

- `architecture/` — Jarvis Phase 1 实施规格与架构摘要：`architecture/JARVIS_PHASE1_SPEC`（实施规格书）、`architecture/JARVIS_PHASE1_SUMMARY`（架构摘要）。
- `behavior-parity/` — Rust（工作树）vs Scala 行为对比清单与审计指引：`behavior-parity/README`（清单总览与子 agent 作业指引入口）。
- `design/`（库外旧稿）— 协调与架构：`design/analysis`（多 Agent 协调方案·修正版）、`design/design`（多 Agent 协调系统设计方案）、`design/architecture-optimization-proposal`（架构优化建议）、`actor-mailbox-pattern-review`（Actor Mailbox 模式应用建议）；Flow 与上下文/记忆：`design/flow-engine`（Flow Engine 设计）、`design/flow-tree`（FlowTree 设计）、`flow-redesign-plan`（Flow 架构改造方案）、`context-memory-analysis`（上下文管理与记忆系统技术分析）、`scala-context-management-report`（Scala 版上下文管理技术报告）、`design/memory-system`（记忆系统设计）、`design-memory-v2`（记忆系统 V2 设计）；界面与工具：`design/card-rendering-protocol`（Card 渲染协议）、`design/multi-view-refactor`（多窗口状态路由重构）、`design/20260928_nodeprogress-node-task-card`（NodeProgress 设计卡）、`design/cli-design`（CLI 设计）、`agent-icon-design-guide`（Agent 图标设计指南）、`design/hook-system-demo.html`（Hook 系统交互样张）；子系统与运维：`design/ci-testing`（CI/CD 测试与自修复）、`design/git-management`（Git 管理协助设计）、`design/session-reminder`（Session Reminder 设计）、`design/telemetry-dashboard`（遥测与 Dashboard 设计）、`design/inter-agent-mail`（Inter-Agent Mail 设计）；同步与互联：`design/sync-design`（登录与多设备同步 v2）、`design/sync-mvp`（Mesh 内置互联方案）、`design/mesh-sync-v2`（Mesh Sync v2 全量同步）。
- `evidence/` — 缺陷/事件的封存证据记录：`evidence/20260929_0200_brief-transport-silent-truncation`（brief-transport 静默截断与 HTML 实体转义）。
- `issues/` — 编号 issue（`000` 起步，模板与术语表在 `issues/templates/`，入口 `issues/templates/README`）。存档：`issues/000-issue-template`（类型选择）、`issues/010-plugin-system-architecture`、`issues/011-mcp-tool-auto-discovery`、`issues/012-extensible-tool-cards`、`issues/014-chat-history-persistence-redesign`、`issues/015-merge-session-agent-actor`、`issues/016-flatten-agent-state`、`issues/017-actor-io-async-cleanup`、`issues/018-context-manage-bug`、`issues/019-edit-tool-single-mode-refactor`、`issues/020-slash-ask-command`、`issues/021-remove-stagnation-detection`、`issues/022-mcp-production-hardening`、`issues/023-decouple-core-from-agent`、`issues/024-tool-lifecycle-hooks`、`issues/025-agent-centric-architecture`、`issues/025-external-event-system`、`issues/026-unified-agent-registration`。
- `reviews/` — 评审与差异清点报告：`reviews/scala-rust-diff`（Rust vs Scala 功能差异清点）、`reviews/arch-refactor-doc-review.html`（架构重构文档评审）；`parity/` 十八个子系统对比，入口 `reviews/parity/README`。

## 写作纪律

所有产出文档遵守 wiki-docs 强制条款（Obsidian 格式 / 论文式引用 / 概念首现必释或必引用，见 [[glossary#^concept-first-use]]）；条款全文随 visual-report 插件的 wiki-docs skill 分发（仓内源 `src/main/resources/seed/plugins/visual-report/skills/wiki-docs/SKILL.md`），机械校验器 `check_wiki_docs.py` 同目录 `scripts/` 下（`--root docs` 三态退出，R1 格式 / R2 断链孤儿 / R3 概念首现）；跨文档复用概念一律先查 [[glossary]]。
