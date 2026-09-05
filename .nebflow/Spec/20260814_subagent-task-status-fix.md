# 修复方案：SubAgentTaskStore 状态更新因 parentSessionId 空串而静默失效

- 日期：2026-08-14
- 类型：bug 修复
- 优先级：高（数据完整性 + 可观测性 + 未来崩溃恢复的正确性前提）
- 来源：调查报告见 `/tmp/subagent-task-bug-report.md`（同轮次生成，含证据链）

## 1. 现状分析

`SubAgentTaskStore`（`src/main/scala/nebflow/agent/SubAgentTaskStore.scala`）按父会话分文件存储子代理任务：`~/.nebflow/subagent-tasks/<parentSessionId>.json`。

- **写入路径正确**：`DelegateTool.scala:408-424` 与 `SubTaskTool.scala:265-281` 在 spawn 时调用 `recordTask`，使用真实 `parentSessionId`。
- **更新路径断裂**：`BackoffSupervisor.scala:184` 和 `:249` 调用 `updateStatus` 时第一参数硬编码为 `""`。`sessionFile("")` 解析为 `.json`，`loadTasks("")` 返回空列表，`map` 空列表等于什么都不改，再把 `[]` 写回 `.json` —— 静默无效，无报错。
- `BackoffSupervisor.apply`（`:48-64`）的参数表中根本没有 `parentSessionId`，实现里用空串占位。

磁盘实锤（2026-08-14 18:08 检查）：

```
~/.nebflow/subagent-tasks/
  .json                                       ← 内容 []，bug 产物
  5cc7590a-….json   35 个任务全部 status=running, completedAt=None
  676780c5-….json    1 个任务 running
  73527280-….json    1 个任务 running
```

影响：任务状态/retryCount/lastError/completedAt 永不落盘；前端可观测性失真；一旦 `findRunningTasks`（当前无调用方，为 P3 崩溃恢复预留）接入启动流程，每次重启会把所有历史任务误判为 running。

## 2. 改动方向

1. **传递真实 ID**：`BackoffSupervisor.apply` 新增参数 `parentSessionId: String`，随 `active()` 透传，替换 `:184`、`:249` 两处 `""` 字面量。两个调用点（`DelegateTool.scala:363`、`SubTaskTool.scala:216`）作用域内均已持有 `parentSessionId: Option[String]`，传 `parentSessionId.getOrElse("")`。
2. **防御空串**：`SubAgentTaskStore.sessionFile`（`:50-51`）对空白 `parentSessionId` 抛 `IllegalArgumentException`。现有调用方均带 `.handleErrorWith(warn)`，异常只会降级为日志告警，不会再静默产生 `.json`。
3. **存量清理（一次性）**：删除 `~/.nebflow/subagent-tasks/.json`；将三个父会话文件中 `status=="running"` 的历史任务改为 `status="failed"`、`lastError="stale: status tracking was broken before fix"`。以一次性脚本在验收阶段执行，不写入运行时代码。

## 3. 涉及文件

| 文件 | 改动 |
|------|------|
| `src/main/scala/nebflow/agent/BackoffSupervisor.scala` | `apply` 与 `active` 增加 `parentSessionId: String` 参数；`:184`、`:249` 的 `""` 替换为该参数 |
| `src/main/scala/nebflow/core/tools/DelegateTool.scala` | `:363` 构造处传入 `parentSessionId.getOrElse("")` |
| `src/main/scala/nebflow/core/tools/SubTaskTool.scala` | `:216` 构造处传入 `parentSessionId.getOrElse("")` |
| `src/main/scala/nebflow/agent/SubAgentTaskStore.scala` | `sessionFile` 增加空白 ID 防御 |
| `src/test/scala/nebflow/agent/AgentRestartSpec.scala` | 新增用例（见验收条件） |

## 4. 预期变更展示

**行为变更说明**：

- 现在：子代理跑完（或失败、或崩溃重启）后，`~/.nebflow/subagent-tasks/` 里它的任务记录永远显示"运行中"，完成时间、错误原因、重启次数全部丢失；目录里还会多出一个名为 `.json` 的空文件。
- 改完后：子代理结束时，它所在父会话的任务文件里对应记录会立刻变成 `completed` / `failed` / `restarting`，带上完成时间和（失败时的）错误信息；不再产生 `.json` 空文件。

