---
type: audit-report
status: final
analyzed-at: 2026-11-01 08:10-08:40
---

# delegate 静默死亡缺陷分析（作者 11-01 08:10 令，只查不改）

> 证据面：`~/.stand/delegates/*.json|*.receipt.json`、`~/.stand/merge-ledger.json`、`~/.stand/merge-channel/`、`~/.stand/sessions/<父会话id>/{delivered-receipts.json,injection-queues.json}`、引擎日志 `~/.stand/logs/stand.2026-10-31.0.log` 与 `stand.log`、git 仓（main 历史/分支/worktree）、引擎源码 `src/main/scala/nebflow/**`。所有结论均给出可复核锚点；无证据处显式标「日志未覆盖」。

## 0. 结论（一段话）

**四批没有死，一个字都没丢工作：它们在供应商抖动中自愈（23:23–23:32 两轮 error-freeze，120s 自动 resume），全部完工、提交、自己 `--no-ff` 合入 main 并正常归档（23:45–00:02）**。真正失效的是「恰一次终态通知」链：四份完工回执在父会话自身冻结窗口内被标记「已投递」（`delivered-receipts.json` 在案），但父会话从未消费——ack 全部缺席、父 transcript 四百余条消息里零命中完工报告正文；而重投腿与该标记**共用同一身份键**，三次重投（23:56–00:31）全部被 intake 判重压制——**单次投递窗口一旦错过（父恰好在冻结），终态通知就永久丢失，且无任何机制能发现「已标记未消费」这个状态**。根会话在零通知下于 08:09 以「原批死于掉线、零产出」的错误前提重派了三批（08:22 撤回，白烧一轮）；完工卡批（c2937ee7）则是第二种静默形态：bg 等待屏障在后台任务被取消后无人复审、2h 兜底 cap 命中「空集静默分支」、合并队列从未入队 → 假活 9 小时，直到根会话 08:08:56 手动 Mail 才唤醒。简报中「分支零提交」的观察与 git 相反——三批分支均有提交且已在 main（合并后 `git log main..分支` 为空，正是「看起来零提交」的错觉来源）。

## 1. 事实基线修正（简报叙事 vs 磁盘证据）

| 简报说法 | 磁盘/git/日志事实 |
|---|---|
| 四批「会话消失、分支零提交」 | 四批 task 文件均 `reportStatus=finish` + `archivedAt` 齐全（`~/.stand/delegates/delegate-nebflow-{f7c28d49,add4c91e,5856419d,7f69c44f}.json`）；三批分支有提交且已并入 main：f7c28d49→c7b3bcdc6（00:01:03）、5856419d→7a303925f（23:45:17）、7f69c44f→bf8489d9f/63bebe9ba（23:56:42）；仅 add4c91e 真零提交（见 §2.2，另一种病灶）。「消失」感来自：7f69c44f 归档后 worktree+分支被清理（其余三批未清理，清理面不一致，见 §6 未决项）；以及合并后 `main..分支` 无差集。 |
| 「Frozen→Processing→消失」因果链 | Freeze 是真的（23:23:31 dock收形/长按退役/徽标归位，23:31:01 完工卡，23:32:01–31 手机接入/待办改版等，`error-freeze reason=provider-down resumeIn≈120s`，stand.2026-10-31.0.log:91893–92065 段）——但这是 120 秒自愈暂停，四批全部自动恢复并继续干活（23:42 起工具调用连续）。00:47「全部转 Processing」的引擎侧证据线：日志未覆盖（AgentControl list 的返回结果不落日志），但各批实际活动恢复时间均远早于 00:47。 |
| 「根会话选 wait，之后零通知」 | wait 决策成立；零通知也是真的——但通知腿不是「没走」，是**走了三步断了第四步**：回执生成✓（四份 receipt 在案）→ 投递标记✓（父 `delivered-receipts.json` 含全部五个 id）→ 父消费✗（transcript 零命中）→ 重投✗（三次全被 exactly-once intake 压制，日志 `external-event-duplicate-suppressed corr=delivery-redelivery-*`）。 |
| 「面板行僵尸（无终态）」 | 08:10 时点面板上真正活着的行是：c2937ee7（引擎侧真·假活，挂到 08:12:40）+ 08:09 误判重派的三批（432df36a/1f4dbedf/d71b0ea4，08:22:33 才撤回）。四原批引擎侧 23:45–00:02 已终态。若作者所见「进行中」行确指四原批行，则为前端快照镜像未随终态刷新，需前端复核（§6 未决项）。 |

