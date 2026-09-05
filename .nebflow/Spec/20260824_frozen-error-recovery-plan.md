# 冻结机制应用于错误恢复 · 设计方案（修订 v2）

> **日期**：2026-08-24 · **类型**：阶段文档（设计分析，只分析不实施）
> **需求原话**（用户 2026-08-24）：*「冻结设计是一种状态保存很好的机制，可以应用到错误恢复——网络中断、LLM 失败、软件崩溃等场景，用『冻结』的语义（状态保存 + 恢复续跑）实现优雅的错误恢复。」*
> **代码依据**：Nebflow 主仓 `/Users/dev/Claude code/Nebflow`，本任务全部 Read/Grep 验证（行号标注见文）。

**修订记录**：
- **v1**（2026-08-24，Explorer @5dccbe1，已 commit）：核心机制——FreezeReason 泛化 + 冻结骨架复用 + 三缺口补点（P0 LlmFailed→ErrorFrozen；P1 根 agent checkpoint + RecoveryScheduler；P1 断线假超时）。
- **v2**（2026-08-24，本版）：按用户两点反馈修订——① 错误恢复 UI 与冻结**同族但可区分**（§4.2-4.4 五维可断言规格，废弃 v1「reason 只改文案与点缀色」）；② 补**父 agent 手动重启路径**（§5 升级链/入口/超时/边界）。联动修订：§2 场景表、§3.3 冻结重试上限、§3.5 红线、§6 实施计划、§7 验收。

---

## 1. 现状梳理

### 1.1 冻结功能（时间表驱动暂停）——已被验证的状态保存范式

冻结的现有实现已经完整解决了「暂停 + 保存 + 续跑」的工程问题，是错误恢复可复用的骨架：

| 能力 | 实现 | 位置 |
|---|---|---|
| 冻结判定 | `FreezeSchedule.eval` 纯函数，黑名单语义（#337），跨午夜段，`nextChangeAt` 真实翻转点 | `core/schedule/FreezeSchedule.scala` L131-151 |
| 单点闸门 | `AgentActor.pipeLlmCall` shadow 头部 gate：冻结时段内系统 dispatch 不调 LLM → `enterFrozen`（F2 事实：AgentCore 内部递归只在 gate 放行后执行，单 choke point） | `AgentActor.scala` L2747-2794、L2821-2832 |
| 状态保存点 | **F1**：工具结果在下一轮 dispatch **之前**已 forkTurn 持久化（`persistIfSession` → `saveMessagesForSession`）；冻结 = 纯内存等待，崩溃不丢结果 | `AgentActor.scala` L1502-1507、`AgentCore.scala` L67-70 |
| 独立 behavior | `frozen` 独立于 `processing`（D3）：天然隔离 20+ processing 交互 case；stale 消息丢弃、已持久化状态不丢 | `AgentActor.scala` L2847+ |
| 恢复驱动 | `CheckFreezeGate`（FreezeScheduler 30s 轮询 / 配置热更即时 scan）+ 用户输入唤醒（`UserInput.clientMessageId.isDefined`）+ 系统注入排队不唤醒（零 token 铁律 D2） | `AgentActor.scala` L2858-2908、`core/processor/FreezeScheduler.scala` |
| 卡死豁免 | `AgentStatus.Frozen` ≠ Processing → TaskStuckWatcher 天然不判卡死（F5，watcher 零改动） | `TaskStuckWatcher.scala` L118、`protocol.scala` L603-609 |
| UI 呈现 | `frozen`/`agentFrozen` WS 事件 + 输入框 sapphire 冷色 tint + 状态栏「已冻结 · HH:mm 恢复」+ 唤醒 hint + 停流超时计时器（F8） | `main.js` L341-376、`input.css` L63-73、`protocol.scala` L572-579 |

**冻结式恢复的核心洞察**：冻结 = 「在安全边界暂停、保存状态、条件满足自动续跑」。错误恢复需要**完全相同的三段式**——差别只是「暂停原因」从「时间表」变成「错误条件」、恢复条件从「出冻结段」变成「错误消除」。

### 1.2 LLM 错误处理（重试 / 降级 / 失败）

| 环节 | 现状 | 位置 |
|---|---|---|
| 错误分类 | `Fallback.classifyError`：Transient（Timeout/ConnectionReset/RateLimit/Overloaded/ServerError/部分 Unknown）vs Permanent（Auth/ModelNotFound/Format/EmptyStream）vs Fatal（ContextOverflow/Unknown-fatal） | `llm/fallback.scala` L81-156 |
| provider 链降级 | `tryProviderWithFallback` 沿候选链 failover，链级 MaxRetries=1，指数退避 1s→10s | `llm/fallback.scala` L177+ |
| 单 turn 重试预算 | 429/529 overload 类：agent 层自动重试 **1 次**（OverloadRetryMax=1），退避 2s→10s、≥5s、+jitter；每 turn 总调用 ≤ MaxTurnLlmCalls=4（`TurnBudgetExceeded` = Permanent，fail-fast 防重试放大） | `AgentActor.scala` L1232-1256、`llm/fallback.scala` L44、L72 |
| 非 overload 类 transient | **fail-fast 不重试**（注释明示：全量历史重发 ~250k tokens/96% cache read，重试大多烧 token 不治愈） | `AgentActor.scala` L1215-1231 |
| 预算耗尽 → fatal | error WS 事件 + Done + 先 `saveMessagesForSession` 落盘 + `AgentStatus.Error` + 债务清偿 `Failed(retryable=true)` + parent `ExternalEvent(failed)` → 回 idle | `AgentActor.scala` L1257-1374、L1290-1300 |
| 全 provider Down | `waitForAnyUp` 阻塞等待（2min probe 周期 + 30s probe 超时），超时 → `AllProvidersDownTimeout` → turn 失败 | `llm/HealthMonitor.scala` L118-150、`fallback.scala` L30 |

**缺口 ①**：非 overload 类 transient（连接重置/超时/未知瞬态）与「预算耗尽」都是**一锤子死亡**——turn 以红色 error-card 终态收场，用户需手动重发消息重新拉起。provider 2 分钟后恢复也**不会自动续跑**。

### 1.3 崩溃与卡死恢复（let-it-crash，2026-08-19 裁定）

| 场景 | 现状 | 位置 |
|---|---|---|
| 子 agent 崩溃 | BackoffSupervisor death-watch → 指数退避重启（5s→60s，maxRestarts=2/5min）→ **loadMessages 断点续跑**（@c85a2655：恢复会话消息注入 childSpawnFn，不重注入原始 prompt）+ 发 `[system] continue` 指令；熔断后 `notifyParentAndStop(failed)` | `agent/BackoffSupervisor.scala` L173-301 |
| 卡死（活着不动） | TaskStuckWatcher：Processing + 10min 无活动 → Stop → BackoffSupervisor 重启路径 → 升级硬取消在飞 LLM → 兜底经 supervisor 发 `Cancelled` 释放父 barrier；根 agent 只广播 taskStuck 由用户决定 | `core/processor/TaskStuckWatcher.scala` L130-249 |
| 根 agent 崩溃/进程重启 | **无 turn 级自动恢复**（F11 事实）：`ensureRootAgent` 按需加载 history 作 initialMessages，turn 中断后用户须重新发消息；`ResumeTurn` 命令已定义但无 handler；FlowTreeActor 恢复路径 no-op | `WebSocketRoutes.scala` L95-147、`protocol.scala`（F11 记录于 freeze-spec §1.1） |
| Retry checkpoint | `state.lastDispatch` 已记录最后 dispatch 类型（LLM 调用 / 工具执行 + ConsumeResult），`Retry` 命令从 checkpoint 重派 | `AgentActor.scala` L1531-1546、L2991-3002 |

