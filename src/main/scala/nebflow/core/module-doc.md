# core

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

核心域：工具、项目/Flow、会话、处理器、插件、MCP、调度等（33 个子模块）。

**入口建议**：先读 [`SessionStore.scala`](SessionStore.scala)（本模块最大文件）。

## 文件（44）

| 文件 | 说明 |
|---|---|
| [`AgentRuntimePort.scala`](AgentRuntimePort.scala) | `agent.SharedResources` 的窄视图(严格DAG第⑥步第三批A)。core 侧(flow/entity/node/ processor/project/tools 家族)经本视图消费:会话存储(cor |
| [`AtomicJson.scala`](AtomicJson.scala) | Crash-safe file writes for JSON (or any small text) state files. Writes go to a unique temp file in the target |
| [`AutoStartService.scala`](AutoStartService.scala) | Shared auto-start (start-on-login) logic for the `nebflow autostart` CLI and the settings-panel WS toggle (F2) |
| [`CanvasTabStore.scala`](CanvasTabStore.scala) | 服务端持久化的 Canvas 标签 v2 存档（F1 根治，2026-08-30）。 作者报告「JVM 重启后标签页（含 file 文档标签）丢失」——根因=浏览器 |
| [`CredentialFileAcl.scala`](CredentialFileAcl.scala) | Owner-only access control for on-disk credential files (device.json, …). WHY (Q2 ruling, 2026-09-11): `Files.s |
| [`DeviceMailAckPort.scala`](DeviceMailAckPort.scala) | `neblink.DeviceMailAck.await` 的窄投影(严格DAG第⑥步第二批 R4):DeviceMailAck 本体不下沉(import core.tools.RelayExecAudit、进程内全局  |
| [`FileChangeLedger.scala`](FileChangeLedger.scala) | How an agent-visible file changed (mirrors the delivery contract keys). |
| [`FriendRosterPort.scala`](FriendRosterPort.scala) | `neblink.FriendRoster` 的窄投影(严格DAG第⑥步第二批 R10):FriendRoster 留驻 neblink(依赖 core.tools.ToolError,属好友域词汇单点),core 侧( |
| [`FriendsSeal.scala`](FriendsSeal.scala) | friends-seal latch (friendseal batch, 2026-09-25; DEFAULT FLIPPED by the 功能开放批， author order 2026-10-30 18:22  |
| [`GitWorktrees.scala`](GitWorktrees.scala) | Read-only Git facts for the engine's seat faces: branch/HEAD readings, dirty sets, ancestry, status digests. |
| [`Guardrails.scala`](Guardrails.scala) | 专用化改造·轨道二（引擎护栏）— team task #5（设计基线 dedicated-agents-taxonomy 设计件 §C）。 |
| [`HeadlessMode.scala`](HeadlessMode.scala) | NEBFLOW_HEADLESS=1 — deterministic benchmark mode, read once at JVM start. Benchmark containers (Terminal-Benc |
| [`InstallLayout.scala`](InstallLayout.scala) | Locates the jpackage app-image payload and the bundled dependencies (Team #11, beta.53). |
| [`JvmRequirement.scala`](JvmRequirement.scala) | JDK baseline gate — the gateway requires Java 21 or newer. This object is deliberately **dependency-free**: it |
| [`LlmLogWriter.scala`](LlmLogWriter.scala) | Writes LLM request/response logs in Router-compatible JSONL format. Output directory: <dataRoot>/logs/router/  |
| [`LlmRuntimePort.scala`](LlmRuntimePort.scala) | `llm.LlmInterface` 的窄投影(严格DAG第⑥步第一批):core 侧的实际调用面 = 在飞查询(inflightFor / inflightCount)+ 会话级取消与 transport abort |
| [`McpToolGate.scala`](McpToolGate.scala) | 操作风险分级（spec §2.1）。判据 = 动作损害面 × 可逆性 × 目标边界。 |
| [`MemoryRecallDaemon.scala`](MemoryRecallDaemon.scala) | 记忆检索库自动入库守护（memory-recall 批 2026-10-22）。 作者令：「这个进库 应该要是我们使用stand自动的能力」——主 agent 会话消息落盘后 |
| [`ModelChainMigration.scala`](ModelChainMigration.scala) | One-time migration from named model presets to per-role model chains. Runs at gateway boot and is idempotent:  |
| [`OnboardingArtifacts.scala`](OnboardingArtifacts.scala) | Onboarding 产物落盘单点（personal-agent 批 2026-10-04，方案 §2.1 / 作者第 2 条）。 问卷答案 → 两份文件的映射（真源 = v2 交互设计说明的「五条映射表」，逐字对齐）： |
| [`OnboardingModelStep.scala`](OnboardingModelStep.scala) | Onboarding 模型步骤的引擎侧单点（personal-agent 批 2026-10-04，作者第 2 条）。 与设置系统对齐**（硬要求）：不再自由填空——从**已配置 provider** 检出可用模型 |
| [`OnboardingService.scala`](OnboardingService.scala) | First-run onboarding state machine (F3, backend slice). State lives in `<dataRoot>/onboarding.json`: |
| [`PathParamCodec.scala`](PathParamCodec.scala) | The ONE source of truth for the "filesystem path ⇄ URL query parameter" pair (imgref batch, 2026-09-18 作者令). |
| [`RestartHelper.scala`](RestartHelper.scala) | Cross-platform restart helper for remote updates. When triggered, spawns a detached background process that: |
| [`SchemePolicy.scala`](SchemePolicy.scala) | Name-aware model-chain resolution policy (single source of truth for "which model chain does agent X run on"). |
| [`SessionStore.scala`](SessionStore.scala) | Max chars of a tool's `content` field to persist in `.ui.json` (the frontend rendering history). Aligned with  |
| [`SoulMigration.scala`](SoulMigration.scala) | `Soul.md` 启动一次性迁移腿（personal-agent 批 2026-10-04，方案 §2.1 / 裁定①）。 搬什么**：`~/.nebflow/agents/Nebula/memory.md` → `~ |
| [`ToolsLogWriter.scala`](ToolsLogWriter.scala) | Structured tool-execution JSONL log（审计 20260903 §5 方案 B）. Output directory: <dataRoot>/logs/tools/ — dataRoot  |
| [`UsageAggCache.scala`](UsageAggCache.scala) | Additive token counters — the merge unit of the incremental aggregate cache. Every field is a plain sum over r |
| [`UsageRecordStore.scala`](UsageRecordStore.scala) | Structured LLM usage record — the input row of the token-consumption dashboard. Captured at every successful L |
| [`UsageTracker.scala`](UsageTracker.scala) | Load the last persisted pattern from disk without recomputing from the log. |
| [`Whitelist.scala`](Whitelist.scala) | Shared whitelist filtering logic for agent catalog visibility. |
| [`WindowsDepProbe.scala`](WindowsDepProbe.scala) | Boot-time dependency probe (Team #11 item ④): say out loud, at startup, which Windows toolchain pieces (Git Ba |
| [`WsTimeoutFilter.scala`](WsTimeoutFilter.scala) | Suppresses scary-but-harmless WebSocket disconnection stack traces caused by OS sleep / wake. When the compute |
| [`capabilities.scala`](capabilities.scala) | 统一注册表(`SharedResources.agentRegistry`)的窄能力投影(D 步)。core 当前只 消费一个行为面:hot-restart 五域判定 F3 的「活跃 turn 会话」读数(HotRest |
| [`errors.scala`](errors.scala) | 定义: NebflowError |
| [`filewatch.scala`](filewatch.scala) | Stat only files present in the previous snapshot. O(n) where n = snapshot size. |
| [`handlers.scala`](handlers.scala) | — |
| [`permissions.scala`](permissions.scala) | wire 契约的**三档显式值域** —— 写入口（WS `setSafetyMode`、REST `PUT /api/safety/mode`）的白名单。写入口**不得**用 `fromString` 的静默兜底 |
| [`ports.scala`](ports.scala) | `gateway.SessionStore` 的窄投影(C 步倒置;D 步增补落盘 flush 两成员)。core 的 消费面:会话元数据与活跃会话 id(scheduler/ScheduledTaskActor)、UI |
| [`ratelimit.scala`](ratelimit.scala) | 定义: RateLimiter |
| [`reminders.scala`](reminders.scala) | Plugin-surface change payload: the rendered `# Plugin Catalog` section as it was last visible to the session ( |
| [`repl.scala`](repl.scala) | 定义: Repl |
| [`replUi.scala`](replUi.scala) | REPL 与 UI 层的桥接接口 |

## 子模块（33）

- [`ask/`](ask/module-doc.md) — Canvas ask 模式：会话注册表、活体检索源、提示缓存与投递面。
- [`compact/`](compact/module-doc.md) — 上下文压缩、工具结果 TTL、历史归档。
- [`daemon/`](daemon/module-doc.md) — 外部守护进程生命周期（声明托管、启停、健康握手）。
- [`delegate/`](delegate/module-doc.md) — 跨会话 delegate 任务：分发、回收、合并与设备腿。
- [`device/`](device/module-doc.md) — 设备信息缓存（进程级）。
- [`entity/`](entity/module-doc.md) — 实体/词条模型与解析。
- [`executor/`](executor/module-doc.md) — 外部执行器：外部会话运行与准备腿。
- [`flow/`](flow/module-doc.md) — 事件/邮件流与投递去重。
- [`hooks/`](hooks/module-doc.md) — hook 配置加载与引擎。
- [`hotrestart/`](hotrestart/module-doc.md) — 热重启编排：intent、后继门、端口让渡、安装指针。
- [`hotupdate/`](hotupdate/module-doc.md) — 统一更新编排：更新请求、七相位执行、回滚。
- [`index/`](index/module-doc.md) — 检索索引（任务/会话）增量维护。
- [`jev/`](jev/module-doc.md) — JEV（jev）事件与判定。
- [`mail/`](mail/module-doc.md) — 会话邮箱（SessionMailbox）。
- [`mcp/`](mcp/module-doc.md) — MCP 管理、MCP 工具门与注册。
- [`msgtokens/`](msgtokens/module-doc.md) — 消息身份牌注册表。
- [`node/`](node/module-doc.md) — Flow 节点运行器。
- [`plugin/`](plugin/module-doc.md) — 插件注册表、信任状态、目录扫描。
- [`presets/`](presets/module-doc.md) — 模型预设存储与迁移。
- [`processor/`](processor/module-doc.md) — 处理器：循环守卫、卡死看护、冻结调度、投递看门狗。
- [`project/`](project/module-doc.md) — 项目/Flow Map：节点引擎、任务账本、合并队列、崩溃恢复（最大子模块）。
- [`sandbox/`](sandbox/module-doc.md) — 沙箱配置与运行时。
- [`schedule/`](schedule/module-doc.md) — 冻结时段调度（FreezeSchedule）。
- [`scheduler/`](scheduler/module-doc.md) — 定时任务：服务、存储、周报生成。
- [`search/`](search/module-doc.md) — 检索索引服务（含 chat 适配）。
- [`secrets/`](secrets/module-doc.md) — 机密存储。
- [`seed/`](seed/module-doc.md) — 冷启动最小种子集。
- [`skill/`](skill/module-doc.md) — 技能服务。
- [`task/`](task/module-doc.md) — 文件任务存储。
- [`tools/`](tools/module-doc.md) — 工具注册表与内置工具（最大子模块，83 个文件）。
- [`util/`](util/module-doc.md) — 小工具函数。
- [`wiki/`](wiki/module-doc.md) — 词条 / wiki 图。
- [`workspace/`](workspace/module-doc.md) — 工作区知识库。

## 依赖的兄弟模块

[`shared/`](../shared/module-doc.md)、[`core/tools/`](tools/module-doc.md)、[`actor/`](../actor/module-doc.md)、[`neblink/core/`](../neblink/core/module-doc.md)、[`neblink/social/`](../neblink/social/module-doc.md)、[`core/scheduler/`](scheduler/module-doc.md)、[`core/node/`](node/module-doc.md)、[`core/plugin/`](plugin/module-doc.md)、[`neblink/identity/`](../neblink/identity/module-doc.md)、[`core/presets/`](presets/module-doc.md)、[`core/flow/`](flow/module-doc.md)、[`db/format/`](../db/format/module-doc.md)、[`db/`](../db/module-doc.md)、[`neblink/relay/`](../neblink/relay/module-doc.md)
