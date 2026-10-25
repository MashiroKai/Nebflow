# 修复批 · 子代理等待三缺口（990bd848 卡死链）REPORT

日期：2026-10-25 ｜ 分支：`delegate/delegate-nebflow-86906dc5` ｜ 基点：44d5243fa

## 0. 结论

三个缺口全部封堵并带 spec 验收；新增 15 测全绿，指定回归族（BackoffSupervisor / TaskStuckWatcher / DelegateTool·Subagent）通过，1 个**预先存在**的红测（WatchdogSelfMonitorSpec，基点同红，与本批无关，见 §5）。引擎在产，未重启；交付走合并队列。

## 1. 实证链回放（stand.log 16:48–17:00）

- 16:48:35 delegate `990bd848` 发起 Subagent 调用 → 子会话 `subagent-cc369fb8` spawn，持续出帧干活（WebSearch/WebFetch 直至 17:15+）。
- 父 delegate 阻塞在 Subagent 工具相位：agent 侧事件流静默 → 16:59:01 watcher 判死（"tool 'Subagent' running 625s"）→ Stop → respawn（89 msgs checkpoint）。
- **缺口②（自毒根因）**：checkpoint 断点里无 result 的 tool_use 原样进恢复会话；registry 的 `currentToolStartedAt` 跨 respawn 残留成幽灵相位 → watcher 以 "tool 'Subagent' running 655s/685s" 连续判死，escalate 的 `cancelInflightFor`/`transportAbortFor` 掐掉的是恢复会话**健康的续跑轮**（16:59:31 / 17:00:01 两连杀）→ maxRestarts=2 烧尽 → 批失败；子会话成孤儿继续烧 token 27min+。
- **缺口③（扣杀无出路）**：17:00:01 "no in-flight LLM request (tool-phase); process kill withheld [evidence #5 container-blindness guard; class=provider-hang]" —— 护栏无条件扣杀，三连 Stop 无效后无进程级出口。

## 2. 改动（缺口 → 实现 → 验收）

### 缺口① 子代理活性判定（等待面）
- `shared/Defaults.scala`：新增 `SubagentStartBudgetMs`（sys prop `nebflow.subagent.startBudgetMs`，默认 120000，每次现读——StuckThresholdMs 同族 kill-switch 先例）。
- `core/tools/BlockingSubagent.scala`：
  - `startSpy`：子会话经 wsSend 间谍链的**第一帧**（`agentStart` 领衔；完成帧天然蕴含）落活性标记——零新协议、零新 actor，与完成桥同链。
  - `awaitResult`（实现单点，spec 判红锚）：两段等待——段一活性（预算窗内零帧 = 判子死：Stop + 补面板终态帧 + 结构化失败 `subagent-never-started` 返回，父 agent 自行重试/绕过）；段二完成（15min 硬顶 + finalText 桥，与改造前逐字）。哲学对齐 #692：等不到活性事实 = 对端死亡判定，不是超时杀。
- 验收：`BlockingSubagentStartGuardSpec` 5 测——判死腿真实计时（≥预算窗 400ms、≪15min）、活性腿零漂移、空文本回归钉、startSpy 首帧落标、旋钮默认/现读。

### 缺口② checkpoint 恢复的在飞工具调用终结（恢复面）
- 新文件 `agent/SubagentCrashRecovery.scala`（单点三件）：
  1. `terminateDanglingToolCalls`（纯函数）：transcript 里每条无 result 的 tool_use（= 崩溃中断的在飞调用，K-1/K-2 纪律下当且仅当批次中途崩溃才出现）补一条 `recovered-after-crash` 错误 result（isError=true，`source="crash-recovery"`，同批多悬挂合并一条 user 消息——provider 配对形态）。恢复会话的 LLM 看见失败事实，自行重试该步或绕行；禁止原样重进等待。
  2. `disposeOrphanChildren`：崩溃会话名下仍 running 的子任务（唯一等它的人已死）→ Stop + registry 移除 + task 终态化 cancelled + ask 槽清理（镜像 BlockingSubagent.reap 结算面）。fail-soft，绝不阻断 respawn。
  3. `staleToolPhaseCleared`（纯函数）：恢复记录清幽灵工具相位——补齐 `AgentRegistryEmit.touchRegistryActivity`「离开 Processing 一律清工具相位」不变式（respawn 直改 registry 曾绕过）。
- `agent/BackoffSupervisor.scala` respawn 链接入三件（dispose → repair → childSpawnFn(repaired)；registry refresh 落 `staleToolPhaseCleared`），各腿带修复量日志。
- 验收：`SubagentCrashRecoverySpec` 6 测——悬挂 Subagent 调用恢复即失败返回（token/配对/顺序钉）、完整配对恒等零漂移、空/多悬挂形态、孤儿处置（Stop+移除+终态化+干净兄弟行不动）、无孤儿 no-op、幽灵相位清除+干净记录恒等。

