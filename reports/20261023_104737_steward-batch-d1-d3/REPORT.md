# 管家批 · D1 通知投递 + D3 合并等待无人管理 — REPORT

- 分支：`delegate/delegate-nebflow-34ef4fc6`（基于 main `aeae9d18a`）
- 日期：2026-10-23
- 验证：`Test/compile` 通过；目标测试集 **82/82 全绿**（新增 5 个 spec + 8 个回归 spec，见下）
- 格式：我的 16 个文件全部 scalafmt-clean；门禁现状见「遗留与门禁现状」

## 0. 设计立场（红线遵循）

- **零超时**：全程不新增任何 wall-clock 计时器；正确性判据 = actor death watch /
  `ActorSystem.isAlive` 活性探针 / MergeQueue 持久账本行 / 失败事件计数。DelegateBudget
  计时器未复活（未触碰）。
- **零 sleep/轮询等待**：所有推进由事件驱动——`onTerminal` 终态回调、respawn
  reattach、boot sweep 重挂、TaskStuckWatcher 既有扫描节拍（其本身是事件驱动的
  看护环，本批只挂新看护腿）。
- **最小改动**：既有语义默认参数保持逐字不变（`keepForRetry=false`、
  `mergeRoundRefused=false`、无 system = 旧行为直投）。不重构无关代码。
- **D2（#753）未动**：零改动涉及 #753 相关文件路径。

## 1. D1 — 后台任务通知投递（BashTool 交付面三段式）

**验收**：①「目标会话已死 → 结果仍到达活会话」②「重启窗口 → 复活后补投」。

### 1.1 新文件

- `src/main/scala/nebflow/core/tools/BgNotifyDelivery.scala`（D1 核心，~230 行）
  - `BgInboxPort`（:33）：M2 注册器。`trait Face { def append(sessionId, event): IO[Unit] }`，
    `install`/`clear`；未注册 = 响亮 WARN + no-op（分层纪律：core 不得引用
    nebflow.agent，持久注入队腿经此端口反转依赖；scripts/check-scala-layers.mjs 验证
    `core -> agent:4` 边数不变）。
  - `BgNotifyDelivery.route`（:84）：路由裁决**纯函数**四态——
    `Direct`（target 活）/ `Reroot`（target 死 ∧ root 活）/ `Persist`（target 死 ∧
    root 死，或 target==root 死）/ `Undeliverable`（无 root 线索）。
  - `deliverAgentEvent`（:119）：system 缺席（harness 形态）= 恒 Direct 旧行为；
    Reroot 投递失败回退 Persist；审计走 FQN
    `nebflow.core.project.ProjectRuntimeRegistry`/`FlowMapEventLog`（core→core 同层，
    BashTool auditBgSlow 先例），kind = `bg-notify-rerouted` / `bg-notify-deferred` /
    `bg-notify-undelivered`。
  - `deliverToRootOrInbox`（:183）：①升级共用落点——root 会话活 ⇒ 直投；死 ⇒ 写
    持久收件箱。
  - `resolveRootSessionId`（:212）：registry 行 `rootSessionId` 优先，fallback parent。
  - 持久补投：CompactionQueueStore（`~/.nebflow/sessions/<sid>/injection-queues.json`）
    事件，correlationId = `bg-redelivery-<jobId>`（幂等键），metadata 标注
    `bgNotifyRerouted`/`bgNotifyDeferred`/`bgTaskId`/`bgNotifyFromSession`。该文件正是
    复活轮 `RecoverPersistedQueues`（AgentIdle:595，spawn 时触发）重放的唯一入口——
    **写入即补投契约达成**。
- `src/main/scala/nebflow/agent/BgInboxBackstop.scala`：`face(resources)`——
  CompactionQueueStore load → correlationId 去重 → sessionStore.listSessions 会话存在
  守卫 → save；fail-soft WARN（收件箱文件损坏不阻断会话启动）。

### 1.2 编辑

- `src/main/scala/nebflow/core/tools/BashTool.scala:1137`：`notifyAgent` 从裸
  `ref ! event`（死 actor 消息静默蒸发 = D1 根因，`!` 是 queue.offer 永不失败）改走
  `BgNotifyDelivery.deliverAgentEvent(targetRef=Some(ref), targetSessionId=ctx.sessionId,
  rootSessionId=ctx.rootSessionId.orElse(ctx.sessionId), system=ctx.actorSystem, ...)`。
- `src/main/scala/nebflow/agent/SharedResources.scala:493`：构造尾部
  `BgInboxPort.install(BgInboxBackstop.face(this))`。

