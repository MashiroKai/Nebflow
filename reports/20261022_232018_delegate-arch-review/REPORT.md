# delegate 架构全景审查报告（只读批）

- 批次：只读审查批（作者令 2026-10-22「delegate架构审查」）；**零代码改动、零构建跑动**，全部证据来自 Grep/Read 定点。
- 审查基线：worktree `delegate-nebflow-88a36678`（branch `delegate/delegate-nebflow-88a36678`，HEAD ≈ `edd7e3a57`）。路径均相对仓库根；`TSW` = `src/main/scala/nebflow/core/processor/TaskStuckWatcher.scala`，`BS` = `src/main/scala/nebflow/agent/BackoffSupervisor.scala`，其余按全路径。
- 标注纪律：每条论断带 `路径:行号`；无法从代码确证的现场观测（如 88 个 waiter 的确切来源、672 的确切构成）显式标注**假设**，并给出可在数据盘面验证的判据。

---

## 0. 结论（一段话）

delegate 生命周期主干（spawn→监督→终判→投递→清理）结构完整、清账纪律好（终态三清、幂等、诚实失败），但存在**四类系统性缺口**：①通知面单通道——后台任务完成通知只投向 child actor 引用，会话一死通知即丢（案例④根因）；②资源面零闸——后台任务注册与 delegate 扇出全无速率/并发上限（案例①根因）；③等待语义只有一半——合并停靠有 Waiting 状态+面板帧，bg-wait 停靠只有一行日志+一个前端没注册的 WS 帧（案例⑤根因）；④显示面对账是单调累积的——「重启打断」灰旗只落不清，注册表零删除（案例⑥根因）。在飞的平台修复批（HeldReceiptStore/HeldDeliveryWatchdog/DrainHeldReceipts/Waiting 停靠）已把案例③（barrier 幻影槽）的治本件落进本树，本报告与其衔接见 §5。**未发现「必死螺旋」**：会话内各熔断都有预算上限并落到诚实终态；唯一无界循环在**父会话层的 re-delegate 决策**（引擎侧零预算，见 D6）。

---

## 1. 现状架构图（delegate 生命周期全链，文字+锚点）

### 1.1 创建（spawn）

```
Nebula/根会话 LLM 调 Delegate(task, project?, executor?, lifecycle?)
  └─ DelegateTool.spawnWithPlugins
       ├─ 深度闸: ctx.depth >= MaxDepth(5) 拒绝        core/tools/DelegateTool.scala:44-45, 307-308
       ├─ 执行器解析（内置 nebflow / 外部 CLI）          DelegateTool.scala:309-312
       ├─ agentRegistry 登记（kind=Delegate, supervisorRef=adapter）
       │                                                DelegateTool.scala:765-775
       ├─ DelegateRegistry.put 持久地址簿（<dataRoot>/delegates/<id>.json）
       │                                                DelegateTool.scala:777-790; core/delegate/DelegateRegistry.scala:139-141
       ├─ subAgentTaskStore.recordTask(status="running") DelegateTool.scala:849-857
       ├─ TaskLedger note（taskId 在场时）               DelegateTool.scala:859-875
       └─ child ! UserInput(brief, replyTo=adapter)     DelegateTool.scala:877
并发上限：无（作者裁定 2026-09-26 退休注释在案）         DelegateTool.scala:31-33
```

- 屏障 +1：父会话 tools-complete 时对成功且非 persistent 的 Delegate/SubTask 调 `countBarrierIncrements`，`outstandingSubagentResults += n`（`src/main/scala/nebflow/agent/AgentProcessing.scala:746-747`；判据 `src/main/scala/nebflow/agent/TurnBoundaryDrains.scala:97-103`；快照写 `AgentProcessing.scala:850`）。

### 1.2 运行与监督（BackoffSupervisor）

```
child actor（完整 agent 会话） ──AgentEvent──▶ BackoffSupervisor（death-watch + 事件适配）
  ├─ 崩溃(Terminated): 预算内 → respawn（指数退避 5s→60s 封顶+抖动；断点续跑/重注原 prompt；
  │   registry 回写；cleanupPendingAsks）；预算尽 → failed("supervision-exhausted")
  │                                                BS:494-519, 564-712; 默认 maxRestarts=2/withinTimeRange=5min BS:68-71
  ├─ respawn 崩溃安全壳: respawn 链任何一步炸 → 诚实 failed("respawn-failed")，绝不留 "restarting" 僵尸行
  │                                                BS:521-554（#692 修批）
  ├─ 空烧(Completed+零工具+空文): 预算内 → respawn 续推(nudge)；delegate 轨预算尽 → 交确定性预检；
  │   subtask 轨 → failed("empty-burn")            BS:389-451; 判据 BS:881-895
  ├─ Failed: 不重启，直接 failed 终态               BS:453-470
  └─ Cancelled（AgentControl/父删/看门 giveUp）: 全套清理的 cancelled 终态   BS:472-490
```

- **时间预算已拆除**：3600s wall-clock 预算 2026-10-09 作者裁定拆除，`DelegateBudget.register/pause/resume/release` 全为 no-op 墓碑壳（`src/main/scala/nebflow/agent/DelegateBudget.scala:9-66`；拆除注释 `BS:83-91`）。合并窗「重挂预算」（`BS:199-204, 682-685`）因此也是 no-op——**合并窗停靠实际无任何引擎侧时限**（见 D3）。
- 治理哲学对齐 node：「无超时、只治真卡死」——TaskStuckWatcher 无进展判据 + SubAgentStartupRecovery 重启屏障看门（§1.5）。

