---
type: report
status: solved
---

# 循环检测误杀整批任务：诊断与修复（2026-11-02）

批次：循环检测误杀修复批（delegate-nebflow-18f7bacc）。文档库索引见 [[index]]，术语面见 [[glossary]]。

## 0 一句话结论

三条结构性缺陷叠成一次误杀：**R 系三轴根本没有警告档**（第 1..9 次全是 Pass，第 10 刀直接杀）、**Warn 文案印的是指纹哈希而不是工具名**（即使注入了模型也认不出被卡住的是哪个工具）、**暂时性故障与真死循环同罚**。修法 = 阶梯化（同一指纹首次触阈只发结构化 Warn，第二次才终止）+ 三轴补齐 soft 档 + 暂时性故障按倍率放大阈值（仍有界）+ 终止时把被杀的最后一轮输出抢救进节点 result。

## 1 现象（真实案例，非假设）

agent 在同一条 Bash 命令上反复重试，到第 10 次左右被引擎判定为重复调用循环，**连同整批 delegate 工作一起终止**。当日三例：

1. 某 delegate 的 land 节点 18:04 `loop-detected`，整条链断在那里，gate / docs 两个节点一直 `pending`；
2. 液闪图件批 —— 被掐；
3. 开题报告批 —— 被掐。

## 2 诊断：四问四答

> 行号分两类：**「改前」= 本批改动前的代码位置**（用于复现缺陷），**「改后」= 现在的位置**（用于复核修法）。

### Q1 `Verdict.Terminate` 之后到底发生了什么

**答案：只终结当前 turn，不是直接判定 delegate 任务失败——但这条「turn 级」终止是 fatal 类，会走完整个失败链，最终让**承载该 turn 的节点**变成 `failed`。**

完整路径（改后行号）：

1. `LoopGuard.evaluate` 返回 `Verdict.Terminate` —— `src/main/scala/nebflow/core/processor/LoopGuard.scala:401`（裁决块），`finalCounters` 把 fp 记进 `terminatedFps` 供后续 turn 复发判定（`:491`）。
2. 挂载点 `pipeToolExecutions`（`AgentCore` 工具环，`guardBatch` 之后）：`src/main/scala/nebflow/agent/AgentSessionExecution.scala:1514`（热读配置）→ `:1525`（`LoopGuard.evaluate`）→ `:1537-1544`（root 豁免：`depth==0` 的 Terminate 降级为 Warn）→ **`:1561-1580` 的 `case t: Verdict.Terminate`：不发 `ToolsComplete`，改发 `ctx.self ! AgentCommand.LlmFailed(LoopDetectedError(...))`**。
3. `AgentProcessing` 的 `case LlmFailed(...)`：`src/main/scala/nebflow/agent/AgentProcessing.scala:135`；fatal 分流判据 `:375`（`case _: LoopDetectedError => false` —— 循环终止**不进冻结恢复**，直接走 fatal）；`AgentError` 构造 `:405-416`；WS `error` 帧 + `Done` 帧 `:444-462`；**`failTargets.traverse_(_ ! AgentEvent.Failed(...))` `:479`**（`failTargets = replyTo ++ owedCompletion`，见 `:478`）；父会话外部事件（仅当无 replyTo 时）`:494-512`；registry 写 Idle `:537`；`idle(...)` `:560`。
4. 节点桥：`src/main/scala/nebflow/core/project/NodeStarter.scala:1089-1094` `case AgentEvent.Failed(_, err) => resultDeferred.complete(Left(FailOutcome(err.message)))` → `:1294` `else failNode(nodeId, fo.message)`（只有含 "cancelled" 字样的才走取消腿）。
5. 节点终态写入：`src/main/scala/nebflow/core/project/NodeCompletion.scala:787-848` `failNodeR`——`:822-827` `status = Failed, result = Some(err), ttlExpireAt = None`；`:835` WS `nodeUpdated`；`:839` `deliverFailed(...)`；`:843` `checkBarriersNow(cause="failed")`。

