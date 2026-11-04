# 中断恢复验收台架（V1–V5）· 红证（改动前）

**批次**：9.19 版中断恢复实现（机制 + sha + file:line + 今天还在不在）
**日期**：2026-11-04
**台架**：`src/test/scala/nebflow/core/project/InterruptRecoveryLegsSpec.scala`
**脚本**：`reports-evidence/20261104_interrupt-recovery/run.sh`
**红证日志**：本目录 `V1.log` … `V5.log`（每腿一份 sbt 原文 + `[LEG Vn]` 证据行）

---

## 1. 台架选型（为什么是 spec 缝，而不是真 JVM 隔离实例）

任务书给了两条路：引擎内 spec 缝（`OrphanDelegateRecoverySpec` 示范）或真实隔离实例端到端。
**本台架选 spec 缝**，理由三条：

1. **断言必须确定性**。真实例端到端要跨进程 SIGKILL + 真 boot 链 + 真 LLM 回合，「会话重启后是否到达
   Processing」会受网络/模型/启动竞态影响——同一份代码可能跑出红也可能跑出绿，红证不可复跑。
2. **驱动的是同一个生产函数**。`SubAgentStartupRecovery.sweep` 就是 boot 链上**唯一**的中断恢复决策点；
   `GatewayMain.scala:834-856` 只做装配（`RevivalDeps(sharedResources, actorSystem, wsHub.broadcast)`），
   没有任何额外逻辑。台架里的「重启」= 「盘面摆成崩溃后的形态 + 跑同一 sweep」，决策语义与真实重启逐字一致。
3. **真件全在**：真 `SubAgentTaskStore` / 真 `SessionStore` / 真 `ActorSystem` / 真 spawn（`SharedResources.spawnAgentActor`
   + `spawnSupervisedAdapter`）/ 真 `DelegateRegistry` 盘面；只有 LLM 换成记录型 stub（这正是断言
   「下一 turn 已启动」的观测面）。

V3b 的「fire-and-forget 崩溃窗口」用**探针 actor** 钉成确定性：探针收下 `ImmediateInput` 即不再落盘，
精确复刻「发送方 `ref ! …` 已发出、接收方还没 `persistQueues` 就随进程死亡」这一现实窗口
（`NodeDelivery.scala:317` → `AgentProcessing.scala:1650`）。

**未采用**：真隔离实例（`--port 64xx` + 独立 `STAND_HOME`）。理由：上述确定性要求无法在 kill/重启竞态下
满足；且本机 2026-11-03 22:59 有过隔离 canary 误杀生产实例（pid 41715）的事故，任何多进程台架都有同类风险面。
本台架**零进程启动**：只在 sbt 的测试 JVM 内跑 spec，不碰 41715、不调 `stand stop/restart`。

---

## 2. 每腿一条命令

```bash
cd "/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-70e8d8ab"

bash reports-evidence/20261104_interrupt-recovery/run.sh V1     # → red/V1.log
bash reports-evidence/20261104_interrupt-recovery/run.sh V2     # → red/V2.log
bash reports-evidence/20261104_interrupt-recovery/run.sh V3     # → red/V3.log
bash reports-evidence/20261104_interrupt-recovery/run.sh V4     # → red/V4.log
bash reports-evidence/20261104_interrupt-recovery/run.sh V5     # → red/V5.log
bash reports-evidence/20261104_interrupt-recovery/run.sh all    # 顺序全跑
```

裸 sbt 等价形式（判词节点可任选）：

```bash
IR_LEG=V1 sbt "testOnly nebflow.core.project.InterruptRecoveryLegsSpec"
```

（`IR_LEG` = 腿筛选环境变量：选中的腿真跑，其余用例 `assume` 跳过；缺省 = 五腿全跑。
不用框架过滤参数——sbt fork 与非 fork 两种形态都能读到，退出码精确反映该腿红绿。）

退出码 = sbt 退出码（红 = 非 0）。每条用例失败时会在日志里打印
`[LEG Vn]` 证据行（现状读数 + why-red 的 file:line），下游修复节点据此定位。

---

## 3. 五腿断言与当前代码为什么红

