# Team Manager 任务工具规格书

> 需求提出：用户 2026-08-25 15:06 —— 给 Team 的 Manager 设计一个**任务工具**：①参考 Nebflow git 历史中旧版任务工具的设计 ②任务全由 Manager 创建和控制状态（owner=Manager）③Nebula 可查（只读）④保留 block 语义（blockedBy/blocks 依赖）⑤前端要有设计——team 面板能看到团队当前工作（复用 25e7193c 的 Team 面板 WS 挂载基础）。
> 本文为调研 + 设计规格书，不触碰产品代码。代码证据来自当前主仓 `/Users/dev/Claude code/Nebflow`（archive/scala 分支，v1.4.1-beta.51）。
> 配套：git 历史检索结果（§1 旧版任务工具设计思路）、现状代码证据（§9）。
> 状态：draft v2.0 · 待用户/Manager 确认后派发实施
> 版本日志：v1.0（初版）→ v2.0（本版，按用户 08-25 15:1x 裁定：任务工具与 flow 体系**无耦合**——删除「flow v2 衔接」章节；flow 相关保留项降级为「安全隔离」标注）

---

## 0. 用户裁定记录（2026-08-25，决定本规格书走向）

| # | 裁定项 | 用户选择 | 对本规格书的影响 |
|---|---|---|---|
| U1 | 状态机 | **简化四状态**（去掉 needs_confirmation）：pending → in_progress → completed / failed | §2.3 四态转移矩阵；§7 差异表 |
| U2 | Team 成员权限 | **成员先不看到**，只是 Manager 自己管理（比"只读"更严格——MVP 成员无任何 TeamTask 工具） | §3 权限模型；阶段 1 注入断言 |
| U3 | 与现有任务体系的关系 | **扩展现有体系**（Task 模型加 scope/teamId 维度，team 任务存 `~/.nebflow/tasks/teams/<team>/`，复用状态机/block/环检测/归档逻辑） | §4 数据模型与存储 |
| U4 | assignee 指派字段 | **不支持**（任务只归 Manager，成员从面板观看，派活走 Mail） | Task 模型不加 assignee；§2.1 工具参数 |
| U5 | 与 flow 体系的关系 | **无耦合**（「Flow 和这个工具没有耦合」——任务工具是独立能力，与 flow 体系无关联设计；flow 完成不自动改任务状态、noteLinks 引用 flow 产出、任务→flow 绑定等衔接设计全部删除） | §5 重写（去耦合）；flow 节点剥离 TeamTask* 保留为**安全隔离**标注（非衔接） |

> 注：U2「成员先不看到」指 **agent 成员**（不注入 TeamTask 工具）；**人类用户**仍通过 Nebflow 前端 team 面板看到团队任务（这是需求⑤的本意——"能看到 team 目前在做什么工作"）。前端为只读展示，编辑全走 Manager 工具。

---

## 1. 旧版任务工具设计思路摘要（任务 1 产出——git 历史检索）

### 1.1 演进链（git log 检索，按时间序）

| 阶段 | commit | 设计要点 |
|---|---|---|
| 工具时代（初版） | ac46b2da / f44d13a8 | TaskModel/TaskCreateTool 引入：session-scoped 任务列表，TaskList/TaskCreate/TaskUpdate 三个工具 |
| system prompt 注入 | **38f07802**（07-24） | 任务从"调用 TaskList 才可见"改为**每 turn 注入 system prompt**（`TaskStore.renderForPrompt` + PromptSection order 630 + PromptContext.taskListText）——"agent always knows current task state without calling TaskList"。Task 模型加 parentId 支持嵌套任务，TaskCreate 加 parentTaskId |
| TaskList 工具移除 | **bcd75e55**（07-24） | 删除 TaskListTool（61 行）——任务清单改为注入，TaskCreate/TaskUpdate 保留为变更入口 |
| 五状态生命周期 | **a78f77d0**（todo-v2） | 五状态：pending / in_progress / **needs_confirmation** / completed / failed；needs_confirmation = "做完待用户确认"，completed 保留给用户点圆（agent 不能直接置） |
| human/agent 任务分拆 | **6064a1c3** + **8f082213** + **abc147b0** | todo-panel：Apple Reminders 风格双区面板（待办区 human=人操作 / 任务区 agent=只读观察）；taskKind 字段；WS completeTask |
| block 依赖语义 | **bb9bb240** | TaskUpdate 全字段：**blocks/blockedBy + addBlocks/addBlockedBy/removeBlocks/removeBlockedBy** + batch（逗号分隔多 ID）+ 环检测 |
| 归档与检索 | **cffa714b**（C2）+ **1ff3c641**（C3） | completedAt/notes/events 事件流 + TaskArchive 跨 session 索引 + TaskQuery（session/recent/project 三 scope） |
| 可靠性 | **55d4575e**（#23）+ ee063703（#21） | 原子写（tmp + ATOMIC_MOVE）、TaskCreate 返回 taskId |
| 打回语义 | **be09c448**（08-24 裁定） | needs_confirmation → in_progress 允许（对话反馈=打回，更新需带 note 防滥用） |