### 缺口③ tool-phase 挂死的受控击杀通道（watcher 升级面）
- `core/processor/TaskStuckWatcher.scala` 子代理阶梯（`rec.parentRef` 分支）L2：transport abort 命中 0 且 `cls.recoverable`（类① true-stuck——零正信号、确实停摆）时，不再无条件扣杀——放行**受控击杀**：复用 `BgTaskRegistry.reclaimSession`（按自有 PGID 击杀会话进程组 + 结算名下 bg 任务 + WS 终态帧）。放行闸 = 三连 Stop 无效（`attempts >= StopAttempts+1`，恰为 transport abort 武装拍）+ 等待面已判死（`cls.recoverable`）+ §3.4 互斥点 1(b)（status==Processing）。类②（仍在推进）/类④（provider-hang，LLM 层自管）进不来；纯 Deferred 挂死形态仍由下一拍 giveUp 终态收殓（通道不越权）。flow/root 分支零改动（flow 有 L3/L4 出口；root 按设计只广播由用户决策）。
- 验收：`TaskStuckWatcherControlledKillSpec` 4 测——正控（attempts=3 + n=0 + true-stuck ⇒ 探针 bg 任务被 reclaim + cancelled 帧 + Stop 照发）、负控①（attempts=2 不杀）、负控②（类④ 不杀）、负控③（类② 零动作不杀）。开杀日志带 `controlled-kill channel` 可 grep 标记。

## 3. 测试证据

| 套件 | 结果 |
|---|---|
| 新增 3 spec（15 测） | **15/15 绿**（17:38 本地跑，日志含行为面断言输出） |
| 回归：BlockingSubagentEndToEnd / DelegateToolSpec / AgentFlowTool / BackoffSupervisor{Cancel,EmptyBurn,RestartDelay} | 42/42 绿 |
| 回归：TaskStuckWatcher / StuckJudgementOrder / WatchdogSelfMonitor / StuckRecoveryBudget / StuckRecoveryLeg | 63/64，1 红为预先存在（§5） |
| 回归：StuckRecovery{LedgerWritePoint,ResumeCallback} / StuckTimeBase / WaitTimeoutR2Scan / EnvLostFastFail / CancelSourceAndToolDeadline / WatchdogCriteriaReplay / WatchdogReplaySegmentation / SubAgent{TaskStatus,Barrier} | 57/57 绿 |
| 编译 | main + Test 全绿 |

基点对照：`git worktree add --detach /tmp/wsm-baseline HEAD` 在无改动基点单独跑 WatchdogSelfMonitorSpec → 同样 5 测 1 红（已清理临时 worktree）。

## 4. 文件清单

改 4：
- `src/main/scala/nebflow/shared/Defaults.scala`（+16：SubagentStartBudgetMs）
- `src/main/scala/nebflow/core/tools/BlockingSubagent.scala`（活性判定窗 + awaitResult 单点 + startSpy）
- `src/main/scala/nebflow/agent/BackoffSupervisor.scala`（respawn 三件接入 + 幽灵相位清除）
- `src/main/scala/nebflow/core/processor/TaskStuckWatcher.scala`（子代理阶梯受控击杀通道，+29 行，flow/root 分支零改动）

新增 5：
- `src/main/scala/nebflow/agent/SubagentCrashRecovery.scala`
- `src/test/scala/nebflow/core/tools/BlockingSubagentStartGuardSpec.scala`
- `src/test/scala/nebflow/agent/SubagentCrashRecoverySpec.scala`
- `src/test/scala/nebflow/core/processor/TaskStuckWatcherControlledKillSpec.scala`
- `reports/20261025_174453_subagent-wait-guards/REPORT.md`（本文件）

## 5. 未做 / 开放项

1. **WatchdogSelfMonitorSpec「R8-③ shadow 守门」预先存在红**（基点同红，与本批无关）：断言 `cAborted`（"L1 必须真实硬取消在飞 LLM"）是 killruling #28（98b3e3b2c，2026-09-23 退役 L1 auto-kill）之前的旧契约——L1 退役后 scan 1 为 sensor-only，硬取消最早发生在 scan 2（L2 transport abort），scan 1 后 `halt.tryGet` 必为 None。属旧断言未随退役更新，本批不越权修（另一批的契约面）；修法明确：把 `abortedAfterL1` 的测量点移到 scan 2 之后，或按退役语义改断言。
2. **stopCounts 跨 respawn 不复位**（观察项，未动）：watcher 的 Stop 阶梯计数按 sessionId 持久，respawn 后新会话继承旧档位（事故中 attempts 2/3 打在新 respawn 会话上）。缺口② 清幽灵相位后 watcher 对新会话不再误判，该残留的实际危害已消大半；彻底修需 watcher↔supervisor 复位协议，另批。
3. **判死腿的集成形态覆盖限度**：真实 AgentActor 子会话在 turn 派发前零帧的形态在测试内不可构造（agentStart 在 LLM 派发前发出），故「零帧判死」以 `awaitResult` 单点真实计时验收 + 端到端桥由既有 BlockingSubagentEndToEndSpec 承重（若活性桥接线断裂，该 spec 会在预算窗处误开火转红——互为哨兵）。
4. **单子会话 15min 硬顶未动**：>15min 的健康长侦察（事故中子会话实际跑了 27min）仍会超时取消——那是 SubagentTool description 声明的既有契约（hard cap 15 minutes），改它是行为变更，未获授权不做。

## 6. 关键假设

- 活性事实的口径 = 子会话经 wsSend 间谍链的首帧（headless 下 wsSend=None 时间谍仍接收——间谍在路由包装外层，语义不变）。
- 恢复面 repair 覆盖**全部**悬挂 tool_use（不限 Subagent）：崩溃点任何未完成调用的 result 都永远不会到来，transcript 配对完整性是 provider 硬要求；Subagent 类另加孤儿子会话处置。文本统一 `recovered-after-crash` token（spec 判红锚）。
- 受控击杀的动作面复用 reclaimSession 既有原语（Nebula 2026-09-07 裁定的收殓支），不新造 kill 机制。