一句话：**Terminate = 「这个 turn 失败了」**，但它经 `LlmFailed → AgentError(LlmFailed) → AgentEvent.Failed → failNode` 精确地杀死承载它的那个**节点**，并把该节点的 `result` 覆盖成一段错误串。delegate 会话本身不会被标记失败（除非整个 delegate 只有这一个节点），链也没有 chain 级 failed 状态。

### Q2 为什么整条链断（门 / 文档两个节点永久 pending）

**答案：两层同时失效——引擎侧是「D5 failed 零结算」的刻意语义（失败上游永不投递 ⇒ 下游 barrier 永不归零），而本该被叫来手工调度的人（分发器会话）在 delegate 作用域里经常收不到失败通知。**

- **引擎不再推进**（结构性，不是 bug 而是设计）：`deliverFailed`（`src/main/scala/nebflow/core/project/NodeEngine.scala:857-917`）只对显式 `(failed)` + `mode=signal` 的边做 `settleTo`，其余目标一律**零结算**并写一行 INFO：`:895-901`「`downstream(s) ... keep waiting (D5 zero-settlement; on-failed signal edges opt targets out; reactivate upstream to auto-resume)`」。普通 pass 边根本不在 `failedEdges` 里 ⇒ 不投递、不结算、不启动。
- **启动闸门恒不放行**：`NodeStarter.scala:54-56` 的 `depsSatisfied` 判据是 `forall(_.status == Completed)`，`:104` 要求 `node.in` 全部在 `deliveredTo`；barrier 结算 `NodeDelivery.scala:571-627`（`:592-594` `allArrived` = `in.forall(deliveredTo.contains)`）。failed 既不是 Completed 也永远不会被投递 ⇒ 下游永久 `pending`。
- **链级状态不会翻 failed**：`ChainLedger.scala:502-510` 的链状态值域只有 `cancelled > paused > active`，全仓没有 `failChain/abortChain`（`not found`）。所以面板上链还是 `active`、下游还是 `pending`，**看上去像卡住，实际是「没人再调度」**。
- **周期回扫救不了**：`NodeCompletion.scala:441` 的 barrierOk 闸 + `:502-503` 的自述「上游 failed/cancelled 卡 barrier 的补触发**不可达**，事件即其可见性载体（分发器巡检处置）」；`mount-stalled`（`:547-550`）、`barrier-blocked`（`NodeGating.scala:540-571`）都只告警不放行；`TaskStuckWatcher` 的候选集是 `agentRegistry` 里 `Processing` 的**会话**（`TaskStuckWatcher.scala:727-742`），看不到 pending 节点。
- **本该来的那个人没来**：唯一的生产自愈是分发器（LLM 会话）收到 `dispatch-notify` 后 `NodeEdit` 重激活该节点（`NodeEngine.scala:943-963`、`DispatchNotify.scala:1093-1107` 的处置指引）+ 节点自带 `retry` 策略自动回跳（`NodeEngine.scala:981-1019`，本批未用）。而 delegate 作用域里 `DispatchNotify` 落到 `defaultTrigger(projectName, rootSessionId)`（`DispatchNotify.scala:1243-1256, 1290`），查 `ProjectRuntimeRegistry` 取不到宿主时**只写一条 WARN `"Project '...' not mounted — dispatch-notify skipped"`**，`flushBatch`（`:314-328`）却照样 `markSent` 并记一条 `dispatch-notify "triggered"` 事件——**通知蒸发了，事件面却写着已触发**。叠加 delegate 会话终态后 `DelegateFlowHosts.unmount(delegateId)`（`DelegateTerminal.scala:670-673`）会把引擎实例整个卸掉，连 30s 的 `settleRunnableSweep` 也没了。

