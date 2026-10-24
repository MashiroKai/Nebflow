# 无损热重启与统一更新控制面 · 调研+设计（评审稿）

> 批次：调研+设计批（2026-10-24），实现另行立项。本文只设计不实施；所有代码论断带 `路径:行号` 锚点（相对仓库根），供逐条复核。

---

## 0. 作者令（逐字）

「stand的无损热重启功能如何，这对我们的分布式架构也很重要。我们更新通过官网用户的主页统一控制，然后设备如果在跑，我们要能无损续跑，比如我们现在后台跑了很多agent和任务，热重启的时候 agent要能不受影响。」

拆成四问：① 现在重启到底什么死了、什么活着（代码为准）；② 离「无损」差哪几块；③ 无损热重启机制怎么设计（drain→快照→换血→复活）；④ 与官网主页统一更新控制面怎么接、多设备版本一致性与 C2 租约怎么落。

---

## 1. 目标语义：两档，取舍说清楚

根本物理约束先立此存照：**在飞的 LLM HTTP 调用不能跨进程迁移**。流式尾巴要么等它收束、要么掐断——不存在第三条路。所以「无损」只能定义在收束点之后，由此分两档：

| | **档 A：原地续跑（turn 边界泊车）** | **档 B：控制面零丢失（数据面短暂中断）** |
|---|---|---|
| 语义 | drain 等 in-flight agent/delegate 到 **turn 边界**（当前 LLM 调用收束＋工具批落盘），落泊车标记 → 换血 → boot **主动拉起**（非懒加载）＋续跑 nudge；agent 看到「计划内重启，请继续」，不是「crash，恢复」 | 立即可换血；盘上持久面全保；复活走既有 checkpoint（ToolsComplete 粒度），当前 turn 尾巴由 agent 续跑重做；在飞工具批丢弃并告知 |
| agent 感受 | 一条 system「planned restart, continue」；零重做 | 一条 system「process restarted, continue from checkpoint」；最后一拍重做 |
| 丢失面 | 收束点之后零丢失（消息、排队注入、任务台账、委托链） | 收束点之前的流式尾巴＋当前工具批（transcript 已落盘部分不丢） |
| 重启窗口 | 受最长 turn 支配 → **必须带 deadline，到点自动降档 B** | 有界、与负载无关 |
| 成本 | 新增快照面（turn 泊车标记）＋boot 主动拉起腿 | 零新增（现状即档 B＋WaitIdle 前置） |
| 工具副作用 | 已落盘的不重做 | 重做尾巴可能重复执行外部副作用（resume prompt 已带「未落盘副作用不可信」告知，`core/project/NodeRecovery.scala:894-902`） |

**裁定建议**：统一更新链路默认档 A（`drainDeadlineMs` 建议 120s，可配），到点自动降档 B 并在 UI/主页声明；手工热重启两档可选。今天的热重启实际上是「档 B＋先等全空闲（WaitIdle）」——长任务在跑就永远换不了血，这正是作者令要解的痛。

---

## 2. 现状审计

### 2.1 死亡清单（重启时什么死、死在哪）

