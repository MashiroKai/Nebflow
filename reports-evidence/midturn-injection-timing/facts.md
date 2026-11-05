# 三条注入腿的当前注入时机 —— 现状事实表 + 改前必红断言 + 风险判定

分支：`delegate/delegate-midturn-inject` · 基线 commit `2af513702` · 全部读数 = 本机 harness（真 `AgentActor` + 脚本化 LLM + 门文件 Bash，零 spawn / 零端口 / 未触碰 6138）。

## 0. 结论先行（三句话）

1. **用户腿今天有两套承载形，只有一套是工具边界注入的**：纯文本帧 → `ImmediateInput` → **工具边界**（绿）；带附件 / 引用 / 引言 / 排队条 drain 的帧 → `UserInput` → `pendingUserInputs` → **回合末 head-forward**（红）。作者报障命中的是后者。
2. **Mail delegate 腿已经是工具边界注入**（两条承载形都绿）：`DelegateNotify.inject` 走 `ImmediateInput`，`MailTool` 续聊腿走 `UserInput(source="mail")` 但被 `TurnBoundaryDrains.drainMailPackage` 显式豁免、整包在边界注入。
3. **node 腿已经是工具边界注入**（绿）：`NodeDelivery.injectRunning` 投 `ImmediateInput(source="system")`。

## 1. 现状事实表

证据口径：`E:<日志行>` = `logs/*.log` 里逐字可查的行（`[MTIT *]` 行、`event=` 行，全文见 §6 原始摘录）；`C:file:line` = 代码锚点（只用于定位，不单独充当结论）。

### 腿 1 · 用户腿（纯文本，帧 `immediateInput` / CLI `userMessage`）

| 列 | 读数 |
|---|---|
| 注入点 | `C:src/main/scala/nebflow/gateway/WsSessionChatHandlers.scala:908` `handleImmediateInput` → `:923` `handleUserText` → `C:WebSocketRoutes.scala:2457` / `:2484` `dispatchUserText` → `C:WebSocketRoutes.scala:2508` `ref ! AgentCommand.ImmediateInput(…, fromUser = true)`；CLI 同腿 = `:937` `handleUserMessage` |
| 判定条件 | 接收侧 `C:src/main/scala/nebflow/agent/AgentProcessing.scala:1641` → 入 `pendingImmediateInputs`；边界 `C:AgentProcessing.scala:698-702` `TurnBoundaryDrains.drainHeadExpanded(pendingImmediateInputs, compactionPending = state.pendingCompaction.isDefined)`；两个门 = ①compaction 挂起零消费 ②每边界**只取队首一件** |
| 实际投递时机 | **工具边界**（本回合内）。`E:[MTIT L1] calls=2 roundCompleteBeforeBoundary2=0 inFirst=false inSecond=true secondHasBatchEcho=true` —— 第 2 次 LLM 请求（工具批次完成的续轮）已含正文，第 1 次请求不含；请求位置证据不可被「回合末注入」伪造（回合末注入不可能追溯进一条已发出的请求） |
| 失败回落 | 无内部边界（在飞轮即最后一轮）→ 回合末 `C:AgentFinishTurn.scala:570-586`（事件名 `immediate-input-injected-at-turn-end`）；transport 中断 → `C:AgentProcessing.scala:278-295`（`immediate-input-injected-after-recoverable-abort`，同一 `drainHeadExpanded`）；compaction 挂起 → 队列原样保留，`CompactionComplete` 再排空 |

### 腿 2 · 用户腿第二承载形（typeless 帧：附件 / 引用 / 引言 / `inputMode='queue'` 的 drain 腿）

| 列 | 读数 |
|---|---|
| 注入点 | `C:src/main/scala/nebflow/gateway/WebSocketRoutes.scala:2232-2241` `ensureAgent(msgSessionId)(ref => ref ! AgentCommand.UserInput(content, None, clientMessageId, Some(blocksList), chatWidth))`（`WsDispatch.handlers` 无 `type` 登记 ⇒ 落 typeless 腿）。前端来源：`C:src/main/resources/web/js/input.js:139-150` 默认用户帧（带附件/引用/引言）、`:1428` queue 模式 drain 腿 |
| 判定条件 | `C:AgentProcessing.scala:1594` → 入 `pendingUserInputs`；工具边界的两个消费器都**不收它**：`immediate-input` 只读 `pendingImmediateInputs`，`drainMailPackage` 只收 `TurnBoundaryDrains.isMailSupplement`（`C:TurnBoundaryDrains.scala:161-182` = `fromUser=false && source=="mail"`）。规范面 = 2026-09-15 root 裁定「排队消息逐条独立 turn」（`C:AgentProcessing.scala:738-743` 注释） |
| 实际投递时机 | **回合末**。`E:[MTIT L1b] callsAfterBoundary1=2 inBoundaryRequest=Some(false) callsAfterTurnEnd=4 landing=3` —— 边界续轮请求里没有它，回合走完（两个边界 + 回合末轮）之后才以**新回合**出现（`C:AgentFinishTurn.scala:369` `pendingUserInputs.headOption.traverse_(msg => ctx.self ! msg)` = head-forward 给 idle） |
| 失败回落 | 无回落概念——它本身就是回落路径（idle 直投本回合立即开 turn，只是永远等不到「本回合」） |

