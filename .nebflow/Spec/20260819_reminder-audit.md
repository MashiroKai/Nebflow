# System Reminder 审计报告（2026-08-19）

> 触发：用户实测 00:00 收到 "Off-peak hours." 闲时通知，明确要求去掉。
> 范围：枚举全部 reminder 类型 → 分发范围审查 → 移除 Off-peak/idle → 实施优化。
> 实施 commit：见 feat/backend-fixes 分支 Task 3 commit。

## 1. Reminder 类型清单

| 类型 (category) | 内容 | 触发时机 | 当前分发目标 | 建议分发目标 | 处理 |
|---|---|---|---|---|---|
| `time` | "Current time: yyyy-MM-dd HH:mm +08:00" | 每个 user turn（持久化为 User 消息进历史，有 20 条剪枝） | 全部 agent | 全部 agent（时间感知有用，已持久化+剪枝） | **保留** |
| `time-context` | "Peak hours active (14:00-18:00, 3x pricing)" / **"Off-peak hours"** + "Next idle window: …" | 每个 user turn（无变更检测，恒注入） | 全部 agent | 无（用户裁定：闲时通知去掉） | **✅ 已移除** |
| `schedule` | "Pending schedules (N): - [HH:mm today] …" | user turn + 本会话有待触发定时任务 | 全部 agent（按 session 过滤，worker 会话天然为空） | 同左 | **保留** |
| `devices` | "Devices changed:\n…" | user turn + devices 快照变更（cache v2 变更检测） | 全部 agent | 全部 agent（跨设备任务需要） | **保留** |
| `sessions` | "Active sessions changed:\n…" | user turn + 活跃会话快照变更 | 全部 agent | 全部 agent | **保留** |
| `environment` | "Environment changed:\n…" (chat width/PID/port) | user turn + env 快照变更 | 全部 agent | 全部 agent（仅变更时触发，内容小；worker 走 rebuild 路径天然不触发） | **保留**（评估结论：低频小内容，不需裁剪） |
| `tasks` | "Current tasks:\n## Current Tasks …"（按 session 过滤的任务清单） | user turn + 本会话有活跃任务 | 全部 agent（含 worker） | **根 agent（depth==0）**；worker 任务由父 Delegate/SubTask 指令承载，不读任务存储 | **✅ 已加 depth 门控** |
| `language` | "Language changed: respond in X" | user turn + language 快照变更 | 全部 agent | 全部 agent | **保留** |
| `gitBranch` | "Git branch changed from \"A\" to \"B\" (in worktree)…" | git 分支变更（首次检测静默） | 全部 agent | 全部 agent（文件操作安全必需） | **保留** |
| `maintenance`（MaintenanceService） | "Memory maintenance checkpoint — N delegated tasks completed…" | depth==0 + delegate 计数每 10 次 | 根 agent（已 depth==0 门控） | 同左 | **保留**（已天然正确） |
| save-memory / compact 提示（CompactService） | 压缩前记忆维护指令 / 压缩摘要指令 | 生命周期节点（两阶段压缩） | 全部 agent | 同左（生命周期必需，非噪声） | **保留** |

## 2. 分发机制现状（AgentCore.pipeLlmCall 公共路径）

- 所有 reminder 经 `SystemReminders.collectAllIO` 在**每个 agent 的 user turn** 注入（Nebula/team lead/team member/worker 共用一条路径）。
- cache v2 变更检测：devices/sessions/environment/language 只在**快照变更**时产生 reminder（生命周期节点 rebuild 后 change 为空 → 不注入）。
- `tasks`/`schedule` 按 session 过滤——只有**本会话**有任务的才注入。
- `time` 是唯一持久化进历史的消息（20 条上限剪枝）；其余为 per-turn 请求级注入。

## 3. 关键发现

1. **Off-peak 是唯一"恒注入"的噪声源**：`time-context` 无变更检测，每个 user turn 必然产生（"Peak hours active" 或 "Off-peak hours" + idle window），且对 agent 行为无任何指导价值（provider 计费时段与 agent 决策无关）。这是用户 00:00 收到通知的直接来源。
2. **worker 的实际噪声已很低**：fresh spawn 走 `isLifecycleRebuild` 路径（cachedSystemStable 空）→ 变更类 reminder 全为空；worker 会话任务目录通常为空 → tasks/schedule 为空。实测噪声主要来自根 agent 的 time-context。
3. **Current tasks 本就按会话隔离**：`renderForPrompt(sid)` 只渲染本会话任务，不存在"广播给全部 agent"的问题。本次仅加 depth 门控把 worker 排除（worker 的工作指令来自父 agent，不读任务存储）。
4. **UsageTracker.loadPattern 读取可移除**：`time-context` 是 loadPattern 唯一消费方；pattern 文件仍由 NebulaMemoryHook 的 `analyzePattern()` 维护写入，不影响其他功能。

## 4. 实施改动

- `src/main/scala/nebflow/core/reminders.scala`：
  - 删除 `timeContextReminder` / `nextIdleWindow` 两函数；
  - `collectAllIO` 移除 `UsageTracker.loadPattern()` 读取与 time-context 组合；
  - `collectAllIO` 新增 `depth: Int = 0` 参数，`tasks` reminder 加 `depth > 0 → None` 门控；
  - 文档注释同步（审计结论）。
- `src/main/scala/nebflow/agent/AgentCore.scala`：`collectAllIO` 调用传 `depth = depth`。
- `src/test/scala/nebflow/core/ScheduleV2Spec.scala`：移除 time-context 断言，新增 "time-context 永不注入" + "tasks 按 depth 门控" 两组测试。

## 5. 验证

- sbt test 全量（含 ScheduleV2Spec 11 用例）+ agent 包回归。
- 运行实例行为：user turn 注入的 reminder 不再含 "Off-peak hours." / "Peak hours active" / "Next idle window"。

## 6. 遗留/可选

- 若产品层面认为 "Peak hours（3x pricing）" 对用户有成本提示价值，可考虑移到**用户可见 UI**（非 agent 上下文）——本次按用户裁定完全移除，不进 agent 上下文。
- `tasks` 门控目前是 `depth==0`（Nebula + team lead + team member 均保留本会话任务清单）。若用户希望更严格（仅 Nebula 主会话），改一行判定即可。
