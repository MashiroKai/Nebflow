# 取证：待办三入口 / todoStore 空壳 / 后端真源判定（todo-unify-backend · 取证节点）

分支 `delegate/delegate-nebflow-79768ef5` · 基线 main `5e17ad65c` · 取证时间 2026-11-04 14:3x–14:5x
工作区 `/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-79768ef5`
本文件只是**证据**；最终知识文档由 land 节点落 `<dataRoot>/projects/<project>/.stand/docs/`。

> 行号均为**合并 main 之后**的实测行号（`git rev-parse HEAD` = 合并提交 34387c557 之后的工作树）。

---

## 0. 基线与载体（先说清楚"证据是怎么造出来的"）

| 项 | 值 | 证据 |
|---|---|---|
| 合并 main | `git fetch --all` → `git merge --no-ff main`（main 尖端 = `5e17ad65c`，简报写的 `7f8051ce5` 已被作者再推进两次） | `recon/00_merge_main.txt` |
| 冲突 | **0 个**（合并前 merge-base == HEAD，本分支无自有提交 ⇒ 无对侧 hunk 可撞；`css/ask.css` +10、`css/mobileLayer.css` +38、`tests/canvas-ask-resize.spec.mjs` +126 全部干净带入） | `recon/00_merge_main.txt` |
| 运行时载体 | **真 HTTP + WS 服务器** `127.0.0.1:8399`（node `http` + `ws`），静态根 = `src/main/resources/web`；**零 sbt、零 gateway、绝不触碰 6138** | `recon/todo-shell-runtime-proof.mjs` 头注 |
| 为什么不只用 `page.routeWebSocket` | 本机实测该垫片对**首连**存在时序竞态（首轮出现 `ERR_CONNECTION_REFUSED`，重连才被拦下）。阴性结论（"零写帧"）不能建立在可能没接上的记录器上 ⇒ 换成真服务器，帧日志 = 服务器 `onmessage` 原文 | `recon/09_routewebsocket_race.txt`（早期轮次 stderr 逐字转写） |
| 运行总账 | **PASS 33 / FAIL 0**，exit 0 | `recon/10_runtime-proof.stdout.txt` 末行 |

---

## (a) 三入口表

| # | 入口 | file:line（实测） | 渲染什么 | 数据来自哪里 |
|---|---|---|---|---|
| ① | **桌面胶囊**（顶栏正下方居中一行） | 槽位 `src/main/resources/web/index.html:877`（`<div id="task-list">` 在 `#top-overlays` 内，`:862` 起是 `#top-overlays`）；挂载 `js/sidebar.js:2461` → `js/todoFace.js:1568 renderTodoTaskList(sessionId)` → `:382 renderCapsule()` | `.tfl-capsule` 两态翻面（`N 待办` / `M 逾期`，`todoFace.js:382-420`），展开 = `.tfl-card` 内的 `.tfl-row` 列表 + 标签 chips + 重要度筛选 | **内存 mock**：`js/todoStore.js`（261 行，`openCount()`/`overdueCount()`/`visibleItems()`），种子 = `todoStore.js:205-228 seed()` 的 8 条假待办。**零 WS、零落盘** |
| ② | **待办页**（消息列表顶部「待办事项」入口行 → 玻璃 overlay 页） | 入口行构造 `js/scheduled-task.js:1415 buildTodoEntryRow()`，插入点 `js/messages.js:644`（宿主 `index.html:686 #panel-messages` / `:688 #fm-conversations`）；页面壳 `index.html:828-843 #todo-page`；开合 `js/scheduled-task.js:859 openTodoPage()` + `:848-852 requestTodoInbox()` | 分组列表（已到期浮顶 / 待决策 / 待批准 / 计划），行 = `scheduled-task.js:525 buildRow()` 的 `.reminder-row`，圆钮 = 打卡销账 | **真 WS 真后端**：`listScheduledTasks {scope:'all'}` → `scheduledTaskList`（`WsTasksWorkspaceHandlers.scala:456-495`，数据源 `ScheduledTaskStore.getAllTodos:166-170`）；写 = `completeTodo`/`deleteScheduledTask`/`updateScheduledTask`/`createScheduledTask` |
| ③ | **手机任务面**（左上角悬浮圆钮 → 「任务」详情页） | 圆钮 `js/mobile/chatChrome.js:176-190`（`data-chrome="tasks"`，`pushPage('tasks')`，位置实测 375 视口 `x=16,y=16,48×48`）；面板壳 `js/mobile/taskPanel.js:49-95 ensurePanel()`（`#mobile-task-panel`，icon `list-todo`）；收编 `js/mobile/navController.js:566`（`overlayFaceByPage.tasks`）、`:675-678`（`case 'tasks'` → `ensureTaskPanel`） | 面板体 = 待办列表（`taskPanel.js:77 mountTodoFaceInto(bd)` → `todoFace.js:1595 mountTodoFaceInto`），面板头计数 `taskPanel.js:200-203`；圆钮徽标 `taskPanel.js:90-93 taskBadgeCount()` = `todoStore.openCount()` | **内存 mock**（同一 `todoStore`）。实测：手机任务页 8 行 = 胶囊那 8 条假待办 |

