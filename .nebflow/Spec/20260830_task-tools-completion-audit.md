# #15 任务工具补齐 Backend 侧核对表

日期：2026-08-30
分支：feat/task-tools-b（worktree `~/Claude code/.nb-worktrees/nb-task-b`，基线 main @3c2e0143）

## 核对结论

**Backend 侧全链已存在，零产品改动，仅补 1 个测试钉。** 任务书基于旧基线（写书时 @a8f6b94b assignee 尚未入库），现 main @3c2e0143 已含任务工具重做批次的完整归属语义。

## 交付核对表（六规格 + 三条 vs 现状 vs 改动）

| # | 规格 | 现状（代码证据） | 改动 |
|---|------|------------------|------|
| ① | teamId+memberId 归属字段+事件载荷含两字段 | `Task.teamId: Option[String]`（TaskModel.scala:122）+ `Task.assignee`（:128）尾加默认零迁移；create 从 scopeKey 提取（TaskStore.scala:181-211）；update assignee 三语义「非空设 trim/空串清/缺省保持」+assignee 事件（:314-318, :336-341）；事件载荷 teamTaskListUpdate {type,team,tasks} tasks 全量 Task JSON（ConfiguredCodec.derived 全字段，TaskToolHelper:33-44） | **补钉**：TeamTaskToolsSpec 事件断言扩展为 teamId+assignee 双字段显式断言 |
| ② | 不再有待办（needs_confirmation 移除） | TaskStatus 纯四态 Pending/InProgress/Completed/Failed（TaskModel.scala:11-12）；needs_confirmation 仅 codec 兼容映射→Completed（:39）；dismissed/cancelled→Failed（:40-41）；TeamTaskUpdateTool 严格四态解析（:152-154，needs_confirmation 被拒） | 零改动（grep 断言：src/main 残留仅注释+兼容映射，无状态语义） |
| ③ | Nebula 不配任务工具 | AgentCore.fixedToolsFor Nebula 分支 = BaseTools+Issue+FlowExecute+NebulaOrchestrationTools（:1959-1971），**无 TeamTaskTools**；team 分支全量注入（成员机制默认） | 零改动 |
| ④ | 任务统一 Nebula 会话显示 | 任务提醒已从 Nebula 切到 team 会话（reminders.scala:101-105「moved from Nebula to team members」，gate 上移 AgentCore） | 零改动（属于任务重做批次已完成项） |
| ⑤ | TTL 6h/2d | TaskStore.scala:44-65：CompletedTtl 6h / ActiveTtl 2d，判据 createdAt/completedAt，isExpired 纯函数纯磁盘 | 零改动 |
| ⑥ | pending 方框 | 前端渲染项（后端无状态改动面）；team 四态矩阵 pending/in_progress/completed/failed 已就位 | 零改动（后端） |
| ⑦ | 无取消打回归档 | core/task 无 Cancelled case（grep 零匹配）；dismissed/cancelled codec 兼容→Failed | 零改动 |

## 验证

- 定向 spec：TeamTaskStoreSpec **17/17** + TeamTaskToolsSpec **15/15**（含新双字段钉）Failed 0
- 全量：待补（/tmp/nb-taskb-fulltest.log）
- V2 grep 断言：`needs_confirmation` src/main 残留=仅注释+codec 兼容映射；`Cancelled|Dismissed` core/task 主代码零匹配
- 变更面：+3/-1（仅测试文件 TeamTaskToolsSpec.scala）

## 可复用模式

「任务书缺口描述 ≠ 代码现状」——接线/字段类缺口先 grep 调用点+测试覆盖定性（已存在 → 补钉 or 零改动核对表），本次与 skip-persist 批③同模式。
