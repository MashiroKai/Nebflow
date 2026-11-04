# 当前中断恢复体系审计 + 缺陷清单（任务 B）

- 审计时点：2026-11-04 11:20–11:45（生产实例 pid 41715 未重启、未触碰）
- 代码基线：worktree `.nebflow/worktrees/delegate-nebflow-70e8d8ab`（分支 `delegate/delegate-nebflow-70e8d8ab`，= main@64bd0d85b + 本批未提交区）
- 数据面：只读 `~/.stand`（未写入任何生产数据）
- 所有行号为该基线实际行号；「实测」= 我从盘上/日志亲自读到的原文，「机制」= 代码读出的路径

---

## 0. 结论摘要（六句话）

1. **今天 09:09 那次重启，恢复确实发生了**：3 个 delegate 同 id 复活（1b0c6317 / cfc822d8 / 4bff07ce，`stand.log:19`、`:20`、`:27`），2 个被判 failed（bb1a8bbb / 79bb6698，`stand.log:33`、`:34`）。
2. **被判 failed 的两条，不是「没被恢复」，是「被 standby 闸挡掉」**：判据原文 `standby at engine restart — the delegate was parked awaiting the author, not working`（`stand.log:9`，代码 `DelegateRevival.scala:107-116`）。
3. **salvage 通知没有静默投递失败**：两条通知在 09:09:47.637 被父会话（Nebula 根 `d7c3cb54`）**成功 drain 并注入**（`stand.log:911`；注入原文存于压缩前转录 `compaction/20261104-091623-before.json` 第 326 条）。
4. **通知是在约 6 分半后被「上下文压缩」吞掉的**：`09:16:23` 的 pingpong 压缩（文件 `compaction/20261104-091623-after.json` 首条内嵌 `<context-compact mode="pingpong" forkSeq=347 preservedRounds=0>`、`Compressed 347 messages into summary`，压后仅剩 13 条）把注入正文抹掉 → 父会话对失败零记忆，11:08 只能靠 `AgentControl` 工具回调查出来（现行转录 `#133`）。
5. **failed 之后会话完全能活，且同 id 原地复活、转录全量重放**：两条腿都不看 `task.status`（`SessionResumption.judge:168-180`、`DelegateRevival.wakeFrozen:154-173`），bb1a8bbb 实际上就是以 11:09:53 的非人工路径之外——**人工 Mail**——复活的（`stand.log:129469`，435 条转录）。
6. **缺的那一环就一句话**：`boot sweep` 的复活判定知识里没有「这个 standby 是在等自家链节点」这一型，而 `run-time watchdog` 只有收殓腿没有复活腿；所以今天所有活下来的会话，要么是本来就合格，要么靠作者手工 Mail。

> **V1 / V2 澄清（前置纠偏）**：仓里**不存在**名为 "startup recovery V1" 的启动恢复路径。V1/V2 是同一批（`d268ee689`「结果投递链丢失五向量实现」2026-09-03）的两个**向量号**：
> - **V1 = 删除级联**：`deleteSession` 时停掉 Delegate/SubTask/Ephemeral 子代理 + 终态化其任务行，防「父循环已退出、子结果静默蒸发」（`src/main/scala/nebflow/agent/SessionChildCascade.scala:9-27`）。**不在启动路径上。**
> - **V2 = 启动收殓**：`SubAgentStartupRecovery`（`src/main/scala/nebflow/agent/SubAgentStartupRecovery.scala:14-52`）。
> 因此本报告讲的「启动恢复」只有 V2 一条，它自身在 2026-10-22 / 10-28 / 11-01 又扩了三次（标记腿、台账结算分诊、冻结腿）。

---

## 1. 恢复体系全图：两条路径 + 谁调用 + 什么时机

### 1.1 路径 A：boot sweep（进程启动，恰一次，listen 之前）

链位（`src/main/scala/nebflow/gateway/GatewayMain.scala:1197`，按 `*>` 顺序）：

```
hubSetup *> recorderSetup *> nfCacheInvalidatorSetup *> pendingAskBootSweep *> taskTtlSweep
  *> subagentCrashSweep      ← 本条（delegate 收殓+复活）
  *> mailboxBootSweep        ← 邮箱对账（读收殓落下的终态行）
  *> taskIndexBackfill *> seedMinimalSet *> sourcePluginHeal
  *> startupMount            ← 挂载项目（恢复开启时跳过挂载期僵尸收殓）
  *> projectCrashSweep       ← 项目轨节点认领（快段）
  *> delegateScopeCrashSweep ← delegate 作用域轨节点认领
  *> projectBootWake *> mergeQueueBootSweep *> msgTokensBootReconcile ...
```

