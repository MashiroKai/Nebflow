PASS

# 判词 · 中断恢复修复验收（独立复核，2026-11-04 15:14–15:26 CST）

判的对象：`delegate/delegate-nebflow-70e8d8ab` 上 `8884379eb`（C-1 会话侧）+ `00c78e8d9`（C-2 node 消息侧），
基线 `ad770bfdb`（台架 + 红证）。判词节点**未采信上游自述**：每条判据均自行重跑或读盘/读代码核出。

复跑落盘：本目录 `verify/V1.log … V5.log`（我自己的运行）、`verify/verifier-specs.log`（spec 复跑）。
命令：`bash reports-evidence/20261104_interrupt-recovery/run.sh all verify`（run.sh 原文未改，第二参换输出目录）。

---

## 判据 1 · 目标语义 ⊂ PASS（逐项）

**1a 被中断会话重启后自动原位复活（无人工 Mail）** —— 自跑，非自述：
```
$ bash reports-evidence/20261104_interrupt-recovery/run.sh V1 verify     → exit=0
[LEG V1/bb1] scope open non-terminal nodes = 1 (BB1 形态: 链上还有活)
[LEG V1/bb1] report: terminalized=0 revived=1 (delegate-nebflow-v1-bb1) revivalSkipped=0
[LEG V1/bb1] task row status after restart = running (作者要的是 running/Processing)
[LEG V1/bb1] live actor registered = true
[LEG V1/bb1] parent mailbox events for child = 0
  + V1 自动复活（无人工 Mail）… 1.014s        [info] Passed: Total 6, Failed 0, Passed 1, Skipped 5
```
同 sessionId + 转录回放 + 座位：`DelegateRevival.scala:376` `sessionId = task.taskId`、`:388` `initialMessages = transcript`、
`:354` `RevivalCheckpoint.write(...)`；日志 `event=spawn … msgs=2` → `event=turn-complete … msgs=3`（`verify/V1.log:13/:20`）。
无 Mail 代偿：台架断言面里 `parent mailbox events for child = 0`（复活腿不靠投递），我复跑未加任何 Mail 调用。

**1b 人工 cancel 不复活**：`verify/V2.log`
```
[LEG V2] boot#1: terminalized=1 revived=0 () revivalSkipped=1 skip=List((delegate-nebflow-v2-cancelled,cancel tombstone present (cancelled by the user panel at 1793776499473) — a cancelled delegate is never revived))
[LEG V2] boot#2: terminalized=1 revived=0 () ; row status after boots = failed ; live actor = false
  + V2 墓碑 … 0.446s     Passed: Total 6, Failed 0
```
代码闸序未动：`DelegateRevival.scala:239-241`（墓碑）在 `:263-271`（纯待命闸）**之前**，`eligibility` 全链 `:198-272`。

**1c 纯待命不复活 + 诚实落 failed**：自跑 `sbt -batch "testOnly nebflow.agent.DelegateRevivalSpec nebflow.agent.InterruptRecoveryClosureSpec"`
```
[info] Passed: Total 22, Failed 0, Errors 0, Passed 22     （15:20:50，verify/verifier-specs.log）
```
其中 `V10: PURE standby (zero in-flight evidence) … → still refused, salvage settles failed` 断言
`report.revived == Nil` ∧ `row.status == "failed"` ∧ `!live`；分型代码 `:114 def inFlight` / `:159 standbyTriage` / `:263` 文案仍带
`standby at engine restart` 前缀（旧口径可 grep）。