### 1.3 终判与回执投递（核心链）

```
child turn 结束 → AgentEvent.Completed → supervisor
  ├─ ①bg-wait 等待屏障门（先于终判）                BS:274-377
  │    等待集 = BgTaskRegistry.waitingFor(subagentId)（自有 ∪ rootSessionId==自己，persistent 过滤）
  │                                                src/main/scala/nebflow/core/tools/BgTaskRegistry.scala:249-254
  │    非空 ⇒ HOLD（不终判；每等待期武装 bgWaitCapMs=BgGateWaitTimeoutMs 兜底计时器，默认 2h）
  │                                                BS:297-339; src/main/scala/nebflow/shared/Defaults.scala:381-382
  │    唤醒 = 后台任务完成回调 makeNotifyCallback → ExternalEvent(source="background-task") 注入仍活着的 child
  │                                                src/main/scala/nebflow/core/tools/BashTool.scala:1110-1115
  │    cap 到点 ⇒ capFired 闩 → 重发 Completed → 复检回合强制终判（带诚实注记：未结任务的通知将随会话丢失）
  │                                                BS:289-292, 322-339, 352-377
  ├─ ②终态判定机 DelegateTerminal.onCompleted（delegate 轨）  core/delegate/DelegateTerminal.scala:80-289
  │    判词序：漂移闸(drift) > 确定性预检(precheck) > LLM 判定机(judge) > 自申报回落 > 缺省 completed
  │                                                DelegateTerminal.scala:125-170
  │    ReadyToMerge ⇒ HoldForMerge：任务库置 ready-to-merge + MergeQueue 入队；不报父、不停 actor
  │                                                DelegateTerminal.scala:270-271, 676-752
  │    Completed/CompletedNote ⇒ Release（判词行随 payload）  DelegateTerminal.scala:272-286
  │    判定钩子自身失败 ⇒ fail-open 照常 completed         BS:263-268
  └─ ③notifyParentAndStop（全部终态汇合点）           BS:744-828
       DelegateTerminal.onTerminalCleanup（MergeQueue 出队 + failed/cancelled 补判词行 + host unmount
       + plugin MCP release + 自动归档 archivedAt）   DelegateTerminal.scala:575-635
       + taskStore 终态写 + parentRef ! ExternalEvent(source="delegate") + hub 槽清理
       + registry 移除 + child Stop + 自停             BS:755-826
       parentRef=None 兜底：响亮 WARN + delegateResultUndelivered 帧（结果无处投递时绝不静默）
                                                    BS:790-815
```

- 父会话侧接收（子代理批屏障）：`AgentIdle.ExternalEvent` 三分支——idle 首个结果**立即注入**（#418，idle 永不扣留）、批完成汇聚全量注入（outstanding→0, pendingEvents→Nil）、非子代理事件走 held-receipt 先补注入腿（`src/main/scala/nebflow/agent/AgentIdle.scala:398-531, 482-530`）。
- turn 内扣留：Processing 期到达的子代理结果 append 进 `pendingEvents`（`src/main/scala/nebflow/agent/AgentProcessing.scala:1210`，快照对账 `:1243-1245`）；turn 末 `drainBarrier`——outstanding>0 时子代理结果全 held、只放首个非子代理事件（`src/main/scala/nebflow/agent/AgentFinishTurn.scala:433-438`；`TurnBoundaryDrains.scala:115-127`）。completion debt（#25）：子代理在飞时 turn 级 Completed 不发、欠账 `owedCompletion` 跨界重放（`AgentFinishTurn.scala:498-502`）。

### 1.4 空闲与面板语义

- registry 状态值域：`Idle | Processing | WaitingForUser | Waiting | Frozen | Error(msg)`（`src/main/scala/nebflow/actor/AgentState.scala:229-249`）；wire 原样 `toString` 透出（`src/main/scala/nebflow/gateway/WebSocketRoutes.scala:2922-2955`）。
- `Waiting`（合并停靠）唯一写点 = `BS:216-222`（平台修复批 fix 4），随 `delegateWaiting` 帧下发，前端已接（`src/main/resources/web/js/main.js:2036-2039`）。
- **bg-wait 停靠无状态面**：只有日志 + `delegateBgWait` WS 帧（`BS:300-317`）；前端 **无任何 `delegateBgWait` 处理器**（web/js 全目录 grep 0 命中）——面板上该 delegate 停在「空闲」。
- 面板状态桶：`idle` 桶 = `Idle | WaitingForUser`（`src/main/resources/web/js/main/bgAgents.js:117-126`；标签 `web/js/locales/zh-CN.js:764`「空闲」）。barrier 列作者令隐藏（`bgAgents.js:70`）。

### 1.5 监督链现状（三看门 + 启动清扫）