## 2. 逐批死亡时间线（有据才写）

### 2.1 dock收形批 f7c28d49 —— 正常完工，通知黑洞（5856419d / 7f69c44f 同构）

| 时刻 | 事件 | 证据 |
|---|---|---|
| 23:23:31 / 23:32:31 | 两轮 error-freeze（provider-down，120s resume） | stand.2026-10-31.0.log |
| 23:56–23:59 | 验证、写批文档、`git add`/commit 587db0035（23:59:01） | 同上日志工具调用流；git |
| 00:00:51 | `session-sync push status=ready-to-merge`；BackoffSupervisor `held for merge round (parent NOT notified — the single terminal receipt follows when the merge lands)` | stand.log |
| 00:01:03 | 会话自己执行 `git merge --no-ff` → c7b3bcdc6 | stand.log + git |
| 00:01:23 | `delegate_report(finish)` 「合并轮完成…机械验收面成立」 | stand.log |
| 00:01:35 | 归档（archivedAt），completed 事件投父：**`external-event-duplicate-suppressed — identity already delivered/queued`**（说明更早的投递已标记） | stand.log |
| 00:11:39 / 00:21:40 / 00:31:41 | 重投 #1/#2/#3 全部被 intake 压制；`[delegate-delivery] parent never acknowledged … terminal (completed)` | stand.log |
| — | 回执：`deliveredAt=00:31:41, ackedAt=None, redeliveries=3` | `~/.stand/delegates/delegate-nebflow-f7c28d49.receipt.json` |

同构两批：5856419d（手机接入，commit c77836cdf 23:44:34 → merge 7a303925f 23:45:17 → 归档 23:45:48）、7f69c44f（徽标归位，commit bf8489d9f 23:55:44 → merge 63bebe9ba 23:56:42 → 归档 23:57:14）。两份回执同为 `ackedAt=None, redeliveries=3`，重投压制日志 23:56:05 起同型。merge-ledger 三条 `state=merged, closeReason="merge landed (care-tick fact-check; owner did not report back)"`——care-tick 兜底合入与 owner 汇报存在竞态，但本例 owner 实际在其后数十秒内完成收尾汇报，非致命。

### 2.2 长按退役批 add4c91e —— 唯一真「零提交」，死于**过期完成释放**

| 时刻 | 事件 | 证据 |
|---|---|---|
| 23:23:31 / 23:32:31 | 两轮 error-freeze，自愈 | 日志 |
| ~23:4x–23:50 | 发出一版**过早的 finish 申报**（reportDetail 原文自认："Spec job e5beb936 still running … This report is premature — superseded by continuing work; no merge yet"） | task 文件 reportDetail；bg job e5beb936 于 23:50:22 完成日志 |
| 23:50–00:02 | 继续干活：改 spec、probe-t4 反复跑、再挂两个 bg job（db86646e 23:53:35、1b4de8fd 00:02:10） | 日志工具调用流 |
| 00:02:24 | `BackoffSupervisor: background task(s) all finished — releasing held completion` | stand.log |
| 00:02:28 | 归档：reportStatus=finish 但 **reportDetail=那份过期 premature 文本**；workReport=干到一半的叙述；分支零提交（tip=db0068003=与 main 的 merge-base），worktree 留未提交改动 | task 文件；git worktree list；receipt payload |

