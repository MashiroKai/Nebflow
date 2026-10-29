# #822 复活批（Schedule 双面化）单口合并报告（delegate/delegate-nebflow-cd4d977f → main）

合并执行：stand 收尾节点 2026-10-29 18:0x，`git merge --no-ff`，merge commit 见 main。
本批 = 「Schedule 定时工具双面化 + 主动性六条」（#822 复活批，作者令 2026-10-29 12:00；
批报告见 `reports/20261029_schedule-revival-batch-report.md`）。

## ① 合并基线漂移与冲突

- 分叉点 `5c2ad9b57`（merge-base）→ main 侧推进 58 commits（含 `9df0820d6` schedcard 批）。
- 全 14 个批文件中仅 `ScheduledTaskActor.scala` 冲突（1 处）；其余文件（model/store/tool/
  reminders/gateway/ConditionEvaluators/SessionToolActivity + 三 spec）干净合入，main 侧
  零触碰。

## ② 语义重叠：#822 复活批 vs schedcard 批（同日两批都动触发注入面）

两批在 `ScheduledTaskActor` 的**触发注入段**语义重叠：

- **schedcard 批（main，9df0820d6，14:53）**：触发落盘带注入标记
  （`injected=true, source="schedule", eventType="trigger"` + `schedule` 卡片载荷）；
  广播帧换注入 `user` 帧（裸 `userMessage` 死帧收口）；作者令「定时任务卡片防退化 +
  stand 配色」。前端 `buildInjectedRow` 有 `source==='schedule'` 专属卡分支。
- **#822 复活批（分支，3e2970bf2）**：把触发注入重构成多写点（单任务 fireTask、
  ⑥同窗合并 fireMergedGroup、待办 digest surfaceTodos 共用 helper `injectExternal`），
  helper 仍是**裸 User 行 + 死帧**形态（分支基于 10-24 残稿，未见过 schedcard 批）。
- **冲突实质**：main 的标记改动只落在旧单写点 `triggerTask` 上；分支把该写点拆成了
  helper + 多写点。若按分支原样并入 → schedcard 批的防退化标记**全线退化**（新写点
  全部落裸行 + 死帧，作者报障的绿泡形态回归）；若按 main 原样保留 → 分支的
  双面化/六条落不进来。两批是**互补而非对立**，逐字两侧语义都保留地融合。

## ③ 合流裁定（逐条）

1. **标记契约 = schedcard 逐字保留**：`injectExternal` 重命名 `injectScheduleCard`，
   新参 `scheduleMeta: Json`；落盘 `injected=true, source="schedule", eventType="trigger"`
   + 卡片载荷；广播注入 `user` 帧。`scheduleMeta(task)`（单任务，原 schedcard 逻辑）+
   `mergedScheduleMeta(tasks)`（⑥同窗合并组：全组一致字段才落键，`triggerAt` 取组内
   最早，附 `merged:true`/`count`）。
2. **待办 digest 腿标记为 `source="todo"`（`injectTodoDigest`）**：待办汇总与定时任务
   触发呈现语义不同（「今天要办的事」清单 vs 到点单卡），刻意**不串** schedule 专属卡，
   走通用注入泡（「Todo · Digest」标签）。裸 User 行/死帧在待办腿同样会被 schedcard
   批修掉的绿泡形态接住 ⇒ 同款标记纪律（合并新增裁定，非两批原文）。
3. **词表三处同源登记 `todo`**：`InjectionAttribution.BackendNamedSources` +
   `NotificationHeader.KindLabels("todo"→"TODO")` + 前端 `INJECTED_SOURCE_LABELS.todo`
   （契约门三硬门同批更新：InjectionSourceContractSpec ① pin、NotificationHeaderSpec
   逐字表、TodoScheduleSpec 断言从死帧 `userMessage` 改注入 `user` 帧 + 落盘行带标记
   `source=todo`）。
4. **TodoScheduleSpec「userMessage」断言勘误**：该断言钉的是 schedcard 批收口令前的
   死帧形态，与 main 上 `ScheduleCardContractSpec③` 的 `!actorScala.contains("
   \"userMessage\"")` 直接互斥（两 spec 同跑必一红）——按收口令改注入 `user` 帧断言
   （spec 内留勘误注记）。
5. 分支其余语义（丢失修复 Mutex、错过补偿快进、条件评估器、降频、⑤摘要、⑥合并、
   待办存储/工具面、说法加宽）与 main 零重叠，逐字合入。

## ④ 测试读数（合并后 main 上复跑）

| 面 | 读数 |
|---|---|
| 本批自带 spec（`nebflow.core.scheduler.*`：Revival 20 + Todo 18 + Initiative 13 + SchedCardContract 9） | 60/60 绿 |
| 调度族回归（ScheduleV2 + SystemReminders + PluginSurfaceReminder + FreezeSchedule + ScheduleTool + ScheduleActions） | 125/125 绿 |
| 词表契约钉（InjectionSourceContract + NotificationHeader + UiMessageSchedule + InjectionIntakeContract + ReminderWiring） | 27/27 绿 |
| 合计 | **212/212 绿** |
| 编译 | sbt compile success |

预存在红（HEAD 同红，本批未碰）：schedcard 批 commit message 已列（SubAgentInboxMirror
③-2 等 repo 级环境漂移项）。

## ⑤ 工作树收尾

- 分支 `delegate/delegate-nebflow-cd4d977f` 已并：worktree 无未落盘改动（净）。
- main 工作树仅剩与本合并无关的 evidence/ 截图 mtime 漂移（takeover-fourfix 批的
  既有未暂存项，合并前已在场），未纳入 merge commit。
- 不 push（纪律红线）。