**三条腿的分裂点（一句话）**：①③ 读同一个**只活在浏览器内存里**的 `todoStore`；② 读写**引擎侧持久真源** `scheduledTaskStore(kind=todo)`。同一屏上两套数、互不同步。

补充锚点：
- 手机层胶囊**整条退役**：`src/main/resources/web/css/mobileLayer.css:2727-2729`（`body.mobile-layer #task-list { display: none !important; }`），实测 `getComputedStyle` 读数 `{"taskListDisplay":"none","taskListRect":{"w":0,"h":0}}`。
- 手机层第二个待办入口：`nf:mobile-todo-entry` 事件腿，`js/scheduled-task.js:1457` 发射 → `js/mobile/navController.js:2657` 接收 → `pushPage('todo')`；`navController.js:120` 是 todo 页登记，`:650-655` 是 `case 'todo'` 开页臂，`:2171-2174` 是出栈关态。
- 侧边栏旧槽位已退役：`index.html:636-643`（注释：`#todo-btn`/`#panel-todo` 已删，入口 = 消息列表顶部行）。

---

## (b) 空壳实证

### ① 代码证据 —— 三无 + mock 种子

原始输出：`recon/11_anchors.txt`（"########## (b) 空壳代码证据 ##########" 段）。

- 文件规模：`wc -l js/todoStore.js` = **261**。
- **三无 grep**（`fetch(|localStorage|sessionStorage|WebSocket|EventSource|XMLHttpRequest|sendWs|onMessage`）唯一命中是 **第 10 行的注释文字**（"本批内存态不落 localStorage"）——**零调用**。
- 内存变量：`todoStore.js:49-58`（`let tags = []` / `let items = []` / `let tagOrder = []` / `let activeTagId = null` / `const selectedPriorities = new Set()`）。
- 写口只改数组 + `emit()`：`todoStore.js:114-125 createItem`、`:127-137 updateItem`、`:140-145 completeItem`（`it.completed = true; emit();`）。
- mock 种子并**模块加载即执行**：`todoStore.js:205-228 seed()` + `:228` 裸调用 `seed();`（8 条假待办：修复登录态/缴房租/整理验收帧/买菜/回复设计稿/读书/灵感/体检）。
- 测试缝：`todoStore.js:242-260 __seed()`（`__` 前缀 = 非产品路径，shot 夹具用）。

### ② 运行时证据（改前红）

原始输出：`recon/10_runtime-proof.stdout.txt`；线上帧全量：`recon/ws-frames-desktop.json`、`recon/ws-frames-mobile.json`。

**(i) 改前 DOM 断言（点完成钮之前）**

```
  state: {"openCount":8,"capsuleText":"8 待办"}
  PASS 胶囊读数 = 8 待办（todoStore.js:205 seed() 8 条 mock）
  PASS todoStore.openCount() = 8
  PASS 入口行 .fm-todo-entry 在场（消息列表顶部 messages.js:644）
  PASS 入口行可见
  PASS 桌面 #task-list display ≠ none
  #task-list 内旧 delegate 残留计数: {"chainBlock":0,"taskCard":0,"chainHead":0}
  PASS #task-list 内零旧 delegate 链块残留（现役渲染只出 .tfl-*）
  展开后行数: 8
  将点完成的首行标题: 修复登录态在手机层的过期提示
```

