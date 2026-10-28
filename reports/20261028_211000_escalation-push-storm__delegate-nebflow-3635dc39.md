# 修「后台代理恢复升级事件向父会话重复推送」根因报告

作者报障 2026-10-28 20:2x：「什么东西一直重复推送？这是缺陷」。实证窗口 2026-10-28 20:08-20:21，同一 background-agent 恢复事件（reason=provider-down）向 root 会话以 system-reminder 形态重投 21+ 次（level 2→5 各级反复），每次打断父会话一个完整回合；父已裁定 restart（验证生效）后事件仍不销账、继续重投。

## 一、实证考古（stand.log，只读）

`grep "Nebula-d7c3cb54 .* error-escalated"` 逐波读数（root 会话收件日志）：

- 19:23-19:24：7 条（L1 波；19:23:18 idle 直投 `external-event`，其余 `external-event-queued`）
- 19:33:47-19:35:17：7 条（L2 波）
- 19:46:17-19:47:47：7 条（L3 波）
- 19:56:17-19:57:47：7 条（L4 波）
- 20:08:47-20:10:17：7 条（L5 波）——作者报障窗口正落在 L4/L5 波

同窗口另有 ~30 组 `error-freeze ⇄ freeze-resume`（provider-down，30s 退避拍）横跨 root + 7 个 delegate 子会话（19:14 起持续 flapping）。**「同一事件重投 21+ 次」实为 7 个同源 sibling 会话 × 4 个升级波 ≈ 28 条同族事件**——每条 (session, level) 只投一次，但事件族（same reason/payload 形态）在父侧观感为同一事件反复推送。

## 二、根因链（四面缺一即风暴）

1. **销账缺失（生命周期根因）**：升级链状态（`AgentState.execution.escalation` + registry 快照）**没有任何销账点**——全库无 `withEscalation(None)` 写点。后果：
   - 父裁定 restart → `Stop("parent-restart")` → respawn 后 provider 仍 down ⇒ 新代会话重走 3 次冻结 ⇒ **全新 L1 链再入**（事件流对「已裁定」零感知，永不绝账）；
   - flapping（恢复→再冻结）时旧链原样续推（`state.escalation` 非空 + 旧 `escalateAt` 已过期 ⇒ 扫描器 ≤30s 即刻 Escalate，10min 窗被击穿）；恢复成功数小时后再失败也续旧 level（永不落幕）。
2. **重投无节流（放大器）**：`FreezeScheduler.scan`（30s）对 registry 快照过期的 Frozen 记录**逐拍重发 Escalate**——actor 忙（processing 长轮/邮箱积压）期间陈旧 Escalate 在邮箱堆积，冻结后逐条消费 = level 连跳 + 通知连发（frozen Escalate handler 无到期复核闸）。
3. **同源无合并（③缺位）**：父忙时 `error-escalated` 逐条 append 进 `pendingEvents`（processing/frozen 两态入队口），7 个 sibling 的同波事件各占一条，turn 边界 reminder 逐条注入。
4. **总量无总闸（④缺位）**：通知面只有「单会话单级 10min 窗」（`ErrorEscalateAfterMs`），多会话同源叠加零防御。

事件产生源不是「每分钟重发」也不是「投递层重放旧队列」——是 7 条独立升级梯错拍叠加 + 上述四面缺失的复合。

## 三、改动面（最小面，全部随下一波包生效）

