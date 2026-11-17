# agent

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

agent 运行时：主循环、会话执行、交互枢纽、子代理与崩溃恢复。

**入口建议**：先读 [`AgentSessionExecution.scala`](AgentSessionExecution.scala)（本模块最大文件）。

## 文件（45）

| 文件 | 说明 |
|---|---|
| [`AgentActor.scala`](AgentActor.scala) | Overload-class reasons: provider saturated, backoff can heal it. |
| [`AgentCompactionHandlers.scala`](AgentCompactionHandlers.scala) | 压缩/ask 收尾 handler 族:原 object AgentActor 内 handleCompactResponse / handleCompactFailure / emitAbandonedCompacti |
| [`AgentCore.scala`](AgentCore.scala) | Nebula-root-session identity criterion (tool-face identity batch B1 / taskunify 2026-09-24, single source): `a |
| [`AgentFinishTurn.scala`](AgentFinishTurn.scala) | turn 收尾族:原 object AgentActor 内 handleLlmCompleteBranch / finishTurn / finishTurnCont / markTeamBusy / markTeam |
| [`AgentFrozen.scala`](AgentFrozen.scala) | 冻结域:原 object AgentActor 内 enterErrorFrozen / enterFrozen / frozen 的实现 整体迁入本件(方法体逐字未动;frozen 行为内部的 actor 变换与自递归 |
| [`AgentIdle.scala`](AgentIdle.scala) | idle 域:原 object AgentActor 内 idle 行为的实现整体迁入本件(方法体逐字 未动;idle 内部的自递归——返回下一 idle behavior——保持原逻辑,解析到本 |
| [`AgentLibrary.scala`](AgentLibrary.scala) | Get a single agent by name. Converged names resolve from code only. |
| [`AgentPingpong.scala`](AgentPingpong.scala) | 刀一②（2026-10-28 无感压缩批）：fork 内 send 级重试拍数（每拍内部已遍历整条 候选链——「跨供应商重试」由链本身承担，本拍级是链全灭后的重投）与拍间隔。 |
| [`AgentProcessing.scala`](AgentProcessing.scala) | processing 域:原 object AgentActor 内 processing 行为、mail 检查助手 (hasUsedMail / handleMissingMail)、supervisor 重启助手(r |
| [`AgentRegistryEmit.scala`](AgentRegistryEmit.scala) | Direct wsSend for use inside Fiber context (pipeLlmCall, pipeToolExecutions). Do NOT call from receive handler |
| [`AgentSession.scala`](AgentSession.scala) | Session management helpers extracted from AgentActor. Handles dedup, sessionBusy signaling. |
| [`AgentSessionExecution.scala`](AgentSessionExecution.scala) | Emergency fallback: non-LLM rule-based compaction (extracted 2026-08-22 Write-only loop batch). Strips tool re |
| [`AgentStreamPipelines.scala`](AgentStreamPipelines.scala) | WS 流事件合批管道（原 streamEmitter 内联实现，2026-09-07 抽出并修复 park 饿死）。 缺陷（B-after-A 冒烟悬案，2026-09-07 插桩实证）：旧实现 flush 只发生在 |
| [`AskAnswerLanding.scala`](AskAnswerLanding.scala) | AskUser 社交腿答案的**着陆面**（answer-landing 实施批 2026-10-21；设计批 `reports/20261020_123548_askuser-social-answer-inject/ |
| [`AskUserAnswerBridge.scala`](AskUserAnswerBridge.scala) | AskUserQuestion **非阻塞模式的回投桥**（工具面按角色分化批 B5/L9，2026-09-13）。 职责（规格 §2.2 D3/D4）：非阻塞模式下，hub 槽位的 `reply` 不指向「等着工具结果 |
| [`BackoffSupervisor.scala`](BackoffSupervisor.scala) | Supervises child agents and completion delivery. Stale detection uses the debt execution roundId, independently of delivery generation; stale cleanup preserves replacement actor registration. |
| [`BgInboxBackstop.scala`](BgInboxBackstop.scala) | D1 后台任务通知「持久补投」面（steward 批 2026-10-23）：[[BgInboxPort]] 的 agent 侧实现——把待补投事件 append 进目标会话的**持久注入队 |
| [`CompactionAbandon.scala`](CompactionAbandon.scala) | 压缩作业被**放弃**（用户中断 / turn 在 compact 阶段前结束）时的两面决策。 纯决策对象（无 IO、无 actor 依赖）——沿用 `TurnBoundaryDrains` 的「纯决策 + 调用点 |
| [`CompactionQueueStore.scala`](CompactionQueueStore.scala) | F2 (2026-08-30, compact-injection-shield batch 2): durable queue store for the compaction-window injection que |
| [`ContextRefresher.scala`](ContextRefresher.scala) | Unified context refresh for session-scoped resources. All sources are re-resolved every turn (EveryTurn). Mtim |
| [`DelegateBudget.scala`](DelegateBudget.scala) | DelegateBudget —（2026-10-09 作者裁定拆除）原 R11 第 4 层 wall-clock 预算的**墓碑壳**。 原机制：delegate 内核会话装配 3600s wall-clock 预算（ |
| [`DelegateRevival.scala`](DelegateRevival.scala) | DelegateRevival —— delegate 监督链**跨重启复活腿**（监督链自动恢复批 2026-10-22， 作者令；设计 `docs/design/delegate-supervision-reviva |
| [`DispatchCause.scala`](DispatchCause.scala) | Dispatch 发起方分类（freeze-schedule spec D1）：决定 pipeLlmCall gate 的行为。 Gated（默认）= 系统续跑（ToolsComplete 续轮 / finishTurn |
| [`EscalationNotifyGate.scala`](EscalationNotifyGate.scala) | 升级链通知风暴闸（v2 反推送风暴批，2026-10-28 作者报障「什么东西一直重复推送」）。 实证（stand.log 2026-10-28 19:23-20:10）：一次 provider-down 波及 root |
| [`InjectedInboxMirror.scala`](InjectedInboxMirror.scala) | 子代理收件（卡08 裁点 2，作者 2026-09-20 07:33 批「建」）—— **投递侧增量**。 == 缺口（诊断定谳）== |
| [`InjectionSource.scala`](InjectionSource.scala) | A named, mtime-tracked source of prompt injection content. File-backed sources use [[MtimeFileCache]] so uncha |
| [`InteractionHub.scala`](InteractionHub.scala) | InteractionHub — the single interaction center for the whole gateway (P2). Every agent (root or sub-agent) sen |
| [`LanguageDetector.scala`](LanguageDetector.scala) | Simple Unicode-range based language detector. Returns a human-readable language name suitable for system promp |
| [`MailboxBootSweep.scala`](MailboxBootSweep.scala) | MailboxBootSweep —— 邮箱的 **boot 对账合成腿**（终态通知 actor 邮箱化批 P1/P2； 设计 §3.4 唤醒点⑥「引擎 boot：全会话扫箱注入 + 账面合成缺失条目」+ §3.3 |
| [`MailboxDrain.scala`](MailboxDrain.scala) | MailboxDrain —— 会话邮箱的**唯一消费口**（终态通知 actor 邮箱化批 P1/P2；设计 `docs/design/20261101_090600_delegate-terminal-mailbox |
| [`MailboxProducer.scala`](MailboxProducer.scala) | MailboxProducer —— 终态类事件入箱的**生产者职责单点**（终态通知 actor 邮箱化批 P2；设计 §3.3「mailOnce 唯一入队口」）。 |
| [`MaintenanceService.scala`](MaintenanceService.scala) | Memory maintenance reminder — injected every N delegate/flow calls to prompt the main agent to consolidate and |
| [`MergeQueueBootSweep.scala`](MergeQueueBootSweep.scala) | MergeQueueBootSweep —— 合并队列（交通引导员制）的**重启对账** boot sweep （台账 #896 · 2026-10-26 作者裁定；上游前身 = 引擎加固批 2026-10-09 的 |
| [`PendingAskLedger.scala`](PendingAskLedger.scala) | PendingAskLedger — 重启孤儿对账批（2026-10-22，作者「Header 待答徽标僵尸态 · 诊断修复批」修复①）。 |
| [`PiLoopWiring.scala`](PiLoopWiring.scala) | pi-logic 接线层（轮 A，L-6=逐位点织入；计划件 [[PiLoopWiring-PLAN]] / docs 计划全文）。 == 定位 == |
| [`PingpongStateFile.scala`](PingpongStateFile.scala) | 影子相位（spec §F2）：compacting = 影子在途；s-ready = S 已产出未换入。 |
| [`PromptSections.scala`](PromptSections.scala) | Conditional system prompt sections. Each section is a self-describing block that knows its own inclusion condi |
| [`SendConfirm.scala`](SendConfirm.scala) | 出站代发动作的**用户确认**（#147 接线段，2026-09-12）—— 全仓唯一实现。 WHY（事实锚：作者 2026-09-11 裁定 U-5 / 方案件 §1）：`FriendService` 三档权限 |
| [`SessionChildCascade.scala`](SessionChildCascade.scala) | V1 (2026-09-03, 结果投递链丢失向量修复): session-delete cascade for Delegate/SubTask/Ephemeral child actors. |
| [`SharedResources.scala`](SharedResources.scala) | Shared resources available to all actors in the hierarchy. Created once in GatewayMain and passed down through |
| [`SubAgentStartupRecovery.scala`](SubAgentStartupRecovery.scala) | V2 (2026-09-03, 结果投递链丢失向量修复): startup recovery for orphan in-flight delegate tasks. |
| [`SubAgentTaskStore.scala`](SubAgentTaskStore.scala) | File-based store for SubAgentTask records. Layout: `~/.nebflow/subagent-tasks/<parentSessionId>.json` — one fi |
| [`SubagentCrashRecovery.scala`](SubagentCrashRecovery.scala) | 子代理等待三缺口批（2026-10-25，缺口②）：**checkpoint 恢复的在飞工具调用终结面**。 990bd848 卡死链实证：delegate 在 Subagent 工具相位被 Stop → supervi |
| [`ToolHeartbeat.scala`](ToolHeartbeat.scala) | 工具执行期心跳（审计 20260903 子项①）：在 `io` 运行期间每 `interval` 触发一次 `emit`——AgentCore 用它发 toolHeartbeat WS 事件喂活前端 busy |
| [`TurnBoundaryDrains.scala`](TurnBoundaryDrains.scala) | Pure turn-boundary drain decisions shared by ToolsComplete / CompactionComplete. Top-level and side-effect-fre |