### 1.2 值得复用到新 Manager 任务工具的设计（结论）

| 旧版设计 | 复用方式 | 本规格书落点 |
|---|---|---|
| **五状态 + 用户确认通道** | 状态机骨架复用；team 域按 U1 简化为四态（去掉 needs_confirmation，Manager 全权验收） | §2.3 |
| **blocks/blockedBy + addBlocks/addBlockedBy + DFS 环检测** | **原样复用**（TaskStore.hasCycle / update 里的依赖合并逻辑，TaskStore.scala:208-325） | §2.4 |
| **system prompt 注入（而非 TaskList 轮询）** | 复用 renderForPrompt 模式；team 域由 TeamTaskList 工具 + 前端面板承载（Manager 的 turn 注入作为阶段 3 可选增强） | §2.2 / §6 |
| **WS taskListUpdate 广播** | 复用 TaskToolHelper.emitTaskListUpdate 模式 → team 域 `teamTaskListUpdate` 事件 | §2.5 |
| **事件流 + note + 归档** | 原样复用（Task.events/notes/completedAt + TaskArchive 索引） | §4.2 |
| **原子写 + hwm** | 原样复用（AtomicJson + 每目录独立 hwm） | §4.2 |
| **taskKind human/agent 双区** | **不采用**——team 任务全归 Manager，无 human 提醒语义 | §7 差异表 |

### 1.3 关键现状事实（设计约束）

- **权限现状**：TaskCreate/TaskUpdate 在 `LeadLevelTools`（AgentCore.scala:1674，Nebula + `"*"` 工具的 lead 用，depth≥2 worker 剥离）；但 **Manager 的 agent.json tools 不是 `"*"`**（ReminderIsland/Manager: `["Read","Grep","Glob","Bash","Mail"]`）→ **当前 Manager 实际拿不到 TaskCreate/TaskUpdate**。新 TeamTask* 工具必须显式注入。
- **任务 prompt 注入仅 Nebula**：AgentCore.scala:494 注释 "Tasks: Nebula only, real-user turns only"——Manager 目前系统提示词里没有任务清单。
- **TaskDeleteTool 存在但未注册**（registry.scala 只有 TaskCreate/TaskUpdate/TaskQuery）——死代码，规格书不涉及。
- **Team 面板现状**：`/api/teams/mounted` 返回 `teams[{name, type, agents[{name, sessionId, status, manager}]}]`；flowTeams.js 渲染卡片（header + agent 磁贴 + Flows DAG 行）；WS getTeams → teamList；25e7193c 保证 WS connect 时恢复挂载。**面板无任务数据**——需扩展。

---

## 2. 工具接口设计

### 2.1 命名与工具族

新增 **TeamTask 前缀族**（与 Flow* 前缀族一致性，grep 一次搜全）：

| 工具 | 语义 | 可用者 | 与现有工具关系 |
|---|---|---|---|
| **TeamTaskCreate** | 创建团队任务（subject/描述/blockedBy） | Manager（owner） | 内部复用 TaskStore.create（team 域） |
| **TeamTaskUpdate** | 控制状态 + block 依赖 + note（四态机） | Manager（owner） | 内部复用 TaskStore.update（team 域 + 简化转移矩阵） |
| **TeamTaskList** | 只读查询团队任务（team/status/全部） | Manager（owner）+ **Nebula（只读）** | 复用 TaskStore.list + TaskArchive；**无任何变更参数**（只读硬约束） |

> 为什么独立工具名而非给 TaskCreate/TaskUpdate 加 scope 参数：①权限隔离——TeamTask* 的可用者集合与 Task* 完全不同（Manager 只有 TeamTask*，Nebula 两者都有但 TeamTask* 只读）；②语义清晰——四态机与五态机不同，混在一个工具里 description 会互相污染；③防越权——工具名即权限边界（同 NebulaExclusiveTools/LeadLevelTools 模式）。

### 2.2 TeamTaskCreate

```
TeamTaskCreate(
  subject: string        // 必填，祈使句标题（复用 TaskCreate 规范）
  description: string    // 必填，做什么
  activeForm: string?    // 进行时态，用于 spinner（复用）
  blockedBy: string[]?   // 创建时即声明依赖（本任务依赖的 team 任务 ID）
)
```

