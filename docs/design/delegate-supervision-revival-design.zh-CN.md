# delegate 监督链自动恢复机制设计（boot-time 复活腿）

状态：**实施中**（设计先行落盘；实现与验证结论回写本档「实现纪要/验证纪要」两节）

## 0. 背景与问题（作者令 2026-10-22）

> 「为什么各工作没有自动重启？我们在 9.19 的版本里各节点是有自动重启和监督恢复机制的，
> delegate 也应该要有自动恢复自己工作的能力呀。监督链应该是 subagent → agentflow →
> delegate → delegate → 主 agent。」

实证（2026-10-22 两次引擎重启）：在飞 delegate 全部被标记 failed（`[task lost: crash]`）——
`SubAgentStartupRecovery` 启动清扫把「进程死亡时在飞」一律**收殓终态化**，父会话收到的是
失败通知而不是续作结果。同日 `MergeQueueBootSweep` 对 ready-to-merge 停靠 delegate 的
boot 恢复生效了（fe4f891e 被自动收口），唯独 running 中的 delegate 没有被复活。

## 1. 考古结论（9.19 起的既有监督/恢复面，逐层盘点）

| 层 | 对象 | 进程内监督 | 引擎重启后 | 锚点 |
|---|---|---|---|---|
| L0 | 主 agent 会话 | — | F2 注入队重放（`RecoverPersistedQueues`） | `AgentActor.scala:937` |
| L1 | agentflow 节点（项目图） | NodeEngine watchdog + `TaskStuckWatcher` | `ProjectCrashRecovery.recoverAll`：`bootRecoveryClaim` 三分类 + 慢段 `bootRecoveryStart` **transcript 断点 rehydrate** | `ProjectCrashRecovery.scala`（9.19 已有，crash-recovery 批 2026-09-07） |
| L2 | delegate 作用域图（delegate 自建链） | `DelegateFlowHosts.launchScanner` | `ProjectCrashRecovery.recoverDelegateScopes`：重挂宿主 + 同判据认领 rehydrate | `ProjectCrashRecovery.scala:255`（N1 刀1） |
| L3 | **delegate 会话**（Delegate tool → SubAgentTaskStore） | `BackoffSupervisor`：crash respawn + **transcript checkpoint 续跑** + 预算重挂 | ❌ **只收殓**（`SubAgentStartupRecovery.recoverOrphans` → failed + F2 通知父） | `BackoffSupervisor.respawn`（`loadMessagesForSession` → `initialMessages` → continue nudge）；`SubAgentStartupRecovery.scala` |
| L4 | subtask（Subagent tool） | `BackoffSupervisor` | 收殓（轻量任务，父可廉价重派） | 同上 |
| 特例 | ready-to-merge 停靠 delegate | MergeQueue | `MergeQueueBootSweep`（重挂队列 + 合并轮复活通道） | `MergeQueueBootSweep.scala` |

**缺口 = L3**：delegate 的四件恢复现场在盘上样样俱全——任务行（SubAgentTaskStore）、
地址簿记录（DelegateRegistry：cwd/worktree/branch/project/plugins）、会话转录
（SessionStore）、工作产物（worktree/座位目录）——但 boot 清扫只做收殓，从不利用。

**9.19 版本机制溯源**：`git show 2026.9.19:...ProjectCrashRecovery.scala` 即节点轨
boot 恢复的原始实现（本批 L3 复活直接沿用其**快段认领 + 慢段 fork rehydrate +
信号量并发护栏**骨架）；delegate 进程内 checkpoint 续跑在 9.19 的
`BackoffSupervisor.respawn` 已定型（recovered messages + nudge + 预算重挂）。

## 2. 设计（L3 复活腿，挂在既有 boot 清扫内）

### 2.1 监督链语义（分层分工，杜绝双头管理）

```
主 agent（L0）
  └─ 监督 delegate 会话（L3）——进程内 BackoffSupervisor；跨重启 = 本批复活腿
       └─ 监督 delegate 作用域图节点（L2）——DelegateFlowHosts + recoverDelegateScopes
       └─ 监督 subtask（L4）——BackoffSupervisor；跨重启收殓（保持）
项目图节点（L1）——ProjectCrashRecovery（零触碰）
```

- **`SubAgentStartupRecovery` 仍是 L3/L4 boot sweep 唯一主人**：复活腿与收殓腿同一处、
  同一遍扫（先判复活资格，不合格才收殓）——无第二个 sweep、无新 watchdog。
