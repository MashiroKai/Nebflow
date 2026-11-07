---
type: design
status: in-progress
date: 2026-11-04
---

# scala db 接线第一阶计划（六宿主 + 九静默面）

> **一句话**：作者 11-04 15:10 令「接下来db线开始尝试接线吧」——本件是 db 库本体（main `22557aa08`，47/47 绿）接进 scala 运行时的**第一阶接线计划**。上游三件已亲读：实现报告（[[20261104_135608_scala-db-module-impl-v1__delegate-nebflow-ebb64e95]]）、设计方案 v1（[[20261104_121502_scala-db-module-design-v1__delegate-nebflow-006825e8]]）、核心导航（`20261103_212253_scala-core-agent-code-map`，有 1 处证伪前科——本文所有 file:line 均已亲读核实，实测基线 = 本 worktree HEAD `eaa1b786e`）。

## 0. 总原则（先于一切）

1. **接线是增量 + best-effort**：db 任何故障（open 失败/写失败/锁被占）⇒ WARN + 回落现状行为（内存/旧档），**绝不阻断宿主主链**。这是第一阶的安全边界。
2. **生产激活机制**：装配点（GatewayMain/NodeEngine）不在本阶解禁面 ⇒ 宿主**自开** `DbHost.forRoot(PathUtil.dataRoot)`（best-effort）。判别式 = 默认根判别（用生产默认路径构造 ⇒ 自开；传临时路径 ⇒ 不开）+ 显式注入参数（测试传 `Some(handle)`）。现有测试零改动零回归。
3. **双读过渡（D-11 第一阶形态）**：旧档为主读面；db 为**写穿影子 + 坏档恢复源**（会话 UI 面/调度面）；事件流 db 增量并行写（KernelEvent 形）；待审单 db 成为主面（旧 pending-asks.json 兼容双读，boot 对账沿用）。
4. **收编（D-5）≠迁移**：九静默面改三态语义（Absent/Corrupt/Present）+ `db.Quarantine` 隔离 + WARN，坏档字节保全，禁「坏==无」。
5. pilogic 包**零改动**（只 import `KernelEvent`）——增量轮并行在飞。
6. 每步小 commit、每步 `sbt compile + test` 全绿；任何回归立即停手报告。

## 1. 新增文件（1 个）：`src/main/scala/nebflow/db/DbHost.scala`

按数据根的进程内共享句柄注册表（`INSTANCE.lock` ⇒ 同根同进程恰一 handle；宿主散布在 core/agent/llm 三包，必须共享）。**零 `import nebflow.*`**（db 卫生判据 V7/R20 保持绿）；dataRoot 由调用方传入。best-effort `openBestEffort`：失败 ⇒ WARN + None；JVM shutdown hook 优雅 close。附单测。

## 2. 逐宿主接线表（commit 顺序 = 优先级）

| # | 宿主 | 接线内容 | 激活方式 |
|---|---|---|---|
| C2 | DispatchNotify（冷却 guard 档，#144 头条病灶） | `failedGuard`/`cancelledGuard` 窗口态写穿 db `writeNotifyGuard`（source=`dispatch-notify:failed:<proj>` / `:cancelled:<proj>`）；构造后首用前经 `recoverNotifyGuardConservative` 重水合（重启冷却不归零 ⇒ 不重发风暴）。markSent 已持久（notifySentAt）不动 | `forEngine`（生产唯一工厂，NodeEngine:326 调用；测试直构不走它）自开；直构参数 `guardDb: Option[DbHandle] = None` = 现状 |
| C3 | PendingAskLedger + InteractionHub（待审单落库，D-8） | `record` → `openPending`（三类 kind 全记）；`remove` → `closePending`；boot 由 `recoverPendingD8` 重水合：**permission 重装回 hub**（数据面恢复不复活会话身份）、**ask 过期关闭**（沿用 expired-orphan 广播+回执+待办销账语义）；旧 pending-asks.json 保留双读（对账兜底） | PendingAskLedger 默认路径判别自开 + `db: Option[DbHandle]` 注入缝 |
| C4 | SessionStore + PendingAskLedger（九静默面收编，D-5） | 逐处改三态：Corrupt ⇒ `Quarantine.quarantine` + WARN + 按三标签处置（取证面②：隔离后按缺席起步；索引派生面③：隔离后重建/孤儿恢复兜底；待审不可再生①：隔离+空账起步+审计） | 无需 db handle（Quarantine 纯文件操作）——收编语义独立于 db 写回路 |
| C5 | FlowMapEventLog（事件流 + KernelEvent 对接，P5） | `append` 追加腿并行写 db：`pilogic.KernelEvent`（type/occurredAt/sessionId/seqInSession/payload）→ `appendEvent(event, dedupKey)`；旧 JSONL 主读不动；`readEvents` 读回路供测试+恢复面 | FlowMapEventLog 无实例态（object）⇒ 惰性自开（首次 append 时） |
| C6 | ScheduledTaskStore（待办调度面） | 写穿影子：`saveTasks`/`addTask`/`upsert` → `appendScheduleTask`；`deleteTask`/`removeByName` → `tombstoneScheduleTask`；`loadUnlocked` 坏档（decode 失败）⇒ 隔离 + **db `liveScheduleTasks` 恢复读**（真实写读回路） | baseDir 默认根判别自开 + 注入缝 |
| C7 | HistoryArchiver（快照面） | `archiveCompaction` → db `writeSnapshot`(before/after) + `appendCompactPoint` + `readSnapshot` 读回校验（WARN 不阻断）；旧 before/after.json 不动 | `fileSystem(sessionsRoot)` 默认根判别自开 |
| C8 | SessionStore UI 面（会话消息流第一阶回路） | `saveUiMessages` 写穿 db `appendMessage(ui)`（批量语义：以 save 为界追加新帧）；`loadUiMessages` Corrupt ⇒ 隔离 + **db `readSession(ui)` 影子恢复读**（渲染史不再永久销毁）；LLM 面留 C8 边界外（下阶） | 构造注入缝（SessionStore 测试全用临时根 ⇒ 零波及） |