**1d `node:` 消息投递前落盘 + 重启可重放 + terminal 仍拒绝**：自跑
```
$ bash reports-evidence/…/run.sh V3 verify → exit=0
[LEG V3b] durable replay surfaces after a SUCCESSFUL delivery: injection-queues=false, transcript=false, node.task=false, node-message-ledger=true
[LEG V3b] ledger rows for this message = nm-mw1s7vf1-83aaee=injected
[LEG V3c] boot#1 resume prompt carries the message = true / boot#2 AGAIN = false / final ledger state = Some(consumed)
$ sbt -batch "testOnly …NodeMessagePersistenceSpec …NodeMessageReplayQueueSpec"
[NMP S3] sendNodeMessage = Left(Node 'n-s3' is terminal (status=completed) — messages are refused…) ; ledger rows = 0 ; probe received = 0
[info] Passed: Total 4, Failed 0        （verify/verifier-specs.log）
```
落盘点确实在分派**之前**：`NodeDelivery.scala:271-300`（append `queued` → 失败即 `NODE_MESSAGE_NOT_PERSISTED`，`:294/:299`），
终态拒收在落盘之前 `:258-269`（`:267` 文案）。台账形态 `NodeMessageLedger.scala:110` id / `:117` 正文标记 / `:185` 状态 fold / `:214` transition；
回放腿 `NodeRecovery.scala:342-380`（拼 resume prompt + `Consumed`）、`:430-499`（未认领节点补 task）。

**1e（分发器追加 1）V5 cb580112 形态自动收尾 + 父可观测**：`verify/V5.log`
```
[LEG V5] task row status = Some(completed) ; registry workReport present = true, reportStatus = Some(finish)
[LEG V5] parent mailbox events for child after restart = 1
[LEG V5/negative] sweep report: revived=1 ; row status = running ; parent mailbox events for child = 0 (must be 0) ; reportStatus = None
  + V5 收尾 … 0.461s ; + V5 反向 … 0.319s ; Passed: Total 6, Failed 0, Passed 2
```
代码 `SubAgentStartupRecovery.scala:415 finalizeCompletedUnreported` / `:439 finalizeOne`（先投后落 `reportStatus=finish`，一次性）；
二次 boot 幂等由 `InterruptRecoveryClosureSpec` `V5/finalize … + idempotent` 覆盖（22 全绿内）。

**1f（分发器校准追加）salvage 通知在父会话压缩后仍可被重新感知**：通路是**新增持久提醒账 + 压缩处重述**，不是换投递通道。
- 账：`RestartLossLedger.scala`（`<dataRoot>/sessions/<parentSid>/restart-loss-ledger.json`），写点 `SubAgentStartupRecovery.scala:695`
  （仅邮箱轨投递成功后记）。
- 重述点（压缩完成 = 抹除同一拍）：`AgentProcessing.scala:1199`（全量装态）、`AgentPingpong.scala:573`（乒乓换入 `durableSwapMessages` 前）。
- 可观测回执：`SubAgentStartupRecovery.scala:693` `[startup-recovery] notify receipt <task> → parent …: mailbox:Enqueued`
  （我复跑 V2 日志实测到 `mailbox:Enqueued` + 第二次 `mailbox:AlreadyPresent`，`verify/V2.log:…`）；另有 ChainStore `delegate-terminal-delivery` 行
  + `completion-finalize` 行；文案与事实同源 `:737-749`（失败写 `parent notification FAILED (cause)`）。
- 有界自清：`RestartLossLedger.scala:121/135/173/182`（≤2 次、24h 剪除、同 taskId 重投重置、末条 assistant 带 ToolUse 时不追加且不耗预算）。
- 单测：`InterruptRecoveryClosureSpec` `D1/recap` + `D1/receipt` 均绿（22 内）。

## 判据 2 · 证据真实性 ⊂ PASS（git 时间线可机械判，不是自述）

