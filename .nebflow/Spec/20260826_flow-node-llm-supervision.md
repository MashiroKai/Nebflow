> 关联资产仍在 ~/.nebflow/docs/Nebflow/assets/（暂留）

# Flow 节点 LLM 流式 60s 停摆直接杀死节点 — 排查与统一监管修复方案

> 阶段文档（诊断+方案），2026-08-26。事故：dynamic flow `fpga-deck-v5-academic` planner 节点（agent design-engineer，Vision preset→kimi/k3-256k）于 18:48:56 死于 `LLM stream inactive for 60s`，节点失败→整 flow 失败，20 分钟 / ~86k cache_read 上下文丢弃。
> 红线：08-18 2 亿 token 事故的全部防护（流式悬挂兜底、per-turn 预算、overload-only agent 重试、seam guard）不得回退。

---

## 1. 事故时间线（nebflow.log 重放）

| 时刻 | 事件 | 出处 |
|---|---|---|
| 18:32:41 | FlowDagRunner 启动 `fpga-deck-v5-academic`（instance `inline-cbffa123`，dynamic） | nebflow.flow.dag-runner |
| 18:32:41 | planner 节点 spawn `subagent-design-engineer`（session `dag-fpga-*`） | lifecycle event=spawn |
| 18:32–18:47 | planner 正常工作 ~16 分钟：读 skill、16 parts、写 v5-workorders.md、git 操作 | handlers Tool 日志 |
| 18:48:34 | kimi 流第一次错误（Unknown→Transient）→ **同 provider retry 1 次**，退避 1270ms | `WARN Stream retry kimi/k3-256k: Unknown (1 left, 1270ms)` |
| 18:47:56→18:48:56 | retry 后的流输出部分内容后 **60s 零 chunk** → 看门狗触发 | inactivityTimeout |
| 18:48:56.595 | `Stream aborted after partial content (kimi/k3-256k): inactivity timeout — provider switch suppressed (…stitched-output guard)` — **seam guard 禁止换 provider**（已发出内容无法召回，防止拼接输出） | interface.scala:686-699 |
| 18:48:56.597 | planner `event=llm-fail err=LLM stream inactive for 60s` → turn fatal | AgentActor:1379 |
| （紧接） | AgentEvent.Failed → FlowDagExecutor bridge → NodeResult(success=false) → onError=None 默认 **Stop** → 整 flow Failed | FlowDagExecutor:1034/337-364 |
| （紧接） | `deleteSession` — AgentActor fatal 前 persist 的会话历史（供恢复用）被立即删除 | FlowDagExecutor:1104 |
| 18:49 后 | 无任何 retry / restart 日志。同窗口另外两个 design-engineer Delegate 实例正常完成（kimi 本身可用，属该实例流式停摆） | — |

结论先行：**这不是看门狗误报，而是四层防御全部缺席处的单点故障**。检测（60s）是合理的兜底；问题是检测后的处置链把一次上游抖动放大成「节点死刑→flow 报废→上下文蒸发」。

![事故链路与四道缺席防线](assets/20260826_flow-llm-incident-chain.svg)

---

## 2. 看门狗机制现状

### 2.1 两阶段 per-provider 看门狗（事故触发点）

`src/main/scala/nebflow/llm/interface.scala:148-185` `inactivityTimeout[O]`：

- **Phase 1（firstToken）**：尚无任何 chunk → `Defaults.LlmFirstTokenTimeoutSec = 90s`，异常消息 `LLM stream: no response within 90s`。90s 余量是给 thinking 模型的（GLM-5.2 reasoning 阶段延迟首 token，Defaults.scala:20-29 注释）。
- **Phase 2（subsequent）**：已收到 chunk 后停滞 → `Defaults.LlmStreamInactivitySec = 60s`，即事故消息 `LLM stream inactive for 60s`。设计假设「LLM providers should always produce chunks within a few seconds, even during extended thinking」（Defaults.scala:30-38）。
- 计时器在每个元素上重置；用 `System.currentTimeMillis()`（防 Mac 睡眠时 nanoTime 冻结）；检查间隔 = min(两阈值)/5，夹在 [500ms, 30s]。
- **作用域：所有 LLM 流**。sendStream 对**每个 provider 候选**独立包一层（interface.scala:515-524）——root / team / delegate / flow 节点无差别覆盖。挂掉一个 provider 的流可 fallback 到下一个（locked=false 时）。

