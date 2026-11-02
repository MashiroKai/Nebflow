---
type: audit
status: done
related: "[[20261028_102700_delegate机制与数据同步现状]]"
---

# 分发器模式链式执行连环断供 RCA（2026-11-01，kernel 单会话）

> 批令（作者 11-01 16:4x，kernel 不派链）：分发器模式的链式执行今天连环断供，给出根因与修法分档。只分析不修码。证据源：`~/.stand/logs/stand.log`（14:40-17:00）、`~/.stand/chains/*.json`（ChainStore 台账）、`.nebflow/flow-map-events.jsonl`（项目级审计流）、flow-map 盘面、现行 main 源码（commit 8dc02803d 一带）。全部锚点可复核。

## 0. 结论（先读这段）

**四批 dispatcher 模式派发（9b828eae / a3194495 / 94fe78c9 / 1f99803a）全部呈现同一签名，断供不是四个故障，是一个机制闭环的四次实例化：**

1. delegate 被判为 dispatcher 模式 → 写链 → 发「开工宣言」→ **回合结束**（turn-complete）；
2. 会话级 Completed 触发终态判定机，判定机**正确**回答了 "blocked"（could not complete）——它看到了零提交的 git 摘要和在飞链读数；
3. **#566 折叠语义把 blocked 判词折成 `completed + note`**（「blocked 是给 agentflow 用的」退役令的直接副作用）——引擎一切发布面（session-sync / 任务卡 / 父投递状态字段）只剩 `completed`；
4. 终态清理（`DelegateFlowMapLifecycle.flushBeforeUnmount`）**在飞链台账级 cancelled**（`delegate_terminal_closure`），作用域宿主 unmount；
5. 项目分发器会话（属主 = 已死的 delegate）继续 idle 存活（4h 空闲窗），但：桥不再自动回投（R7-b）、链已 cancelled（NodeStarter 不再推进）、`node:`/`Mail` 地址面不可达、`AgentControl cancel` 被所有权守卫拒绝——**结果必达链路四处全断**。

判定机没有被骗；是**终态状态机里「批次未完」这个状态不存在**（#566 退役后），加上**「在飞链」这个信号没有任何一处闸或规则消费它**。修法相应分三档：小修把信号接进判定与权限面（诚实化 + 可管理），结构修把链生命周期从会话解耦并给结果投递加引擎级回执（必达），提示词修压发生率（不除险）。

## 1. 今日实案时间线（全部可复核）