- **teamName 不传**——从调用上下文推断（`ToolContext.teamName`，团队 agent spawn 时注入）。Manager 属于唯一 team，无歧义；同时杜绝跨 team 写入（无 teamName 参数 = 无越权面）。
- 返回：`Task created: <subject> (ID: <id>)`（复用 ee063703 的 taskId 返回先例）。
- 创建后状态 = `pending`；触发 `teamTaskListUpdate` WS 广播。

### 2.3 TeamTaskUpdate + 四态状态机（U1）

```
TeamTaskUpdate(
  taskId: string                 // 必填，team 任务 ID（同 team 域内）
  status: "pending"|"in_progress"|"completed"|"failed"?
  addBlocks: string[]?           // 本任务 block 的任务
  addBlockedBy: string[]?        // 本任务依赖的任务（必须先完成）
  removeBlocks: string[]?
  removeBlockedBy: string[]?
  subject/description/activeForm: ?   // 可选改
  note: string?                  // 追加结果备注（复用 C2 note 语义）
  noteLinks: string[]?
)
```

**四态转移矩阵**（team 域专用，独立于 session 域矩阵——session 域零回归）：

| from \ to | pending | in_progress | completed | failed |
|---|---|---|---|---|
| pending | ✅ no-op | ✅ | ✅ | ✅ |
| in_progress | ❌ | ✅ no-op | ✅ | ✅ |
| completed | ❌ | ❌ | ✅ no-op | ❌（terminal） |
| failed | ❌ | ❌ | ❌ | ✅ no-op（terminal） |

与现有五状态 TaskUpdate 的关系：
- **数据模型同一枚举**：`TaskStatus` 枚举保留全部值（含 needs_confirmation/dismissed），team 域转移矩阵**不产生** needs_confirmation/dismissed 状态；session 域矩阵（TaskStore.scala:166-205 的 isValidTransition/isValidTransitionFor）**原样不动**。
- 差异语义：session 域"completed 保留给用户"（C15，agent 不能直接置）；**team 域 Manager 可直接置 completed**（owner 全权验收，无用户环节，U1）。
- 无 dismissed：team 任务由 Manager 管理，失败即 failed，不做用户侧清理语义。
- failed 为 terminal（与 session 域一致）；重开失败任务 = 新建任务或后续扩展（backlog）。

### 2.4 block 语义（需求④，原样复用）

- 字段：`Task.blocks: List[String]` / `Task.blockedBy: List[String]`（TaskModel.scala:88-89 已存在）。
- 变更：`addBlocks` / `addBlockedBy` / `removeBlocks` / `removeBlockedBy`（TaskUpdateInput 已存在）。
- **环检测原样复用**：`TaskStore.hasCycle`（DFS，TaskStore.scala:208-228）——team 域 update 时对同 team 任务集校验，成环报 `IllegalStateException`。
- 引用作用域：block 引用**仅限同 team 任务**（Manager 只操作自己 team）。MVP 不校验引用存在性（与 session 域现状一致，只做环检测）；可选增强：校验被引用 ID 存在且同 team（阶段 3 backlog）。
- 删除清理：`TaskStore.delete` 的引用清理逻辑复用（删除任务时从其他任务 blocks/blockedBy 摘除）。

### 2.5 WS 事件

- 新增 `teamTaskListUpdate`：`{type, team, tasks: [...]}`——任何 TeamTask* 变更后广播（复用 TaskToolHelper.emitTaskListUpdate 模式，改传 teamKey）。
- 前端 team 面板监听该事件实时刷新（与 taskListUpdate 的 session 面板对称）。

---

## 3. 权限模型（需求②③ + U2）

| 角色 | TeamTaskCreate | TeamTaskUpdate | TeamTaskList | 说明 |
|---|---|---|---|---|
| **Manager（owner）** | ✅ | ✅ | ✅ | 创建 + 全权状态控制 + 自查 |
| **Nebula** | ❌ | ❌ | ✅ 只读 | 可查任意 team（需求③），**无任何变更工具** |
| **team 成员（agent）** | ❌ | ❌ | ❌（U2：MVP 不注入） | 成员看面板（人类用户侧），agent 无工具 |

**机制层注入**（不靠 agent.json 手动声明——#381 教训）：

