# 投递屏障扣留回执缺陷取证（延迟补投通知不完整）

状态: COMPLETED（诊断 + 直修 F-A~F-D + spec 68/68 全绿；2026-10-22 收口）

任务: 诊断投递屏障扣留回执缺陷（作者收到「延迟补投」通知）——
1. 复原完整链路：哪条回执 / 什么条件扣留 / 扣多久 / 为何补投
2. 定位通知不完整的缺陷点（phantom slot 判定？nudge 没接住？槽位归还漏 flush？）
3. 低风险直接修；核心调度改动写方案呈报

前次会话已取证但未落盘（全部丢失，需重做）的线索：
- nebflow.ws 日志
- DelegateTerminal completion uplink 机制
- 10:36 force-drain held=5/outstanding=0 对照

---

## 证据区（边取证边写）

### E1. 屏障机制本体（src/main/scala/nebflow/agent/）

- `AgentActor.scala:193-213` `touchBarrierSnapshot`：barrier 状态快照进 agentRegistry（outstanding/pending），诊断口径——idle 期 outstanding>0 且无在飞=phantom slot；outstanding>0 且 pending>0=有结果被 HOLD。
- `AgentIdle.scala:392-435` 子代理结果屏障主判据：`isSubagentResult` 到达 → `newOutstanding = max(0, outstanding-1)`；**newOutstanding>0 ⇒ 结果进 pendingEvents 扣留（HELD）**，等批完成一 turn 合批注入。
- `AgentIdle.scala:436-460` 批完成腿：`isSubagentResult && held.nonEmpty` ⇒ 注入全部 held（含本条），outstanding/pending 清零，台账对账 `syncHeldReceipts(Nil)`。
- `AgentIdle.scala:482-528` 非子代理事件腿（fix 1 第三腿）：`heldFlushPlan(state)` 非空 ⇒ 本 turn 先补注全部 held 再注入本事件；outstanding 归零（幻影槽位归还，issue #31）。
- `AgentActor.scala:349-372` `heldFlushPlan`：pendingEvents 非空 ⇒ HeldFlushPlan（messages+清空态+events）；空 ⇒ None（调用路径零影响）。
- `AgentIdle.scala:617-647` `AgentCommand.DrainHeldReceipts`（fix 2 动作面，watchdog 催注）：held 空 ⇒ 幂等 no-op+快照对齐；非空 ⇒ 全量补投（`held-receipts-force-drain` 日志行含 `held=N returned-outstanding=M`）+ `emitForceDrainVisibility`（补投注记气泡）+ 快照归零 + 台账清空 + 徽标清零。

### E2. 前次会话线索的对应点

- 「10:36 force-drain held=5/outstanding=0」= E1 的 `held-receipts-force-drain` 日志行：补投时 5 条被扣回执、outstanding 已为 0 —— 即**扣留发生时屏障计数与回执队列已脱钩**（held>0 但 outstanding=0 意味着批完成腿 436 本应在最后一条结果到达时直接触发全量注入，却没有触发——待查最后一条走了哪条腿）。

### E3. 实证日志（/Users/kaiyu/.stand/logs/stand.log，运行面已 rebrand 为 stand）

- `stand.log:13283` — `2026-10-22 10:36:54.810 INFO nebflow.agent.lifecycle - Nebula-d7c3cb54 Nebula/d7c3cb54 event=held-receipts-force-drain detail=held=5 returned-outstanding=0`（前次取证所指 10:36 行；会话 = Nebula/d7c3cb54）
- `stand.log:45187` — `2026-10-22 16:50:56.616 ... event=held-receipts-force-drain detail=held=1 returned-outstanding=1`（同会话下午又一例）
- `stand.log:46256` — 前次被杀会话的诊断调用实录：作者收到通知文本为「**[投递补注] 1 条回执此前被子代理批屏障扣留未注入（屏障槽…**」——「延迟补投通知」= `emitForceDrainVisibility` 的补投注记气泡。
- `stand.2026-10-21.0.log` 无 watchdog/force-drain 行 → 10-21 平台修复批当日本产生实际催注；两例均在 10-22。

