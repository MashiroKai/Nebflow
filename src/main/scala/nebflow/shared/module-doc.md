# shared

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

跨域共享：模型、配置、路径、品牌、日志。

**入口建议**：先读 [`Defaults.scala`](Defaults.scala)（本模块最大文件）。

## 文件（40）

| 文件 | 说明 |
|---|---|
| [`AgentModelConfig.scala`](AgentModelConfig.scala) | Per-agent model configuration. The agent simply declares which models to use — a `preferred` primary and an |
| [`AttachContract.scala`](AttachContract.scala) | 附件通道冻结契约 —— **单一数值权威面**。 作者 2026-09-14 09:14 给定数（逐字：「把文件传输的上限增加到一个g。1024MB」， |
| [`Branding.scala`](Branding.scala) | L1 rebrand (2026-08-17): typed view over the brand single source `brand.conf` — repo root, packaged into the J |
| [`BrowserManager.scala`](BrowserManager.scala) | Browser fetch result. |
| [`CompactThreshold.scala`](CompactThreshold.scala) | Fixed threshold for large-context models (256k tokens). |
| [`Defaults.scala`](Defaults.scala) | Default configuration values used across the codebase. Centralized here to avoid magic numbers scattered in mu |
| [`DeviceMail.scala`](DeviceMail.scala) | 跨设备 Nebula 邮件（device-mail 批，2026-09-15 作者令 12:04 逐字）： 「给 Mail 工具加上设备参数，让 Mail 可以给设备发邮件，然后邮件直接注入给该设备的 |
| [`DropboxModels.scala`](DropboxModels.scala) | A single message in the dropbox conversation with a peer. |
| [`FileHistory.scala`](FileHistory.scala) | VS Code-style file history: snapshots file content before overwrites. Storage layout (default root follows Pat |
| [`HealthTypes.scala`](HealthTypes.scala) | Health state of a single provider+model combination. |
| [`InteractionRequestId.scala`](InteractionRequestId.scala) | 多 AskUser 并发批（#250 第五项，2026-09-13 作者裁定「6 项全补」）—— interaction requestId 的**单点生成器**。 |
| [`MemoryBudget.scala`](MemoryBudget.scala) | 记忆预算闸（memory-management-plan §2.2-3 / §3.3 / §6.2-2.2，2026-09-05 第二批机制）。 预算现行裁定（50KB/30KB 硬顶）+ 80% 软触发（40KB/24 |
| [`MemoryHygieneSignal.scala`](MemoryHygieneSignal.scala) | 生命周期记忆整理信号（memory-management-plan §2.2-2 / §6.2-2.5，2026-09-05 批次二机制五）。 两个一次性触发源，由 ContextRefresher.buildMemor |
| [`MemoryPaths.scala`](MemoryPaths.scala) | 记忆面「全局层」的路径基（单一实现，供 [[nebflow.core.tools.MemoryDirectWriteGuard]] 与 [[nebflow.core.tools.MemoryChangeNotifier] |
| [`MemorySnapshot.scala`](MemorySnapshot.scala) | 记忆写前快照（snapshot-on-write，dream-agent 批 2026-09-05）。 快照先行=硬护栏：记忆白名单写路径在落盘前必须先经过本对象 |
| [`MemoryStore.scala`](MemoryStore.scala) | Two-level memory store backed by Markdown files. Levels (personal-agent 批 2026-10-04 — memory is Nebula-only): |
| [`MemoryWriteGate.scala`](MemoryWriteGate.scala) | 记忆落盘单点闸（M4 —— 工具面收敛设计 §4 Q2-④ ④-b / M2 表 M4 行；作者 2026-09-13 裁定「M4 闸下沉 = 独立小批立刻立项」）。 |
| [`MtimeCache.scala`](MtimeCache.scala) | File content cache keyed by modification time. On every access, checks the file's mtime via os.stat (cheap sys |
| [`PeerModels.scala`](PeerModels.scala) | Device identity header for peer requests. Set by the P2P leg (`RemoteExecutor` `remote-exec` dispatch) and rea |
| [`PlaywrightFetcher.scala`](PlaywrightFetcher.scala) | Playwright browser fetcher — optional component. This object imports com.microsoft.playwright.*. If the Playwr |
| [`PowerStateTracker.scala`](PowerStateTracker.scala) | 宿主睡眠/冻结窗（墙钟毫秒，闭区间语义 `[sleepAtMs, wakeAtMs]`）。 hostresume 批 2026-09-22，设计卡 |
| [`ProtocolPayloads.scala`](ProtocolPayloads.scala) | 会话级计数器（AgentState 顶层字段——S3 跨 turn，turn 边界只清 S1 与 R 连续计数）。turnKey = state.loopTurnKey.toString（逻辑 turn 纪元： |
| [`ReadTracker.scala`](ReadTracker.scala) | Record a file read with current timestamp. Updates timestamp if already tracked. |
| [`RulesStore.scala`](RulesStore.scala) | Per-folder rules store backed by Markdown files. Storage: ~/.nebflow/folders/<folderId>.rules.md |
| [`SessionMeta.scala`](SessionMeta.scala) | 会话级压缩阈值比例覆盖**（ctxthresh 批，2026-09-15 方案 A）：键 `compactThresholdRatio` 落 `<dataRoot>/sessions/_index.json`（与 `mo |
| [`SubAgentTaskModels.scala`](SubAgentTaskModels.scala) | Persisted metadata for a sub-agent task (Delegate / SubTask). Written at spawn time, updated on completion/fai |
| [`ThinkingMode.scala`](ThinkingMode.scala) | 思考模式能力域类型（thinking-config 实施批，2026-10-28；设计 `docs/design/20261028_215449_thinking-config-research__delegate-ne |
| [`TunnelOriginMark.scala`](TunnelOriginMark.scala) | Process-self-signed tunnel-origin mark (introduced in stage C of the token retirement route): lets the gateway |
| [`askUser.scala`](askUser.scala) | 条件依赖：仅当 ref 问题的答案等于 equals 时才显示此问题 |
| [`availability.scala`](availability.scala) | 模型不可用时段判定（model-schedule-unavailable 批，2026-10-29 作者令 17:44 「下午2点到6点，我不想用zhipu和kimi的模型，因为会加倍收费。应该设置一个， |
| [`cjk.scala`](cjk.scala) | CJK 宽字符检测（供 TUI 和 Markdown 渲染复用） |
| [`config.scala`](config.scala) | 模型不可用时段规则（model-schedule-unavailable 批，2026-10-29 作者令 17:44）： `unavailableWindows` 数组的一项。时段内该 provider / 模型视为* |
| [`contracts.scala`](contracts.scala) | F-2 计数面（presdial 批 2026-09-19）：本条提醒所宣告的变化**身份** —— device 通道 = 设备**成员集合**键（deviceId 面，见 [[SystemReminders.devi |
| [`fallback.scala`](fallback.scala) | Stream-inactivity timeout (phase-2 watchdog: chunks received, then stalled). Dedicated type so classifyError c |
| [`http.scala`](http.scala) | 全局共享的同步 HTTP Backend，所有工具复用同一个实例以利用连接池。 配置要点： |
| [`logging.scala`](logging.scala) | Highlight a value with the given color for log messages. |
| [`paths.scala`](paths.scala) | Cross-platform path utilities. On Windows, os.Path("D:\\foo") fails when os.pwd is on C: drive |
| [`protocol.scala`](protocol.scala) | Injection source marker (任务 P): when a message was injected by a tool rather than typed by the user, this reco |
| [`retry.scala`](retry.scala) | 重试/退避组合子(Phase 3 去重的公共层)。本步**零调用点改动**:只新增组合子 与测试,各站点迁移由后续步逐个做;迁移纪律 = 先对照下表逐项核对语义, |
| [`terminal.scala`](terminal.scala) | ANSI 转义码和终端工具 |

## 依赖的兄弟模块

[`neblink/social/`](../neblink/social/module-doc.md)、[`neblink/core/`](../neblink/core/module-doc.md)、[`neblink/relay/`](../neblink/relay/module-doc.md)、[`prompts/`](../prompts/module-doc.md)、[`neblink/portal/`](../neblink/portal/module-doc.md)
