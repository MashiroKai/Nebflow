# 管家批 · D1 通知投递 + D3 合并等待无人管理 — REPORT

- 分支：`delegate/delegate-nebflow-34ef4fc6`（基于 main `aeae9d18a`）
- 日期：2026-10-23
- **作者补充裁定（2026-10-23 09:46，逐字）**：「目标会话死了就激活发送，不用发给root」
  ——本批已按裁定返工 D1 改道项（激活目标会话本身，废除投 root 路径），见 §1。
- 验证：`Test/compile` 通过；目标测试集 **84/84 全绿**（新增 5 个 spec + 8 个回归
  spec，见下；D1 spec 6 用例 = 裁定后验收路径）
- 格式：本批文件全部 scalafmt/scalafix-clean；门禁现状见「遗留与门禁现状」

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

## 1. D1 — 后台任务通知投递（活性判定 + 激活目标会话 + 持久补投）

**验收（作者裁定 09:46 后）**：①「目标会话已死 → **该会话被激活**且通知注入**该会
话**」②「重启窗口 → 复活后补投**到目标会话**」。**不再有「改道投 root」路径**
（首版 Reroot 腿已删除；D3-① 升级落点 `deliverToRootOrInbox` 不在裁定范围，原样
保留）。

### 1.1 核心件（`src/main/scala/nebflow/core/tools/BgNotifyDelivery.scala`）

- `BgNotifyDelivery.route`（:99）：路由裁决**纯函数**四态——`Direct`（target 活）/
  `Revive`（target 死 ∧ 激活面装配 ⇒ 激活目标会话本身）/ `Persist`（target 死 ∧
  激活面未装配 = 重启窗口 ⇒ 向**目标会话**注入队落「待补投」标记）/
  `Undeliverable`（会话已删）。另有 `Delivered(sessionId)` 仅作 D3 升级面返回标签。
- `BgSessionActivator`（:60）：**激活面 M2 注册器**（作者裁定的机制落点）。
  `trait Face { def activate(sessionId, pendingEvent): IO[ActivationResult] }`；
  `Activated`（面已持久化标记 + 恢复 spawn 已发起，重放交付）/ `Deferred(reason)`
  （该会话族复活归监督者 respawn / boot sweep 腿所有，面不越权 ⇒ 调用方落标记等
  自然复活）/ `UnknownSession`（会话已删，不落盘不激活）。install/clear，未装配 =
  走 Persist 腿（重启窗口语义）。
- `deliverAgentEvent`（:145）：system 缺席（harness）= 恒 Direct 旧行为；**直投腿用
  registry 上的新鲜 ref**（捕获 ref 可能是同路径重生前代，`!` 投旧邮箱蒸发——封
  同类洞）；死 target ⇒ Revive/Persist；审计走 FQN
  `nebflow.core.project.ProjectRuntimeRegistry`/`FlowMapEventLog`（core→core 同层），
  kind = `bg-notify-activated` / `bg-notify-deferred` / `bg-notify-undelivered`
  （`bg-notify-rerouted` 随裁定废除）。
- `deliverToRootOrInbox`（:262）/`resolveRootSessionId`（:282）：D3-① 升级共用落点
  ——**逐字保留**（裁定只覆盖 D1 改道项）。
- 持久补投：CompactionQueueStore（`~/.nebflow/sessions/<sid>/injection-queues.json`）
  事件，correlationId = `bg-redelivery-<jobId>`（幂等键），metadata 标注
  `bgNotifyRedelivered`/`bgNotifyToSession`/`bgTaskId`。落盘对象 = **目标会话自己**
  的注入队——复活轮 `RecoverPersistedQueues`（AgentIdle:595；AgentActor:979 spawn
  即触发）重放，通知补投给它。
- `BgInboxPort`（:35）：持久注入队 append 面 M2 注册器（实现 = agent 层
  `BgInboxBackstop.face`：correlationId 去重 + 会话存在守卫，会话已删 = 不产生垃圾
  文件）。分层纪律：core.tools 经两个 M2 端口消费 agent/gateway 能力——层检查
  `core -> agent:4` 边数不变、`gateway -> core:30` 边数不变（零新反向边）。

### 1.2 激活面生产实现（`src/main/scala/nebflow/gateway/WebSocketRoutes.scala:292`）

- `activateSession(sessionId, pendingEvent)` + 构造行 `BgSessionActivator.install(...)`：
  - SessionStore 无行 ⇒ `UnknownSession`（无处激活、无处落盘）；
  - registry 行已活（判活→激活竞态合拢）⇒ 直接投该会话新鲜 ref；
  - Delegate/SubTask/Ephemeral/Plan ⇒ `Deferred`（复活归监督者 respawn / boot sweep
    既有恢复腿，标记等自然复活重放——同源持久化恢复）；
  - Root/Team/Flow ⇒ 先写目标注入队标记，再走 **`ensureRootAgent`/`doSpawnRootAgent`
    持久化恢复 spawn**——SessionStore 水合 transcript + spawn 即
    `RecoverPersistedQueues` 重放（WS connect 恢复同款）＝「与引擎重启后的会话恢复
    同源」的字面实现，通知落目标会话新回合。
- 单飞行防重：`rootSpawnInFlight` Deferred 单飞（既有）防同会话双 spawn。

### 1.3 编辑