- `AgentCore.buildAllowedToolSet` 增加参数 `isTeamLead: Boolean = false`；FlowTreeActor 团队 agent spawn 时传 `agentName == teamDef.lead`（该处已有 isManager 判定，FlowTreeActor.scala:477）。
- `isTeamLead=true` → 工具集 ∪ `TeamTaskCreate/TeamTaskUpdate/TeamTaskList`。
- Nebula → 工具集 ∪ `TeamTaskList`（只读；Nebula 特判处 AgentCore.scala:1288-1289 加）。
- **剥离规则**（防越权/防递归，复用 SubTask worker leaf 模式 AgentCore.scala:1315）：
  - SubTask worker、flow 节点 agent（一次性 flow 内）、Mail ask fork 上下文 → 剥离全部 TeamTask*（加入 `ForkSideEffectTools` 与 leaf 剥离集）。
  - depth ≥ 2 的 `"*"` 工具 agent → 剥离 TeamTask*（同 LeadLevelTools 处理）。
  - **flow 节点剥离属于「安全隔离」（U5 裁定），非衔接设计**：flow 节点是执行 leaf，不应拥有任何变更团队任务的能力——这是通用 leaf 剥离规则（SubTask worker 同款）的延伸，与「任务工具与 flow 体系是否耦合」无关。任务工具的全部设计（状态机/block/存储/前端）不依赖 flow 存在与否。

**ToolContext 扩展**：`teamName: Option[String]`——团队 agent spawn 时注入；TeamTask* 工具要求 `ctx.teamName` 存在否则 `ToolError("Team task tools are only available to team agents")`（非团队上下文硬拒绝，双保险）。

---

## 4. 与现有任务系统的关系（任务 2 §3 + U3：扩展现有体系）

### 4.1 数据模型扩展（向后兼容红线）

`Task` 增加两个字段（withDefaults 兼容，qa R1 红线同款——存量 JSON 零迁移）：

```scala
case class Task(
  // ... 现有字段不动 ...
  /** "session" = Nebula/用户域（现状）；"team" = Manager 任务域。 */
  scope: String = "session",
  /** team 域任务所属团队名（scope=="team" 时有值）。 */
  teamId: Option[String] = None
)
```

- `Configuration.default.withDefaults` 已启用（TaskModel.scala:121-122）——缺省字段解码为默认值，**535+ 存量 session 任务 JSON 零迁移**（同 completedAt/notes/events 先例）。
- 反向兼容：现有 session 域代码（TaskCreate/TaskUpdate/TaskQuery/renderForPrompt/前端 taskList.js）不感知 scope 字段，行为不变。

### 4.2 存储与复用

| 维度 | session 域（现状） | team 域（新增） |
|---|---|---|
| 目录 | `~/.nebflow/tasks/<sessionId>/` | `~/.nebflow/tasks/teams/<teamName>/`（teamName 需 path-sanitize） |
| ID | session 内 hwm 递增 | **team 内独立 hwm 递增**（每目录独立，互不干扰） |
| 原子写 | AtomicJson（55d4575e） | 原样复用 |
| 状态机 | 五态矩阵（不动） | 四态矩阵（§2.3） |
| block/环检测 | hasCycle（不动） | 原样复用（同 team 集校验） |
| note/events/归档 | TaskArchive 索引 + TaskQuery | 复用；TaskArchive 索引条目加 scope/teamId 字段（withDefaults 兼容旧索引） |

**TaskStore trait 扩展**：现有 API 全按 sessionId 走。增加 scope 维度有两种实现（实施时二选一，均不破坏现有调用）：
- A. `FileTaskStore` 内部把 scopeKey（sessionId 或 `team:<name>`）映射到目录——trait 不变，TeamTask* 工具直接复用现有 store API 传 scopeKey。
- B. trait 加 `createTeam/updateTeam/listTeam` 显式方法——类型更安全但重复。
推荐 **A**（最小侵入：目录映射一处，状态机矩阵按 scope 分支）。

### 4.3 与 Nebula 侧查询的关系

- Nebula 查 team 任务走 `TeamTaskList`（新只读工具），**不改现有 TaskQuery**（session/recent/project 语义保持——project 是 folder 维度，team 是组织维度，不混）。
- 可选（阶段 3）：TaskQuery 加 `scope=team` 复用归档索引——如无真实需求则不做，避免 scope 语义膨胀。

---

## 5. 与 flow 体系的关系（无耦合——U5 裁定）

> **用户裁定（08-25 15:1x）**：「Flow 和这个工具没有耦合」。任务工具（TeamTaskCreate/Update/List）是**独立能力**，与 flow 体系（FlowExecute/FlowReport/预定义 flow）**无关联设计**。本规格书不含任何 flow 衔接设计。

**删除的衔接设计**（v1.0 曾有，U5 裁定删除，不再恢复）：

