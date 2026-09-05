# Nebflow 任务结果投递链丢失向量审计（只读）

- 日期：2026-09-03
- 审计对象：main @ `2d8dc9f1`（v1.4.1-beta.54 工作树，只读未改任何代码）
- 主仓：`/Users/dev/Claude code/Nebflow`
- 方法：全链路静态代码走读（文件:行号均实测定位）+ `git merge-base --is-ancestor` 合并验证 + 关键回归测试实测（57/57 通过，见 §6）
- 严重度：高 = 常规操作即可触发且无保障；中 = 触发窗口存在、有部分兜底；低 = 双故障/边缘路径/文档化取舍

---

## 0. 结论速览

**F0/F1/F2 合并状态：全部已合并当前 main（明确结论，非部分合并）**

| 修复 | 内容 | 提交 | 合并验证 | 代码在位 |
|---|---|---|---|---|
| F0 | thinking-only 压缩轮走失败通道（不再死循环 mail-reminder） | `db633a4d` 2026-08-30 11:53 | `git merge-base --is-ancestor db633a4d main` ✅ | AgentActor.scala:2653-2673 |
| F1 | 压缩完成两分支统一全量排空队列（G1 修复） | 同上 `db633a4d` | ✅ | AgentActor.scala:296-413（drainQueuesAfterCompaction）+ :2158/:2198 两分支调用 |
| F2 | 注入队列落盘 + spawn 重放（G2 修复，**覆盖 2/3 队列**） | `d1445914` 2026-08-30 12:14 | `git merge-base --is-ancestor d1445914 main` ✅ | AgentActor.scala:360-367/:1104-1122/:2330/:2440/:2161/:3025/:3093 + CompactionQueueStore.scala 全文件 |

G1（压缩完成不排空）已闭合：压缩窗口内到达的事件在 CompactionComplete 两分支（resume=true 续轮 / resume=false 回 idle）经 `drainQueuesAfterCompaction` 全量注入续轮（AgentActor.scala:2152-2212），唯一例外是 barrier-held 的 subagent 结果（:390-394，等待批次契约，非丢失）。

G2（三队列纯内存）**部分闭合**：`pendingImmediateInputs` + `pendingEvents` 已落盘重放；**`pendingUserInputs` 明确不在范围**（CompactionQueueStore.scala:28-30 注释自认，降级为「前端可见的既有状态」）。mail queue 内容本就在磁盘（MailQueueStore）。即：三队列中 2 个已持久化，1 个（排队用户消息）保持内存态——文档化取舍，非遗漏。

**Top 3 风险（各一行）**：
1. **V2（高）**：进程崩溃/重启后 in-flight delegate 结果永久蒸发——`SubAgentTaskStore.findRunningTasks` 注释承诺「startup recovery」但全仓无任何启动恢复调用（唯一调用点是 AgentControl list 展示，且重启后 registry 清空连孤儿行都不显示），父会话恢复后永不知情。
2. **V8（中）**：Flow Map 节点 `out=Nebula` 投递 fire-and-forget 无记账（不写 deliveredTo、无 dedup 标记、无重启重投扫描）——completeNode 落盘与根会话消费之间崩溃，结果滞留 map 无人再投。
3. **V13（中）**：mail-dedup 以「30min 内 (sender, recipient, content) 三元组相同」判重，把重启重放与合法同文重发不可区分地一起吞掉（消费不入注入），仅 WARN 留痕。

---

## 1. 环一：Delegate/SubTask → 父会话注入

### 1.1 链路（实测）

```
子 AgentActor 完成 turn (replyTo=adapter)
  → BackoffSupervisor / persistentAdapter 收 AgentEvent.Completed/Failed/Cancelled
  → parentRef ! AgentCommand.ExternalEvent(source="delegate"/"subtask"/address)
  → 父 AgentActor 分状态处理：
      idle       → #418 立即注入开新 turn（:934-961）         [不 hold]
      processing → 入队 pendingEvents + F2 落盘 + barrier 递减（:2294-2332）
      frozen     → 入队 pendingEvents（内存，不落盘）（:3857-3873）
  → turn 边界 drain：drainBarrier（ToolsComplete :1832-1837 / finishTurnCont :2983-2988）
      outstanding>0 → subagent 结果 hold 等批次；=0 → 批次一次注入
      compactionPending → 全 hold（Consumed-yet-discarded 防护）
  → CompactionComplete → F1 全量排空（:2158）
```