## 依赖的兄弟模块

[`shared/`](../shared/module-doc.md)、[`actor/`](../actor/module-doc.md)、[`core/`](../core/module-doc.md)、[`core/compact/`](../core/compact/module-doc.md)、[`core/tools/`](../core/tools/module-doc.md)、[`core/project/`](../core/project/module-doc.md)、[`llm/`](../llm/module-doc.md)、[`core/mail/`](../core/mail/module-doc.md)、[`core/hooks/`](../core/hooks/module-doc.md)、[`neblink/core/`](../neblink/core/module-doc.md)、[`neblink/social/`](../neblink/social/module-doc.md)、[`core/delegate/`](../core/delegate/module-doc.md)、[`core/ask/`](../core/ask/module-doc.md)、[`core/flow/`](../core/flow/module-doc.md)、[`bridge/`](../bridge/module-doc.md)、[`core/node/`](../core/node/module-doc.md)、[`pilogic/`](../pilogic/module-doc.md)、[`neblink/attachment/`](../neblink/attachment/module-doc.md)、[`core/entity/`](../core/entity/module-doc.md)、[`core/skill/`](../core/skill/module-doc.md)、[`core/executor/`](../core/executor/module-doc.md)、[`core/plugin/`](../core/plugin/module-doc.md)、[`db/format/`](../db/format/module-doc.md)、[`db/`](../db/module-doc.md)、[`core/daemon/`](../core/daemon/module-doc.md)、[`core/scheduler/`](../core/scheduler/module-doc.md)、[`core/task/`](../core/task/module-doc.md)、[`core/workspace/`](../core/workspace/module-doc.md)、[`dropbox/`](../dropbox/module-doc.md)、[`neblink/sync/`](../neblink/sync/module-doc.md)