装配点 `GatewayMain.scala:834-856`：

```scala
SubAgentStartupRecovery.sweep(sharedResources.subAgentTaskStore, sessionStore,
  Some(RevivalDeps(resources = sharedResources, system = actorSystem, wsSend = Some(wsHub.broadcast))))
```

关键点在 `Some(...)`：**boot 恒传 ResurrectionDeps ⇒ `resources` 恒为 Some** ⇒ delegate 轨的通知走**持久邮箱**而不是 F2 队列（见 §3）。sweep 内的分工：

| 段 | 作用 | file:line |
|---|---|---|
| 在飞扫描 | `findAllTasks` 过滤 `running ∪ restarting ∪ ready-to-merge ∪ frozen` | `SubAgentStartupRecovery.scala:313-316`（集合定义 `:93`、`:101`） |
| ready-to-merge 分诊 | 有合并队列成员 = 合并轮在管（零触碰）；无成员 = 僵尸 → 收殓 | `:158`、`:195-232` |
| frozen 分诊 | 冻结行**绝不进收殓链**；有 deps 且 kill-switch 开则单独判复活 | `:164-194` |
| 复活判定 | 逐条 `DelegateRevival.eligibility` | `:266-268` |
| boot cap | `eligible.sortBy(-_.spawnedAt).take(cap)` 新者优先 | `:269-274` |
| 复活慢段 | 信号量护栏 + 逐条 fork + join 收口 | `:284-291` → `DelegateRevival.reviveAll:400-415` |
| 收殓同步段 | 复活集之外的孤儿串行收殓（F2/邮箱单写者纪律） | `:292-296` |
| 收尾 | 重启打断旗回溯 + 台账对账 | `:297`、`markInterruptedRecords:324-358`、`DelegateLedgerReconcile.reconcileInterruptedRecords` |

### 1.2 路径 B：运行期 watchdog（周期，60 s 一拍）

- 启动点：`GatewayMain.scala:2370-2377`，`interval = 60.seconds`，`stallMs = Defaults.RestartingStallWatchdogMs`
- 默认值：`src/main/scala/nebflow/shared/Defaults.scala:1856` → `600_000L`（10 min）
- 判据（`SubAgentStartupRecovery.scala:364-395`，谓词在 `:375-377`）：

```
任务行 ∈ findRunningTasks (running|restarting)          // SubAgentTaskStore.scala:311
  ∧ agentRegistry 无该 taskId 的活体行
  ∧ now - statusAt ≥ stallMs
⇒ 强制终态化 failed + salvage + 父通知
```

- **watchdog 只收殓、不复活**（直接调 `recoverOne`，`:391`）。这是路径 A/B 的语义裂缝（缺陷 D3）。
- 循环体 `run:401-407`：`watchdogSweep *> sleep(interval) >> loop`（错误自愈）。

### 1.3 恢复覆盖面一览

| 对象 | 恢复腿 | 判定入口 |
|---|---|---|
| delegate 任务（in-flight） | 同 id 复活（transcript checkpoint 续跑） | `DelegateRevival.eligibility:73-128` |
| delegate 任务（冻结） | 同 id 冻结唤醒 | `:174-194` → `DelegateRevival.wakeFrozen:154-173` |
| delegate 任务（standby） | **不复活**，落收殓链 | `:107-116` |
| delegate 任务（failed） | **boot 完全不扫描** | `inFlightTasks:313-316` |
| delegate 作用域图内节点 | 重挂宿主 → 节点认领 → rehydrate | `ProjectCrashRecovery.recoverDelegateScopes:256-308` |
| 项目节点 | 同上（项目轨） | `ProjectCrashRecovery.recoverAll:50-80` |

---

## 2. 复活/收殓判定闸清单（逐条 + file:line + 默认值）

`DelegateRevival.eligibility`（`src/main/scala/nebflow/agent/DelegateRevival.scala:73-128`）按**代码顺序**逐条判，任一命中即 `Some(reason)` ⇒ 落收殓链：