| 时刻 | 事件 | 证据锚 |
|---|---|---|
| 14:45:18 | 9b828eae mode-decision = **dispatcher**（leg=jev, "jev need_dispatcher/fit_dispatcher pair"） | `~/.stand/chains/1284.json` 行1 |
| 14:45:21 | 9b828eae spawn（BackoffSupervisor watching） | stand.log:43192；1284.json 行2 |
| 14:46:26 | 写入 11 节点链 `unified-buttons-impl`（n0-n10），首波自动开跑 | 9b828eae chains 文件 step-plugins 行；flow-map-events `chain-archived` 14:58:37 |
| 14:57:16 | 项目分发器会话 **dispatcher-20b0e8ea** spawn（属主 rootSessionId = 9b828eae） | stand.log:44361；ProjectActor.scala:2237-2257 |
| 15:03:19 | **第二条** 11 节点链 `unified-buttons-impl-v3`（b0-b10）写入 | 1284 chains step-plugins；stand.log:44707 一带 ChainWrite OK |
| 15:03:38 | 9b828eae turn-complete（msgs=23, textLen=501＝开工宣言），terminal-verdict 落行：**verdict=completed, reason="judge: could not complete — substance in the terminal text", source=judge** | stand.log:44708；`~/.stand/chains/1284.json` 行3 |
| 15:03:39 | b 链台账 **cancelled**（reason=delegate_terminal_closure, cancelled=11）＋作用域宿主 unmount | flow-map-events.jsonl；stand.log:44709（unmount 行） |
| 15:04:41 | 94fe78c9（重飞批）mode-decision=dispatcher，15:04:44 spawn | `~/.stand/chains/1287.json` 行1-2 |
| 15:08:24 | a3194495（RCA 批）mode-decision=dispatcher，spawn 15:08:26 | `~/.stand/chains/1289.json` 行1-2 |
| 15:15:43 | a3194495 判词：**同文折叠** completed；7 节点链 `rca-dispatcher-premature-finish` 同秒 cancelled | 1289.json 行3；flow-map-events 15:15:37 |
| 15:19:26 | 94fe78c9 写 9 节点链 `unified-glass-buttons`（recon 首波 node-074a0baf 开跑） | 94fe78c9 flow-map.json |
| 15:19:50 | `Mail(→node:dispatcher-20b0e8ea)` 被拒：**not a chain node**（分发器不在 node 地址面） | stand.log:49887 |
| 15:21:13-14 | 94fe78c9 turn-complete（msgs=21）→ 判词**同文折叠** → `unified-glass-buttons` cancelled（cancelled=9）——**但 recon 节点会话已在跑，盘面零触碰，一直跑到 16:33 提交 f9feaeb71**，下游因链 cancelled 永不推进（「无人派下一波」实例） | stand.log:50136；1287.json 行3；flow-map-events 15:21:14；94fe78c9 flow-map（recon completedAt=1793519596722，下游 pending） |
| 15:25:36 | 1f99803a（迁移批）mode-decision=dispatcher，spawn 15:25:38 | `~/.stand/chains/1291.json` 行1-2 |
| 15:36:25-40 | 写 8 节点链 `ustc-git-migration` → 判词**同文折叠** completed → 链同秒 cancelled（cancelled=8）——工作区未提交产出无人收尾 | 1291.json 行3；flow-map-events 15:36:40；stand.log:51999 |
| 15:38:35-38 | **跨根注入**：DispatchNotify（reason=failed, node=Mac runner预备，属 1f99803a 的链）注入进**别人的**分发器 dispatcher-20b0e8ea | stand.log:52392-52394 |
| 16:39:49 | `AgentControl(cancel dispatcher-20b0e8ea)` 被拒：**belongs to root session 'delegate-nebflow-9b828eae'**（已死 1h36m）——孤儿分发器无人可管 | stand.log:59957；AgentControlTool.scala:430-441 |

共性实锤（作者令三点逐一对应）：编排所有权挂在死会话上 = 时间线 16:39 行；链生命周期连着会话终态 = 15:03:39 / 15:15:37 / 15:21:14 / 15:36:40 四条 `delegate_terminal_closure`；中间态被当终态 = 四条判词行逐字同文的折叠 completed。

## 2. ① delegate 会话终态判定代码路径

**触发面**：delegate 会话的「终态」输入是**回合结束**（`AgentEvent.Completed`＝end_turn 且终文非空），不是「批次完成」。入口：`BackoffSupervisor.completeNormally`（`src/main/scala/nebflow/agent/BackoffSupervisor.scala:163-191`，source=="delegate" 门在 :179，调 `DelegateTerminal.onCompleted` 在 :180-191）。

**判定序**（`src/main/scala/nebflow/core/delegate/DelegateTerminal.scala:97-340`，onCompleted）：

1. 漂移闸（:143-147，只读读数；四态 Penalty/NoteOnly/Warn/Clear，#566 后全为注记形）；
2. 确定性预检（:151，空烧形态：零工具调用 ∧ 空终文 ⇒ CompletedNote）；
3. **判定机**（:152-166）：`DelegateJudgePort.judgeTerminal`（两层＝Jev 优先＋SmallJudge 兜底，`DelegateJudgeMachine.scala:93-107`）；**两层全败 = None → 回落**（fail-open，`DelegateJudgeMachine.scala:99-107` WARN "falling back to self-report/legacy"）；
4. 判词序（DelegateTerminal.scala:173-188）：`driftVerdict.orElse(precheck).orElse(judged).orElse(selfReport).getOrElse(Verdict.Completed)`——**兜底恒 completed**（:188）；
5. ready-to-merge 降级/翻转（:200-226：零领先提交降 completed；completed×有分支×领先>0 翻转入合并队列）；
6. 合并轮机械验收闸（:231-240）——**这是全流程唯一一个确定性「不放行」闸，但它只管合并轮**。