### 1.2 保障机制（现状，带证据）

| 机制 | 证据 |
|---|---|
| 崩溃自动重启（2 次/5min 退避），三终态必发 ExternalEvent 且 source 保持 delegate/subtask（barrier 精确递减），熔断后发 failed | BackoffSupervisor.scala:136-171（Failed/Cancelled）、:302-315（maxRestarts give-up）、:319-359（notifyParentAndStop） |
| #25 完成债（owedCompletion）：子代理在飞时父 turn 终态延迟签发，防嵌套 delegate 死信 | AgentActor.scala:3039-3042；NestedDelegateNotifySpec（#25 direction A） |
| #31 兜底：TaskStuckWatcher 三级升级 Stop → 硬取消在飞 LLM → supervisor Cancelled（归还 barrier + 注入 held + 清 registry） | TaskStuckWatcher.scala:256-291；StuckDelegateReleaseSpec（E2E） |
| #418：idle 父绝不 hold 子结果——首个到达结果立即唤醒父 | AgentActor.scala:934-961 |
| #31 Fix D barrier 快照：AgentControl list 显示 `outstanding/pending`，phantom 可见 | AgentControlTool.scala:430-436；AgentActor.scala:237-251（touchBarrierSnapshot） |
| 任务元数据持久化（spawn/终态/重启计数落盘 subagent-tasks/<parent>.json） | DelegateTool.scala:437-454、SubTaskTool.scala:300-317、BackoffSupervisor.scala:335-343 |
| F2 队列落盘 + spawn 重放（pendingEvents/pendingImmediateInputs） | AgentActor.scala:2330/:2440（enqueue 落盘）、:1104-1122（RecoverPersistedQueues）、:2161/:3025/:3093（drain 后同步） |
| phantom barrier 计数修正：只数成功 ephemeral Delegate + 全部 SubTask，persistent/失败不计数 | TurnBoundaryDrains.scala:66-72（countBarrierIncrements）+ AgentActor.scala:1894-1906 注释 |

### 1.3 丢失向量

| # | 向量 | 触发条件 | 严重度 | 证据 |
|---|---|---|---|---|
| V1 | **父 actor 已死时 `ref ! ExternalEvent` 静默蒸发**。actor 框架 `!` 是向内存 Queue offer，fire-and-forget；循环已退出的 actor 的 queue 无人消费——官方注释自认「messages sent to it vanish silently」。BackoffSupervisor 通知失败/父死均无重试，`handleErrorWith` 后自停 | 用户 deleteSession（stopTeamSessionActors 只停 team 成员，**不停 Delegate/SubTask 子代理**，WebSocketRoutes.scala:320-342 + :306-318）或父 Stop 后子代理才完成；子代理结果永远无人接收 | **中高** | ActorSystem.scala:24-27、LocalActorRef.scala:24（offer 即忘）、BackoffSupervisor.scala:345-359、DelegateTool.scala:560-571（persistentAdapter 同样无兜底） |
| V2 | **进程崩溃 → in-flight delegate 结果永久蒸发，且无启动恢复**。`findRunningTasks` 的 doc 写明「for startup recovery」，但全仓无启动期调用；唯一调用 AgentControlTool.scala:407 是把 task 表 join 到 **registry 行**上展示——重启后 registry 清空，孤儿任务**连 AgentControl list 都不出现**。父会话恢复后 LLM 上下文里「You will be notified when it completes」的承诺永不兑现 | 任意 SIGKILL/断电/崩溃发生在子代理运行中，或子代理终态事件已 offer 进父 mailbox 但未消费 | **高** | SubAgentTaskStore.scala:13-16（注释承诺）与 :134-149（实现）、AgentControlTool.scala:407-414（registry join 逻辑）、MailTool.scala:815-821（对照：mail queue 有恢复接线，subagent task 没有） |
| V3 | `pendingUserInputs` 不落盘（F2 明确排除）——处理中/压缩窗口排队的用户消息崩溃即丢 | 崩溃发生在消息入队后、turn 边界消费前 | 中（文档化取舍） | CompactionQueueStore.scala:28-30 |
| V4 | frozen 态排队事件不落盘（:3857-3873 入队无 persistQueues；冻结状态本身也是内存 behavior） | 冻结期间到达结果 + 进程重启（双故障） | 低 | AgentActor.scala:3857-3873 |
| V6 | ToolsComplete drain 后**不**调 persistQueues（对照 finishTurnCont :3025/:3093 有）——磁盘快照仍含已消费事件，崩溃重启重放 → **重复注入**（at-least-once 反向向量，非丢失） | drain 与下一同步点之间崩溃 | 低 | AgentActor.scala:1908-1926（更新后无落盘）vs :1993-2004 |
| V7 | **planWaiting catch-all 吞消息**：plan 模式等待期到达的 ExternalEvent/UserInput 被消费后直接丢弃——注释写「Buffer user messages while planning」但代码未 buffer | 主会话 StartPlan 期间有 in-flight delegate 完成 | 低中 | AgentActor.scala:1418-1423 |