- `ProjectCrashRecovery`（L1/L2 节点轨）不碰 SubAgentTaskStore；`MergeQueueBootSweep`
  只管 ready-to-merge（任务行不在 `findRunningTasks` 视野，与复活零交集）；
  `RestartingWatchdog` 是运行期僵死兜底，复活后的行在 registry 有活体 actor，其判据
  `!registry.contains(taskId)` 不命中——零误杀。
- **复活 ≠ 重派**：同 id（session 身份 = transcript 身份 = `delegate:<id>` 地址身份），
  从转录断点续跑，父会话最终拿到的是**续作结果**而非失败通知。

### 2.2 复活判据（全部满足才复活，任一不满足 → 落既有收殓链）

1. kill-switch 开：`Defaults.DelegateRevivalEnabled`（sys prop
   `nebflow.delegate.revival.enabled`，默认 true）
2. `source == "delegate"`（subtask 不复活：轻量任务父可廉价重派，避免长尾）
3. DelegateRegistry 记录在 ∧ `executor == 内置（nebflow）` ∧ `archivedAt` 空
   （外部执行器腿本批不复活——CLI 进程死亡语义不同，续聊是运行期 Mail 腿的事）
4. 座位目录存在：`os.exists(record.cwd)`（worktree 被清 = 复活无意义）
5. 父会话存在（`sessionStore.listSessions` 有 parentSessionId；父已删 = 无投递对象）
6. 任务级预算：`revivalCount < Defaults.DelegateRevivalMaxPerTask`（默认 2）
7. 年龄预算：`now - spawnedAt ≤ Defaults.DelegateRevivalMaxAgeMs`（默认 7 天，陈年孤儿不诈尸）
8. boot 级预算：本 boot 复活数 ≤ `Defaults.DelegateRevivalBootCap`（默认 8；
   按 spawnedAt **新者优先**，超额者走收殓——防病态转录的 LLM 洪峰）

### 2.3 复活动作（镜像 `BackoffSupervisor.respawn` 的跨进程版；同件套零新机制）

1. 任务行先行：`status=running`、`revivalCount+1`、`revivedAt=now`、`lastError` 清空、
   `statusAt=now`（先写行再 spawn——复活半途再崩，下一 boot 看到的是诚实的 running 行）
2. spawn（session id 不变）：`spawnAgentActor` + `spawnSupervisedAdapter`
   （SharedResources 既有双件套，与 `spawnWithPlugins` 同点），参数从 registry 记录现读：
   座位 `projectRoot = record.cwd`、`sessionName = task.description`、depth=1、
   `initialMessages = loadMessagesForSession(taskId)`（**transcript checkpoint**）
3. agentDef = `BuiltinAgents.delegateEntry(ExecutorName)`（全部内置 delegate 同一 def，
   确定性重建）；**插件重挂**（best-effort）：`record.plugins` 非空 → 逐名
   `PluginRegistry.resolve` → `pluginMcp.acquire` → effectiveDef 授 MCP servers/tools；
   失败只 WARN 不阻塞复活（fail-open 与派发面同口径）
4. `registerAgent`（registry 活体行回写：ref + supervisorRef + root/parentSessionId）
5. `DelegateFlowHosts.mount`（delegate 自己的作用域图宿主重挂——节点 out=Nebula 的
   投递通道恢复；fail-soft 同 spawn 腿）
6. nudge 注入：转录非空 → `[system]` 续跑指令（engine-restart 措辞，BackoffSupervisor
   crash nudge 同族）；转录为空 → 重注原 prompt（fresh-start fallback 同 respawn）
7. `DelegateBudget.register`（delegate 轨 3600s wall-clock 预算重挂，超时走既有
   Cancelled 链）
8. ChainStore 审计行 `delegate-revival`（`delegate-spawn` 行族同构，带 revivalCount）
9. spawn 并发护栏：`Semaphore(Defaults.DelegateRevivalConcurrency)`（默认 2），
   慢段 fork——boot 不阻塞，同 `ProjectCrashRecovery` 慢段纪律

### 2.4 终态投递（父会话「只接最终结果」的两条腿）