点胶囊首行圆钮（`.tfl-complete` → `todoFace.js:647-672 performComplete` → `:660 todoStore.completeItem`）后：

```
  state: {"openCount":7,"capsuleText":"7 待办"}
  DOM 行: ["缴房租","整理验收对照表的动画取帧","下班顺路买菜","回复设计稿第二批意见","读《设计中的设计》第三章","记录灵感：刘海收展节奏迁移到胶囊","预约体检"]
  PASS 点击后 DOM 行数 = 7
  PASS 点击后 todoStore.openCount() = 7（纯内存态）
  PASS 点击后胶囊读数收敛为 7
  PASS 被点行标题已从可见面退场
```

**(ii) 刷新后状态回滚（写操作零留存）**

```
=== (ii) 刷新后状态回滚 ===
  state: {"openCount":8,"capsuleText":"8 待办","taskListDisplay":"block"}
  PASS 刷新后 todoStore.openCount() 回到 8（写操作零留存）
  PASS 刷新后胶囊读数回到 8
  PASS 刷新后被点过的行重新出现在可见面（回滚）
```

**(iii) 点击前后零写帧（线上原始帧日志）**

记录器先自证在线（否则阴性结论无效）：

```
  已录帧数: 29
  帧型: takeoverIdentify,setVoiceMuted,getSkills,getTeams,memoryStatus,getLlmLog,getConfig,getToolsList,getModelOptions,fleetUpdateSnapshot,listAgents,getPendingAsks,getActiveBgTasks,getActiveAgents,listSessions,getHeldReceipts,listDir,listScheduledTasks,listScheduledTasks,getTaskList,getHistory,listAgents,getHeldReceipts,listDir,listScheduledTasks,listScheduledTasks,getTaskList,listAgents,watchSubscribe
  活跃探针帧: [29] {"type":"getConfig"}
  PASS 活跃探针 getConfig 被服务器日志捕获（记录器在线）
```

点击窗口（点完成钮 → 1.6s 后全部 DOM/store 收敛）：

```
=== (iii) 点击窗口内的 client→server 线上帧（原始）===
  （空 —— 点击窗口内页面一个字节都没有发往服务器）
  PASS 点击窗口内 client→server 帧数 = 0
  PASS 整场零写帧（createScheduledTask/completeTodo/deleteScheduledTask/updateScheduledTask/toggleScheduledTask）
  全场帧型统计: {"takeoverIdentify":1,"setVoiceMuted":1,"getSkills":1,"getTeams":1,"memoryStatus":1,"getLlmLog":1,"getConfig":2,"getToolsList":1,"getModelOptions":1,"fleetUpdateSnapshot":1,"listAgents":3,"getPendingAsks":1,"getActiveBgTasks":1,"getActiveAgents":1,"listSessions":1,"getHeldReceipts":2,"listDir":2,"listScheduledTasks":4,"getTaskList":2,"getHistory":1,"watchSubscribe":1}
```

⇒ 全场 30 帧里 `createScheduledTask / completeTodo / deleteScheduledTask / updateScheduledTask / toggleScheduledTask` **各 0 帧**（其余均为启动期读帧与偏好帧）。

**(+) 阳性对照（证明"零写帧"不是记录器坏了）** — 同一台服务器、同一份日志，点**待办页**（入口②）圆钮：

```
  对照窗口线上帧（原始）:
  [74] listScheduledTasks  {"type":"listScheduledTasks","sessionId":"todo-shell-root","scope":"all"}
  [75] completeTodo  {"type":"completeTodo","sessionId":"todo-shell-root","id":"st-1"}
  PASS 待办页圆钮确实发出 completeTodo 帧（阳性对照 ⇒ 日志可见写帧）
```

⇒ **同一时刻、同一记录器：入口① 零帧，入口② 真写**。这就是"改前红"。

（旁注：入口③ 手机任务页 `#mobile-task-panel` 8 行也来自同一个 `todoStore`，见 (f) 的 `f7` 截图与 `10_runtime-proof.stdout.txt` 的 `PASS 手机任务页 = 待办列表 8 行`。）