**为什么「开工宣言」能过终态判定**：三态判词的值域是 completed / ready-to-merge / blocked（折注记）——**全部围绕「可合并产物」设计，没有任何一态表达「批次进行中」**。宣言 + 零提交 + 在飞链的组合下：ready-to-merge 不成立（零领先提交），判定机于是正确选了 blocked——然后被 #566 折叠发布为 completed（见 ③）。屏障面盘点：会话级只有后台 Bash 任务的 bg-wait hold（BackoffSupervisor.scala:95-100）；节点级有 CompletionGate 产物闸（`core/project/CompletionGate.scala`）；**「在飞链」是唯一没有对应屏障的资源类**——`inFlightChains` 读数存在（DelegateTerminal.scala:121-137）、进了判定输入（:163 → DelegateJudgeMachine.terminalState :463），但从判词指令到闸到发布面**零消费**。

## 3. ② 链与 delegate 会话的生命周期耦合

**归属登记**：项目分发器会话 spawn 时在 agentRegistry 盖章 `rootSessionId = 派发者会话 id`（`core/project/ProjectActor.scala:2237-2257`，AgentRecord :2243；spawn 日志 :2298）。该归属**一次性、不可转移**：任何其他 root 桶会话对它的 cancel/restart 被拒（`core/tools/AgentControlTool.scala:430-441`：「belongs to root session …」）。父会话死亡不触发任何所有权转移、不触发孤儿收养——dispatcher-20b0e8ea 至今存活即实证（4h 空闲窗，`shared/Defaults.scala:639-641`；任务终态锚 `DispatcherTaskTerminalAnchor` :643-660 未生效/未开）。

**父死链为何继续跑**：链的执行体（节点会话）与作用域宿主是引擎侧实体，不随会话终态即灭；终态清理走 `DelegateTerminal.onTerminalCleanup`（DelegateTerminal.scala:629-720）→ dispatcher 模式命中 `DelegateFlowMapLifecycle.applies`（`core/delegate/DelegateFlowMapLifecycle.scala:59-60`）→ `flushBeforeUnmount`（:87-125）三段收口：出库归档 → **`finalizeAbandonedChains`：非终态成员在场的链台账落 cancelled（:134-192，reason 字符串 :152-153 与今日四条审计行逐字吻合）**，但「盘面节点零触碰」（:81 注释）→ unmount（DelegateTerminal.scala:670-673）→ 10 分钟宽限后删作用域目录（:53, :218-250）。**即：「delegate 终态 ⇒ 在飞链取消」是 R3 批（2026-10-29）的设计决定**（该批源自 [[20261028_171800_审计报告二五机制__delegate-nebflow-a13975e4]] 的 P3/P10 项——「取消链未走归档清扫→账实不一致」的机制封口；本批证明该封口把断供面从「台账谎报」换成了「在飞链被终态连坐」），本批四次断供全部是这条设计路径的忠实执行。94fe78c9 的 recon 节点在链 cancelled 后继续跑完（15:19→16:33）就是「盘面零触碰」+「台账已死」的组合产物：活节点跑完，出边投递因链已取消不再推进，下游 `glass-pkg`/`shots-before` 永远 pending。

**结果回投断在哪一环**（queue / bridge / 邮箱三处逐一）：

- **bridge**：R7-b 批（2026-09-12, D-4）起分发器桥**不再**把 turn 产出投递给 Nebula（ProjectActor.scala:1247-1254：「旧路径 NodeEngine.deliverDispatcherOutputToNebula 同批删净」）——回投 100% 押在链拓扑的 chain-end 节点 out 边（`"to":"Nebula"` root gate）或分发器显式 Mail 上。而分发器提示词明示「you hold no messaging tool … Root return is engine-delivered」（`core/entity/BuiltinAgents.scala:280+`）——回投唯一通道收窄到「链活到 chain-end」这一条。
- **queue/推进**：链 cancelled ⇒ NodeStarter 不再推进（下游 deps 满足也不启动）；DispatchNotify 失败通知腿照发（15:38:35 reason=failed），但**跨根喂进了别人的分发器**（无属主校验），该分发器即使开 turn 也无法推进已取消的链。
- **邮箱/地址面**：分发器不在 `node:` 地址面（15:19:50 被拒：6 个已挂载链作用域都查无此名）；`AgentControl` 被所有权守卫拒（16:39:49）。四条通路：拓扑 out 边（链死）、桥自动回投（已删）、分发器显式 Mail（分发器提示词告知无此工具）、跨会话管理（权限拒）——**全部断**。