| # | 什么死 | 死因与锚点 |
|---|---|---|
| D1 | **在飞 LLM 调用**（含流式尾巴） | SIGTERM 链显式 abort：`core/project/ShutdownState.scala:106-108`（`cancelAllInflightSync`）＋`gateway/GatewayMain.scala:1447-1468`（releaseBackend TCP 级掐断） |
| D2 | **agent 运行态整面**（busy、排队注入、askuser 未决、compact 在飞、错误冻结、barrier/完成债） | `actor/AgentState.scala:754-762` 逐字「AgentState is NEVER persisted — a JVM restart produces a fresh actor」；`pendingUserInputs` 显式不持久 `agent/CompactionQueueStore.scala:27-29`（定义 `AgentState.scala:587`）；askuser 未决＝内存 Deferred `AgentState.scala:498-500`，boot 全部过期清算 `GatewayMain.scala:729-742`；frozen 闭包不持久 `agent/AgentFrozen.scala:120-122,145-156`；barrier/完成债自证 in-memory `AgentState.scala:524-544` |
| D3 | **在飞 delegate 的监督链与 actor** | BackoffSupervisor 是进程内 behavior（`agent/BackoffSupervisor.scala:29`），随进程消亡；跨进程续命只靠 boot 复活腿（见 S8） |
| D4 | **后台任务注册表与输出缓冲** | `core/tools/BgTaskRegistry.scala:79-80,346-350` 逐字「等待型任务无持久面（进程内 Ref），重启即亡」；`BgTaskOutputStore.scala:18-23,72`；在跑 shell 子进程随 JVM 死；**无任何 boot 重登记路径** |
| D5 | **dag 流实例**（dag- 会话） | `core/entity/RunningFlowRegistry.scala:42-56` 全 Ref 无持久化；`FlowDagExecutor.scala:297-304` nodeSessions 内存表——引擎重启 dag 流无恢复 |
| D6 | **卡死恢复账本**（RecoveryLedger/防自激闸门） | `core/processor/TaskStuckWatcher.scala:570-594,634-683` 内存态，重启即清（防自激预算归零——可接受，标注即可） |
| D7 | **SIGTERM 路径的会话落盘尾巴** | JVM hook 链（`Main.scala:331-377`）**没有** SessionStore flush——模型消息 2s debounce＋UI 500ms debounce 的尾巴随进程丢；对照热重启 [3] 有显式 flush：`core/hotrestart/HotRestart.scala:330-335`（`flushPendingUiWrites/flushPendingMessages`） |
| D8 | **turn 边界标记的写面**（详见 G1） | `core/flow/TurnStateStore.scala:69` 的 `save` 全仓**零生产调用点**（仅定义＋测试）——写面已死，读/清/恢复面还活着且不可达 |
| D9 | 网关 cookie 会话、WS 连接、agentRegistry 活体表 | `GatewayMain.scala:463-465`「in-memory by design」；`agent/SharedResources.scala:88-94`（Ref） |
| D10 | 外部 daemon 子进程 | `GatewayMain.scala:1447-1468`（daemonService.stopAll，随宿主死） |

### 2.2 家底清单（什么活着、覆盖到什么程度）