**缺口 ②**：崩溃/重启后的「恢复」是**即时重启续跑**，没有「暂停 + 原因可见」层——崩溃瞬间流静默停止，前端靠流超时（600s+30s）才提示，用户不知道「发生了什么、会不会恢复」。根 agent 与进程重启场景则**根本没有恢复**。

### 1.4 网络中断（客户端断线）

| 环节 | 现状 | 位置 |
|---|---|---|
| 断线重连 | ws.js 指数退避重连（BASE→MAX）+ heartbeat ping/pong 5s 检测 + wake 检测 + cookie→?token fallback | `web/js/ws.js` L205-235、L350-364、L578-593 |
| 后端行为 | **turn 服务器端继续跑**（WS 只是广播通道，无客户端时事件丢弃、turn 不停） | `WebSocketRoutes.scala` broadcast 语义 |
| 重连恢复 | 前端从后端 sessionStore 拉 `historyPage` 重建 DOM；客户端 queueMessage 排队断线期间输入 | `WebSocketRoutes.scala` L100、`input.js` L608-613 |

**缺口 ③**：断线期间事件到不了前端 → 客户端流超时计时器（600s+30s，`main.js` L320-334）可能在 turn 实际完成/失败后**误报超时**；断线期间工作过程完全不可见；重连后若 turn 已失败，用户看到的是断裂的红色 error-card，无「当时发生了什么、现在能否续跑」的中间态。

### 1.5 缺口汇总

| # | 缺口 | 用户体验现状 |
|---|---|---|
| G1 | LLM transient 耗尽 → turn 死亡，无条件恢复自动续跑 | 红色 error-card，手动重发 |
| G2 | 全 provider Down → turn 失败（即使 2min 后恢复） | 失败终态，不自动续跑 |
| G3 | 崩溃/重启（根 agent、进程重启）无恢复、无暂停态 | 流静默停止 → 假超时 / 需手动拉起 |
| G4 | 错误终态无「暂停 + 原因 + 预计恢复」中间态 | 终态死卡，不可视 |
| G5 | 客户端断线假超时 + 断线过程不可见 | 误报「超时」，过程黑盒 |
| G6（v2 新增） | 自动恢复失败（冻结 3 次 / supervisor 熔断）后**无父干预路径**：子 agent 直接 fatal；team 成员错误无恢复载体（无 supervisor、AgentControl 对 Team 只读、Manager 无管控工具）；根 agent 错误只能用户重发 | 错误无人决策、无人重启，只能手动重发或干等 |

### 1.6 父级语义现状（v2 新增，升级链的事实依据）

| 事实 | 现状 | 位置 |
|---|---|---|
| 父子关系数据 | `AgentRecord.parentRef`（Mail 语义/向上诊断）+ `parentSessionId`（SubAgentTaskStore 文件键）+ `rootSessionId`（权限桶） | `protocol.scala` L225-273 |
| 子 → 父终态通知 | BackoffSupervisor `notifyParentAndStop`：`ExternalEvent(source=delegate/subtask, eventType=failed/cancelled, metadata{failedSessionId, retryable, failureType})` → 注入父上下文 + barrier 释放 | `agent/BackoffSupervisor.scala` L319-361 |
| team 成员父 = 调用者 | Mail-spawned team agent：`parentRef = ctx.agentActorRef`（Manager/Nebula），**无 BackoffSupervisor**（仅 death watch 清 ghost） | `core/tools/MailTool.scala` L1030-1068、L1087+ |
| team 成员崩溃恢复载体 | 会话激活入口以 `initialMessages = history` 重建 actor + mail-queue drain（Mail 路径自带断点恢复语义） | `core/tools/MailTool.scala` L1030-1085 |
| 管控工具授权 | AgentControl 是 **Nebula 专用**（`NebulaExclusiveTools`）；Team Leads 只有 TaskCreate/TaskUpdate，**Manager 无法管控自己的成员** | `agent/AgentCore.scala` L1653-1660 |
| 管控白名单 | cancel: Delegate/SubTask/Ephemeral；restart: Delegate(ephemeral)/SubTask；**Team 只读**（"killing one mid-collaboration breaks the team state machine"）；Flow → cancelFlow；Root/Plan 自杀守卫；rootSessionId 同桶 | `core/tools/AgentControlTool.scala` L88-110、L162-200 |
| 用户面板 | managePanel `OPERABLE_KINDS = Delegate/SubTask/Ephemeral`，Team/Flow/Root 灰禁 + tooltip；stop→cancelAgent、retry→restartAgent(soft) | `web/js/managePanel.js` L28-48、L122-146 |
| watcher 分工 | 有 parentRef 的子 agent 卡死 → 自动 Stop 重启；Team → attention 广播（只读）；无 parentRef（root）→ 广播由用户决策 | `core/processor/TaskStuckWatcher.scala` L138-249 |

**关键事实**：team 成员**没有 supervisor**，AgentControl.restart 对它们会拒绝（"no supervisor on record"）；但 MailTool 的会话激活入口本身就是「以 history 重建 actor」的断点恢复载体——父重启 team 成员不需要新机制，**复用 Mail 激活入口**即可。

---

## 2. 场景覆盖表（场景 × 现状处理 × 冻结式恢复设计）

| 场景 | 现状处理 | 冻结式恢复设计 | 优先级 |
|---|---|---|---|
| LLM 失败：overload（429/529） | 自动重试 1 次（2s→10s），耗尽 → fatal | 预算耗尽 → **ErrorFrozen(LlmTransient)**，指数退避 + UI 可见，恢复条件满足自动续跑（重试仍计入 MaxTurnLlmCalls 预算）；同 reason 连续冻结 3 次 → **升级父干预**（v2 修订，原为直接 fatal） | P0 |
| LLM 失败：连接重置/超时/未知瞬态 | fail-fast → turn 死亡 | 进入 **ErrorFrozen(Network)**，短退避（10-30s）后自动重试 1-2 次；仍失败 → 连续冻结计数，3 次 → 升级父干预 | P0 |
| LLM 失败：全 provider Down | waitForAnyUp 阻塞至超时 → turn 失败 | 进入 **ErrorFrozen(ProviderDown)**，resumeAt ≈ 探测周期（120s+jitter），HealthMonitor markUp 事件唤醒（P1）或轮询重评估（P0） | P0/P1 |
| LLM 失败：Permanent/Fatal（401/404/上下文溢出等） | fail-fast → fatal | **不冻结**——Permanent 重试无意义，维持现状（降级为错误终态，附错误详情）；Fatal 同理 | — |
| 网络中断：LLM API 不可达 | 链内 failover + fail-fast | ErrorFrozen(Network) 自动重试（同「连接重置」行） | P0 |
| 网络中断：客户端 WS 断线 | 前端重连 + historyPage 重建；后端续跑；可能假超时 | 后端续跑语义**保持不变**（冻结式恢复不打断服务器端进度）；修复断线假超时 + 重连后渲染「断线期间发生了什么」（若 turn 已进 ErrorFrozen/完成，直接呈现对应状态，P1） | P1 |
| 软件崩溃：子 agent | BackoffSupervisor 退避重启 + loadMessages 断点续跑（已成熟） | **保留**；增强：重启后 child 先入 frozen(RestartRecovery)——若 provider Down/网络中断则冻结等待而非立即续跑失败；恢复条件满足再续跑（P1） | P1 |
| 软件崩溃：根 agent / 进程重启 | 无恢复；在飞 turn 死亡 | **turn checkpoint 持久化** → 重启后 RecoveryScheduler 扫描 → 未完成 turn 以 frozen(RestartRecovery) 重建 → 条件满足续跑（P1；补上根 agent 与进程重启的恢复空白） | P1 |
| 卡死（活着不动） | TaskStuckWatcher 10min → Stop/硬取消/兜底 | **不冻结**——保持 let-it-crash 裁定（崩溃+恢复 > 慢性挂起）：卡死是「不动」不是「条件性不可用」，冻结解决不了；但 ErrorFrozen 态（≠Processing）天然豁免卡死判定，无冲突 | — |
| **自动恢复失败 → 父干预（v2 新增）** | 冻结 3 次 → fatal（v1 设计）；team 成员/根 agent 无干预入口 | **ErrorFrozen 升级链**：同 reason 连续冻结 3 次（或 supervisor 熔断）→ 通知父（agent：ExternalEvent 注入上下文；用户：WS errorEscalated + UI 干预卡片）→ 父决策 restart（断点续跑）/ cancel（终态）/ wait（保持冻结，超时向上一级）→ 父不可用逐级向上，最终用户仲裁；父 restart 消耗 supervisor 熔断预算防无限重启 | P0（链机制）/P1（team 成员 Mail 再激活 + 卡片） |
| 冻结时间表（现有功能） | frozen(Schedule) | 语义不变；成为 FreezeReason=Schedule 的特例，机制统一 | — |