## 4. ③ judge 判定依据与「substance in the terminal text」的产生机制

**判定依据**（判定机看到的输入，`DelegateJudgeMachine.terminalState` :445-465）：任务书（≤12000 字）、终文（不可信输入，`<<<RESULT-BEGIN/END>>>` 围栏 + ≤4000 字截断）、git 摘要（唯一权威 git 证据面）、self-report 行、mergeRound 布尔、**inFlightChains 计数（:463）**。判词指令（:467-471）只定义三态选择规则：completed＝纯读且做完 / ready-to-merge＝有分支有领先提交 / blocked＝没做成——**指令里没有一句关于 inFlightChains 的规则**；Jev 判词 criteria 同样只写「nothing left to merge」「deliverable commits」（:169-177）。于是判定机的实际行为 = 拿终文与 git 面做「做完没有」的语义判断——它答 blocked 是**忠于指令**的。

**「substance in the terminal text」的产生机制**：#566 修复批（2026-10-19，作者令「delegate 不应该有 blocked 型状态，直接上报就行了」）把 blocked 从独立判词退役为注记形。折叠点两处（Jev 腿 `verdictFromChoice` DelegateJudgeMachine.scala:203-210；SmallJudge 腿 `parseVerdict` :504-510）：选中 blocked 且未携带 reason ⇒ 生成固定短语 `"judge: could not complete${mergeRound} — substance in the terminal text"`——Jev 的 blocked criteria 还主动引导模型不重复理由（「the terminal text states the blocker」，:172-174），所以 reason 恒空、短语恒同文。该短语随 `Outcome.Release` 的 verdictSuffix 进父投递 payload（`[delegate-judge] note: …`，DelegateTerminal.scala:320-337）并落 ChainStore `terminal-verdict` 行 reason 字段。今日四条判词行逐字同文 = 四次都走的这条折叠路径的机械证据。**折叠语义本身有作者令背书，缺陷在它删掉了「未完成」这个可发布状态而没有补任何替代载体**：note 只活在投递 payload 尾部，session-sync 状态字段、任务卡、面板、delegate 归档旗全部显示 completed。

## 5. ④ 修法分档

### 小修档（终态判定收紧：诚实化 + 可管理，不动生命周期）

| # | 改动 | 锚点 | 回归风险 |
|---|---|---|---|
| S1 | 判词指令补在飞链规则：`inFlightChains > 0 ⇒ 非 completed`（指令 + Jev criteria 各一句） | DelegateJudgeMachine.scala:467-471, :169-177 | 判词分布漂移：更多 blocked 折注记（本就是期望行为）；判定机对「废弃链已放弃」场景可能误 blocked——由注记承载，不阻断 |
| S2 | blocked 折叠注记强制拼入 chains 读数：`… — in-flight chains: N (batch NOT complete)` | DelegateJudgeMachine.scala:203-210, :504-510 | 注记变长；父会话注入行变长（截断面已有，无新增风险） |
| S3 | 会话级在飞链闸（对照合并轮机械验收闸的形态）：chains>0 ∧ mode=dispatcher ∧ 非合并轮 ⇒ 判词强制带「未收束」注记且**禁止 queueFlip 入队/降级 completed 的静默面**。真停靠（保活等链收束）需要链终态回调，归结构修；小修只做「拦住静默谎报」 | DelegateTerminal.scala:173-188, :222-240 | 需精确圈定 dispatcher 模式（kernel 会话作用域图有自有辅助图，误伤会卡正常完成）；chains 计数 fail-soft=0（:137）在宿主已 unmount 时读 0——闸对「先 unmount 后判定」的次序不设防，需把读数挪到清理序列之前或以台账为准 |
| S4 | AgentControl 孤儿例外：rec.rootSessionId 指向已终态/不存在的会话时，同项目 root 桶可 cancel（限 cancel 不含 restart），强制 reason + 审计行 | AgentControlTool.scala:430-441 | 权限面放宽：误伤需靠「root 已死」证据 + 审计兜底；restart 不放开（重挂到谁的问题未解） |
| S5 | DispatchNotify 属主校验：失败通知只喂属主活着的分发器；属主死 ⇒ 丢弃 + 审计（不跨根投递） | ProjectActor.scala:1422-1446（注入命中腿）；DispatchNotify.scala | 「没人处理失败」与「别人误处理失败」之间选前者——与 S4 配套后可改为「投给项目 + 可被任何 root 桶接管」 |