### 2.2 外层 whole-stream no-progress 看门狗（600s）

`interface.scala:791-812`（issue #31 Fix B，2026-08-20）：覆盖 intake→首 chunk 的盲窗（候选解析/健康检查/HTTP 建连），`Defaults.LlmStreamNoProgressTimeoutSec = 600s` 无进展 → 整个 sendStream 失败。这是 08-18/08-20 事故后补的流式悬挂兜底，**必须保留**。

### 2.3 阈值配置现状

三个阈值（90/60/600）**硬编码在 Defaults.scala，无任何配置覆盖入口**。全库仅 `stuckThresholdMs`（TaskStuckWatcher 阈值）有 nebflow.json 覆盖先例（llm/config.scala:161、GatewayMain.scala:660）。interface.scala 里的 `streamInactivityOverride` / `noProgressTimeoutOverride` 仅为测试钩子。

### 2.4 引入史

| 机制 | 引入提交 | 时间 | 动机 |
|---|---|---|---|
| inactivityTimeout 两阈值 90s/60s | `dc7589eb`（unified flow engine 大重构） | 2026-08-03 | 防 LLM 流悬挂（Mac sleep/wake 后死连接） |
| Timeout→Permanent 分类 | `0bf832c0` | 07-22 | 「skip retries, go straight to next provider」（当时针对的是无预算的盲重试） |
| agent 层 overload-only retry + fail-fast | 08-18 token 事故批 | 2026-08-18 | 每次重试重发全量 ~250k 上下文 → 非 overload 一律 fail fast |
| 外层 600s no-progress | `92873aa6`（issue #31 Fix B） | 2026-08-20 | intake→首 chunk 盲窗悬挂（40min 零 trace 事故） |
| seam guard（locked 后不换 provider） | `666929d3` | 08-21 | 防跨 provider 拼接输出（qwen+kimi 混流生产证据） |

**结论：60s 阈值不是 08-18 P0 止血批引入**——它早于事故两周（08-03），动机是连接级悬挂兜底。08-18 批改变的是**检测后的重试语义**（overload-only），08-21 批封死了「有部分内容时换 provider」的路。三层改动叠加后，`timeout+单候选+部分内容` 组合变成了无出口的死局。

---

## 3. 三类后台 agent 的 LLM 失败处理路径对比

![三类后台 agent 监管体系对比](assets/20260826_bg-agent-supervision-compare.svg)

| 维度 | team agent（Mail 激活长驻） | Delegate / SubTask 子 agent | flow 节点（FlowDagExecutor） |
|---|---|---|---|
| spawn 包装 | FlowTreeActor 挂载会话 | **BackoffSupervisor**（DelegateTool.scala:415 / SubTaskTool.scala:277）：death-watch + restart | **裸 AgentActor**（FlowDagExecutor.scala:1041-1061），无 supervisor、无 death-watch |
| LLM 接口层防护 | 三类共享：两阶段看门狗 + 600s no-progress + provider fallback + seam guard | 同左 | 同左 |
| agent 层 turn 内重试 | overload×1（`OverloadRetryMax=1`，AgentActor.scala:129），预算 `MaxTurnLlmCalls=4`（fallback.scala:72） | 同左 | 同左 |
| Transient 错误（如 ConnectionReset） | **v2 ErrorFrozen 冻结续跑**（AgentActor.scala:1424-1457）：退避后自动续跑，≥3 次同 reason 升级父干预 | 同左 | 同左（同一 AgentActor，未排除 flow 节点） |
| **Timeout 错误** | Permanent → 不重试不冻结 → turn fatal | 同左 | 同左 ← **事故死因** |
| turn 最终失败（AgentEvent.Failed） | 无 replyTo → `ExternalEvent(source=team, failed, retryable=true)` 通知 lead 重派（AgentActor.scala:1522-1541） | supervisor 转发父：`failedSessionId`+`retryable`+`failureType`，**历史已 persist** 供父重派/fork（BackoffSupervisor.scala:136-151） | bridge 收 Failed → `NodeResult(success=false)` → **onError 默认 Stop → 整 flow 报废**（FlowDagExecutor.scala:337-364） |
| crash（actor 异常死亡）恢复 | TaskStuckWatcher（10min）只读通知；AgentControl 手动 restart | BackoffSupervisor：5s→60s 退避 restart，max 2 次/5min，**loadMessagesForSession 断点续跑**（:227-301） | **无**。且 executor 侧正常拿 Failed 事件，crash 场景 bridge 悬挂（靠 agent onError 兜底，无 restart） |
| 卡死（活着不动）检测 | TaskStuckWatcher 扫描含 Team（10min，只读） | 扫描含 Delegate/SubTask → Stop→重启链→硬取消升级 | 扫描含 Flow kind（TaskStuckWatcher.scala:62）——但 **60s 看门狗先杀 turn，10min 检测用不上**；且 `supervisorRef=None`，giveUp 兜底明确排除 Flow/Ephemeral（:210-211 注释） |
| 断点恢复 | TurnStateStore + restoreInterruptedTurns（FlowTreeActor.scala:612-644，重启后） | loadMessages 恢复消息 + continue 指令 | **无**——失败即 `deleteSession`（:1104），AgentActor persist 的历史被丢弃 |
| 显式重试支持 | —（父语义重派） | —（父语义重派） | `onError=restart` + `maxRetries`（FlowDagExecutor.scala:346-362）——但 **fresh spawn 从零重跑**（executeNode 重新 spawn，initialMessages=Nil），20 分钟工作全重烧 |