| 腿 | 场景（盘面 = 崩溃后现场） | 目标语义断言 | 现状 | 现状为什么红（根因 file:line） |
|---|---|---|---|---|
| **V1** | delegate 跑到一半、为自家作用域链节点 standby 停靠（BB1 形态：`standbySince` 在场 + 作用域图有非终态节点 + 转录在盘） | sweep 后 `report.revived ∋ id`、任务行仍 `running`（Processing）、registry 有活体 actor、**下一 turn 已启动**（stub LLM 收到请求且请求体含崩溃前转录） | **红**（控制组普通在飞孤儿已绿，证明夹具通、红只归因 standby 闸） | `DelegateRevival.scala:107-116`（standby 闸把「等自家链节点」误判为「等作者」）→ `SubAgentStartupRecovery.scala:266-268` 判不合格 → `:292-296` 落收殓链 → `recoverOne:508-525` 任务行 `failed` + 父通知。BB1 实证：`stand.log:9` skip 原因逐字 = `:114-115` |
| **V2** | 人工 cancel（`cancelRequestedAt` 墓碑）后引擎重启，任务行仍 `running`（e25cd0c2/b5646a58 形态） | 三条腿都守住：boot sweep 不复活、二次 boot 幂等（不复活）、`SessionResumption.judge` 判 `Derive`（不同 id 原位续） | **绿（按设计本来就是绿的）** | 墓碑闸 `DelegateRevival.scala:100-106` + `SessionResumption.judge:173-177`（2026-10-28「R1 缝隙 C 修」）。**为什么加固**：修复批会新增自动复活腿（D3 watchdog 复活 / D4 failed∧restartInterrupted 自动腿 / standby 分型），任何新腿都必须先过同一道墓碑——本腿把「每条同 id 复活腿都不许绕过墓碑」钉死，新腿绕过即红 |
| **V3** | ① 接收方已落盘（`injection-queues.json`）的 node 消息 + 同 id 重 spawn；② `sendNodeMessage` 对 running 节点投递成功但接收进程当场死亡 | ① 重启后消息被重放、崩溃前上下文完整；② **投递成功 ⇒ 消息已落在可重放的持久面**（注入队列 / 转录 / 节点 task 任一） | ① **绿**（既有 F2 链）；② **红** | ②`NodeDelivery.scala:317` `record.ref ! ImmediateInput` = fire-and-forget，返回 `Right` 时盘上零字节；接收方落盘在 `AgentProcessing.scala:1636-1650`（`persistQueues`），之间崩溃只剩 `FlowMapEventLog` 一行（`NodeDelivery.scala:332-338`）且**无人回放**（审计 D5；`AgentActor.scala:565` 在 sessionId 空时 `persistQueues` 直接 no-op） |
| **V4** | 崩溃前已完成 3 个 turn（转录在盘），重启复活 | ① 已完成 turn 数/内容不变（不截断不重置）；② 复活必须携带**机器可读的已完成步骤断点**（台账），使「已完成」与「待做」机械可分 | ① **绿**（`initialMessages = transcript`，`DelegateRevival.scala:241`）；② **红** | ②断点台账**整条不存在**：`TurnStateStore` 的 `save`/`load` 链被审计判定为假恢复链（`save` 无生产调用点）并整棵移除，只剩清理原语——`TurnStateStore.scala:6-18`；复活交给模型的只有一句人话 nudge（`DelegateRevival.scala:317-328`）、Mail 续命同款（`SessionResumption.scala:358-362`）。审计 D6 |
| **V5** | 活已干完只缺收尾报告（cb580112 形态）：任务行 `completed` + `registry.workReport` 非空 + `reportStatus` 缺席 + 已归档 | 重启后必须**自动收尾**（交付 workReport / 终态化）或至少进入「已交付待报告」态**并通知父会话**；不许静静地既不活也不报 | **红** | 在飞视野只收 `running/restarting/ready-to-merge/frozen`（`SubAgentStartupRecovery.scala:313-316`）⇒ `completed` 行**连视野都不进**；没有任何腿读 `workReport`/`reportStatus`（审计 D8）。cb580112 实证：11:25 合并先落地 → finish 无裁定 → idle（`stand.log:148849`「ended its turn WITHOUT a delegate_report … parent NOT notified」） |

### 机械判据（写死在台架里的口径，与 fixsession 对齐用）

- **「活干完」（V5）**：`subagent-tasks/<parent>.json` 中该行 `status == "completed"` ∧ `DelegateRegistry` 记录
  `workReport` 非空 ∧ `reportStatus` 缺席（∧ `archivedAt` 在场 = cb580112 实录）。三者都在盘上，可机械求值。
  **刻意不用**「分支已合并」作主判据：合并事实要 git 上下文，且 `ready-to-merge` 行归 `MergeQueueBootSweep`
  分诊（`SubAgentStartupRecovery.scala:158,195-232`），会与 V5 抢判——风险更高、可复跑性更差。
