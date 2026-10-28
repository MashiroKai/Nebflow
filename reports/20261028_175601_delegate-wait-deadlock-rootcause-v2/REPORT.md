# 「delegate 等子任务收轮→冻结」行为根因排查（二轮）

## 范围与方法
- 新增第四案（元案例）：`delegate-nebflow-84530237` 看门狗重启循环与 checkpoint 重复派子任务。
- 保留原三案：`#994`、`#988`、孤儿复活。
- 保留七问：outstanding 计数登记、SubTask 终态事件丢失路径、收轮语义、回执唤醒 idle 会话、提示词文案误导、watcher 盲区、根因链综合。
- 只做只读取证与分层修复设计；不修改在产引擎，不派子代理或后台作业。
- 每节以证据（文件:行 / 日志事件）为准；尚未核实的结论显式标“待证”。

## 当前进度
- [x] 建立可续写报告骨架。
- [x] 第四案：重启 12 次的链账与看门狗日志（主链已闭合）。
- [x] 上一轮草稿只读收割（无 REPORT/notes；改读已完成子会话终稿）。
- [x] 三案与七问逐条源码核实。
- [x] 分层修复设计（不实施）。
- [x] 根因链综合。
- [x] 最终校验并将本报告提交到本案分支（`d8bfeee6a`）。

## 第四案（元案例）：`delegate-nebflow-84530237` 看门狗重启循环

### 4.1 时间线与直接判定

- 会话 15:13:33 创建；15:28:01 至 17:40:37 共 **12 次** `stuck-detected → stuck-fire(level=stop)`。每次均为 `class=true-stuck`：registry 显示某工具仍在飞、`agentIdleMs == toolPhaseMs`、超过授权的 600s，且 60s 窗内从未出现 progress signal。工具名为 Subagent 8 次、Read 3 次、Grep 1 次；不是 crash 侦测，也不是单纯的 no-progress idle 计时。证据：`~/.stand/logs/watchdog/2026-10-28_events.jsonl:44-79`；`~/.stand/logs/stand.log:76100-76102,77916-77918,79739-79741,82803-82805,86294-86296,88683-88685,90286-90288,92191-92193,94355-94357,96023-96025,97230-97232,98487-98489`。
- watcher 对子 agent 的 L1 是 `AgentCommand.Stop`，actor 退出后由 BackoffSupervisor 的 death-watch 当作 `Terminated` 重启；所以日志写 “crashed” 是恢复通道的措辞，不代表原发事件是进程崩溃。代码：`TaskStuckWatcher.scala:34-46`、`BackoffSupervisor.scala:545-569`。
- 12 次重启都恢复 **同一份 7 条消息**：日志每次均为 `recovered 7 messages (resuming from checkpoint)`，例如 `stand.log:76119-76121`，其后同样记录见 `77942-77944 … 98505-98507`。磁盘 `~/.stand/sessions/delegate-nebflow-84530237.json` 也确实只有 7 条，最后时间戳 15:15:06；首批 5 个 Subagent 直到 15:17:38 才入链账，故“派子代理”这一代现场根本没有进入恢复 checkpoint。

### 4.2 为什么 `maxRestarts=2` 却能重启 12 次

- 监督器的预算不是终身次数，而是 `withinTimeRange=5min` 的滑动窗熔断：`recentRestarts = restartHistory.filter(now - t < 5min)`，只有 `recentRestarts.length < maxRestarts` 才重启（`BackoffSupervisor.scala:54-71,547-569`）。
- 本案每轮约为 10min 工具卡死判据 + 子代理清理/重启 + 恢复后运行，两个触发点相隔约 11.5–12.5min；每次到 `Terminated` 时，上次重启已滑出 5min 窗，`recentRestarts` 又回到空集，所以永远不触发 give-up。
- `restartCount` 仍单调累计并只用于日志/展示，`triggerLine` 因而打印 `#3/2` 直到 `#12/2`（`BackoffSupervisor.scala:625-633,659-675,771-790`）；这不是日志造假，而是“累计序号 / 滑动窗上限”两个量被放进同一个分数。**watcher 头注所谓‘再次卡死→maxRestarts 熔断’在慢周期下不成立**（`TaskStuckWatcher.scala:41-46`）。