---

## 3. 统一机制设计

### 3.1 核心抽象：冻结 = dispatch 边界暂停（reason 泛化）

现有 `frozen` behavior 已是「暂停在 dispatch 边界」的完整实现。统一机制 = **把「暂停原因」从隐式（只有时间表）泛化为显式枚举**，错误恢复是新增 reason 的复用，而非新造机制：

```
enum FreezeReason:
  case Schedule          // 现有时间表冻结（#337 黑名单），恢复条件=出冻结段
  case LlmTransient      // LLM transient 错误预算耗尽，恢复条件=退避到期 + provider 恢复
  case Network           // 连接重置/超时/未知瞬态，恢复条件=短退避到期
  case ProviderDown      // 全候选 provider Down，恢复条件=HealthMonitor 探测恢复
  case RestartRecovery   // 崩溃/进程重启后重建，恢复条件=条件检查通过后立即续跑
```

**关键决策 D1（复用 AgentStatus.Frozen，不新增 ErrorFrozen 状态）**：
- FreezeScheduler 按 `status == Frozen` 扫描、TaskStuckWatcher 按 `status != Processing` 豁免——复用 Frozen 则两个 watcher **零改动**；
- reason 作为**显式参数**进入 `frozen` behavior 闭包（决定 CheckFreezeGate 重评估什么条件）+ 进入 `AgentStreamEvent.Frozen` 载荷（前端呈现）+ 可选写 `AgentRecord.freezeReason`（管理面板区分显示，P1）；
- `AgentStatus.Error` 保留给真正的终态错误（Permanent/Fatal/父或用户放弃恢复）。

**触发点（复用现有单点）**：`AgentActor.pipeLlmCall` shadow gate（时间表冻结处，L2774）与 `LlmFailed` handler（L1232）——前者加 reason 判定，后者在「retryable 预算耗尽 / 非 overload transient」分支把「fatal 死亡」替换为「enterErrorFrozen」。

**转入辅助**：复用 `enterFrozen`（L2821-2832，Frozen 事件 + registry 标记 + 进入 behavior），扩展 reason/detail/retryCount 参数。进入错误冻结时**先立即 `persistIfSession` 落盘**（fatal 路径已有此动作，L1290-1300，直接复用）——保证冻结期间再崩溃不丢上下文。

### 3.2 状态保存点（沿用现有边界，不新造）

| 保存点 | 内容 | 来源 |
|---|---|---|
| dispatch 边界（主） | 工具结果 + 消息历史（F1，冻结前已持久化） | `AgentActor.scala` L1502-1507 |
| lastDispatch checkpoint | 最后 dispatch 类型（LLM / 工具执行 + ConsumeResult），恢复续跑从 checkpoint 重派 | `AgentActor.scala` L1531-1546 |
| 错误冻结进入时 | 立即补一次 `saveMessagesForSession`（含 error 信息可后续注入） | 复用 fatal 路径 L1290-1300 |
| turn checkpoint（P1 新增） | 小记录 {sessionId, turnId, reason, at}——进程重启后重建冻结态的凭据 | 新增（见 §6.2） |
| escalation 状态（v2 新增） | `EscalationInfo{level, escalateAt, awaitedParentSessionId}` 挂 AgentRecord（P0 入内存，P1 可选随 turn checkpoint 落盘） | 新增（见 §5.2） |

**恢复续跑 = 现有 Retry checkpoint 语义**：`ErrorFrozen` 恢复时按 `lastDispatch` 重派——LLM 调用 → `pipeLlmCall(state)`（重新经过 gate：条件未消除 → 再入冻结，天然闭环）；工具执行 → `pipeToolExecutions`。不引入新的续跑原语。

### 3.3 恢复驱动（三类，前两类已存在，第三类新增）

| 驱动 | 机制 | 适用 reason | 状态 |
|---|---|---|---|
| R1 轮询 | FreezeScheduler 30s 扫描 → CheckFreezeGate 重评估（frozen behavior 内按 reason 分支：Schedule → eval 时间表；LlmTransient/Network → 退避到期；ProviderDown → HealthMonitor 候选状态） | 全部 | 已存在（P0 扩展 CheckFreezeGate 分支） |
| R2 事件 | HealthMonitor `markUp`（探测成功/provider 恢复）→ 通知 FreezeScheduler 即时 scan 或直接发 CheckFreezeGate（把 waitForAnyUp 的「请求级等待」扩展为「agent 级唤醒」） | ProviderDown | 新增（P1，P0 用 R1 兜底 120s） |
| R3 用户唤醒 | 现有 UserInput(clientMessageId) 唤醒 + 注入用户消息立即 dispatch | 全部 | 已存在，零改动 |

**恢复预算与防放大**：ErrorFrozen 每次续跑递增 `llmFailRetries`/`llmCallsThisTurn`，受 `MaxTurnLlmCalls=4` 预算硬限（沿用 `TurnBudgetExceeded` = Permanent → fatal 的现有防护）。

**冻结重试上限（v2 修订）**：同 reason 连续冻结 3 次无进展 → **不再直接升级 fatal**，改为**请求父干预**（进入 §5 升级链，父决策 restart/cancel/wait；父不可用逐级向上，最终用户仲裁）。fatal/cancelled 终态只在三种情况发生：① 父/用户决策 cancel 或放弃；② 父 restart 耗尽 supervisor 熔断预算（2/5min，现有 `notifyParentAndStop("failed")` 路径）；③ Permanent/Fatal 错误（§3.5 红线 1，不冻结直接终态）。理由：条件性错误可能持续较久（provider 维护数十分钟），自动 fatal 太激进——「3 次」只触发「请求决策」，不替用户宣判死刑。

### 3.4 与现有机制的关系：统一抽象 + 补充，非替代