- **「可重放的持久面」（V3b）**：`CompactionQueueStore.load(sid)` / `SessionStore` 转录 / 节点 `task` 三处任一含消息正文。
  事件流 `flow-map-events.jsonl` **不算**（无人回放，审计 D5）。
- **「机器可读断点」（V4）**：`<dataRoot>/sessions/<sid>/` 下的 json/jsonl 台账（排除 `injection-queues.json`——
  那是待注入队列不是已完成台账）内容里列举已完成 round（≥2 个 round 标记），或含
  `checkpoint|completedTurns|turnCursor|cursor` 关键词且带 ≥3 的数值槽。今日一族皆无。
- **「Processing」（V1）**：作者 11:27 裁定的等价活体信号 = actor 注册（`agentRegistry` 含 id）+ 下一 turn 启动
  （stub LLM 收到请求且请求体含崩溃前转录）+ 任务行 `running`。三件同时成立才算绿。

---

## 4. 实测读数（2026-11-04 13:02–13:05 本批跑出，逐字摘自 V1–V5.log）

| 腿 | 退出码 | 关键读数（日志原文） |
|---|---|---|
| **V1** | **1（红）** | 控制组 `report: terminalized=0 revived=1 (delegate-nebflow-v1-control) revivalSkipped=0`（夹具通、可复活）；被测形态 `revivalSkipped: List((delegate-nebflow-v1-bb1, standby at engine restart — the delegate was parked awaiting the author, not working; not revivable …))`、`task row status after restart = failed`、`live actor registered = false`、`next turn started (LLM requests) = 0`、`scope open non-terminal nodes = 1`。失败点 = 第 291 行目标断言（revived ∋ id） |
| **V2** | 0（绿·按设计已满足） | `boot#1: terminalized=1 revived=0 revivalSkipped=1 skip=List((…cancel tombstone present (cancelled by the user panel at …) — a cancelled delegate is never revived))`；`boot#2: terminalized=0 revived=0`；`row status after boots = failed`；`live actor = false / false; turns started = 0`；`SessionResumption.judge = Derive(cancel tombstone present …)` |
| **V3** | **1（红）** | V3a（既有 F2 链）`persisted queue replayed after restart = true`、`pre-crash context intact = true`、`transcript messages = 6`；V3b（被测）`sendNodeMessage result = Right(Message delivered to running node 'n-v3b' — it will be injected at the next turn boundary …)`、`probe actor received = 1 message(s)`、`durable replay surfaces after a SUCCESSFUL delivery: injection-queues=false, transcript=false, node.task=false`、`flow-map-events trace lines (NOT replayed by anyone) = 1`（该行原文：`{"type":"node-message","nodeId":"n-v3b","summary":"injected -> live session node-v3b-session: [NODE-MESSAGE] V3_INFLIGHT_INSTRUCTION do not lose me"}`） |
| **V4** | **1（红）** | 护栏绿：`after-revival transcript contains all 3 rounds = true`、`task row = status=running revivalCount=Some(1)`；被测红：`machine-readable checkpoint artifacts under sessions/delegate-nebflow-v4/ = 0`；`revival nudge (the ONLY hand-off the model gets) = [system] The engine process restarted while your task was in flight. Your work so far (4 transcript messages) is loaded below — continue the task from where you left off.` |
| **V5** | **1（红）** | `sweep report: terminalized=0 revived=0 revivalSkipped=0`、`task row status = Some(completed)`、`registry workReport present = true, reportStatus = None`、`parent mailbox events for child after restart = 0` |

**三态小结（改动前）**：V1 红 · V2 绿（按设计，已加固）· V3 红 · V4 红 · V5 红。
V2 之外四条全红；V2 的绿不是「验收已通过」，而是「这条语义早就实现了」——它的价值是给修复批的
新增自动复活腿当**防回归锁**（判据见 §3 表 V2 行「为什么加固」）。

---

## 5. 给下游修复节点的最小改动方向

