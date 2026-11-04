# 待办真源帧契约（todo-unify-backend · 后端真源节点交付）

施工面：`src/main/scala/nebflow/{core/scheduler,gateway}`。本文件是**下游前端节点（胶囊 / 面板）的施工依据**，
与代码同步提交（commit `ca9bf046e`）。全部为**纯增量**：老帧型、老字段语义一律不动。

## 1. 模型字段（`ScheduledTask`）

| 字段 | 类型 | 缺省 | 语义 | 老库兼容 |
|---|---|---|---|---|
| `priority` | `Int` | `0` | 0=无 / 1=低 / 2=中 / 3=高。`ScheduledTask.clampPriority` 单点钳位 0..3（create 与 WS patch 都过它，库内不出现越界值） | 缺键 → 0 |
| `completedAt` | `Option[Long]` | `None` | 完成时刻；**软删标记**。`Some` ⇒ 已完成（不进收件箱默认面、不被 scheduler 浮出、不算 pending） | 缺键 → None |

派生：`isCompleted = completedAt.isDefined`。排序键（服务端权威，可从行数据算出）：
`ScheduledTask.todoSortKey` = `(已完成殿后, -priority, triggerAt 升序, createdAt 升序)`。

## 2. client → server 帧

| 帧 | 新增/变更 | 载荷 | 服务端行为 |
|---|---|---|---|
| `createScheduledTask` | 扩字段 | `kind:"todo"`, `content`, `project?`, `name?`, **`priority?`(0..3)** | `priority` 钳位后落库；response 的 `task` 增 `priority`/`completed`/`completedAt` 三键 |
| `updateScheduledTask` | 扩 patch | `id`, 任一 `content` / `project` / **`priority`** | `priority` **只对待办面生效**（kind=task 行传 priority 零改动）；`priority:0` = 清空重要度；广播 `scheduledTaskUpdated`（行 JSON 与 list 面**同形**，由 `listTaskJson` 单点产出） |
| `completeTodo` | **语义变更（软删）** | `id`, `sessionId` | 写 `DoneRecord(via:"ws")`（周报数据源保留）+ 置 `completedAt`（**不再删行**）；幂等：重复打卡不重复写 done 痕、不改首次完成时刻 |
| `uncompleteTodo` | **新增帧** | `id`, `sessionId` | 清 `completedAt` + 追加 `DoneRecord(via:"undo")` 反向事件（只增不改，周报按净额解释）；幂等：本来就未完成 ⇒ 不写 undo，仍回 updated 帧收敛；未知名 ⇒ 回 `scheduledTaskDeleted`；kind=task ⇒ error 帧 |
| `deleteScheduledTask` | 不变 | `id`, `sessionId` | 物理删（与「完成」区分：完成可回退，删除不可逆） |
| `listScheduledTasks` | 扩参数 | `scope:"all"`, **`includeCompleted?`(默认 false)** | 默认**不返回已完成行**（收件箱语义）；`includeCompleted:true` 才带已完成（殿后）；响应回显 `includeCompleted` |

## 3. server → client 帧

| 帧 | 何时 | 载荷 |
|---|---|---|
| `scheduledTaskCreated` | 建待办成功 | `{type, sessionId, task:{…全量行…, priority, completed:false, completedAt:null}}` |
| `scheduledTaskCompleted` | **新增** · `completeTodo` | `{type, id, sessionId, completedAt, task:{…全量行…}}` |
| `scheduledTaskDeleted` | **完成时照发（兼容）** / 物理删 / 幂等兜底 | `{type, id, sessionId}` |
| `scheduledTaskUpdated` | 改字段 / 取消完成 / 幂等收敛 | `{type, sessionId, task:{…全量行…}}` |
| `scheduledTaskList` | list 响应 | `{type, tasks:[…全量行…], sessionId, scope:"all", includeCompleted}` |

**完成广播兼容策略 = 新帧 + 老帧同发**：`scheduledTaskCompleted` + `scheduledTaskDeleted`。
理由：`js/scheduled-task.js` 现有唯一消费点是「把行从收件箱摘掉」，与完成语义同形（摘行幂等）⇒ 老前端零改动即正确；
老帧不删，避免「要求所有前端同批升级，跨版本实例把完成行留在面上」。

## 4. 行 JSON（`listTaskJson` 单点，list / created / updated / completed 四帧共用）

存量键（**语义零改动**）：`id` `content` `triggerAt` `createdAt` `triggered` `triggeredAt` `referencePath`
`repeat` `enabled` `kind` `name` `missCount` `sessionId` `project`
新增键（纯增量）：`priority: Int`、`completed: Boolean`、`completedAt: Long|null`

## 5. 触发面护栏（已完成绝不被叫醒）

1. 读口硬闸：`ScheduledTaskStore.getAllTodos` / `getAllDueTodos` / `getAllPendingTasks` / `getPendingCount` 全部排除 `completedAt.isDefined`；`getAllDueTasks` 本就 `!isTodo`。
2. Actor 二道闸：`ScheduledTaskService.surfacedTodos`（纯函数）在 `ScheduledTaskActor.surfaceTodos` 入口再剔一次。
3. 不可达路径：`completeTodo` 之后该 todo 在 `getAllDueTodos` 里零候选（帧级 spec ⑦ 钉）。

## 6. spec 清单与读数

| spec | 例数 | 读数 |
|---|---|---|
| `nebflow.core.scheduler.*`（含新增 `TodoPriorityCompletionSpec` 10 例） | 102 | 102 绿 / 0 红 |
| `nebflow.gateway.TodoUnifyFramesSpec`（新增，帧级契约） | 9 | 9 绿 / 0 红 |
| `nebflow.gateway.*`（全包） | 1106 | 1103 绿 / 3 红（`FriendApiRoutesSpec`×2 + `Mvp2DeviceRoutesSpec`×1，全部 `GET /conversations … route fell through`；**改前基线同红**，证据 `04_/05_*.log`） |

原始日志：本目录 `01_test_compile.log`（compile 绿）、`02_spec_scheduler+frameface.log`、`03_spec_gateway_all.log`、`04_baseline_friendapi.log`、`05_baseline_mvp2.log`。

## 7. 已知取舍 / 开放项

- **`ScheduleTool action="done"`（LLM 工具面）仍是硬删**（本批未改，超出施工图范围）：工具面完成的待办行被物理删，无法 `uncompleteTodo`；DoneRecord 照写（周报不断）。若要两面对齐需另开批（改 `ScheduleTool.doneAction` 走软删并广播新帧）。
- 已完成行**不自动清理**：软删行留在库内（recon 建议 30 天 TTL 清理，未做，避免本批引入清理策略）。
- digest（每日 09:00 浮出文案）排序仍按 `triggerAt, createdAt`，**不按 priority**——只有 WS 收件箱面按 priority 排；若前端要求 digest 也按重要度需再改一处。
- 未改任何前端文件、未动 `build.sbt`、未 push、未落知识文档（land 节点负责）。