**无丢失结论（带证据）**：
- 压缩窗口内到达的 subagent 结果：**不丢**——enqueue 即 F2 落盘（:2330），CompactionComplete 后 F1 全量排空注入续轮（:2158-2212），AgentActorCompactionSpec V2/V2b/V2-persist/V2-recover 实测通过。
- 批次（barrier）语义下先到的结果：**不丢**——hold 在 pendingEvents（同样落盘），批次汇聚一次注入（:962-984）或 drainBarrier outstanding=0 分支排空（TurnBoundaryDrains.scala:94-96）；卡死批次由 TaskStuckWatcher 三级兜底强制释放。
- idle 父收到结果：**不丢**——#418 立即注入（:934-961）。

---

## 2. 环二：Node 结果 → 持久 → 沿边投递

### 2.1 保障机制

| 机制 | 证据 |
|---|---|
| 完成即落盘：completeNode 单 mutate 写 result/status/completedAt/ttlExpireAt，FlowMapStore write-through 原子写 flow-map.json | NodeEngine.scala:342-359；FlowMapStore.scala:44-52、:109-110 |
| R2 竞态纪律：终态化一律事务内现读 fresh（防陈旧快照覆盖接线——n-95271231/n-ab4a884f 实证修复注释） | NodeEngine.scala:332-340（注释）、:350-358 |
| 沿边投递记账：target.deliveredTo += nodeId（dedup）→ barrier 归零（in ⊆ deliveredTo）→ startNode | NodeEngine.scala:475-500（deliverOut）、:114-133（deliverOutTo） |
| barrier 启动事务性复核（fix a）：startNode 快照检查 + spawnAndRun 翻转 mutate 内 fresh 复核，并发接线增长在翻转前拦下 | NodeEngine.scala:227-243、NodeTools.scala（setOut 归档感知 :93-97） |
| 悬空节点接通补投递（D1）：create/edit 时对「终态有 result 而 deliveredTo 未记」的**全部** in 上游补投（fix b 完整性：含边已存在但投递曾丢失的 n-219106db 形态），活动+归档 findNode 兜底 | NodeTools.scala:514-523（create）、:857-895（edit/fix b）、:845-856（已完成节点改接） |
| TTL 归档只归档**终态**节点、结果全文保留；归档上游仍可投递/触发（findNode 活动区优先、归档区兜底） | FlowMapStore.scala:37-42（findNode）、:90-105（sweepExpired 只移 Terminal）、NodeEngine.scala:138-140（depsSatisfied findNode 兜底） |
| blocked 上游反馈串不投递（R1）；失败节点 collect 占位结算 | NodeTools.scala:519/:831/:882、NodeEngine.scala:502-526 |

### 2.2 丢失向量