| 组件 | 看什么 | 判据 | 动作 | 锚点 |
|---|---|---|---|---|
| TaskStuckWatcher | registry 中 **Processing** 的 Delegate/Ephemeral/Flow/SubTask/Root/Team | agent 侧 10min 零活动 ∪ 单工具 >10min（`Defaults.scala:730-731, 776-777`；每 30s 一拍） | 每轮 Stop→supervisor respawn 升级；Stop 2 次无效 → 在飞 LLM 硬取消 → 进程组收割；Stop+2 轮仍卡 → supervisor `Cancelled` 终态 | TSW:89, 721-742, 1291-1414, 1313-1368, 1381-1403 |
| SubAgentStartupRecovery | 任务行 running/restarting **且 registry 无活体行** ≥10min | `statusAt` 停滞 | 强制诚实 failed + F2 队列通知父（correlationId 幂等）；boot 清扫同链 + 重启打断标记腿 | src/main/scala/nebflow/agent/SubAgentStartupRecovery.scala:77-84, 93, 96-159, 165-195, 285-292 |
| HeldDeliveryWatchdog | registry 中 **Idle** 会话的 held 回执台账 | 首催 10min → ×2 封顶 30min，5 次催注（≈2h）穷尽 → exhausted 未读徽标 | `DrainHeldReceipts` 催注（绝不杀会话）；穷尽落旗 + `heldReceipts` 帧广播 | src/main/scala/nebflow/core/processor/HeldDeliveryWatchdog.scala:59-74, 90-98, 100-133; HeldDeliveryPolicy 同文件:210-245 |
| boot 孤儿清扫 | 全任务库 in-flight（running∪restarting） | 进程重启幸存 | 终态化 failed（`RestartLossFingerprint` 前缀）+ 部分结果打捞 + 父队列通知 + registry 补灰旗 | SubAgentStartupRecovery.scala:90-93, 96-159, 209-278 |

### 1.6 取消与级联

- AgentControl（Root/team-lead 专属工具，`src/main/scala/nebflow/agent/AgentSessionExecution.scala:2154-2159`）：cancel 有 supervisor ⇒ `sup ! Cancelled`（异步结算）；无 supervisor ⇒ 自组 cancelled 通知+终态化+Stop 降级（`src/main/scala/nebflow/core/tools/AgentControlTool.scala:862-948`）。被取消会话**自身**的后台任务/进程由 `killSessionShellProcesses → BgTaskRegistry.reclaimSession` 收殓（`src/main/scala/nebflow/agent/AgentActor.scala:1022-1031`；`BgTaskRegistry.scala:214-241`）。
- **子树不级联**：cancel 链与 supervisor 终态链都不触碰目标的子会话；唯一的子会话级联 `SessionChildCascade.stopChildDelegateActors` 只挂在 **deleteSession** WS 路径（`src/main/scala/nebflow/agent/SessionChildCascade.scala:43-70`；唯一生产调用点 `WebSocketRoutes.scala:495-496`）。flow 图侧的 NodeCanceller 级联闭环只走 NodeDef 引用，不覆盖 delegate 树（`src/main/scala/nebflow/core/project/NodeCanceller.scala:256-358, 438-447`）。

### 1.7 台账↔面板读面（三库 join）

- `/api/chains`、`/delegates` 以 `DelegateRegistry.list()` 全扫为行源（`src/main/scala/nebflow/core/project/ChainsReadFace.scala:68-83, 74`），逐行 join `taskStatuses.get(rec.id)`（键 = `Record.id == SubAgentTask.taskId`，`ChainsReadFace.scala:204`；状态来源 `src/main/scala/nebflow/gateway/PresenceRoutes.scala:460-479, 600-615`），`live` = registry 内存行存在与否的布尔提示（`ChainsReadFace.scala:190,195`）。
- 前端显示态优先级：taskStatus(直取) > reportStatus(finish/blocked→completed) > live > **restartInterruptedAt → 灰「interrupted」** > taskStatus(failed/cancelled…) > 兜底 running（`src/main/resources/web/js/delegateChainStub.js:199-248, 43-54`）。
- 两套词表并存：面板/liveness 用 AgentStatus 六值，任务行用 ROW_STATUS 八值，唯一桥 = `live` 布尔 + `taskStatus`（§3 D10）。

---

## 2. 六个活体案例逐一归因（根因链）

### ②① 案例一（①）：delegate-nebflow-59f171d8 八分钟自铸 88 个 Decision-point waiter 刷爆后台任务面板

**根因链**（每步有锚）：
1. 后台任务注册**零上限零速率闸**：`BgTaskRegistry.register` 是无条件 map 写入，无总 bound、无 per-session 计数、无频率检查（`BgTaskRegistry.scala:95-117`）。BashTool `run_in_background` 路径只有「终态销毁窗禁 spawn」一个守卫（`BgTaskRegistry.scala:136-197`；`src/main/scala/nebflow/core/tools/BashTool.scala:1188-1208`）。
2. delegate 扇出同样**无并发上限**（作者裁定 2026-09-26，`DelegateTool.scala:31-33`）；唯一闸是深度 5。
3. 任务描述是**自由文本**（`ActiveTask.description`，`BgTaskRegistry.scala:31-63`），面板按 description 渲染——「Decision-point waiter」不在仓内任何代码/文案中（全仓 grep 0 命中），**假设**：是 59f171d8 会话自己给 `run_in_background` 起的名字（等待某决策点/合并提醒的 sleep 型后台作业），8 分钟内连开 88 个。
4. 面板数据源 = `BgTaskRegistry` 活跃集（WS `backgroundTaskUpdate` 帧每任务一发，`BashTool.scala:1118-1132`；重连快照 `activeTasksJson`，`BgTaskRegistry.scala:293-316`）——88 个条目全部入册即全部可见，无聚合、无折叠。
5. **次生放大**：自有后台任务全部计入该 delegate 的 bg-wait 等待集（`BgTaskRegistry.scala:249-254`）——88 个 waiter 会把该 delegate 自己的完成判定无限期 hold（且每个 waiter 完成都唤醒一轮 LLM 复检，烧轮次）。

**闸位建议**：见 §3 D2。