- `src/main/scala/nebflow/core/tools/BashTool.scala:1137`：`notifyAgent` 从裸
  `ref ! event`（死 actor 消息静默蒸发 = D1 根因）改走
  `BgNotifyDelivery.deliverAgentEvent(targetRef=Some(ref), targetSessionId=ctx.sessionId,
  system=ctx.actorSystem, registry=..., ...)`。
- `src/main/scala/nebflow/agent/SharedResources.scala:493`：构造尾部
  `BgInboxPort.install(BgInboxBackstop.face(this))`。

### 1.4 D1 测试（`BgNotifyDeliverySpec`，6 用例 = 裁定后验收路径）

① route 四态纯函数（Revive 取代 Reroot）；② 验收①：死 target ⇒ 激活面被以**目标
会话 id** 调用 + 标记进目标注入队（correlationId 幂等）+ 通知落**该会话**（复活
模拟体收到）；② 活 target 直投零漂移 + 激活面零调用；③ 验收②：重启窗口（无激活
面）⇒ 标记落**目标会话**注入队（`bgNotifyRedelivered`/`bgNotifyToSession` 标注）+
幂等 + 已删会话跳过；③ Deferred 腿（面不越权、交付面落标记）+ UnknownSession 零
落盘；④ 无 system ⇒ 旧行为直投（语义零漂移）。

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
Passed: Total 84, Failed 0, Errors 0, Passed 84
```

- 新增：BgNotifyDeliverySpec(6，裁定后验收路径) + MergeQueueHeadSkipSpec(3)
  + MergeWindowCareSpec(3) + WaitingDockCareSpec(2) + MergeQueueRestartReminderSpec(3)
  = 17
- 回归：MergeQueueSpec、DelegateTerminalSpec、DelegateTerminalPrecheckSpec、
  DelegateAutoMergeSpec、MergeQueueBootSweepSpec、TaskStuckWatcherSpec、
  BgTaskRegistrySpec、DelegateBgWaitHoldSpec = 67（既有用例零改动通过）

## 4. 遗留与门禁现状（如实上报）

1. **进程内重试触发**：被拒/延迟条目的重投 = 仅 reattach（respawn 事件）/ boot sweep
   （重启事件）两条事件腿；无「自动重泵给死条目」的常驻机制（设计取舍：避免定时器
   复活红线，等下一个事件再续）。若需更强活性，后续可挂到 AgentIdle 节拍。
2. **合并轮 judge 换窗竞态**：ReadyToMerge 重入队时的旧窗残留由
   `releaseGhostWindowLocked` 尸窗自愈兜住（不保证零延迟，保证不楔死）。
3. **RemoteExecutor bg 通知**不在本批（任务只点名 BashTool 交付面）。
3b. **激活面家族覆盖**：Root/Team/Flow 会话 = 恢复 spawn 同源激活（裁定主路径）；
   Delegate/SubTask/Ephemeral/Plan = `Deferred`（复活归监督者 respawn / boot sweep
   既有恢复腿，标记等自然复活重放——不越权重挂，避免与引擎家族恢复机制双写同一
   会话）。若后续要求 delegate 族也即时激活，可在该分派点接
   BackoffSupervisor 的 revive 面（本批未做，理由：双重复活源竞态）。
4. **门禁现状（main 基线本红，非本批引入）**：
   - `scalafix --check`：main 现状 74 文件违规（OrganizeImports 噪音 +
     AtomicJson 一处 `DisableSyntax.return`）；本批全部文件零违规（返工后新增的
     BgNotifyDelivery/WebSocketRoutes 两文件已按 OrganizeImports 期望形修整）。
   - `scripts/check-scala-layers.mjs`：main 现状 9 条 gate failure（cli→gateway ×6、
     core→dropbox ×1、SCC ×2），worktree 与 main 的失败清单逐字一致；本批新增边仅
     向下（core→shared +2、core→actor +2），`core->agent:4` 与 `gateway->core:30`
     边数不变（M2 端口反转依赖生效）。
   - `scalafmtCheckAll`：main 基线有大量未格式化文件（返工后清单 115+121 个，
     与 main 同源）；本批全部文件已按 `scalafmtOnly` 格式化。
     （注：本批曾在 worktree 跑过一次 `scalafmtAll`，其把基线脏文件一并重排了 223
     个——已全部 `git checkout` 还原，最终 diff 只含本批文件。）
5. **AcceptanceError / MergeWindow 既有 #753 代码路径**：零改动。

## 5. 产出清单（含裁定返工）

新主文件 3：`BgNotifyDelivery.scala`（含 BgInboxPort + BgSessionActivator 两 M2 端口）、
`BgInboxBackstop.scala`、`MergeWindowCare.scala`
编辑主文件 8：`BashTool.scala`、`SharedResources.scala`、`WebSocketRoutes.scala`
（激活面生产实现 + 装配）、`MergeQueue.scala`、`DelegateTerminal.scala`、
`BackoffSupervisor.scala`、`TaskStuckWatcher.scala`、`MergeQueueBootSweep.scala`
测试适配 1：`DelegateAutoMergeSpec.scala`
新测试 5：`BgNotifyDeliverySpec.scala`（6 用例）、`MergeQueueHeadSkipSpec.scala`、
`MergeWindowCareSpec.scala`、`WaitingDockCareSpec.scala`、
`MergeQueueRestartReminderSpec.scala`
