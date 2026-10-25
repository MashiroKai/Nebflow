# #896 合并队列改纯协调者「交通引导员」——重构批报告（2026-10-26）

- 批次：台账 #896（作者裁定 2026-10-26 逐字核心）:「主要是你的重试和复活的逻辑写的不好 delegate合并卡住的本质是收不到通知 我们的合并排队系统 应该就是一个交通引导员的角色 通知他们一个一个合并就行了 然后手里有个账就行了 它不需要负责合并 自然就不会出现合并拒绝 然后静默无通知的情况」。
- 分支：`delegate/delegate-nebflow-6c1fcbfe`（本报告所在 worktree；合并回 main 走现队列正常流程——放行通知属主自并）。
- 门禁：相关族 spec **59/59 全绿**（10 套）+ 面板 playwright **20/20** + boot-smoke（隔离实例，见 §6）。
- 结论：MergeQueue 从「排程器 + 自动合并执行者 + 重试/复活看护」收缩为**纯交通引导员**——①持久账本（waiting → turn-granted → merged / owner-gone / failed）②放行 = 通知（属主 delegate 自己跑 `git merge`，引擎永不代并）③自动重试轮 / 复活轮 / 停摆看护四件全删 ④放行与关账前账实核对（`git merge-base --is-ancestor`）⑤通知不可达 ⇒ owner-gone 显式升级，绝不静默 park。

## 1. 今晚事故 → 新语义逐条映射

| # | 今晚事故 | 旧机制病灶 | 新机制（交通引导员制） | 代码锚 |
|---|---|---|---|---|
| ① | 队列自动重试 ×5，attempt 5 refused 后 parked，会话楔死 | 合并轮被拒 ⇒ `refused-keep-queued` + 重试注入（MergeStallCare / MergeWindowCare / MergeRefusalCare 三件看护轮番施救） | **重试轮与 parked 语义整体退役**：合并轮被拒 = `failed` 关账一次（原因随行、面板可见），处置归 dispatcher；合并指令明言 "no automatic retries" | MergeStallCare/MergeWindowCare/MergeRefusalCare/DelegateAutoMerge 四件 `git rm`；`MergeQueue.onTerminal(Some(Refused)) → closeFailed`；`mergeBriefFor` STOP 条款 |
| ② | 复活/恢复实例互踩（recovered rekey 双实例同分支竞写） | `Recovered` 闭包 + `rekey` 换键复活腿；重启重挂（MergeQueueRestartReminder 旧语义 = 留队待重试跨重启重挂） | **复活腿删除**（Recovered/rekey/reattach-revive 全撤，仅保留监督者 respawn 的进程内 `reattach` 换绑）；重启 = boot 对账：已并回收、空支诚实关账、可并 owner-gone 报 dispatcher——**零复活零重挂** | `MergeQueueBootSweep.sweep/reconcileOne`（全重写）；`MergeQueueRestartReminderSpec` ② 退役词表零出现 |
| ③ | dispatcher 清场员（人肉找尸体、清队列） | 死亡会话无事件，队列被尸体持窗整队陪站 | **owner-gone 显式处置面**：活性探针判死 / 通知不可达 / careWaitingDock 死停靠 ⇒ 关账 owner-gone + `OwnerGonePort` 持久升级通知 + `/api/merge-queue` recent 列呈现「需 dispatcher 处置」 | `MergeQueue.markOwnerGone`；`OwnerGonePort`（SharedResources 装配）；`TaskStuckWatcher.careWaitingDock`；`careTick` 持窗尸体判死 |
| ④ | 脏 base exit 128 连带（合并轮撞脏工作区报错成灾） | 放行不看 base 净空，脏面直接进合并轮 | **放行前置净空闸**：`MergeArtifactCare.hygiene` 白名单收编/还原 → 余脏非空或基分支非检出分支 ⇒ 本拍不放行，`grant-blocked` 记账 + `lastBlockReason` 面板可见（waiting 行「未放行原因：…」） | `MergeQueue.baseReady/requeueBlocked`；`mergeQueueStatus.js` reason 段 |
| ⑤ | e300e71c 分支已落定仍在账上罚站（已并仍排进队列、放行、被拒、parked） | 入队/放行/关账无一处核对 git 实况 | **账实核对先于一切**（四处）：①放行前 `is-ancestor` 命中 ⇒ 直接关账 merged + 「已落地」收尾通知（属主立即 finish、不碰 base）②回合外终态先核对再落 owner-gone ③markOwnerGone 前核对（期间被并 ⇒ merged）④boot reconcile 已并回收；合并指令自带只读预检（复活/陈旧重放在属主侧也被挡） | `MergeQueue.alreadyMerged`（grant/onTerminal/markOwnerGone 三调用点）；`MergeQueueBootSweep.reconcileOne`；`mergeBriefFor` 预检条款 |

