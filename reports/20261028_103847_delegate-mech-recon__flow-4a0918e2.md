# delegate 机制只读侦查 —— core/delegate 全件职责 + 崩溃恢复/复活 + cancel 语义 + 台账与 worktree 座位

代码根:`/Users/kaiyu/Claude code/Stand/src/main/scala/nebflow/`(下文相对路径均在此根下;引擎数据根 = `~/.stand`,即 `/Users/kaiyu/.stand`)。侦查时点 main = `c4cf7388c`,工作树仅 perception 前端两文件改动,delegate 机制面零未提交改动。

---

## 一、全景:一个 delegate 的生命周期(数据流)

```
Nebula 调 Delegate(task, project?)
  └─ DelegateTool.spawnBackground
       ├─ id 铸造 = delegate-<agentName>-<8hex>            core/tools/DelegateTool.scala:683
       ├─ DelegateWorkspace.prepare:判定机(会否改文件)→ 一律建分支 delegate/<id> + worktree 座位
       ├─ DelegateRegistry.put:持久地址簿(~/.stand/delegates/<id>.json)
       ├─ ChainStore spawn 行(delegate-spawn)             core/delegate/ChainStore.scala:64
       ├─ SubAgentTaskStore.recordTask:任务行(running)    ~/.stand/subagent-tasks/<父会话id>.json
       └─ TaskLedger 注记「Delegate dispatched → <id>」    DelegateTool.scala:962-975
执行(内置 agent + BackoffSupervisor 监督 / 外部 CLI / 跨设备 DeviceDelegate)
  ├─ 进程内崩溃:BackoffSupervisor Terminated → 退避 respawn(断点续跑,maxRestarts=2/5min)
  ├─ 引擎重启:boot sweep(SubAgentStartupRecovery)→ 先判复活资格(DelegateRevival 同 id 复活)→ 不合格才收殓
  └─ 运行期:watchdog(在飞行 ≥10min 无活体 actor ⇒ 强推 failed)
终态(Completed/Failed/Cancelled)
  └─ BackoffSupervisor.notifyParentAndStop(唯一写点)
       ├─ DelegateTerminal.onCompleted:判定机(漂移闸→预检→judge→自申报)→ Release / HoldForMerge(入 MergeQueue)
       ├─ 终态三清+五件:台账结算 note / flow-host 卸载 / 插件回收 / MergeQueue 关账 / DelegateRegistry.archive
       ├─ 交付回执 DelegateDeliveryReceipts(ack/重投)
       └─ 任务行终态化 + 父通知(直投 / F2 持久兜底 / 跨设备 outbox)
```

三层状态各司其职(无双头状态机,纪律原文 `core/delegate/DelegateRegistry.scala:24-26`):
- **agentRegistry**(内存):活体与否;kill -9 后自动视为不 live;
- **SubAgentTaskStore**(任务行,per-parent 文件):执行状态机(running/restarting/ready-to-merge/completed/failed/cancelled);
- **DelegateRegistry**(持久地址簿):「地址 → 元数据」,跨重启存活,**状态不落本表**。

续聊地址:`delegate:<id>` 是唯一形态(回执措辞单点 `DelegateRegistry.scala:175-176`;AgentControl list 的地址列 `core/tools/AgentControlTool.scala:555`)。`kernel:<id>` 是**同 id 空间的旧形态别名**(`core/tools/MailTool.scala:1198、1228`;退役声明 `core/entity/BuiltinAgents.scala:218`、`agent/AgentCore.scala:394-407`——起实例腿已退役,续聊腿保留且提示新形态)。

---

## 二、core/delegate/ 十二件:职责一句话 + 关键锚点

(目录 `/Users/kaiyu/Claude code/Stand/src/main/scala/nebflow/core/delegate/`)

1. **DelegateRegistry.scala(398 行)**——delegate 的持久地址簿:`<dataRoot>/delegates/<id>.json` 一实例一文件(:140-142),Record 23 字段(:42-138,含 cwd/worktree/branch/baseBranch/projectWorkspace/taskId/parentSessionId/report* 四键/workReport/cliSessionId/plugins/orchestrationMode/worktreeJudged/archivedAt/delegatedBy/restartInterruptedAt)。注册 = `put`(:148-150),热读 `find/list`(:153-172);单写族全部读-改-写 + writeLock + 幂等:updateReport(preserve-before-overwrite,旧 detail 留底 workReport,:189-211)、updateWorkResult(first-write-wins,:220-234)、upgradeOrchestrationMode(只上不下,:259-273)、archive/unarchive(:281-317)、markRestartInterrupted(:324-338)。手工 Decoder 容缺键防旧盘静默消失(:347-396)。**零删除纪律**:归档只加时间戳旗,绝不删行(:115-119)。