**必答判断题的答案**：用户腿今天**不是**全部工具边界即注入。挡在回合末的是**承载形分流**（typeless 帧 → `UserInput` → `pendingUserInputs`），不是 compaction 门禁、不是 `drainHeadExpanded` 只取一件、也不是状态机不在 Processing（那些是腿 1 的次要门，见 §2 G1 读数）。

### 腿 3 · Mail delegate 腿（两条承载形都为绿）

| 列 | 读数 |
|---|---|
| 注入点 | ①`C:src/main/scala/nebflow/core/delegate/DelegateNotify.scala:54-78` `inject` → `ImmediateInput(source="delegate-notify", eventType="flow-notify", fromUser=false)`。②`C:src/main/scala/nebflow/core/tools/MailTool.scala:1329-1339`（`deliverToDelegate` live 腿）→ `UserInput(source="mail", replyTo=supervisorRef, fromUser=false)` |
| 判定条件 | ①同腿 1（`pendingImmediateInputs` + `drainHeadExpanded`）。②`pendingUserInputs` + `TurnBoundaryDrains.drainMailPackage`（`C:TurnBoundaryDrains.scala:171`，`compactionPending` 零消费），多件整包合并（`mergeMailPackage`），边界整包注入 |
| 实际投递时机 | **工具边界**。`E:[MTIT L2] delegateInjectLive=true calls=2 inSecond=true secondHasBatchEcho=true` · `E:[MTIT L2b] calls=2 inSecond=true` |
| 失败回落 | ①目标不在注册表 ⇒ `inject` 返回 false，调用方走滞留补投（`DelegateNotify.scala:60-63`）；②compaction 挂起 ⇒ 留队，`CompactionComplete` 排空；idle 会话 ⇒ 既有即时腿立即开 turn（`MailBoundaryInjectionSpec` W3） |

### 腿 4 · node 腿

| 列 | 读数 |
|---|---|
| 注入点 | `C:src/main/scala/nebflow/core/project/NodeDelivery.scala:242` `sendNodeMessage` → `:346` `injectRunning` → `:375` `record.ref ! AgentCommand.ImmediateInput(text = "[NODE-MESSAGE]…", source=Some("system"), fromUser=false)`（先落 `NodeMessageLedger` `queued` 行，`C:NodeDelivery.scala:283-300`） |
| 判定条件 | 同腿 1（`pendingImmediateInputs` + `drainHeadExpanded`，每边界一件）；节点状态必须 `running`（interrupted 也走注入面） |
| 实际投递时机 | **工具边界**。`E:[MTIT L3] sendNodeMessage=Right(Message delivered to running node 'n-l3' …) calls=2 inSecond=true` |
| 失败回落 | 非 running ⇒ `appendToTaskRoute`（落 task「待启动补充」，等节点启动/重跑时进 brief）；会话查无 ⇒ `appendUndelivered`（「注入未达」留痕）；compaction 挂起 ⇒ 留队 |

> 同文件另一条 `ImmediateInput`（`C:NodeDelivery.scala:870` `offerRootNotify`，source=`"node"`，节点状态→根会话通知）不是 dispatcher→node 腿，但它与腿 1/2/4 共用同一条 `pendingImmediateInputs` 边界队列，且是 `windowItems` 展开的唯一写入点（`TurnBoundaryDrains.expandWindowFlush`：N 件载荷在同一 turn 内逐件注入）——收敛改动必须把它算进「每边界预算 K」的口径。

## 2. 判据层的两个门（纯函数读数，解释「腿 1 也会落到回合末」的条件）