小修档效果：报表诚实（终态带「批次未完」信号）、孤儿可清、失败通知不串批。**链继续跑完 + 结果回投仍不发生**——那是结构修的活。

### 结构修档（链生命周期独立于会话 + 结果必达）

| # | 改动 | 现状基础 | 回归风险 |
|---|---|---|---|
| D1 | 链宿主与 delegate 会话解耦：dispatcher 模式的链登记到**项目作用域**（或随 dispatch slot 归项目），delegate 终态不再触发 `finalizeAbandonedChains`，改为「归属转移 + 继续推进」；该收口只对「无活执行体」的链生效 | DelegateFlowMapLifecycle.scala:59-60, :134-192；DelegateFlowHosts 按 delegate 挂载（需要项目级宿主或宿主重挂） | 僵尸链风险（活执行体自己也死了的链）⇒ 必须配链级看门狗/TTL（TtlTick sweep 已有底座）；scope 目录生命周期判据（grace 删除双闸）随之要改；「链活过会话」改变面板/归档语义面 |
| D2 | 结果必达：链到达 chain-end / 被 cancelled / failed 任一终局，**引擎**（不是分发器 agent）投递结构化 batch-closing 回执给原 requester；requester 死 ⇒ 升级 Nebula root / 任务板注记，绝不静默丢弃 | delivery-receipts 已有 3 次重投 + human-review 保留（stand.log 15:03:19 的 WARN 族就是它）；ChainLedger 已有链级控制态 | 回执风暴（每链一条）入父会话的恰一次判重/屏障槽位——需与 correlationId 判重面协调（2026-10-31 合并回执修复批刚踩过同族坑：停靠投递与终态回执共用 correlationId 被判重滤除） |
| D3 | dispatcher 链 schema 强制 chain-end root gate：ChainWrite 校验「无 out→Nebula 终节点的 dispatcher 链拒绝登记」，拓扑回投由构造保证 | BuiltinAgents.scala 分发器提示词已有「root gate」口径（dispatcher 侧）；delegate 写链侧无此约束 | 存量链写法回归：需要兼容窗（先 WARN 后拒）；快速小批（单节点直答）需要豁免形态 |
| D4 | 所有权项目化 + 孤儿收割：root 死亡 ⇒ 分发器 rootSessionId 转项目（AgentControl 判据同改）；开启 DispatcherTaskTerminalAnchor（Defaults.scala:643-660）收割无主分发器 | AgentRecord/AgentControlTool 判据单点；task-terminal 锚已实现待启用 | 所有权语义变化影响面板归桶与审计显示；锚开启后「任务永不终态 ⇒ 分发器永不拆」的量化上限（设计 §4d 的 cap 条款）必须同批落 |

结构修是唯一能把「开工宣言终态」从事故变成正常事件（链照跑、结果照回、会话照关）的档位。D1+D2 是最小闭环；D3 是构造性防线；D4 是回收面。

### 提示词修档（分发器完工纪律）