| # | 闸 | 判据原文/字段 | file:line | 默认值 / 开关 |
|---|---|---|---|---|
| 1 | kill-switch | `Defaults.DelegateRevivalEnabled` | `:74-75`；`Defaults.scala:1427-1428` | `true`（prop `nebflow.delegate.revival.enabled`） |
| 2 | source | 只复活 `source == "delegate"`（subtask 重新派便宜） | `:76-77` | — |
| 3 | registry 记录 | `DelegateRegistry.find(taskId)` 必须在 | `:79-80` | — |
| 4 | executor | 只复活内置执行器 `ExecutorRegistry.DefaultId` | `:98-99` | 外部执行器走 Mail 续聊腿 |
| 5 | cancel 墓碑 | `cancelRequestedAt` 在场 ⇒ **永不复活** | `:100-106` | — |
| 6 | **standby** | `standbySince` 在场 ∧ 无冻结 ⇒ 不复活（**今天挡掉 bb1a8bbb/79bb6698 的就是这条**） | `:107-116` | — |
| 7 | archived | `archivedAt` 在场 ⇒ 不复活 | `:117` | — |
| 8 | 座位目录 | `seatExists(rec.cwd)`（`Files.isDirectory`，构造失败 = 缺席） | `:118`、`:65-67` | — |
| 9 | 父会话 | `sessionStore.listSessions.exists(_.id == parentSessionId)` | `:96-97`、`:119` | — |
| 10 | 任务级预算 | `revivalCount < DelegateRevivalMaxPerTask` | `:82`、`:120-121`；`Defaults.scala:1434-1435` | `2`（prop `…maxPerTask`） |
| 11 | 年龄预算 | `now - spawnedAt ≤ DelegateRevivalMaxAgeMs`（冻结行不适用） | `:83`、`:122-123`；`Defaults.scala:1442-1443` | 7 天（prop `…maxAgeMs`） |
| 12 | 冻结保留期 | 冻结行改判 `DelegateFreeze.retentionMs` | `:90-95`、`:124-125`；`Defaults.scala:1049-1050`（14 天）、`DelegateFreeze.scala:374` | 14 天（prop `…freeze.retentionDays`） |

调用方（`SubAgentStartupRecovery.sweep`）追加的三道：

| # | 闸 | file:line | 默认值 |
|---|---|---|---|
| 13 | boot cap（按 `spawnedAt` 新者优先截取） | `:269-274`；`Defaults.scala:1450-1451` | `8`（prop `…bootCap`） |
| 14 | 复活并发（慢段信号量） | `DelegateRevival.reviveAll:402`；`Defaults.scala:1457-1458` | `2`（prop `…concurrency`） |
| 15 | 分诊：ready-to-merge（有队列成员零触碰 / 无成员收殓）、frozen（不收殓） | `:158`、`:195-232`、`:164-194` | — |

运行期 watchdog 追加（**不收殓资格判定，也不走 eligibility**）：

| # | 闸 | file:line | 默认值 |
|---|---|---|---|
| 16 | 状态 ∈ `running｜restarting` ∧ 无活体 ∧ 停滞 ≥ stallMs | `:364-395`（谓词 `:375-377`） | 600 000 ms（`Defaults.scala:1856`） |

**实测对照（今天 09:09 这一轮）**：3 复活、2 因 #6 挡、2 条 ready-to-merge 无归属落地——`stand.log:9`（revival 行）、`:35`（`terminalized=2 revived=3 revivalSkipped=2`）。

---

## 3. `salvaged and delivered to parent` 到底搬了什么、投到哪（本批关键题）

### 3.1 机制全链（A 腿：逐跳 file:line）

`recoverOne`（`SubAgentStartupRecovery.scala:420-531`）：