- 父 live（复活 delegate 完成时父会话已起）：`notifyParentAndStop` 直投（现状）。
- 父不 live（typical：父会话等用户打开才 spawn）：`BackoffSupervisor.notifyParentAndStop`
  的 `parentRef=None` 分支**加 F2 注入队兜底 append**（`CompactionQueueStore`，
  correlationId=subagentId 幂等——`SubAgentStartupRecovery.notifyOnce` 同机制），把
  「响亮 WARN 但结果丢失」升级为「响亮 WARN + 结果持久投递」；父会话 spawn 时
  `RecoverPersistedQueues` 重放。此兜底同样惠及既有 Mail 续聊腿（同分支）。

### 2.5 与既有机制的边界表（防双头管理）

| 机制 | 管什么 | 与本批关系 |
|---|---|---|
| `SubAgentStartupRecovery` 收殓腿 | 资格不满足/预算耗尽/复活失败的孤儿 | 保持逐字（lastError 指纹、F2 幂等通知、标记腿全不动） |
| `BackoffSupervisor` | 进程内 crash respawn | 复活重建的监督链此后归它管 |
| `ProjectCrashRecovery`（L1/L2） | flow-map 节点轨 | 零触碰；复活的 dispatcher delegate 与其作用域图经各自通道重挂 |
| `MergeQueueBootSweep` | ready-to-merge 合并轮 | 零交集（任务行状态不同域） |
| `RestartingWatchdog` | 运行期重启屏障僵死 | 复活行有活体，判据不命中 |
| `DelegateBudget` | 单 delegate 3600s | 复活重挂一次（respawn 同款） |

### 2.6 已知限界（显式假设）

- 复活会话 depth 重置为 1（原 depth 不在盘上任何记录中——预算语义以 DelegateBudget
  wall-clock 为准，depth 预算从复活窗重新起算）。
- `rootSessionId` 以 `record.parentSessionId` 估计（registry 记录不存根会话 id；
  顶层 delegate 两者相等，嵌套 delegate 的根归属退化为父 delegate id——只影响台账
  归属读数，不影响投递链，投递锚是 parentSessionId）。
- 外部执行器（CLI）delegate 本批不复活：CLI 进程死亡后 `ExternalSessionRunner.continueExternal`
  的续聊语义属运行期 Mail 腿；boot 复活留待后续批（收殓+通知父的现状不变）。

## 3. 验收条件（实施后逐条回写证据）

- **冒烟红线**
  - V1 合格孤儿（delegate + registry 记录 + 座位在 + 转录在 + 预算内 + 父会话在）→
    sweep 后任务行 running、revivalCount=1、registry 有活体、nudge 已注入（stub LLM
    回 "ok" → 完成回投父 F2 队列 eventType=completed）。
  - V2 预算耗尽（revivalCount 已达上限）→ 收殓 failed + 父通知，零 spawn。
- **端到端**
  - V3 硬杀→重启→复活：真实引擎（隔离实例）派 delegate → kill -9 → 重启 → delegate
    自动复活续作 → 父会话收 completed 结果（非 failed）。
  - V4 复活后再崩→预算逐次递减→第三次 boot 收殓（failed + 通知）。
- **回归**
  - V5 kill-switch off / 非 delegate source / 无 registry 记录 / 座位缺失 / 父会话删除
    → 全部落既有收殓链，`OrphanDelegateRecoverySpec` R1–R7 零改全绿。
  - V6 `BackoffSupervisor` 既有 spec 零改全绿（F2 兜底只在 parentRef=None 分支 additive）。
  - V7 编译 + 既有 agent 面测试族全绿。

## 4. 实现纪要

落点（2026-10-22，本批全部实现完成）：

- **复活腿**：`agent/DelegateRevival.scala`（新）——资格判读 + spawn 链
  （markRevived 先行 → transcript checkpoint → spawn 双件套 → registerAgent →
  作用域宿主重挂 fail-soft → 插件 best-effort 重挂 → nudge → ChainStore
  `delegate-revival` 审计行）+ `reviveAll` 慢段（信号量 2 + fork）。`SubAgentTask`
  增 `revivalCount/revivedAt`（缺键容忍解码）；`SubAgentTaskStore.markRevived`
  专面专名（updateStatus 签名零扩——避免 defaulted 前缀重载二义性，编译实证后回退的
  取舍）。
