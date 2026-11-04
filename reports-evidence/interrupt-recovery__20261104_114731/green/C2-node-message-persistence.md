# C-2 · node 消息持久化 + 节点上下文恢复（红 → 绿取证）

**批次**：9.19 版中断恢复实现考古 · C-2 腿（node 侧）
**日期**：2026-11-04
**分支**：`delegate/delegate-nebflow-70e8d8ab`（父 = `8884379eb` C-1 收口）
**台架**：沿用 `InterruptRecoveryLegsSpec`（V3 腿，harness 节点 `ad770bfdb` 产，红证 `red/V3.log`）+ 本节点新增两件 spec
**纪律**：零进程启动、不碰生产实例 pid 41715、不调 `stand stop/restart`、不 push/不 merge/不碰 main

---

## 1. 三条投递语义在「重启前 / 重启后」的落地差异（file:line）

`node:` 消息三态投递（裁定②③④，2026-09-05 六条语义批）在 9.19 与今天**逐字一致**
（9.19 = tag `249a83958` / `949ce9159`；今日 = 纯搬家到 `NodeDelivery.scala`，见
`archaeology/9.19-recovery.md`）。下表行号 = **本批改动后**的今日行号（括号内 = 考古给出的
9.19 行号 / 改动前行号）。

| 语义 | 今日写点（改动后） | 9.19（`NodeEngine.scala`） | 重启**前** | 重启**后** |
|---|---|---|---|---|
| ④ 终态拒收 | `NodeDelivery.scala:258-269`（拒收文案 `:267`） | `:1832` | 返回 Left，零投递零落盘 | 同（终态节点没有会话，无需恢复） |
| ② running 注入 | `:346` `injectRunning` → `:375` `record.ref ! ImmediateInput(...)` → `:396` 事件行 | `:1835/:1862-1915` | tell 返回即「投递成功」；**落盘在接收方**：idle 直投腿要等 `AgentFinishTurn.scala:649` 才写转录、busy 腿要等 `AgentProcessing.scala:1651-1655` 才写 `injection-queues.json` | **崩溃点若落在上述两个落盘点之前 ⇒ 永久丢件**（详见 §2）。若已落盘，则 spawn 期 `AgentIdle.scala:738` `RecoverPersistedQueues` / transcript 水合会把消息带回来（V3a 护栏实证） |
| ② interrupted 走注入 | `:322` → `injectRunning` | `:1844` | 同上（非终态休眠节点按注入面处理，会话已死则落 `appendUndelivered`） | 同上；会话死后由 node 认领链 resume（`NodeRecovery.scala:580`）——**旧实现 resume 不重放注入面消息** |
| ② 注入未达兜底 | `:429` `appendUndelivered`（`== 分发器补充（... 注入未达） ==` 段落） | `:1916-1934` | 追加进 **node.task** ⇒ 随 flow-map 落盘（**持久**） | 持久：重启后节点启动/续跑经 `buildInput`（`NodeStarter.scala:284-326`，读 `node.task`）或 `NodeList(detail)` 可读到 |
| ③ wiring/pending 追加 | `:477` `appendToTaskRoute` | `:1846/:1936-1968` | 追加进 node.task ⇒ 随 flow-map 落盘（**持久**） | 持久（同上一行） |
| ⑤ 留痕 | `:396-402`（`FlowMapEventLog` `node-message` 行） | `:1835` 后段 | 落 `.nebflow/flow-map-events.jsonl` | 落盘**在**，但**没有任何回放腿读它** ⇒ 只是审计面，不是恢复面 |

**Mail 侧单点**：`MailTool.scala:1133/:1170/:1217` 三处（9.19 `:691-717`）都调
`NodeEngine.sendNodeMessage`，本批修补对它们一次性生效（零第二实现）。

## 2. 「消息只在内存里、重启即丢」的确切路径