### ② 案例二（②）：同一会话两次 loop-detected 熔断（同一 Bash 连续 10 次同参）

**机制阶梯**（LoopGuard，`src/main/scala/nebflow/core/processor/LoopGuard.scala`）：
- L0 警告：同参同败 ×3（`Config` `:54-62`：soft=3 / hard=8 / callHard=10 / textHard=10 / crossTurn=3）；
- L1 turn 终止：S1 同参同败 ×8，或 R-call 同参（成败皆计）连续 ×10，或 R-text 同文 ×10（裁决序 `:251-283`；连续性语义：任何不同调用/文本即刷新，`:194-236`）；
- L2 冻结：跨 turn ≥3 个 turn 同败无成功，或 L1 终止后同 fp 立即复发（`:238-262`）→ `enterFrozen(Loop)`，resumeAt=1 年（语义「永不自动续跑」，`src/main/scala/nebflow/agent/AgentProcessing.scala:775-836`），父收 `ExternalEvent("loop-detected", retryable=false)`（`AgentProcessing.scala:809-824`）。

**对 delegate 的实际后果**：L1 → `LlmFailed(LoopDetectedError)`（`src/main/scala/nebflow/agent/AgentSessionExecution.scala:1401-1406`）→ 不走错误冻结（`AgentProcessing.scala:373-375` 显式排除）→ fatal 链 → supervisor `Failed` → **terminal failed，一次即终**（`BS:453-470`；Failed 分支无重启腿）。L2 则冻结待人工（loop 冻结恢复=仅人工，`src/main/scala/nebflow/actor/AgentEvent.scala:131`；TSW 对 Frozen 双重豁免：扫描过滤 `TSW:724` + 恢复闸 `TSW:635-643`）。

**「同一会话两次」的代码侧解释**（按代码，单个 delegate 会话至多一次 L1——之后即 failed 终态。三次可能读法，均标注为**假设**，可依日志判别：grep `loop-detected`/`loop-freeze` 事件行）：
- (a) 第一次是 L0 警告（提醒注入/`loop-detected` 词汇出现）被观测为一次「熔断」，第二次是 L1 真终止；
- (b) 该会话是 root/团队面——root 的 L1 被抑制降级为 Warn（`AgentSessionExecution.scala:1377-1384`），root 可在同夜两次 L2 冻结（用户唤醒后复发，冻结唤醒面 `src/main/scala/nebflow/agent/AgentFrozen.scala:237-293`）；
- (c) 父会话收到 `retryable=true` 的 failed 提示后 re-delegate（见下），后继会话再熔断，面板行按 agent 名归并为「同一会话」。

**「必死螺旋」判定（范围 1 的正面回答）**：
- **会话内无螺旋**：崩溃重启预算 2 次/5min（`BS:68-71, 494-519`），耗尽 → `supervision-exhausted` 诚实 failed；空烧续推共用同一预算，delegate 轨耗尽 → 确定性预检接手（`BS:433-437`）；respawn 链中途炸 → `respawn-failed` 诚实终态（`BS:521-554`）；看门 Stop 升级链每 30s 一轮，Stop 2 次无效 → LLM 硬取消，再 2 轮 → supervisor Cancelled（`TSW:1313-1403`）——最坏 ~30 分钟内必达终态。
- **两机制的交互面**：loop-guard 不消耗重启预算（走 Failed 不走 Terminated）；看门 Stop-kill 消耗重启预算（走 Terminated）。二者对「真卡死会话」形成正交互补（loop 抓有活动循环、看门抓零活动挂起），无互相放大的回路。
- **唯一无界循环在父会话层**：L1/失败终态带 `retryable` 元数据（`AgentErrorType.LlmFailed → retryable=true`，`BS:456-459`），父会话 idle 注入「consider re-delegating」提醒（`src/main/scala/nebflow/agent/AgentIdle.scala:352-363`）——父 LLM 获得引擎侧背书的重派暗示，而**重派次数无任何引擎预算/去重**。同一必败任务（同一坏路径）可被无限次重派，每次都是全新 delegate 全新预算。见 D6。

### ③ 案例三（③）：三条会话「被扣留的子代理回执永不注入」幻影槽（barrier (+1)）

**根因链**（issue #31 类）：
1. 屏障计数 +1 在 spawn 成功时（`AgentProcessing.scala:746-747`），−1 在结果事件到达时（`AgentIdle.scala:399-400`）。**槽位归还完全依赖「每个成员必达一个终态事件」**。
2. 成员不达的形态：成员会话被看门/看门兜底漏掉地挂死、actor 死而无终态事件、（历史上）respawn 半途卡 `restarting`（#692 前形态）。任一形态 ⇒ outstanding 永不归零。
3. turn 末 `drainBarrier`：outstanding>0 ⇒ 子代理结果全部 held（`TurnBoundaryDrains.scala:121-124`；`AgentFinishTurn.scala:433-438`）——其余成员的已产出回执滞留 `pendingEvents`，父会话看似 Idle 完成，回执烂在内存队列。UI 面 = AgentControl 列 `barrier` 列 `outstanding/held`（`AgentControlTool.scala:546-550`；快照字段 `WebSocketRoutes.scala:2967-2972`）。
4. **治本件已在本树落位**（平台修复批 fix 1/2/3，2026-10-21）：
   - 台账：HeldReceiptStore 每次 pendingEvents 变动对账（hold/drain/flush 三接线点；`HeldReceiptStore.scala:145-172`；`AgentActor.scala:312-321`）；
   - 催注：HeldDeliveryWatchdog 对 **Idle** 会话到期未注入回执发 `DrainHeldReceipts`（10min 首催→×2 封顶 30min→5 次穷尽≈2h 落 exhausted 未读旗；`HeldDeliveryWatchdog.scala:59-74, 100-133`）；
   - 先补注入：父会话任何后续输入/事件先补注全部 held 回执并**归还幻影槽位**（outstanding 清零——回执全产出后剩余槽位只能是幻影；`AgentActor.scala:366-396`；`AgentIdle.scala:114-124, 483-489, 617-660`）；
   - 可见化：`heldReceipts` 徽标帧 + 快照 unread 计数（`AgentActor.scala:328-346`；`HeldDeliveryWatchdog.scala:119-132`）。