### 1.3 D1 测试（`BgNotifyDeliverySpec`，4 用例）

① route 四态纯函数；② 死 target + 活 root ⇒ 改道投 root（metadata 含
bgNotifyRerouted/bgTaskId）【验收①】；③ 重启窗口（root 不在注册表）⇒ 持久事件
correlationId=`bg-redelivery-job-3` 落文件 + 重复投递幂等 + 已删会话跳过【验收②】；
④ 无 system ⇒ 旧行为直投、无改道标注（语义零漂移）。

## 2. D3 — 合并等待无人管理（五层）

### 2.1 ② 提醒持久化（不绑活体）

- `MergeQueue.scala:365`（`fireViaActor`）：注入前 `childAlive` 活性探测（:151
  `installLivenessProbe` + :154 fail-open 恒活默认；SharedResources.scala:498 安装
  `actorSystem.isAlive(ref.path)`）。死 ⇒ `deferUndeliverable`（:384）：条目
  `pendingRetry=true` **留队**（不删不投）、单飞行窗释放 + pump 下一条、账本行
  `reminder-deferred`。重启形态：`reminder-deferred`/`refused-keep-queued` 账本末行
  成员被 boot sweep 按中断成员重挂（`MergeQueueBootSweep.scala:294`
  `InterruptedActions` 词表扩展），revive 通道重投合并提醒。
- `MergeQueue.scala:413`（`reattach`/`reattachIO`）：崩溃 respawn 换绑新子 actor +
  清 `pendingRetry` + 仅提醒未发时立即 pump 重投 + 账本行 `child-reattached`。调用点
  `BackoffSupervisor.scala:690`（respawn 中 source=delegate）。

### 2.2 ③ 队头被拒不堵塞

- `MergeQueue.scala:83`：`Entry.pendingRetry: Boolean = false`。
- `MergeQueue.scala:250`（`onTerminal(delegateId, keepForRetry=false)`）：
  `keepForRetry=true` ∧ 提醒已发 ⇒ 条目**不出队**、`pendingRetry=true` 留队 +
  窗立即释放 + pump（`pumpLocked` :338 `filterNot(_.pendingRetry).headOption`——待重试
  条目不参与授窗）+ 账本行 `refused-keep-queued`；pre-injection 出队腿加
  `releaseGhostWindowLocked`（:279 尸窗自愈：持窗者消亡的竞态形态不留幽灵窗）。
- `DelegateTerminal.scala:61`：`Release(verdictSuffix, mergeRoundRefused=false)`；
  :297 CompletedNote 分支 `mergeRound && acceptance.exists(!_.ok)`；:596/:657
  `onTerminalCleanup` 透传；`BackoffSupervisor.scala:188/:767/:786` 解构透传。
- 测试适配：`DelegateAutoMergeSpec.scala:338` `Release(suffix, _)`。

### 2.3 ④ Waiting 停靠活性看护

- `TaskStuckWatcher.scala`：删掉整族 Waiting 豁免 filter（仅注释保留说明）；
  扫描链首环挂 `careWaitingDock`（:853）：registry 行 `Waiting` ∧ actor 死 ⇒
  `MergeQueue.onTerminal(keepForRetry=true)`（队列前进）+ subAgentTaskStore 翻
  failed（parentSessionId 非空时）+ WS `waiting-dock-dead` 广播 +
  `escalateDockDeath`（:889，correlationId=`waiting-dock-dead-<sid>`）。**零时间判据**：
  `lastActivityMs` 完全不参与判定。扫描尾部挂 `MergeWindowCare.care`。

### 2.4 ⑤ 合并窗台账事件化看护

- 新文件 `src/main/scala/nebflow/core/delegate/MergeWindowCare.scala`：`care`（:46）
  遍历 `entriesSnapshot`（MergeQueue:139）——external/recovered 通道腿跳过（看护只覆盖
  internal 腿）；driver（supervisorRef）死 ∧ 提醒已发 ∧ 未待重试 ⇒
  `onTerminal(keepForRetry=true)` + 升级投 root（ActionRoundDriverDead，D3-① 落点）；
  driver 死 ∧ 提醒未发 ∧ 注册表无行（孤儿）⇒ 出队收口（ActionOrphanDequeue）；
  活司机/已待重试 ⇒ 零动作（幂等）。

### 2.5 ① 升级投 root 落点