**判定：既(a)又(b)——(a) 是 D5 的结构性停等（惩罚由「整条链下游全部悬空」承担，这就是「惩罚与错误不成比例」的根源）；(b) 是 delegate 作用域里失败通知难以送达分发器，导致 (a) 的补偿腿失效。** 本批不负责任改 D5 语义（改法会让「失败上游自动放行」变成新的静默错误源），改为**大幅降低进入 failed 的概率**（阶梯 + 暂时性故障区分），并把「失败现场」变成有信息的交付物（见 Q3 的修法）。dispatch-notify 蒸发是独立缺陷，列在 §6 未做项。

### Q3 已完成的部分去哪了

**答案：文件产物有三层抢救，上下文里的分析/结论在 L1 终止腿上是零落盘、零回传。**

- **会话 transcript**：每批工具结果都在 `ToolsComplete` 边界落盘（`AgentProcessing.scala:888-893`、`:950` 的 `persistIfSession`；`:824` 处 `newMessages` 才把 `assistantMsg` / `resultMsg` 拼进消息表）。而 L1 终止腿**不投 ToolsComplete**（`AgentSessionExecution.scala:1561` 的注释原文：「L1：turn 以 LoopDetected 失败——不投 ToolsComplete」）⇒ 触发终止那一轮的 assistant 文本与该批工具结果**从未进入 messages**，fatal 路径 `AgentProcessing.scala:461-470`（`saveMessagesForSession(sid, cleanedState.messages)`）保存的只是「上一次批次边界」的快照。**这一轮的分析结论彻底消失。**
- **节点 result 文件**：成功腿 `NodeStarter.scala:1239-1254` 取 `extractLastAssistantText(messages)` 写 `results/<nodeId>.md`；失败腿 `:1294` 只把 `fo.message`（错误串）写进 `result`（`NodeCompletion.scala:822-827` 无条件 `result = Some(err)`）。**`results/<nodeId>.md` 有文件，但内容是一句 loop-detected 错误。** 对比取消腿有保真拼接（`NodeCompletion.scala:916-920` `preservedCancelResult`）——**failed 反而比 cancelled 更粗暴**。
- **文件变更**：`terminalDiffMetadata()`（`BackoffSupervisor.scala:1026-1034`，失败腿 `:632-635`）只对 `source=="delegate"` 的 supervisor 轨生效；flow 节点直接 `spawnAgentActor`（`NodeStarter.scala:718`），不走 supervisor ⇒ **节点失败时连 file-diff 载荷都没有**。磁盘上真实写过的文件当然还在，但没有任何自动归集（`WorktreeRescue` / `MergeArtifactCare` 只在合并/清理面跑）。
- **已有的文本抢救件**：`NodeReportRegistry` 的 append-only journal（`NodeReportRegistry.scala:159-196`）能跨进程死存活，但失败腿不 drain（`NodeStarter.scala:1244-1246`「cancelled/failed 路径不消费」）；`SubAgentStartupRecovery.extractLastAssistant`（`:497-501`）只能取回**已落盘**的文本，救不回被丢掉的那一轮。

**该补的层：挂载点（`AgentSessionExecution` 的 Terminate 分支）是唯一还能碰到那段文本的地方**（`result.text` 与 `guardedBatch` 都在作用域内），补法见 §3.5。

### Q4 Warn 档为什么没起作用

**答案：Warn 的注入通路是通的（真的进了模型上下文），但三轴里有两轴根本没有 Warn，而唯一那一档的文案对模型毫无信息量。**

