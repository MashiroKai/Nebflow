# 结果投递链丢失五向量修复——实施收尾报告

- 日期：2026-09-03
- 任务：续作模式收尾（实现本体已由前会话提交，本会话补测试/验红/合并/清理/报告）
- 审计依据：`20260903_result-delivery-loss-audit.md`（唯一依据）
- 分支：`result-delivery-fix`（已合并删除）；合并 commit：**`2b064487`**（main，--no-ff，未 push）
- 实施环境：git worktree `.nebflow/worktrees/result-delivery-fix`（已清理）

---

## 1. 接手盘点结论（7418ce8f 逐向量复审）

接手时 HEAD=7418ce8f（前会话实现本体，12 文件 +585），**工作区不干净**：前会话死亡前已把 V1 级联逻辑抽取为 `SessionChildCascade.scala`、改接 WebSocketRoutes、并写好 5 个向量各自的 spec（未提交）。逐文件判定：全部为有效续作，保留并提交为 `7f633b37`（[续作补完]）。

7418ce8f 本体逐向量复审：

| 向量 | 完整性 | 复审结论 |
|---|---|---|
| V2 孤儿 delegate 启动收殓 | 完整 | SubAgentStartupRecovery（抢救+F2 队列通知+correlationId 幂等+终态化）+ GatewayMain 启动装配点接线 + AgentControl 孤儿行。与审计建议一致（审计建议「注入 ExternalEvent 或标 failed」——实现两者兼做：failed 事件 + 终态化） |
| V1 deleteSession 级联 | 本体完整但**有潜伏 bug** | 级联停路径 + cancelRunningForParent 终态化。取舍「停掉」而非「落盘回收」（理由见 §2.2）。**续作发现并修复嵌套 IO bug（见 §2.2 与 §3）**——bug 存在时级联静默无效，正是 R1 首轮红的根因 |
| V7 planWaiting 真缓冲 | 完整 | 三类消息真实入队（ExternalEvent/ImmediateInput F2 落盘，UserInput 内存队列）+ 四出口统一 F1 排空（exitPlanWindow）。与 F0-F3 关系：复用 drainQueuesAfterCompaction/persistQueues，不新造语义 |
| V8 out=Nebula 记账+重投 | 完整 | nebulaDeliveredAt 记账（与 in barrier 的 deliveredTo 完全分离）+ redeliverUnconsumedNebulaResults（挂载即扫 + TtlTick 30s 周期扫，活动区+归档区）+ 根 ref 缺失不丢弃（不记账滞留） |
| V13 mail-dedup 收窄 | 完整 | 方案取「重放窗口豁免」（非判据收窄）：dedup 咨询仅作用于收件会话激活后 60s 内；窗口外无条件投递且不记账 |