5. **残余缺口**：看门只扫 **Idle** 会话（`HeldDeliveryWatchdog.scala:92`）——永卡 Processing/Frozen/Waiting 的扣留面不催注（设计上留给 turn 边界/解冻腿，但那两腿依赖会话还有下一拍）；幻影槽位归还要么等下一事件、要么等 10min 首催，期间 barrier 快照维持 (+1) 观感。见 D8。

### ④ 案例四（④，重点）：三例 bg-wait 上限截断——后台任务完成通知随会话结束丢失

**完整根因链**（本次审查重点，逐环有锚）：
1. delegate 回合结束时名下有等待型后台任务 ⇒ **hold 不终判**（等待屏障，`BS:274-377`；启用开关 `Defaults.DelegateBgWaitHoldEnabled`，`Defaults.scala:127`）。这是作者令 2026-10-20 的 A 段修复（防「完成即终结、通知必丢」），方向正确。
2. 唤醒通道 = 完成回调 `makeNotifyCallback` → `ref ! ExternalEvent(source="background-task")` 注入**仍活着**的 child（`BashTool.scala:1110-1115`）→ 新回合 → Completed → 复检（`BS:352-377`）。
3. 兜底 = 每等待期武装 `bgWaitCapMs`（默认 **2h**，`BgGateWaitTimeoutMs`，`Defaults.scala:381-382`；`BS:74`）——到点仍非空 ⇒ capFired ⇒ 重发 Completed ⇒ 复检回合**强制终判**，payload 带诚实注记：「their completion notifications will be lost with this session (honest forced delivery; re-dispatch or re-attach if the work matters)」（`BS:289-292`，cap 触发 `BS:322-339`，Force 判定 `BS:355-362`）。
4. **丢失路径本体**：终判 → `notifyParentAndStop` → registry 移除 + child Stop（`BS:744-826`）→ 未结后台任务作为 OS 进程**继续在跑**（bg-wait hold/cap 都不杀任务；只有 cancel/Stop 路径经 `reclaimSession` 收殓，而 cap 终判走的是 completed 语义）→ 任务完成时回调向**已死**的 `ctx.agentActorRef` 投递 → actor 框架对死引用的 offer 失败 → 仅 `logger.warn`（`BashTool.scala:1115`）→ **通知蒸发**。输出文本留存 `BgTaskOutputStore`（`BashTool.scala:1140-1142`），但无任何机制把它再接回任何会话。
5. 佐证「需人工 Mail 续命」：注记明确让模型「re-dispatch or re-attach」，实际是作者手工 Mail 父会话续命——三例（投递屏障诊断批/生产迁移批/新设备批）同构。
6. **结构性判语**：bg-wait 屏障把「通知可达」建立在 child actor 存活上；而 registry（`BgTaskRegistry`）才是等待集权威——**权威在册、通道旁落**。丢失不是竞态是实现形态：通知面是单通道（活 child 引用），无死引用改道（re-home）、无父队列持久化兜底。
7. 相邻事实：`DelegateNotify.inject` 已经存在一个「对活会话 turn 边界注入 + 不活返回 false 交调用方兜底」的通道构件（`src/main/scala/nebflow/core/delegate/DelegateNotify.scala:38-60`），但 bg 完成回调没有接它。

**修法建议**（详见 §3 D1）：
- (a) **死引用改道（re-home）**：`makeNotifyCallback` 的 `notifyAgent` 失败分支（现仅 WARN）改为——查 `DelegateRegistry.find(sessionId).parentSessionId` / agentRegistry 父链 → `DelegateNotify.inject` 改道父/根；仍不活 → 按 `SubAgentStartupRecovery.notifyOnce` 同款把事件**持久化进父会话 F2 注入队列**（`SubAgentStartupRecovery.scala:285-292` 已有 correlationId 幂等先例）。S-M 工作量。
- (b) **cap 从「终判」降级为「告警+改道」**：cap 到点不强制 completed，改为一发诚实注记的中间事件（或直接 re-home 未结任务的「看护权」给父会话），delegate 保持 hold/或由作者显式取消——与「无超时」哲学一致（预算已拆，唯一还在的强时限就是它）。S 工作量（改 `BS:322-362` 的 Force 腿语义 + 开关）。
- (c) **终态清账补位**：cap 终判发生时，把未结任务清单写进任务行 lastError + 给父会话队列补一条「N 个后台任务改道至你」事件（现状只有 child payload 里的注记，父会话可见性弱）。S 工作量。

### ⑤ 案例五（⑤）：空闲 delegate 面板裸「空闲」无等待语义