| 修复面 | 文件 | 内容 |
|---|---|---|
| ①销账 | `agent/AgentFrozen.scala` | 错误族恢复（CheckFreezeGate backoff-elapsed）、用户唤醒（UserInput/AskQuestion/SkillActivate）、RestartAgent、Interrupt 全路径 `withEscalation(None)` + registry 快照清 None；`AgentActor.retryFromCheckpoint`（processing/frozen 共用 Retry 腿）同款销账 |
| ①销账（裁定侧） | `core/tools/AgentControlTool.scala`、`gateway/WsSessionChatHandlers.scala` | 新增 `clearRegistryEscalation`；doRestart（Team/delegate 两支）与 WS parentRestart（Root/delegate 两支）裁定即清——「父会话执行 restart 即关闭其未决升级事件」 |
| ①销账（respawn 侧） | `agent/BackoffSupervisor.scala` | respawn 的 registry `oldRecord.copy(...)` 补 `escalation=None, frozenReason=None`（旧代升级链随死亡消失，防陈旧 escalateAt 让扫描器对新代首冻结即刻 Escalate） |
| ②同级去重 | `agent/AgentFrozen.scala`（Escalate handler） | 陈旧 Escalate 丢弃闸：`escalateAt <= 0`（用户终态）或 `now < escalateAt`（窗内重发）⇒ 零通知零升级原地丢弃（`escalate-stale-dropped` 日志留痕）；窗内每级至多投一次 |
| ②重投节流 | `core/processor/FreezeScheduler.scala` | 发 Escalate 后立即把 registry 快照 `escalateAt` 前推一个窗口——actor 未及消费期间扫描器不再 30s 重发（actor 消费后同值覆写，两写幂等） |
| ③合并 | `agent/AgentActor.scala`（`coalesceEscalationEvents`，纯函数）+ `AgentProcessing.ExternalEvent` / `AgentFrozen.ExternalEvent` 两个入队口 | 升级事件按 (source, eventType, failedSessionId) 同键在 pendingEvents **原位替换**（最新 level/payload 胜出、位置不变）；父忙期间风暴波每会话只积一条，turn 边界 reminder 天然单条携带最新 level |
| ④风暴总闸 | 新件 `agent/EscalationNotifyGate.scala` + `shared/Defaults.scala`（`EscalationNotifyWindowMs=1h` / `EscalationNotifyCapPerHour=6`）+ `agent/SharedResources.scala`（默认字段，`Ref.unsafe` 先例，存量构造零改动） | 按父会话滑窗限流：1h 内 >6 条升级通知静默吞掉（升级链状态照常推进），压制计数随下一条放行通知以摘要行回流（`[escalation-notify throttled: N earlier …]`）；闸故障降级直投 |
| 投递口单点化 | `agent/AgentActor.scala`（`sendEscalationToParent`） | L1 进入腿（notifyEscalation）与 L2+ 升级腿（frozen Escalate）统一走同一投递口——闸单点生效、消息面同构（合并键成立）、metadata 键不变 |

## 四、验证

- 新 spec `src/test/scala/nebflow/agent/EscalationNotifyStormSpec.scala` **4/4 绿**（真实 AgentActor harness + 父 probe）：
  - STORM-1/2：链进入恰通知一次（L1）→ 窗内陈旧 Escalate ×2 丢弃（零通知、level/escalateAt 原样）→ 唤醒（父裁定）销账（registry escalation=None，零新增通知）；
  - STORM-2b：扫描器发 Escalate 后 escalateAt 前推整窗（二次 scan 不重发）；
  - STORM-3：同键原位替换（异会话/普通事件逐字保留）；
  - STORM-4：cap=3 滑窗——超限压制、计数随放行首条消费归零、会话隔离。
- 回归（`sbt Test/testOnly`）：`FreezeGateSpec`（含 ER-1/2/3 升级链既有验收 + B6 扫描器）、`AgentStreamEventUsageSpec`、`AgentControlE2ESpec`、`AgentRestartSpec`、`RestartRecoverySpec`、`BgLedgerRedeliverySpec`、`DelegateBgWaitHoldSpec`、`TaskStuckWatcherSpec`、BackoffSupervisor ×3、`SubagentCrashRecoverySpec`、`AgentLlmFailRetrySpec` **合计 96 passed / 0 failed**（2 ignored=存量）；通知/注入族（ExternalEventBlocks/HeldDeliveryFlush/Injection×3/NestedDelegateNotify/TeamMemberFailureNotify/DelegateWakeContinuation/BusyConsoleInputInjection/MailBoundaryInjection）后台批结果见会话尾注。
- 纪律：未触碰在产引擎（pid 70481/6138）；本修复随下一波包生效。

## 五、边界与开放项

- 风暴闸阈值（6 条/小时/父会话）为 Defaults 常量，未接 nebflow.json 热更（如需运行中调参可照 `freezeScheduleRef` 先例后续补）。
- `errorEscalated`（无父 → 用户终态 WS 卡片）路径未设总闸：用户级卡片是终态展示不打断会话回合，维持原语义。
- LLM 调用侧的 provider-down flapping 本身（30s 退避拍持续失败）属 provider 健康问题，不在本报障修复面内；本批只保证它不再转化为父会话的通知风暴。