`MergeWindowCare.escalate` 与 `TaskStuckWatcher.escalateDockDeath` 均走
`BgNotifyDelivery.deliverToRootOrInbox`——root 活投 root，死写持久收件箱（复活补投）。

### 2.6 D3 测试

- `MergeQueueHeadSkipSpec`（3 用例）：③ 被拒队头不堵塞（b 即刻注入、a 留队
  pendingRetry、b 终态后 a 不重投）【验收「队头堵塞跳过」】；② 死目标 deferred +
  账本行 + 下一条轮转【验收「提醒持久化」】；② reattach 复活重投 / 已发提醒只换绑。
- `MergeWindowCareSpec`（3 用例）：⑤ 司机死（留队 + 窗释放 + 活 root 收升级 + 二拍
  幂等）；⑤ 孤儿出队；⑤ 活司机零动作。
- `WaitingDockCareSpec`（2 用例）：④ 死停靠被看护（留队 + 翻 failed + WS +
  root 收事件 + 后续条目即刻入）【验收「Waiting 被看护」】；② 活停靠零动作负控
  （lastActivityMs=1 也不判——时间非判据）。
- `MergeQueueRestartReminderSpec`（3 用例）：`refused-keep-queued` /
  `reminder-deferred` 末行 ⇒ 重启后 interruptedMembers 含它 + sweep 重挂 + revive 收
  合并 brief【验收「重启后提醒仍达」】；dequeued 末行 = 干净退出不重挂。

## 3. 测试结果

```
Passed: Total 82, Failed 0, Errors 0, Passed 82
```

- 新增：BgNotifyDeliverySpec(4) + MergeQueueHeadSkipSpec(3) + MergeWindowCareSpec(3)
  + WaitingDockCareSpec(2) + MergeQueueRestartReminderSpec(3) = 15
- 回归：MergeQueueSpec、DelegateTerminalSpec、DelegateTerminalPrecheckSpec、
  DelegateAutoMergeSpec、MergeQueueBootSweepSpec、TaskStuckWatcherSpec、
  BgTaskRegistrySpec、DelegateBgWaitHoldSpec = 67（含 7 处既有用例零改动通过）

## 4. 遗留与门禁现状（如实上报）

1. **进程内重试触发**：被拒/延迟条目的重投 = 仅 reattach（respawn 事件）/ boot sweep
   （重启事件）两条事件腿；无「自动重泵给死条目」的常驻机制（设计取舍：避免定时器
   复活红线，等下一个事件再续）。若需更强活性，后续可挂到 AgentIdle 节拍。
2. **合并轮 judge 换窗竞态**：ReadyToMerge 重入队时的旧窗残留由
   `releaseGhostWindowLocked` 尸窗自愈兜住（不保证零延迟，保证不楔死）。
3. **RemoteExecutor bg 通知**不在本批（任务只点名 BashTool 交付面）。
4. **门禁现状（main 基线本红，非本批引入）**：
   - `scalafix --check`：main 现状 74 文件违规（OrganizeImports 噪音 +
     AtomicJson 一处 `DisableSyntax.return`）；本批 16 文件零违规。
   - `scripts/check-scala-layers.mjs`：main 现状 9 条 gate failure（cli→gateway ×6、
     core→dropbox ×1、SCC ×2），worktree 与 main 的失败清单逐字一致；本批新增边仅
     向下（core→shared +2、core→actor +2），`core->agent:4` 边数不变（M2 端口反转
     依赖生效）。
   - `scalafmtCheckAll`：main 基线有大量未格式化文件；本批 16 文件已格式化。
     （注：本批曾在 worktree 跑过一次 `scalafmtAll`，其把基线脏文件一并重排了 223
     个——已全部 `git checkout` 还原，最终 diff 只含本批 16 文件。）
5. **AcceptanceError / MergeWindow 既有 #753 代码路径**：零改动。

## 5. 产出清单

新主文件 3：`BgNotifyDelivery.scala`、`BgInboxBackstop.scala`、`MergeWindowCare.scala`
编辑主文件 7：`BashTool.scala`、`SharedResources.scala`、`MergeQueue.scala`、
`DelegateTerminal.scala`、`BackoffSupervisor.scala`、`TaskStuckWatcher.scala`、
`MergeQueueBootSweep.scala`
测试适配 1：`DelegateAutoMergeSpec.scala`
新测试 5：`BgNotifyDeliverySpec.scala`、`MergeQueueHeadSkipSpec.scala`、
`MergeWindowCareSpec.scala`、`WaitingDockCareSpec.scala`、
`MergeQueueRestartReminderSpec.scala`