### 4.3 为什么每次恢复又派子代理

恢复链实际做的事是：

1. `loadMessagesForSession(subagentId)` 读旧转录（`BackoffSupervisor.scala:686-695`）；
2. 只取消链账中仍为 `running` 的子会话（`SubagentCrashRecovery.disposeOrphanChildren`，同文件调用点 `BackoffSupervisor.scala:696-715`；实现 `SubagentCrashRecovery.scala:87-131`）；
3. 只修补**已落盘但没有 result 的 tool_use**，再注入“continue from where you left off”（`SubagentCrashRecovery.scala:47-85`、`BackoffSupervisor.scala:744-750`）。

本案第一批 Subagent 调用本身未进入 7 条 checkpoint，因此第 3 步没有可修补的悬挂调用；第 2 步只破坏性地取消旧子任务，并不会把“你已经派过哪些、哪些已完成/取消、不要再派”写入恢复上下文。模型每次从同一个 15:15:06 前的上下文继续，便再次选择 fan-out。链账是旁路状态，不是恢复提示词的事实源。

量化交叉证据：`~/.stand/subagent-tasks/delegate-nebflow-84530237.json` 共 46 个子任务，27 completed、19 cancelled；派生批次为 15:17:38、15:29:38、15:41:38、15:54:14、16:05:42、16:17:40、16:29:25、16:41:04、16:55:00、17:07:05、17:18:53、17:30:35、17:41:29，主题每轮高度重复。17 个 running 子任务由 12 次崩溃恢复清理取消，最后 2 个由 17:49 人工收编取消；取消时间与 `stand.log` 的 `disposed … orphaned child session(s)` 一一对应（如 `76115`, `77938`, `94374`, `98501`）。

### 4.4 元案例结论

最后一个环节不是“checkpoint 记住了还有子任务却仍错误重派”，而是更强的反例：**checkpoint 没记住派子任务，监督器只从旁路链账取消在飞者，却不把链账事实回注恢复上下文；滑动窗又使慢循环永不熔断。** 旧恢复上下文 → 重派 → 至少一个子任务超过 watcher 600s → Stop 被当 crash → 再读同一旧上下文，构成自维持闭环。

## 上一轮发现收割

上一轮根会话没有留下本案自己的 REPORT/notes，不能把它失控过程中的即时判断当成结论。本轮只读收割其已完成子会话终稿，以及其工作树中已落盘的 `reports/20261028_104800_report2-delegate-plugin-agentflow-mergequeue-portal__flow-4a0918e2/REPORT.md`、`reports/20261028_103847_delegate-mech-recon__flow-4a0918e2.md`，再逐项用当前源码、任务行和日志复核；下文不引用“子代理说过”作为证据。

采纳并复核的部分：

- **三层状态必须分开读**：`agentRegistry` 是内存活体，`SubAgentTaskStore` 是 per-parent 持久任务行，`DelegateRegistry` 是地址/元数据簿；“面板 Idle”“任务行 running”“没有 supervisor”不是同一件事，也不能两两互推。上一轮机制稿对该分域的描述可用，但它对本案的若干现场归因不可用。
- **恢复有两个不同层面**：进程内 `BackoffSupervisor` 用 transcript checkpoint 做同 id respawn；跨进程 boot sweep 才做孤儿复活/收殓。二者不能混为“重启都会复活并恢复全部等待关系”。代码：`BackoffSupervisor.scala:545-569,615-795`；`SubAgentStartupRecovery.scala:135-200,253-299`；`DelegateRevival.scala:109-240`。
- **现役 AgentFlow 是 ChainWrite/NodeEngine 薄壳**，ACK 即返、后台运行、末端结果通知创建者；不是旧 `core/flow` 引擎，也不是 `BgTaskRegistry` 的后台 Bash。代码：`AgentFlowTool.scala:8-20,40-53,166-183`。案 A 因此必须检查 delegate 终态与 flow-host 生命周期的关系，而不能只查 outstanding 计数。
- **需要纠正的上一轮归因**：案 B 的孩子是 `BlockingSubagent`（任务行 `source="subagent"`），不是 SubTask；该机制本来就不增加 `outstandingSubagentResults`，叶子也本来不挂 BackoffSupervisor，所以 `outstanding=0`、`supervised=false` 都是设计值。真正丢失的是父 L3 重启后不可重建的内存完成桥。
- **“回执不会唤醒 idle”不是普遍事实**：直接 SubTask/Delegate 的 `ExternalEvent` 在 `AgentIdle` 有明确注入与 `pipeLlmCall` 路径（`AgentIdle.scala:381-390,441-527`）；失效条件要具体到“目标已死 / 事件在 Processing 或 Frozen 排队 / held 台账缺失或穷尽 / 完成桥已随父重启丢失”。这避免了把所有等待故障错误归因于模型提示词。