```
$ git log --oneline --diff-filter=A -- reports-evidence/…/red/V1.log
ad770bfdb test(interrupt-recovery): V1–V5 中断恢复验收台架 + 改动前红证      ← 红证在修前提交里
$ git diff --quiet ad770bfdb..HEAD -- …/red/V1.log .. red/V5.log  → 五个文件均 "unchanged since ad770bfdb"
$ git log --diff-filter=A -- …/green/{V1,V2,V4,V5}.log → 8884379eb ；green/V3.log → 00c78e8d9   ← 绿证由各自修复提交带入
```
日志本体是真 sbt 产物：墙钟时间戳（`13:02:17.563` / `14:05:33.586`）、`/var/folders/.../ir-seat-v1-bb15812550904913385063/`
这类每次运行新铸的随机临时路径、ANSI 色码、异常栈带 `InterruptRecoveryLegsSpec.scala:291` 行号；红绿命令逐字相同
（同一 `run.sh`，只有第二参 `red|green|verify` 不同）。红证失败读数与改前代码事实吻合（`standby at engine restart…`、
`injection-queues/transcript/node.task` 三面全 false）。**未发现事后手写/伪绿迹象。**

## 判据 3 · 复跑 V1–V4（含 V5 与 V3）⊂ PASS

我自己跑（非引用上游日志）：

| 腿 | 我的实际输出行 | rc |
|---|---|---|
| V1 | `revived=1 (delegate-nebflow-v1-bb1)` / `status = running` / `live actor registered = true` / `Passed: Total 6, Failed 0, Passed 1, Skipped 5` | 0 |
| V2 | `boot#1 terminalized=1 revived=0 skip=cancel tombstone…` / `boot#2` 同 / `row = failed` / `live = false` / `Passed 1` | 0 |
| V3 | `node-message-ledger=true` / `ledger rows = nm-mw1s7vf1-83aaee=injected` / `boot#1 …=true` / `boot#2 AGAIN=false` / `final=Some(consumed)` / `Passed 1` | 0 |
| V4 | `checkpoint artifacts = 1`（`…/data/sessions/delegate-nebflow-v4/revival-checkpoint.json`）/ nudge 含 `must NOT be redone` / `revivalCount=Some(1)` / `Passed 1` | 0 |
| V5 | `parent mailbox events = 1` / `reportStatus = Some(finish)` / 反向 `revived=1, events=0, reportStatus=None` / `Passed 2` | 0 |

红→绿对照（同机同 spec，红条目为我读回已提交的红证，绿条目为我的复跑）：
`revived=0→1`、`row failed→running`、`live=false→true`、`turns 0→turn-complete msgs=3`、
`checkpoint 0→1`、`parent events 0→1`、`reportStatus None→finish`、`ledger false→true`。

## 判据 4 · 幂等断言可观测 ⊂ PASS（附失败模式）

- **机制（V4）**：`RevivalCheckpoint` 从转录**纯函数**推导 cursor（转录条数）/completedTurns/completedToolRounds/最近 5 回合首行，
  落 `sessions/<sid>/revival-checkpoint.json`，读数拼进 nudge `DelegateRevival.scala:475`「those steps must NOT be redone」。
  可观测面 = 盘上 JSON（`verify/V4.log` 实测 1 个 artifact）+ closure spec 断言 `cursor=6, completedTurns=3, completedToolRounds=1,
  raw.contains("CP_ROUND_3_DONE")`（真断言，非空跑）。
- **机制（node 消息）**：状态机 fold `queued<injected/appended<replayed<consumed/dead` + 正文标记 `消息号 <id>` +
  转录判据权威（`NodeMessageLedger.inMessages/inText`）⇒ V3c `boot#2 AGAIN=false`、`final=consumed`、`probe tells=1`（LLM 侧零重复）。
- **机制（V5）**：先投后落 `reportStatus=finish` ⇒ 二次 boot 判据不成立（`events2.size == events1.size` 断言）+ 邮箱 identity=taskId 同幂等键。
- **失败模式（要记住的）**：①「不要重做」在 V4 里是**提示词级软保证**（模型可能不听话重跑，没有工具级去重）；
  ② 台账重述计数在**生成时**就 +1、而 V4/V3 类的落盘各不同 —— 全量压缩腿的 recap 只进内存态（`AgentProcessing.scala:1199`，
  靠后续 turn 落盘），若压缩后立刻崩则这一拍预算被消耗而父会话没看见（最坏 2 次用尽后静默）；
  ③ `node-messages.jsonl` 无剪除（线性增长，C-2 已自述）；④ 台账回放腿与 `injection-queues.json` 回放腿无显式去重，
  以转录判据为准（见判据 7 残余风险）。