**病灶**：等待屏障（wait barrier）扣住的 completion 来自 23:4x 那一轮；其后 3+ 轮新回合并未作废这笔「旧完成」，bg job 一清零就被原样释放归档——旧申报杀死了还在干活的会话，未提交工作就地遗弃。代码位：`src/main/scala/nebflow/agent/BackoffSupervisor.scala:405-431`（holdForBgWait）与 `:527-538`（释放腿只在「新 Completed 过闸且等待集空」时触发，从不校验 held completion 是否已被后续回合过期）。

### 2.3 完工卡批 c2937ee7 —— 第二种静默：没死也不再动的 9 小时假活

| 时刻 | 事件 | 证据 |
|---|---|---|
| 23:10–23:12 | 完工：commit 76f3bf3b3（23:11:41）；reportStatus=merge（报告已交付）；23:12:09 回合结束但 bg job 7728a39c（"Repro T12 note bg state after click"，已空转 20+ 分钟零输出）未结 → **completion 被 hold**（wait barrier），2h cap 武装 | stand.2026-10-31.0.log；BackoffSupervisor.scala:414 |
| 23:20:03 | bg-lifecycle nudge #1（"owner asked to check"） | 日志 |
| 23:33:46 | bg job 7728a39c 被取消（`cause=cancelBackgroundJob`）——这是该会话在 31 日日志里的**最后一条** | 日志 |
| 23:33→08:08 | **零日志**。hold 无人复审；cap 到点（~01:12）时 `waitingFor` 已空 → 走「竞态：唤醒轮在飞」静默 no-op 分支（BackoffSupervisor.scala:437），唯一兜底无声失效；reportStatus=merge 但 **MergeQueue 从未入账**（merge-ledger 无此 id、merge-channel 无文件）——bg 等待闸先于终判，`Outcome.HoldForMerge` 永未到达 → 永无 merge window/reminder | 日志缺席 + BackoffSupervisor.scala:211-246 + merge-ledger.json |
| 08:08:56 | 根会话手动 `Mail(→delegate:delegate-nebflow-c2937ee7)` 唤醒 | stand.log |
| 08:09:22 / 08:09:32 | bg-ownership 清理；会话自己 `git merge --no-ff` → c29b35be7 | stand.log + git |
| 08:12:38 / 08:12:40 | `releasing held completion` → 归档，回执生成（redeliveries=0） | stand.log + receipt |

**病灶三连**：①bg job 被取消不触发 hold 复审（释放腿只挂在「新 Completed 过闸」上，而会话再也没有任何唤醒源）；②bgWaitCap 的空集分支按「竞态」假设静默放弃，把唯一兜底拆了；③`delegate_report(merge)`（工具面置 reportStatus=merge）与 supervisor 终判（HoldForMerge → 入 MergeQueue）之间无对账——报告说在等合并，账本里根本没有这一行。

## 3. 问题链逐答

**Q1 四批死在哪条路径上？** 没有死在任何路径上。四批 freeze（23:23/23:32）→ 120s 自动恢复 → 完工/提交/自合/归档（23:45–00:02），task 文件 finalState=finish、merge-ledger 三条 merged、git 三条 merge commit 在 main，四面证据互洽。简报的「恢复后二次崩溃/watchdog/session bridge 清理」假设均不成立；「消失」是归档+清理+零通知叠加的观感。

**Q2 父会话为何零通知（对照 AgentControl cancel 有通知）？** 通知腿走过，断在第四步：