| 现有机制 | 关系 | 说明 |
|---|---|---|
| freeze gate / frozen behavior / FreezeScheduler | **统一抽象的主体** | reason 泛化后复用全部骨架（单点闸门、独立 behavior、轮询唤醒、用户唤醒、F5 豁免） |
| BackoffSupervisor + loadMessages 断点续跑 | **保留，作为崩溃恢复的续跑路径 + 父 restart 的执行器** | 冻结式恢复补充「何时续跑」的条件层：崩溃重启后若处于错误条件（provider Down），child 入 frozen(RestartRecovery) 等待而非立即续跑失败（P1）；父 restart 复用其 Stop→respawn 路径（§5.3） |
| HealthMonitor / waitForAnyUp | **复用 + 扩展信号语义** | markUp 从「唤醒阻塞请求」扩展为「唤醒 ErrorFrozen agent」（P1） |
| TaskStuckWatcher | **零改动** | ErrorFrozen 复用 AgentStatus.Frozen，天然豁免（F5）；卡死不冻结（let-it-crash 裁定不变）；父干预与 watcher 的分工见 §5.4 |
| AgentControl restart / Retry | **保留为手动路径 + 权限分级扩展** | 冻结中 Interrupt/Stop = 放弃续跑（D10 现有语义）；Retry 手动重派继续可用；v2 扩展：直接父可管理自己的 Team 成员（§5.3.1） |
| #346 中间过程收起 | **天然兼容** | ErrorFrozen 期间 turn 未完成 → 按「失败/中断 turn 保持展开」语义处理，不冲突 |
| MailTool 会话激活（v2 新增） | **team 成员 restart 的恢复载体** | 无 supervisor 的 team 成员，父 restart = Stop + 复用激活入口以 history 重建（§5.3.1） |

**一句话**：冻结式恢复 = 把「错误发生 → 立即死亡」改成「错误发生 → 暂停（状态已保存、原因可见、恢复条件明确）→ 条件满足自动续跑 → 自动续跑失败则请求父/用户决策」，所有骨架复用冻结功能的已验证实现，崩溃恢复（BackoffSupervisor/loadMessages/Mail 激活）作为其「重续跑」底层，HealthMonitor 作为其「恢复信号」来源，父干预作为其「自动路径耗尽」的兜底。

### 3.5 边界与防护（设计红线）

1. **Permanent/Fatal 不冻结**：重试无意义，维持现状降级——冻结只用于「条件性、可消除」的错误（Transient 家族）。
2. **零 token 铁律（D2）延续**：ErrorFrozen 期间零 LLM 调用；恢复续跑才消耗 token，且计入 turn 预算。**升级通知（ExternalEvent/WS）不消耗 agent LLM token**（是消息注入，父处理时才消耗父的预算）。
3. **债务不清偿（v2 强化）**：错误冻结期间 replyTo/owedCompletion 保持 parked（与时间表冻结相同语义，`frozen` 闭包持有 replyTo）；**升级链全程也不清偿**——只有最终终态（父/用户 cancel、放弃恢复、supervisor 熔断 fatal）才清偿 `Failed`（现状 L1306-1340 路径）。
4. **不破坏断线续跑**：客户端 WS 断线 ≠ 服务器端暂停——turn 继续跑（现状语义），冻结式恢复只处理「服务器端无法推进」的错误。
5. **卡死不冻结**：TaskStuckWatcher 10min 触发机制不变，let-it-crash 裁定优先。
6. **升级链有界（v2 新增）**：升级只沿 `parentSessionId` 静态链向上、每级**一次**（通知按 level 去重）、无环（单父树）；父记录不存在 → 跳级；最终到达用户（root/无父）即终态，无再升级对象。父 restart 消耗 supervisor 熔断预算（2/5min 硬限），防「父无限重启放大」。

---

## 4. UI 呈现方案（v2 重写：同族骨架 + 异类五维可区分）

> **v2 修订原则**：v1「reason 只改文案与点缀色」被用户裁定过于单薄。修订为**两族设计语言**——Schedule（时间表冻结）与 Error（错误恢复）共享同一**结构骨架**（同族，用户不觉得陌生、实现零重复），但在**色相/图标/动效/文案/按钮五维**上强制可区分（异类，用户 0.5s 内能分辨「这是错误恢复中」还是「这是时间表冻结」），且每条差异可自动化断言（§4.4）。

### 4.1 WS 事件扩展

```
frozen:      { type:'frozen', sessionId, resumeAt, reason:'schedule'|'llm-transient'|'network'|'provider-down'|'restart-recovery',
               detail?: string, retryCount?: number,
               escalation?: { level, awaitedParentSessionId: string|null, escalateAt } }   // v2: 升级链状态透传
agentFrozen: 同上 + agentId（子 agent，bg/flow 弹窗 tile 复用）
resumed:     { type:'resumed', sessionId }   // 不变
errorEscalated:  { type:'errorEscalated', sessionId, agentId?, reason, retryCount, level, escalateAt }  // v2 新增：升级到达用户
```

- 兼容：`reason` 缺省 = `'schedule'`；`escalation` 缺省 = 无（旧后端新前端 / 新后端旧前端均安全降级为现有行为）。`protocol.scala` `AgentStreamEvent.Frozen(resumeAtMillis)` 扩展可选字段（toJson L572-576）。
- 新 WS action（用户干预卡片按钮）：`parentRestart {sessionId}`（父/用户重启冻结中的子，后端按 kind 路由，见 §5.3.3）；`abandonRecovery` 不新增——「放弃」由现有 `cancelAgent` 覆盖。

### 4.2 同族：复用的冻结视觉骨架（完全相同，零新增结构）

错误恢复 UI **结构上复用**时间表冻结的既有骨架——这是「同族」的落点，也保证实现成本最低：

| 骨架元素 | 现状实现 | 错误族复用方式 |
|---|---|---|
| 输入框 tint 语言 | `#input-bar.frozen`：边框 + 背景 tint + 外环阴影 + 内高光，同 transition（0.2s ease） | 新增 `.frozen-error` 修饰类，**完全复制结构**（边框/背景/阴影/圆角/transition），只换色相变量（§4.3 维度 1） |
| 状态栏 banner 结构 | `.frozen-status`：icon + text + hint 三件套，同间距（gap 10px）/圆角（10px）/字号（13px/12px） | `.frozen-status.error-family`——同一元素复用，只换族类 |
| 毛玻璃材质 | 半透明 tint 叠加（`rgb(var(--sapphire) / α)` 多层）+ `frozen-status-in` 0.2s 入场 | α 层级与叠加方式逐值相同，只换色相 |
| 停流超时清理 | `frozen` 事件清 busy 计时器（F8，`main.js` L348-351） | **同路径**——错误冻结可能持续数分钟，必须停计时器（验收 UI-6） |
| 唤醒 hint | placeholder = `chat.frozenWakeHint`「输入消息可立即唤醒」 | 保留同语义（R3 用户唤醒对错误冻结同样生效），文案换 reason 前缀（§4.3 维度 4） |
| resumed 恢复路径 | `resumed` → hideFrozenStatus + 去 tint + applyInputModes | 完全复用（`main.js` L363-376），错误族 `resumed` 走同一逻辑 |
| 子 agent tile | bg/flow 弹窗 footer「已冻结 · HH:mm」（`flowAgentPopup.js` L730-738） | 同结构 + reason 短文案与 amber 点（§4.3） |

### 4.3 异类：五维可区分设计（可断言规格）

**一个核心区分**：Schedule = 「时间驱动等待」（sapphire 冷色，语义平静）；Error = 「问题解决中」（amber 暖色，语义警觉）。五维全部锚定这个区分。