| # | 向量 | 触发条件 | 严重度 | 证据 |
|---|---|---|---|---|
| V8 | **out=Nebula 投递无记账 + fire-and-forget + 无重启重投**：deliverOut 的 Nebula 分支不写 deliveredTo（无 dedup/已投标记），deliverToNebula 是 `ref ! ImmediateInput` 即忘；根 ref 不存在仅 warn 丢弃；completeNode 落盘（结果已在 map）与 Nebula 消费之间崩溃 → 重启后**无任何自动重投/对账扫描**（已验证 project 包内无 reconcile/redeliver 启动路径），唯一恢复 = 人/分发器 NodeList 察觉后 NodeEdit 改接触发 deliverOutTo。反向：因无已投标记，人工多次改接会重复投 Nebula | 崩溃窗口；或挂载时 rootSessionId 会话尚未注册（registry.get(rootSessionId) 为 None） | **中** | NodeEngine.scala:478-479（Nebula 分支无 mutate）、:534-546（deliverToNebula + warn 丢弃）、全仓 grep 无启动重投 |
| V9 | 投递目标为**归档节点**时静默 no-op：deliverOut 的 mutate 只写活动区，归档 target 的 deliveredTo 不落、startNode 不触发、无 warn | out 指向已归档节点（防御性场景——TTL 只归档终态节点，向终态节点投递本无意义） | 低 | NodeEngine.scala:487-499（`s.nodes.get(targetId)` 活动区 miss → allArrived=false） |

**无丢失结论（带证据）**：
- 节点结果持久化：**崩溃安全**——完成即原子落盘，重启后 loadInitial 恢复（FlowMapStore.scala:123-132），节点保持 completed+result。
- 节点→节点沿边投递：**不丢**——deliveredTo 记账 + barrier 事务复核 + D1/fix b「终态有结果而 deliveredTo 未记即补投」覆盖投递曾丢失的历史损伤；NodeBarrierDeliverySpec（②③④⑤⑥ 8 用例）+ NodeEdgeRepairSpec（②-a/②-b/③/③-b/④/④-c 7 用例）全绿。
- TTL 归档节点被重新接通：**结果仍可投递**——findNode 归档兜底 + fix a setOut 归档感知（归档 from 不再崩溃）+ 归档节点仅限 out 改接的补投通道（NodeTools.scala:320-342）；NodeEdgeRepairSpec ②/③/④ 族用例即此场景。

---

## 3. 环三：blocked 重入协议

### 3.1 保障机制