## 链路复原（核心结论）

### 屏障机制（代码面）

- 扣留队列 `pendingEvents` + 计数器 `outstandingSubagentResults`（纯内存，`ExecutionContext`）。
- 入队面：processing 态收到**任何** ExternalEvent 一律入队（`AgentProcessing.scala:1209-1212`，事件本身只管子代理结果减计数）；enqueue 即 `persistQueues`（:1239 磁盘快照）+ `syncHeldReceipts`（:1243 台账落账 producedAt）。
- idle 态永不扣留（#418，`AgentIdle.scala:402-435` 结果直投成 turn；`:436-464` 批完成汇聚全量注入）。
- 释放面：① tools-complete 边界 `drainBarrier`（`TurnBoundaryDrains.scala:115-127`：outstanding>0 时只放行**非子代理**事件队首，子代理回执全扣）；② idle 汇聚腿；③ fix-1 先补注入腿（任何 idle 输入/事件到达时把 held 全量前置注入+归还幻影槽）；④ watchdog 催注 force-drain。
- 台账/徽标 `HeldReceiptStore`（`~/.stand/sessions/<sid>/held-receipts.json`）；磁盘队列 `CompactionQueueStore`（同目录 `injection-queues.json`，空则删文件）。

### 完整链路复原（2026-10-22，会话 Nebula/d7c3cb54，stand.log 实证）

**作者收到的通知 = 16:50:56 的 force-drain 补投气泡**：
1. 16:40:35.535 子代理完成回执到达（processing 态）→ 入 pendingEvents + 磁盘 + 台账，outstanding 2→1（批里另一名成员未完成）。
2. 本回合 tools-complete 边界 `drainBarrier`：outstanding=1>0 ⇒ 子代理回执继续扣留。
3. 会话回 idle，held=1 + outstanding=1（**幻影槽**：该成员此后未归——即 issue #31 类）。
4. 台账 producedAt=16:40:35，`HeldDeliveryFirstRetryMs=10min`（Defaults.scala:750）⇒ 16:50:54 到期。
5. 16:50:56.614 watchdog 催注 → `DrainHeldReceipts` → idle 腿 force-drain：`held=1 returned-outstanding=1`（held-receipts-force-drain，stand.log:45187）。
6. 回执经 `buildEventReminder` 包成 `<system-reminder>` 注入 LLM（**LLM 历史面**）；作者可见面 = `emitForceDrainVisibility` 一条气泡（AgentActor.scala:399-412）。

**同会话全天同类事件**（nudge 流水实证幻影槽反复出现）：
- 12:17:51 微信消息入队 → 12:18:03 边界放行（11 秒，正常）。
- 12:27→12:50 一组「1 held receipt」5 催注后 exhausted（head `<system-reminder>`）。
- 13:42:43 回执入队 → **13:42:44 边界已投递**，但台账未出账 ⇒ 13:52-14:14 对**已投递回执**假催注 5 次 → 14:15:55 **假穷尽**（head「博士开题报告 PPT 批」，正是 13:42 那条）。
- 15:15/15:25 继续催注 → 16:50:56 真正补投成功（作者通知）。
- 17:13:46 引擎重启（机器重启）→ spawn msgs=466 + **queues-recovered events=11**（=重启孤儿收割器写给该父会话的 11 条 `[task lost: crash]` 失败回执；5cc7590a 会话队列文件里现躺着同款 5 条）→ 17:17:05 微信消息触发 fix-1 腿 `held=11` 全量前置注入。
- 10:36:19 同型重启恢复（events=5）→ 35 秒后 watchdog nudge（台账只记 1 条）→ force-drain held=5（stand.log:13283）。
- 09:40:00 重启恢复 events=2 → 09:47:13 用户输入 fix-1 腿 flush held=2。

## 缺陷定位（按「通知不完整」与三个候选点）