**用户疑点②实锤**：flow 节点在「turn 失败处置」这一段确实另起炉灶——不装 BackoffSupervisor、不吃 failedSessionId/retryable 元数据、失败即删会话。它已有的 `onError=restart` 是唯一容错路径，但（a）默认 Stop 且 FlowExecute inline DAG 从不设置；（b）restart 是从零重跑不是断点恢复；（c）parallel 分支的 onFail=abort 还会把 sibling 全 pierced。

**公平对照**：delegate 的 turn 级 LLM 失败同样**不会**被 BackoffSupervisor 自动 restart（Failed≠Terminated）——它靠「父 agent 是 LLM，看到 retryable=true 事件会语义重派」兜底。flow 的父是确定性 DAG walker，没有这个语义层。所以差距精确说是：**delegate 把恢复决策交给了有智能的父；flow 把恢复决策交给了默认 Stop 的路由表，且销毁了恢复所需的全部状态**。

---

## 4. 根因结论

**R1（触发）**：kimi/k3-256k 上游 60s 流停摆——真实抖动（同窗口其他实例正常完成）。看门狗检测本身是对的：60s 零 chunk 的流不可信，必须处理。

**R2（分类层缺细分）**：`TimeoutException → Permanent`（fallback.scala:118-119）一刀切。07-22 设计它时针对的是「provider 不响应→跳过重试直接换下一个」；但今天的三种 timeout 语义不同——firstToken 超时（provider 死）、inactivity 停摆（上游抖动，重试大概率恢复）、整流 no-progress（系统级悬挂）。第三种该保持最严，第一种换 provider 合理，**第二种（事故场景）在换无可换（单候选）或 seam guard 锁死（有部分内容）后直接判 turn 死刑，过重**。

**R3（agent 层无此路重试）**：llm-fail 自动重试仅 overload×1（08-18 裁定，token 保护，正确）。stream-inactivity 不在其中 → fail fast。v2 freeze 也明确把 Timeout 留在 fatal 列表（AgentActor.scala:1429-1430 注释）。

**R4（seam guard 封路，行为正确）**：locked=true 后禁止 provider 切换是拼接输出防护（08-21 生产证据），**不应改动**。但它与 R2/R3 叠加意味着「部分内容 + 单候选 + 停摆」零出口。

**R5（flow 层无监管，放大器）**：FlowDagExecutor 裸 spawn + onError 默认 Stop + 失败 deleteSession。R2-R4 任何一层有出口，R5 都不会被触达；R5 存在则任何 turn 级失败都是 flow 级灾难。

**60s 阈值评估（疑点①）**：60s 偏紧但非主因。依据：firstToken 给 thinking 留了 90s，subsequent 却假设秒级出 chunk——对不发 thinking delta、无 SSE keep-alive 的 OpenAI 兼容网关，长思考停顿 >60s 合法存在（kimi k3 是 thinking 模型）。放宽到 120s + 可配置是合理余量；真正的修复在处置链。

---

## 5. 修复方案：flow 节点纳入统一监管

设计原则：**复用既有监管机制（AgentActor 重试预算 / ErrorFrozen / BackoffSupervisor / loadMessages / onError.Restart），不新造 flow 专属体系**。四道防线按成本从低到高补：重试（最便宜，token 增量受控）→ 阈值余量 → 节点断点重启。