**不是**「全部消息都丢」——`appendToTaskRoute` / `appendUndelivered` 落的是 `node.task`
（flow-map 持久面），永不丢。丢的**只有 running/interrupted 的注入腿**，窗口如下（按执行序）：

1. `NodeDelivery.scala:375` `record.ref ! AgentCommand.ImmediateInput(...)` —— **fire-and-forget
   tell**（`AgentCommand` 投递为 tell 语义，无回执）。
2. 此刻消息只存在于：目标 actor 的**邮箱**；或 actor 已收下则存在
   `state.messages`（内存）或 `pendingImmediateInputs`（内存 `ExecutionContext`，
   `AgentProcessing.scala:1651`）。
3. 落盘点：
   - idle 直投腿：`AgentFinishTurn.scala:649` `saveMessagesForSession(sid, messagesWithImmediate)`
     —— 走完整轮 LLM 之后才写转录；
   - busy 腿：`AgentProcessing.scala:1655` `persistQueues` → `CompactionQueueStore.save`
     （`~/.stand/sessions/<sid>/injection-queues.json`）。
4. **step 1 与 step 3 之间进程死**（kill -9 / 断电 / 引擎重启）⇒ 磁盘上三处全空：
   `injection-queues.json` 无该条、transcript 无该条、`node.task` 无该条（裁定②要求
   running 时 task 不动）。**证据**：`red/V3-C2-prefix.log` 的 V3b 行
   `injection-queues=false, transcript=false, node.task=false, node-message-ledger=false`，
   同时 `flow-map-events.jsonl` 有 1 行 `injected -> …`（无人回放）。
5. 恢复期：`bootRecoveryClaim`（`NodeRecovery.scala:580`）只按 transcript + `node.task` 重建
   上下文 ⇒ 无回放面 ⇒ **永久丢件**（这正是 V3b 红证与审计 D5 的实测形态）。

窗口宽度不是微秒级：idle 腿的落盘点在一整轮 LLM 之后（分钟级）；busy 腿的
`persistQueues` 虽然紧跟入队，但 tell→actor 处理之间仍是纯内存窗口。

## 3. 落盘 / 重放 / 幂等键设计（实现）

### 3.1 落盘（append-only 账）

- 文件：`<workspace>/.nebflow/node-messages.jsonl`（与 `flow-map-events.jsonl` 同目录同形态，
  一行一次状态迁移，`os.write.append(..., createFolders = true)`）。
  实现 `NodeMessageLedger.path`（`NodeMessageLedger.scala:128`）/ `append`（`:162`）。
- **写点（投递前）**：`NodeDelivery.scala:271-300` —— `sendNodeMessage` 铸 id（
  `NodeMessageLedger.newId`，`:110`）→ append `queued` 行 → **才**分派路由。
  **写失败 ⇒ 投递失败**（`NODE_MESSAGE_NOT_PERSISTED`，`:288-301`）：宁可拒收也不制造
  「已投但盘上零字节」的窗口。
- **写点（投递后状态）**：
  - `:398-408` 注入成功 → `injected`（soft，失败只 WARN）；
  - `:515-521` task 追加成功 → `appended`；`appendUndelivered` 走同一 `appended` 终态。
- **正文携带幂等标记**：header / 分节头追加 `· 消息号 nm-…`（`:362-363`、`:440-441`、
  `:489-490`）——标记是**普通文本**，因此转录、`node.task`、台账三面都能凭同一字符串
  判定「这条消息到没到」。

### 3.2 重放（两条腿，互不相交）