上一轮本身同时成为第四案：它没有完成一次顺序取证，而是在每次旧 checkpoint 恢复后重新 fan-out；因此其“等回执”表象不能单独证明任何一个子机制有 bug，必须按下列四案分别闭环。

## 三案与七问

### 案 A：`#994` 现场（`delegate-nebflow-c93d27ad` / `flow-4a0918e2`）——delegate 先于 AgentFlow 链终态

- 时间线：`stand.log:37330-37334`，10:30:38 JEV 与 SmallJudge 两腿均不可用，`delegate-judge` 明写 `terminal verdict unavailable on both legs ... falling back to self-report/legacy`；10:30:39 flow-host unmount，父侧收到 `source=delegate type=completed`。五个 AgentFlow 节点却分别在 10:31–10:49 才完成（`stand.log:37634-37636,38860-38863,39094-39096,40651-40653,41798-41800`）。系统随即在 10:33:21 续出 `delegate-nebflow-d99a238a`（`stand.log:38010-38017`），它 10:54:42 也走了同一 fallback→completed→unmount 路径（`42648-42652`）。
- 机制：`outstandingSubagentResults` 的增量只认 `SubTask` 与 ephemeral `Delegate` 工具结果（`TurnBoundaryDrains.scala:88-104`）；AgentFlow/ChainWrite 登记的 node 会话不产生这个计数，也不是 `BgTaskRegistry` 的等待型后台 Bash。链状态只在终判时以 `inFlightChains` 供 judge 参考，且宿主缺失/快照失败时 fail-soft 为 0（`DelegateTerminal.scala:107-126`）。
- 判词不是硬闸：judge 失败时 `judged=None`，最终回落 self-report/legacy 或默认 `Completed`（`DelegateTerminal.scala:137-177`）。因此“作用域图里仍有非终态成员”最多影响判词注记，不能阻止 Release/unmount。节点结果之后由 NodeEngine 投给原会话；delegate 已 completed/unmount 后，结果失去 live 目标。
- 结论：案 A 的病根是 **AgentFlow/ChainWrite 没有进入 delegate 终判前的完成屏障**，与“提示词叫模型不要等”无关；即使模型不等，引擎也应拒绝带非终态链的 terminal verdict，或至少不许 unmount。

### 案 B：`#988` 现场（`delegate-nebflow-821b3f47` / `subagent-2689514a`）——L3 重启丢弃 BlockingSubagent 完成桥