- **通路是通的**：`AgentSessionExecution.scala:1579-1590` 的 `case w: Verdict.Warn` → `ToolsComplete(loopReminder = Some(LoopGuard.reminderMessage(w)))` → `AgentCommand.scala:406-410` 的 `loopReminder` 字段 → `AgentProcessing.scala:820-827`：以 `<system-reminder>` 包成一条 **user 消息**追加在本轮工具结果之后，进入下一轮请求。wiring 钉子实证：`src/test/scala/nebflow/agent/LoopGuardWiringSpec.scala:372`「round-2 request must carry the L0 reminder」（`textOf(reqs(1)).contains("Loop guard")`）。**所以「模型看不见」不是原因。**
- **真正原因 1：R 系三轴没有警告档。** 改前 `LoopGuard.scala:279` 的 Warn 分支条件是 `withText.streakCount == cfg.identicalFailureSoft`，而 `streakCount` 只在 **S1（同参同败且错误签名一致）** 上累计。掐死 Mashiro 的是 `identicalCallHard=10`（R-call，成败皆计）——那条轴从 1 到 9 **全是 Pass**，第 10 次直接 Terminate。R-text 轴同理（改前 `:249`）。crossTurn 冻结轴（改前 `:239`）也是零警告：每 turn 只败一次时 S1 软阈（3）永远不触发，第 3 个 turn 直接 Freeze。
- **真正原因 2：Warn 文案印的是指纹哈希。** 改前 `LoopGuard.scala:281`：`s"tool '${withText.streakFp}' has failed ..."` —— `streakFp` 是 `sha256(tool+args).take(12)`，模型看到的是一串 hex，**根本不知道卡住的是哪个工具、哪条命令**（`Counters` 里连工具名都没存）。这一档等于提供了「你循环了」而没有提供「循环在哪」。
- **原因 3（放大器）**：改前文案只有一句陈述，没有「再重复就会怎样」的后果声明，也没有「暂时性故障可以再试一次 / 确定性失败该换路」的分流指引。

## 3 改法

### 3.1 分三层实现（协作方点名要的三条）

| 层 | 位置 | 做什么 |
|---|---|---|
| 判定核心（纯函数，可单测） | `LoopGuard.scala:270-500` | 阶梯化裁决 + 暂时性故障分类 + 逐键配置解析（`:142`） |
| 计数状态 | `ProtocolPayloads.scala` `Counters:20-62` | 新增 `streakTool` / `streakTransient` / `lastCallTransient` / `transientFps` / `warnedKeys`（grace 记账，会话级） |
| 挂载点与交付面 | `AgentSessionExecution.scala:1561-1580`（终止时带上 `salvagedText`）+ `AgentEvent.scala:105-127`（`LoopDetectedError.fullMessage`）+ `AgentProcessing.scala:405-416,436-438`（写进 `AgentError.message` 与 WS error 帧） | 提示注入沿用既有 `loopReminder` 通路；终止时抢救最后一轮输出 |

### 3.2 阶梯：warn → 继续 → 仍重复 → 终止

- 新增 **R-call soft 档**（`identicalCallSoft`，默认 5）与 **R-text soft 档**（`identicalTextSoft`，默认 5）；S1 soft 仍为 3。
- 新增 **`requireWarnBeforeTerminate`（默认 true）**：同一指纹（或同一文本哈希）**首次**触硬阈/冻结阈只发 Warn 并把键写进 `Counters.warnedKeys`（`LoopGuard.scala:380` `graceUsed`、`:491` 记账），**第二次才终止/冻结**。因为软阈（3/5）总在硬阈（8/10）之前，实际语义是：「软阈先警告一次 → 硬阈前若模型已吃过警告则直接终止，否则再给一轮 grace」。
- 冻结轴（crossTurn）同样受 grace 约束（`LoopGuard.scala:389-400`）：每 turn 只败一次的形态从「第 3 个 turn 直接冻结」变成「第 3 个 turn 警告、第 4 个 turn 才冻结」。
- **不放宽到永不终止**：所有轴仍是有界的整数阈值；grace 只推迟一轮。`requireWarnBeforeTerminate=false` 即完全回到改前语义（回退开关：`mustAct` 无条件为真，连 grace Warn 也不再补发）。
- **grace 记账键 = 指纹本身**（跨轴共享）：同一 fp = 同一「工具+参数」组合，从 S1 轴还是 R-call 轴被警告过并无区别——模型已经被明确告知这个组合在循环，因此第二次触阈直接终止。R-text 轴用 `text:<hash>` 命名空间隔离，避免与 fp 的 12 位 hex 撞键（`LoopGuard.scala:380-385` 的 `warned` / `graceUsed` / `mustAct` 三个判定单点）。

### 3.3 提示注入（这一档必须真的进模型上下文）