`E:[MTIT G1] headOnlyDrained=List(A) stayed=List(B) compactionPendingDrained=0 mailPackageDrained=2 mailCompactionPendingDrained=0`

- **每边界只取队首一件**（`TurnBoundaryDrains.drainHead`）：同一边界到达的 N 件用户输入 = N 个边界（N 次全上下文往返）。队首长期积压时会推后后来者 ⇒ 长回合里表现为「越晚到越晚注入」。
- **compaction 挂起 ⇒ 零消费**（三条腿同款守卫）：`pendingCompaction.isDefined` 期间边界不消费任何队列（注入进将被 summary 替换的历史 = 消费即丢失）。compaction 若横跨整个工具循环，三条腿都会落到回合末。
- 附注（探针读数，不作为判据）：`roundComplete` 在本夹具四轮读数恒 0（`E:[MTIT L1] roundCompleteBeforeBoundary2=0`、`E:[MTIT L5] roundCompleteBeforeIdle=0`），故未用它做「本回合 / 下一回合」判据；判据一律取 LLM 请求位置。

## 3. 五条断言的红 / 绿现状（`src/test/scala/nebflow/agent/MidTurnInjectionTimingSpec.scala`）

`E:[error] Failed: Total 8, Failed 1, Errors 0, Passed 7`（`logs/midturn-injection-timing-spec.log`）

| 编号 | 断言 | 现状 | 读数 |
|---|---|---|---|
| L1 | 用户腿（`ImmediateInput`，纯文本承载形）在本回合下一次 LLM 请求里 | **绿** | `inFirst=false inSecond=true secondHasBatchEcho=true` |
| **L1b** | 用户腿（typeless 帧 `UserInput`，附件/引用/队列 drain 承载形）同样在本回合下一次请求里 | **红（缺陷证据）** | `inBoundaryRequest=Some(false) landing=3` ⇒ 落在回合之后 |
| L2 | Mail(delegate) 腿 `DelegateNotify.inject` 在同一回合边界注入 | **绿** | `delegateInjectLive=true inSecond=true` |
| L2b | Mail 补充件腿 `UserInput(source="mail")`（`MailTool` 续聊腿承载形）在边界注入 | **绿** | `inSecond=true` |
| L3 | node 腿 `sendNodeMessage`（running 节点）在边界注入 | **绿** | `sendNodeMessage=Right(…) inSecond=true` |
| L4 | 幂等：同一条消息跨连续两个工具边界只注入一次 | **绿** | `afterBoundary1=Some(1) afterBoundary2=Some(1)` |
| L5 | 回落：回合结束后到达的消息仍送达且只送达一次 | **绿** | `inNextTurn=Some(true) deliveries=0,0,0,1`，calls=4（无第 5 次） |
| G1 | 判据层门（compaction / 队首一件）机械读数 | 绿（辅证，不计入五条） | 见 §2 |

> L1b 是**改前必红**：修的是承载形分流，不是放宽断言。L1/L2/L2b/L3/L4/L5 是「不许改坏」的现状红线。

## 4. 基线（`logs/baseline.log`，7 套件同批跑）

`E:[error] Failed: Total 43, Failed 1, Errors 0, Passed 42`（`BASELINE_EXIT=1`）

- 唯一红 = `SubAgentInboxMirrorSpec ③ -2`（`SubAgentInboxMirrorSpec.scala:381`）：静态调用点计数钉 `14`，当前树实际 `16`。**既有红，非本批引入**，证据：`git diff --stat HEAD -- src/main` 为空（本批主源码零改动），且 `git show HEAD:…` 逐文件计数 = AgentActor 3 / AgentFinishTurn 3 / AgentFrozen 1 / AgentIdle 4 / AgentProcessing 5 = 16 ≠ 14（钉值自 #939 与后续并批起就未重同步）。见 `logs/baseline-pre-existing-red-proof.log`。
- 其余 6 套件 + 该套件其余 12 条断言全绿：`BusyConsoleInputInjectionSpec`、`MailBoundaryInjectionSpec`、`DeviceProvenanceInjectionGateSpec`、`Agent.inboxMirror` 其余项、`CompactionQueueStoreSpec`、`NodeMessagePersistenceSpec`、`DelegateNotifyInjectSpec`。
- **结论：本批零新增红。**

## 5. 风险判定（三条腿能否收敛到同一注入判定单点）

**结论：能，且不需要重写 `AgentActor` 状态机分支、不改变回合生命周期语义。**