- 现场纠正：父的 SubAgent 任务行显示孩子是 `source="subagent"`（`~/.stand/subagent-tasks/delegate-nebflow-821b3f47.json`，`task=subagent-2689514a`），不是 SubTask/Delegate。因此 `outstanding=0` 是设计值：BlockingSubagent 是同步工具调用，结果走工具返回值，不占后台通知 barrier（`BlockingSubagent.scala:15-55,117-159`；`TurnBoundaryDrains.scala:88-104`）。叶子无 BackoffSupervisor，`supervised=false` 也是设计值。
- 真实断点：父在 `stand.log:38000-38001,38437-38438` 于 10:33/10:34 走 L3 Full restart。BlockingSubagent 的等待状态是父 actor 栈上的 `Deferred` 完成桥和 120s/15min 两段等待（`BlockingSubagent.scala:84-92,177-221`）；L3 重建父会话后没有任何机制重新安装这个桥。孩子 11:17:49 只有 `turn-complete`（`stand.log:46433`），无人调用 `BlockingSubagent.reap`（`BlockingSubagent.scala:243-266`），于是 registry 里留下 live Idle actor、任务行保持 running。
- 父本体不是被这个孩子拖住：父在 10:46:34 已 `delegate_report(category="merge")`（transcript `~/.stand/sessions/delegate-nebflow-821b3f47.json` message[108]），registry 记录 `reportStatus="merge"`、`workReport` 在案，15:21:44 archive（`~/.stand/delegates/delegate-nebflow-821b3f47.json`）。真正孤儿是 `subagent-2689514a`，直到 17:49:46 人工取消（`stand.log:99646-99648`）。
- 结论：案 B 的病根是 **一次性内存完成桥没有所有权/收养协议**。监督恢复链能重建 SubTask/Delegate 的 supervisor 关系，但不能重建 BlockingSubagent 的 bridge；运行期清扫又因 registry 有活体而跳过。

### 案 C：09:37 boot 孤儿复活与运行期清扫盲区

- boot 对照：09:37 重启后五个孤儿被复活或收殓，其中包含案 B 父 `821b3f47`（`stand.log:7,25-36`）。复活判据只对 `source="delegate"`；`subagent` 明确“cheap to re-dispatch”不复活（`DelegateRevival.scala:72-96`）。delegate 复活会重建 supervisor、flow-host、预算并注入 checkpoint 或原 prompt（`DelegateRevival.scala:108-240`）。
- 运行期 `watchdogSweep` 只处理“任务行 running/restarting ∧ registry 无活体 ∧ statusAt 超龄”（`SubAgentStartupRecovery.scala:77-84,92-93,253-287`）。案 B 的 `2689514a` 恰是“registry 有活体 Idle actor，但 owner/bridge 已丢失”，永远不在清扫集合内。boot sweep 只在进程重启时执行，也不能覆盖这种运行期断桥。
- 另一个真实覆盖差是取消意图不持久：`AgentControlTool.doCancel` 先向 supervisor offer Cancelled，随后才异步写任务行/registry；无 supervisor 的降级路径是 notify/taskUpdate/registry/Stop 顺序，任一环节失败只 WARN（`AgentControlTool.scala:862-944`）。若 cancel 信号发出后进程重启而行未命中，任务行仍 running，下一 boot 可按在飞复活/收殓，用户意图被覆盖。本案主链没有这一形态，但它是“孤儿复活” complaint 的一个合法子类，修复设计必须一并闭合。
- 结论：boot 复活本身不是本案 bug；盲区在 **运行期没有“活体但失去 owner/bridge”的对账，取消意图也没有持久 tombstone**。

### 七问收束

