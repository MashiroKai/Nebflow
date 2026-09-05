# SubAgentTaskStore 状态更新失效调查报告

## 结论

**子代理任务的状态更新从未生效。** `BackoffSupervisor` 在任务完成/失败/重启时调用 `updateStatus`，但第一参数 `parentSessionId` 被硬编码为空字符串 `""`，导致它读写一个名为 `.json` 的错误文件，而真实的父会话任务文件永远停留在 `running` 状态。

## 证据链

### 1. 代码证据（根因）

| 位置 | 调用 | parentSessionId |
|------|------|-----------------|
| `DelegateTool.scala:412` | `recordTask` | 真实会话 ID（`getOrElse("")`） |
| `SubTaskTool.scala:269` | `recordTask` | 真实会话 ID（`getOrElse("")`） |
| `BackoffSupervisor.scala:184` | `updateStatus("", subagentId, "restarting", …)` | **硬编码 `""`** |
| `BackoffSupervisor.scala:249` | `updateStatus("", subagentId, taskStatus, …)` | **硬编码 `""`** |

`BackoffSupervisor.apply` 的参数列表（`BackoffSupervisor.scala:48-64`）中**没有 `parentSessionId`**——调用方（DelegateTool / SubTaskTool）持有该值但没有传递进来，于是实现里直接写了空串占位。

### 2. 磁盘证据（实锤）

`~/.nebflow/subagent-tasks/` 目录下：

```
.json                                          ← 文件名就是 ".json"，内容: []
5cc7590a-6dcb-4dba-8979-0ce5eb0a14fe.json      ← 35 个任务，全部 status=running, completedAt=None
676780c5-1105-4ae4-8653-cc877e890094.json      ← 1 个任务，status=running
73527280-b201-4a32-bebc-bf4effe9c9d4.json      ← 1 个任务，status=running
```

- `.json` 正是 `sessionFile("") = baseDir / ".json"` 的产物（`SubAgentTaskStore.scala:50-51`）
- 37 个任务全部 `running` / `completedAt=None`——其中 `delegate-Explorer-581a380b` 等任务的会话文件当天 18:14 还在正常更新，明显早已完成
- updateStatus 的逻辑（`SubAgentTaskStore.scala:88-99`）：`loadTasks("")` 读到空列表 → `map` 空列表什么也不改 → 把 `[]` 写回 `.json`。**静默无效操作，无任何报错**

### 3. 数据流分叉

![数据流分叉图](/tmp/subagent-bug.svg)

## 影响分析

`SubAgentTask` 的三个设计用途（`SubAgentTaskStore.scala:14-19`）全部失效：

| 用途 | 实际后果 |
|------|---------|
| **崩溃恢复** | `findRunningTasks()`（`:109-123`）扫描全部文件，每次进程重启会把 37 个早已完成的任务当作 running 返回——幽灵恢复 / 误重发通知 |
| **前端可观测性** | 任务状态永远显示"运行中"，看不到 completed/failed/restarting 和重启进度 |
| **自动重试 / 审计** | `retryCount`、`lastError`、`completedAt` 从未落盘，事后无法追溯子代理失败原因 |

另外 `.json` 这个空文件本身也是脏数据（注意 `findRunningTasks` 会尝试解析它，目前因内容为 `[]` 而无害，但它是 bug 的持续标志物）。

## 修复方向（供参考）

1. **传递 parentSessionId**：给 `BackoffSupervisor.apply` 增加 `parentSessionId: String` 参数，DelegateTool / SubTaskTool 创建 supervisor 时传入（两处调用点均已持有该值），替换 `:184` 和 `:249` 的 `""`
2. **防御**：`SubAgentTaskStore.sessionFile` 对空/空白 parentSessionId 直接返回 `IO.raiseError` 或记 warn 拒写，避免再次静默产生 `.json`
3. **清理存量**：修复上线后一次性清理 `.json`，并将历史 `running` 任务标记为 `failed`（`lastError = "stale: status tracking was broken"`）

验收要点：真实 delegate 一个子代理跑完 → 父会话 json 中该任务 `status=completed` 且 `completedAt` 非空；重启进程 → `findRunningTasks` 不返回已完成的旧任务；目录中不再出现 `.json`。