1. 设计基点（10-31 08:38 裁定，`BackoffSupervisor.scala:211-227` 注释）：ready-to-merge 停靠不再投递，**每个 delegate 对父恰一次终态投递**（合并落地时，correlationId=delegateId）。
2. 四份终态回执全部生成并投出；父侧 `delivered-receipts.json`（256 条身份集）**含全部五个 id**——标记在投递决策点落了。
3. 但投递窗口 23:45–00:02 正是父会话自身 error-freeze 循环期（23:19:55/23:24:01/23:28:31/23:31:28 连续 freeze，`all providers down: none recovered within 120000ms`）——回合全部失败，父 transcript（431 条消息）对完工报告正文**零命中**（c7b3bcdc6 等字样最早出现在 08:21，是根会话事后自己 git 查证的产物）。
4. 重投腿（10min grace × 3 次）存在且全部触发——但 `DeliveredReceiptLedger.identityKeys`（`src/main/scala/nebflow/core/processor/DeliveredReceiptLedger.scala:128-131`）把重投件归位原件身份，`intakeAccepts`/`isDuplicateIdentity`（:146-153）对「已标记 ∪ 队列中」一律判重压制：**已标记未消费的身份永远无法重投**。日志实证：每批 3 次重投逐条 `external-event-duplicate-suppressed corr=delivery-redelivery-*`。
5. 附带发现：所有 markDelivered 调用点理论上与 ack 同点同序（AgentIdle:494-497/537-542/620-622、AgentIdle:816-820、AgentFinishTurn:488-493、AgentProcessing:943-951、AgentActor:400-407），但五份回执 ackedAt 全 None、全库无一条 ack 失败 WARN——**存在一条「标记成功而 ack 未落」的恢复/汇聚路径失配**，具体失配腿未逐行钉死（§6 未决项①），但「标记≠消费」这个不对称本身已被四面证据钉死。
6. 对照组：AgentControl cancel 走的是同一条 `notifyParentAndStop` 终态腿（`BackoffSupervisor.scala:635-652`），并不更可靠——08:22:33 两笔重复批撤回的 cancelled 事件此刻仍原样躺在父会话 `injection-queues.json` 待发队列里（本次分析批占线父会话所致，属正常暂存，但也说明 cancel 通知与完工通知同乘一条船）。另：五批 23:32 的 freeze 升级通知（`awaiting parent decision` 腿，AgentActor.scala:1530）被 `escalation-gate notify throttled (level=1, reason=provider-down)` 按父会话风暴闸静默压制（stand.2026-10-31.0.log:92049–92065）——冻结广播也没到父。

**Q3 面板行为何不沉降、谁负责终态化？** 引擎侧行态真源=agentRegistry 快照+WS 帧（delegateWaiting 等，`BackoffSupervisor.scala:247-259`「面板快照透出 Waiting」；终态腿整行移除 registry），前端行态按「engine 快照 → reportStatus → live.status」三级解析（`src/main/resources/web/js/delegateChainStub.js:220-223`）。四原批引擎侧已终态，行态理应沉降；08:10 时点真「进行中」的行是 c2937ee7（引擎侧假活至 08:12:40）与 08:09 重派三批（至 08:22:33）。**delegate 面板没有任务列表 #713 那样的重启对账**：行终态化完全依赖父消费通知这条已断的链；引擎重启/父恢复时没有任何一道 sweep 拿 delegates/*.json + merge-ledger 的账面事实来 settle 行。谁负责——答案是目前没人负责，这正是缺口。

**Q4 完工卡批 Idle 卡在哪？** 见 §2.3：不是等 barrier（barrier 依赖的任务 23:33:46 已取消），不是恢复后 turn 边界没续上（它 23:12 后没有任何待注入事件——完工报告早已交付、终态被 hold），而是 **hold 后失去一切唤醒源 + 兜底静默失效 + 合并账缺位**的三重叠加。08:12:38 的 `releasing held completion` 证明 hold 从 23:12 一直挂到 08:12。

## 4. 根因一句话

**四批在断供抖动中自愈并正常交付，真正缺陷是「恰一次终态通知」把 at-most-once 当 exactly-once 用：投递标记落在消费之前、父冻结窗口吞掉唯一投递机会、重投腿与标记同键而永久互斥、且无任何对账面能发现「已标记未消费」——c2937ee7 则是同一哲学在等待屏障上的复刻（hold 无复审、cap 空集静默、合并账缺位）。**

## 5. 修法清单（按影响排序）