2. **DelegateTerminal.scala(802 行)**——终态判定钩子(消费点 = BackoffSupervisor 的 Completed)。`onCompleted`(:86-304)判定序:漂移闸 driftPrecheck(:429-449,纯归类 classifyDrift :458-537)→ 确定性预检(:341-349,零工具调用+空烧=注记)→ 判定机(:144-155)→ 自申报回落(:165-176)→ 无可并产物降级(:202-205)→ queueFlip 翻转入队(:211-215)→ 机械验收闸(:220-229)。`onTerminalCleanup`(:593-664)= 任意终态统一清理:engine 判词行(:603-619)→ 台账结算 note(:656-659)→ flow-host 卸载(:624-627)→ 插件 MCP 回收(:631-634)→ MergeQueue 关账(:660)→ 自动归档 archive(:646-652)。`holdForMerge`(:711-800):工作终稿保真 updateWorkResult(:781-790)+ 任务行翻 ready-to-merge(:791-793)+ 入队(:794);无基分支锚拒绝猜 main(:741-762)。

3. **DelegateLedgerReconcile.scala(208 行)**——台账 ↔ delegate 面板两层状态打通,三条腿:腿1 `onLedgerTerminal`(:62-92,台账条目终态→关联面板行归档;在飞不收尸);腿2 `ledgerSettlementNote`(:102-130,终态交付后台账 append 结算 note,复活注记随行);腿3 `reconcileInterruptedRecords`(:135-155)boot 对账「假中断候选」(restartInterruptedAt 旗 ∧ 未归档):台账已终态→归档(:162-168)、台账 open 但分支已并入基分支→按合并事实归档+台账 note(:169-190,mergeEvidence=isAncestor :200-206)、无证据→旗保留(:190-193)。