| 已删除项 | v1.0 表述 | 删除理由 |
|---|---|---|
| 双视图映射 | 任务=管理视图 / FlowExecute=执行视图 | 任务工具不依赖 flow 的存在；Manager 无 flow 能力时任务工具完全自洽 |
| flow 完成自动改任务状态 | flow 完成后 Manager 用 TeamTaskUpdate 推进 | flow 完成与否与任务状态无任何引擎级关系 |
| noteLinks 引用 flow 产出 | 任务完成时 note/noteLinks 引用 flowCompleted 摘要/commit/文件路径 | noteLinks 是通用引用语义（文件/URL/任务 ID，§7.1），不特指 flow 产出 |
| 任务→flow 结构化绑定 | 阶段 3 backlog：可选 `flowRef` 字段 | 无耦合即无绑定字段 |
| 工具集合并章节 | BaseTools + Mail + SubTask + FlowExecute + TeamTask* | TeamTask* 注入与 FlowExecute 注入彼此独立（各自机制层注入），不构成合并工具集 |

**保留项（标注为「安全隔离」，非衔接）**：
- **flow 节点剥离 TeamTask***：一次性 flow 的节点 agent 是执行 leaf——剥离 `TeamTaskCreate/TeamTaskUpdate/TeamTaskList`（防 flow 节点越权改团队任务，同 SubTask worker leaf 剥离，AgentCore.scala:1315 模式）。这是**通用 leaf 安全隔离**的延伸，不是 flow 与任务工具的耦合设计（§3 剥离规则、§8 阶段 1 注入断言）。

**独立性说明**：TeamTask* 的全部设计（状态机 §2.3、block §2.4、存储 §4.2、前端 §6）在「flow 体系存在 / 不存在」两种世界里都成立；flow 体系（动态 flow 重设计，见 `20260825_flow-redesign-research.md`）同样不依赖任务工具——两个体系各自独立演进，互不感知。

---

## 6. 前端设计（任务 2 §5——team 面板任务区）

### 6.1 数据流

- REST：`GET /api/teams/mounted` 响应每个 team 增加 `tasks` 数组（一次拉全，复用现有轮询/挂载恢复路径；flowTeams.js 已消费该端点）。
- WS：`teamTaskListUpdate`（§2.5）实时刷新（复用 25e7193c 的 WS 挂载基础——connect 恢复挂载后拉 tasks）。
- 渲染：flowTeams.js 的 team 卡片内新增 Tasks 区（位于 agent 网格与 Flows 区之间——团队"正在做什么"优先于 flow 明细）。

### 6.2 布局（team 卡片内）

```
┌─ Team Card ────────────────────────────────────────┐
│ ReminderIsland                [rules] [inbox] ●idle │
│ ┌─ agents ──┐（现状磁贴不动）                        │
│ │ Manager   │ swift-dev   qa-swift                  │
│ └───────────┘                                      │
│ ── Tasks (3) ────────────────────────────────      │
│  ◐ #7 修复焦点链内存泄漏         in_progress · 2m   │
│  ⏸ #6 刘海区 popover 键盘链      pending · ⛔ dep #7 │
│  ⏸ #5 IME 五域回归测试           pending             │
│ ── Flows ──（现状 DAG 行不动）                      │
└────────────────────────────────────────────────────┘
```

- **任务行**：状态徽章 + `#id` + subject（截断 30 字符）+ 更新时间 + 依赖徽章。
- **状态徽章四色**（CSS 变量，禁止硬编码 hex）：pending=灰（`var(--color-text-muted)` 边框）、in_progress=蓝（`var(--color-primary)`）、completed=绿（`var(--color-success)`）、failed=红（`var(--color-error)`）。
- **block 依赖徽章**：`blockedBy` 非空 → `⛔ dep #7`（灰字小徽章）；`blocks` 非空 → `🔒 blocks #9`（可选，MVP 只显示被依赖方向）。悬停 tooltip 显示完整依赖列表（`title` 属性即可，零 JS）。
- **进度概要**（可选增强）：卡片 summary 区显示 `3 active · 1 blocked`（复用现有 team-card-summary 位置，25e7193c 已有 summaryText）。

### 6.3 交互

- **只读展示**：前端无编辑按钮、无状态切换控件——所有变更走 Manager 的 TeamTask* 工具（用户通过对话让 Manager 管理，或观察团队工作）。
- 点击任务行展开 description + notes（可选，仿 taskArchive 视图）；MVP 折叠展示 subject + 徽章即可。
- 空态：`No team tasks — Manager 尚未创建任务`（复用 team-empty 样式）。
- block 依赖图（**阶段 3 增强**，MVP 不阻塞）：任务数 ≤ 15 时用内联 SVG 拓扑图（dagre/d3 或 graphviz 预生成），>15 降级列表。