1. **通知保障（最高）**：把「已投递」判定从注入决策点移到父回合实际消费点；引入「delivered-but-unacked」可视态，重投 intake 对该态豁免判重（重投必须能进门）；`ackedAt` 长期缺席的回执升级为对父的显式「通知未达」告警而非静默 3 次后放弃。验收红线：注入决策后令父回合失败 → 下一 grace 拍重投必须实际进入父回合（今日复盘里 12 次重投 0 次进门即为判红锚）。
2. **Frozen 恢复审计**：error-freeze/恢复/升级压制（escalation-gate throttled）全部落持久审计，AgentControl list 快照带「最近活跃时间」——23:35/23:47 根会话对「Frozen 态」的判读与各批实际自愈干活完全脱节，00:47 的「全部转 Processing」无引擎证据线；把任务列表 #713 重启对账先例扩到 delegate 生命周期：引擎启动/父会话恢复时对 ready-to-merge、held、receipt-unacked 的 delegate 出一份对账报告投父。
3. **等待屏障活性**：bg job 被 cancel/kill 时主动通知 supervisor 复审 hold（今日：cancel 后零复审、9 小时零日志）；`bgWaitCap` 的 `waitingFor=Nil` 分支禁止静默 no-op（BackoffSupervisor.scala:437），空集应立即走 release 或强制终判并留日志；held completion 在任何后续新回合到达时应作废重估（add4c91e 病灶：15 分钟前的过期完成申报杀死了还在干活的会话）。
4. **合并账对账**：`delegate_report(merge)` 置位与 MergeQueue 入账之间加对账——reportStatus=merge 而账本无此 id 超 N 分钟即告警/自动入队（c2937ee7 从未入队 → 永无 merge reminder）。
5. **面板对账**：delegate 面板行接入与 #713 同款对账 sweep，以 delegates/*.json reportStatus + merge-ledger 为真源 settle 行态；unacked 回执超 1h 的行挂「通知未达」徽标而非进行中。
6. **重派防误判**：重派前的「零产出」判定必须查 merge-ledger/分支 tip/receipt 三账（08:09 重派理由与三账事实全部相反）；重派批创建时与「曾合入 main 的同令批」自动查重（08:22 重复批撤回本可免）。

## 6. 未决项 / 日志未覆盖

- ① **「标记成功而 ack 未落」的具体失配腿**：所有已知 markDelivered 调用点均与 ackEvent 同点（AgentIdle:494-497/537-542/620-622、AgentIdle:816-820、AgentFinishTurn:488-493、AgentProcessing:943-951、AgentActor:400-407），但五回执 ackedAt 全 None 且无 ack 失败 WARN——需在修复批中加临时审计日志逐腿钉死（本批只查不改）。
- ② AgentControl list 的返回内容不落日志：23:35/23:47 根会话看到的 Frozen 名单、00:47 的「全部转 Processing」均无引擎侧原始记录，只有根会话转述。
- ③ 7f69c44f 归档后 worktree+分支被清理而其余三批未清理，清理触发条件未查（疑与归档竞态有关，零证据不下结论）。
- ④ 若作者面板所见「进行中」行确为四原批行（而非重派三批+完工卡行），则前端快照镜像未随引擎终态刷新，需前端复现定位（引擎侧四面证据均为已终态）。

## 7. 关联文档

- [[docs/design/20261029_200944_merge-queue-write-ahead-channel-spec__delegate-nebflow-3ad3478e.zh-CN]]（合并队列 write-ahead channel——c2937ee7 未入账问题在排队语义上的对照面）
- [[docs/design/20261030_merge-lane-state-machine.zh-CN]]（合并 lane 状态机——RequeueForLane/careTick 放行腿）
- [[docs/design/held-receipt-drain-silence-spec.zh-CN]]（held 回执 drain 静默——本批 Q2/Q4 的同族病灶前案）
- [[docs/20261031_112344_delegate-standby-semantics-audit__delegate-nebflow-c0a2b939]]（delegate 停靠语义审计——Waiting/hold 语义盘点）
- [[docs/20261031_235200_bg-badge-homecoming__delegate-nebflow-7f69c44f]]（徽标归位批——四原批之一，其批文档即本次「未消失」的直接物证）