| 腿 | 位置 | 覆盖 | 行为 |
|---|---|---|---|
| ① 认领回放（快段） | `NodeRecovery.scala:568` `withLedgerReplay` + `:342` `ledgerReplayAppendix`，接线在 `:699`（loop 双会话）与 `:728`（普通节点） | **被 boot 认领**的 Running/Interrupted 节点 | 未被消费的消息拼进 `ResumeContext.resumePrompt`（节点续跑的第一条输入）⇒ 节点**继续干活**时上下文完整；同时翻 `replayed` + 落 `boot-recovery` 事件行 |
| ② 队列兜底回放 | `NodeRecovery.scala:430` `replayPendingNodeMessages`；调用点 `ProjectCrashRecovery.scala:193`（项目 sweep）/ `:529`（delegate 作用域 sweep） | **未被认领**的 wiring/pending 节点（崩溃落在「台账 queued 之后、task 追加之前」的微窗口） | 把消息按「注入未达」段落追加进 `node.task`（durable carrier），翻 `appended` |
| 收尾 | `NodeRecovery.scala:744` `bootRecoveryStart` 尾部 → `:501` `markReplayedConsumed` | 续跑会话已把回放文本写进转录 | 翻 `consumed`（终态，永不再回放） |
| 终态一致性 | `NodeRecovery.scala:558` `ledgerDeadMark`，接线 `:606`（class c 判死）与 `:430` 腿内 | 节点已终态 | 未消费消息翻 `dead`，**永不**注入（裁定④：终态不收消息） |

### 3.3 幂等键（三层，机械可求值）

1. **消息身份牌** `id = nm-<base36 毫秒>-<6 hex>`（`NodeMessageLedger.scala:110`，铸一次，全生命周期不变）。
2. **状态机**（`fold` 取最高档，`NodeMessageLedger.scala:185`）：
   `queued`(0) < `injected`/`appended`(1) < `replayed`(2) < `consumed`/`dead`(3)。
   只有 0/1/2 进回放候选（`Entry.pending`），3 = 终态永不再注入。
3. **转录判据（权威）**：回放前扫 `sessionStore.loadMessagesForSession(sessionRef)`，命中
   `消息号 <id>` ⇒ 视为已消费（`ReplayPlan.of`，`NodeRecovery.scala:310-340`；匹配器
   `NodeMessageLedger.inMessages:120`）——覆盖「接收方已消费、台账未标」的崩溃点；
   `node.task` 正文命中同理（`:430` 腿内 `inText:123`）。

## 4. 节点上下文恢复（复用既有链，零手写 spawn）

| 需要恢复什么 | 机制 | file:line |
|---|---|---|
| 已收到的补充（含本次补的 node 消息） | `ResumeContext.resumePrompt` + 台账回放附录 | `NodeRecovery.scala:342` → `:568` |
| 已执行到哪 | transcript 水合（`recoveredMessages`）+ resume prompt 断点句（`nodeResumePrompt`） | `NodeRecovery.scala:720-728`、`NodeEngineContract.scala:782-789` |
| 链归属 | spawn 期重取 `chainContextOf`（resume 同样注身份） | `NodeStarter.scala:120-127` |
| 会话/sessionId | resume 复用旧 `sessionRef`（transcript 单文件续写 + F2 队列重放白捡） | `NodeStarter.scala:359`、`:353-354` |
| 启动全链 | `bootRecoveryStart` → `startNode(nodeId, Some(ctx))`（**不手写 spawn**） | `NodeRecovery.scala:744-750`、`NodeStarter.scala:72-188` |

**覆盖范围（两类节点同一链）**：项目图节点 = `ProjectCrashRecovery.recoverProject`（`:148-225`）；
delegate 作用域链节点 = `recoverDelegateScopes` → `recoverDelegateScopeOn`（`:448-544`）。
两者都逐节点调 `bootRecoveryClaim` ⇒ 本批的回放腿对**两类节点同时生效**，无覆盖缺口。

## 5. 红 → 绿（日志原文）