### D1（作者主诉根因）：补投气泡零身份、悬空冒号
`AgentActor.scala:399-412` `emitForceDrainVisibility` 只发 **条数**，文案「[投递补注] N 条回执此前被子代理批屏障扣留未注入（屏障槽位已归还），现随本回合补投:**:**」——冒号后无任何内容；回执本体只进 `buildEventReminder` 的 LLM `<system-reminder>`（AgentActor.scala:245-267），force-drain 腿**没有逐条 receiveVisibility 气泡**（对比 idle 直投腿 AgentIdle.scala:368-391 有）。作者无法知道补投的是哪条、什么内容、等了多久。对照：假穷尽 WARN（HeldDeliveryWatchdog.scala:103-108）**带 Heads**。

### D2（假催注/假穷尽）：tools-complete 边界漏台账对账
台账 sync 点全清单：AgentProcessing:1243（入队）、AgentIdle:444（idle 汇聚清零）、AgentIdle:645（force-drain 清零）、AgentActor:393（fix-1 flush 清零）、AgentFinishTurn:483（finishTurn drainBarrier 对账）。**AgentProcessing ToolsComplete 分支（:620-669 drain + :845 persistQueues）无 syncHeldReceipts** —— 平台修复批 fix 1 只接了 finishTurn 面、漏了 tools-complete 孪生面。后果：边界投递成功的回执永挂台账 → watchdog 对**已投递**回执催注 5 次 → 假穷尽 WARN + 假未读徽标（13:42→14:15 实证）。

### D3（watchdog 对恢复回执致盲）：RecoverPersistedQueues 不同步台账
`AgentIdle.scala:590-608` 恢复只合并进 pendingEvents，无 syncHeldReceipts / 徽标。后果：重启恢复的 5/11 条回执不进台账 → 无 producedAt → watchdog 永不催注（nudge 文本整天说「1 held receipt」而实际队列 5/11 条）；只能等下一次输入/事件触发 fix-1 腿。

### D4（磁盘队列陈旧重放）：idle 批完成汇聚腿漏 persistQueues
`AgentIdle.scala:436-464` 汇聚腿把 pendingEvents 置 Nil 后**不写磁盘**（入队时 :1239 写过全量快照）。后果：已消费回执留在 injection-queues.json → 下次 respawn 重放为幽灵回执（10:36 events=5、17:13 events=11 的构成里含陈旧快照条目与重启收割回执）。

### D5（调度面核心，只呈报不改）：幻影槽生命周期
`outstandingSubagentResults` 纯内存、无 wall-clock 超时（10-19 拍板）；成员 hang/被冻死永不归还槽位 ⇒ 批永不完成 ⇒ 边界永不放行 ⇒ 回执在 held 里泡到 watchdog 首催（≥10min）。force-drain 归还槽位（「屏障槽位已归还」文案）是**假设回执已全部产出**——对活批成员属过早归还。nudge 落 processing/frozen 态= no-op 丢催（设计靠 turn 边界兜，但幻影槽下边界不放行=兜不住）。
候选方案：① ephemeral delegate 槽位挂 actor 死亡回收（孤儿收割仅限重启；运行时 hang 无回收——接 TaskStuckWatcher 判定 stuck delegate 时向父发 failed 回执+还槽）；② respawn/回 idle 且 held.nonEmpty 时直接触发注入 turn 或短延时 DrainHeldReceipts（不等 10min 底线）；③ 台账 due 判定从 producedAt 改 lastAttemptAt 连续递增（现 backoff 10→20→30min 封顶，与实测 10,10,1,1,1 分钟间隔的 observed 形态需复核 spec 锚）。

## 修复 / 方案

低风险直修（本批直接改，不动调度语义）：
- F-A：D1 —— emitForceDrainVisibility 逐条带身份（source·eventType·payload 头 80 字），可选带台账 age（读 HeldReceiptStore）。
- F-B：D2 —— ToolsComplete 分支 drain 后补 syncHeldReceipts(remainingEvents)+徽标（镜像 AgentFinishTurn:483）。
- F-C：D3 —— RecoverPersistedQueues 合并后补 syncHeldReceipts(pendingEvents)+徽标。
- F-D：D4 —— idle 汇聚腿清空后补 persistQueues。