**根因链**：
1. 等待语义目前只有两处落了状态面：合并停靠 `AgentStatus.Waiting`（`BS:216-222` fix 4）+ held 回执徽标（`AgentActor.scala:328-346`）。
2. **bg-wait 停靠（案例④的 hold 期）没有任何状态面**：child actor 在 turn 结束后回 Idle（`src/main/scala/nebflow/agent/AgentFinishTurn.scala:359` 等 touch Idle 点），supervisor 只是扣住 Completed 不转发；唯一信号 = `delegateBgWait` WS 帧（`BS:306-317`），而**前端未注册该帧处理器**（web/js 全目录 grep `delegateBgWait` 0 命中）。
3. 面板 idle 桶 = `Idle | WaitingForUser`（`bgAgents.js:117-126`）→ bg-wait hold 中的 delegate 显示裸「空闲」，作者「不理人、看不到进展」的观感即此。
4. 对照组合并停靠有全套（Waiting 状态 + `delegateWaiting` 帧已接 `main.js:2036-2039` + TaskStuckWatcher 豁免 `TSW:742` + MailIdleGate 视同可投递）——bg-wait 是同一作者令批里缺的那一半。

**修法**：见 D7（把 hold 期落 registry 快照/或前端补 `delegateBgWait` 处理器映射到 waiting 桶 + 剩余任务计数随帧下发——帧里已有 `waitingCount/waiting` 字段，`BS:310-315`，纯前端接线即可先止血）。

### ⑥ 案例六（④段尾）：灰色中断项 672 条的成因

**根因链**：
1. 「已中断」灰态的唯一数据源 = `DelegateRegistry.Record.restartInterruptedAt`（`DelegateRegistry.scala:120-128`；显示投影 `ChainsReadFace.scala:103` + `delegateChainStub.js:205-207, 213`；渲染 `web/js/delegatePanoramaView.js:112` + `locales/zh-CN.js:1487`「已中断」）。
2. 落旗点唯一：boot 对账腿 `markInterruptedRecords`（`SubAgentStartupRecovery.scala:125-159`）——任务记录 `lastError` 带重启指纹 `RestartLossFingerprint = "process restart during in-flight task"`（`:90`，写入点 `:266, 270`）且四守卫（未旗/未归档/未交付报告/指纹 join 命中）⇒ `markRestartInterrupted`（`DelegateRegistry.scala:281-295`）。
3. **单调累积的三个乘数**：
   - 旗**只落不清**：全仓无任何 unset 路径（grep 确认；`markRestartInterrupted` 幂等注释自认）；
   - 注册表**零删除**：归档也只是加旗（`DelegateRegistry.scala:24-26, 238-253`），在册窗的行只能靠人工 archive 或 delegate_report 才离开现役窗（`ChainsReadFace.scala:76`）；
   - 回溯腿**含历史 boot**（`:121`「含历史 boot 落下的」）：本批上线后第一次 boot 会把历史上所有带指纹的行一次性补旗——**672 的主体很可能是补旗腿一次性翻出的历史存量**，而非今夜新增。此后每次「带 K 个在飞 delegate 的进程死亡」再追加 K 条。
4. 看门强推腿（`lostToWatchdog`，`:264, 269`）的 lastError 前缀是 `restarting-barrier stall: …`，**不**匹配指纹——故看门收割的行显示 failed 而非灰中断（词表分叉，但语义尚可辨）。
5. **验证判据**（数据盘面，仓外）：`count(dataRoot/delegates/*.json 的 restartInterruptedAt 非空)` 与 `count(subagent-tasks/*.json lastError 前缀匹配指纹)` 对账；若两者接近 672 即证实累积说。
6. **假设**标注：672 的确切构成无法从仓内代码确证（在数据根），上链为机制级成因。

**修法**：见 D5（历史存量一次性迁入历史窗/或给灰旗行加自动归档龄期）。

---

## 3. 缺陷清单（按严重度排序；S<1 天 / M=1-3 天 / L>3 天，含回归面）