| 腿 | 红 | 绿 |
|---|---|---|
| V3b（台架，fire-and-forget 窗口） | `red/V3-C2-prefix.log`：`durable replay surfaces after a SUCCESSFUL delivery: injection-queues=false, transcript=false, node.task=false, node-message-ledger=false`；`ledger rows for this message =`（空）；`Failed: Total 6, Failed 1` | `green/V3.log`：`… node-message-ledger=true`；`ledger rows = nm-…=injected`；`Passed: Total 6, Failed 0, Passed 1, Skipped 5`（`exit=0`） |
| V3c（本节点新增，回放幂等） | 与 V3b 同一条 test body（V3b 先失败即中止）⇒ 红证由 S2 红日志给出：`boot#1 … prompt carries marker = false` | `green/V3.log`：`boot#1 resume prompt carries the message = true`、`boot#1 pre-crash context restored = true`、`boot#2 resume prompt carries the message AGAIN = false`、`final ledger state = Some(consumed)` |
| S1 落盘前置 | `red/NodeMessagePersistence-prefix.log`：`ledger rows = 0`、`probe received with marker = false`、`S1: a successful delivery must leave ledger rows on disk` | `green/NodeMessagePersistence.log`：`ledger rows = 2 : queued/nm-…, injected/nm-…`、`folding state = nm-…=injected`、`probe received with marker = true`、`Passed: Total 3` |
| S2 重放幂等 | 同上：`prompt carries marker = false`（boot#1）、`ledger after send =`（空） | `boot#1=replayed` → `boot#2 prompt carries marker = false` → `consumed`；`probe tells = 1`；`pre-crash context restored = true` |
| S3 terminal 仍拒收 | **两侧同绿**（不变量闸，本批未改）：`left contains NODE_TERMINAL_NO_MESSAGE`、`ledger rows = 0`、`probe received = 0` | 同 |
| S4 队列兜底回放 | `red/NodeMessageReplayQueue-prefix.log`：`value replayPendingNodeMessages is not a member of NodeEngine`（改前无此腿 ⇒ 编译即红） | `green/NodeMessageReplayQueue.log`：`replay #1 actions = 1 ; #2 actions = 0 (idempotent)`、`marker occurrences = 1`、`Passed: Total 1` |

复跑命令：

```bash
cd "/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-70e8d8ab"
bash reports-evidence/20261104_interrupt-recovery/run.sh V3 green       # 台架 V3（V3a 护栏 + V3b/V3c）
sbt -batch "testOnly nebflow.core.project.NodeMessagePersistenceSpec"     # S1/S2/S3
sbt -batch "testOnly nebflow.core.project.NodeMessageReplayQueueSpec"     # S4
```

红证复跑（改前形态）：`git stash push -- src/main/scala` 后跑同样命令
（`NodeMessageReplayQueueSpec` 需临时移出源码树，因其引用改后才有的接口——改前红即「无此能力」）。

## 6. 回归（既有语义不回退）

| 组 | 命令（sbt testOnly） | 结果 |
|---|---|---|
| A 组（node 消息/投递/崩溃恢复域） | `NodeMessageSpec NodeMessagePersistenceSpec NodeMessageReplayQueueSpec NodeMessageReactivationSpec MailNodeSubAddressSpec InterruptRecoverySpec BootCrashRecoverySpec` | `Passed: Total 51, Failed 0, Errors 0, Passed 51`（`green/regression-A.log`） |
| B 组（作用域恢复 ws / barrier / root 补投） | `DelegateScopeRecoveryWsSendSpec NodeBarrierDeliverySpec RootDeliveryRedeliverySpec` | `Passed: Total 23, Failed 0, Errors 0, Passed 23`（`green/regression-B.log`） |

**已知既有抖动（与本批无关，基线同样复现）**：把上表 10 件 spec 塞进**同一 JVM 批次**跑时，
`BootCrashRecoverySpec.C1`（`imms.count("[Node 'rec-a' completed]") == 1`，`BootCrashRecoverySpec.scala:331`）
偶发 `Obtained 0`（root 通知打包窗 `Defaults.RootNotifyQuietMs` 5s 与断言采样点的竞速）。
证据：`git stash push -- src/main/scala`（改前）跑同一批次 ⇒ **同样 1 failed / 69 passed**；
C1 单跑改前改后均绿（改后连跑 2 次 `Passed: Total 14`）。分组跑（A/B）全绿。
同批次日志留在 `green/regression-C2.log`（10 件同批、1 failed = 上述抖动）——保留原文不删，
用于对照「分组绿 / 同批抖动」这一事实。