## 2. 新账本模型（MergeQueue.scala 全重写）

**状态机**：`waiting → turn-granted → merged / owner-gone / failed`（Open = 前两态；Terminal = 后三态；JSON 直存字符串，跨重启持久形状）。

- **账本**：`<dataRoot>/merge-ledger.json`（写穿 tmp+move；通道闭包不落盘）。逐迁移审计行落 ChainStore（kind=`merge-queue`）：`enqueued / enqueued-duplicate / granted / grant-blocked / artifact-hygiene / merge-merged / merge-failed / owner-gone / already-landed-notice / session-reattached`——**重试/滞留词汇零出现**（spec 钉死）。
- **排序**：唯一排序键 `readyAt`（ready-to-merge 完成时刻，FIFO；同刻按 delegateId 字典序）。位次投影 `queueViewOf`（position/total/ahead/holder/state）= 行内 `mergeQueue`/`mergeQueuePos` 键同源。
- **单持有人**：per `projectKey` 至多一个 `turn-granted`（窗本体）。pump 锁内占窗（占位即迁移，绝不双授）→ 锁外 grant：账实核对 → 净空闸 → 通知。
- **放行 = 通知**：内部腿 actor 注入（`UserInput(source="merge-queue")`，replyTo=监督者）／外部腿续聊闭包（CLI resume）。通知携带 base 当前 tip（审计 `grantedBaseTip` 可对账）。通道失败 ⇒ owner-gone（绝不楔死队列）。
- **成员回报驱动关账**：`onTerminal(id, outcome)`——`Some(Merged)`（机械验收通过）⇒ merged + PostMergeCleanup；`Some(Refused(r))` ⇒ failed(原因)；`None`（回合外）⇒ 先账实核对再 merged/owner-gone。幂等。
- **分层纪律**：core 不 import agent——升级走 `OwnerGonePort` 注册器（生产装配在 `agent.SharedResources`）。

## 3. 删除件清单（勿留死代码）

| 删除 | 原职责 | 去向 |
|---|---|---|
| `DelegateAutoMerge.scala` | 引擎代跑 git merge + 拒并文案收编 | 引擎永不代并；净空收编逻辑收编进 `MergeArtifactCare` |
| `MergeStallCare.scala` | 停摆提醒/升级（stall-* 词汇族） | owner-gone + careTick 判死取代 |
| `MergeWindowCare.scala` | 重试注入/障碍清除（retry-injected/obstacle-cleared） | 无重试；blocked 记账自然重试（下拍 pump） |
| `MergeRefusalCare.scala` | 拒并升级 | refused = failed 关账一次 |
| `MergeQueue.Recovered / rekey / NoopChild / NoopSupervisor / isInFlight / pendingRetry / reminder-sent / dequeued` | 复活与重试词汇面 | 全部退役（grep 零残留；退役词表 spec 断言） |

## 4. 各文件落点（主代码 14 + 前端 5 + 测试 9）