- **sweep 编排**：`SubAgentStartupRecovery.sweep(store, sessionStore, revival)`——
  先判资格、boot cap 新者优先、慢段 fork 复活、同步段收殓、收尾 settle()
  （标记腿 + 台账对账腿）。`recoverOrphans` 旧签名保留（legacy 旁路语义逐字节）。
- **终态投递兜底**：`BackoffSupervisor.notifyParentAndStop` 的 parentRef=None
  分支加 `f2DeliveryBackstop`（F2 注入队 append，correlationId 幂等，父已删跳过）——
  「响亮 WARN + 结果丢失」升级为「响亮 WARN + 结果延迟可达」。
- **台账对账**：`core/delegate/DelegateLedgerReconcile.scala`（新）三腿——
  TaskTool complete/close flatTap 钩子（`TaskToolDef.call`）；终态记账
  （`DelegateTerminal.onTerminalCleanup` 序列内 verdictLine 邻位）；boot 对账
  （sweep settle() 内，旗后收敛）。
- **开关**：`Defaults` 五旋钮（enabled/maxPerTask=2/maxAgeMs=7d/bootCap=8/
  concurrency=2，均 sys prop 现读）。
- **接线**：`GatewayMain` wsHub 提前到 subagentCrashSweep 之前，sweep 传
  RevivalDeps(sharedResources, actorSystem, wsHub.broadcast)。
- 偏差：`updateStatus` 扩展方案因 Scala defaulted 重载二义性编译失败 → 改
  `markRevived` 专面（记录如上）；其余与设计一致。

## 5. 验证纪要

**2026-10-22 会话（delegate-nebflow-af984340 承接 2c2db017 现场续作）回写。**

- **编译**：`sbt Test/compile` 零错（首轮修 spec 三处 API 错配：`completeSync`
  返回 `Either[ToolError, String]` 纯值需包 `IO(...)`；`AgentDef` 构造补齐必需
  参数；补 `cats.syntax.all.*` 导入）。
- **跑批口径**：`sbt testOnly` 一批 13 个 spec 类 = 两个新 spec + 11 个邻族回归
  （OrphanDelegateRecovery / RestartingWatchdog / DelegateTerminal /
  DelegateAutoArchive / BackoffSupervisor×3 / SubAgentTaskStatus /
  StuckDelegateRelease / DelegateRegistry / DelegateTool）。
- **终验**：`Passed: Total 88, Failed 0, Errors 0, Ignored 2`（88 = 新增 14
  —— DelegateRevivalSpec V1–V8 + DelegateLedgerReconcileSpec L1a–L3c —— + 邻族 74）。
  关键用例证据：V1 复活链全通（行翻 running/revivalCount=1 → stub LLM 续跑 →
  父 F2 队列收 completed 携 REVIVED_WORK_DONE → nudge 注入转录）；V5 三连 boot
  复活→预算耗尽收殓；V8 台账裁定归档折叠；L1b TaskTool complete 真通路折叠面板行。
- **验证期修复**（本会话贡献）：
  1. `DelegateRevival.reviveAll` 由「fork 不收口」改为 **fork + join 收口**——
     sweep 返回时报告 revived 精确、registerAgent 落定（修 V1 报告竞态 + V5
     跨 boot 活体回注册竞态；fork 并发护栏保留）。
  2. `BackoffSupervisorEmptyBurnSpec` 断言按 **#566 判词退役**对齐（blocked →
     completed + judged-incomplete 注记 + 审计行 verdict=completed）——**main
     既有漂移**（stash 本批后 HEAD 基线同红，实证非本批回归）。
  3. `SubAgentTaskStatusSpec` 补 `PathUtil.setDataRoot` 隔离（每件用例）——该
     spec 此前跑在真实数据根上（HEAD 起 ChainStore verdictLine 即泄漏），套件
     间全局根争用挤爆 3s 等待窗；隔离后 completed 用例 0.1s 级稳定绿。
  4. L3c git 现场迁至 worktree `target/`（带唯一后缀 + finally 清理）+ 搭建
     **重建式重试**（5×300ms）——宿主侧对 TMPDIR 目录/`git` spawn 有间歇干扰
     （bisect + WatchService + forkAndExec ENOENT 栈取证；删除者未明，环境病理，
     不涉被测代码），重试后稳定绿。
- **F2 兜底沙盒注记**：`f2DeliveryBackstop` 走 `CompactionQueueStore`（全局
  dataRoot 根，与 `notifyOnce` 同机制）——生产面正确；测试面由各 spec 的
  setDataRoot 隔离兜住（Revival/LedgerReconcile/Status 套件均已隔离）。