---

## (c) 后端真源判定（结论先行）

**结论：人待办的可写真源 = `Schedule` 的 `kind="todo"` + `ScheduledTaskStore`。任务台账 `Task`（`core/task/TaskModel.scala:89`）不是，且不能是。不要造第三套存储。**

代码依据（≥3 条，全部实测行号，原始输出见 `recon/11_anchors.txt`）：

1. **语义已在模型里定死**：`core/scheduler/ScheduledTaskModel.scala:31-39` —— `Some("todo")` = 待办，`triggerAt` 复用为**下次浮出时间**，"完成 done 销账 / cancel 取消后从存储删除、永不再浮出"，且与定时任务**同等引擎侧持久（会话压缩/重启不丢）"。
2. **读口是待办专用的**：`ScheduledTaskStore.scala:166-170 getAllTodos`（跨会话、未完成、digest 内容源）、`:173-178 getAllDueTodos`（到点浮出），而定时任务读口 `:148-152 getAllDueTasks` 显式 `!t.isTodo` 排除待办——两套语义已分家。浮出腿只有一条：`ScheduledTaskActor.scala:177 dueTodos <- taskStore.getAllDueTodos` → `:667 surfaceTodos` → `:713 nextTodoSurfaceAt`。
3. **写口与 UI 消费方都已存在且在用**：`gateway/WsTasksWorkspaceHandlers.scala:43-48` 注册 `createScheduledTask / listScheduledTasks / deleteScheduledTask / updateScheduledTask / toggleScheduledTask / completeTodo`（handler 表 `:39` 起）、`:649-682 handleCompleteTodo`、`:456-495 scope:'all'` 跨会话收件箱；前端合法消费方 `js/scheduled-task.js:848-852`（读 scope:all）/`:956`（建）/`:1111-1140`（删）/`:1141-1160`（完成）。
4. **周报数据源挂在它身上**：`DoneRecordStore.scala:43-78`（只增 JSONL，`~/.stand/checkin-records/done-records.jsonl`）+ `WeeklyReport.scala:85-121 aggregate`（`doneCount = week.length`、`plannedCount = week.length + openTodoCount`），`WeeklyReport.scala:266 openTodos <- taskStore.getAllTodos`。换真源 = 周报断源。
5. **`Task` 台账是人待办的禁区**：
   - `TaskModel.scala:112-117` 注释逐字：「任务工具重做后**无新建 human 通道**（存量自然 TTL），complete 圆圈路径保留」⇒ 人待办入口已在下游被删。
   - `TaskStore.scala:49-53`：`completed/failed 过 6h 清、pending/in_progress 过 2d 清`（`CompletedTtl = 6.hours` / `ActiveTtl = 2.days`）⇒ 人待办放这里，**两周的房租待办两天就被物理清掉**。
   - 字段面不匹配：`Task` 无 priority、无 due、无 project 之外的待办维度；`scope` 只有 `session|team`（`TaskModel.scala:123-136`），没有 `scope:'all'` 的人收件箱概念。
   - **前端零消费**：`grep -rn "taskKind" src/main/resources/web/js/` = 0 命中（原始输出见 `11_anchors.txt` 末段）。
6. **为什么不造第三套存储**：`kind=todo` 已经四件齐活——落盘（`ScheduledTaskStore` 按会话文件）、done 痕（`DoneRecordStore`）、跨会话收件箱（`getAllTodos` + `scope:'all'`）、引擎浮出（`surfaceTodos` 每日 09:00 digest + missCount 缺卡计数）。再立一套 = 把"完成/周报/浮出/收件箱"四条腿各拆两半，且要重写 TTL、并发锁（`storeLock`）、迁移与 `withDefaults` 兼容。**收益为负。**

---

## (d) 缺口清单与端点方案（供 backend / capsule / panel 三节点照做）

### d.1 现状五项落库能力盘点