单点的正确位置 = **已有的工具边界决策层** `TurnBoundaryDrains`（纯函数、零 `IO`、已被 `MailBoundaryInjectionSpec` 的纯函数层直测）。今天它是**三条判据散落**（`drainHead` 的 `pendingImmediateInputs` / `drainMailPackage` 的 `isMailSupplement` / 回合末 `pendingUserInputs` head-forward），收敛 = 把「本边界该不该注入这件」抽成**一个谓词 + 一个每边界预算**，两条队列**保留不动**：

1. 谓词单点 `TurnBoundaryDrains.shouldInjectAtBoundary`：`ImmediateInput`（恒真）∪ `isMailSupplement`（服务端补充件）∪「真人 typeless 承载形（`UserInput` + `clientMessageId` + `blocks`，即腿 2）」。
2. 每边界预算：现状「一件 imm + 一件整包 mail」→ 统一为「每边界至多 K 件、保序」；`K=1` 即逐字节等价于今天（除腿 2 变绿）。
3. 腿 2 变绿的最小改法（**推荐**，纯接线不改语义）：typeless 腿改投 `ImmediateInput`（它自带 `blocks` 字段），把 `clientMessageId` 随行带到 `ImmediateInput`（需在 `AgentCommand.ImmediateInput` 追加**默认值字段** `clientMessageId: Option[String] = None`，位置参数构造点用命名参数或置末以免错位），`AgentProcessing.scala:1641` 的 dedup 复用 `recentMessageIds` 判据（与 `:1595` 同一套）。
   - 关键取舍：这是**与 2026-09-15「排队消息逐条独立 turn」裁定的正面冲突面**——那条裁定管的是「排队队列」，而 2026-10-28 queuedirect 裁定已把纯文本改成 `immediateInput` 直通；本改法把剩下的「带附件/引用的真人消息」也归到同一通道，等价于把那条裁定的适用范围收窄到「显式 queue 模式」。**这是政策裁定，不是技术风险，需作者点头**（备选：保留 queue 模式语义，只在 `inputMode='direct'` 的 typeless 帧上生效）。

**预计改哪些文件**

| 文件 | 改动 | 性质 |
|---|---|---|
| `src/main/scala/nebflow/gateway/WebSocketRoutes.scala:2232-2241` | typeless 腿 `UserInput` → `ImmediateInput`（保留 blocks / chatWidth / clientMessageId） | 接线，1 处 |
| `src/main/scala/nebflow/actor/AgentCommand.scala:276-316` | `ImmediateInput` 追加 `clientMessageId: Option[String] = None`（置末） | 增量字段，默认值 ⇒ 既有构造点零改 |
| `src/main/scala/nebflow/agent/AgentProcessing.scala:1641-1657` | `ImmediateInput` 分支补 `clientMessageId` 查重（与 `:1595` 同判据）+ 铸牌沿用 | 分支内增量，**不改状态机形状** |
| `src/main/scala/nebflow/agent/AgentIdle.scala:878` | idle 转换腿把 `clientMessageId` / `blocks` 原样带进 `UserInput` | 字段透传 |
| `src/main/scala/nebflow/agent/TurnBoundaryDrains.scala` | 抽 `shouldInjectAtBoundary` 单点（`isMailSupplement` 成为其子句） | 纯函数，零 `IO` |
| （可选）`src/main/scala/nebflow/agent/AgentFinishTurn.scala:369` | head-forward 谓词换成同一单点 | 可选；先不动更安全 |

**blast radius**：只覆盖「root 会话收真人输入」这一条入水口 + 该输入的 idle 转换与 dedup 面。工具边界、回合生命周期、compaction、子代理屏障、完成债（`owedCompletion`）、mail 包合并语义**全部不动**；前端零改动（帧形态不变，只是后端换承载）；`reports-evidence` 之外无 schema/落盘格式变化（`ImmediateInput` 新字段走 `CompactionQueueStore` 的既有 codec，需一条 round-trip 断言）。

**是否需要动状态机**：**否**。注入判定不动 `Processing / Idle / Frozen` 的迁移，也不新增注入点；改动落在「既有边界的队列消费谓词 + 一个入水口的承载类型」。若日后要求把 `pendingImmediateInputs` 与 `pendingUserInputs` **合成一条队列**，那才是状态机/持久化面重写（`CompactionQueueStore`、`persistQueues`、崩溃恢复三方同步），本批不建议。

（本批**不触发**状态机重写标记：单点收敛可在纯函数决策层 + 一处接线完成，故本文档不写该标记行。）