![数据流分叉：修复前](/tmp/subagent-bug.svg)

修复后上图红色分支消失：`updateStatus` 与 `recordTask` 写同一个父会话文件。

## 5. 验收条件

以下条件全部满足才算完成，逐条可自动化执行：

1. **冒烟测试（硬性，先于一切）**：`sbt compile` 通过后，`sbt "run"` 真实启动（主类 `nebflow.Main`，构建配置 `build.sbt:63`），等待启动完成，`curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/health`（路由定义 `RestApiRoutes.scala:50`）返回 `200`。启动日志无 `subAgentTaskStore` 相关 warn。
2. **编译与单测**：`sbt Test / compile` 通过；`sbt test` 全量通过，无回归。
3. **新增单元测试（覆盖修复逻辑，不依赖 LLM）**：在 `AgentRestartSpec.scala`（或新建 Spec）中：构造真实 `SubAgentTaskStore`（临时目录）+ `BackoffSupervisor`（fake child 直接 `! Completed`），断言 `<parentSessionId>.json` 中该任务 `status=="completed"` 且 `completedAt` 非空；再测 `Failed` 路径断言 `status=="failed"`、`lastError` 非空；再测空串 parentSessionId 断言抛异常且不产生 `.json` 文件。
4. **端到端验证（真实链路）**：启动 gateway 后，通过 WS 发送一条触发 `Delegate` 工具的消息（或调用等效 REST/WS 接口启动子代理），等待子代理完成（收到 `ExternalEvent` completed 通知），然后断言：`~/.nebflow/subagent-tasks/<该会话>.json` 中对应 `delegate-*` 任务 `status=="completed"`、`completedAt != null`。
5. **防御验证**：向 `sessionFile` 传入 `""` 与 `"  "`，断言抛 `IllegalArgumentException`；测试后目录列表中不存在名为 `.json` 的文件。
6. **存量清理执行**：清理脚本运行后，`ls ~/.nebflow/subagent-tasks/` 无 `.json`；三个历史父会话文件中不存在 `status=="running"` 的记录（全部转为 failed + stale 标注）。
7. **重启恢复正确性（回归 `findRunningTasks`）**：清理完成后再次启动进程，调用/测试 `findRunningTasks` 返回空列表。

## 6. Agent 可读任务块（供 Manager 分发）

```yaml
task: fix-subagent-task-status-update
kind: bugfix
summary: BackoffSupervisor 传空 parentSessionId 导致 SubAgentTaskStore 状态更新静默失效
files:
  - path: src/main/scala/nebflow/agent/BackoffSupervisor.scala
    change: apply/active 增加 parentSessionId: String 参数；替换 :184 与 :249 的 "" 字面量
  - path: src/main/scala/nebflow/core/tools/DelegateTool.scala
    change: ":363 BackoffSupervisor(...) 增加 parentSessionId = parentSessionId.getOrElse(\"\")"
  - path: src/main/scala/nebflow/core/tools/SubTaskTool.scala
    change: ":216 BackoffSupervisor(...) 增加 parentSessionId = parentSessionId.getOrElse(\"\")"
  - path: src/main/scala/nebflow/agent/SubAgentTaskStore.scala
    change: sessionFile 对空白 parentSessionId 抛 IllegalArgumentException
  - path: src/test/scala/nebflow/agent/AgentRestartSpec.scala
    change: 新增 completed/failed/空串防御 三组用例（真实临时目录 store）
constraints:
  - 不改变 recordTask 现有行为
  - updateStatus 异常路径保持 handleErrorWith 降级为 warn 日志
  - 存量数据清理只做一次性脚本，不进运行时代码
acceptance:
  - sbt run 真实启动 + curl /health == 200
  - sbt test 全量通过（含新增用例）
  - E2E：真实 delegate 完成后任务 json 中 status=completed 且 completedAt 非空
  - ~/.nebflow/subagent-tasks/ 无 .json 文件；历史 running 全部转 failed(stale)
  - 重启后 findRunningTasks 返回空
```