| 维度 | Schedule（时间表冻结，现有） | Error 族（v2 新增） |
|---|---|---|
| **1 色相** | sapphire：`--sapphire: 91 127 191`（`sapphire.css` L7） | **amber**：新增 `--amber: 255 152 0` RGB triplet（与 `--color-warning: #ff9800` 同色相，`base.css` L6；triplet 形式才能进 `rgb(var(--amber) / α)` 与现有 tint 语法一致）；暗色模式微调提亮 `--amber-dark: 255 168 60`（沿用 sapphire 暗色覆盖模式，`sapphire.css` L36-38） |
| **2 图标** | 环形 spinner：`.frozen-icon` 18px ring，`frozen-spin` 2.4s 匀速旋转（`chat.css` L2133-2141）——「等待」语义 | **实心警示图形**：`.error-icon` 18px SVG——LlmTransient=服务不可用（云+叹号）、Network=断线（斜杠圆）、ProviderDown=服务器（机架）、RestartRecovery=续跑箭头（rotate-cw）；静态图形 + 呼吸动效（见维度 3），**禁止用旋转 ring** |
| **3 动效** | 匀速旋转 2.4s（`frozen-spin`）——平静、无紧迫感 | **呼吸 pulse**：`error-pulse` 1.6s，opacity 0.75↔1 + 图标 scale 1.0↔1.06——节奏更快的「问题在解决/等一等」信号；retryCount 变化时文本更新（§4.4 UI-3 断言 animation-name） |
| **4 文案** | 「已冻结 · HH:mm 自动恢复」+ hint「输入消息可立即唤醒」 | 「错误恢复中 · <原因> · 第 n 次重试」+ hint「输入消息可立即重试」。**禁词规则（可断言）**：schedule 文案禁含「恢复中」，error 文案禁含「已冻结」；reason 文案 i18n key：`chat.errorRecovering.llmTransient/network/providerDown/restartRecovery`（新增） |
| **5 按钮/操作** | `.frozen-cancel-btn`「解除冻结」→ WS `setWorkSchedule(false)`（全局解除，`chat.css` L2154-2168） | `.error-retry-btn`「立即重试」→ WS `immediateInput`（R3 唤醒强制续跑）+ `.error-abandon-btn`「取消任务」→ WS `cancelAgent`（放弃恢复 → 终态清偿） |
| **状态栏 badge** | 族 chip：`.frozen-status` 左侧 chip「已冻结」（sapphire 底） | 族 chip：「恢复中」+ 升级时「等待上级决策 · 剩余 Xm」（amber 底；escalation 透传时追加，§5.2）——badge 是用户扫视的第一判别点 |

**Error 族内部（LlmTransient/Network/ProviderDown）不靠色相区分**——同一 amber（保持族内一致，不搞彩虹），靠**图标 + 文案**区分；RestartRecovery 同属 amber 族（它是错误恢复的一种，不是时间表——v2 修正 v1 把它归 sapphire 的错误，那会破坏「一眼分辨」）。

| reason | 图标 | 状态栏文案示例 |
|---|---|---|
| llm-transient | 服务不可用 | 「错误恢复中 · LLM 服务暂不可用 · 自动重试中（第 n 次）」 |
| network | 断线 | 「错误恢复中 · 网络中断 · 等待自动恢复」 |
| provider-down | 服务器 | 「错误恢复中 · 所有模型服务暂不可用 · 自动探测恢复中」 |
| restart-recovery | 续跑箭头 | 「错误恢复中 · 上次运行被中断 · 正在从断点恢复」 |

### 4.4 可断言验收点（每条二值、可自动化）

- **UI-1 类互斥**：frozen 事件 `reason='schedule'` → 输入框含 `.frozen` **且不含** `.frozen-error`，banner 含 `.frozen-status` **且不含** `.error-family`；`reason≠'schedule'` → 反之（断言 `classList` 双条件）。
- **UI-2 色相断言**：`getComputedStyle`——schedule 输入框 border-color = `rgb(91 127 191 / α)`、error = `rgb(255 152 0 / α)`；α 层级逐值相同（border 0.4 / bg 0.06 / ring 0.08，input；banner border 0.25 / bg 0.08）——断言**同结构不同色相**。
- **UI-3 图标与动效**：`.frozen-icon` 存在且 `animation-name = frozen-spin`；`.error-icon` 存在且为 SVG（断言 path 数据与 reason 对应：断线/服务器/箭头图形），`animation-name = error-pulse`；两种元素互斥存在。
- **UI-4 文案禁词**：渲染文本断言——schedule 含「已冻结」且不含「恢复中」；error 含「恢复中」且不含「已冻结」；error 文案含对应 i18n key 的 reason 文案与重试计数（`第 n 次`）。
- **UI-5 按钮与动作**：schedule → `.frozen-cancel-btn` 文案「解除冻结」→ 点击发 `setWorkSchedule(false)`；error → `.error-retry-btn` 文案「立即重试」→ 点击发 `immediateInput`；`.error-abandon-btn` 文案「取消任务」→ 点击发 `cancelAgent`（断言 WS 帧 type 与 sessionId）。
- **UI-6 停流超时**：两种族事件后 `sessionBusyTimeouts[sid]` 均为 undefined（F8 路径一致）。
- **UI-7 子 agent tile**：bg/flow 弹窗——schedule → sapphire dot + 「已冻结」；error → amber dot + reason 短文案（「重试中」「等恢复」）；escalation 透传时追加「等待上级决策」（断言 tile footer 类与文案）。
- **UI-8 管理面板（P1）**：`AgentRecord.freezeReason` 驱动状态列「冻结(时段)」vs「冻结(错误: llm-transient)」；escalation 中显示「等待决策 · 剩余 Xm」。

### 4.5 与 #346 / 断线修复的衔接

- #346：ErrorFrozen 期间 turn 未完成 → 保持展开，`done` 后的收起逻辑零冲突；
- 断线假超时修复（P1）：重连 → historyPage 载入时清理残留 busy 计时器；若后端记录该 session 处于 ErrorFrozen/已失败，前端直接渲染对应状态横幅而非空等；ErrorFrozen 期间断线 → 重连后按 reason 渲染错误族 banner（不是空等，也不是断裂的 error-card）。

---

## 5. 父 agent 手动重启路径（v2 新增）

> 需求：无法自动恢复的错误（自动恢复失败/超上限/未知错误），需要父 agent 手动干预重启。父级语义：team 成员的父=Manager；Manager 的父=Nebula；Nebula 的父=用户；flow/delegate/subtask 的父=调用者（谁调用谁就是父）。

### 5.1 父级语义与错误升级链

**父级语义（映射到现有数据）**：

| 角色 | 父 | 代码依据 |
|---|---|---|
| team 成员 | Manager（其 parentRef=Manager actor） | `MailTool.scala` L1036、L1067 |
| Manager / flow / delegate / subtask | 调用者（parentSessionId=调用者 sessionId） | `protocol.scala` L256-260、`SubAgentTaskStore` |
| Nebula（root agent） | 用户（parentRef=None，kind=Root） | `TaskStuckWatcher.scala` L236-249（无 parentRef = 用户决策，同一判定） |
| 用户 | 终极父（无再上级，仲裁终态） | — |

**升级链（自动恢复失败 → 父决策 → 逐级向上 → 用户终裁）**：

```
ErrorFrozen(reason, retryCount) 进入
  │
  ├─ 自动恢复成功（条件满足 / R1 轮询 / R2 事件 / R3 用户唤醒）→ resumed（现有，不升级）
  │
  └─ 同 reason 连续冻结 ≥ 3 次（§3.3 冻结重试上限）→ 进入升级链，level=1
       │
       │  ① notify 父（当前级 parent）：
       │     - 父是 agent（parentRef.isDefined）：ExternalEvent(source=子source,
       │         eventType="error-escalated", metadata{reason, retryCount, level, escalateAfterMs})
       │         → 注入父上下文（与 "team failed" 同通道，AgentActor L1321-1339）——父决策
       │     - 父是用户（root/无 parentRef）：WS errorEscalated + UI 干预卡片（§5.3.2）
       │  ② 等父决策，窗口 T_escalate（默认 10 min，可配置）：
       │     - 父 restart  → supervisor Stop / Mail 再激活 → 断点续跑（§5.3）
       │     - 父 cancel   → 终态（cancelled，barrier 释放——复用现有 cancel 链）
       │     - 父 wait/忽略 → T_escalate 到期
       │  ③ T_escalate 到期无决策 → 向上一级（父的父）：level+1，重复 ①-②；
       │     父记录不存在（registry 查无）→ 跳级（不等窗口）
       │  ④ 到达用户（root/无父）→ 持久化 UI 干预卡片（不自动消失，用户随时决策）
       │
       └─ 任意时刻用户输入（R3）→ 立即续跑（等价「等待」的主动版，天然逃生门）
```