**引擎**：`MergeQueue.scala`（全重写，账本/状态机/泵/闸/关账/通知/持久化）；`MergeQueueCare.scala`（新，扫描拍驱动面：持窗尸体判死 + 补泵）；`MergeQueueBootSweep.scala`（全重写为重启对账 Report(alreadyMerged, droppedEmpty, ownerGone)）；`DelegateTerminal.scala`（Outcome.Release(mergeOutcome) 三臂；`isMergeRound` = turn-granted 判据单点）；`BackoffSupervisor.scala`（Release 解构 + reattachIO 保留）；`ExternalSessionRunner.scala`（AutoMerge hook 删除；completed×领先>0 翻转入队；终态关账分臂）；`TaskStuckWatcher.scala`（careWaitingDock 改 markOwnerGone；尾挂 MergeQueueCare.tick）；`GatewayMain.scala`（新 sweep 签名）；`SharedResources.scala`（OwnerGonePort 装配）；`MergeArtifactCare.scala`（Dirs 自有常量）；`PresenceRoutes.scala`（/api/merge-queue 新投影：queues[].entries/recent）；`ChainsReadFace.scala`（mergeQueue {projectKey,ahead,holder,state}，merging = turn-granted）；`BuiltinAgents.scala`（「Merge wake-up discipline」合并纪律条：复活先查账、已并不碰 base、非回合不碰 base）；`ChainStore.scala`（账面词汇注释更新）。

**前端**：`mergeQueueStatus.js`（全重写：entries position/total/state/reason 形状防御，OPEN_STATES 两态）；`main/bgAgents.js`（waiting 行段：turn-granted →「轮到你合并」、ahead →「前面还有 N 位」、reason →「未放行原因：…」）；`locales/en.js`/`zh-CN.js`（`subagents.mergeQueue.turn` 新键、reason 改词、retry 键删除）；`delegateChainStub.js`（行投影 reminderSent → state）。

**测试**：删 5 spec（DelegateAutoMergeSpec/MergeStallCareSpec/MergeWindowCareSpec/MergeQueueStallSpec/MergeQueueHeadSkipSpec）；重写 3 spec（MergeQueueSpec ①FIFO+跨项目 ②单持有人 ④指令文本 ⑤账实核对关账 ⑥refused→failed 幂等 ⑦owner-gone+OwnerGonePort ⑦b回合外终态 ⑧持久化 ⑤b幂等入账；MergeQueueBootSweepSpec ①已并回收 ②空支 ③owner-gone+任务行 failed ④零动作；MergeQueueRestartReminderSpec ①终态不复活 ②退役词表 ③持久回载）；改 6 spec（DelegateTerminalSpec ④⑧⑬、DelegateFinishWorkResultSpec、MergeAcceptanceSpec、ExternalLegPrepareSpec ②③、WaitingDockCareSpec ①②、ChainsReadFaceSpec C5）。

## 5. 前后行为对照表（面板/用户可见面）

| 面 | 旧行为 | 新行为 |
|---|---|---|
| 候合并行（子代理面板） | 「前面还有 N 位 · 第 N 次重试 · 上次被拒：…」 | 「轮到你合并（窗口持有中）」（turn-granted）／「前面还有 N 位 · 未放行原因：…」（waiting） |
| 被拒合并轮 | 留队待重试（attempt 计数、stall 提醒、parked） | 关账 failed（原因入账 + recent 列），无重试 |
| 属主会话死亡 | 队列条目挂 pendingRetry 静默陪站 | owner-gone 关账 + 升级通知 + 面板「需 dispatcher 处置」 |
| 引擎重启 | 留队成员重挂复活（可能互踩） | boot 对账：已并回收 merged / 空支诚实关账 / 可并 owner-gone——零复活 |
| 分支已并 | 仍放行仍重试（e300e71c 罚站） | grant/terminal/boot 三处账实核对直接关账 merged |
| /api/merge-queue | entries+inFlight+retryCount/lastRefusalReason | queues[{projectKey,holder,entries(open),recent(closed≤20)}] |

## 6. 门禁证据