Warn 走既有 `loopReminder` 通路（`AgentProcessing.scala:820-827`，user `<system-reminder>`，下一轮请求可见），文案重写为**结构化四步**（`LoopGuard.scala:508` `reminderMessage`）：

1. 陈述事实并**带工具名与次数**（例：「tool 'Bash' has now been called 10 times in a row with identical arguments」）；
2. 暂时性故障（超时/网络抖动/远端临时不可用）→ 明说并**换法再试一次**（加超时、缩范围、`run_in_background`、换端点）；
3. 确定性失败 → 停止重试：换工具/换参数，或明确报告失败并完成不依赖它的部分；
4. **后果声明**：再重复一次就终止本 turn；已被终止过的指纹复发即冻结整个会话；
5. 附一句「在停下前把已完成的分析落盘」的提示。

### 3.4 暂时性故障 vs 真死循环（特别要求：实现了，但有界）

- 分类器：`LoopGuard.isTransientError`（`LoopGuard.scala:231`）+ `DefaultTransientPatterns`（`:82`，正则，只收超时/连接/DNS/限流/HTTP 5xx 等环境信号；HTTP 码要求带 `http` 前缀，`/docs/503.md` 这类路径不误判）。
- 命中后**不是免检**：计数照旧、警告照旧，只是硬阈按 `transientFactor`（默认 3）放大 —— S1 8→24、R-call 10→30、crossTurn 3→9 个 turn。**终止仍然有界**，不会把真死循环放过。
- 混合形态回退：同指纹只要出现一次确定性失败，`transientFps` 立刻把它移出放大名单（`Counters` 的 `transientFps`），阈回到常规值。
- 可关：`transientAware=false` 即关闭放大（保留分类观察）。
- **残留难点（写进设计问题，不硬做）**：错误文本只有 160 字符（挂载点 `errorText = if r.isError then r.content.take(160)`），模糊错误（如 `exit code 1` 后接大段 stderr）无法可靠分类，仍按真循环处理。要根治需要在工具层给错误打「可重试」标签（`ToolExecResult` 加字段），属独立批次。

### 3.5 终止时把已完成部分落盘/回传

- `LoopDetectedError` 增 `salvagedText`（`AgentEvent.scala:105`），挂载点在发 `LlmFailed` 时把 `result.text`（被终止那一轮的完整助手输出，含分析结论与算出的数据）带上（`AgentSessionExecution.scala:1561-1580`）。
- fatal 路径用 `fullMessage`（`AgentEvent.scala:113-123`，上限 8000 字符）拼「终止原因 + 抢救段」，写进 **`AgentError.message`**（`AgentProcessing.scala:405-416`）与 **WS error 帧**（`:436-438`）。
- 于是三面同时可见：① 节点 `results/<nodeId>.md` 的 `result`（`NodeCompletion.failNodeR:822-827` 写的就是这条 message）；② 用户界面的 error 气泡；③ 父会话的失败载荷（`BackoffSupervisor.scala:635`）。**整批工作不再随一条卡死的命令一起蒸发。**

### 3.6 配置面（阈值全部可配、逐键兜底）

`nebflow.json` → `supervision.loopGuard`：

| 键 | 默认 | 含义 |
|---|---|---|
| `enabled` | true | 总开关 |
| `identicalFailureSoft` | 3 | S1 同参同败警告档 |
| `identicalFailureHard` | 8 | S1 终止档（×transientFactor 放大） |
| `identicalCallSoft` | **5（新增）** | R-call 连续同参警告档 |
| `identicalCallHard` | 10 | R-call 终止档（×transientFactor） |
| `identicalTextSoft` | **5（新增）** | R-text 复读警告档 |
| `identicalTextHard` | 10 | R-text 终止档 |
| `crossTurnFailureTurns` | 3 | S3 跨 turn 冻结档（×transientFactor） |
| `requireWarnBeforeTerminate` | **true（新增，回退开关）** | false = 回到「触阈即杀」老语义 |
| `transientAware` | **true（新增）** | 暂时性故障区分总开关 |
| `transientFactor` | **3（新增）** | 暂态阈放大倍率（≥1） |
| `transientPatterns` | 内置正则集 | 暂态错误模式（整体替换默认集） |
| `exemptTools` | TaskQuery/TaskList/TeamTaskList | 轮询类豁免 |