### P1 — stream-inactivity 与 provider 死亡分流（分类层）

**改动**：`llm/fallback.scala` + `llm/interface.scala`
- inactivityTimeout 的 Phase-2 异常改用专用类型（如 `StreamInactivityTimeout`，携带 lastChunkAge），`classifyError` 将其分类为 **Transient（reason=Timeout）**；firstToken 超时与 600s no-progress 维持 Permanent（provider 死/系统悬挂，语义不同）。
- AgentActor llm-fail 的 retryable 判定（:1390-1398）扩展为 `overload || streamInactivity`，**共用现有 `OverloadRetryMax` 与 `MaxTurnLlmCalls=4` 预算，不新增额度**。退避 ≥30s（给上游恢复窗口，走 `Fallback.retryDelayMs` 现有管线）。
- seam guard 完全不动：重试是 turn 级整体重发（同 checkpoint messages，无拼接问题），不是流级续传。

**token 论证**：一次 turn 级重发 = 1× 全上下文，但 messages 未变 → cache_read 96% 口径下实际计费增量约为输入价的 4%；上限封死在 MaxTurnLlmCalls=4（预算耗尽 → TurnBudgetExceeded Permanent fail fast，与 08-18 防护同构）。对比现状：flow 报废后用户/父 agent 重跑 = 100% 上下文 + 全部工具轮次重烧，重试便宜得多。

### P2 — flow 节点接入 supervisor 语义（监管层，核心）

**改动**：`core/entity/FlowDagExecutor.scala`（executeAgent）+ `FlowDagCompiler.scala` + `FlowExecuteTool.scala`
- executeAgent 的 spawn 包一层 **BackoffSupervisor**（新 source="flow-node"，或最小化改造：给 bridge 加 Terminated watch → restart 分支），使 crash 场景与 delegate 同构。
- `AgentEvent.Failed` 且 `retryable=true`（LlmFailed/Timeout）时：不再无条件走 onError.Stop——按 `OnError.Restart` 处理，受 `node.maxRetries` 上限。重启 spawn 以 `loadMessagesForSession(sessionId)` 作 initialMessages（**断点恢复**，非从零重跑），注入 continue 指令（复用 BackoffSupervisor:267-274 模式）。
- **修正 deleteSession 时机**：失败且尚有重试余量时保留 session；仅终态（完成/不可重试/重试耗尽）删除。这是「AgentActor 认真 persist、executor 转手就删」的直接修复。
- **默认值变更收口在编译层**：FlowDagCompiler 给 FlowExecute inline DAG 的节点默认 `onError=Some(Restart), maxRetries=1`（编译输出中显式可见，可被用户 JSON 覆盖）；predefined flow.json **完全不变**（显式 stop 仍立即失败，兼容承诺）。
- 观测：restart 广播复用 `subagentRetry` WS 事件（前端已有渲染）。

### P3 — 阈值放宽 + 可配置（检测层）

**改动**：`shared/Defaults.scala` + `llm/config.scala` + `gateway/GatewayMain.scala`（装配）
- `LlmStreamInactivitySec` 60→**120s**（对齐 firstToken 90s 的 thinking 余量逻辑）；firstToken 90s 不变。
- 三阈值加 nebflow.json 覆盖入口 `llm.streamTimeouts { firstTokenSec, inactivitySec, noProgressSec }`（Defaults 兜底，参照 stuckThresholdMs 先例）。
- **不动**：600s no-progress、LlmTimeoutMs=600s、seam guard、overload-only fail-fast——08-18 红线全部保留。

### P4 — 观测性（小改）

**改动**：`agent/AgentActor.scala`（llm-fail 日志）+ `llm/interface.scala`
- llm-fail 事件附带 providerId/model、attempt 数、lastChunkAgeMs、是否 locked（有部分内容）；flow 失败的 `[Flow failed]` 回执里含节点 retry 历史。便于事后归因「上游抖动 vs 真死」。

### 验收标准（全部二值可自动化）

1. **单测**：
   - `classifyError(StreamInactivityTimeout) == Transient`；`classifyError(firstToken TimeoutException) == Permanent`（防回归）。
   - agent 层：stream-inactivity llm-fail 触发预算内重试；预算耗尽 → TurnBudgetExceeded → fail fast（**token 红线：单 turn 因 stream-inactivity 的重发总数 ≤ MaxTurnLlmCalls**）。
   - overload-only 语义回归：非 stream-inactivity 的 Permanent（Auth/Format/context overflow）仍零重试。