1. **outstanding 计数何时登记/递减？** 增量在回合工具结果结算处：`TurnBoundaryDrains.countBarrierIncrements` 只数成功 `SubTask` 与非 persistent `Delegate`（`TurnBoundaryDrains.scala:88-104`），写入 `ExecutionContext.outstandingSubagentResults`（`AgentState.scala:526-561`）。递减点是三个状态的 `ExternalEvent` 分支：Idle 直接判 `isSubagentResult` 后减（`AgentIdle.scala:441-527`），Processing 排队并减（`AgentProcessing.scala:1294-1314`），Frozen 排队并减以防冻结期全完成导致计数永不清零（`AgentFrozen.scala:556-560`）。`AgentRecord.outstandingSubagents/pendingEventCount` 只是诊断快照（`AgentState.scala:121-131`），展示可滞后。BlockingSubagent、AgentFlow/ChainWrite、后台 Bash 都不进这个计数。
2. **SubTask/Delegate 终态事件有哪些丢失路径？** 正路是 `BackoffSupervisor.notifyParentAndStop`：delegate cleanup → parent `ExternalEvent` → delegate receipt → taskStore 状态 → registry/预算/actor 清理（`BackoffSupervisor.scala:828-945`）。可丢的位置必须分开：① parentRef 为 None 时先 WARN，再走 F2 持久注入队兜底，父会话已删则跳过（`BackoffSupervisor.scala:895-973`）；② 终态三清链中 IO 失败会被 `handleErrorWith` 吞成 stopped，状态可能停在半更新（`BackoffSupervisor.scala:938-943`）；③ 事件已送达但父在 Processing/Frozen/held 屏障中滞留，注入要等 `drainBarrier` 或 HeldDeliveryWatchdog；④ crash 时 `pendingEvents/outstanding/owedCompletion` 都在内存，恢复降级（`AgentState.scala:532-561`）；⑤ BlockingSubagent 不走这条路，终态完全由桥 + `reap` 写，桥死即全链无人写。投递回执超时重投只覆盖 delegate 源（`BackoffSupervisor.scala:858-876`），不是通用机制。
3. **“等子任务收轮”的硬语义是什么？** 回合末硬闸包括：`outstandingSubagentResults>0` 时 `Completed` 不发、replyTo 存入 `owedCompletion`（`AgentFinishTurn.scala:343-351`；`AgentState.scala:549-561`）；等待型后台 Bash 有 `bgTurnEndAccounting` 栅栏和账目注入（`AgentFinishTurn.scala:360-365`）；合并/ready-to-merge 有各自队列。**AgentFlow/ChainWrite 不在硬闸内**，只影响 judge 输入；BlockingSubagent 靠父进程内调用栈等待，不是跨重启闸。
4. **回执会唤醒 idle 吗？** 直接 SubTask/Delegate 事件会：Idle 分支若减后 `newOutstanding==0` 或有 held 批，就立即 `pipeLlmCall` 注入并开新 turn；后台/非子代理事件也有 continuation replyTo 修复（`AgentIdle.scala:381-527,544-549`）。所以“通知根本不会唤醒 idle”不成立。真实盲区是：目标 actor 已死或桥已丢；事件在 Processing/Frozen 排队只能等边界；held 台账缺失/穷尽时 `DrainHeldReceipts` 只对 Idle 强制补投（`AgentIdle.scala:720-744`），Frozen/Processing 是 no-op；delegate 收据重投不覆盖 BlockingSubagent。
5. **提示词文案是否误导？** 文案按机制各自为真，但被模型当作同一族“会通知/可等待”：`SubagentTool` 明说阻塞至 15 分钟（`SubagentTool.scala:15-24`）；Delegate/SubTask 说明后台通知；AgentFlow/ChainWrite 承诺节点完成通知（`AgentFlowTool.scala:40-53`）。误导不在单条措辞，而在缺少统一的“调用面 × 生命周期 × 断桥后行为”说明；用改文案替代硬闸会把案 A/B 变成“模型纪律”问题。
6. **watcher 有哪些盲区？** `TaskStuckWatcher` 只扫 `status==Processing` 的 delegate/ephemeral/flow/subtask 活体（`TaskStuckWatcher.scala:88-89,721-737`），不看父 actor 是否仍持有 bridge、不做父子链账对账。工具相位阈值尊重 `currentToolDeadlineMs`，但 Subagent 没有可声明的 timeout 入参，内部授权 15min 而父按默认 10min 判死（`TaskStuckWatcher.scala:417-447`；`SubagentTool.scala:26-40`；`BlockingSubagent.scala:54-62`）。子代理后续活动帧只进完成桥，不刷新父的 `lastProgressSignalAt/currentToolStartedAt`，合法等待被当 `true-stuck`。加上 `BackoffSupervisor` 5 分钟滑动重启窗（`BackoffSupervisor.scala:545-569`），慢循环永不熔断。Project flow 的 L3 自检在无 runtime 时显式跳过（`stand.log:37115`），也说明“unmount 容忍”被泛化到仍应有闸的 delegate 终态。
7. **根因链综合？** 见末节；三案不是同一 bug，而是共享“所有权/终态屏障缺位”的薄弱面。