| # | 家底 | 是什么 / 覆盖程度 | 锚点 |
|---|---|---|---|
| S1 | **会话存储三层** | `<dataRoot>/sessions/<id>.json`（LLM 消息，durability 点整列重写：每工具批 ToolsComplete、turn 终态、错误/冻结前；2s debounce）＋`<id>.ui.json`（500ms debounce）＋`_index.json`；乒乓换入直写绕 debounce | `core/SessionStore.scala:544,292,1404,655-674,693-708`；落盘点 `agent/AgentProcessing.scala:334,782,840`、`agent/AgentFinishTurn.scala:334-342,491-497` |
| S2 | **F2 持久注入队** | `sessions/<id>/injection-queues.json`（immediate inputs＋events，入队/出队双写）；bg 通知经 BgInboxBackstop 落同一文件（correlationId 幂等） | `agent/CompactionQueueStore.scala:13-30`；`agent/BgInboxBackstop.scala:9-12,24-43`；重放 `agent/AgentIdle.scala:595-620` |
| S3 | **任务台账 tasks-v2** | `~/.nebflow/tasks-v2.json` 整份原子写，三态机 open/closed/completed＋note 史 jsonl；重启后全在 | `core/project/TaskLedgerStore.scala:55,134,770,302,434,473,513,564` |
| S4 | **链号台账 chain-ledger** | checkpoint＋journal＋冷档；链身份/别名/引用计数/**暂停取消意图**持久，重启仍生效 | `core/project/ChainLedger.scala:75-78,251-304`；`ChainLedgerStore.scala:116-141,726-796` |
| S5 | **flow-map 节点持久层＋优雅关机翻态** | SIGTERM：draining→shutdown-marker→Running 逐节点 CAS→Interrupted（5s deadline）→abort 在飞 | `core/project/ShutdownState.scala:84-133,153-224` |
| S6 | **节点级崩溃恢复** | boot 快段把 Running/Interrupted 按 transcript 完整性三分类认领（翻 Pending＋清 bgWait / failNode），慢段 `startNode(resume)` 从磁盘 transcript 断点水合续跑 | `core/project/NodeRecovery.scala:303-457`；`core/project/ProjectCrashRecovery.scala:10-36`；契约钉死 `src/test/scala/nebflow/agent/CrashResumeSpec.scala:19-27` |
| S7 | **delegate 任务行＋地址簿** | `~/.nebflow/subagent-tasks/<parentSessionId>.json`（status/retryCount/revivalCount）；DelegateRegistry 记 cwd/worktree/branch/plugins | `shared/SubAgentTaskModels.scala:19-50`；`core/tools/SubAgentTaskStore.scala:20-35`；`agent/DelegateRevival.scala:27-30` |
| S8 | **boot 孤儿处置：复活优先、收殓兜底** | sweep 对 running∪restarting 孤儿先判复活资格（kill-switch/source=delegate/registry 记录/座位/父会话在/预算），合格者 **DelegateRevival 同 id 复活**（transcript checkpoint＋监督链重建＋nudge）；不合格者收殓（捞 partial→F2 幂等通知→翻 failed＋重启指纹）；另有 #692 运行期 watchdog | `agent/SubAgentStartupRecovery.scala:92-93,135-201,257-299`；`agent/DelegateRevival.scala:72-96,102-241`；预算默认 enabled/maxPerTask=2/maxAge=7d/bootCap=8/concurrency=2 `shared/Defaults.scala:1130-1153` |
| S9 | **进程内监督链（BackoffSupervisor）** | crash/空烧→指数退避 respawn，从 SessionStore 断点续跑＋continue 注入；终态三清＋parentRef=None 时 F2 持久兜底 | `agent/BackoffSupervisor.scala:545-735,864-890` |
| S10 | **热重启机器（已相当完整）** | [1] 五域在飞判定 quiesceReport（flow-map 节点/sub-agent 任务/Processing 会话/在飞 LLM/等待 bg 任务）→[2] draining 准入闸（WS/Dispatcher 单一 choke 点拒绝）→[3] 会话落盘 flush→[4] intent 写前账→[5] spawn 后继→[6] C1 2s 存活闸→[7] C2 readyToBind 30s→[7.5] 四档健康自检（存活/到门口/端口可服务/version-match）→[8] 优雅让渡（Deferred→Ember FIN→.guarantee 链）；WaitIdle/RejectIfBusy 两模式＋drain deadline＋15s 观察窗＋冷却窗 | `core/hotrestart/HotRestart.scala:17-46,104-132,213-308,320-438,440-479`；端口让渡 `core/hotrestart/SuccessorGate.scala:211-236`；准入门 `gateway/WebSocketRoutes.scala:2098-2108`；PID/单实例三重闸 `cli/ProcessManager.scala:10,26-28,201-246`、`cli/SingleInstanceGuard.scala:44,69-123` |
| S11 | **单机更新控制面（在产）** | VersionCheck 单一实现；UpdateOrchestrator 七相位（冻结相位**复用** `drainForUpdate` 同一道闸同一个排空环）；InstallPointer 指针回滚（健康自检失败切回上一版）；设备侧采纳闸＝心跳响应带 `update.target`（跨通道拒采/前向-only，幂等键 `heartbeat:<channel>:<version>`）；CLI `nebflow update`；WS checkUpdate | `core/hotupdate/VersionCheck.scala:13-20,68-78`；`HotRestart.scala:265-308`；`HotRestart.scala:493-525`；`neblink/NeblinkUpdateConvergence.scala:33-59,101`；`cli/SystemCommands.scala:32`；`gateway/WsSystemHandlers.scala:364-401` |
| S12 | **版本回报三通道** | WS serverConfig 帧（前端唯一版本源）；NebLink 心跳 body 带 version/build/channel 上报 link-server；/api/health 载荷含 deviceId/version（热重启 T4 自检同源）。无 GET /api/version REST 端点 | `gateway/WebSocketRoutes.scala:1134-1136,1464-1468`；`neblink/NeblinkClient.scala:514-531`；`gateway/HealthRoutes.scala:37-56`；版本单源 `nebflow/Version.scala:7-21` |
| S13 | **心跳/presence** | 设备→link-server 心跳 30s loop；presence 5s ping/10s 判死 | `gateway/GatewayMain.scala:324-336`；`neblink/NeblinkPresenceService.scala:197-198,601` |
| S14 | **乒乓/合并队列/pending-asks 专项恢复** | pingpong/state.json 按 phase 恢复；MergeQueueBootSweep；pending-asks boot 清算；开机补建 Nebula 主会话 | `core/flow/PingpongStateFile.scala:72-73`＋`agent/AgentActor.scala:980-985`；`GatewayMain.scala:861-879,729-742,482` |

**C2/DeviceActor 现状**：纯设计稿、零代码。`reports/20261022_204509_actor-scheduler-addendum__delegate-nebflow-0c7caaa9/ADDITION.md` §7 的 DeviceActor（信箱只载控制消息）/租约台账（作业体只住 queue.jsonl、「审计即状态」、TTL 180s＝3×30s 心跳）**未并入** SPEC（本 worktree 内亲验：SPEC 无 §7、`nebflow/compute/` 包不存在）。已产设备面是另一套：`core/device/DeviceRegistry.scala:28-45`（HMAC 配对表）＋DeviceDelegate＋NeblinkEnrollment——C2 设计稿裁定直接复用，不新建。

### 2.3 差距清单（离「无损」差哪几块）

| # | 差距 | 证据 |
|---|---|---|
| G1 | **turn 边界快照的写面死了**：`TurnStateStore.save` 零调用点，`FlowTreeActor` 的 ResumeTurn 恢复腿（`:652-684`）实际不可达（除非盘上留有历史 turn-state.json）；`AgentState.scala:550-553` 注释仍声称「Persisted to TurnStateStore」——注释与代码不符 | `core/flow/TurnStateStore.scala:69`；`core/flow/FlowTreeActor.scala:356,663-677` |
| G2 | **checkpoint 粒度＝ToolsComplete**：当前工具批、流式尾巴、节点本轮 result text 必丢；节点认领腿不写 result（`ShutdownState.scala:150-151`）——档 B 语义下这是接受项，但要在档 A 里靠「等收束」消掉 | `agent/AgentProcessing.scala:334`；`core/project/NodeRecovery.scala:341-347` |
| G3 | **delegate 父子链重建不全**：depth 不在盘上、复活恒 depth=1；rootSessionId 以 parent 估计（registry 不存根 id）；复活时 parentRef 恒 None（registry 重启为空），终态走 F2 兜底（结果延迟可达但不丢） | `agent/DelegateRevival.scala:153-157,282-285`；兜底 `agent/BackoffSupervisor.scala:814-849` |
| G4 | **bg 任务零持久**：任务行不落盘→复活后的 delegate 的 bg-wait hold 不再成立（等待集空了会直接放行）；在跑 bg 任务静默蒸发，所属会话无死亡通知 | `core/tools/BgTaskRegistry.scala:346-350`；对照：bgWait 在节点认领时清空＋resume prompt 死亡告知 `NodeRecovery.scala:341-347` |
| G5 | **SIGTERM 链缺会话 flush**：热重启有 [3] flush，SIGTERM hook 链没有——纯重启（CLI restart）丢 debounce 尾巴 | `Main.scala:331-377` 对照 `HotRestart.scala:330-335` |
| G6 | **「泊车」与「crash 孤儿」不分账**：复活的 nudge 是 crash 语境（DelegateRevival.scala:232），且泊车复活会消耗 revivalCount、落「重启打断」指纹——计划内重启不该算损失 | `agent/DelegateRevival.scala:230-233`；指纹 `agent/SubAgentStartupRecovery.scala:90` |
| G7 | **复活是「delegate 轨」专属**：普通根会话 boot 不主动拉起（懒加载，WS connect/首消息才 spawn）——根 agent 正跑到一半的 turn，重启后要等人打开面板才续 | `gateway/WebSocketRoutes.scala:1174-1178,777-795` |
| G8 | **dag 流无 boot 恢复**（D5）；**恢复账本内存态**（D6） | `RunningFlowRegistry.scala:42-56`；`TaskStuckWatcher.scala:570-594` |
| G9 | **长任务阻死更新**：WaitIdle 模式下在飞 delegate 没完就永远到不了 quiesce，超时只能放弃重启——「更新」与「无损」当前不可兼得，本设计的核心命题 | `HotRestart.scala:223-256,413-438` |
| G10 | **台账单机假设**：tasks-v2/chain-ledger 无跨进程锁——更新编排必须保证一设备一实例，换血期旧新进程不得并存写账（现有 C1/C2 闸已保证，需在验收里钉死） | `TaskLedgerStore.scala:125-128` |

---

## 3. 核心机制草案：drain → 快照 → 换血 → 复活

原则：**能复用的一律复用，新增件最小化**。下表每件标注（复用/新增）。

### 3.1 drain（停止派新活，等在飞到边界）

- 复用：draining 准入闸（`WebSocketRoutes.scala:2098-2108` 单一 choke 点）、quiesceReport 五域（`HotRestart.scala:104-132`）、drainLoop deadline＋15s 观察窗（`:413-438`）、`drainForUpdate` 冻结入口（`:282-308`）。
- **新增：边界排空模式（boundary-drain）**。今天 drainLoop 等的是「全空闲」（等活干完）；boundary-drain 等的是「逐域到边界」：
  - F4 在飞 LLM → 等计数归零（＝每个 turn 的 LLM 调用收束；**这是档 A 的物理边界**）；
  - F3 Processing 会话 → LLM 收束后该 turn 走完既有落盘点（ToolsComplete 已落，turn 终态落盘）→ 写**泊车标记**（见 3.2①）→ 视为已到边界，不强等会话后续轮；
  - F1 running 节点 → 复用既有 ShutdownState CAS→Interrupted 语义（本就是「边界态」，boot 认领续跑，`ShutdownState.scala:153-177`）；
  - F2 delegate → 与 F3/F4 同覆盖（delegate 收束即到边界，不要求整任务完成——这正是与今天 WaitIdle 的本质区别）；
  - F5 等待型 bg 任务 → Phase 1 的持久行/通知腿接住（3.2③）。
  - deadline（默认 120s）到点 → 未到边界者降档 B 处置（abort 在飞＋既有 crash 孤儿链），换血继续。**排空模式是 RestartMode 的第三个值**（`RejectIfBusy`/`WaitIdle` 之外加 `BoundaryDrain(deadlineMs)`），零改既有语义。

### 3.2 快照（新增件集中在这一相，且都是小块）

| ① | **turn 泊车标记**：重接 `TurnStateStore.save` 写面（turn 开始写、ToolsComplete/终态清——先考古 G1 是被移除还是回归）＋新增 `parked=true` 字段。档 A 泊车的会话、delegate 都以此为准据分账（泊车≠重启打断） | 新增（小） |
|---|---|---|
| ② | **DelegateRegistry.Record 补 `depth`/`rootSessionId` 两字段**：消掉 G3 的估计项，嵌套链复活 root 归属正确 | 新增（两字段） |
| ③ | **bg 任务行小文件** `<dataRoot>/bg-tasks.json`：注册时写一行（jobId/sessionId/description/startedAt），完成/取消时终态化。boot 时对残留 running 行向所属会话 F2 队列投「bg 任务死于重启」诚实通知＋行终态化（BgInboxBackstop 同款幂等）——bg-wait hold 复活后据此重新武装 | 新增（小） |
| ④ | 其余全部现成：transcript checkpoint（S1）、F2 注入队（S2）、任务行/地址簿（S7）、tasks-v2（S3）、chain-ledger（S4）、flow-map＋shutdown-marker（S5） | 复用 |
| ⑤ | 明确不持久（接受丢失，标注即可）：恢复账本（D6）、frozen/escalation、agentRegistry 活体表 | 复用现状 |

### 3.3 换血（零新增）

intent 写前账→spawn 后继→C1 2s 存活闸→C2 readyToBind 30s→四档健康自检→SuccessorGate 端口让渡→优雅让渡链，全部照旧（`HotRestart.scala:320-479`、`SuccessorGate.scala:211-236`）。健康自检失败→InstallPointer 回滚（G4 既有）。**档 A/B 的差异不进换血相**——换血相永远等价。

### 3.4 复活（既有 boot 腿族＋一条新腿）

- 复用：ProjectCrashRecovery（S6）、SubAgentStartupRecovery＋DelegateRevival（S8）、MergeQueueBootSweep、PingpongRecover、RecoverPersistedQueues、pending-asks 清算（S14）。
- **新增：泊车拉起腿（planned-revival）**。boot 读泊车标记（3.2①），对 `parked=true` 的会话/delegate：
  - **主动 eager spawn**（打破懒加载 G7）：水合 transcript＋重放持久队（既有）＋ResumeTurn/续跑 nudge，nudge 文案是「计划内重启，继续」；
  - **不算损失**：不落 RestartLossFingerprint、不耗 revivalCount（planned 复活走独立账目）、任务列表不打「重启打断」徽标——消 G6；
  - delegate 轨复用 DelegateRevival 的重建链（registry/座位/插件重挂/监督链），只是资格判定旁路「孤儿」判据、改读泊车标记；
  - 失败降级：拉不起→落既有收殓链（诚实终态＋F2 通知），绝不静默。
- **新增：bg 任务死亡通知腿**（3.2③ 的 boot 半段）＋ dag 流残留响亮告警（G8 本期不恢复，先出声）。

### 3.5 复用/新增总账

| 相 | 复用（现成） | 新增 |
|---|---|---|
| drain | 准入闸、五域判定、排空环、deadline、drainForUpdate | `BoundaryDrain` 模式（F4 收束判据＋逐域推进） |
| 快照 | transcript/F2 队/任务行/registry/tasks-v2/chain-ledger/flow-map | turn 泊车标记（复活写面）、registry 两字段、bg 任务行 |
| 换血 | intent/C1/C2/四档自检/端口让渡/优雅链/指针回滚 | 无 |
| 复活 | 全部既有 boot 腿族 | 泊车拉起腿（eager spawn＋分账）、bg 死亡通知腿、dag 残留告警 |

---

## 4. 与统一更新控制面的接口

### 4.1 通道现状（不新建通道）

官网主页（stand-website）→ link-server → 设备，唯一可信通道＝心跳请求/响应对：设备 30s 心跳上报 version/build/channel（`NeblinkClient.scala:514-531`），响应带 `update.target`，设备侧 NeblinkUpdateConvergence 采纳闸（跨通道拒采/前向-only/幂等，`NeblinkUpdateConvergence.scala:33-59,101`）。设备侧执行＝UpdateOrchestrator 七相位，冻结相位已经复用热重启排空环（`HotRestart.scala:265-308`）。统一更新系统设计稿（`reports/20261020_unified-update-system-design/DESIGN.md` §3 三仓职责/§4 数据流/§6 热更分档/§7 离线补推/§8 健康可见）为本节的基线，本设计只做增量。

### 4.2 设备侧编排接到本设计四相

```
官网主页一键下发（targetVersion + policy）
  → link-server 记账（版本清单 §5 + 离线补推状态机 §7，已有设计）
  → 心跳响应 update.target 携带 policy = { drainMode: boundary|immediate, drainDeadlineMs, wave }
  → 设备采纳闸（既有）→ UpdateOrchestrator：
      冻结   = drainForUpdate(BoundaryDrain(120s))   ← 新模式；到点降档 B
      执行   = 下载/校验（既有七相位）
      换血   = 既有热重启 [4]-[8]（intent/C1/C2/四档自检/让渡）
      复活   = boot 泊车拉起腿（新）
      回报   = 心跳 version/build/channel（已有，零改动）
```

policy 字段进统一更新设计稿 §5 版本清单 schema 的 `update.target` 对象（草案级扩展，字段三件：drainMode/drainDeadlineMs/wave）。

### 4.3 多设备版本一致性（架构裁定在此落地）

- **主页只管意图**：targetVersion＋波次（canary→全量）；**收敛判定在 link-server**：按心跳 version 聚合，「全部在位设备连续 N 拍（建议 3）version==target」⇒ 状态置已收敛；有设备停滞旧版 ⇒ 视图亮 drift。
- **不自动强推**（零超时纪律的延伸）：drift 设备重推 policy（幂等键已有），重推次数到上限⇒主页告警、留给作者处置——引擎侧绝不接受「强杀在跑任务换版本」的指令形态，档 B 也是排空后换。
- **设备视角的诚实回报**：心跳在既有 version/build/channel 之外新增 `updateState`（idle|draining|restarting|reviving|failed+reason）与 `plannedRestart: true` 状态位——presence 5s/10s 判死窗与换血窗（上限 ~90s）冲突，link-server 对 `plannedRestart` 设备挂起判死（避免换血中被误判离线、触发误重推）。

### 4.4 C2 DeviceActor/租约台账：设备换血租约不丢

以 §7 设计稿（ADDITION.md）为准绳，本设计给三条换血铁律（C2 落地时验收）：

1. **先落账再认领**：租约 claim 必须先把 claim 行 append 进 `queue.jsonl`（「审计即状态」）再返回——DeviceActor 内存态只是可重建投影，换血丢内存不丢租约。
2. **复活即重放**：DeviceActor crash/换血后由台账重放重建（设计稿 §7 本就有此语义）；对租约 TTL 补一条**续租优先于过期判死**——复活后的第一拍心跳先续租再参与判死，避免「重启窗 30-90s × TTL 180s」边界上的合法作业被误回收。
3. **判死豁免窗**：设备 `plannedRestart` 期间，租约 TTL 暂停计时（或 TTL 检查豁免该设备），换血完成首拍心跳恢复——作业不因计划内换血被二次派发（幂等作业 id 兜底双保险，SPEC §3.3 已有）。

---

## 5. 分期落地建议（每期验收标准）

### Phase 1 —— 会话/任务零丢失（最痛，纯保命，不碰编排）

范围：① SIGTERM hook 链补 `flushPendingUiWrites/flushPendingMessages`（对齐 HotRestart [3]，G5）；② 考古 `TurnStateStore.save` 写面死因→重接写点（turn 开始写/边界清）＋复活 ResumeTurn 腿（G1）；③ `pendingUserInputs` 持久化（进 CompactionQueueStore 快照第四队列；若作者裁定接受丢失则改为 UI 诚实提示——开放问题 Q1）；④ bg 任务行小文件＋boot 死亡通知腿（G4）；⑤ dag 流残留响亮告警（不恢复）。

验收（二值可判）：
- V1 端到端零丢消息：发消息→1s 内 SIGTERM→boot→该消息在历史中；连跑 10 轮 0 丢失（钉 G5）。
- V2 turn 恢复可达：会话 turn 中 kill -9→boot→盘上 turn-state.json 有效→ResumeTurn 腿触发续跑（钉 G1）。
- V3 排队注入回归：排队 3 条→kill→boot→3 条全投递（S2 回归钉）。
- V4 台账零损：重启前后 tasks-v2/chain-ledger/subagent-tasks/flow-map 内容等价（仅含预期翻态，脚本断言）。
- V5 bg 任务不出声即死：在跑 bg 任务时 kill→boot→所属会话收到死亡通知、行终态化（钉 G4）。
- V6 回归：CrashResumeSpec/InterruptRecoverySpec/DelegateRevivalSpec/FreezeGateSpec 全绿＋本期新增 spec。

### Phase 2 —— delegate 续跑（泊车语义，档 A）

范围：① 泊车标记＋泊车拉起腿（eager spawn＋分账，消 G6/G7）；② DelegateRegistry 补 depth/rootSessionId（消 G3 估计项）；③ `BoundaryDrain` 模式接入 `drainForUpdate` 与热重启（消 G9）；④ 复活 delegate 的 bg-wait hold 重武装（读 3.2③ 行）。

验收：
- W1 档 A 端到端：N 个长 delegate 在跑→热重启→全部原地复活续跑、父会话最终收**续作结果**（非失败通知）、无一行翻 failed。
- W2 嵌套链：3 层嵌套 delegate 中途 kill→boot→子孙复活且 rootSessionId 归属正确（非 parent 估计）。
- W3 长任务不阻更新：≥1h delegate 在跑→update 下发→BoundaryDrain 120s 到点自动降档 B→换血完成→delegate 走 crash 孤儿链复活续跑（全程无需人工）。
- W4 泊车不计损：planned 复活不落 RestartLossFingerprint、不耗 revivalCount、任务列表无「重启打断」徽标。
- W5 洪峰护栏：bootCap/concurrency 既有预算对 planned 复活同样生效（防 LLM 洪峰）。

### Phase 3 —— 全网统一更新编排

范围：① `update.target` 携 policy（drainMode/drainDeadlineMs/wave，schema 扩展）；② 官网主页波次编排＋收敛视图（website/link-server 侧，本仓出接口契约）；③ 心跳 `updateState`/`plannedRestart` 状态位＋link-server 判死豁免；④ C2 落地时 §4.4 三条换血铁律验收。

验收：
- X1 一键下发→金丝雀设备 drain→换血→心跳回报新版本→主页收敛视图可见（全自动无人值守）。
- X2 波次中止：中止命令→未开始设备不动、进行中设备走完本机安全点（换血不可中断但可中止后续波次）、状态如实上报。
- X3 版本一致性：全部在位设备连续 3 拍 version==target⇒「已收敛」；一台卡旧版⇒drift 亮灯＋重推有上限＋不自动强推。
- X4 离线设备：离线补推状态机（统一更新设计稿 §7）回归绿；离线期间主页视图如实。
- X5（若 C2 已落地）租约换血：设备换血中租约不丢、复活后续租、无作业双跑（幂等 id 钉）；误判死豁免窗生效（换血中不被回收）。

---

## 6. 风险与开放问题

| # | 风险/问题 | 处置 |
|---|---|---|
| R1 | 在飞 LLM 调用不可跨进程（物理约束） | 档 A 也只承诺「收束点之后零丢失」；文档与 UI 明示 |
| R2 | BoundaryDrain 等待 vs deadline 张力：长 LLM 调用（>120s）到点必被降档 | deadline 可配；降档动作在主页/面板声明；档 B 链路本身无损台账 |
| R3 | 档 B 尾巴重做的工具副作用重复（外部 API 非幂等） | resume prompt 已带「未落盘副作用不可信」告知（`NodeRecovery.scala:894-902`）；长期看工具副作用幂等是工具域课题，不进本期 |
| R4 | `TurnStateStore.save` 死因未考古（移除还是回归） | Phase 1 第一件事：git 考古确认意图，再决定重接还是重造 |
| R5 | pendingUserInputs 持久化改变「刷新即清」现行为（Q1） | 需作者拍板：持久化（推荐）或诚实降级 |
| R6 | 一设备多实例同写 dataRoot（台账无跨进程锁，`TaskLedgerStore.scala:125-128`） | 更新编排串行保证＋C1/C2 闸已有；验收加「换血窗内零双写」断言 |
| R7 | 泊车拉起的 LLM 洪峰（大量 parked 会话同时续跑） | 复用 revival 慢段信号量＋bootCap 预算同构（W5） |
| R8 | C2 DeviceActor 未落地，§4.4 暂无法验收 | X5 标注「若 C2 已落地」；三铁律写进 C2 实施批次的设计输入 |

---

## 7. 一页结论

- **家底比想象厚**：热重启编排（五域判定/准入闸/换血四闸/四档自检/指针回滚）、boot 恢复腿族（节点认领续跑、delegate 同 id 复活、F2 持久注入队、任务台账三套全持久）、单机更新控制面（七相位/采纳闸/版本回报）都已再产——**本设计 80% 是组装，不是新造**。
- **真正的新增件只有四小块**：turn 泊车标记（重接死写面）、registry 两字段、bg 任务行、泊车拉起腿；外加一个排空模式（BoundaryDrain）。
- **最痛的一刀**：G9「长任务阻死更新」——今天 WaitIdle 等活干完才准换血， endless；BoundaryDrain 把「等干完」改成「等到边界」，这是档 A 语义的支点。
- **两档语义**：档 A 原地续跑（更新默认，deadline 降档）、档 B 控制面零丢失（兜底与手工快重启）；两者共用同一套换血与复活机器，差别只在 drain 的边界判据与复活分账。