---

# 增补：台账 ↔ delegate 面板两层状态打通（作者补充需求，同批并入）

状态：**已实施并验证**（三腿 + 增补验收 V8–V11 全绿，见 §5 终验）

## A. 问题实证（作者 2026-10-22 实测）

- **P1 台账→面板脱节**：任务面板显示 delegate 记录行；用 Task 工具把台账条目
  complete 掉，面板行不消失——两层零联动。
- **P2 假中断行残留**：今天重启后大量「实际早已完成」的 delegate 行被标
  「重启打断·已中断」且不收敛。根因链：完成收口（终态链）被重启杀死 → 行残
  running → sweep 收殓成 failed+指纹 → `markInterruptedRecords` 打
  `restartInterruptedAt` 旗 → **但收殓腿绕过了 `DelegateTerminal.onTerminalCleanup`**
  → 既有 AutoArchive（终态自动归档，`DelegateTerminal.scala:627`）不生效 → 行永驻
  现役窗、假中断徽标长存。读面判据
  （`ChainsReadFace.scala:103`：旗在 ∧ 未归档 = 中断行）无收敛出口。

## B. 设计：`DelegateLedgerReconcile`（core.delegate 新对象，三条触发腿）

分工红线：归档写点唯一（`DelegateRegistry.archive`，与既有 AutoArchive 同方法）；
旗写点唯一（markRestartInterrupted，语义不变，腿 3 是它的下游对账）；**复活行
（running/未归档/无旗）三条腿都不碰**。

### 腿 1 — 台账 → 面板（TaskTool complete/close 钩子）

`TaskTool` complete/close 成功后 → `onLedgerTerminal(entryId)`（best-effort 同步段，
fail-soft）：扫 registry 记录（taskId == entryId）：

- 对应任务行 **terminal**（completed/failed/cancelled；ready-to-merge 不算——合并轮
  在飞）→ **archive 记录**（折叠退役窗；读面判据「旗 ∧ 未归档」即收敛，旗保留作
  历史）+ 一行 info。
- 任务行**仍在飞** → 不动面板（delegate 还活着，不替它收尸）。
- 幂等：archive 已归档 = 零重写。

### 腿 2 — 面板 → 台账（终态交付自动记账）

`DelegateTerminal.onTerminalCleanup` 清理序列加一环 `ledgerNote`（record.taskId
定义时）：`TaskLedgerStore.appendNoteSync(tid, "delegate <id> 终态 <status>…")`，
复活注记读任务行 revivalCount（>0 → 「经 N 次重启复活续作」）。台账条目已删 =
notFound 吞掉 WARN。位置 = verdictLine 邻位（engine 单点，不新造第二终态记账面）。

### 腿 3 — boot 对账（与复活腿同一 sweep、收尾位；不另起 sweep）

扫「假中断候选」（旗在 ∧ 未归档）：

- **台账裁定**：record.taskId 的台账条目 ∈ {completed, closed} → archive
  （作者需求 #1/#3 的 AutoArchive 窗口延伸到 sweep 收殓行）。
- **合并佐证**：台账 open 但分支已并入基分支（`GitWorktrees.isAncestor`）→
  archive + 台账 note（「分支已并入基分支，面板行按合并事实归档」）——父零打扰，
  台账得到推进线索（补强需求 #2 反向链）。
- 台账 open 且无证据 → **旗保留**（真中断，等再派发/续聊）。
- 顺序：markInterruptedRecords 之后（旗先落、对账后收敛；同 boot 单帧收敛）。

### 人工裁定面（需求 #3）

不加新 AgentControl 动作——人工裁定 = **TaskTool complete/close**（作者的工作清单
面，complete 即裁定「实际完成」），腿 1 自动折叠面板行；误归档走既有
`DelegateRegistry.unarchive` 回滚。

## C. 增补验收

- V8 台账 complete + delegate 行 terminal 带中断旗 → 记录归档、读面中断集不含它。
- V9 revival 完成链交付 → 台账 appendNote 落「终态 + 复活注记」。
- V10 boot 对账三态：台账 completed → 归档；open+已并入 → 归档+note；open+无证据 → 旗保留。
- V11 既有旗语义回归：R6/R7（无 taskId 记录）旗照常打、不误归档。