核心调度（只呈报）：D5 ①②③ + force-drain 过早还槽的批完整性语义评审。

### 已实施直修（2026-10-22 本批，全部簿记/通知面、不动调度语义）

| # | 缺陷 | 修复点 | 文件:行 |
|---|------|--------|---------|
| F-A | D1 气泡零身份 | emitForceDrainVisibility 逐条带 [source·eventType·已等待N分钟] + payload 头 80 字；台账读取失败降级为无时长版（簿记面不阻断主路径） | AgentActor.scala:399-448（oneLine helper :450-451） |
| F-B | D2 tools-complete 边界漏台账对账 | ToolsComplete 分支 touchBarrierSnapshot 后补 syncHeldReceipts(remainingEvents)+徽标（镜像 AgentFinishTurn:483）；**编辑时一度误删 immEventIO 行，已当轮回补** | AgentProcessing.scala:851-858 |
| F-C | D3 恢复面不同步台账 | RecoverPersistedQueues 合并后补 syncHeldReceipts(exec.pendingEvents)+徽标 | AgentIdle.scala:606-613 |
| F-D | D4 idle 汇聚腿漏 persistQueues | cleanedExec 单点构造，汇聚注入前 persistQueues（已消费回执落盘清除，防 respawn 重放） | AgentIdle.scala:437-446 |

未动：TurnBoundaryDrains.drainBarrier 判定本体（SubAgentBarrierSpec 锚定的纯函数）、watchdog 决策面 scanCore（HeldDeliveryWatchdogSpec 锚定）、HeldDeliveryPolicy 参数。

### 核心调度方案呈报（D5，待作者拍板）

1. **幻影槽运行时回收（治本）**：孤儿收割只覆盖引擎重启（boot 写 `[task lost: crash]`）；运行时 hang/冻死的 delegate 永不还槽。方案：BackoffSupervisor / DelegateTerminal 在 delegate actor 终态（正常完成已有回执；异常终态=停止失败/hang 判定）时向父会话补发 `failed` 回执 + 由既有入队面减槽（外部事件 decrement 语义已备）。接 TaskStuckWatcher 的 stuck 判定作为触发源之一。风险：改 actor 终态语义，需 spec 覆盖 BackoffSupervisor/DelegateTerminal。
2. **恢复/idle 带件直达**：RecoverPersistedQueues 或 finishTurn 回 idle 且 held.nonEmpty 时，不等 watchdog 10 分钟底线——直接触发汇聚注入 turn（语义同 batch-complete 腿）或短延时（如 30s）自投 DrainHeldReceipts。风险低（复用既有腿），但改变「idle 静默」行为面，需产品确认。
3. **催注时钟语义复核**：现 isDue = producedAt + delayMs(attempts+1)，delay 10→20→30min 封顶（HeldReceiptStore.scala:219-227）；实测 nudge 间隔呈 10,10,1,1,1 分钟形态（12:47→12:48→12:49），与政策函数不一致——疑似 attempts 记账在 actor no-op 腿未消耗、或 markAttempted 与 isDue 的读点错位；需 spec 锚定后再动。
4. **force-drain 还槽语义**：DrainHeldReceipts 把 outstanding 清零（「屏障槽位已归还」）基于「回执已全部产出」假设；活批成员后到时经 idle else 腿照常单件注入（无丢失，仅失合批）。若批完整性有消费方依赖（#25 嵌套债），需评审此假设。低风险观察项。
5. **重启收割风暴的通知面**：引擎重启对 N 个孤儿 delegate 写 N 条 failed 回执，父会话一次性合批 reminder + 单条计数气泡；F-A 已带逐条身份。可考虑首重启批的摘要气泡（「N 条因引擎重启产生的失败回执」）与普通补注区分——文案面，随 F-A 落地后再评。

## 未决项