### 6.4 可断言验收点（二值）

| # | 断言 | 验证方式 |
|---|---|---|
| A1 | `GET /api/teams/mounted` 每个 team 含 `tasks` 数组，元素含 id/subject/status/blockedBy/blocks | curl + jq 断言字段存在 |
| A2 | team 任务按 team 隔离：team A 的 tasks 不含 team B 任务 | 预置两 team 任务后 curl 断言 |
| A3 | 前端 team 卡片渲染 Tasks 区：`.team-tasks` 容器存在，任务行数 = 后端 tasks 长度 | Playwright 打开 team 面板 → `querySelectorAll('.team-task-row').length` 断言 |
| A4 | 状态徽章映射正确：in_progress → `badge-in_progress` class + 正确文案 | Playwright 断言 class/文本 |
| A5 | block 徽章：blockedBy 非空任务显示 `dep #<id>` 徽章；无依赖任务无徽章 | Playwright 断言 |
| A6 | WS 实时刷新：Manager 工具变更任务后，`teamTaskListUpdate` 到达 → 面板任务行更新（无全量刷新） | 后端模拟变更 + Playwright 监听 WS 帧断言 DOM 更新 |
| A7 | 空态：team 无任务时显示空态文案 | Playwright 断言 |
| A8 | 暗色模式：任务徽章在 dark/light 下均清晰（CSS 变量） | Playwright 切换 prefers-color-scheme 截图人工确认 |
| A9 | 响应式：375px 视口下任务行不溢出（subject 截断生效） | Playwright 375x667 截图断言 |

---

## 7. 与现有任务面板的差异与兼容（任务 3）

### 7.1 差异对照

| 维度 | 现有任务面板（session 域，用户侧） | Team 任务（team 域，Manager 侧） |
|---|---|---|
| 状态机 | 五状态 + dismissed（pending/in_progress/needs_confirmation/completed/failed/dismissed） | **四状态**（pending/in_progress/completed/failed），无 needs_confirmation、无 dismissed |
| 完成确认 | needs_confirmation → 用户点圆确认（C15：agent 不能直接 completed） | **Manager 直接置 completed**（owner 全权，无用户环节） |
| 打回 | 用户打回：needs_confirmation → in_progress + `[打回任务]` 注入块 + returnCount | **无打回**（无用户确认环节） |
| 创建者/owner | Nebula/根 agent（session 内） | **Manager**（团队域） |
| 查询权限 | Nebula 全权；用户面板展示 active session | Manager 全权；**Nebula 只读**；用户面板 team 卡片只读展示 |
| human/agent 双区 | taskKind=human（待办区，用户点圆）/agent（任务区） | **无 human 语义**（团队任务全归 Manager） |
| 嵌套 | parentTaskId 层级树 | **无嵌套**（扁平清单 + block 依赖表达顺序，U4 无 assignee 同源） |
| 引用 | noteLinks（文件/URL/任务 ID） | 复用 noteLinks（通用引用语义；U5：不特指 flow 产出） |
| block 依赖 | blocks/blockedBy + 环检测 | **原样复用**（需求④核心） |
| 展示位置 | 用户消息区右侧/下方任务面板（taskList.js） | **team 面板卡片内**（flowTeams.js） |
| 归档检索 | TaskArchive + TaskQuery（session/recent/project） | TeamTaskList（team 域）；归档检索阶段 3 可选 |

### 7.2 兼容性保证

1. **数据零迁移**：Task 加 scope/teamId 字段走 withDefaults——存量 session 任务 JSON 不动（qa R1 红线同款）。
2. **session 域零回归**：五态矩阵、C15/C22、human/agent 双区、打回流程全部不动；TeamTask* 是纯增量工具族。
3. **工具集不冲突**：TeamTask* 新名不与 Task*/Flow*/Mail/SubTask 撞名；Nebula 的工具集多一个只读 TeamTaskList，不影响现有 TaskCreate/TaskUpdate 行为。
4. **前端不冲突**：taskList.js（session 面板）与 flowTeams.js（team 面板 Tasks 区）互不依赖；WS 事件 taskListUpdate 与 teamTaskListUpdate 分域。
5. **归档索引兼容**：TaskArchive 索引条目加 scope/teamId（withDefaults），旧索引读取不受影响。

---

## 8. 分阶段实施 + 验收条件

> 每阶段验收以**冒烟测试硬性首位**（Explorer 红线规则）。涉及服务端，一律 `sbt run` 真实启动 + curl/WS 断言，不 mock。

### 阶段 1：Backend 工具（核心）