| # | 严重度 | 缺陷 | 根因锚点 | 修法建议 | 估 |
|---|---|---|---|---|---|
| D1 | **P0** | bg 任务完成通知单通道绑定 child actor 引用：会话终态（cap 终判/cancel/任何 failed）后未结任务的通知**必丢**，输出留存但无人接 | `BashTool.scala:1110-1115`（死引用仅 WARN）；`BS:322-339`（cap 强制终判不收殓任务）；权威集在 `BgTaskRegistry.scala:249-254` 却无改道 | 死引用 re-home：notifyAgent 失败分支 → DelegateNotify.inject 改道父/根 → 仍不活则持久化进父 F2 队列（notifyOnce 幂等先例 SubAgentStartupRecovery.scala:285-292）；cap 终判时同步给父补改道事件 + 任务行记账 | M |
| D2 | **P0** | 后台任务注册与 delegate 扇出零速率闸/零上限：面板刷爆（案例①）、等待集被自家 waiter 撑爆、LLM 轮次空烧 | `BgTaskRegistry.scala:95-117`（无条件 register）；`DelegateTool.scala:31-33`（无并发上限裁定） | 三层闸位：①per-session 等待型任务软上限（超限 register 返回可读错误让模型自纠，闸位放 BgTaskRegistry.register 入口）；②同参防重（同 sessionId+同 description+在册 ⇒ 拒，治「同一名字连开 N 个」）；③面板聚合行（同前缀 N 条折叠一行 + 展开帧），可选:会话级 bg-spawn 频率窗 | M |
| D3 | **P1** | 合并停靠（Waiting）无任何引擎侧时限与活性看护：merge-window 预算重挂已随 DelegateBudget 拆除成 no-op；TaskStuckWatcher 豁免 Waiting；重启看门只看 registry 无行的行——活着但没人发合并轮提醒的 delegate 永久停靠 | `DelegateBudget.scala:27-31`（no-op）；`BS:199-204`（重挂成空转）；`TSW:738-742`（豁免）；`SubAgentStartupRecovery.scala:175-177`（有活体行即不判） | 合并轮提醒阶梯（MergeQueue 已有 reminderSentAt/revive 面 `MergeQueue.scala:217-247, 286-363`，补「N 小时未提醒 → 升级通知父/根」）；或 Waiting 状态加 lastActivity 停滞判据走一次诚实 completed+注记 | M |
| D4 | **P1** | AgentControl cancel / 一切 delegate 终态**不级联子树**：被取消 delegate 的子孙会话继续在飞，完成后向已死父投递静默蒸发（SessionChildCascade 文档记载的同一丢失向量，上移一层）；进程与任务也无人收殓 | `AgentControlTool.scala:862-948`（cancel 不触子会话）；`BS:744-826`（终态清理只清自己）；`SessionChildCascade.scala:43-70`（唯一级联只挂 deleteSession） | 复用 SessionChildCascade 形态做 delegate 树级联：终态/cancel 时按 `parentSessionId == 自己` BFS（registry 行在册即可查，无需新索引），对每个子 delegate 走 `sup ! Cancelled` 正路；注意与 flow 图 NodeCanceller 的职责分层（只级联 AgentKind.Delegate/SubTask，不碰 node-*） | M |
| D5 | **P1** | 「重启打断」灰旗单调累积（672 条）：只落不清 × 注册表零删除 × 历史回溯补旗，现役窗被历史尸体占据 | `DelegateRegistry.scala:281-295`（无 unset）；`SubAgentStartupRecovery.scala:121-159`（历史回溯）；`ChainsReadFace.scala:76`（现役窗过滤只有 archivedAt） | 一次性迁移：现网存量灰旗行（或全 interruptedAt 早于本批上线时刻的行）批量 archive 进历史窗（`DelegateRegistry.archive` 幂等，脚本批处理）；此后新旗行保留现役窗 + 给灰态行加「N 天后自动进历史窗」龄期（或面板现役窗折叠计数） | S |
| D6 | **P2** | 失败终态 `retryable=true` + 父侧「consider re-delegating」提醒构成父层重派螺旋的燃料：重派次数无引擎预算，必败任务可被无限重派 | `BS:456-459`（LlmFailed→retryable=true）；`AgentIdle.scala:352-363`（re-delegate 提醒注入）；全仓无 re-delegate 计数器 | ①failureType=loop/empty-burn/supervision-exhausted 的 failed 置 retryable=false（loop 类不该被引擎背书重派）；②父会话侧按 (description 摘要, task) 记「同任务失败 N 次」计数，N≥2 时提醒文本从「consider re-delegating」改「stop re-dispatching the same failing task, change the brief」 | S |
| D7 | **P2** | bg-wait 停靠无面板语义（案例⑤）：hold 期裸「空闲」；`delegateBgWait` 帧前端未接 | `BS:300-317`（帧已发）；web/js 无处理器（grep 0）；`bgAgents.js:117-126`（idle 桶） | 前端先接线：onMessage('delegateBgWait') 映射 waiting 桶 + 帧内 waitingCount/waiting 明细渲染（数据已在帧里）；引擎侧可选：hold 期 registry 快照置显式状态（对齐 fix 4 的 Waiting 形态）供 AgentControl list/快照透出 | S |
| D8 | **P2** | HeldDeliveryWatchdog 只扫 Idle：Processing/Frozen/Waiting 期扣留的回执不催注，幻影槽归还依赖「会话还有下一拍」 | `HeldDeliveryWatchdog.scala:92`（Idle 过滤） | 扩候选 = Idle ∪ Frozen ∪ Waiting（Frozen/Waiting 的催注走「queued 不唤醒」语义需 agent 侧配套 no-op 分支，AgentFrozen 已有 queued 腿 `AgentFrozen.scala:553-578` 可挂）；或维持现状但在 AgentControl status 里把 held>0 的非 Idle 会话标 ⚠（零风险可见化） | S |
| D9 | **P3** | AgentControl 动作基本无审计：普通 cancel/restart/list 无事件行（唯一 AUDIT 是 Team 目标），事后归因只能翻散落 WARN | `AgentControlTool.scala:936, 1035`（仅两处失败 WARN）；`:444-460`（Team 专用 audit） | 对 cancel/restart 补 FlowMapEventLog.append（bg-slow 审计腿同款先例 `BashTool.scala:1164-1181`）或 nebflow.audit AUDIT 行复用 auditTeamAction 形态 | S |
| D10 | **P3** | 台账↔面板 join 不对称：registry 有行而任务记录缺失 ⇒ 永远兜底 running（前 #566 形态的残余）；AgentStatus 与 ROW_STATUS 两套词表仅靠 live 布尔桥接 | `ChainsReadFace.scala:44-49, 204`；`delegateChainStub.js:212-215` | 注册行缺任务记录且超龄 ⇒ 投影为 interrupted（或直接要求 spawn 时两库同写已成立——补一个 boot 对账把「无任务记录的活行」落一次 failed 对账） | S |

**工作量总览**：S×6、M×4；无 L。D1+D2 是本批最高收益对（直接消除案例①④的复发面）；D3/D4 填监督链最后两个无界角落；D5-D10 全是止血/可见化级。

---

## 4. 范围逐项作答（对应任务书 1-6）