## 判据 5 · 反作弊 ⊂ PASS

- 无 `@Ignore`/`@Ignore`-等价/`pending`：`git diff ad770bfdb~1..HEAD -- src/**` 全量 grep 仅命中台架既有的
  `assume(legEnabled("V1"), …)`（= `IR_LEG` 单腿过滤器，**红证时代就在**，红证日志里同一批用例显示 `skipped`，不是本批加的放水）。
- 无 sleep 伪绿：diff 里两条 `IO.sleep(80/100.millis)` 均在 `ad770bfdb`（台架提交）内的 `waitUntil` 有界等待，且等待后有硬断言；
  改造提交 `8884379eb`/`00c78e8d9` 未新增 sleep。
- kill-switch **未被默认关掉**：`Defaults.DelegateRevivalEnabled = sys.props.getOrElse("nebflow.delegate.revival.enabled", "true")`
  （`Defaults.scala:1427`），且 `git diff --stat ad770bfdb~1..HEAD -- '*Defaults*'` 为空 ⇒ 本批**未改**默认值/未开旁路。
  kill-switch off 时 failed 行也不进视野：`SubAgentStartupRecovery.scala:166`（`revival.isDefined && Defaults.DelegateRevivalEnabled`）。
- 判据改动面（唯一一处，已披露且**从严**）：V3b 的 `durable` 加第 4 面 `node-message-ledger` 且**另加 STRICT 断言**
  「台账行必须在场」+ 新增 V3c 证明该面**被重启回放腿真读**（无 V3c 时这条加面就是放水；有 V3c 则为「修复引入的新持久面被消费」）。
  `DelegateRevivalSpec.V10` 未被改弱：保留 `revived == Nil` + `failed` + `!live` 三条反向断言，另**加了** `reportStatus=Some("standby")` 收紧夹具，
  并新增 V11–V14 正反用例。

## 判据 6 · 生产安全 ⊂ PASS（有一处需要点名的事实更正）

- **`ps -p 41715` 不活**：`ps -p 41715` 无行、`exit=1`。→ **但 41715 不是本批杀死的**，证据链：
  ```
  engine 78176 lstart = 三 11月 4 12:42:14 2026（现役，hold 6138 LISTEN）
  本批首个提交 ad770bfdb = 2026-11-04 13:04:20   （8884379eb 14:07:02，00c78e8d9 15:11:10）
  stand.log:198877  12:21:26 "Background job 92fb9c96 热重启引擎（护栏链完整跑一遍） started sessionId=d7c3cb54…"
  stand.log:198876  12:21:26 ("/Users/kaiyu/Claude code/Stand/ops/stand-restart.sh" --hot 2>&1 | tail -30)
  stand.log 11-04 12:24:12.919（旧进程最后一行） → 12:42:15.400（新进程第一行）
  ```
  即：**根会话 Nebula（sessionId d7c3cb54）在 12:21 发起的 `ops/stand-restart.sh --hot` 演练**把 41715 换成 78176，
  时间早于本批任何改动；任务书里的 41715 是 11:27/11:54 写就的旧读数（本节点自身环境表 `PID = 78176` 亦证）。
- 6138 无事故：`lsof -iTCP:6138 -sTCP:LISTEN` → `java 78176 … TCP *:6138 (LISTEN)`，本批时段（13:04–15:26）**无第二次重启**
  （78176 连续 uptime 2h30m+），stand.log 本批窗口无 restart/bind/port 相关 ERROR（只有 jev/provider 网络 WARN，与本批无关）。
- 隔离实例零残留：我**未启动任何隔离引擎进程**（全部工作是本 JVM 内 munit spec）；
  `lsof` 在 6300–6500 与 8080–8089 段无 LISTEN；`ps | grep delegate-nebflow-70e8d8ab` 无残留进程；台架临时目录（`/var/folders/…/ir-*`）跑完即空。