**改动**：
- `TaskModel.scala`：Task 加 `scope`/`teamId` 字段（withDefaults）。
- `TaskStore.scala`：FileTaskStore 目录映射支持 team scopeKey（`teams/<teamName>/`）；update 按 scope 分支四态/五态矩阵；hasCycle 复用（同 scope 集校验）。
- `core/tools/TeamTaskCreateTool.scala` / `TeamTaskUpdateTool.scala` / `TeamTaskListTool.scala`（新，复用 TaskToolHelper 模式）。
- `ToolContext` 加 `teamName: Option[String]`；团队 agent spawn 注入。
- `AgentCore.scala`：`buildAllowedToolSet` 加 `isTeamLead` 参数 + Nebula 只读 TeamTaskList + 剥离规则（fork/leaf/depth≥2）。
- `FlowTreeActor.scala`：团队 spawn 传 isTeamLead。
- WS：`teamTaskListUpdate` 事件（TaskToolHelper 扩展）。

**验收（冒烟硬性首位）**：
- [ ] **冒烟测试**：`sbt run` 真实启动 → 模拟 Manager 会话调用 `TeamTaskCreate`（含 blockedBy）→ `TeamTaskUpdate`（in_progress → completed + note）→ 断言：磁盘 `~/.nebflow/tasks/teams/<team>/` 生成任务 JSON、WS 收到 `teamTaskListUpdate`、`TeamTaskList` 返回正确状态。
- [ ] 状态机单测：四态矩阵全 16 格逐一断言（合法/非法转移）；非法转移（如 completed→in_progress）报 `IllegalStateException`。
- [ ] block 单测：addBlockedBy 成环 → 拒绝报错；正常依赖 → blockedBy/blocks 字段正确；删除任务 → 其他任务引用被清理。
- [ ] 目录隔离单测：session 域与 team 域任务互不可见；team A 与 team B 互不可见；hwm 独立。
- [ ] 向后兼容单测：用存量 session 任务 JSON（无 scope 字段）解码 → scope 默认 "session"，现有五态矩阵行为不变。
- [ ] 注入断言：单元测试 `buildAllowedToolSet`——isTeamLead=true 含 TeamTask* 三工具；Nebula 含 TeamTaskList 不含 Create/Update；普通成员（isTeamLead=false）不含任何 TeamTask*；SubTask worker / flow 节点（安全隔离，U5）/ fork 上下文剥离全部 TeamTask*。
- [ ] 非团队上下文拒绝：TeamTask* 在无 teamName 的会话调用 → `ToolError`。
- [ ] 回归：现有 TaskCreate/TaskUpdate/TaskQuery/session 面板全链路跑通一次，行为不变。

### 阶段 2：前端 team 面板任务区

**改动**：
- `RestApiRoutes.scala`：`/api/teams/mounted` 响应加 `tasks`（按 teamName 读 team 域 TaskStore）。
- `flowTeams.js`：team 卡片加 Tasks 区（渲染 + 状态徽章 + block 徽章 + 空态）。
- `flowTeams.css`（或 team 样式）：徽章/行/依赖样式（CSS 变量）。
- `ws.js`：`teamTaskListUpdate` 事件路由到团队面板刷新。

**验收（冒烟硬性首位）**：
- [ ] **冒烟测试**：`sbt run` 真实启动 → curl `GET /api/teams/mounted` 断言每个 team 含 `tasks` 数组且字段齐全（A1）→ Playwright 打开 team 面板断言 Tasks 区渲染（A3-A5）。
- [ ] A2 team 隔离断言（curl）。
- [ ] A6 WS 实时刷新：真实 Manager 工具调用 → `teamTaskListUpdate` → Playwright 断言 DOM 行数/徽章更新（无全量刷新）。
- [ ] A7 空态 / A8 暗色 / A9 响应式（Playwright 截图人工确认 + 断言）。
- [ ] 静态资源断言：curl `/js/flowTeams.js`、`/css/*.css` 200 且内容非空。
- [ ] 回归：agent 磁贴、Flows DAG 行、mailbox/rules 按钮行为不变（Playwright 冒烟）。

### 阶段 3：集成（Nebula 只读 + 检索增强）

**改动**：
- Nebula 端到端只读验证（工具已注入，此阶段做真实场景验证 + 文档）。
- Manager 工作流规范文档（创建任务 → 调度派活（Mail/SubTask）→ 依结果推进状态 + note → 前端面板反映——**无 flow 衔接**，U5）。
- 可选：TaskArchive 索引 scope/teamId + TeamTaskList 历史检索（`status=completed` 过滤 + since）。
- 可选增强：block 引用存在性校验（同 team）；前端 block 依赖 SVG 图（任务 ≤15）。