| 腿 | 最小改动方向 |
|---|---|
| **V1** | standby 分型：`DelegateRevival.eligibility` 的 standby 闸（`:107-116`）改为「链等待 ≠ 作者等待」——作用域图仍有非终态节点（`SessionResumption.openNodeCount(scopeDir) > 0`）/ worktree 脏 / ready-to-merge 任一在场 ⇒ 走复活腿而不是收殓；纯作者等待（图已清、无旁证）维持现语义（`DelegateRevivalSpec` V10 是这条语义的既有锁，改闸时要同步改 V10 的期望）。boot cap（`Defaults.DelegateRevivalBootCap = 8`）与预算（`max = 2`）不变 |
| **V2** | 无需修（已绿）。新增的任何自动复活腿（watchdog 复活 / failed 自动腿）必须先调 `DelegateRevival.eligibility` 或 `SessionResumption.judge`，否则本腿红 |
| **V3** | 投递语义补持久化：`NodeDelivery.injectRunning` 先落盘（`injection-queues.json` 或 node.task 的 undelivered 段）再 `ref !`；或发送方要接收方回执（回执前记账为 undelivered），重启后由 `NodeRecovery`/`RecoverPersistedQueues` 统一重放。事件流一行不能算送达 |
| **V4** | 复活带断点：把已完成 turn/tool-round 的机器可读台账恢复出来（真写点的 checkpoint store，写点 = turn 边界），复活时随 `initialMessages`/首条注入一起交给会话，并在续跑指令里声明「已完成的 N 步不再重做」。禁止把「已存在但没人写」的 TurnStateStore 老壳复活成第二份假恢复链 |
| **V5** | boot 腿加一条「完成未报告」分诊：`status=completed ∧ workReport 非空 ∧ reportStatus 缺席` 的行 → 自动收尾（交付 workReport 给父邮箱 + 任务行终态化）或落「已交付待报告」态并以 `MailboxProducer.deliver` 通知父会话。`.void` 吞 `MailResult` 的写法（`SubAgentStartupRecovery.scala:500`）不得复制——通知要带可核对的投递读数 |

---

## 6. 复跑纪律

- 台架不写 `~/.stand`、不起进程、不碰生产 pid 41715。每次用例自带临时 `dataRoot`（`os.temp.dir`），跑完即清。
- 修复批改完后，**同一命令**应转绿；判词节点独立复跑同一命令即为复验。
- 若某腿在修复后仍红且确认语义已满足，**改断言而不是改宽松**：V4 的断点接受族（§3 机械判据）允许修复批按自己的
  台账形态扩写 `checkpointArtifacts(...)`，但不得退化为「只断言转录还在」（那是现状已绿项）。
- 既有 `DelegateRevivalSpec` V10 把「standby 重启不复活」写成期望——V1 转绿时该用例必然冲突，属**同批语义变更**，
  由修复批一并裁决（作者 2026-11-04 11:27 裁定：链等待形态必须自动复活）。

---

## 7. C-2 批（node 消息持久化）追加的红件（2026-11-04）

本目录原有 `V1.log … V5.log` = harness 节点 `ad770bfdb` 在**改前**跑出的红证，**一字未动**。
C-2 批在同一「改前」形态（`git stash push -- src/main/scala`）下追加三份红件：

| 文件 | 内容 | 改前读数 |
|---|---|---|
| `V3-C2-prefix.log` | `IR_LEG=V3 sbt testOnly InterruptRecoveryLegsSpec`（V3b 判据已含台账面 + STRICT 断言；V3c 为 C-2 新增） | `durable replay surfaces … injection-queues=false, transcript=false, node.task=false, node-message-ledger=false`；`ledger rows for this message =`（空）；`Failed: Total 6, Failed 1` |
| `NodeMessagePersistence-prefix.log` | `sbt testOnly NodeMessagePersistenceSpec`（S1 落盘前置 / S2 重放幂等 / S3 terminal 拒收） | `ledger rows = 0`、`prompt carries marker = false`；`Failed: Total 3, Failed 2, Passed 1`（绿的一条 = S3 不变量护栏，本批未改） |
| `NodeMessageReplayQueue-prefix.log` | `sbt testOnly NodeMessageReplayQueueSpec`（S4 队列兜底回放） | 编译即红：`value replayPendingNodeMessages is not a member of NodeEngine`（改前无此腿） |

> V3b 的 `durable` 判据在 C-2 批新增第四个持久面（`<workspace>/.nebflow/node-messages.jsonl`）：
> 这不是放宽——V3c 证明该面**被重启回放腿消费**（resume prompt 回放 + 消费后零重放），
> 且 V3b 另加 STRICT 断言（台账行必须在场）。改前四面全空，判据语义不变。