**判定规则（实现为纯函数，可测）**：

- `nextEscalationTarget(rec, registry)`：`registry.get(rec.parentSessionId)` → Some（父存活）→ 父；None → 继续沿父的 `parentSessionId`（若 registry 中父记录缺失，直接用 `rec.rootSessionId`——root 即用户）；`rec.parentSessionId` 为空或等于自身 → 用户（root）。
- 「父不可用」两种：① 父记录不在 registry（死/重启）→ 跳级不等待；② 父存活但 Frozen/Processing（通知已投递、排队中）→ 等 T_escalate（frozen behavior 的系统注入排队语义，`AgentActor` L2897-2908，通知不丢只延迟）。
- 升级通知**每级一次**（按 level 去重：同一 level 不重复 notify），无环（单父树结构保证）。

### 5.2 升级驱动与超时机制

**驱动：扩展 FreezeScheduler scan（不新造 fiber，与现有 30s 轮询同构、幂等、自愈）**：

- `AgentRecord` 增可选字段 `escalation: Option[EscalationInfo]`；`EscalationInfo{level: Int, escalateAt: Long, awaitedParentSessionId: String}`（protocol.scala 定义，P0 内存态，P1 可选随 turn checkpoint 落盘以便进程重启后延续）。
- FreezeScheduler.scan 增加：`status==Frozen && escalation.escalateAt > 0 && now >= escalateAt` → 执行 escalate 动作（§5.1 判定规则 + 通知上一级 + 更新 `level/escalateAt/awaitedParentSessionId`）。scan 的 `handleErrorWith` 自愈语义不变（`FreezeScheduler.scala` L33-35）。
- 进入升级链（3 次冻结触发）与每次 escalate 时更新 `escalateAt = now + T_escalate`。

**超时参数**：`T_escalate` 默认 **10 min**（与 TaskStuckWatcher 卡死阈值同量级，用户有合理决策窗口；可配置 `Defaults.ErrorEscalateAfterMs` 或 nebflow.json）。每级窗口相同（从升级链启动算起逐级顺延）；用户级**不设超时**（用户是最终仲裁，卡片持久化等待）。

**通知形式**（§5.1 已列）：父是 agent → `ExternalEvent("error-escalated")` 注入上下文（不唤醒冻结中的父——排队，T_escalate 兜底）；父是用户 → WS `errorEscalated` 事件 + UI 卡片（见 §5.3.2）。**不用 Mail**——ExternalEvent 已是父上下文注入的现成通道（`AgentActor` L1321-1339 同路径），且不占用 Mail 队列语义；父处理该事件后通过工具/UI 决策，决策结果经 AgentControl/WS 回流子 agent。

### 5.3 父决策入口

#### 5.3.1 工具层面：AgentControl 权限分级（v2 扩展）

**现状**：AgentControl Nebula 专用（`AgentCore` L1653-1657），白名单 cancel{Delegate/SubTask/Ephemeral}、restart{Delegate(ephemeral)/SubTask}，Team 只读（`AgentControlTool` L88-110）。

**修订——「父可管理直接下属」分级**，`withGuardedRecord`（L162-200）增加第二级守卫：

```
调用者角色判定（按序）：
  1. 调用者 == Nebula / rootSessionId 同桶  → 全权（现状不变）
  2. 调用者 == rec.parentSessionId（直接父）→ 可管理自己的直接下属：
     - restart/cancel 白名单扩展：Delegate / SubTask / Ephemeral（现状）+ Team 成员（v2 新增）
     - 其余 kind 拒绝理由不变（Flow→cancelFlow；Root→自杀守卫；Plan→拒绝）
  3. 其他 → 现状拒绝（含 Team 成员对非父调用者保持只读）
```

- **安全边界保持**：Team 成员只对「直接父（Manager）与 root（Nebula/用户桶）」开放，其他 agent 依旧只读——「killing mid-collaboration」风险由「只有父能管自己的成员」收窄，而非放开。
- **Team 成员 restart 的执行路径（无 supervisor）**：`AgentControlTool.doRestart` 增 Team 分支——`Stop("parent-restart")` 旧 actor + **复用 MailTool 会话激活入口**（`MailTool.scala` L1030-1085：以 `initialMessages = history` 重建 actor + mail-queue drain，该路径自带断点恢复语义）。Delegate/SubTask 分支不变（supervisor Stop→respawn）。
- **授权**：Manager 等父级 agent 的 agent.json `tools` 显式授予 AgentControl（工具授权列表，`AgentDef.tools`）；或在工具描述中注明「父级可用」。**替代方案（备选，不推荐）**：新建轻量 `ParentControl` 工具（仅 list/restart/cancel 直接下属）——权限面更小但新增工具 + 重复守卫/渲染逻辑；推荐直接扩展 AgentControl 分级（零新工具、复用全部守卫/渲染/路径）。
- **防无限重启**：父 restart 消耗 supervisor 熔断预算（2/5min，`BackoffSupervisor` L177-180）——熔断后 restart 立即失败并 `notifyParentAndStop("failed")`（L302-315），天然限频。

#### 5.3.2 UI 层面：升级干预卡片（用户级入口）

- **管理面板不动**：`OPERABLE_KINDS` 保持 {Delegate/SubTask/Ephemeral}（`managePanel.js` L28）——面板是用户视角的全局任务管控，Team 成员协作不放进用户直接管理面；Team 成员的干预走升级链卡片。
- **新增升级干预卡片**（`errorEscalated` 事件驱动，渲染在对应 session 视图顶部，复用 `.frozen-status` 结构 + `.error-family` 样式，§4.3）：
  - 文案：「等待决策 · <agent> 自动恢复失败（<reason> · 第 n 次）」+ 升级链位置（level / 等待剩余时间）；
  - 三按钮（断言与 §4.4 UI-5 同族）：
    - 「重启并续跑」→ WS `parentRestart {sessionId}`（后端按 kind 路由，§5.3.3）；
    - 「取消任务」→ WS `cancelAgent`（复用现有链路，终态清偿 + barrier 释放）；
    - 「继续等待」→ 关闭卡片（冻结保持，escalateAt 顺延；根 agent 时等用户输入唤醒）。
  - 持久化：用户级卡片不自动消失（最终仲裁，无再上级）。

#### 5.3.3 WS 通道：`parentRestart` 路由（新 action）

```
WS parentRestart {sessionId} → 后端按 kind 分路：
  - 有 supervisor（Delegate/SubTask）：supervisorRef ! Stop("parent-restart") → Terminated
    → BackoffSupervisor 退避 respawn + loadMessages 断点续跑（现有路径，L227-274）
  - Team 成员（无 supervisor）：Stop + MailTool 激活入口重建（§5.3.1）
  - Root：RestartAgent(Soft)（复用现有 restartAgent handler，L995-1006）；
    若 Root 无 handler（F11），备用 = 前端提示用户直接输入消息（R3 唤醒）
```

- 幂等/守卫：parentRestart 仅允许「父会话（parentSessionId 匹配）/ root / Nebula」发起（WS 层按 `parentSessionId == 当前 root` 校验，复用 AgentControl 守卫语义）；非 ErrorFrozen 态 → 拒绝并返回错误帧（不打断正常 turn）。