| # | 改动 | 回归风险 |
|---|---|---|
| P1 | dispatcher 模式 delegate 简报补完工纪律：**完工 = 链收束**；派链后的终文禁用「已开工/已交付」语气，必须显式申报「链在飞、批次未完」并走 delegate_report 语义（finish 只允许链收束后调用） | 纯提示词 = 咨询性约束：9b828eae 的宣言正是在现行提示词下产生的——降发生率不除险；回合预算/上下文压力下模型仍可能提前结束 |
| P2 | delegate 写链纪律与 D3 同口径：每条 dispatcher 链必含 chain-end 验收节点且 out 指向 Nebula——提示词与引擎校验同批改，避免「提示词说的规则 ≠ 引擎执行的规则」互相打架 | 共享前缀变更 ⇒ prompt cache 失效一段（nebflow-cache 纪律：改动尽量贴前缀尾部，控制失效面）；链 schema 口径双写点（提示词 / ChainWrite 校验）需同步维护 |

### 分档建议

先落小修 S1/S2/S3/S4/S5（一天内、低风险、报表与权限面立即恢复诚实），结构修 D2（结果必达）与 D4（收割）次之，D1（解耦）是主工程需要设计批，D3 随 D1 的 schema 面一起落，提示词修 P1/P2 随 D3 同批（避免口径先行于机制）。

## 6. 未决项与旁证异常

1. **9b828eae 为什么写了两条 11 节点链**（14:46 n-wave、15:03 b-wave）：n-wave 14:58:37 已归档（archive=sweep 认为全终态，未逐节点核验是否真跑完），b-wave 15:03:19 重写——delegate 在两条链之间的行为（重写动机）只有会话历史可考，本批未展开。
2. **作用域目录宽限删除未生效**：9b828eae 终态 15:03:39，宽限 10min，但 flow-maps 目录 16:45 仍在盘上，`scope dir removed after grace window` / `not safely removable` 日志均零命中——graceRemoval fiber（DelegateTerminal.scala:706-715）疑似未跑或静默失败，需单独核查（防同类静默面扩大）。
3. **flowmap-lifecycle 的 INFO 观测行零命中**但 chain-cancelled 审计行落盘（`retire cancel-closure` 应有日志）——logger 配置或异步丢失，观测面缺口，修 S1-S3 时顺带核。
4. **工作区遗留**：1f99803a 的未提交产出（.gitlab-ci.yml + README/CONTRIBUTING）在主仓工作区的处置归作者裁（本批只分析不收尾）；重飞批 94fe78c9 分支上的 recon commit f9feaeb71 是唯一实际产物，其下游九节点未跑。
5. 判词行 source=judge 无法区分「delegate 是否调过 delegate_report」——判定机在自申报**之前**消费（DelegateTerminal.scala:173-176 判词序），若要审计自申报纪律需另加观测行。

## 7. 复核锚点汇总

- 判定入口：`BackoffSupervisor.scala:163-191`
- 判词序/兜底：`DelegateTerminal.scala:173-188`；判定机两层：`DelegateJudgeMachine.scala:93-107`
- 折叠点：`DelegateJudgeMachine.scala:203-210`（Jev）/ `:504-510`（SmallJudge）；#566 语义：`DelegateTerminal.scala:38-42` 头注
- inFlightChains 读数与入参：`DelegateTerminal.scala:121-137, :163`；入判定输入未消费：`DelegateJudgeMachine.scala:463, :467-471`
- 唯一确定性闸（合并轮机械验收，对照件）：`DelegateTerminal.scala:231-240`
- 终态清理/链取消：`DelegateTerminal.scala:629-720`；`DelegateFlowMapLifecycle.scala:87-192`
- 分发器归属与桥：`ProjectActor.scala:2237-2257, :1205-1325`；R7-b 不回投：`:1247-1254`；4h 空闲窗：`Defaults.scala:639-641`；任务终态锚：`:643-660`
- 所有权守卫：`AgentControlTool.scala:430-441`；节点产物闸（对照件）：`CompletionGate.scala`
- 盘面台账：`~/.stand/chains/{1284,1287,1289,1291}.json`；审计流：`.nebflow/flow-map-events.jsonl`（15:03:39 / 15:15:37 / 15:21:14 / 15:36:40 四条 chain-cancelled）；日志：`~/.stand/logs/stand.log` 行 43192 / 44361 / 44708 / 49128 / 49887 / 50136 / 51999 / 52393 / 59957