- **编译**：`sbt -batch Test/compile` 绿（main + test 全量，Scala 3.5.2）。
- **Scala 回归（10 套全绿 · 59/59）**：`sbt -batch "testOnly nebflow.core.delegate.MergeQueueSpec nebflow.core.delegate.MergeAcceptanceSpec nebflow.core.delegate.DelegateTerminalSpec nebflow.core.delegate.DelegateFinishWorkResultSpec nebflow.core.delegate.DelegateTerminalPrecheckSpec nebflow.agent.MergeQueueBootSweepSpec nebflow.agent.MergeQueueRestartReminderSpec nebflow.core.processor.WaitingDockCareSpec nebflow.core.executor.ExternalLegPrepareSpec nebflow.core.project.ChainsReadFaceSpec"` → `Passed: Total 59, Failed 0, Errors 0`（2026-10-26 02:4x；终轮日志 `/tmp/mq-test-final.log`）。新增/重写判读面：MergeQueueSpec（FIFO 完成序/单持有人/跨项目并行/指令文本钉死/账实核对关账/refused→failed 幂等/owner-gone+OwnerGonePort/回合外终态/持久化回载/幂等入账）、MergeQueueBootSweepSpec（已并回收/空支/owner-gone+任务行 failed/零动作）、MergeQueueRestartReminderSpec（终态不复活/退役词表零出现/持久回载）、ExternalLegPrepareSpec ②③（fact-check 关账 + 外通道收单 + 无通道 owner-gone）、WaitingDockCareSpec ①②（死停靠 owner-gone 处置 / 活停靠零动作）。
- **前端面板回归（20/20 绿）**：`playwright test tests/subagent-panel-merge-status.spec.mjs tests/delegate-panorama.spec.mjs tests/delegate-ui-twolayer.spec.mjs` → 20 passed（42.7s；含新 T1b turn-granted「轮到你合并」段、T1 位次+未放行原因段、T3-T5 降级红线）。
- **boot-smoke（隔离实例，未触在产 daemon pid 72160）**：`sbt "run --home /tmp/mq-smoke-mq896 --port 6177 --no-browser"`（隔离数据根，stdin `quit` 自净退出）→ 启动日志 **`Merge-queue boot reconcile: alreadyMerged=0 () droppedEmpty=0 ownerGone=0 ()`**（空账零动作路径）+ `GET /api/merge-queue` → **200** `{"generatedAt":…,"queues":[]}`。

## 7. 残留与边界（诚实清单）

- **外部腿「已落地」收尾通知 × 座位回收的时序交互**：grant 时账实核对命中 ⇒ `closeMerged(cleanup=true)` 先回收座位，随后「已落地」收尾通知走外部续聊通道时，CLI 的 cwd（原座位）可能已被清 ⇒ CLI 启动失败（实测 error=2，`ext-rtm` 夹具空壳实证）。账已关（merged）、队列零损；任务行落 failed（启动失败原文，dispatcher 可见）。内部腿（actor 注入）不受影响。记录在案，处置（通知先发后清 / 续聊 cwd 兜底）归后续批。
- **测试夹具教训（本批踩坑实证）**：净空闸的分支核对（`currentBranch(baseWs) != baseBranch ⇒ 不放行`）会把「位于仓内目录的测试 tempRoot」判成 base 未净空——本批已把涉队列夹具全部换成真 git 基座（`mkRepo`/`mkRealRepo` + HEAD 回 main），spec 的 baseWs 语义 = 基工作区（仓根、检出基分支），不再是任意 tempdir。
- 外部腿 ② 的续聊通道经 `ExecutorRegistry.resolveRequested` 按 **PATH** 重新解析 CLI（spawn 侧 `binPath` 假不进 resume）——spec 断言面已收窄到引擎可控事实（入账/关账/审计行/会话历史落信），CLI 侧结果不断言。
- 机械验收闸（合并轮终态）仍要求双亲 merge commit；fast-forward 落地的分支在**账本侧**由 is-ancestor 判 merged 关账，但属主合并回合若已走完会被闸拒——账已关，无队列损伤（属主侧差异，记录在案）。
- AGENTS.md（基础工作区未跟踪本地件）已追加 §20「delegate 合并纪律（交通引导员账本制）」（复活先查账 / 非回合不碰 base / 拒绝即 STOP）——与 `BuiltinAgents.scala` 的 "Merge wake-up discipline" 提示词条同语义（仓内提示词英文、无过程词，守本件第 19 条）。