## 分层修复设计（只设计，不实施）

目标不变量：**每一个可等待对象都有唯一 owner；owner 消失时有确定性接管者；delegate 终判前所有声明过的等待对象都要么归零、要么被显式弃权记账。** 以下各层互相独立，可分批落地；优先级按案 B/四案 → 案 C → 案 A 排列。

### L1 调用面：把“授权时长”与“活性”还给 Subagent

- 单源授权：BlockingSubagent 的 120s 起步预算与 15min 完成硬顶已有常量，但父会话 `AgentRecord.currentToolDeadlineMs` 未随调用设置。修复是工具执行层在进入 BlockingSubagent 时写父记录的 `currentToolDeadlineMs = 15min`、`currentToolName = Subagent`，工具返回时清理；`ToolStuckJudgment.effectiveToolPhaseMs` 因此自然放宽到授权 + slack（`TaskStuckWatcher.scala:432-447,2337-2340`）。不新增 `timeout` 入参，避免模型自 declared 时长绕过 15min 硬顶。
- 活性桥：完成间谍已在子会话帧路径上（`BlockingSubagent.scala:271-300`）。让间谍在非终态帧上也调用父会话的 `markToolProgress`（或等价 touch），使“孩子仍在动”成为父记录的 progress signal；终态帧继续只喂 `Deferred`。验收：子任务运行 11–15 分钟且持续产帧时，父 watcher 不得产生 `true-stuck/L1`；子任务真死时仍按 120s/15min 或零帧超时失败。
- 明确合同：工具描述只说“block up to 15 minutes; child activity refreshes the parent watchdog”，不给“可等任意久”的暗示。

### L2 所有权与恢复：BlockingSubagent 桥的持久账本、收养或确定性 reap

- 新增最小等待账本（建议 `BlockingWaitLedger`）：`childId/parentSessionId/parentGeneration/toolCallId/startedAt/state`，spawn 时写、正常 `reap` 清、进程内周期对账。它不替代 `SubAgentTaskStore`，只补“谁在等这个孩子”这一缺失事实。
- 父 L1 stop 后的 BackoffSupervisor respawn 保留现语义：同父代继续等待，桥由调用 fiber 持有，账本不变。
- 父 L3 Full restart / actor 代际更换时不能 silently 弃桥。默认策略采用**确定性收殓**：新代启动读取账本中本会话的 `state=waiting` 行，Stop 孩子、`BlockingSubagent.reap(status="cancelled")`，并在恢复上下文注入“上次 Subagent 已取消，不要重派或显式重派”。因为 BlockingSubagent 的结果契约是旧工具调用的返回值，旧调用已不可复活；把它伪装成后台通知会引入第二套语义。
- 可选扩展（默认关闭）：对长任务改为“注册后台 + 立即返回 token”，完成走 delegate 式 receipt。只有在该模式成为一等机制后，才允许收养而非取消；本层先不引入。
- 验收（直接映射案 B）：父 L3 后不允许出现 `source=subagent` 且 registry live Idle、任务行 running 的行超过一个 grace 周期；孩子完成时若 owner 已亡，必须落 cancelled/completed 终态并产生可读审计行，而不是只有 `turn-complete`。

### L3 恢复上下文：链账事实必须回注

- `SubagentCrashRecovery` 现在只修已落盘 dangling tool_use、取消 running 子任务、恢复旧 transcript（`SubagentCrashRecovery.scala:47-131`）。在此之后追加一条系统注入：按 `SubAgentTaskStore` 生成本会话子任务摘要——总数、completed/cancelled/failed 计数、最近 10 条 `id + description + status + 终态时间`，以及显式指令“这些是已派发事实；重复任务先复用结果，确需重派时改名并说明差异”。
- 恢复前把当前 turn 的 tool_use（尤其 Subagent/Delegate/AgentFlow）同步落盘，或至少在 respawn 前把已发调用写入账本，避免第四案“首批 fan-out 根本不在 checkpoint”的形态。
- 给重复派单加软闸：同一 parentGeneration 内，相同 `normalized(prompt hash)+target agent` 的子任务已有非 running 终态时，工具返回结构化警告/要求显式 `allowDuplicate=true`。这是防回归护栏，不替代上下文注入。
- 验收（直接映射第四案）：恢复后 transcript 必须可见“46 个已派任务、27 completed、19 cancelled”级别的事实，或工具层拦截同题重派；不允许恢复 7 条旧消息后无提示地再 fan-out。