| 操作 | 现状 | 位置 |
|---|---|---|
| 新建 | ✅ 有（`kind:"todo"`，服务端重算浮出时刻，`project`/`name` 可带） | `WsTasksWorkspaceHandlers.scala:351-396` |
| 删除 | ✅ 有（硬删，不留痕） | `:524-546` |
| 完成 | ✅ 有（写 DoneRecord → `deleteTask` → 广播 `scheduledTaskDeleted`） | `:649-682` |
| **取消完成** | ❌ **无**（行已删，无从"取消"） | — |
| **改重要度** | ❌ **无**（模型无 priority） | `ScheduledTaskModel.scala` 全文 0 命中 `priority` |
| （备注/notes） | ❌ 无（`todoFace` 编辑面板有 notes 输入，但内存态） | `todoFace.js` 编辑面板 |

### d.2 必须补的字段（`core/scheduler/ScheduledTaskModel.scala`，全部 `Option` ⇒ `ConfiguredCodec + withDefaults` 零迁移，依据 `:103-112`）

| 字段 | 类型 | 语义 | 迁移风险 |
|---|---|---|---|
| `priority` | `Option[Int] = None` | 0/None = 无，1 低 / 2 中 / 3 高（对齐 `todoStore.js:35-40 PRIORITY_COLORS` 四色与 `:42 PRIORITY_LABEL_KEYS`） | 零（缺键 = None） |
| `notes` | `Option[String] = None` | 备注（`todoFace` 编辑面板 `notes` 输入的真源） | 零 |
| `completedAt` | `Option[Long] = None` | 完成时刻；`None` = 未完成。**软删标记** | 零 |
| `completedVia`（可选） | `Option[String] = None` | `"ws"` / `"tool"`，与 `DoneRecord.via` 对齐便于对账 | 零 |

### d.3 必须补/改的帧（沿用既有 msgType 族，不开新命名空间）

| 动作 | 帧（client→server） | 服务端行为 | 广播（server→client） |
|---|---|---|---|
| 新建 | `createScheduledTask {kind:"todo", content, project?, priority?, notes?}`（**扩字段**） | 现状 + 落 `priority`/`notes` | `scheduledTaskCreated.task{…, priority, notes, completedAt:null}` |
| 完成 | `completeTodo {id, sessionId}`（**改语义**） | `DoneRecord.record(via:"ws")` **+ `updateTask(completedAt = Some(now))`（不再 `deleteTask`）** | **`scheduledTaskCompleted {id, sessionId, completedAt}`**（替代现 `scheduledTaskDeleted`；前端 `js/scheduled-task.js` 现有 `onMessage('scheduledTaskDeleted')` 旁加一条同构腿，把行从收件箱摘掉） |
| 取消完成 | **`uncompleteTodo {id, sessionId}`**（新增） | 校验 `isTodo && completedAt.isDefined` ⇒ `updateTask(completedAt = None)` + 追加 `DoneRecord(via:"undo")`（同 taskId） | `scheduledTaskUpdated`（带 `completedAt: null` 全量行 JSON） |
| 删除 | `deleteScheduledTask`（**不变**，硬删、不留 done 痕） | 现状 | `scheduledTaskDeleted`（不变） |
| 改重要度 | `updateScheduledTask {id, priority}`（**扩 patch 字段**：`content` / `project` / `priority` / `notes`，`:579-600` 现只 content+project） | 字段级 patch（`priority` 传 `-1` 或 `null` = 清空） | `scheduledTaskUpdated`（全量行 JSON 已含新增三键） |

### d.4 读口必须同步的三处（否则会咬人）

1. **`listTaskJson`（`:499-522`）加 `priority` / `notes` / `completedAt` 三键**（纯增量，旧前端零感知）。
2. **已完成 todo 绝不能被 `ScheduledTaskActor` 触发** —— 硬闸 = 在读口过滤，不是在 Actor 里加判断：
   - `ScheduledTaskStore.scala:173-178 getAllDueTodos` 加 `&& t.completedAt.isEmpty`（不过滤 ⇒ 已完成行每日 09:00 继续浮出、`missCount` 继续涨、`:713 rescheduleTask` 继续顺延）
   - `:166-170 getAllTodos` 加同一条件（digest 与 scope:'all' 共用它）
   - `:159-160 getAllPendingTasks` / `:181 getPendingCount` 加同一条件（否则"下一次浮出"与待办数虚高）
   - `:148-152 getAllDueTasks` 已是 `!t.isTodo`，**天然安全**