## 判据 7 · 范围与风格 ⊂ PASS（含 3 条残余风险，不阻塞）

- 复用既有结构、不新造平行机制：投递仍走 `MailboxProducer.deliver`（identity=taskId）；回放复用 `bootRecoveryClaim` / `resumePrompt`
  （`NodeRecovery.scala:342-380`、`:430-499`、接线 `:699/:728`）；台账形态与 `flow-map-events.jsonl` 同族（同目录、append-only JSONL）。
- 未吞异常：新增的 fail-soft 都是显式 `.handleErrorWith(... logger.warn)`；投递结果**不再 `.void`**（改前 `- .void` 已删，
  `SubAgentStartupRecovery.scala:634-700` 改为 Either 如实回传 + 文案同源）。
- 未削弱既有语义：diff 里 85 行删除经逐行核读均为搬移/参数化（`eligibility` 闸序保持：kill-switch → 非 delegate → 外部执行器 →
  **墓碑** → 已交付报告 → 归档 → 座位 → 父会话 → 预算 → 年龄 → 纯待命），骨架未变。
- 残余风险（建议后续批，不影响本轮判定）：
  ① `RestartLossLedger` 头注与 fixsession 自述仍写 `restatementSync(sid)`，实际装配点是 `restatementForTail(...)`（文档漂移）；
  ② fixnode 自述的 `markReplayedConsumed` 在代码中不存在（实际为 `transition(..., Consumed)`）——文档引用名需订正；
  ③ 台账与 injection-queues 两回放腿无显式去重（fixnode 已披露）：boot 期两腿时序若重叠，理论上同一 `node:` 消息可能被注入两次
  （转录判据是时点检查），建议后续以台账单点为权威。

## 判据 8 · 文档素材齐否（给下游文档节点）—— 3/4 齐，1 条需补一句话

| 素材 | 状态 | 出处（下游直接引用） |
|---|---|---|
| 9.19 一句话机制 + sha + file:line | **齐** | `archaeology/9.19-recovery.md:9`（一句话机制）、`:3`/`:17-25`（sha 判定：tag `2026.9.19`→`249a83958`，恢复面与 `949ce9159` 逐字节一致）、`:132-147`（三分法表，含 `f62a1795c` standby 闸 `DelegateRevival.scala:107-116`） |
| 缺陷 Top5 | **半齐** | `audit/current-recovery-audit.md:256-315` 是 D1–D8 全表按严重度（D1 致命 / D2/D3/D4 高 / D5 中高 / D6/D7/D8 中），**没有一张单列 Top5 汇总**——文档节点需自行摘 D1–D5 成一表 |
| V1–V4 红→绿数字 | **齐** | `green/README.md:19-27` 表 + 本判词判据 3 的实测行（`verify/V{1,2,3,4,5}.log`） |
| BB1 案例在新实现下的结论 | **半齐** | 事实面齐：`forensics/20261103-2259-incident.md:75-114`（BB1 命中的就是 standby 闸）+ `red/README.md:66`（V1 台架即 BB1 形态）+ 本判词 V1 实测 `revived=1/status=running/live=true`。**但缺一句显式结论**（「若 11-03 22:59 重演，BB1 会被自动原位复活、不再需要人工 Mail」）——文档节点落笔时补这一句 |

---

## 结论与「必须重做什么」

**PASS**。无 FAIL 项。给 fixsession/fixnode 的回路上可选的 3 条打磨（**不阻塞验收**）：
1. 订正文档引用名：`restatementSync` 实际装配点为 `restatementForTail`；`markReplayedConsumed` 实际为 `transition(..., Consumed)`。
2. `node-messages.jsonl` 按 24h/水位压紧 `consumed|dead` 行（台账线性增长）。
3. 台账回放与 injection-queues 回放之间显式去重（或声明台账为唯一权威源），消除双注入的理论窗口。