### L4 重启预算：慢循环也要有总量熔断

- `BackoffSupervisor` 保留 5 分钟滑动窗用于快崩溃，但增加三类终身预算：① 同一任务总重启数；② 累计重启墙钟/任务年龄上限；③ 同源触发熔断——`stuck-detected` 的 `(toolName, class)` 指纹连续 N 次相同即 give-up。give-up 走 F2/failed 通知与任务行终态，而非无限 respawn。
- watcher 事件把恢复链决策与 supervisor 预算共用一个 ledger，`triggerLine` 显示“累计/总预算、滑动窗/窗预算”两个分数，避免 `#12/2` 这种误读。
- 对合法长等待：L1 的授权时长与活性桥先消除误杀；预算只对真重复失败生效。验收：12 次同因重启在第 3–5 次内终态化，且每次 give-up 都有父可见 failed 事件。

### L5 AgentFlow/ChainWrite 完成闸

- 在 `DelegateTerminal.onCompleted` 中把 `inFlightChains` 从 judge 参考升级为确定性前置闸：非终态链存在时，默认不允许 Release/unmount；产出 withheld/blocked 形态，链账与父通知写明 `waiting-on-chains=[chainId...]`。只有两个出口：链全终态后重入终判；或调用者用 `delegate_report(abandonChains=true, detail=...)` 显式弃权，弃权本身入 ChainStore 并随判词注记交付。
- flow-host unmount 必须放在终判成功之后；judge 两腿失败只允许降级判词，不允许跳过该闸。`inFlightChains` 读取失败时 fail-closed（保守拦截并留错误），替代现在的 fail-soft 0。
- NodeEngine 对已终态 delegate 的末端通知增加兜底：目标不存在时把结果落 salvage 文件/父 F2 队列，并打 `result-after-delegate-terminal` 审计行；这是闸失效时的最后兜底，不是替代。
- 文案同步改：AgentFlow 不再说“回本会话等通知”作为唯一收尾方式，而写“delegate 保持未终判直到链终态，或显式弃权”。
- 验收（直接映射案 A）：五个节点未完成时，10:30:39 那类 completed+unmount 不可能发生；显式弃权路径可审计。

### L6 运行期对账与取消 tombstone

- 扩展 `SubAgentStartupRecovery.watchdogSweep` 的三态逻辑：registry 无活体→现行强制终态；registry 有活体且 Processing/带合法 supervisor→不动；registry 有活体 Idle/SubTask 且 `BlockingWaitLedger` 无 owner 行且超 grace→确定性 reap（cancelled）并审计。判据必须包含任务行 `source` 与 ledger，而不是只看 registry。
- `SubAgentTask` 增加持久取消标记：`cancelRequestedAt/cancelBy/cancelId`。cancel 入口先写 tombstone，再发 supervisor/Stop；boot 复活与收殓先读 tombstone，取消意图不可被“行仍 running”覆盖。对 delegate 复活，tombstone 优先于 `DelegateRevival.eligibility`。
- 清理链幂等化：`notifyParentAndStop` 的 cleanup/notify/receipt/taskUpdate 各腿独立重试或持久 work item，至少保证“通知已发但任务行 running”可由对账器补写，而不是停在半更新。
- 验收（直接映射案 C）：`2689514a` 形态在 10 分钟级被 reap；用户取消后的引擎重启不会复活该行。

### L7 提示词与展示