### 5.4 与 let-it-crash / TaskStuckWatcher 的边界（自动 restart vs 父手动 restart 分工）

**分工原则：自动路径优先（零父参与），父手动路径只在自动路径耗尽或不可用时介入。**

| 异常场景 | 自动路径（现有，零父参与） | 父手动路径（v2 新增/衔接） | 触发切换条件 |
|---|---|---|---|
| 崩溃（死亡） | BackoffSupervisor 退避重启（5s→60s，2/5min）→ 熔断 → `notifyParentAndStop("failed")` | 熔断后父收到 failed 通知，可 restart（再消费预算）或 cancel 或重新委派 | supervisor `Terminated` 且熔断（L177-180、L302-315） |
| 卡死（活着不动） | TaskStuckWatcher：有 parentRef → Stop 自动重启；**Team → attention 广播（只读不自动）**；root → 用户广播 | Team 卡死 → Manager 收到 attention 后决策（现状）；ErrorFrozen 卡死豁免（F5）无冲突 | Processing + 10min 无活动（watcher L138-249） |
| 条件性错误（LLM/网络/provider） | ErrorFrozen 自动恢复（退避/探测/唤醒，R1-R3） | 冻结 ≥3 次 → 升级链（§5.1）——父 restart/cancel/wait，逐级到用户 | 同 reason 连续冻结 3 次（§3.3） |
| 根 agent 错误 | 无自动（用户决策语义） | UI 干预卡片（§5.3.2）——用户 restart/取消/继续等待 | 根 agent ErrorFrozen 升级到达用户 |

**关键区分**：
- **自动 restart（let-it-crash / watcher）= 机制性恢复**：崩溃/卡死是「执行载体损坏」，恢复是「重新拉起载体」——watcher/supervisor 自行判定、零人工。
- **父手动 restart = 决策性恢复**：条件性错误是「外部条件不可用」，自动续跑只是「碰运气」——3 次后升级是「请人判断：重试值得吗 / 换方案 / 放弃」。父（最终用户）手里有自动路径没有的信息（任务价值、外部状态）。
- **Team 成员特殊性**：无 supervisor（不自动重启）+ watcher 只读 → 它的「自动路径」就是 ErrorFrozen 自动恢复（条件性错误）与 Manager 决策（崩溃/卡死 attention）——升级链是它的主干预通道。
- **预算统一**：父手动 restart 与自动 restart 共享 supervisor 熔断预算（2/5min）——手动不额外放大；升级链 level 有上限（每级一次，到达用户为止）。

---

## 6. 分阶段实施计划

### 6.1 P0 最小闭环：LLM transient 错误 → 冻结 → 自动恢复续跑 + 可区分 UI

**目标**：补 G1/G2/G4/G6（transient 耗尽与 provider Down 不再一锤子死亡；错误恢复 UI 与冻结可区分；升级链机制就绪），完成「冻结式恢复 + 父干预请求」的最小可信闭环。

| 步骤 | 改动 | 文件 |
|---|---|---|
| 1 | `FreezeReason` 枚举（shared 层）+ `AgentStreamEvent.Frozen` 加 reason/detail/retryCount/escalation 可选字段（toJson 透传）+ `EscalationInfo` case class | `agent/protocol.scala` |
| 2 | `enterFrozen` 加 reason 参数；`frozen` behavior 闭包携带 reason + resumeAt + retryCount + escalation；`CheckFreezeGate` handler 按 reason 分支重评估（Schedule → eval；LlmTransient/Network → 退避到期；ProviderDown → 候选 health 检查，P0 用 R1 轮询兜底） | `agent/AgentActor.scala` L2821、L2847-2872 |
| 3 | `LlmFailed` handler：retryable 预算耗尽（原 L1257 fatal 分支）与「非 overload transient」（原 L1231 fail-fast 分支）改为 `enterErrorFrozen(reason, resumeAt)` + 立即 persist；**保留** Permanent/Fatal/TurnBudgetExceeded → 现有 fatal 路径；同 reason 连续冻结 3 次 → 启动升级链（notify 父 + 设 escalation.escalateAt），**不再直接 fatal** | `agent/AgentActor.scala` L1174-1377 |
| 4 | `lastDispatch` checkpoint 作为恢复重派依据（现成，仅确认不动） | `agent/AgentActor.scala` L1531-1546 |
| 5 | 前端五维可区分（§4.3）：`frozen` 事件按 reason 渲染族类——`.frozen`/`.frozen-error` 输入框类、`.frozen-status--schedule`/`.error-family` banner、图标（ring vs 警示 SVG）、动效（frozen-spin vs error-pulse）、文案（已冻结 vs 恢复中）、按钮（解除冻结 vs 立即重试/取消任务）；`resumed` 恢复逻辑不变；`--amber` CSS 变量 + i18n key 新增 | `web/js/main.js` L341-376、`web/css/input.css`、`web/css/chat.css` L2116-2168、`web/css/sapphire.css`、`web/js/chat.js` L232-282、`web/js/locales/en.js` |
| 6 | 子 agent 事件 `agentFrozen` 透传 reason + escalation → tile 文案（bg/flow 弹窗，amber dot） | `web/js/bgAgentPopup.js`、`flowAgentPopup.js` |
| 7 | AgentControl 权限分级（§5.3.1）：`withGuardedRecord` 加「直接父可管理 Team 成员」分支；`doRestart` 加 Team 分支（Stop + MailTool 激活入口重建）；Manager agent.json 授予 AgentControl | `core/tools/AgentControlTool.scala` L162-200、L366-407、`core/tools/MailTool.scala` 激活入口 |
| 8 | WS：`errorEscalated` 事件 + `parentRestart` action（按 kind 路由，§5.3.3）+ 升级干预卡片（§5.3.2） | `gateway/WebSocketRoutes.scala`、`web/js/ws.js`、`web/js/managePanel.js` 或新 `errorCard.js` |

**验收**：见 §7 P0（含 UI-1..UI-7、E1-E3）。

### 6.2 P1 扩展

| 项 | 内容 | 补的缺口 |
|---|---|---|
| ① turn checkpoint 持久化 + 进程重启恢复 | 新 checkpoint 记录（{sessionId, turnId, reason, at}，atomic 写；进入冻结/每轮 dispatch 时更新，turn 终态清除）；启动时 RecoveryScheduler 扫描 → 未完成 turn 以 `initialMessages=history` 重建 agent 于 frozen(RestartRecovery) → 条件检查通过续跑。**根 agent 与进程重启的恢复空白一并补上**（F11 现状突破点） | G3 |
| ② HealthMonitor 事件唤醒 | `markUp` → 通知 FreezeScheduler 即时 scan ProviderDown 冻结 agent（R2）；恢复延迟从 ≤30s 轮询降到 probe 完成即醒 | G2 |
| ③ supervisor 重启冻结接驳 | BackoffSupervisor 崩溃重启（L241-274 恢复续跑路径）后：若 provider Down → child 先 frozen(RestartRecovery) 等待而非立即续跑失败；`subagentRetry` 事件透传 reason | G3/G4 |
| ④ 断线体验修复 | 重连 historyPage 载入清理残留 busy 计时器（假超时修复）；断线期间事件落盘（后端可补 event log），重连后呈现「断线期间发生了什么」；ErrorFrozen 期间断线 → 重连后按 reason 渲染错误族 banner | G5 |
| ⑤ 管理面板区分 | `AgentRecord.freezeReason` → 面板显示「冻结(时段) / 冻结(错误: xxx)」+ escalation 中显示「等待决策 · 剩余 Xm」（UI-8） | G4 |
| ⑥ 升级链强化 | FreezeScheduler scan 扩展 escalateAt 检查（§5.2，P0 若已实现则验证）；escalation 随 turn checkpoint 落盘（进程重启后延续升级链）；父记录缺失跳级逻辑单测 | G6 |

