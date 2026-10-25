# 复活批 · #823/#824 后台任务族成对续做 REPORT

- 批次：revive-bgtask（stranded WIP 复活之二——依 `reports/20261025_180431_stranded-triage/REPORT.md` §3 ⑪⑫）
- 日期：2026-10-25
- 工作分支：`delegate/delegate-nebflow-b5646a58`（合并队列落地，不自行 merge main）
- 快照来源：#823 = `delegate/delegate-nebflow-f98fc1e2`（单 wip 提交 0f97b5fbb）；#824 = `delegate/delegate-nebflow-a8a4de17`（单 wip 提交 4bb1e8cc9）。两分支同基 3a1e2ddba（10-24 16:45），main 自此漂移 70 提交——wip 快照即各批最新进度（#823 含续作会话 96167011 修净 4 红的未提交收尾；#824 无续作，快照为唯一实现）。

## 1. 落了什么

### #823 后台任务完成通知详情（作者报障「通知很不详细甚至有截断」）
- **BgNotifyPayload.scala（新件，148 行）**：终态通知负载纯组装单点。完成 = `[Background task completed] "desc" (exit N · dur):\n<尾段>`（**退出码恒在**，exit 0=显式成功信号）；失败同构带错误尾段；正文截 `Defaults.BgNotifyTailLines` 行（prop `nebflow.bg.notifyTailLines`，默认 20）+ 截断标注（总行数+jobId 指回详情卡）；零输出 = `[no output]` 占位（悬空冒号消灭）。
- **BashTool.makeNotifyCallback**：负载走 BgNotifyPayload；`BgTaskRegistry.unregisterReturning`（新方法，注销与读数一拍原子）取 startedAtMs 算耗时；WS `backgroundTaskUpdate` 终态帧只增键 `exitCode`/`durationMs`/`finishedAt`。
- **RemoteExecutor.notifyRemoteBgResult**：远程腿同一负载单点 + 终态帧三键。退出码认 `RemoteBgOutputLog.parseExitMarker`（对端回执 `(exit N)` 标记）；耗时用 main 输出查看批传入的 `startedAtMs`（`unregisterReturning` 语义留本地腿）。
- **WsSystemHandlers 取消腿**：取消通知补原因段 `(Cancelled by user)`。
- **main.js**：面板行 exit chip（复用 `bg.detail.exit` 文案键）+ 终态耗时冻结（durationMs 权威，ticker 终态守卫）——旧行为终态后仍走表谎报「已运行 N 分钟」收口。帧缺键=缺读零回归。
- spec：`BgNotifyPayloadSpec`（14 断言，快照自带，零改动跑绿）。

### #824 后台任务状态对 agent 不可见 + 完成事件可丢失（idle 无 barrier 死等）
- **BgTaskLedger.scala（新件，344 行）**：完成事件持久账本（`<dataRoot>/sessions/<sid>/bg-task-ledger.json`，AtomicJson 原子写，损坏容忍）。`recordCompletion`（先落账再投递唯一写点）/ `markOrphaned`（bg-wait cap 强制终判孤儿登记，含输出尾段快照；completion 到达原位升级不降级）/ `pending`+`consume`（consume-on-injection 判重幂等）/ `armBarrier`（回合尾持久栅栏）/ `splitForRedelivery`+`turnEndWarning`（纯判定面）。
- **BashTool / RemoteExecutor 投递腿**：事件与输出尾段**先落账再投递**，投递面任何一环失联由补投腿兜底；fail-soft（账本故障绝不阻断投递）。
- **AgentActor.flushPendingLedgerCompletions**：会话唤醒三腿（UserInput/ExternalEvent/ImmediateInput）先补投未消费完成事件（payloadHead 已在转录/在途 ⇒ 只消费不重注，双路径判重幂等）。
- **AgentFinishTurn.bgTurnEndAccounting**：回合尾对照 `BgTaskRegistry.waitingFor` 自有在飞集——非空 ⇒ `armBarrier` 持久栅栏 + 新等待期首拍记账警告进 idle 底座消息（模型上下文可见「在飞 N 个（id+描述+起止）」）；空集撤栅栏；同任务集不重发。
- **BackoffSupervisor bg-wait cap 腿**：cap 强制终判时逐任务 `markOrphaned`（输出尾段快照随行）——「两种死法统一收口」，续会话拾取有据。
- **WebSocketRoutes.activeAgentEntryJson**：增 `bgWaitingCount` 显式字段（面板/取证直读数，与 bgWaiting 数组恒一致）。
- spec：`BgTaskLedgerSpec` + `BgLedgerRedeliverySpec`（R1–R5 补投/W1–W3 栅栏）+ `BgWaitCapLedgerSpec`（C1 孤儿登记+诚实注记）+ `BgWaitingCountSpec`（S1/S2 状态面读数）。

