# 中断恢复验收 · 修复后绿证（green/）

> 台架与断言 = `reports-evidence/interrupt-recovery__20261104_114731/red/README.md`（harness 节点产，
> commit ad770bfdb，红证在 `red/`）。本目录只放**修复后复跑**的 sbt 原文，断言一字未改
> （V1/V2/V4/V5 全绿；V3 属审计 D5 节点消息持久化，不在本节点验收面，仍红）。

## 1. 复跑命令（每腿一条，日志落 green/）

```bash
cd "/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-70e8d8ab"
bash reports-evidence/interrupt-recovery__20261104_114731/run.sh V1 green   # 同法 V2/V4/V5
# 裸 sbt 等价：
IR_LEG=V1 sbt "testOnly nebflow.core.project.InterruptRecoveryLegsSpec"
```

`run.sh` 第二参 = 输出子目录（缺省 `red`——红证命令逐字不变；`green` = 本目录）。

## 2. 红 → 绿（数字见 green/V*.log 的 `[LEG ...]` 行）

| 腿 | 改前（red/V*.log） | 改后（green/V*.log 实测读数） | 修复点 |
|---|---|---|---|
| V1 自动复活（无人工 Mail，BB1 形态） | `revived=0`、`revivalSkipped: standby at engine restart…`、行 failed、`live=false`、零 turn | 控制组 `revived=1`；被测 `revived=1`、`scope open non-terminal nodes = 1`、行 `running`、`live actor = true`、转录回放（lifecycle `event=turn-complete … msgs=3`）、父邮箱 0 条（无申报 ⇒ 不自动终态，既有矩阵语义） | standby 分型：`DelegateRevival.eligibility`（在飞旁证豁免） |
| V2 墓碑不复活 | 绿（按设计已满足） | 绿：`boot#1 terminalized=1 revived=0 skip=cancel tombstone present…`、`boot#2` 同、行 failed、`live=false`、`SessionResumption.judge = Derive(cancel tombstone…)` | 无改动（墓碑闸仍在分型之前） |
| V4 幂等 | `checkpoint artifacts = 0` | `machine-readable checkpoint artifacts = 1`（`sessions/delegate-nebflow-v4/revival-checkpoint.json`）；nudge 携断点句（`Completed-work checkpoint … 4 transcript messages, … completed assistant turn(s) … must NOT be redone`）；3 个已完成 round 全在；`revivalCount=Some(1)` | 新增 `RevivalCheckpoint`，复活腿写点 |
| V5 收尾（cb580112 形态） | 父邮箱 0 条、completed 行不在 sweep 视野 | `parent mailbox events = 1`（携 workReport 全文）、`reportStatus = Some(finish)`、`sweep report: terminalized=0 revived=0` | 新增 boot 收尾腿 `finalizeCompletedUnreported` |
| V5 反向（没干完） | 新增用例 | 绿：`revived=1`、行 `running`、父邮箱 0 条、`reportStatus=None` | 同一条腿的判据闸（completed ∧ workReport ∧ reportStatus 缺席） |
| V3 节点消息持久化 | 红 | **C-2 批已转绿**（见 `green/V3.log` 与 `green/C2-node-message-persistence.md`）：台账面 `node-message-ledger=true`、`boot#1 resume prompt carries the message = true`、`boot#2 … AGAIN = false`、`final ledger state = Some(consumed)` | — （C-1 未改；C-2 批补：`NodeMessageLedger` + 落盘前置 + 回放两腿） |

> 读日志提示：V1 台架的 `[LEG V1/bb1] next turn started (LLM requests) = 0` 是**等待前**的
> 采样（台架顺序：先打印 evidence，再 `waitUntil` 断言下一 turn）。紧随其后的 lifecycle
> 行 `event=turn-complete detail=msgs=3 textLen=31` 与用例 PASS 即「下一 turn 已启动」的
> 实证（该断言未改动）。

## 3. 判据（全部盘上可机械求值）

### 3.1 standby 分型（D2）
`standbySince` 在场 ∧ 未冻结时，读四路旁证（任一在场 = 在飞 ⇒ 复活；全零 = 纯待命 ⇒ 维持
「不复活 + 诚实 failed + 父通知」旧语义）：

| 信号 | 数据源 | 读法 |
|---|---|---|
| ① 作用域图仍有非终态节点 | `<base>/.nebflow/flow-maps/<id>/flow-map.json`（base = projectWorkspace ∥ cwd） | `SessionResumption.openNodeCount` |
| ② 合并队列在册 | `~/.stand/merge-queue` 账本 | `MergeQueue.openSnapshot.exists(_.delegateId == id)` |
| ③ worktree 脏 | `git -C <base> status --porcelain` | `GitWorktrees.dirtyPaths` |
| ④ 分支领先基分支 | `git rev-list --count <baseBranch>..<branch>` | registry 的 `branch` / `baseBranch` |