---

## 7. 验收条件

### P0 验收（每条二值、可自动化）

1. **编译**：`sbt compile` 通过。
2. **单元测试**：新增 FreezeReason/EscalationInfo 状态机测试（FreezeGateSpec 扩展）——① transient 耗尽 → ErrorFrozen；② 退避到期 CheckFreezeGate → resumed + 从 lastDispatch 续跑；③ 同 reason 连续冻结 ≥3 次 → **升级父（ExternalEvent("error-escalated") + escalation.escalateAt 设置），不直接 fatal**；④ Permanent（401/404）不冻结直接 fatal；⑤ 时间表冻结（reason=Schedule）行为与现状完全一致（回归）；⑥ 升级链判定纯函数（父存活/父缺失跳级/到达用户）用例。全部通过。
3. **冒烟测试（硬性）**：`sbt run` 真实启动 → `curl http://localhost:8080/api/health` 返回 200 + 断言服务可用；前端 `curl /` 返回 HTML 含挂载点。
4. **端到端（模拟 LLM 失败 → 冻结 → 恢复）**：mock 或本地降级 provider 返回 429 至预算耗尽 → 断言 WS 收到 `frozen(reason=llm-transient, resumeAt≠null)` + 输入框 `.frozen-error` 类（且无 `.frozen`）+ 状态栏 reason 文案（UI-1..UI-4）；provider 恢复 → 断言 `resumed` 事件 + turn 自动继续完成（后续 turn 有 LlmComplete 输出），全程零手动干预。
5. **零 token 硬指标**：ErrorFrozen 期间（从 frozen 事件到 resumed）无新 LLM 调用（UsageRecordStore 断言零增量）。
6. **UI 可区分验收（§4.4）**：UI-1 至 UI-7 逐条 Playwright 断言通过（schedule 与 error 两族同屏对比：类互斥、色相不同、图标/动效/文案/按钮不同、停流超时一致）。
7. **父干预 E2E（新）**：mock provider 持续 429 → agent 冻结 3 次 → 断言父（team Manager，工具模拟）收到 `error-escalated` 通知；父执行 AgentControl(restart) → 断言 supervisor Stop → respawn → loadMessages 断点续跑；provider 恢复 → 续跑完成；父执行 cancel → 断言终态 cancelled + 父 barrier 释放。
8. **回归**：现有冻结功能（时间表黑名单、用户唤醒、子 agent 冻结）测试全绿。

### P1 验收（每条二值、可自动化）

1. **进程重启恢复 E2E**：制造 mid-turn 崩溃（kill 网关进程）→ 重启 → RecoveryScheduler 检出未完成 turn → 断言 session 以 frozen(RestartRecovery) 重建 → 自动续跑完成；无 checkpoint 的完成 turn 不误恢复。
2. **provider Down 事件唤醒**：全部 provider 标记 Down → agent 进 ErrorFrozen(ProviderDown) → 手动 `markUp` 一个 provider → 断言 5s 内（非 30s 轮询）resumed + 续跑。
3. **supervisor 冻结接驳**：子 agent 崩溃重启瞬间 provider Down → 断言 child 冻结等待而非立即失败；provider 恢复 → 续跑完成。
4. **断线修复**：断开 WS 模拟 8 分钟 → 重连 → 断言无假超时提示；若期间 turn 完成，重连后直接呈现完成态。
5. **升级链 E2E（新）**：team 成员 LLM 失败 3 次 → Manager 收到通知 → Manager 无响应 → 10min（测试用短配置）后升级到 Nebula → 仍无响应 → 用户 UI 收到 `errorEscalated` 卡片 → 点「重启并续跑」→ 断言成员经 Mail 激活重建并续跑完成；断言卡片「继续等待」不触发终态、escalateAt 顺延。
6. **team 成员 Mail 再激活（新）**：无 supervisor 的 team 成员 parentRestart → 断言 Stop + 以 history 重建 + mail-queue drain（复用 MailTool 激活路径），续跑无重复注入。
7. **权限分级（新）**：非父 agent 对 team 成员 restart → 拒绝（只读理由不变）；Manager 对自有成员 restart → 放行；跨 rootSessionId → 拒绝。
8. **回归**：P0 全部验收 + 现有 BackoffSupervisor/CrashResumeSpec/TaskStuckWatcher/AgentControlToolSpec 测试全绿。

---

## 附：关键代码索引（现状验证依据）

| 事实 | 位置 |
|---|---|
| FreezeSchedule 黑名单语义 + eval | `src/main/scala/nebflow/core/schedule/FreezeSchedule.scala` |
| FreezeScheduler 30s 轮询（v2：升级链 escalateAt 扩展点） | `src/main/scala/nebflow/core/processor/FreezeScheduler.scala` |
| 冻结 gate 单点 + enterFrozen + frozen behavior | `src/main/scala/nebflow/agent/AgentActor.scala` L2747-2794、L2821-3002 |
| F1 工具结果持久化先于 dispatch | `src/main/scala/nebflow/agent/AgentActor.scala` L1502-1510、`AgentCore.scala` L67-70 |
| LLM 错误分类 / 预算 / fatal 路径 | `src/main/scala/nebflow/llm/fallback.scala` L81-156、`AgentActor.scala` L1174-1377 |
| BackoffSupervisor 崩溃重启 + loadMessages 续跑 + notifyParentAndStop | `src/main/scala/nebflow/agent/BackoffSupervisor.scala` L173-361 |
| TaskStuckWatcher 卡死判定/恢复（Team 只读 / 子自动重启 / root 广播） | `src/main/scala/nebflow/core/processor/TaskStuckWatcher.scala` L138-249 |
| HealthMonitor Down/probe/markUp | `src/main/scala/nebflow/llm/HealthMonitor.scala` |
| 前端 frozen 事件 + 输入框视觉 + banner 结构（v2 五维区分落点） | `web/js/main.js` L341-376、`web/css/input.css` L63-73、`web/css/chat.css` L2116-2168、`web/js/chat.js` L232-282 |
| --sapphire 定义（v2：--amber 新增处） | `web/css/sapphire.css` L7、L36-38；`web/css/base.css` L2-6（--color-warning） |
| WS 断线重连 | `web/js/ws.js` L205-235、L350-364、L578-593 |
| AgentStatus 枚举 | `src/main/scala/nebflow/agent/protocol.scala` L603-609 |
| AgentRecord 父子关系字段（parentRef/parentSessionId/rootSessionId/supervisorRef） | `src/main/scala/nebflow/agent/protocol.scala` L225-273 |
| AgentControl 权限/白名单（v2：父分级扩展点） | `src/main/scala/nebflow/core/tools/AgentControlTool.scala` L88-110、L162-200、L366-407 |
| AgentControl Nebula 专用授权 | `src/main/scala/nebflow/agent/AgentCore.scala` L1653-1660 |
| team 成员 Mail 激活入口（v2：parentRestart 恢复载体） | `src/main/scala/nebflow/core/tools/MailTool.scala` L1030-1085 |
| WS restartAgent / cancelAgent handler（v2：parentRestart 分路参照） | `src/main/scala/nebflow/gateway/WebSocketRoutes.scala` L995-1052 |
| 管理面板权限矩阵（OPERABLE_KINDS） | `web/js/managePanel.js` L28-48、L122-146 |
| Root agent 无 turn 级恢复（F11） | `WebSocketRoutes.scala` L95-147（ensureRootAgent 按需重建） |