3. **已完成行是否仍出现在 `scope:'all'`**：**默认不出现**（`getAllTodos` 注释 `:163-164` 就是"ALL unfinished todos"）。需要"已完成"视图时加 opt-in 参数 `includeCompleted:true`，**默认路径一字不改**，前端零感知。

### d.5 取消完成的表达方式：推荐 vs 不推荐（DoneRecord 不能断）

**推荐：`completedAt` 软删置位 + DoneRecord 追加 `via:"undo"` 反向事件抵销。**
- `DoneRecordStore` 的**只增不改**契约（`DoneRecordStore.scala:48-60`，锁内 append，`:13-26` 头注逐字"只增不改"）保持不变——撤销不改写历史，只追加一条反向事件 ⇒ 可审计、并发安全。
- `WeeklyReport.aggregate`（`:85-107`）改 3 行即可：`weekDone = week.filter(_.via != "undo")`；`undone = week.filter(_.via == "undo").map(_.taskId).toSet`；`doneCount = weekDone.count(r => !undone.contains(r.taskId))`。`plannedCount = doneCount + openTodoCount` 沿用，且 `openTodoCount` 走已过滤的 `getAllTodos`（d.4-2）⇒ 分子分母自洽。
- 退路（若不愿动 WeeklyReport）：**撤销不回溯周报**（口径 = "完成事件数"），必须在文档里明写取舍；周报不断，但"完成→撤销"会算一次打卡。

**不推荐：done-record 回滚（改写/删除 JSONL 行）。** 违反只增契约、并发写会撕裂文件、丢审计、`between()` 窗口聚合还得处理半行。

补充建议（不阻塞本批）：`completedAt` 超过 N 天（如 30 天）的已完成行由 `ScheduledTaskService` tick 物理清理，避免无限累积；`ScheduleTool action="done"`（`ScheduleTool.scala:412-481 doneAction`，调用点 `:280`）与 WS `completeTodo` 走**同一软删路径**，保持真源单点。

---

## (e) 影响面清单（三入口退役/并轨会连带谁）

先说一条**已经发生的事实**：换血批（2026-11-02）之后 `#task-list` 只渲染 `.tfl-*`，旧 delegate 链块**已零残留**（本批实测 `{"chainBlock":0,"taskCard":0,"chainHead":0}`，见 `10_runtime-proof.stdout.txt`）。下表因此分成"仍绿/需改"与"**已 stale（断言面与现役渲染脱节）**"两类——stale 那批是**本批之前就已红**，不是本批弄红的，但并轨时必须一并处置，否则验收永远不可能全绿。