**顺带修掉的配置缺陷**：改前 `loadConfig` 是一个全有或全无的 for-comprehension —— 配置文件里**缺任何一个键，整份配置静默回落默认值**（用户只写 `identicalCallHard: 20` 完全不生效，「阈值可配置」名存实亡）。改后逐键兜底并抽出纯函数 `parseConfig`（`LoopGuard.scala:142`），带回归钉子。

## 4 改前 / 改后行为对照

以改前的默认阈值为基准（事故轴加粗）：

| 场景 | 改前 | 改后 |
|---|---|---|
| **同一条 Bash 命令连续执行 10 次（R-call）** | **1..9 全 Pass，第 10 次直接 Terminate —— 零警告** | 第 5 次 Warn（带工具名/次数/分流指引）→ 模型不改 → 第 10 次 Terminate |
| R-call 第 10 次恰好撞上已吃过的警告 | 同左（无 grace 概念） | 直接终止（grace 已消耗），不可能出现「警告 + 终止同轮」的浪费 |
| 同参同败且错误一致（S1） | 第 3 次 Warn（文案印 fp 哈希）→ 第 8 次杀 | 第 3 次 Warn（带工具名 + 四步指引）→ 第 8 次杀（不变） |
| 助手逐字复读同一段文本 | 第 10 轮直接杀 | 第 5 轮 Warn（`text:<hash>` 记账）→ 第 10 轮杀 |
| 每 turn 只败一次的跨 turn 形态（案例 1） | 第 3 个 turn **直接 Freeze**（冻结=等人工，比杀更狠） | 第 3 个 turn Warn（「再失败一个 turn 就冻结」）→ 第 4 个 turn Freeze |
| 命令超时 / 连接被重置 / HTTP 503 连败 | 第 8 次（S1）或第 10 次（R-call）终止 | 阈放大到 24 / 30，第 24 次才终止（仍有界）；期间照常 Warn |
| 超时失败混入一次确定性失败 | 按终止档 | 立刻取消放大，阈回到 8/10 |
| 终止发生时已产出的分析 | 丢弃（transcript 无、result 只有错误串） | 随 `AgentError.message` 写进 `nodes/results/<id>.md` + WS error 帧 + 父会话载荷 |
| root agent（depth=0） | Terminate 降级为 Warn（不变） | 同左 |
| 想整体回退 | —— | `requireWarnBeforeTerminate=false`（行为与改前逐字同）；`enabled=false`（全关），均热读 |

## 5 验证

- `sbt Test/compile` 通过（703 主源 + 970 测试源）。
- `sbt "testOnly nebflow.core.processor.LoopGuardSpec nebflow.agent.LoopGuardWiringSpec"` → **Passed: Total 42, Failed 0, Errors 0**（LoopGuardSpec 36 个纯函数用例 + LoopGuardWiringSpec 6 个 wiring 用例，全绿）。
- `LoopGuardSpec`（纯函数矩阵）新增钉子覆盖：`①` R 两轴 soft 档存在（`R-call: the call axis now HAS a warning stage`）、`②` grace 阶梯（`ladder: first touching the hard threshold only WARNS`）、`③` 暂态分类正反例含 `/docs/503.md` 不误判、`④` 暂态放大（S1 8→24、R-call 10→30）与混合回退、`⑤` `transientAware=false` / `requireWarnBeforeTerminate=false` 两条回退开关、`⑥` 配置逐键兜底与 `transientFactor` 夹取、`⑦` Warn 文案带工具名、`⑧` crossTurn 从「第 3 turn 冻结」改为「第 3 turn 警告、第 4 turn 冻结」。
- `LoopGuardWiringSpec`（真实 AgentActor + 恒败 Read 工具，收紧阈值 soft=1/hard=3）：L0 提醒确实进下一轮请求消息、L1 fatal 链、root 豁免、L2 复发冻结、三条冻结唤醒路径 —— 6 条既有钉子全部保持通过（本批未改这些语义）。
- 未跑全量 `sbt test`（按协作方约束只跑受影响 spec）。