2. **集成（mock provider）**：先发 1 chunk 再停 >120s+退避的 provider → planner 节点经历 1 次 restart 且 `loadMessages` 断点恢复（断言恢复后消息数 > 0、无重复 system prompt）→ flow 最终 completed；全程 LLM 请求计数 == 预期值。
3. **冒烟（硬性）**：`sbt run` 起真实服务 → 触发两节点 dynamic flow（planner+worker，正常 mock）→ flowCompleted(success=true)；再触发注入抖动的 flow → flowCompleted(success=true) 且 subagentRetry 事件可见；`curl /api/health` 200。
4. **回归红线**：600s no-progress 仍整流失败；seam guard 用例（部分内容后错误）不换 provider；predefined flow.json 显式 onError=stop 的节点失败仍立即终止 flow。

---

## 6. 风险与取舍

| 项 | 风险 | 缓解 |
|---|---|---|
| Timeout→Transient 放宽 | 真死的 provider 会多重试 1-2 次，多烧 1-2× 输入 token | cache_read 高命中率 + MaxTurnLlmCalls 硬顶 + ≥30s 退避；对比 flow 报废重跑的总成本更低 |
| flow 默认 onError 变更 | predefined flow 行为兼容性 | 收口在 FlowDagCompiler（仅 dynamic inline DAG），flow.json 显式声明优先；编译输出可见 |
| restart 断点恢复正确性 | 依赖 fatal 路径 persist 完整性；compaction 中途死有既有 zombie guard | 恢复消息为已 persist 快照，continue 指令模式已在 BackoffSupervisor 生产验证 |
| 120s 阈值 | 真 hang 的发现延迟 +60s | 仅 Phase-2；Phase-1 与 600s 兜底不变；可配置收紧 |
| BackoffSupervisor 包 flow 节点 | kind/registry/parentRef 交互（AgentKind.Flow 的 supervisorRef 回写、TaskStuckWatcher giveUp 分支语义变化） | giveUp 兜底从「排除 Flow」改为走 supervisor Cancelled 需同步评审；P2 落地时一并处理 |
| 范围控制 | 本方案横跨 llm/agent/flow 三层 | P1/P3 独立可先发（低风险止血）；P2 为核心结构性改动，单独评审+充分测试 |

**取舍说明**：没有选择「flow 节点失败交回触发它的 LLM 父 agent 语义重派」（delegate 模式），因为 flow 的价值恰是确定性执行与并行编排，失败回交父 agent 会退化成 emergent 协调、丢掉 DAG 的路由保证；也没有选择无限放宽看门狗——60s→120s 只是余量修正，兜底语义不变。

---

## 附：关键代码索引

- 看门狗：`llm/interface.scala:148-185`（两阶段）、`:791-812`（600s 外层）、`:515-524`（per-provider 装配）、`:686-699`（seam guard）
- 阈值：`shared/Defaults.scala:29/38/55`
- 错误分类：`llm/fallback.scala:81-152`（Timeout→Permanent :118-119）、预算 `:72`（MaxTurnLlmCalls=4）
- agent 层：`agent/AgentActor.scala:129`（OverloadRetryMax=1）、`:1379`（llm-fail）、`:1390-1398`（retryable 判定）、`:1424-1457`（v2 freeze，Timeout 在 fatal 保留列表）、`:1494-1500`（fatal persist）、`:1522-1541`（team 无 replyTo 通知）
- 监管：`agent/BackoffSupervisor.scala:136-151`（Failed→父通知）、`:173-301`（Terminated→restart+断点）；`core/processor/TaskStuckWatcher.scala:62`（扫描集含 Flow）、`:210-233`（giveUp 排除无 supervisor 的 Flow）
- flow：`core/entity/FlowDagExecutor.scala:272-334`（executeNode）、`:337-364`（handleResult/onError）、`:988-1142`（executeAgent：裸 spawn :1041、Failed→NodeResult :1034、deleteSession :1104）；`core/entity/EntityTypes.scala:307-308`（onError 默认 None→Stop、maxRetries 默认 0）；`core/flow/FlowDagRunner.scala:79-85`（失败回执）