## 6. 原始日志行（`logs/midturn-injection-timing-spec.log` 逐字摘录，8 条）

```
08:52:55.293 INFO nebflow.agent.lifecycle - … event=immediate-input-queued detail=textLen=14
08:52:55.995 INFO nebflow.agent.lifecycle - … event=immediate-input-injected-at-tools-complete detail=batch=1 texts=L1-USER-改用快速路径 remaining=0
08:52:56.648 INFO nebflow.agent.lifecycle - … event=user-input-queued detail=textLen=19 pending=1
08:52:59.023 INFO nebflow.agent.lifecycle - … event=immediate-input-queued detail=textLen=25
08:52:59.693 INFO nebflow.agent.lifecycle - … event=immediate-input-injected-at-tools-complete detail=batch=1 texts=L2-DELEGATE-NOTIFY 节点失败通知 remaining=0
08:53:00.313 INFO nebflow.agent.lifecycle - … event=user-input-queued detail=textLen=30 pending=1 source=mail
08:53:00.972 INFO nebflow.agent.lifecycle - … event=mail-injected-at-tools-complete detail=items=1 blocks=0 agesMs=659ms textLen=30 remainingUserInputs=0
08:53:01.740 INFO nebflow.agent.lifecycle - … event=immediate-input-queued detail=textLen=109
08:53:02.410 INFO nebflow.agent.lifecycle - … event=immediate-input-injected-at-tools-complete detail=batch=1 texts=[NODE-MESSAGE] 来源：任务分发器 NodeMessage · 节点 remaining=0
08:53:03.056 INFO nebflow.agent.lifecycle - … event=immediate-input-queued detail=textLen=19
08:53:03.703 INFO nebflow.agent.lifecycle - … event=immediate-input-injected-at-tools-complete detail=batch=1 texts=L4-IDEMPOTENT 只出现一次 remaining=0
```

**L1b 的负证据就在这两行之间**：`08:52:56.648 event=user-input-queued detail=textLen=19 pending=1`（typeless 承载形入队）之后，**没有任何** `*-injected-at-tools-complete` 行对应它——它在整个工具循环里从未被边界消费，直到回合结束后才由 head-forward 作为新回合投出。同一时刻 L1/L2/L2b/L3/L4 各有一条注入事件行在场，对照组同一夹具、同一窗口。

`event=immediate-input-injected-at-turn-end` 在本批 8 条用例里**零出现**（grep 计数 0）——即落回合末的那条消息走的是 `AgentFinishTurn.scala:369` 的 head-forward 腿（`pendingUserInputs`），不是 `:570` 的 imm-at-turn-end 腿。

## 7. 复现命令

```
sbt -mem 2600 "testOnly nebflow.agent.MidTurnInjectionTimingSpec"          # 8 条：7 绿 1 红(L1b)
sbt -mem 2600 "testOnly nebflow.agent.BusyConsoleInputInjectionSpec nebflow.agent.MailBoundaryInjectionSpec \
  nebflow.gateway.DeviceProvenanceInjectionGateSpec nebflow.agent.SubAgentInboxMirrorSpec \
  nebflow.agent.CompactionQueueStoreSpec nebflow.core.project.NodeMessagePersistenceSpec \
  nebflow.core.delegate.DelegateNotifyInjectSpec"                          # 43 条：42 绿 1 红(既有)
```

## 8. 增补：收敛落地后的实测（实现节点，2026-11-05）

本节是**实现后**追加的增补：§1-§7 是改前现状，本节是「三条腿收敛到单点」的真实读数与
**结构性无法统一的部分**（如实记，不假装统一）。

### 8.1 判定单点与三条腿的调用点

单点 = `src/main/scala/nebflow/agent/InjectionTiming.scala`：
`decide`（`InjectionTiming.scala:158`，唯一的「此刻注入 / 等下一个边界」判定）、
`planBoundary`（`:217`，把两条队列按该判定合成一次边界的注入计划）、
`hasInjectable`（`:254`，回合末腿的「该不该开注入轮」）。