| # | file:line | 现断言 / 消费 | 处置建议 |
|---|---|---|---|
| 1 | `tests/todo-split.spec.mjs:1-16`（头注②"入口行唯一 `.fm-todo-entry`"）、`:154-155`（点入口行 → `#todo-page.open`）、`:182`、`:265`（徽标收敛） | 入口行 + 待办页 | **保留并加强**：这是唯一覆盖"待办真落库写口"的 spec。补 `completeTodo` / `uncompleteTodo` / `updateScheduledTask{priority}` 帧断言与"刷新后仍为已完成"断言 |
| 2 | `tests/todo-page-shots.mjs:101-116` | 桌面 `#todo-page.open` / 手机 pushPage('todo') 截图 | 入口行与待办页保留 ⇒ 不改；若并轨为新统一面板 ⇒ 改选择器后重拍 |
| 3 | `tests/todo-face-shots.mjs:147-155`（胶囊 8 待办 + 零 delegate 残留）、`:160-212`（展开/筛选/添加/完成三段）、mobile 段（`m-capsule-fab` = 8） | 胶囊 + 手机任务页 | **必改**：数据源换成真源后，`__seed` 注入腿（`:47-72`）要换成 WS 夹具腿；行数由 mock 8 变真源 N；补"刷新后仍在"的落库断言（把 (b)(ii) 的红反过来） |
| 4 | `tests/todo-capsule-redesign-shots.mjs:156-162`、`:201`、`:238-279` | 胶囊几何/三数字/翻面 | 几何断言保留；`__seed` 数据注入腿换 WS 夹具 |
| 5 | `tests/mobile-panel-title.spec.mjs:164`（`scheduledTaskList` 夹具）、`:203-204`（`nf:mobile-todo-entry` → todo 页）、`:555-557`（todo 页滚动域） | 手机 todo 页 | 若入口行退役 ⇒ `:203-204` 推页腿必须改；否则不改 |
| 6 | `tests/mobile-dock.spec.mjs:423-429` | `.mobile-page[data-page="tasks"] .mobile-page-body > #mobile-task-panel`、`.tp-x` display none | 若 tasks 页并轨为待办页 ⇒ 改选择器（面板壳 `taskPanel.js` 保留） |
| 7 | `tests/mobile-v3.spec.mjs:405-416`（T6） | `#task-list` `toBeHidden()` | **仍绿**（手机层恒 none，`mobileLayer.css:2727`），保留 |
| 8 | `tests/mobile-taskpanel-active-only-shots.mjs:110`、`:126-127` | `#task-list .task-chain-head` 计数 | **已 stale** ⇒ 改到新面或退役 |
| 9 | `tests/taskpanel-blocked-retire.spec.mjs:149-160`（`#task-list .task-chain-head` 计数 2、`data-task-no` 各位） | 旧 delegate 链块 | **已 stale** ⇒ 退役或整体改到 `#mobile-task-panel` |
| 10 | `tests/delegate-chain-stub.spec.mjs:150/166/219/345/425/588`、`tests/delegate-ui-twolayer.spec.mjs:318-377`、`tests/tasklist-chain-blocks.spec.mjs:111-480`、`tests/taskbar-flow-overlap.spec.mjs:112-185`、`tests/tasklist-reconcile-shots.mjs:114-148` | 全部 `#task-list .task-card/.task-chain-*` | **已 stale**（本批实测残留 0）⇒ 建议整批退役这批断言；`taskList.js` / `taskPanel.js` 的旧渲染核代码按既有批令**保留存档、零删除** |
| 11 | `tests/capsule-before-shots.mjs:31-99` | `todoStore.__seed` + `.tfl-capsule` 改前对照 | 保留（对照件），数据注入腿随数据源调整 |
| 12 | `tests/tmp-verify-todo.mjs:49-102` | 旧待办面板 `.task-item/.task-stats` | **已 stale + tmp** ⇒ 删或退役 |
| 13 | `tests/cognition-phase2.spec.mjs:195-242`、`tests/desktop-avatarfix-shots.mjs:126-144` | `.fm-todo-entry .fm-row-badge` / `.fm-todo-icon` | 入口行保留则不改 |
| 14 | `js/mobile/navController.js:120`（todo 页登记）、`:650-655`（`case 'todo'` → `openTodoPage`）、`:675-678`（`case 'tasks'`）、`:2171-2174`（出栈关态）、`:2339`（`CLOSE_WATCH`）、`:2657`（`nf:mobile-todo-entry` 推页腿） | 两个详情页（todo / tasks） | 并轨 ⇒ 收敛成一条页腿（删一条 `case` + 一条 `CLOSE_WATCH`）；保留两条 ⇒ 只需改数据源 |
| 15 | `js/sidebar.js:2458-2461` | `#task-list` 槽位渲染 = `renderTodoTaskList(sid)` | 胶囊保留 ⇒ 调用点不变（换实现不换入口）；胶囊退役 ⇒ 删此调用 + `index.html:877` + `css/todo-face.css` 几何块 |
| 16 | `js/todoFace.js:1568-1585`（桌面挂载）、`:1595-1601`（手机页面挂载）、`js/todoStore.js` 全文（261 行） | 渲染层 + 内存仓 | 退役 `todoStore`：`TodoFace` 改为订阅新 store/WS；两处挂载点保留（`#task-list`、`#mobile-task-panel`） |
| 17 | `js/mobile/taskPanel.js:77`（`mountTodoFaceInto(bd)`）、`:90-93`（`taskBadgeCount`） | 面板体 + 圆钮徽标 | 徽标改读真源（scope:'all' 未完成数），`sessionShowsNodeEntries` 闸（`taskList.js:57-61`）保留 |
| 18 | `js/scheduled-task.js:848-852 / :956 / :1111-1140 / :1141-1160` | 既有合法写口 | **保留并扩展**（priority / notes / uncomplete），是本批唯一正确的写入通道 |
| 19 | `css/mobileLayer.css:2727-2729` | 手机层 `#task-list` display:none | 胶囊在手机层复活 ⇒ 删此规则；否则保留 |
| 20 | `index.html:877 / :828-843 / :686-688 / :621` | 三个 DOM 槽位 | 随并轨方向定 |