**刻意不用**「没交过 finish 报告」做 standby 分型（纯待命记录的申报槽正是
`reportStatus=standby`，二者在这一维不可分）；该信号落在 failed 行复活腿上。

### 3.2 failed 行复活腿（任务②）
视野判据：`status=failed` ∧ `lastError` 前缀 = `SubAgentStartupRecovery.RestartLossFingerprint`
（`isRestartLostRow`）。天然失败（LLM 网络错等，无前缀）永不进视野。有界：任务级预算
`DelegateRevivalMaxPerTask`（复活即 `markRevived` 翻 running+清指纹）/ boot cap / kill-switch；
墓碑压过一切。

### 3.3 收尾腿（V5/D8）
`任务行 status=completed` ∧ `source!=subtask` ∧ `registry.workReport 非空` ∧
`registry.reportStatus 缺席` ⇒ 把 workReport 投父持久邮箱（identity=taskId，
与主投递腿同幂等键）→ 成功后记录置 `reportStatus=finish`（一次性）。载体闸：仅邮箱轨
（`resources` 在场）启用；父会话已删 ⇒ 不投不落态并如实记账。

### 3.4 断点台账（V4/D6）
`<dataRoot>/sessions/<sid>/revival-checkpoint.json`，由转录纯函数推导（游标 = 转录条数、
已完成 assistant 回合、tool_result 块数、最近 5 个回合首行），复活时落盘并把读数拼进
nudge（`Completed-work checkpoint … must NOT be redone`）。

### 3.5 收殓回执重感知（D1）
- 投递结果不再被丢弃：`MailResult.Failed` ⇒ 行文案写「parent notification FAILED (cause)」，
  成功 ⇒ 「delivered to parent」；另有 `[startup-recovery] notify receipt …` 日志行 +
  ChainStore `delegate-terminal-delivery` 台账行（可观测回执）。
- 持久提醒账：`<dataRoot>/sessions/<parentSid>/restart-loss-ledger.json`（每条 taskId /
  投递时刻 / 重述计数）。**压缩完成处重述**：全量压缩 `AgentProcessing` 装态点 +
  乒乓换入 `AgentPingpong` 换入点，各一行 `RestartLossLedger.restatementSync(sid)`——
  未确认的收殓通知以 `<system-reminder>` 重述回上下文，抹除后仍可被父会话重新感知。
  有界自清：每条 ≤2 次、24h 剪除、同 taskId 重投重置。

## 4. 回归（不回退既有正确语义）

| 既有语义 | 守卫 |
|---|---|
| cancel 墓碑永不复活 | `eligibility` 墓碑闸排在分型之前（`DelegateRevivalSpec` V9/V14） |
| 纯待命不复活 | 分型全零 ⇒ 旧文案口径（`DelegateRevivalSpec` V10） |
| ready-to-merge 分诊 | `sweep` 未改（rtm 行仍单独分诊，`ready-to-merge` 不进分型） |
| 冻结行单独分诊 | `sweep.frozenRows` 未改；`standbyGate` 显式 `frozen.isEmpty` |
| boot cap / 并发护栏 / kill-switch | `DelegateRevivalBootCap` / `DelegateRevivalConcurrency` / `DelegateRevivalEnabled` 未动；kill-switch off ⇒ failed 行也不进视野（逐字节回本批前） |

## 5. 未做 / 开放

- ~~V3（node 消息持久化，D5）不在本节点验收面，仍红。~~ **C-2 批已闭环**：V3b/V3c 转绿，
  新增 `NodeMessagePersistenceSpec`（S1/S2/S3）与 `NodeMessageReplayQueueSpec`（S4），
  读数见本目录 `V3.log`、`NodeMessagePersistence.log`、`NodeMessageReplayQueue.log`、
  `regression-A.log`、`regression-B.log` 与设计/取证件 `C2-node-message-persistence.md`。
- D3（运行期 watchdog 无复活腿）未做：本批只改 boot 链（watchdog 仍是收殓腿）。
- D7（salvage 只搬末条 assistant 文本）未做。
- 压缩处重述只覆盖 `AgentProcessing`（全量）与 `AgentPingpong`（乒乓）两个装态点；
  `dream`/`fast-micro` 腿若日后成为抹除源需补接（当前不进上下文替换面）。