| 腿 | 生产调用点（都指向同一符号） | 判定 |
|---|---|---|
| ①用户（纯文本 `ImmediateInput`） | `AgentProcessing.scala:710` `InjectionTiming.planBoundary(...)` → `InjectionTiming.scala:158` | `InjectNow`（immediate 队首一件） |
| ①′用户（typeless 帧 `UserInput`+`clientMessageId`+`blocks`） | 同上一条调用点（同一计划里 `humanFrames` 字段） | `InjectNow`（本批新纳入；此前落回合末） |
| ②Mail（`DelegateNotify` → `ImmediateInput`；`MailTool` → `UserInput(source=mail)`） | 同上一条调用点（`immediate` / `mails` 字段） | `InjectNow`（mail 整包） |
| ③node（`NodeDelivery.injectRunning` → `ImmediateInput(source=system)`） | 同上一条调用点（`immediate` 字段） | `InjectNow` |
| 回落面（中断恢复 / 回合末） | `AgentProcessing.scala:282`（`Stage.AbortRecovery`）、`AgentFinishTurn.scala:573`+`:578`（`Stage.TurnEnd`） | 同判据、不同 stage 口径 |

判据子句也只剩一份：`isMailSupplement`（`:101`）与 `isHumanFrame`（`:124`）都在该 object，
`TurnBoundaryDrains.isMailSupplement` / `drainMailPackage` 改为**转发**（`TurnBoundaryDrains.scala:167` / `:187`）。

### 8.2 五条断言 + L1b 的实测（`logs/green-5efbc90c6-20261105_092120-run1.log` / `-run2.log`）

两份日志 = 同一 HEAD（`5efbc90c6`）**连跑两次**，各自 `Passed: Total 8, Failed 0, Errors 0, Passed 8`
（8/8 全绿，含改前必红的 L1b）。逐条读数（两次一致）：

`E:[MTIT L1] calls=2 roundCompleteBeforeBoundary2=0 inFirst=false inSecond=true secondHasBatchEcho=true`
`E:[MTIT L1b] callsAfterBoundary1=2 inBoundaryRequest=Some(true) callsAfterTurnEnd=3 landing=1,2`
`E:[MTIT L2] delegateInjectLive=true calls=2 inSecond=true`
`E:[MTIT L2b] calls=2 inSecond=true`
`E:[MTIT L3] sendNodeMessage=Right(...) calls=2 inSecond=true`
`E:[MTIT L4] calls=3 afterBoundary1=Some(1) afterBoundary2=Some(1)`
`E:[MTIT L5] roundCompleteBeforeIdle=0 calls=4 inTurnEndRound=Some(false) inNextTurn=Some(true) deliveries=0,0,0,1`

L1b 由红转绿的确切读数变化：`inBoundaryRequest=Some(false) landing=3` → **`Some(true) landing=1,2`**
—— 正文出现在**第 2 次 LLM 请求**（本次工具批次的续轮）里 = 本回合内的工具边界注入，
不是「最终送达」。

### 8.3 反向验红（`logs/reverse-red.log`）：单点确实承载三条腿

做法：把 `InjectionTiming.decide` **真改代码**短路为「本边界永不注入」（恒 `HoldForNextBoundary`），
跑同一套断言 ⇒ `E:Failed: Total 8, Failed 6, Errors 0, Passed 2`：
**L1 / L1b / L2 / L2b / L3 / L4 六条复红**（L1 `second=run the gated batches` 无正文、
L1b `landingIndex=3`、L4 `afterBoundary1=Some(0)`），L5 与 G1 仍绿（它们本就是回落面／纯函数读数）。
复原后 `git diff` 输出为空、`git status --short` 只剩该次红日志本身（随后随证据提交）；
复原态下连跑两次全绿（§8.2）。

### 8.2-b 幂等（L4）曾出现一次红 —— 如实记录与根因（不静默抹掉）

**事实**：09:18:52 的一次运行读数 `Failed: Total 8, Failed 1, Passed 7`，红的是
`L4 幂等（转录里必须恰一次）`：`afterBoundary1=Some(1) afterBoundary2=Some(2)`，
日志另存 `logs/red-l4-stale-queue-replay-20261105_091852.log`（未删除、未覆盖）。

**根因（不是判定点跨边界重复注入）**：该次日志的边界事件行是
`event=immediate-input-injected-at-tools-complete detail=batch=1 texts=L4-IDEMPOTENT … remaining=1`
—— **第一个边界投完之后队列里还剩一件**，第二个边界把剩下的那件又投了一次。残留那件的来源：
本 spec 夹具的 `dataRoot = target/test-midturn-injection-timing/data` **跨多次运行共用**，而会话
spawn 即自投 `AgentCommand.RecoverPersistedQueues`（`AgentActor.scala:1035`）→
`CompactionQueueStore.load(<dataRoot>/sessions/<sid>/injection-queues.json)`
（`AgentIdle.scala:738-752`）⇒ **上一轮运行留在盘上的 F2 队列快照**被当「崩溃未投递件」
重放进 `pendingImmediateInputs`。同一正文于是两份（上一轮残留 + 本轮新入队），第一个边界消费
新件、第二个边界消费残留件。判定点自身的单进程幂等是成立的：**消费即出队**（`remaining*`
里不再有它）+ 窗展开件 `windowItems` 清零 + 边界注入同拍登记 `clientMessageId`。