## 2. 冲突融合与续作收尾（对现 main 70 提交漂移）

| 冲突点 | 处置 |
|---|---|
| RemoteExecutor.notifyRemoteBgResult ×2（#823、#824 各一轮） | 手工三层融合：main 输出查看批 `fillRemote`+`parseExitMarker`+新签名（deviceName/outputPath/startedAtMs）全保留；#823 BgNotifyPayload 单点负载+终态帧三键；#824 recordCompletion 先落账再投递。远程耗时从 main 已传的 startedAtMs 取（`unregisterReturning` 留本地腿） |
| BashTool.makeNotifyCallback（#824） | #823 重排结构（unregisterReturning 前置+BgNotifyPayload）内嵌 #824 的 recordCompletion 块（gateLedger 之后、投递之前） |
| 测试面 API 漂移 ×3 | spec 对现 main 重钉：`BgLedgerRedeliverySpec` AgentState 改 object-apply 装配器缝（`AgentState(messages=…, sessionId=…)`——copy 只收 9 个 ctor 参）；`BgWaitingCountSpec` 补 `cats.effect.unsafe.implicits.global`；`BgWaitCapLedgerSpec` for-表达式赋值改 `val program = for…yield…; program.unsafeRunSync()`（裸 `end for` 后接 `.unsafeRunSync()` 非法语句） |

无覆盖：main 的 device 批 0e10fb49f 提交文明文把「②完成通知负载/③持久账本」让位给 #823/#824（本批即接该缝）；相邻批 01e8a5286 是 output-viewer 域非通知负载。`git cherry` 两分支全 `+`。

## 3. 验证（验收口径：spec 全绿 + 后台任务族回归）

| 面 | 结果 |
|---|---|
| 主仓编译 | **绿**（630 scala 源） |
| 测试编译 | **绿**（收尾重钉后） |
| 新 spec 面（6 套件：BgNotifyPayloadSpec/BgTaskLedgerSpec/BgTaskRegistrySpec/BgLedgerRedeliverySpec/BgWaitCapLedgerSpec/BgWaitingCountSpec） | **40/40 断言全绿** |
| 后台任务族回归（17 套件：BashToolSpec/BashBackgroundHardTimeoutSpec/BashPersistentBgTaskSpec/BashAutoBackgroundSpec/BashActivityBridgeSpec/BashForegroundBudgetSpec/BashToolForegroundSpec/BgTaskOutputDiskSpec/DeviceBgTaskSpec/RemoteBgOutputLogSpec/BgRateGateSpec/BgNotifyDeliverySpec/BackoffSupervisorCancelSpec/BackoffSupervisorEmptyBurnSpec/BackoffSupervisorRestartDelaySpec/DelegateBgWaitHoldSpec/BgRemoteOutputRoutesSpec） | **102/102 断言全绿**（3m55s） |
| 格式 | 本批 14 文件 scalafmt 过（见 §5 附注） |
| 前端 | main.js #823 hunks 落位核实（exit chip/冻结时长/终态守卫/终态帧缺读兼容）；零新 i18n（复用 bg.detail.exit） |

#823 快照自带的 e2e（`scripts/e2e-bgtask-notify-detail.cjs`，14 断言+面板截图）为快照时点已验证，本批未重跑（需活实例；不在验收口径内）。

## 4. 台账

- #823、#824 → `completed`（本批收口时同步 tasks-v2）。
- 复活批进度：stranded 12 条中已复活 **4/12**（#809/#810 P0 前批、#823/#824 本批）；余 8 条候后续派。

## 5. 附注与开放项

1. **全树 scalafmt sweep 不在本批**：scalafmtAll 显示现 main 有 335 文件格式漂移（7850+/4286−），属 #817 round-2「对现 main 重生成」域，本批不替它做——sweep 存 `stash@{0}`（消息注明可再生），本批只对自身 14 文件 scalafmtOnly。
2. **#824 与 #807（b73c3e73）改动面重叠**（AgentIdle/BackoffSupervisor）：triage 报告已提示三批落序需排；#807 续做时对本批的补投腿需 rebase 对齐（本批 AgentIdle 三唤醒点的消息组装顺序=held→ledger→input，#807 回执批接入时保持该序）。
3. 通知详情批的 `AgentCommand.BackgroundTaskNotification` 死命令面维持不改动（快照口径）。
4. BgWaitCapLedgerSpec 日志里的 `ClassCastException: Stop → Nothing$` 是 `Behaviors.stopped` 探针的既有噪音（测试 PASS，非本批引入）。