- [x] sbt compile **PASS**（2026-10-22 18:10:30，586 sources；F-A 首版 `load` 签名误用 Option 已修——`HeldReceiptStore.load` 返回 `IO[List[HeldReceipt]]` 非 Option）。
- [x] 相关 spec：SubAgentBarrierSpec / HeldDeliveryWatchdogSpec / HeldReceiptStoreSpec / AgentActorCompactionSpec / AskUserPendingInjectionSpec / SelfSendSetupSpec（结果见下「spec 验证」节）。
- [x] 修 F-A 时遗留的双叠注释已清理（纯注释，不影响测试语义）。
- [ ] 12:50/14:15 两次穷尽条目的**逐条考古**未做（台账 reconcile 覆盖历史、旧条目 id 已不可考）；机制层证据（D2 假催注链）已足够，不阻塞结论。
- [ ] 5cc7590a 会话身份未确认（哪个父会话、其 5 条 `[task lost: crash]` 回执的后续投递路径未追）。
- [ ] 「已等待N分钟」在台账被 F-B/F-C 对账提前清出账时降级为空（可接受：时长信息属锦上添花）。
- [ ] 17:30:26 入队的微信消息截至 17:38 仍挂台账（attempts=0，10 分钟后才会首催）——运行面实况，非缺陷证据。

### 活体机制复现（诊断会话自身，2026-10-22 18:3x）

本诊断批的续作指令注入时，引擎同时注入了「你有一条被扣留的回执已耗尽重试预算，本消息注入即触发冲刷」——即 **fix-1 先补注入腿（exhausted 件随下一次输入冲刷）在设计内工作**的活体实证：本会话自己的回执也走了「扣留 → 催注 → 穷尽 → 随输入冲刷」全链。链路复原结论与代码面一致，无新增矛盾。

### spec 验证

- **结果：68/68 全绿（0 失败 0 错误）**，2026-10-22 20:44:58，sbt Total 111s（重跑自会话结束时丢失完成通知的 e94da1db 后台任务）。
- 命令：`sbt testOnly nebflow.agent.SubAgentBarrierSpec nebflow.core.processor.HeldDeliveryWatchdogSpec nebflow.core.processor.HeldReceiptStoreSpec nebflow.agent.AgentActorCompactionSpec nebflow.agent.AskUserPendingInjectionSpec nebflow.actor.SelfSendSetupSpec nebflow.agent.HeldDeliveryFlushSpec`
- 分面：
  - SubAgentBarrierSpec **12/12**（isSubagentResult 判据 / 批未齐全扣、批完成全量汇聚 / compaction guard / countBarrierIncrements 计数面——屏障本体未动，锚不破）。
  - HeldDeliveryWatchdogSpec **6/6**（W1 催注 / W2 零动作 / W3 穷尽+BadgeSync / W4 徽标最小化 / W5 退避到期 / W6 混合批——scanCore/policy 未动）。
  - HeldReceiptStoreSpec **10/10**（P1 receiptId 身份 / P2 退避+穷尽判定 / S1 syncFromEvents 对账 / S2 记账幂等 / S3 容错——F-B/F-C 调用的 sync 面语义锚）。
  - AgentActorCompactionSpec **27/27**（含「ToolsComplete drain mirrors the guard」= F-B 同分支面、「V2 barrier-held delegate results stay queued」= 屏障扣留、「V2-persist/V2-recover」= 磁盘快照面——F-B/F-D 的直接锚）。
  - AskUserPendingInjectionSpec **6/6**（含「delegate 结果属于批次屏障语义：outstanding>0 时持有、归零时整批注入」）。
  - SelfSendSetupSpec **2/2**。
  - HeldDeliveryFlushSpec **5/5**（heldFlushPlan F1/F2/F3 + MailIdleGate W1/W2——fix-1 先补注入腿的锚；原相关 spec 列表漏列，本批补入）。
- 结论：F-A~F-D 全部为簿记/通知面（气泡身份、台账对账、磁盘快照），未触碰上述 spec 锚定的纯函数判定（drainBarrier / watchdog scanCore / HeldDeliveryPolicy），全绿与预期一致，无回归。