| 机制 | 证据 |
|---|---|
| blocked 终态全量持久化：status=Blocked + result=渲染串 + **blockedFeedback 全文** + blockCount+1 + **ttlExpireAt=None（永不过期=待办语义）** | NodeEngine.scala:401-415 |
| 不结算下游（传播停止是特性）；下游 pending 对重入分发器 NodeList 可见 | NodeEngine.scala:397-399（注释）、:407-413 |
| 自动重入：FeedbackRouter 裁决 → ProjectActor.ReenterDispatcher → 活跃分发器注入 / spawn 新分发器；reentryPrompt 含节点名/id/轮次/反馈三字段/**Flow Map 快照** | FeedbackRouter.scala:87-111；ProjectActor.scala:200-201、:239-270、:346-382 |
| 升级：escalate-only 档 / blockCount>2 / 10min≥5 次触发 cooldown（30min）合并单条升级 Nebula | FeedbackRouter.scala:68-84、:113-142 |
| 人工重激活：NodeEdit 实际变更 → status 回 wiring/pending、**deliveredTo 清空、blockCount/blockedFeedback 保留** → D1 链重投全部已完成上游（deliverOutTo dedup 幂等）→ barrier 结算重跑 | NodeTools.scala:726-843（:808 注释「blockCount / blockedFeedback 保留」） |
| 重入上限与轮次历史：blockCount 不清零（轮次保留），升级消息附全轮次反馈 | NodeEngine.scala:565-567、FeedbackRouter.scala:63-65、:129-142 |
| 人工重派：TriggerDispatcher 同样注入活跃会话/带快照新会话，blocked 节点全字段在快照内 | ProjectActor.scala:198-199、:216-224 |

### 3.2 丢失向量

| # | 向量 | 触发条件 | 严重度 | 证据 |
|---|---|---|---|---|
| V10 | FeedbackRouter guard/history 纯内存（窗口计数、cooldown、轮次反馈历史）——重启全失。文档自认「重启丢窗口计数可接受——限流器是成本保护不是安全机制」 | 重启后 blocked 风暴重新计窗（可能多一轮无效重入） | 低（文档化接受） | FeedbackRouter.scala:36-39、:61-65 |
| V11 | blocked 分流把 result 字段**覆盖**为反馈渲染串——节点 agent 的原始最终输出不保留全文（fallback detail 截 500 字符）；部分成果若在工作区文件里则仍在，若只在最终消息里则只剩 detail 摘要 | 节点以 BLOCKED 开头宣告时其输出本身即报告，实际影响低 | 低 | NodeEngine.scala:407-413（result=render(feedback)）、BlockedReader.scala:626-629（DetailCap=500） |
| — | escalate 通道复用 deliverToNebula → 继承 V8 的 fire-and-forget 向量 | 同 V8 | 低中（blocked 节点永不过期持久化，可事后发现） | FeedbackRouter.scala:53-58 |

**无丢失结论（带证据）**：
- BLOCKED 报告本体：**不丢**——blockedFeedback/result/blockCount 持久化且永不过期，重启后 NodeList/重入 prompt 仍完整可见；NodeBlockedReentrySpec「blocked: node terminalized…」用例锁定。
- 自动重入与人工重派：**反馈不丢**——重入 prompt 携带三字段反馈 + Flow Map 快照；分发器单例化下注入活跃会话或 spawn 新会话，均落 UserInput（ProjectActor.scala:361-381）。
- 重激活重跑：**上游结果不丢**——deliveredTo 清空后 D1 链重投全部终态上游（含归档），NodeBlockedReentrySpec「reactivation: … upstream results re-delivered, blockCount preserved, reruns to completion」用例锁定。

---

## 4. 环四：Mail queue

### 4.1 保障机制

| 机制 | 证据 |
|---|---|
| 先落盘后触发：append（原子写 mail-queue.json）→ 激活 → MailQueued 命令；激活失败诚实报错（#22「不谎报」），item 仍在磁盘等下次激活 | MailTool.scala:331-382（:344 落盘、:371-382 诚实报错） |
| #22 liveness probe：stale dead ref → isAlive 检查 → 重激活；#407 回退统一 agentRegistry 找活 ref（防双活） | MailTool.scala:641-671 |
| 重启恢复：激活时 re-fire 磁盘 head 的 MailQueued | MailTool.scala:815-821 |
| 幂等 guard：只有 item.id == 磁盘 head 才消费（激活触发与投递触发的双触发去重） | AgentActor.scala:1133-1178 |
| #407 全空闲判据：目标自身（registry status）+ 子树（任务型子记录存在性）+ 在飞 flow（RunningFlow.sessionId.contains(sid)）+ agent 内部权威 barrier 全空闲才投递；turn-end drain 以 skipSelfStatus 复检 | AgentActor.scala:3265-3277（fullyIdle）、MailIdleGate.scala:28-59、:3131-3149（skipSelfStatus） |
| #10 楔死防护：pendingMailQueueCount 累加不钳位（:1167-1172）+ returnToIdle tail 携带计数（:2956-2964）+ count>0 磁盘空时复位（:3217-3224） | 同左 |
| dedup 双点接线：idle drain + turn-end drain 均经 tryDeliver；重复项消费不入注入并递归试下一条 | AgentActor.scala:1184-1213（site 1）、:3198-3216（site 2） |

### 4.2 丢失向量

| # | 向量 | 触发条件 | 严重度 | 证据 |
|---|---|---|---|---|
| V13 | **dedup 误吞合法同文重发**：指纹 = SHA-256(sender\|recipient\|content)，30min 窗口内相同三元组第二次投递被消费（从队列移除）但不注入，仅 WARN + suppressedTotal 计数。dedup 无「重启重放」标记，无法区分重启重放与真实重复——每 10min 发一次相同轮询文本、或 30min 内重发同一段指令均被吞 | queue 模式；30min 内同 sender 同 recipient 同 content 二次投递（immediate 模式不受影响） | **中** | MailDeliveryDedup.scala:37-41（指纹三元组）、:91-106（tryDeliver 窗口判定 + 消费语义）、AgentActor.scala:1187-1191（消费不入注入） |
| V12 | MailQueued trigger 丢失窗口：trigger 是内存消息，actor 忙时只计数；磁盘队列是事实源，任何 trigger 丢失由下次激活/重启恢复 re-fire 补偿——残余窗口仅「actor 永不再激活」（内容不丢，只是延迟） | 目标会话此后永不被激活/删除 | 低 | MailTool.scala:344-352（落盘在前）、:815-821（恢复 re-fire） |

**无丢失结论（带证据）**：
- queue 邮件跨重启：**不丢**——内容先落盘（MailQueueStore 原子写，corrupt 降级空表并 warn），激活即恢复投递；ColdQueueActivationSpec S1（冷激活即排空）/S2（死 ref 重激活）/S3（激活失败诚实报错）/S4（重复 MailQueued 不双注入）全绿。
- 子树忙碌时：**不丢只延迟**——gate 不消费队列（保留 head 与 count），子代理完成事件驱动的 turn 结束重检（AgentActor.scala:1148-1175、:3128-3149）。

---

## 5. 历史丢失事故回归状态

| 事故 | 修复 | 当前 main 状态 | 代码证据 | 测试证据 |
|---|---|---|---|---|
| **#31 phantom barrier**（barrier 持有非 subagent 事件不放行 / 卡死批次永 hold） | ① countBarrierIncrements 只数成功 ephemeral Delegate + 全部 SubTask（qa-backend 2026-08-19）；② TaskStuckWatcher 三级兜底（Stop→硬取消→supervisor Cancelled 释放 barrier）；③ Fix D barrier 快照可见性；④ #418 idle 直注 | **生效** ✅ | TurnBoundaryDrains.scala:66-72、TaskStuckWatcher.scala:256-291、AgentControlTool.scala:430-436、AgentActor.scala:934-961 | SubAgentBarrierSpec（countBarrierIncrements 6 组断言）+ StuckDelegateReleaseSpec（E2E：(a) held 结果注入 (b) barrier 归零 (c) no phantom）——本次实测通过 |
| **08-19 冷激活 queue 断裂**（死 ref 吞 MailQueued / 激活失败谎报 / 重复双注入，#22） | commit `04027017`（merge-base --is-ancestor 验证在 main）：liveness probe + 诚实报错 + head-check 幂等 | **生效** ✅ | MailTool.scala:641-671（probe）、:371-382（诚实报错）、AgentActor.scala:1133-1139（head-check） | ColdQueueActivationSpec S1-S4——本次实测通过 |
| **压缩期 compaction-abandoned 静默死**（thinking-only 压缩轮死循环 mail-reminder，~219K input×2 白烧后静默；zombie CompactionJob 错吞后续回复） | F0：Compact 相位空文本 → handleCompactFailure（熔断计数 + CompactFailed + deferred Left + 诚实终态）；zombie guard：finishTurn/fatal/Interrupt/Restart 四路清 pendingCompaction 并完成 deferred；fatal 路径补 registry 回写 Idle（F1 2026-08-29）；+ F1 全量排空 / F2 落盘（2026-08-30） | **生效** ✅ | AgentActor.scala:2653-2673（F0）、:2749-2757（finishTurn zombie guard）、:1751-1771（fatal 路径 deferred + registry）、:1021-1040（stale deferred 完成） | AgentActorCompactionSpec：「V5: thinking-only Compact response fails compaction — no mail-reminder dead loop」+「finishTurn zombie guard clears a surviving CompactionJob and lets events deliver」等 20 用例——本次实测通过 |

---

## 6. 测试实测记录（2026-09-03，main @ 2d8dc9f1）

```
sbt "testOnly nebflow.agent.SubAgentBarrierSpec nebflow.agent.CompactionQueueStoreSpec
          nebflow.agent.AgentActorCompactionSpec nebflow.core.tools.ColdQueueActivationSpec
          nebflow.core.flow.MailDeliveryDedupSpec nebflow.core.tools.MailDedupWiringSpec"
→ Passed: Total 57, Failed 0, Errors 0   (104s)
```

覆盖：barrier 计数 6 组、F2 store 6 用例、F0/F1/F2 行为 20 用例（含 V2-persist/V2-recover 落盘重放、V5 thinking-only 失败通道）、冷激活 4 用例、dedup 组件 5 + 接线 4 链（W1 窗口吞/W2 重启重放/W3 过期放行/W4 turn-end 吞）。

---

## 7. 丢失向量清单总表

| 环 | # | 向量 | 触发条件 | 严重度 | 现状保障 | 建议修复 |
|---|---|---|---|---|---|---|
| 1 | V2 | 进程崩溃后 in-flight delegate 结果永久蒸发，无启动恢复 | 崩溃/SIGKILL 时有运行中或刚完成的 delegate | **高** | 仅 subagent-tasks 落盘（但无人读回） | 启动期扫 findRunningTasks → 对 status=running 且 registry 无记录的任务，向 parentSessionId 注入「[task lost: crash] + 原 prompt」ExternalEvent 或标 failed；AgentControl list 补孤儿任务行 |
| 1 | V1 | 父 actor 已死时子结果静默蒸发 | deleteSession/Stop 父后子完成 | 中高 | 无（offer 即忘） | notifyParentAndStop 前查 isAlive(parentRef)；死 → 结果写入 parentSessionId 的 F2 队列文件或 taskStore 结果字段供恢复；deleteSession 级联 cancel 子 delegate |
| 2 | V8 | 节点 out=Nebula 投递无记账、崩溃即滞留、无自动重投 | completeNode 落盘后、Nebula 消费前崩溃；或根 ref 不存在 | 中 | 结果在 flow-map.json 持久（可人工发现） | Nebula 投递补 deliveredTo 记账（或 deliveredToNebula 标记）；挂载/启动期扫描「completed + out=Nebula + 未记账」补投 |
| 4 | V13 | dedup 30min 同文合法重发被吞 | 同 sender+recipient+content 30min 内二次 queue 投递 | 中 | WARN + 计数留痕 | 指纹加时间片（如 5min）或仅对「重启后 X 秒内的 head 重放」启用 dedup（重启标记区分重放与真实重发） |
| 1 | V3 | pendingUserInputs 崩溃丢失 | 崩溃于入队后消费前 | 中（取舍） | 文档化（CompactionQueueStore.scala:28-30） | 如需闭合：UserInput 序列化入 injection-queues.json（与 imms/events 同文件） |
| 1 | V7 | planWaiting 吞 ExternalEvent/UserInput | StartPlan 等待期 delegate 完成 | 低中 | 无 | planWaiting 接 pendingEvents/pendingUserInputs 缓冲（补真实 buffer，一行改动语义） |
| 1 | V6 | ToolsComplete drain 后快照不同步 → 重启重放重复注入 | drain 后崩溃 | 低（at-least-once） | F2 其余路径同步 | ToolsComplete 分支补一行 persistQueues（对齐 :3025/:3093） |
| 1 | V4 | frozen 态队列不落盘 | 冻结期 + 崩溃（双故障） | 低 | 冻结前状态已持久 | frozen 入队路径补 persistQueues |
| 3 | V10 | FeedbackRouter cooldown/窗口重启即失 | 重启后 blocked 风暴 | 低（取舍） | 文档化接受 | 可忽略；或 guard 状态写 project.json |
| 3 | V11 | blocked 覆盖 result，原始输出不留全文 | 节点 BLOCKED 宣告 | 低 | blockedFeedback + detail 保留 | 可忽略；如需保留：原始输出另存 audit 字段 |
| 2 | V9 | 投递目标为归档节点时静默 no-op | out 指向归档节点（防御性） | 低 | 终态节点无投递意义 | 归档 target 命中时补 warn 即可 |

---

## 8. 审计方法附注

- 合并验证命令：`git merge-base --is-ancestor <sha> main`（db633a4d / d1445914 / 04027017 / f30b870e / 45eb83d6 均通过）。
- 「无自动恢复」结论均经全仓 grep 复核：`findRunningTasks` 调用点 3 处（定义/测试/AgentControl 展示）；project 包无 redeliver/reconcile 启动路径；actor 框架无死信队列。
- 本文档为阶段文档（audit），完成即冻结。