## 7. 改动清单

| 文件 | 性质 | 内容 |
|---|---|---|
| `src/main/scala/nebflow/core/project/NodeMessageLedger.scala` | **新增**（229 行） | append-only 台账：`newId`/`markerOf`/`path`/`append`/`appendSoft`/`load`/`fold`/`entries`/`pendingFor`/`transition`/`inMessages`/`inText` |
| `NodeDelivery.scala` | 改 | `sendNodeMessage` 落盘前置（`:271-323`）；`injectRunning` 带标记 + `injected` 状态行（`:346-410`）；`appendUndelivered`（`:429-449`）、`appendToTaskRoute`（`:477-521`）分节头带标记 + `appended` 状态行 |
| `NodeRecovery.scala` | 改 | `ReplayPlan`（`:295-340`）、`ledgerReplayAppendix`（`:342-428`）、`replayPendingNodeMessages`（`:430-499`）、`markReplayedConsumed`（`:501-520`）、`ledgerDeadMark`（`:558-562`）、`withLedgerReplay`（`:568-578`）、认领回放接线（`:699`/`:728`）、`bootRecoveryStart` 收尾（`:744-750`）、class c 判死收口（`:606`） |
| `ProjectCrashRecovery.scala` | 改 | 两条 sweep 各调一次兜底回放（`:190-198` 项目 / `:529-535` delegate 作用域） |
| `src/test/scala/.../NodeMessagePersistenceSpec.scala` | **新增** | S1 落盘前置 / S2 重放幂等 / S3 terminal 仍拒收 |
| `src/test/scala/.../NodeMessageReplayQueueSpec.scala` | **新增** | S4 队列兜底回放 + 二次 boot 不重复 |
| `src/test/scala/.../InterruptRecoveryLegsSpec.scala` | 改（仅 V3 腿） | V3b `durable` 判据加入台账面 + STRICT 断言；新增 V3c（回放幂等）；V3 头注记录改动理由 |

## 8. 假设 / 未做

- **假设**：`<workspace>/.nebflow/node-messages.jsonl` 与 `flow-map-events.jsonl` 同属
  「该 flow map 的持久面」——项目节点落项目 workspace，delegate 作用域落其座位基工作区，
  重启后由**同一引擎实例**（同 workspace）回放，不跨引擎读取。
- **假设**：`消息号 <id>` 标记对模型是可忽略的噪声（与既有 `[NODE-MESSAGE] … 时间戳` 同族）；
  spec 只断言前缀 `[NODE-MESSAGE]` 仍成立，无格式硬线被破坏。
- 未做：跨**进程**端到端（真 kill -9 + 重启 + 真 LLM）——台架纪律同 README（零进程启动）。
- 未做：`injection-queues.json` 与台账的去重合并（两面对同一消息都可能命中，回放腿以
  台账 + 转录判据为准，`injection-queues` 重放是既有腿、本批未改；两腿同时命中时
  `RecoverPersistedQueues` 只会把消息交还给会话，台账随后在 boot 见到转录标记转 `consumed`）。
- 未做：`~/.stand/projects/stand/.stand/docs/` 知识库落件——本节点按任务书「不修改 ~/.stand
  下生产数据（取证只读）」未写 vault，知识化文档交文档节点处置。
- **风险（已知、未处理）**：台账 append-only 且无剪除——每条消息约 2–3 行（queued + 状态），
  单 workspace 长跑会线性增长（体量 = 消息条数 × 正文长度）。收口建议（后续批）：`consumed`/
  `dead` 行按 24h 或 90% 水位压紧（重写文件时保留未终态行），与 `RestartLossLedger` 的
  24h 剪除同族。本批不做是因为「压紧 = 白名单写面」，需与审计只读纪律一起裁定。