| 跳 | 动作 | 落点 / file:line |
|---|---|---|
| ① | 取 partial result：读子会话全文转录，取**最后一条非空 assistant 文本** | `:433-436` + `extractLastAssistant:551-555` |
| ② | 父会话在否 | `:437`（`sessionStore.listSessions`） |
| ③ | 造事件：`source = delegate｜subtask`、`eventType = "failed"`、`payload = 头 + partial`、`metadata{taskId, agentName, description, lostToCrash｜lostToWatchdog, recoveredBy}`、`correlationId = taskId` | `:445-457` |
| ④ | 跨设备腿（`delegatedBy` 在场）→ 设备待发区 | `:473-480` → `DeviceDelegate.enqueueLostResultNotice` |
| ⑤ | **本地 + delegate 轨**：`MailboxProducer.deliver(…, identity = taskId, kind = "delegate-terminal", coalesce = false, producer = "startup-recovery")` | `:487-500` |
| ⑤' | 本地 + `resources = None`（legacy/测试）→ F2 注入队 | `:501`、`notifyOnce:538-545` |
| ⑤'' | subtask 轨 → F2 注入队 | `:503` |
| ⑤''' | 父会话不存在 → **不投** | `:504` |
| ⑥ | 终态化：`status = failed` + `lastError` 文案 + `completedAt` | `:508-525`（文案 `:516-518`） |

投递落地与消费：

| 环节 | 动作 | file:line |
|---|---|---|
| 生产者 | 父会话在否复核（不在 ⇒ `Failed("parent-session-gone")`）；就地重试 3 次；门铃 best-effort | `MailboxProducer.scala:55-61`、`:85-93`、`:82` |
| 入箱 | `mailOnce` 原子落 `sessions/<sid>/mailbox/pending/<seq>-<identity>.json` | `core/mail/SessionMailbox.scala:168-200`、`:134-148` |
| 门铃 | `MailboxArrived`（会话不 live ⇒ **静默 no-op，信留盘**） | `MailboxDrain.scala:89-97`（`:93`） |
| 唤醒点 W1 | spawn/恢复：taken 重注 + pending 全取 | `AgentIdle.scala:738-779`（`:765-773`） |
| 唤醒点 W2/W3/W5 | 回合边界 barrier / idle 收口 / 门铃 | `MailboxDrain.scala:121-131`、空闲面 `:110-115` |
| 注入形态 | 单件 = payload 原文 + retryable 提示；多件 = `<system-reminder>` 合批 | `MailboxDrain.scala:146-161`、`injectIdleTurn:205-259` |
| 墓碑 | 注入后 `unlinkTaken` → 删条目 + 写 `delivered.json`（cap 1024，drop-oldest） | `:245` → `SessionMailbox.scala:323-335`、`:355-366`、`:341` |
| 对账闸 | boot 对账读墓碑，已消费 | `MailboxBootSweep.scala:114-130`；`SessionMailbox.deliveredContains:369-379` |

F2 老链（**delegate 轨在 boot 已不走它**）：`notifyOnce:538-545` → `CompactionQueueStore.save` → `sessions/<sid>/injection-queues.json`（`CompactionQueueStore.scala:25`、`:171-179`）；回放在 `AgentIdle.scala:738-754`（`RecoverPersistedQueues` → `load` → `pendingEvents`）。

**搬了什么 / 丢了什么**：
- 搬：`[task lost: crash] Delegate "<description 前 N 字>" (agent=…, session=…) …` + 「Partial result recovered from the child's disk transcript:」+ **最后一条 assistant 文本全文**。
- 丢：分支名 / worktree / 提交 / 链 id / 节点完成集 / 工具产出清单 / 结构化 metadata（全部不进 payload）→ 父会话只能看到一段碎文字（缺陷 D7）。

### 3.2 实例判证（B 腿）：**投了、注入了、被压缩吞了**

| # | 硬证据 | 原文 |
|---|---|---|
| 1 | 投递成功（入箱） | boot 段无 `mailbox-producer FAILED` WARN；`boxesNonEmpty=3`（`stand.log:37`） |
| 2 | **drain 注入成功** | `stand.log:911` `09:09:47.637 … Nebula/d7c3cb54 event=mailbox-drained detail=events=2 takenSub=2 outstanding=0 sources=delegate/failed,delegate/failed` |
| 3 | 注入正文在转录里（压缩前） | `sessions/d7c3cb54-…/compaction/20261104-091623-before.json` 第 326 条：`source=external`、ts `1793754587648`（=09:09:47.648）、`<system-reminder>` 合批，正文逐字含 bb1a8bbb 与 79bb6698 两条 `[task lost: crash]` + partial result |
| 4 | 压缩把它吞掉 | 同一目录 `20261104-091623-after.json`（13 条）：`task lost` 命中 **0**，bb1a8bbb 只剩摘要里任务板 2 处 |
| 5 | 现行转录（201 条）零痕迹 | `grep "task lost: crash" d7c3cb54-….json` = 0；父 11:08 靠 `AgentControl` 工具返回（转录 `#133`）才得知 failed |
| 6 | 父会话目录**没有** `injection-queues.json` | 这是**设计行为**：delegate 轨 boot 恒走邮箱（`SubAgentStartupRecovery.scala:487-500`，`resources=Some`），F2 只在 `resources=None`/subtask 轨用；全库 grep 零命中 ≠ 丢件 |
| 7 | `delivered-receipts.json` 里的 bb1a8bbb | 另一张表：`DeliveredReceiptLedger`（`core/processor/DeliveredReceiptLedger.scala:29,71,96`，写入点 `AgentIdle.scala:497/539/622`、`AgentFinishTurn.scala:522`）——旧链重投抑制台账，**与 F2 不同源**；它在场 ⇔ 注入腿真的跑过，反向佐证 #2 |

**三选一结论：①「投了但没人读」和 ②「投递链某跳静默丢」都不是；也不是 ③「根本没投」。**
准确答案是**第四种**：**投了 → 注入了 → 单次消费 → 约 6 分半后被上下文压缩从工作记忆里抹掉**。
根因行号：`SubAgentStartupRecovery.scala:500` 对 `MailboxProducer.deliver` 的返回值 `.void`（投递结果被丢弃）、`:516-518` 文案恒称 "delivered to parent"、`SessionMailbox.scala:355-366` 墓碑把「消费过」记为「已交付」、`MailboxBootSweep.scala:114-130` 据此 `synthesized=0` 永不补投。

### 3.3 cb580112（C 腿）现状

| 面 | 值 | 说明 |
|---|---|---|
| 任务行 | `status=completed`、`spawnedAt=1793760305308`（**10:45:05**）、`completedAt=1793762976381`（11:29:36）、`lastError=null` | 今天 10:45 才创建 ⇒ **从未经过 boot 收殓** |
| registry | `reportStatus=null`、`reportDetail=null`、`workReport`=完整回执、`archivedAt=1793762976414`（11:29:36） | 干完并落回执，但从未申报 status |
| 日志 | `11:27:41` 「ended its turn WITHOUT a delegate_report … reminder armed, NO terminal verdict, parent NOT notified」（`stand.log:148849`）；`11:29:36` delegate-judge 两腿缺席回落（`stand.log:151050`）→ completed | 「干完不发回执」形态 |

⇒ **cb580112 不是 salvage 投递案例**（它没死过），是「完成不自动收敛 / 无 reportStatus」形态的证据。作者说的「一条 Mail 后同 id 原地复活 374 条」是 `SessionResumption.resumeInPlace` 腿，与 bb1a8bbb 同一机制。

---

## 4. `status = failed` 之后这个会话还能不能被唤醒？

**能，而且人不需要重建会话。** 当前只有**人工腿**，没有自动腿。

| 问 | 答 | file:line |
|---|---|---|
| 走哪条腿 | Mail `delegate:<id>` → `deliverToDelegate` 三态分流：活体注入 / 冻结唤醒 / 其余续命 | `core/tools/MailTool.scala:802`（前缀）、`:856`（路由）、`:1272`（入口）、`:1302-1388`（三态） |
| 冻结记录 | `DelegateRevival.wakeFrozen` | `MailTool.scala:1360-1373` → `DelegateRevival.scala:154-173` |
| 其余（含 failed） | `continueDelegate` → `SessionResumption.judge` → `resumeInPlace` | `MailTool.scala:1388/1408/1446-1463/1487`；`core/delegate/SessionResumption.scala:168-180`、`:219-381` |
| 会不会看 `task.status` | **完全不看**（judge 只拦 cancel 墓碑与转录读不出） | `SessionResumption.scala:168-180`；`DelegateRevival.wakeFrozen:161-171` |
| 保同 sessionId 吗 | **保**（原位） | `SessionResumption.scala:256`（`sessionId = id // 🔴 原位`）；`DelegateRevival.scala:230` |
| 重放 transcript 吗 | **全量重放**（`initialMessages = transcript`） | `SessionResumption.scala:227/263`；`DelegateRevival.scala:202-207/241` |
| 状态翻回 running 吗 | 翻，但**不清旧错** | `SessionResumption.scala:234-240`（只 `updateStatus(…,"running")`）；`DelegateRevival` 走 `markRevived`（`SubAgentTaskStore.scala:226-245`，清 lastError/completedAt） |
| 链归属会丢吗 | 不丢：同 id spawn + `DelegateFlowHosts.mount(delegateId = 原 id)` 重开同一张图 | `SessionResumption.scala:293-307` |
| 有没有自动腿 | **没有**：sweep 只选集 `running｜restarting｜ready-to-merge｜frozen`、watchdog 只选集 `running｜restarting` | `SubAgentStartupRecovery.scala:313-316`；`SubAgentTaskStore.scala:311` |

**实测**：`stand.log:129469` `11:09:53.699 [session-resume] resumed IN PLACE delegate-nebflow-bb1a8bbb (transcript=435 msgs, parentLive=true, scopeMounted=true …) — same session id, chain ownership untouched`。

**副作用（缺陷 D4）**：bb1a8bbb 任务行现在三元不一致 —— `status=running` ∧ `lastError=process restart during in-flight task…` ∧ `completedAt=1793754569206`（09:09 那次收殓的旧值）；`restartInterruptedAt` 被 `clearRestartInterrupted`（`SessionResumption.scala:333`）摘掉，但 `lastError` 没人擦。

---

## 5. node 侧单列：`node:` 三态投递在重启前后各怎么落地

### 5.1 三态的落地点（`sendNodeMessage`，`core/project/NodeDelivery.scala:229-275`）

| 态 | 判据 | 消息去哪 | 在内存还是盘上 | file:line |
|---|---|---|---|---|
| **running** | `status == Running`（另：`Interrupted` 也走这条） | 广播 `AgentCommand.ImmediateInput(source="system", text="[NODE-MESSAGE] 头\n\n正文")` 给活会话 | **先内存**：actor mailbox → `pendingImmediateInputs`（`actor/AgentState.scala:562`）；**再落盘**：入队即 `persistQueues` → `sessions/<sid>/injection-queues.json` | `NodeDelivery.scala:257-258`、`:266-267`、`injectRunning:300-345`（tell 在 `:317`）；`AgentProcessing.scala:1644-1650`；`AgentActor.scala:558-565`；`CompactionQueueStore.scala:25` |
| **wiring / pending** | 其余非终态 | **追加到 `NodeDef.task` 字段**（分节头「== 分发器补充（NodeMessage <ts>） ==」） | **盘上**：`flow-map.json`（或 scoped `flow-maps/<scope>/flow-map.json`）+ 分片 `tasks/<nodeId>.md` | `NodeDelivery.scala:403-441`（追加 `:417`）→ `FlowMapStore.scala:273-280`、`persistState:714`、`writeTaskFiles:831-838`；字段定义 `ProjectTypes.scala:667`；启动读回 `NodeStarter.scala:284-285` |
| **terminal** | `completed｜failed｜cancelled｜blocked`（`ProjectTypes.scala:44`） | **拒绝**，不落盘 | 无 | `NodeDelivery.scala:250-256`（`NODE_TERMINAL_NO_MESSAGE`）、竞态版 `:450-456` |
| 兜底「注入未达」 | running 但活会话已丢 | 仍追加到 `task`，带「（注入未达）」标注 | 盘上 | `appendUndelivered:359-396`（`:370`） |

前缀与分节常量单点：`NodeMessagePrefix = "[NODE-MESSAGE]"`（`NodeEngineContract.scala:217`）、头部 `:223-225`、分节 `:232-235`、事件类型 `node-message`（`:220`）；事件落 `flow-map-events.jsonl`（`FlowMapEventLog.scala:11`）。

### 5.2 重启后节点恢复什么

| 面 | 恢复吗 | file:line |
|---|---|---|
| 资格集 | `Running ∪ (Interrupted ∧ Defaults.ShutdownInterruptEnabled)` | `ProjectCrashRecovery.scala:160-166`（作用域轨 `:491-496`） |
| 「认领」= 什么 | **单事务 CAS 翻 `Running｜Interrupted → Pending`**，同时清 `bgWait`、`reportPendingSince`、`reportReminderCount`；返回 false 即不认领 | `NodeRecovery.scala:346-367` |
| 认领痕迹 | `flow-map-events.jsonl` 一行 `boot-recovery / claimed (resume): …` + 内存 `bootRecoveryQueue`（防收殓误判） | `NodeRecovery.scala:371-383`、`NodeEngine.scala:376-377` |
| 续跑内容 | 读盘转录 `loadMessagesForSession(sid)` → `ResumeContext(recoveredMessages, resumePrompt)` → 慢段 `startNode(id, Some(ctx))` → `initialMessages = recoveredMessages` | `NodeRecovery.scala:438-445`、`:464-467`、`NodeStarter.scala:774-777` |
| 转录丢失 | `failNode("crash recovery: session transcript lost…")` | `NodeRecovery.scala:309-336` |
| 已收到的补充（wiring/pending 期） | **恢复**：写在 `node.task` 里，启动 `buildInput` 读 | `NodeStarter.scala:284-285` |
| 已收到的补充（running 期） | **靠会话队列重放**：同 sessionId 复用 + W1 扫箱；不在 node 定义上 | `AgentIdle.scala:738-779`、`MailboxDrain.scala:265-278` |
| 已执行到哪 | 节点 `status` + `loopRound/loopPhase` 保留（claim 是 `f.copy` 局部改） | `NodeRecovery.scala:351-362` |
| 链归属 | **不丢**：`chainId` 是 `flow-map.json` 里的持久字段，claim 原样带过；ChainLedger 由 TtlTick 的 `reconcileChainLedger` 懒重建 | `ProjectTypes.scala:946-969`；`FlowMapStore.scala:244-269`；调用点 `ProjectActor.scala:921` |
| 慢段并发护栏 | 信号量 | `ProjectCrashRecovery.scala:205-220` |

### 5.3 「只在内存、重启即丢」的路径（明确回答）

存在，**三条窄窗**：

1. **`tell` → 入队之间**：`record.ref ! ImmediateInput`（`NodeDelivery.scala:317`）是 fire-and-forget；落盘发生在**接收方**入队处理里（`AgentProcessing.scala:1646-1650`）。若进程在这两个时点之间死亡，消息**不在任何持久面**；唯一残留是事件日志那行 `injected -> live session $sid: $text`（`NodeDelivery.scala:332-338`）——**没有任何代码回放这行事件日志**。
2. **`sessionId` 为空的会话**：`persistQueues` 的 `case None => IO.unit`（`AgentActor.scala:565`）⇒ 队列不落盘，入队即只在内存。
3. **actor mailbox 里的其它短消息**（非本批新增窗口，既有事实）：进程死亡即蒸发。

反过来说：**node 消息没有「独立的待投队列」**——running 期靠会话 `injection-queues.json`，wiring/pending 期靠 `node.task`；两者都不存在「重启后逐条重放 node-message 事件」的腿。

---

## 6. 缺陷清单（按严重度）

### D1【致命】收殓通知只注入一次，压缩即抹除；"delivered to parent" 文案与父子两侧可感知事实脱节

- **现象**：今天 09:09:47 两条 salvage 通知注入父会话，09:16:23 自动压缩后父会话对失败零记忆；11:08 作者问起时父只能查工具。而任务行 `lastError` 恒称「partial result salvaged and delivered to parent」。
- **根因**（三处叠加）：
  1. 投递返回值被丢弃：`SubAgentStartupRecovery.scala:500` `.void`（`MailResult.Failed`/`AlreadyPresent` 都当成功）；
  2. 文案与事实解耦：`:516-518` 无论哪条支路都写「delivered」；
  3. 消费即等于交付：`SessionMailbox.scala:355-366` 墓碑 + `MailboxBootSweep.scala:114-130` 据此永不补投；注入是一次性 user 消息，压缩摘要不保留终态事件。
- **影响面**：所有**靠终态通知决策**的派发会话，Nebula 根最重（它就是今天的苦主）。
- **修法方向**：给终态事件加「未确认」维度（父会话模型首个回合后 ack，未 ack 在下次 spawn/压缩时重述）；压缩提示词把「未处理终态事件」列为必保留段；`recoverOne` 用 `MailResult` 决定文案（Failed ⇒ 改「notification delivery failed」并落 ERROR）。

### D2【高】standby 闸把「等自家链节点」误判为「等作者」⇒ 应复活不复活，被判 failed，而其链节点照常复活 → 归属真空

- **现象**：`stand.log:9` 两条以 `standby at engine restart` 被跳过 → 09:09:29 判 failed；**同时**其作用域内节点 09:09:45 照常复活（`stand.log` 09:09:45 `CanvasStackView/node-c31 event=spawn`、`model-switch/node-c90`、`shared-card/node-3db`）；现盘面 `flow-maps/delegate-nebflow-bb1a8bbb/flow-map.json` = 16 节点 / 10 completed / **5 pending 无驱动** / 1 failed，`final-gates` 的 `notify=None`（本就是「完成后唤醒 owner」，而 owner 已判 failed）。
- **根因**：`DelegateRevival.scala:107-116` 只读 `rec.standbySince`，不区分待命**原因**（等作者 vs 等链节点）；`SubAgentStartupRecovery.scala:296` 把这类行直接推进收殓链，而 `ProjectCrashRecovery.recoverDelegateScopes:256-308` 与它零交互。
- **影响面**：所有 `mode=dispatcher` 的长链 owner（bb1a8bbb 就是）。
- **修法方向**：standby 分型（`standbyReason=chain-wait` ⇒ 走复活腿；`author-wait` ⇒ 保持现状）；或「owner 收殓时其作用域图未完成节点一律暂停 + 给父会话一张裁决卡」。

### D3【高】运行期 watchdog 只有收殓腿、没有复活腿，与 boot 腿语义不一致

- **现象**：restart barrier 卡死 ≥10 min 直接 failed + 通知，不做同 id 续跑；同一份现场在 boot 上会复活，在运行期不会。
- **根因**：`SubAgentStartupRecovery.scala:391` 直接 `recoverOne`，不经 `DelegateRevival.eligibility/revive`。
- **影响面**：网络级失败退避中的 delegate（#692 型僵尸）。
- **修法方向**：watchdog 收殓前先跑 eligibility，合格则 `DelegateRevival.revive`，不合格才收殓。

### D4【高】failed 行只有人工 Mail 能活（无自动腿）；且 Mail 腿不擦 `lastError/completedAt`，状态三元不一致

- **现象**：bb1a8bbb 任务行现状 `status=running` ∧ `lastError=…startup recovery V2` ∧ `completedAt=09:09 旧值`；`restartInterruptedAt` 已被摘（`SessionResumption.scala:333`）但 `lastError` 没人清。
- **根因**：① sweep 选集不含 failed（`SubAgentStartupRecovery.scala:313-316`）、watchdog 选集不含 failed（`SubAgentTaskStore.scala:311`）；② `resumeInPlace` 只 `updateStatus(…,"running")`（`SessionResumption.scala:234-240`），不写 lastError/completedAt，也不增 revivalCount（与 `DelegateRevival` 的 `markRevived` 不同构）。
- **影响面**：面板/晨报/对账读行面；`AgentControl` 会给作者「failed」与「running」两套矛盾读数。
- **修法方向**：resumeInPlace 复用 `markRevived` 同款「running + 清错 + 计数」；再给 failed 行一条**有闸的自动腿**（例如：failed ∧ restartInterrupted ∧ 父在 ∧ 有未完成链节点 ⇒ 自动原位续，与 D2 同一判据）。

### D5【中高】node 消息存在「只在内存」窗口，且事件日志无人回放

- **现象**：running 节点在 `tell` 与入队落盘之间崩溃 ⇒ 该条 node 消息除事件日志一行外无任何残留。
- **根因**：`NodeDelivery.scala:317` fire-and-forget；落盘在接收方（`AgentProcessing.scala:1646-1650`）；`AgentActor.scala:565` 空 sessionId 时 no-op；`FlowMapEventLog` 的 `injected -> …` 行（`NodeDelivery.scala:332-338`）无回放腿。
- **影响面**：重启/崩溃瞬间发给 running 节点的补充指令。
- **修法方向**：投递前先把正文写进 `node.task`（undelivered 形态）并在 ack 后剥离；或接收方回写 `host-delivered` 标记，boot 时扫「已记未投」重投。

### D6【中】恢复后会重复执行已完成的工作（幂等缺失，只有转录、没有断点台账）

- **现象**：delegate 复活把整段转录喂回 + nudge「继续」；node 续跑同样整段 `recoveredMessages`。没有任何「哪几步已完成、不要重做」的结构化提示。
- **根因**：`DelegateRevival.reviveImpl:202-241`、`SessionResumption.resumeInPlace:227-263` 只重放转录；节点侧 `NodeRecovery.scala:441-445` 的 `resumePrompt` 是唯一雏形，未推广到 delegate。
- **影响面**：长链 owner、含副作用步骤的批（合并/发版/提交）——重复提交、重复合并是现实风险。
- **修法方向**：复活时把「链节点 completed 集 + 分支/提交事实」作为结构化 resume hint 注入（`flow-map.json` 里现成可读）。

### D7【中】salvage 只搬最后一条 assistant 文本，结构化结果全丢

- **根因**：`SubAgentStartupRecovery.scala:433-436` + `extractLastAssistant:551-555`。
- **影响面**：父会话拿到碎文字，无法判断产物（分支/提交/文件/链状态）——今天的 partial result 恰好是一段「已申报待命」的口水话，信息量为零。
- **修法方向**：payload 附 `DelegateRegistry` 的 branch/worktree/最新提交 + 作用域图节点状态摘要。

### D8【中】「完成但无 reportStatus」形态可机械判定，但当前无人判定（cb580112）

- **现象**：cb580112 `workReport` 完整、分支已并入 main、`reportStatus=null`，11:27 日志明示「ended its turn WITHOUT a delegate_report … parent NOT notified」，最终靠 delegate-judge 回落才 completed。
- **根因**：终态判定腿只做「自报优先」，缺「工作已完成但无申报」的机械判据（`stand.log:148849`）。
- **影响面**：所有干完不报的 delegate（父会话静默不知道结果已到）。
- **修法方向**：`workReport` 非空 ∧ 分支已并入基分支 ∧ 无 reportStatus ⇒ 自动补一条 `completed` 终态 + 父通知（与 D1 的「未确认」机制合用）。

### 观测项（非缺陷，但会误导排障）

- 根会话 `sessions/<root>/` **没有 `injection-queues.json`**，而其它 1049 个会话都有 —— 因为 delegate 轨 boot 恒走邮箱（`SubAgentStartupRecovery.scala:487-500`）。按 F2 队列 grep 排障会得出「零命中 = 丢件」的错误结论（本次就是这个假设）。

---

## 7. 核验边界（诚实声明）

- 全部 `file:line` 均在本基线读出并逐段复核；实例证据（日志行、转录条目、registry/任务行 JSON、作用域图）均亲读。
- **未验证**：① 09:16:23 那次压缩的触发者（自动阈值 / 手动 / pingpong 换入）只确认了结果（347→13 条）；② 5 个 pending 节点的下游是否还有其它投递腿（我只确认了 `notify=root/None` 字段与 owner 已 failed）；③ `MailboxBootSweep` 对本例 `synthesized=0` 的判定细节（读的是墓碑命中，未逐分支复算）。
- **未改动任何产品代码、任何 `~/.stand` 数据**；本批禁止向生产数据根写入，故本审计未落 vault（`<dataRoot>/projects/stand/.stand/docs/`），仅落本证据目录。