HealthMonitor（provider-avoid，D-10 另半）：视 C2 完成度与构造点风险决定本阶或下阶（构造点 SharedResources/GatewayMain 不在解禁面；测试自开风险高 ⇒ 倾向下阶，报告写明）。

## 3. 九处静默面实测清单（亲读核实，行号基线 `eaa1b786e`）

| # | 位置 | 现状 | 处置标签 |
|---|---|---|---|
| 1 | SessionStore.scala:395 | 索引整档 `decode[Json].getOrElse(Json.obj())` | ③ 派生面：隔离+孤儿恢复重建 |
| 2 | SessionStore.scala:397 | 索引 sessions `.getOrElse(Nil)` | ③ 同上 |
| 3 | SessionStore.scala:398 | 索引 folders `.getOrElse(Nil)` | ③ 同上 |
| 4 | SessionStore.scala:513 | 孤儿恢复首条消息 `.getOrElse(Nil)` | ② 取证面：隔离+按缺席 |
| 5 | SessionStore.scala:556 | legacy 迁移 `.getOrElse(Nil)` | ② 同上 |
| 6 | SessionStore.scala:605 | meta sidecar `decode[Json].getOrElse(Json.obj())` | ② 同上（sidecar 无则 folder 缺席） |
| 7 | SessionStore.scala:1480 | UI 面 `.getOrElse(Nil)`（R1 病灶：坏档→回填缓存→整档覆盖销毁） | ②+C8：隔离+db 影子恢复读 |
| 8 | PendingAskLedger.scala:82 | `parse.map(...).getOrElse(Nil)` | ① 不可再生：隔离+审计+空账（db 主面接管） |
| 9 | PendingAskLedger.scala:83 | `Try(...).getOrElse(Nil)` | ① 同上 |

**与上游文档的差异记录**：设计件写「:395-397 三连」——实测 395/397/398 三处（396 是 `activeId` 字符串缺省，非空表折叠，不属静默面）；设计件未列 :605（meta sidecar `Json.obj()` 形）。合计仍为 9 处，构成与设计件记录有微差，以实测为准（导航件证伪前科纪律）。

## 4. 测试计划（验收②）

- `DbHostSpec`：同根单句柄、best-effort、close。
- `DispatchNotifyGuardPersistenceSpec`：冷却开启 → 句柄关闭(模拟重启) → 重开 + 新构造 DispatchNotify → 冷却窗仍在（guard 档恢复）⇒ Suppress 不重发；markSent 跨重启生效不重发。
- `PendingDbWiringSpec`：权限单开单 → 重启 → 重装回 hub 且可答（数据面）；ask 挂起 → 重启 → 过期关闭（expired-by-restart 终态行 + 广播/回执语义保持）。
- `SessionStoreThreeStateSpec`：坏 UI 档注入 ⇒ Corrupt 可辨（隔离档在场 + WARN）+ db 影子恢复；坏索引 ⇒ 三连不再静默。
- `ScheduledTaskStoreDbSpec`：任务写穿；坏档 ⇒ 隔离 + db 恢复读。
- `FlowMapEventLogDbSpec`：append → readEvents 读回 KernelEvent 形（对拍 wire 字段）；dedupKey 在场。
- 存量全量回归：db 47 + pilogic 22 + 全仓 suite 不许红。

## 5. 边界（本阶不做，报告写明）

1. LLM 消息流/会话流完整迁移（D-11 完整双读切换）——下阶。
2. OS 级 kill -9 / 跨进程双开实测——chaos 专用批。
3. HealthMonitor provider-avoid（若本阶不做）。
4. 其余 12 处裸写（R20 清单）——不在六宿主面。
5. db 遗忘刀 sweep 调度接线（Retention 默认 24h 位）——库面已有，宿主调度下阶。

## 6. 风险与对策

- ** INSTANCE.lock 同根争用**：宿主共享 DbHost 单句柄；测试一律临时根。
- **生产首次自开遇上数据根无 db 树**：SeqCounter 空树起步 = 全新根，正常。
- **写穿影子与主档漂移**：影子 best-effort，恢复读仅坏档时启用 ⇒ 漂移不改变正常路径行为；报告列差异语义。
- **pi-logic 并行增量轮动 build.sbt**：本阶预计零 build.sbt 改动（db/pilogic 依赖已在 main）；若必须动，合并时正常处理冲突。