1. **生命周期全链 + restart 预算×loop-guard 交互**：§1.1-1.3 + 案例②。结论：无会话内必死螺旋；无界循环只剩父层 re-delegate（D6）。restart 预算（2/5min）与 loop-guard 判据正交、预算互不串用（loop 走 Failed、看门 Stop 走 Terminated），最坏路径 ~30min 必达诚实终态（看门 Stop 升级链 + supervisor 预算耗尽 + respawn 安全壳三重兜底）。
2. **监督链现状与盲区**：§1.5。盲区四条：TaskStuckWatcher 只看 Processing（Idle/Frozen/Waiting/WaitingForUser/Error 全盲，`TSW:724`）；HeldDeliveryWatchdog 只看 Idle（`HeldDeliveryWatchdog.scala:92`）；重启看门只看 registry 无活体行的行（活着但停靠的行无人管，`SubAgentStartupRecovery.scala:175-177`）；合并窗时限已随预算拆除蒸发（`DelegateBudget.scala:27-31`）。另：giveUp 无 supervisorRef 时只循环 Stop 不终态（`TSW:1402`，Ephemeral/Flow 无桥形态）。
3. **回执投递与屏障**：§1.3 + 案例③④。held 机制链、幻影槽治本件（已在树）、bg-wait 丢失路径根因与修法（D1 重点）。
4. **台账↔面板**：§1.7 + 案例⑥。672 成因 = 灰旗单调累积 + 零删除注册表 + 历史回溯补旗（**假设**：主体为存量翻出，验证判据已给）。
5. **资源模型**：案例① + D2。后台任务与 delegate 双零闸是明确的作者裁定遗产（`DelegateTool.scala:31-33`），建议以「软上限+可读拒绝」形态落闸而非复活硬预算，与「无超时」哲学相容。
6. **AgentControl 权限/可观测面**：§1.6 + D9。权限面完备（RootExclusive 工具 + 桶匹配 + kind 矩阵 + Manager 子树链 maxDepth=8 + 自杀守卫 + external 根桶守卫，`AgentControlTool.scala:101-120, 151-192, 241-242, 355-377, 405-433`；面板路径守卫弱化已注释声明 `WsSessionChatHandlers.scala:535-536`）；观测面缺口 = 动作无审计行。

---

## 5. 与在飞监督链批的衔接建议

1. **已落在树、本报告直接认账的**：平台修复批 fix1/2/3（HeldReceiptStore 台账 + HeldDeliveryWatchdog 催注/穷尽/徽标 + 三处先补注入腿 + DrainHeldReceipts）、fix4（Waiting 停靠状态 + delegateWaiting 帧 + 看门豁免）、#692 两件（respawn 安全壳 + findRunningTasks 加宽 running∪restarting + 运行期重启看门）、任务列表行重启对账批（指纹 + markRestartInterrupted + interrupted 显示面）。本报告的 D8/D5 是它们的**补全**不是冲突：催注候选扩面、灰旗生命周期。
2. **建议监督链批接走的**：D1 的 re-home（通知改道与催注共享「父队列持久化」基建，`notifyOnce` 先例同源，放同一批改动面最小）；D7 的前端接线（`delegateBgWait` 帧已发，接帧是纯前端件，可与 fix4 的 delegateWaiting 处理器同文件落地）。
3. **建议分开批次、避免耦合的**：D2（资源闸）是行为新增，涉及作者裁定面（无并发上限），先出闸位设计（软上限值 + 拒绝文案 + 面板聚合）征裁再动；D4（树级联）触碰终态清理热路径（`notifyParentAndStop`），需要独立竞态评审（级联 cancel 与子孙自身终态的首个胜出语义，对齐 `BS:476-477` 的 mailbox 串行论证）。
4. **对「监督链批在飞做补全」的边界提醒**：本报告为现状图，案例③的治本件已按已落地形态记述；若在飞批正改 `HeldDeliveryWatchdog/AgentIdle` 同区，请以在飞批为基底套用 D8（候选扩面）与 D1（notifyAgent 失败分支），勿双写 `syncHeldReceipts` 接线点（现有四处：`AgentIdle.scala:444, 645`；`AgentFinishTurn.scala:483`；`AgentProcessing.scala:1243`）。

---

## 6. 审查方法与覆盖声明

- 读点：BackoffSupervisor(全)、DelegateTerminal(全)、DelegateRegistry(全)、DelegateBudget(全)、SubAgentStartupRecovery(全)、HeldDeliveryWatchdog/HeldReceiptStore(全)、LoopGuard(全)、AgentIdle(全)、SessionChildCascade(全)、TurnBoundaryDrains(全)、BgTaskRegistry(全)、AgentFinishTurn/AgentProcessing/AgentSessionExecution/AgentActor/BashTool/TaskStuckWatcher/AgentControlTool/Defaults（定点段）；delegate 链读面与前端映射经三路只读子代理核查（ChainsReadFace/delegateChainStub/bgAgents/PresenceRoutes/WebSocketRoutes/AgentFrozen/MergeQueue/NodeCanceller）。
- 未覆盖（声明）：外部执行器腿（ExternalSessionRunner/ExternalJudgeHook）仅经子代理转述，未逐行读；LLM 判定机内部（DelegateJudgeMachine）只读到端口层；前端 turnActivity/mobile 侧仅锚点级。案例①②的现场会话日志未取（仓外数据根），相关归因已标**假设**并给出日志判据（`loop-detected`/`loop-freeze`/`delegateBgWait`/`background job .* callback` 日志行）。