**偏差清单**（均为语义等价或更优路径，逐条说明）：
1. V1：审计建议「notifyParentAndStop 前查 isAlive + 结果写 F2 队列或 taskStore 回收」——实现取审计建议的另一分支「deleteSession 级联 cancel 子 delegate」。理由见 §2.2。
2. V13：审计建议两方案（时间片收窄 / 重放豁免标记）——实现取重放窗口豁免，理由见 §2.5。
3. V8：FeedbackRouter escalate 复用 deliverToNebula 的通道传 nodeId=None，保持 fire-and-forget——blocked 反馈本体已持久化在节点上，升级消息不进重投扫描（避免对已处置的 blocked 重复升级）。审计指出该通道「继承 V8 向量」——本实现有意豁免并留注释。
4. **续作补完清单**：a) 5 个 spec 落盘提交（前会话已写未提交）；b) V1 嵌套 IO bug 修复（fea04217）；c) V7/V1 spec 等待预算加固（c52da697/01010883）；d) 顺带修复 stopTeamSessionActors(#38) 同类嵌套 IO bug。

---

## 2. 逐向量：根因 + 实现摘要 + 改动清单

### 2.1 V2（高优）：孤儿 delegate 启动收殓

- **根因**（审计 §1.3 V2）：进程崩溃时在飞 delegate 结果永久蒸发。`findRunningTasks` 注释承诺 startup recovery 但全仓无启动调用；AgentControl list 按 registry 行 join，重启后 registry 清空，孤儿连显示通路都没有。
- **实现**：`SubAgentStartupRecovery.recoverOrphans`——扫 running 任务 → 从子会话磁盘转录抢救部分结果（extractLastAssistant）→ 向父会话 F2 队列文件追加 failed ExternalEvent（correlationId=taskId 幂等，已排队不重复追加）→ 任务终态化 running→failed（幂等：下次启动不再命中）。挂 GatewayMain 启动装配点（任何会话 actor spawn 之前，队列文件单写者；best-effort 不阻塞启动）。AgentControl list 补 registry-free 孤儿行（orphanInScope 按父会话锚定 scope）。
- **改动**：
  - `SubAgentStartupRecovery.scala`（新，141 行）：recoverOrphans / recoverOne / notifyOnce / extractLastAssistant
  - `GatewayMain.scala`：subagentCrashSweep 装配（hubSetup *> taskTtlSweep *> **subagentCrashSweep** *> startupMount *> projectTtlScanner）
  - `AgentControlTool.scala`：orphanInScope + orphanRows + orphanNote（list 渲染）
  - `SubAgentTaskStore.scala`：updateStatus 已有能力复用（V1 另加 cancelRunningForParent）

### 2.2 V1：deleteSession 级联停 Delegate/SubTask/Ephemeral 子代理

- **根因**（审计 §1.3 V1）：deleteSession 只停 team 成员（stopTeamSessionActors）+ root agent；Delegate/Ephemeral 子代理继续在飞，子完成后 `parentRef ! ExternalEvent` 进死 queue（actor 框架 offer 即忘）→ 结果静默蒸发。
- **取舍（协议要求写明）**：选「停掉」非「让其完成落盘回收」。父会话删除 = 用户显式放弃该子树全部产出；(a) 子跑完结果也无处投递（父队列文件随会话删除无人再读），白烧 LLM token；(b) 停止语义无竞态窗口，BackoffSupervisor 的 Cancelled 兜底统一结算（父 cancelled 通知→barrier 正确释放→taskStore 终态化→registry 移除→child Stop→自停），无 phantom slot。
- **实现**：`SessionChildCascade.stopChildDelegateActors`（由 WebSocketRoutes 私有方法抽取为独立对象，WS 两条删除路径与单测共用同一实现）——按 parentSessionId 从 registry 找 Delegate/SubTask/Ephemeral 子 actor：有 supervisorRef（Delegate/SubTask=BackoffSupervisor）→ `sup ! AgentEvent.Cancelled`（AgentControl doCancel 同款正路，绝不直接 Stop 子 actor 以免被当成 crash 触发重启）；无 supervisor（Ephemeral）→ 直接 Stop + registry 移除；最后 cancelRunningForParent 兜底终态化任务记录。
- **改动**：
  - `SessionChildCascade.scala`（新）
  - `WebSocketRoutes.scala`：deleteSession/batchDelete 两路径接 stopChildDelegateActors；stopChildDelegateActors 委托
  - `SubAgentTaskStore.scala`：cancelRunningForParent（只取消 running/restarting，保留已完成历史，他父不动）
  - **嵌套 IO bug 修复（fea04217）**：`IO(sup ! msg)` 产生 `IO[IO[Unit]]`——内层 offer 只被构造从不执行，supervisor 永收不到 Cancelled，级联静默无效。修复为直连 `(sup ! msg)`。同 bug 波及 stopTeamSessionActors(#38)（`IO(ref ! AgentCommand.Stop)`，#38 的「停成员」实际从未生效）一并修复。该 bug 由「前会话」进程（死会话残留但进程实际存活，见 §7 协作说明）定位并修复，本会话复审采纳、清除调试探针、补修第二处。

### 2.3 V7：planWaiting 真缓冲

- **根因**（审计 §1.3 V7）：plan 模式等待期 catch-all 吞 ExternalEvent/UserInput——注释写「Buffer user messages while planning」但代码消费即弃。StartPlan 期间 in-flight delegate 完成 → 结果蒸发。
- **实现**：planWaiting 显式缓冲：ExternalEvent → pendingEvents（persistQueues F2 落盘）、ImmediateInput → pendingImmediateInputs（F2 落盘）、UserInput → pendingUserInputs（与 processing 态同款内存队列）。计划窗口四个出口（approved/cancelled/failed/interrupt）统一经 `exitPlanWindow` → **复用 F1 共享排空 helper `drainQueuesAfterCompaction`**（barrier-held 子结果保持 hold、带 replyTo 的 UserInput 保持 deferred——与压缩窗口排空语义完全一致，不新造排空）。
- **与 #43 兼容**：UserInput 缓冲≠误消费——AskUser 的回答走 InteractionAnswered 即时通道（不经 planWaiting 的消息循环排队），#43 的 pending ask 刷新走 WS 重发 pending 状态（AskUserPendingInjectionSpec 验证），均不受 V7 缓冲影响；缓冲只在「计划等待」这个明确窗口生效，恢复后按到达序注入（pendingUserInputs 尾追加 + F1 排空按队序）。
- **与 F0-F3 关系**：同代码域（AgentActor 队列系统）复用既有机制：缓冲复用三队列字段，落盘复用 persistQueues/CompactionQueueStore，排空复用 drainQueuesAfterCompaction——零新语义。
- **改动**：`AgentActor.scala` planWaiting 三个 case 重写 + exitPlanWindow（新）+ PlanApproved/PlanCancelled/PlanFailed/Interrupt 四出口接排空。

### 2.4 V8：out=Nebula 投递记账 + 重启重投扫描

- **根因**（审计 §2.2 V8）：deliverOut 的 Nebula 分支不写 deliveredTo（无记账），deliverToNebula 是 `ref ! ImmediateInput` 即忘；根 ref 不存在仅 warn 丢弃；completeNode 落盘与 Nebula 消费之间崩溃 → 结果滞留 map 无人再投。
- **实现**：`NodeDef.nebulaDeliveredAt: Option[Long]`（旧 flow-map.json 无此键 → withDefaults 解码 None，零迁移）——与 in barrier 的 deliveredTo 判定**完全分离**，barrier 语义零改动。deliverToNebula 带 nodeId 的调用（deliverOut/deliverFailed/deliverOutTo/重投扫描）offer 成功后写记账（活动区优先、归档区兜底）；根 ref 缺失 → warn 且**不记账**（滞留待补投）。`redeliverUnconsumedNebulaResults`：扫活动区+归档区「终态 + out=Nebula + 有 result + 未记账」补投并记账；挂载即扫（ProjectRuntimeRegistry.mount）+ TtlTick 30s 周期扫（projectTtlScanner）；根未 spawn 静默跳过（下个 tick 再试）。NodeEdit 重激活清零 nebulaDeliveredAt（重跑轮重新投递+记账）；人工改接 deliverOutTo 已记账也再投（用户显式意图）+ 刷账本防周期扫描重复。at-least-once：offer 与记账之间崩溃 → 扫描再投一次，宁重复不丢失。
- **改动**：`ProjectTypes.scala`（NodeDef 字段）、`NodeEngine.scala`（deliverToNebula 带 nodeId + markNebulaDelivered + redeliverUnconsumedNebulaResults）、`ProjectActor.scala`（mount 即扫 + TtlTick 接扫描）、`NodeTools.scala`（重激活清账本）。

### 2.5 V13：mail-dedup 收窄至重放窗口

- **根因**（审计 §4.2 V13）：指纹 = SHA-256(sender|recipient|content)，30min 窗口内相同三元组第二次 queue 投递被消费但不注入——重启重放与合法同文重发（10min 轮询文本、重发指令）不可区分地一起吞。
- **方案取舍（协议要求写明）**：审计给两案——收窄判据（仅「已成功消费」参与去重）或重放豁免标记。实现取**重放窗口豁免**：dedup 咨询仅作用于收件会话激活（AgentRecord.startedAt）后 `MailDedupReplayWindowMs`（60s，新 Defaults 常量）内——重启恢复 re-fire（MailTool activateAgent 重发磁盘 head）是唯一已证实的真实重复源，只可能在激活后数秒内注入。窗口外投递**不咨询也不记账** → 合法同文重发无条件投递；窗口内照旧咨询+记账（重启重放抑制不回归）。理由：(a) 无需给投递加「已消费」生命周期状态（收窄方案需要在队列项上带消费状态，跨重启语义复杂）；(b) 误吞暴露面从 30min 收窄到 60s；(c) registry 缺记录/startedAt=0 保守回退 dedup 激活（pre-V13 行为），向后兼容。
- **改动**：`Defaults.scala`（MailDedupReplayWindowMs=60s）、`MailDeliveryDedup.scala`（tryDeliver 增 withinReplayWindow 参数：false 直通）、`AgentActor.scala`（mailDedupInReplayWindow + 两处 drain 接线点 scoped）。

---

## 3. 测试与双向断言（红绿证据）

5 个新独立 spec（每向量一个，19 用例），全部真实前台执行。验红方式：临时变异生产代码绕过该向量修复 → 跑 spec 取红证据 → `git checkout --` 还原 → 复跑取绿。

### V2 — OrphanDelegateRecoverySpec（5 用例）
- R1 收殓全链（终态化+父通知+部分结果抢救）/ R2 幂等（二扫不重复通知）/ R3 父已删不写队列文件 / R4 AgentControl 孤儿行（registry-free）/ R5 验红基线（内嵌：绕过收殓→任务滞留 running 无通知）
- **变异**：`recoverOrphans` 的 findRunningTasks 桩化为空（`IO.pure(Nil).flatMap`）。**红证据**：`R1 X ...OrphanDelegateRecoverySpec.scala:95`（recovered=Nil≠[a,b]）、`R2 X ...:125`、`R3 X ...:143`——Failed 3, Passed 2。**还原后绿**：Passed 5, Failed 0。

### V1 — DeleteSessionCascadeSpec（3 用例）
- R1 GREEN 全链（真 AgentActor Delegate 出真子代理挂死 LLM → 级联 → supervisor 收 Cancelled 结算：registry 清、任务 cancelled、父收到 cancelled 唤醒、无多余注入）/ R2 验红基线（内嵌：级联绕过+父先死+子延迟完成→结果进死 queue 永不到达父）/ R3 store 级（cancelRunningForParent 只取消在飞、保留完成、他父不动）
- **变异**：stopChildDelegateActors 桩化 `IO.pure(Nil)`。**红证据**：`R1 X ...ComparisonFailException DeleteSessionCascadeSpec.scala:201`（stopped=Nil）——Failed 1。**还原后绿**：Passed 3, Failed 0。
- **首轮真红（非变异）**：修复前 7418ce8f 本体因嵌套 IO bug 级联实际无效——R1 反复红（`waitUntil: condition not met within 10 seconds`，supervisor 收不到 Cancelled、registry 行 10s 不清）。定位过程与修复见 §2.2。

### V7 — PlanWaitingBufferSpec（1 用例）
- R1 GREEN：planWaiting 期间 delegate 结果 + 用户消息缓冲（不唤醒父），PlanCancelled 后 F1 排空注入同一续轮（LLM 第二轮请求同时含两 marker），planEnd 照发（既有语义不回归）。父轮次按内容计数（plan agent 与父共享 sessionId——僵尸会话修正，已复审）。
- **变异**：三处缓冲 append 移除（吞消息，pre-V7 形态）。**红证据**：`R1 X ...waitUntil: condition not met within 20 seconds`（缓冲被吞→取消后第二轮永不开启）。**还原后绿**：Passed 1, Failed 0。
- 负载抖动记录：还原后曾连红两次（并行节点全量测试抢 CPU，planReady/注入等待 20s 超时）；加固预算 20s→30s（c52da697）后静默期与负载期均稳定绿。

### V8 — NebulaDeliveryRedeliverySpec（5 用例）
- R1 崩溃窗口补投+记账 / R2 幂等（二扫 0） / R3 根缺失滞留不误标 / R4 failed 节点同补投 / R5 人工改接通道保持（已记账也投+刷账）
- **变异**：扫描守卫恒真（扫描永远返回 0）。**红证据**：`R1 X ...:145`、`R2 X ...:166`、`R4 X ...:198`——Failed 3, Passed 2。**还原后绿**：Passed 5, Failed 0。

### V13 — MailDedupReplayWindowSpec（5 用例）
- R1 窗口外同文重发放行（审计丢失形态场景）/ R1b 窗口外不记账（无 ledger 文件落盘）/ R2 窗口内重启重放照旧抑制（磁盘指纹跨 reset）/ R3 30min 指纹过期放行（既有窗口语义不回归）/ R4 运行时全链（真 AgentActor+MailTool：激活 20× 窗口后同文重发注入两封、队列清空）
- **变异**：mailDedupInReplayWindow 恒真（pre-V13 全窗吞）。**红证据**：`R4 X ...waitUntil: condition not met in time`（同文重发被吞、第二轮注入永不发生）。R1-R3 组件级直传参不受影响（变异点在 AgentActor 接线层），符合设计。**还原后绿**：Passed 5, Failed 0。

---

## 4. 兼容性

- **#43（AskUser 刷新）**：合并两轮（566b4309 / 84580044）后其 spec 子集（AskUserBuildJson/AskUserPendingInjection/AskUserQuestionTool/InteractionHub/InteractionHubReplay）+ wait-timeout 新 spec 复跑全绿（§5/§6 数字）。V7 缓冲不经 AskUser 通道（§2.3）。
- **F0-F3（压缩窗口）**：V7 复用其排空 helper 与落盘机制，不改其语义；AgentActorCompactionSpec 20 用例 + CompactionQueueStoreSpec 全绿。
- **deliveredTo 既有语义**：V8 记账用独立字段 nebulaDeliveredAt，in barrier 判定（in ⊆ deliveredTo）零改动；NodeBarrierDeliverySpec/NodeEdgeRepairSpec/NodeBlockedReentrySpec 全绿。
- **V1×V2 交互**：deleteSession 级联经 cancelRunningForParent 终态化 → V2 启动扫描不会对已删父会话误收殓（R3 用例锁定）。V1 停止走 supervisor Cancelled 正路 → barrier 正确释放（SubAgentBarrierSpec/StuckDelegateReleaseSpec/NestedDelegateNotifySpec 全绿）。
- **wait-timeout-fix（并行合并）**：其 AgentCore/AgentActor 改动与 V7 域文本无冲突自动融合，AgentActor 双方符号共存核对（exitPlanWindow/plan-wait-buffer-user 与 WaitingForUser/permissionWait），其 4 个新 spec 复跑绿。

## 5. 全量 sbt（worktree，合并前）

```
sbt test（前台一次跑完）
Passed: Total 2169, Failed 0, Errors 0, Ignored 7
Total time: 479 s (0:07:59.0)，21:16:25 完成
```

## 6. 合并后主仓子集复跑（不跑第二次全量）

21 类（五向量新 spec + #43 AskUser/InteractionHub + AgentActor/AgentControl/barrier/stuck/delegate + Mail dedup/cold-queue/idle-gate + Node edge + wait-timeout + TaskStuckWatcher）：

```
Passed: Total 149, Failed 0, Errors 0
Total time: 637 s (0:10:37.0)，21:30:17 完成
```

（worktree 内合并 wait-timeout 后另跑了关键子集 23+75=98 全绿，先于主仓合并验证融合。）

## 7. 特殊事件记录：并发「死会话」残留的协作

本会话期间，任务标注为「死亡」的前会话进程实际仍在同一 worktree 活跃施工（20:19 跑 `-z GREEN`、20:23-20:31 连续编辑 spec 与 BackoffSupervisor 加调试探针、修正 V7 计数方式）。处理方式：不推倒对方工作，逐改动复审——其嵌套 IO 根因定位与 SessionChildCascade 修复**采纳并提交**（fea04217），其调试探针全部清除，其 V7 计数修正随 7f633b37 入库。其最后一次编辑在 21:0x 后停止；每次 commit 前 git status 复核未纳入任何未复审改动。**风险提示**：并行节点状态图显示 dead 的会话仍可能写盘，flow 编排的死亡判定与进程存活不一致，建议平台侧排查。

## 8. 分支与 commit

| commit | 说明 |
|---|---|
| 7418ce8f | 实现本体（前会话）：五向量 src/main 12 文件 |
| 7f633b37 | [续作补完] 5 spec 落盘 + SessionChildCascade 抽取 + WebSocketRoutes 委托 |
| 566b4309 | Merge main（canvas-html/collapse/sendbtn/worktree-param 域，零冲突） |
| fea04217 | [续作补完] 嵌套 IO bug 修复（SessionChildCascade + stopTeamSessionActors#38） |
| c52da697 | [加固] V7 spec 等待预算 |
| 01010883 | [加固] V1 spec 等待预算 |
| 84580044 | Merge main（wait-timeout-fix 域，零冲突，融合核对通过） |
| **2b064487** | **main 合并 commit（--no-ff，未 push 远程）** |

## 9. 清理确认

- `git worktree remove .nebflow/worktrees/result-delivery-fix`：完成（git worktree list 已无此项）
- 软链 `.nebflow/result-delivery-fix`：已删（ls 确认不存在）
- `git branch -d result-delivery-fix`：已删（曾指向 84580044，历史经 --no-ff 保全于 main）

## 10. 生效说明

**运行时生效需重启宿主**——本次合并进 main 即代码交付，严禁重启宿主（任务纪律），修复随作者下一次重启包生效。在飞改动点：GatewayMain 启动装配（V2 收殓、V8 周期扫描挂载）、AgentActor 行为（V7/V13）、NodeEngine/ProjectActor（V8）、WebSocketRoutes 删除路径（V1）。

## 11. 遗留

1. **测试时序敏感**：V1/V7 spec 为真 actor 编排测试，对机器负载敏感（曾两次负载假红，已加预算 25-30s）。若未来 CI 机器极慢仍可能抖，可再加大预算或抽确定性单测。
2. **V13 窗口常量 60s**：MailDedupReplayWindowMs 为保守经验值；若未来激活→re-fire→drain 链路变慢（如超慢盘），需复核。
3. **V8 at-least-once**：offer 与记账之间崩溃会重投一次（设计取舍：宁重复不丢失）；根会话若对 node 投递做去重需求，需在消费侧加 correlationId。
4. **stopTeamSessionActors(#38) 修复**顺带落在本分支（同类 bug 同一修复面）；其行为变化（#38 的停成员从无效变有效）已在合并后子集验证无回归，但建议作者知悉。
5. **并发死会话写盘风险**（§7）：平台侧问题，本任务无法修复。
6. 前会话后台 sbt（如有）已不可依赖；本次全部验证自跑，无残留进程依赖。