---

## (f) 改前截图（双端，合并后基线）

全部落 `recon/`，绝对路径前缀 `/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-79768ef5/reports-evidence/todo-unify-backend__20261104_145310__delegate-nebflow-79768ef5/recon/`：

| 文件 | 尺寸 | 内容（已逐张看过） |
|---|---|---|
| `f1-desktop-1280x800-capsule+entryrow.png` | 1280×800@2x | 桌面双入口同帧：**顶栏正下方胶囊**（此刻翻到 `● 1 逾期` 面，mock 里"缴房租"已过期）+ **左侧消息面板顶部「待办事项 / 全部清零」入口行** |
| `f2-desktop-1280x800-capsule-expanded-8rows.png` | 1280×800@2x | 点胶囊展开：`1 逾期` 头部 + **8 行 mock 待办**（重要度四点色 / 标签 chips 工作·生活·学习·灵感 / 截止时刻），行尾圆钮 = 打卡位 |
| `f3-desktop-1280x800-after-complete-7rows.png` | 1280×800@2x | 点首行圆钮后 **7 行**（"修复登录态在手机层的过期提示"已退场）——内存态销账 |
| `f4-desktop-1280x800-after-reload-rollback.png` | 1280×800@2x | 刷新后回到 **8 条** —— 写操作零留存的视觉对照 |
| `f5-mobile-375x812-chat-fab+capsule-displaynone.png` | 375×812@2x | 手机聊天页：**左上角任务圆钮 + 徽标 8**；顶栏下方**没有**胶囊行（`#task-list` display:none 的视觉证据） |
| `f6-mobile-375x812-messages-entryrow.png` | 375×812@2x | 手机消息页：**「待办事项 / 全部清零」入口行**（点击 = `nf:mobile-todo-entry` → pushPage('todo')） |
| `f7-mobile-375x812-taskpage-8rows.png` | 375×812@2x | 手机「任务」详情页：标题 `8 待办` + **8 行**（同一份内存 mock） |
| `f8-desktop-1280x800-todopage-realrow.png` | 1280×800@2x | 待办页（入口②）打开 + WS 夹具下发的**真后端行**（阳性对照现场） |

> 手机端三入口**不可能同帧**：`#task-list` 在手机层被 `css/mobileLayer.css:2727-2729` 整条 `display:none`（实测 `w=0,h=0`）——所以手机侧拆成 f5（圆钮）+ f6（入口行）两张，"胶囊缺席"本身就是证据。

---

## 附：证据文件索引（同目录）

| 文件 | 内容 |
|---|---|
| `00_merge_main.txt` | 第 0 步合并 main 的原始命令与结论（冲突 0） |
| `10_runtime-proof.stdout.txt` | 运行时取证全量 stdout（**PASS 33 / FAIL 0**） |
| `10_runtime-proof.stderr.txt` | 运行时取证 stderr（最终成功轮次为空文件） |
| `09_routewebsocket_race.txt` | `page.routeWebSocket` 首连竞态的实测转写（解释为什么改用真服务器） |
| `11_anchors.txt` | (a)(b)(c) 三节所有 file:line 锚点的原始 grep/sed 输出 |
| `todo-shell-runtime-proof.mjs` | 取证脚本（真 HTTP+WS 服务器 + 逐帧日志 + 阳性对照，可复跑） |
| `ws-frames-desktop.json` | 桌面全量线上帧 + 前后 DOM/store 快照 |
| `ws-frames-mobile.json` | 手机读数 + 线上帧 |
| `f1..f8-*.png` | (f) 双端改前截图 |