- 按机制拆清，不做全局笼统句：Subagent=阻塞、预算 15min、父 watchdog 会被子活动刷新；Delegate/SubTask=后台、终态通知、crash 后 receipt/F2/复活；AgentFlow/ChainWrite=后台链、delegate 终判受链闸约束、弃权要显式；后台 Bash=回合栅栏账目。
- 面板/AgentControl 把 `outstandingSubagents`、`pendingEventCount`、`unreadReceiptCount`、链账非终态数与 BlockingWaitLedger owner 状态同屏，避免“Idle=没事、running=在飞”的二义。
- 文案不是修复本体：L1–L6 的硬闸先行，文案只解释硬闸。

### 不建议的修复

- 只把 TaskStuckWatcher 的 10min 调大：会把误杀改成晚杀，且不解决桥丢失与重复派单。
- 只给 Subagent 加 `timeout` 参数：让模型自行声明时长，回到 R6 批之前“模型可绕过授权”的形态。
- 只在提示词禁止“等待”：模型可遵守也可不遵守，案 A/B 的引擎事实不变。
- 把 BlockingSubagent 改成后台通知而不换契约：会与 SubTask/Delegate 的 barrier/receipt 语义重叠，产生新的丢终态路径。

## 根因链综合

**总命题：模型“结束本轮等通知”只是诱因；真正根因是各执行机制的等待所有权与终态屏障不一致。** 引擎里同时存在四套“子任务”：SubTask/Delegate 走内存 barrier + receipt/F2，BlockingSubagent 走一次性 `Deferred` 完成桥，AgentFlow/ChainWrite 走 NodeEngine 节点通知，后台 Bash 走 `BgTaskRegistry` 栅栏。它们的生命周期都可能在 owner 终态或重启时断裂，但只有 SubTask/Delegate 和后台 Bash 有跨状态硬闸与恢复链。

- **案 A 链**：AgentFlow 不入 `outstanding` → `inFlightChains` 仅是 judge 输入且 fail-soft 0 → judge 双腿失败回落默认 completed → Release/unmount 先于五个节点终态 → 节点结果失去 live 目标 → 只能续出下一个 delegate。要点：**后台链缺硬完成闸**。
- **案 B 链**：BlockingSubagent 结果契约是父栈上的 `Deferred` → 父 L3 Full restart 销毁栈与桥 → 恢复链能重建 SubTask/Delegate supervisor，不能收养 subagent 桥 → 孩子完成只有 `turn-complete`，无人 `reap` → live Idle actor + running 任务行 → 运行期 watchdog 因 registry 有活体而跳过。要点：**一次性内存桥无所有权账本与确定性接管者**。
- **案 C 链**：boot sweep 只在进程启动时对账；运行期 sweep 只认“registry 无活体”；取消意图不是持久 tombstone → “有活体但失去 owner”的行、“信号已发但行未更新”的行都落在两类 sweep 之间。要点：**运行期缺少跨“任务行 × registry × owner 账本”的对账，取消不持久**。
- **第四案链**：旧 checkpoint 未含首批 Subagent 调用 → 恢复只取消链账 running、不把已派/终态事实回注 → 模型从同一上下文再 fan-out → 合法 15min 子任务被父 10min 工具相位判死，子活动帧不刷新父 progress → L1 Stop 被监督器当 crash → 每轮约 12 分钟超过 BackoffSupervisor 5 分钟滑动窗 → `recentRestarts` 清零、永不 give-up → 12 次重启循环。要点：**恢复上下文缺链账事实 + watcher 与工具授权冲突 + 慢循环预算失效**，三者叠加自维持。

四案共享的不是同一个 bug，而是同一薄弱面：**owner 消失、调用面更换或终判降级时，没有统一的事实账本说清“谁还在等谁、已等到了什么、谁有权收殓”。** 判决顺序应是：先用 L1/L2 消除 BlockingSubagent 误杀与断桥，再补 L3/L4 的恢复事实与总预算，最后把 AgentFlow 纳入 L5 的终判硬闸；运行期对账与持久取消（L6）贯穿全链。完成这些后，“等子任务收轮”才从模型纪律变成引擎不变量。