**修法（断言强度一字未放宽）**：`MidTurnInjectionTimingSpec.withGatedTurn` 在每个用例开始清空
`<dataRoot>/sessions`（测试代码 `src/test/scala/nebflow/agent/MidTurnInjectionTimingSpec.scala`
夹具段，附同文注释）⇒ 每个用例都从「新进程 + 空队列快照」起步 = 生产 spawn 无残留态的初始条件；
L4 仍断言「第二个边界的请求里该正文恰出现 1 次」。**为何现在确定**：同一 HEAD（`5efbc90c6`）
连跑两次均 8/8 绿、L4 两次都是 `afterBoundary1=Some(1) afterBoundary2=Some(1)`
（收尾证据 §8.2）。

**顺带如实标注的生产面（非本批引入、本批未改）**：`injection-queues.json` 的写点是「入队 +
边界 drain 后快照」（`AgentProcessing.scala:994` 等），崩溃发生在「已注入、快照未刷新」的窗口时，
重启重放仍可能把同一件再投一次 —— 这是 F2 崩溃重放面的既有窗口（同族注释见
`NodeMessageLedger` / `DeliveredReceiptLedger` 的「快照-投递窗口」段），与「工具边界判定重复注入」
不是同一件事，本批按最小改动纪律未动持久化面。

### 8.4 结构性**无法**统一的腿与回落口径（如实记）

1. **纯文本真人 `UserInput`（无 blocks / `fromUser=true`）仍落回合末。** 这是 `BusyConsoleInputInjectionSpec`
   W3 的负对照面（2026-09-15「排队消息逐条独立 turn」裁定）。它**不是**本轮要修的承载形：
   纯文本真人件在前端已走 `immediateInput` 帧（腿①，边界注入），仍走 `UserInput` 的那份是
   显式 queue 旧轨。本批**刻意**不统一它 —— 统一它会直接推翻 Pinned 裁定，且没有作者点头。
2. **前端 queue 模式 drain 腿带附件时与直投帧逐字节同形** ⇒ 引擎侧不可区分 ⇒ 该形现在也走
   边界注入，等价于把 2026-09-15 裁定收窄为「显式 queue 模式的纯文本件」。**这是政策收窄、
   非技术不确定**，代码注释与本节都在此如实留痕，待作者裁定；若作者要求保留 queue 模式语义，
   需要前端在帧上加可判别标记（协议增量，本轮未做）。
3. **compaction 挂起期间三腿一律留队（零消费）**：注入进将被 summary 替换的历史 = 消费即丢失；
   回落 = `CompactionComplete` 再排空。判据在同一单点（`decide` 的首个守卫）。
4. **`AgentActor.drainQueuesAfterCompaction`（压缩窗全量 flush 面）未纳入本单点**：它是压缩完成
   后的一次性 flush 车道（既有语义：整批事件 + 一件排队消息），不属于「工具边界判定」。
   本批零改动，行为逐字保留。
5. **回合已结束时到达的真人 typeless 帧**：`Stage.TurnEnd` 下 `decide` 返回 `HoldForNextBoundary`
   ⇒ 留队由 `AgentFinishTurn` 的 head-forward 作为**新回合**投出（不丢消息、不改回合边界）。

### 8.5 回归（`logs/post.log` · **合并 main 之前**的读数）

`E:[error] Failed: Total 43, Failed 1, Errors 0, Passed 42` —— 与 §4 基线**逐条一致**，
唯一红仍是既有 `SubAgentInboxMirrorSpec ③ -2`（静态调用点定值 14 vs 树内 16，非本批引入；
本批 `emitInjectedUserEvent(` 调用点数未变）。**本批零新增红**，且 `BusyConsoleInputInjectionSpec`
的 W3 负对照仍绿。

> ⚠️ 本节的「既有红」在合并 main 后**已失效**：main 的 `f050d6b35` 把该条断言 re-pin 成
> 「注入帧写者单点」判据（`"injected" -> true.asJson` 在 AgentActor 内 0 命中 ∧
> `InjectionChannel` 内恰 1 命中），那条陈旧计数钉随批消失。合并后的实测见 §9.2 ——
> **43/43 全绿、零红**。旧结论不得复用。