### 5.1 行为证据检索：现场已不可取证（记录以免重复挖）

想用**真实日志**替代代码推演（比读码硬），逐处检索后确认当日现场已不在盘上：

| 检索面 | 结果 |
|---|---|
| `~/.stand/logs/stand.log`（13.8 MB，起点 2026-11-02 18:57 —— 当日 jar-swap 重启之后） | 命中 5 条 `loop-detected`，**全部是本会话自己的工具调用字面量**（我 grep 该关键词的命令行），零条引擎事件 |
| `~/.stand/logs/stand.2026-11-01.0.log`（12.8 MB，11-01 归档） | 零命中 |
| `~/.stand/logs/daemon.out` / `daemon.out.old`（21 MB，最后写入 17:11 —— 早于 18:04 事故） | 同样只有本会话命令行字面量 |
| `.nebflow/flow-map-events.jsonl`（1.9 KB，重启后新起） | 零 loop/failed 事件 |
| `.nebflow/flow-maps/*/flow-map.json`（49 个 delegate 作用域） | 零 `failed` / `cancelled` / `blocked` 节点残留 |
| `~/.stand/projects/*/.nebflow/` 与 `.nebflow/worktrees/*/`（39 个 worktree）下的 `results/*.md` | 零 `loop-detected` 命中（若案例 1 的节点 result 还在，`results/<nodeId>.md` 里应当就是那句 loop-detected 错误串——正是本批 §3.5 要修的那条通路） |

结论：三例事故的现场（18:00 前后）在 18:57 的重启中已被轮转/清理，**行为证据不可得**；本报告的证据面 = 代码 file:line + 事故描述。若下次复现，取证点按上表顺序捞即可（`results/<nodeId>.md` 与 `flow-map-events.jsonl` 的 `barrier-blocked` / `mount-stalled` / `dispatch-notify` 三类事件最值钱）。

## 6 未做 / 残留风险（含设计问题）

1. **transcript 仍缺被终止那一轮**：L1 终止腿不投 `ToolsComplete`，本轮 assistant/tool_result 不落盘（文本已抢救进 result，但会话历史里没有）。要根治需把终止改成「先按 `ToolsComplete` 落盘批次、再走 fatal」——`freezeAfter` 已有同形实现（`AgentCommand.ToolsComplete.freezeAfter`），可复刻为 `terminateAfter`，但需要把 `LlmFailed` 的 fatal 体抽成可复用函数，属独立批次。
2. **dispatch-notify 在 delegate 作用域可能蒸发**（Q2）：`ProjectRuntimeRegistry` 取不到宿主时只 WARN，事件面却记「triggered」。这会让 D5 停等的补偿腿无人执行——建议单独一批修（把 delegate 作用域的触发面绑到创建者会话，或让 `flushBatch` 在 no-op 时不 `markSent`）。
3. **D5 failed 零结算未改**（刻意）：失败上游不放行下游是既有裁定，本批只降低触发概率 + 提高失败现场的可读性。若作者希望「失败也放行下游」，那是语义变更，需单独立项。
4. **暂态分类靠错误文本启发**（§3.4）：需要工具层「可重试」结构化标签才能根治。
5. **grace 记账是会话级**（`warnedKeys` 不清）：同一指纹在被警告过一次后，即使中间换过方法，再次形成循环会直接终止而不重新警告。判断为可接受（同一会话内同一「工具+参数」的组合已被明确警告过），记录在案。
6. 未在真实 delegate 批上复现三例事故（无法回放当日数据）；行为对照表由纯函数矩阵 + 挂载点既有 wiring 钉子支撑。