4. **DelegateJudge.scala(251 行)**——判定机 core 侧:判词枚举(:49-70,Completed/CompletedNote/ReadyToMerge;#566 起 blocked 退役,wire 永不写出 :180-184)+ 判定输入 TerminalInput(:73-101)+ 注册器 DelegateJudgePort(:129-174,install :155,未注册=IO.pure(None) fail-open)+ VerdictWire(:177-202)+ DelegateSessions.Prefix="delegate-"(:246-251)。执行体在 `nebflow/llm/decision/DelegateJudgeMachine`(Jev 优先 + SmallJudge 兜底),注册点 = `agent/SharedResources.scala:316` 邻位。

5. **DelegateNotify.scala(74 行)**——flow 通知改向通道:对任意活会话 turn 边界 ImmediateInput 注入(:53-72,live 判据=agentRegistry 有行;不 live=false 调用方兜底);Source="delegate-notify"(:31);NotifyRule=创建者 delegate 本人(:35-41)。

6. **DelegateWorkspace.scala(245 行)**——spawn 前 git 建权腿:`prepare`(:89-146)判定机裁决(会否改文件 :106-108)+ **判是/判否一律** `GitWorktrees.createWorktree` 建分支 `delegate/<id>`+worktree(:109-135,worktreeJudged/judgeLeg 随绑定记账);建败回落 inPlaceBinding(:148-179,项目内分支 + GIT_INDEX_FILE 申报配方);brief 申报行 workingBranchLine(:187-208,工作/只读/回落三变体);ChainStore spawn 行 payload(:218-243)。

7. **DelegateWorkReport.scala(141 行)**——合并轮 finish 交付的工作结果附页(修「工作结果丢失」三根因,2026-10-25 批):appendixFor(:53-68)= merge status 一行 + workReport 保真段 + REPORT.md 摘要段(changedReportFiles :109-128,取合并提交第一亲 diff 里 basename=REPORT.md);三段全空="" 向后兼容(compose :74-80)。

8. **DeviceDelegate.scala(757 行)**——跨设备 delegate 通道:presence WS 结构化消息 + relay 兜底,kind=delegate-spawn/accept/result(:49-51);spawnCanonical HMAC 域分隔拼装(:71-98,ts±90s+nonce 防重放);sendToDevice(:194);对端用 spawnBackground 单点建本地会话,记 `delegatedBy:<hostDeviceId>`;回投代理 actor resultProxyBehavior(:547)把终态折成 delegate-result 发回 host;host 侧 handleResult(:640)按 spawnId 判重回投父会话+TaskLedger 注记;boot 收殓腿 `enqueueLostResultNotice`(:225-256,形态与真终态件同构)。

9. **DelegateDeliveryReceipts.scala(303 行)**——终态交付回执+重投监测(治「`ref !` 从不失败但父是否注入零确认」的盲区):回执落 `<dataRoot>/delegates/<id>.receipt.json`(:114-116);写=notifyParentAndStop 后 record(挂点 `agent/BackoffSupervisor.scala:863-876`);确认=父会话 AgentIdle/AgentFinishTurn 注入即 ack(:81-82);重投=TaskStuckWatcher 扫描拍 sweep(:195-225),needsRedelivery(:179-180)=未 ack 且超 grace;处置序 Drop/Retain/Redeliver(:282-293),MaxRedeliveries=3(:85),已 ack 超 24h 收殓(:88)。

10. **DeviceResultOutbox.scala(207 行)**——跨设备完成件的持久待发区(台账 #944 丢件腿;病灶实证注记 :13-29:2026-10-26 KAI 重启 6 件静默蒸发):`<dataRoot>/device-outbox/<enqueuedAt>-<id>.json`(:91-98),write-ahead(先落盘再发,发送成功才删);enqueue 同 id 幂等(:104-121);drain 按入队序补投、任一成功即出队(:178-200)= at-least-once,宿主按 spawnId 判重;零 TTL 零墙钟(:44-46)。

11. **WorktreeRescue.scala(82 行)**——worktree 清理前的 reports/evidence 抢救守卫:GuardedDirs=["reports","evidence"](:30),删 worktree 前把主工作区缺失的文件同相对路径复制进去,只补缺失绝不覆盖(:49-80);消费点唯一 = PostMergeCleanup.run 移除分支。背景实证:作者 11:53 报告卡「不存在或被清理」(:12-14)。

12. **ExternalJudgeHook.scala(117 行)**——外部执行器终态判定接线缝:`classify` volatile 单点(:76,缺省恒 None=进程结果直判);defaultClassify(:85-105)把缝接到同一 DelegateJudge 判定机;decide(:108-115)缝失败=弃判走进程结果兜底。镜像三态(:38-41,#566:Blocked→Failed)。

兄弟件(机制强关联,一句话):ChainStore.scala——链账本 appendSync(`<dataRoot>/chain-ledger.json`+journal,`ChainStore.scala:53-64`,keyFor(taskId,delegateId));MergeQueue/MergeAcceptance/PostMergeCleanup/MergeQueueCare/MergeArtifactCare——ready-to-merge 停靠的 FIFO 合并轮(台账 #896 交通引导员制:引擎永不自己跑 merge,放行=提醒属主)。

---

## 三、崩溃恢复与 delegate-revival

### 3.1 进程内:BackoffSupervisor(退避重启)

`agent/BackoffSupervisor.scala`(1041 行)。子 actor Terminated(:545-565)→ 5min 内重启次数 < maxRestarts=2 ⇒ respawnSafely(退避 restartDelayMs=5s×2^n,60s 封顶,纯函数钉在 `src/test/scala/nebflow/agent/BackoffSupervisorRestartDelaySpec.scala:27-40`)→ 用磁盘 transcript 断点续跑。respawnSafely 兜底壳(:574-600):spawn 链中途任何意外→转诚实 failed 终态——「restarting 行已写、无人终态化」(10-21 实测 7.5h 僵尸的源头形态)被显式封死。

### 3.2 跨进程:boot sweep + 复活腿

`agent/SubAgentStartupRecovery.scala`(418 行)。`sweep`(:135-201,GatewayMain boot 装配):扫在飞孤儿(inFlightTasks = running∪restarting,:92-93、:206-209)→ 逐条判复活资格 → 合格者复活(慢段 fork),其余走收殓链 recoverOne(:307-393:捞尸 transcript 部分结果 :316-320 → 父通知(F2 队列 correlationId 幂等 :400-407;跨设备腿改走 DeviceDelegate.enqueueLostResultNotice :357-366,#944 修正)→ 任务行翻 failed + RestartLossFingerprint 指纹 :90、:370-387)→ 收尾 markInterruptedRecords(:217-251,registry 落 restartInterruptedAt 旗)+ DelegateLedgerReconcile.reconcileInterruptedRecords(settle :136-138)。

**「queued for revival」字符串出处 = `agent/SubAgentStartupRecovery.scala:180`**(boot 日志行,列合格孤儿与 skip 原因)。

`agent/DelegateRevival.scala`(311 行)——跨重启复活腿(设计:`docs/design/delegate-supervision-revival-design.zh-CN.md`,资格判据 :63、任务行 terminal 排除 :238):
- 资格 eligibility(:72-96,全满足才复活):kill-switch 开 ∧ source=delegate ∧ registry 记录在(内置执行器、未归档)∧ 座位目录在 ∧ 父会话在 ∧ 任务预算 revivalCount<2 ∧ 年龄 <7d;boot 级 cap=8 按 spawnedAt 新者优先截取(调用方 :170-175)。旋钮:`shared/Defaults.scala:1166-1197`(enabled 缺省 **true**、maxPerTask=2、maxAgeMs=7d、bootCap=8、concurrency=2,sys props 可调)。
- 复活动作 revive(:102-241,镜像 respawn 的跨进程版九步):①任务行先行翻 running+预算记账+清旧错(markRevived,`agent/SubAgentTaskStore.scala:196-221`)→ ②transcript checkpoint → ③父活引用(重启后恒 None→终态走 F2 兜底)→ ④agentDef 确定性重建+插件 best-effort 重挂(:245-280)→ ⑤同 id spawn+registerAgent(:160-186)→ ⑥作用域图宿主重挂(fail-soft)→ ⑦预算通道重挂(墓碑 no-op)→ ⑧ChainStore `delegate-revival` 审计行(:209-227)→ ⑨nudge 注入(checkpoint 续跑/原 prompt 重注 :230-234)。
- reviveAll(:294-309):信号量并发护栏 + fork 并发 + join 收口;spawn 链失败的单条经 salvage 落收殓链,绝不静默悬挂。

### 3.3 运行期:watchdog

`SubAgentStartupRecovery.watchdogSweep`(:257-287):任务行在飞 ∧ agentRegistry 无活体行 ∧ 状态停滞 ≥10min(≈10×最大退避)⇒ 强推诚实 failed + 父通知;周期循环 run(:293-299)由 GatewayMain fiber 启动。registry 有活体行 ⇒ 永不判(有 actor 在就没有僵尸,:255)。

---

## 四、cancel 语义:写了什么、没写什么

### 4.1 入口与路径

- 工具面:`core/tools/AgentControlTool.scala` call 的 "cancel"(:295-312):外部腿(external-* 前缀)直达 `ExternalSessionRunner.cancel` 杀进程树(:297-305);其余 `doCancel`(:308-312)。守卫 withGuardedRecord(:337-436):kind 白名单 CancelableKinds(:101,Delegate/SubTask/Ephemeral)、自杀守卫(:405-406)、root 同桶(:407-418)、Team 子树(:419-433)、杀 Manager 需 confirm+reason。
- 面板面:WS `cancelAgent` → `gateway/WsSessionChatHandlers.scala:538-597`,非外部腿同样调 `AgentControlTool.doCancel`(:589-594)——工具/面板同链路。

### 4.2 doCancel 正路(supervisor 在):写什么

`AgentControlTool.scala:870-902`:发 `AgentEvent.Cancelled` 给监督者(:877,**fire-and-forget tell**)→ `BackoffSupervisor.scala:523-541` Cancelled 分支 → `notifyParentAndStop("cancelled", taskStatusOverride=Some("cancelled"))` → 终态序列(`BackoffSupervisor.scala:938-942`):
1. DelegateTerminal.onTerminalCleanup(:851-856)→ engine 判词行(verdict=cancelled 写真值,`DelegateTerminal.scala:603-619` + `DelegateJudge.scala:229-236`)+ 台账结算 note + flow-host 卸载 + 插件回收 + MergeQueue 关账(owner-gone)+ **DelegateRegistry.archive 归档旗**(:646-652);
2. 父通知 ExternalEvent(cancelled,barrier 正确释放,:888-896;parentRef=None 时 F2 持久兜底 :897-932、:954-973);
3. 交付回执 record(:863-876);
4. **任务行持久终态化**:SubAgentTaskStore.updateStatus(parent, id, "cancelled", completedAt, lastError)(:878-886)——写进 `~/.stand/subagent-tasks/<父会话id>.json`;
5. ask 槽清理 → 预算释放 → registry 行移除(:940,内存)→ child Stop → 监督者自停。

即:**当前代码的正路 cancel 写了「持久任务行 cancelled + 回执 + 链账本判词行 + registry 归档旗」**,远不止清内存。tool 的成功文案也明示这是异步终态(:896-899「Terminal state settles asynchronously」)。

### 4.3 降级路(无 supervisor 记录):写了什么、漏什么

`AgentControlTool.scala:903-944`:自补父通知(:917-925)+ 任务行 cancelled(**仅当 kind≠Ephemeral 且 parentSessionId 非空**,:926-937)+ registry 内存移除(:938)+ child Stop(:939)。**不写** DelegateRegistry.archive/任何持久旗(归档只在 notifyParentAndStop 链上)——靠任务行 cancelled 挡复活,面板行可能滞留。

### 4.4 已知缺陷(28 僵尸事故的机制缝隙)与现状评估

任务书给定背景:2026-10-27 前曾有「取消只清内存/注册表、未写持久终态 → 引擎重启后 28 个已取消 delegate 被复活成僵尸」。按现读代码,僵尸生成的机制缝隙有三处(取消意图的持久性不闭合):

- **缝隙 A(cancel 正路的回执先于事实)**:`doCancel` 对监督者是 fire-and-forget tell(:877)。若监督者 actor 已死/僵死(如异常退出残留的 registry 行),消息被丢弃 ⇒ **零持久写入、零报错**,工具却已回「Cancel sent…task file will be marked cancelled」。无 post-cancel 校验腿。
- **缝隙 B(updateStatus 静默 no-op)**:`agent/SubAgentTaskStore.scala:162-194` updateStatus 按 parentSessionId 文件内查找行,父文件/行不匹配 ⇒ 不命中即不写,无告警。
- **缝隙 C(取消意图无持久墓碑)**:「cancelled」只存在于任务行 status/lastError 文本;DelegateRegistry.Record **没有** cancelled 终态字段(只有 archivedAt/restartInterruptedAt)。复活资格判据(`DelegateRevival.scala:72-96`)**不复查取消指纹**——只要任务行因缝隙 A/B 处于 running/restarting,boot 复活腿就照常同 id 复活一个作者已取消的任务 = 僵尸。

**DelegateLedgerReconcile 不是这个缺口的修复**——它修的是「台账↔面板两层脱节」(面板行不收敛/台账零痕迹/假中断候选),与 cancel 是否落持久终态正交。cancel 的持久终态由 `notifyParentAndStop(taskStatusOverride=Some("cancelled"))`(`BackoffSupervisor.scala:523-541, 878-886`)承担,该机制自 AgentControl 原始批(`04c8205c0 feat(agent-control)`)即存在;真正的缺口在「取消信号是否送达监督者」与「取消意图缺持久墓碑」两处。修复方向(供后续批参考):①doCancel 落 post-cancel 校验/超时补偿;②SubAgentTaskStore.updateStatus 对「零行命中」返回告警;③DelegateRegistry 加 cancelRequestedAt 墓碑单写 + DelegateRevival.eligibility 增一条「有墓碑即不复活(转收殓)」。

---

## 五、delegate 与任务台账

- **派发登记**:delegate 的 taskId 来自 `Delegate(task=…)` 绑定的 TaskLedger 条目(`DelegateTool.scala:962-975` 派发时 append「Delegate dispatched → <id> (executor=…, workspace=…, branch=…)」note)。台账条目本体由 Task 工具(create/complete/close)管理,条目文件 = `~/.stand/tasks-v2.json`(TaskLedger,现读 966 条)。
- **终态回写台账(腿2)**:delegate 任意终态经 `DelegateLedgerReconcile.ledgerSettlementNote`(:102-130)append「delegate <id> 终态 <status>(<title>)—— 结果已回投派发会话[,经 N 次重启复活续作]」(revivalCount>0 时带复活注记,fail-soft 不拖终态三清)。
- **台账→面板(腿1)**:Task 工具 complete/close 成功 → onLedgerTerminal(:62-92)把 taskId 命中且任务行已终态的面板行归档折叠。引擎日志实证:2026-10-26 01:16:25 一次归档 7 行(f12732c8/e983c3b5/23732543/f817af85/7177e775/d65f6359/e38c5dda),10-27 四批多行(dd-* 跨设备行同面)。
- **receipt 落台账的路径**:交付回执(`delegates/<id>.receipt.json`)不直接写台账;台账侧痕迹 = 腿2 结算 note + 链账本行(`chain-ledger.json` 的 terminal-verdict/merge-queue/delegate-spawn/delegate-revival 行族)。receipt 的职责面是「父会话注入确认/重投」(`DelegateDeliveryReceipts`),与台账经 `keyFor(taskId, delegateId)` 的链账本行互补。
- **项目侧任务面板**:`.nebflow/task-board.json`(178 项,TaskBoard 工作单)+ `.nebflow/task-history.jsonl`(1440 行审计;含 10-23 两笔 delegate 全流程审计批的 create 记录)。这两者是派发者视角的工作单,不是 delegate 运行状态机。

## 六、worktree / flow-map 座位与命名

- **id 与座位命名**:spawn 时 `subagentId = s"delegate-$agentName-${uuid.take(8)}"`(`DelegateTool.scala:683`)——agentName=nebflow ⇒ `delegate-nebflow-<8hex>`。worktree 目录候选 = 同名 `delegate-<id>`(`DelegateWorkspace.scala:111-119` dirBase=delegateId,branchFollowsDir=false 分支恒 `delegate/<id>`)。
- **worktree 物理位置**:`<ws>/.nebflow/worktrees/<名字>`(`core/GitWorktrees.scala:86-89` freeName 候选 base、base-2…base-99;:109-131 `git worktree add -b`)。项目仓座位如 `.nebflow/worktrees/delegate-nebflow-c93d27ad`(本侦查会话即坐在其中);挂载项目座位在 `<dataRoot>/projects/<项目>/.nebflow/worktrees/delegate-<id>`(日志实证:revived seat=/Users/kaiyu/.stand/projects/link-server/.nebflow/worktrees/delegate-nebflow-157e2e57、…/视觉工作室/…)。现读项目仓 worktrees 目录 153 项。
- **flow-map 座位**:每个 delegate 的 AgentFlow 链文件挂 `.nebflow/flow-maps/<delegateId>/tasks/、results/`(本链即 `.nebflow/flow-maps/delegate-nebflow-c93d27ad/tasks/delegate-mech.md`);终态时 DelegateTerminal 卸载宿主实例(`DelegateTerminal.scala:620-627`),盘面状态留存。
- **判否腿 = 只读座位**:同样建 worktree,但 brief 行走只读变体(`DelegateWorkspace.scala:197-199`),零领先提交终态降级 completed 不入合并轮;worktreeJudged=false 随记录持久化(`DelegateRegistry.scala:107-113`)。

---

## 七、实证汇编(全部本机现读)

**引擎运行日志**(`/Users/kaiyu/.stand/logs/stand*.log`,留存自 2026-10-21):
- boot 复活行(逐字格式 `[delegate-revival] N eligible orphan(s) queued for revival: …`):10-25 00:40(3)/01:09(3)/23:26(5);10-26 00:53(1,b5646a58→revival #2)/12:23(1,c2e6b6d0);10-27 02:44(1,e25cd0c2;同拍 skip subagent-e6254858: source=subagent)/20:55(2,fcbc90c1+ddbdde82)/22:35(2,同两条 revival #2);10-28 09:37(5,c3678c84/821b3f47/9fd0373c/f990809b/585087f7)。
- boot 收殓行(`… in-flight delegate task(s) survived a process restart`):10-21 09:59(5)、10-22 09:36(2)/10:35(4)/17:12(**15**)、10-23 08:45(9)/11:51(2)、10-24 22:07(2)/22:16(6)、10-25 01:15(3)、10-27 02:44(1)/22:37(2)——11 次累计 51 件,单次最高 15。
- 复活后二次重启的收殓闭环实证:10-27 22:35 复活 fcbc90c1/ddbdde82 → 22:37:05 引擎再启,两条被收殓 terminalized(parent=d7c3cb54 exists=true partialResult=false)+「restart-interruption marked on 1 delegate record(s)」+「boot reconcile: 1 restart-interrupted row(s) to adjudicate」。
- watchdog 实证:10-24 18:39 `[restarting-watchdog] 1 orphaned in-flight task row(s) stalled ≥600000ms`。
- ledger-reconcile 运行实证:10-26 01:16、10-27 05:38/10:54/17:48/23:21 多批「archived (ledger entry 'N' terminal)」。

**任务行盘面**(`/Users/kaiyu/.stand/subagent-tasks/`,61 文件 992 行;delegate 810/subagent 154/subtask 28;状态 completed 824/failed 88/**cancelled 73**/running 6/ready-to-merge 1):
- **「已取消 × 被复活过」指纹 2 件**:delegate-nebflow-e25cd0c2(status=cancelled,revivalCount=1,lastError=「…cancelled by Nebula via Ag…」)、delegate-nebflow-b5646a58(status=cancelled,revivalCount=2,lastError=「superseded by merge-round boot recovery → delegate-nebflow-bd12fd9d」)。方向与「取消后被复活」一致,证明取消意图的持久性确实存在被绕过的实绩。
- **「28」直证未复得**:留存日志无单次 28 件的复活/收殒记录(单次最高 15;日志滚动,更早日志已不可得)。任务书所述 28 件为派发方给定背景,本机现存认知仅能支撑「该类事故存在且反复发生」(盘面指纹+11 次 boot 收殓),精确数字**未在本机留存证据中复现**——诚实登记为未证,不影响机制结论。

**registry 盘面**(`/Users/kaiyu/.stand/delegates/`,638 文件,含 `<id>.json` 地址簿记录 + `<id>.receipt.json` 交付回执两类)。

---

## 八、现状评估

**好的(机制成熟面)**
1. 三层状态分域干净(内存活体/任务行/持久地址簿),DelegateRegistry 零删除+手工 Decoder 容错+幂等单写,旧盘零迁移。
2. 终态单点 notifyParentAndStop 的顺序设计正确(通知→回执→任务行→清理,crash 任意点都不会双通知/丢通知),delegate 清理五件套齐全且全 fail-soft。
3. 复活/收殓一体两面:资格闸(7 条)+boot cap+并发护栏+失败落收殓,「绝不静默悬挂」贯彻到位;#692 restarting 收宽+respawnSafely 兜底壳+运行期 watchdog 三层把「无主行」封到只剩缝隙 A/B。
4. 交付可靠性三件套(回执 ack/重投/收殓、F2 持久兜底、跨设备 outbox write-ahead)把三类历史丢件实证(#944 6/6、52308ee4、parentRef=None 蒸发)逐一定向闭合。
5. 台账↔面板三腿对账 + restartInterruptedAt 旗 + 归档收敛,面板僵尸行有了机械出口(日志见实效)。

**缺陷与风险(按危害排序)**
1. **cancel 正路回执先于事实**(缝隙 A,第四节):fire-and-forget tell + 异步成功文案 + 无校验腿——监督者已死时取消零效果零报错,任务行留 running,成为下次 boot 的复活/收殓对象;若被复活即「已取消任务变僵尸」(盘面指纹 2 件佐证该族存在)。
2. **取消意图无持久墓碑**(缝隙 C):DelegateRegistry 无 cancelled 字段、复活资格不复查取消指纹——取消的持久性完全押在任务行一行上。
3. updateStatus 静默 no-op(缝隙 B):父文件/行不匹配时零告警。
4. 降级 cancel 路不归档 registry 行(仅面板观感问题)。
5. 复活重建的已知登记项:depth 恒 1、root 以 parent 估计、crash/泊车不分账(设计稿 G3/G6,`docs/design/delegate-supervision-revival-design.zh-CN.md` 族;调研件 `reports/20261024_192847_hot-restart-lossless-design__delegate-nebflow-b595b5d9.md` §G3/G6)。
6. 观测面:复活/收殓日志行完善,但「取消动作」在引擎日志无审计行(Team 专属 nebflow.audit),事后无法对账「谁在何时取消了什么」——对本次 28 件事故取证已构成实际障碍。