### 8.6 回合生命周期零改动（作者闸未触发，逐条声明）

本批**没有**改 `AgentFinishTurn` 的回合末 head-forward 逻辑：`pendingUserInputs.headOption.traverse_(msg => ctx.self ! msg)`
（`AgentFinishTurn.scala:369`）与 `pendingUserInputs.tail` 保留逐字不变；唯一改动是把回合末
**immediate 腿**的入口条件改为同一单点的 `hasInjectable(..., Stage.TurnEnd, ...)`
（机械等价于原 `pendingCompaction.isEmpty && pendingImmediateInputs.nonEmpty`）。
不提前/延后结束回合、不合并回合、不改 `Completed` 事件语义、不改 `pendingUserInputs` 的排队语义
⇒ **不触发** `state-machine-rewrite-required`，无需作者闸停手。

## 9. 合并 main（外部注入统一通道批 `f050d6b35`）后的复测（2026-11-05 09:38+）

### 9.1 合并与两个单点的分工（不互相吞并）

- 合并 commit `303571c47`（`git merge main`，**零文本冲突**）。
- main 改到本批的两个文件，改动面 = **外部事件气泡的呈现级去重**（`dedup = true` 四处站点）；
  **不涉及**工具边界的注入时机判定 ⇒ 与本批完全正交。
- **两个单点各管一件事，未合并、未互相吞并**：
  - `nebflow/core/InjectionChannel.deliver`（main）= 注入行**呈现/投递**面单点：去重闸 → 落盘
    （`appendUiMessages`）→ 广播（`wsSend`）；其头注释逐字写明「不做注入时机」。
  - `nebflow/agent/InjectionTiming.decide`（本批）= **注入时机**单点：此刻在工具边界注入还是
    留队等下一个边界。
- 合并后核对可见化腿：`AgentActor#emitInjectedUserEvent` 已委派 `InjectionChannel.deliver`；
  本批的两处边界气泡发射（`AgentProcessing` 的 `immEventIO` / `mailEventIO`）与回合末回落面
  （`AgentFinishTurn:644`）**都只经 `emitInjectedUserEvent`**，没有留下「合并前形态」的自己拼帧/
  自己广播旧代码（`AgentProcessing` 内零 `appendUiMessages`；`wsSend` 仅剩 `roundComplete` /
  `doneJson` 这类非注入帧）。真人 typeless 帧**不产注入气泡**（`source = None`，前端本有乐观
  渲染）⇒ 与去重闸无交集。

### 9.2 合并后实测（HEAD `303571c47`，日志名带 sha）

| 套件 | 日志 | 读数 |
|---|---|---|
| `MidTurnInjectionTimingSpec`（连跑两次） | `logs/postmerge-green-303571c47-20261105_093807-run1.log` / `-run2.log` | **8/8 绿 ×2**；`L1b inBoundaryRequest=Some(true) landing=1,2`；`L4 afterBoundary1=Some(1) afterBoundary2=Some(1)` |
| 邻居三套（main 新增）`ExternalInjectionChannelSpec` + `InjectionIntakeContractSpec` + `InjectionSourceContractSpec` | `logs/postmerge-neighbor-specs-303571c47-20261105_093807.log` | **21/21 全绿** |
| 回归批 7 套（含 `SubAgentInboxMirrorSpec`、`BusyConsoleInputInjectionSpec` W3） | `logs/postmerge-regression-303571c47-20261105_093807.log` | **43/43 全绿、零红**（旧「既有红」已随 main 的 re-pin 消失，见 §8.5 注） |
| 合并后反向验红 | `logs/postmerge-reverse-red-303571c47-20261105_094024.log` | 单点短路 ⇒ `Failed: Total 8, Failed 6, Passed 2`：**L1/L1b/L2/L2b/L3/L4 六条复红**（L1 `inSecond=false`、L1b `landing=3`、L4 `afterBoundary1=Some(0)`），L5/G1 仍绿 |
| 复原后复跑 | `logs/postmerge-green-after-restore-303571c47-20261105_094427.log` | **8/8 绿**（复原证据：`git diff` 为空、工作树只剩证据日志） |

L4 幂等（那条曾出现一次红的断言）在合并后的树上**连跑两次都是 1 次注入**，且夹具已按 §8.2-b
清空 `<dataRoot>/sessions` ⇒ 可复现地绿。