**验收**：
- [ ] **冒烟测试（Nebula 只读）**：`sbt run` 真实启动 → Nebula 会话调用 `TeamTaskList` 查 team 任务 → 断言返回正确；调用 `TeamTaskCreate/TeamTaskUpdate`（不存在于 Nebula 工具集）→ 断言工具不存在/调用被拒（只读硬约束）。
- [ ] 端到端工作流：真实场景——Manager 建 3 任务（含 block 链）→ 调度执行（Mail/SubTask 派活）→ Manager 依结果 TeamTaskUpdate 推进（in_progress → completed + note）→ 前端面板实时反映 → Nebula TeamTaskList 查看到最终状态（端到端覆盖：创建 → 执行 → 推进 → 展示 → 查询）。
- [ ] 回归：session 域任务、五态打回、用户确认全链路不变；9 个存量 team 挂载正常。

---

## 9. 代码证据（现状分析引用）

| 文件 | 关键位置 |
|---|---|
| `src/main/scala/nebflow/core/task/TaskModel.scala` | TaskStatus 枚举 L7-46；Task L81-107（blocks/blockedBy L88-89）；TaskUpdateInput L158-174（addBlocks/addBlockedBy）；withDefaults L121-122 |
| `src/main/scala/nebflow/core/task/TaskStore.scala` | isValidTransition 矩阵 L166-205（C15/C22）；hasCycle L208-228；update 依赖合并 L263-325；renderForPrompt L404-458；delete 引用清理 L500-524 |
| `src/main/scala/nebflow/core/tools/TaskCreateTool.scala` | 创建接口 L94-109（返回 taskId + taskListUpdate） |
| `src/main/scala/nebflow/core/tools/TaskUpdateTool.scala` | 更新接口 L142-213（batch + 依赖 + note） |
| `src/main/scala/nebflow/core/tools/TaskQueryTool.scala` | 只读检索 L92-146（session/recent/project） |
| `src/main/scala/nebflow/core/tools/TaskToolHelper.scala` | emitTaskListUpdate L11-21；归档刷新 L31-50 |
| `src/main/scala/nebflow/core/tools/registry.scala` | L33-35 注册 TaskCreate/TaskUpdate/TaskQuery（TaskDeleteTool 未注册=死代码） |
| `src/main/scala/nebflow/agent/AgentCore.scala` | buildAllowedToolSet L1268-1329（LeadLevelTools L1293、leaf 剥离 L1315、fork L1325）；NebulaExclusiveTools L1667-1672；LeadLevelTools L1674；fixedToolsFor L1733-1738；任务注入仅 Nebula L494 |
| `src/main/scala/nebflow/core/flow/FlowTreeActor.scala` | TeamSessionRegistry L32-75；registerSession L475；isManager L477 |
| `src/main/scala/nebflow/gateway/RestApiRoutes.scala` | buildMountedTeamsJson L2210-2250（agents: name/sessionId/status/manager） |
| `src/main/scala/nebflow/gateway/WebSocketRoutes.scala` | getTeams → teamList L1607-1630；WS connect 恢复挂载 L479-484（25e7193c） |
| `src/main/resources/web/js/flowTeams.js` | team 卡片渲染（header/agent 磁贴/Flows DAG 行）；renderTeamsPanel |
| `src/main/resources/web/js/taskList.js` | session 任务面板（双区/圆圈/打回）——不冲突参考 |
| `src/main/resources/web/js/ws.js` | teamList 路由 L167；team- 前缀事件转换 L492-511 |
| `~/.nebflow/teams/ReminderIsland/team.json` | lead/members 结构（Manager 为 lead） |
| `~/.nebflow/teams/ReminderIsland/agents/Manager/agent.json` | tools=`["Read","Grep","Glob","Bash","Mail"]`（非 "*"→当前无 TaskCreate） |
| git 历史 | 38f07802（prompt 注入）/ bcd75e55（TaskList 移除）/ a78f77d0（五状态）/ bb9bb240（block 依赖）/ cffa714b（归档）/ 25e7193c（WS 挂载） |

---

## 10. 待确认/backlog（不阻塞 MVP）

- Manager turn 注入任务清单（仿 renderForPrompt，阶段 3 可选——TeamTaskList 工具已覆盖"查看"需求）。
- 失败任务重开（failed → in_progress，当前 terminal 语义与 session 域一致）。
- block 引用存在性校验（同 team）。
- 前端 block 依赖拓扑图（任务 ≤15 用 SVG）。
- 成员可见性放开（U2 后续：成员 TeamTaskList 只读——工具注入点已预留 isTeamLead 参数可扩展 isTeamMember）。
