# R1 cancel 持久性三修收口批报告（三路径 spec 钉死，映射表 P1 第二次派发收尾）——2026-10-29

批：R1 cancel 持久性三修**收口**（依据 `docs/design/20261028_213056_delegate-dual-mode-redesign__delegate-nebflow-192d10a5.zh-CN.md` §1 P1 行 + [[20261028_171800_审计报告二五机制__delegate-nebflow-a13975e4]] §1.3；第二次派发——前次 429 风暴中断，其代码面 07159c269 已随 3f2bc7aaf 落 main）。
分支：`delegate/delegate-nebflow-109d8ab6`（基点 = local main e307d6115，含前次批全部成果）。纪律：独立 commit，不 push，在产零触碰。

## 一、前次批遗产清点（本次派发开工时的盘面）

07159c269「R1 三修 + 会话死亡台账结算」已落 main 并含 16 例 spec（R1CancelTombstone T1-T5 / R1CancelReceipt C1-C5 / R1StatusMiss B1-B3 / SessionDeathSettle S1-S3）：

| 缝隙 | 前次批状态 | 本次派发核验 |
|---|---|---|
| A（cancel fire-and-forget） | ✅ 墓碑先行 + CancelReceipt.awaitTerminal 有界确认 + 超窗降级兜底（AgentControlTool.scala doCancel；CancelReceipt.scala 新件） | 复核通过 |
| B（updateStatus 静默 no-op） | ✅ 命中才写 + WARN [task-status-miss] + recentStatusMisses 留痕（SubAgentTaskStore.scala:173-201） | 复核通过 |
| C（无墓碑、复活不查指纹） | ⚠️ **部分**：DelegateRegistry 墓碑字段 + DelegateRevival.eligibility 七闸加一道（跨进程腿 T4 已钉）；**进程内 respawn 腿零防护、boot sweep 腿无 sweep 级 spec** | 本次补齐 |

## 二、本次派发的收口内容（缝隙 C 三路径补全）

### 根因（进程内腿为何漏）

`BackoffSupervisor.respawn`（crashed / empty-completion 两腿共用）不读墓碑。runbook 实态：`AgentControlTool.doCancel` 回执超窗的**降级兜底只 Stop child**（监督者被假定已死、不可盲杀）——监督者若只是慢（mailbox 拥塞、长 turn），child 的 `Terminated` 照常走进 respawn：把已 cancelled 的行翻回 `restarting` 并 spawn 新内核 = 取消被进程内复活腿无声推翻。`DelegateRevival.eligibility` 的七闸只管 boot 腿，管不到本腿。这与报告二 §1.3「取消意图持久性全押任务行一行」同构：行翻 cancelled 之后仍有一条进程内路径能把它翻回去。

### 修法（文件:行）

1. **进程内 respawn 墓碑闸**：`BackoffSupervisor.scala:615-661`——respawn 头部读 `DelegateRegistry.find(subagentId)` 墓碑，在场 ⇒ 不写 restarting、零 spawn、直接落 cancelled 终态（父 ExternalEvent 照常，payload 指名 tombstone refusal）；无记录/读失败 = fail-open 照旧 respawn（读面故障绝不阻断崩溃恢复）。原 respawn 链逐字抽为 `respawnBody`（:663-806，零行为差），两腿（crashed :604 / empty-completion :532）自动同闸。
2. **AgentControl restart 同闸显式拒绝**：`AgentControlTool.scala:1089-1133`——doRestart 在写 restarting 前查墓碑，在场 ⇒ Left ToolError 指名 cancel tombstone（不再「回了 restarting 又被监督者闸翻回去」）。
3. **boot sweep 腿 sweep 级 spec**：`DelegateRevivalSpec.scala` V9——墓碑孤儿（行仍 running，实证僵尸 e25cd0c2/b5646a58 形态）经 `SubAgentStartupRecovery.sweep` → eligibility 拒绝 → 收殓链落 failed、父 F2 通知、零 spawn、skip reason 指名 cancel tombstone。
4. **进程内腿 actor 级 spec**：`BackoffSupervisorTombstoneSpec.scala`（新件三例）——R1 崩溃注入（child Stop → Terminated）+ 墓碑在场 ⇒ 零 spawn、行落 cancelled（绝不翻回 restarting）、父通知指名 tombstone；R2 对照（有记录无墓碑 ⇒ respawn 照旧、闸沉默）；R3 doRestart 墓碑拒绝且行零改写。

## 三、验证证据

- `sbt Test/compile` 绿（**915** test 源文件；main+test 全量增量编译零告警失败）。
- 三路径 spec 族（新钉）：
  - `BackoffSupervisorTombstoneSpec` R1（墓碑在场崩溃 → respawn 拒绝：零 spawn、行落 cancelled、父通知指名 tombstone）/ R2（对照：无墓碑 → respawn 照旧、闸沉默、零终态通知）/ R3（doRestart 显式拒绝、行零改写）——**3/3 绿**。
  - `DelegateRevivalSpec` 全族 **9/9 绿**（V1-V8 既有 + V9 新钉：墓碑孤儿经 sweep 拒复活、收殓落 failed、skip reason 指名 cancel tombstone）。
  - `R1CancelTombstoneSpec` T1-T5 5/5 绿（跨进程 DelegateRevival 腿资格拒绝 = T4）。
- R1 前次批 spec 回归全绿：`R1CancelReceiptSpec` C1-C5、`R1StatusMissSpec` B1-B3、`SessionDeathSettleSpec` S1-S3（随 12 类族跑通过）。
- delegate 监督链既有 spec 族回归：12 类联跑 **53 passed / 0 failed / 0 errors**（2 ignored = AgentControlE2ESpec C1/C2 既有 @Ignore 基线件，与本批无关）。覆盖：BackoffSupervisorCancelSpec、DelegateRevivalSpec、BackoffSupervisorRestartDelaySpec、BackoffSupervisorEmptyBurnSpec、OrphanDelegateRecoverySpec、SubagentCrashRecoverySpec（缺口② 6 例）、AgentControlE2ESpec（AC-T1-T3）。

## 四、其他事实

- 前次派发死因（429 风暴循环退化）后其代码已自愈落 main——本批按「从断点读盘面状态续作」纪律清点遗产后只补缺口，未重放任何已完成工作。
- 墓碑闸读面在 respawn 每次触发时多一次 registry 小文件读（respawn 低频事件，代价可忽略）；subtask 轨无 registry 记录 ⇒ 闸恒沉默，既有语义零变化。
