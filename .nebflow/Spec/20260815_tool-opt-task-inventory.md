# 任务工具「人类视角」改造方案 + 全工具体检清单

> 生成：2026-08-15 20:30 ｜ 分支：archive/scala (7408a4b4) ｜ 角色：纯分析，不改代码
> 对应需求原话：「任务工具必须要从人类的角度，知道干过什么，任务细节可追踪，如何按项目区分。」

---

## 0. 结论摘要

| 痛点 | 根因（源码定位） | 核心对策 | 优先级 |
|------|------------------|----------|--------|
| 完成后消失，无历史 | 前端只渲染 active 任务（taskList.js:14,32），completed 后 300ms 淡出 | 数据**本来就没删**——补可见性：前端「今日完成」折叠条 + 档案视图 | 今晚 |
| 无完成时间/产出沉淀 | Task 模型无 completedAt、无 notes 字段（TaskModel.scala:31-42） | 模型加 completedAt + notes（append-only）+ events | 今晚/本周 |
| description 覆盖丢历史 | update 直接 copy 覆盖（TaskStore.scala:211） | 变更自动记入 events 事件流 | 本周 |
| 无法按项目区分 | 任务按 sessionId 隔离，项目仅靠 prompt 约定"第一层=项目名"（TaskCreateTool.scala:29-38） | 复用 SessionStore folderId 链路做聚合索引；TaskQuery 按 project 查询 | 本周 |
| Agent 无查询入口 | 工具只有 Create/Update，无 TaskQuery；system prompt 只注入 active | 新增 TaskQuery 工具（归档不注入 prompt，零 token 膨胀） | 本周 |

**最重要的事实**：`~/.nebflow/tasks/` 下 134 个 session 目录、535 个已完成任务**全部留存磁盘**（FileTaskStore 从不删除终态文件，除非 deleteAll）。痛点不是"数据丢了"，是"没有入口"。改造成本远低于预期——主要是补元数据 + 查询 + 前端视图。

---

## Part A. 任务工具现状取证

### A1. 数据模型（src/main/scala/nebflow/core/task/TaskModel.scala）

```scala
case class Task(
  id: String,                    // 会话内自增 "1","2"...（.highwatermark 分配）
  subject: String,
  description: String,           // 可被 TaskUpdate 整体覆盖，旧值无留痕
  activeForm: Option[String],
  status: TaskStatus,            // Pending | InProgress | Completed | Failed | Dismissed
  parentId: Option[String],      // 树结构；「项目」仅靠 prompt 约定用第一层充当
  blocks / blockedBy: List[String],
  createdAt / updatedAt: Option[String]
  // 缺：completedAt、产出链接、变更历史、项目字段
)
```

### A2. 生命周期与可见性

![现状：完成后进入黑洞](/tmp/task-lifecycle.svg)

- 状态机（TaskStore.scala:146-160）：`pending→in_progress→completed/failed` 为合法主链；`completed/failed→dismissed`；dismissed 是唯一"清除"态。
- **system prompt 注入**（AgentCore.scala:324-326 → TaskStore.renderForPrompt:246-274）：每轮作为 user-turn reminder 注入，**只含 pending+in_progress**，且已做成不进 systemStable 的缓存友好设计（注释：Cache v2）。
- **前端**（web/js/taskList.js:32）：`tasks.filter(t => activeStatuses.has(t.status))` —— WS 的 taskListUpdate 消息里**其实带着全部任务**（TaskToolHelper.scala:13 用的是 `store.list` 全量），前端把 completed/failed 过滤掉播放 300ms 淡出动画后丢弃。→ 前端改造的数据通道已就绪。
- **用户 dismiss**（WebSocketRoutes.scala:1397-1426）：dismissTask 只对终态任务生效，之后 listVisible 排除它。

### A3. 存储

- 路径：`~/.nebflow/tasks/<sessionId>/<taskId>.json`（每任务一文件）+ `.highwatermark`。
- 实测：134 个 session 目录，3.6MB，535 个 `status:"completed"` 文件。
- 写入即 `os.write.over` 全量覆盖（TaskStore.scala:62-64），无 append 通道、无跨会话索引。

### A4. 项目维度的现状与可复用机制

- 任务**无任何项目字段**；TaskCreateTool 的 description（29-38 行）用 prompt 约定「第一层任务必须是项目名」——纯约定，agent 不遵守时无结构可查，且换会话即失效。
- SessionStore（gateway/SessionStore.scala）已有完整 folder 体系：`SessionMeta.folderId`、`Folder(projectRoot, agentName, parentId)`、`resolveProjectRoot`、`getFolderName`。**session→folder 映射已存在**，任务归档做项目聚合可以直接复用，不必给 Task 加项目字段。

### A5. 三套任务体系分裂（顺带取证）

| 体系 | 存储 | 消费方 |
|------|------|--------|
| FileTaskStore（TaskCreate/Update） | tasks/<sessionId>/<id>.json | 前端任务卡 + prompt reminder |
| SubAgentTaskStore（Delegate/SubTask） | subagent-tasks/<parentSessionId>.json（完成保留短时后 prune） | 前端子代理状态 |
| ScheduledTaskStore（Schedule） | scheduler 目录 | 前端定时任务面板 |

三套互不引用。统一是大工程，本期不做，列入 backlog（C7）。

---

## Part B. 任务工具改造设计

### 改造总览

![改造后：完成后进入档案](/tmp/task-after.svg)

### B1. 数据模型变更（TaskModel.scala）

```scala
case class Task(
  // —— 既有字段不动 ——
  id, subject, description, activeForm, status, parentId, blocks, blockedBy,
  createdAt, updatedAt,
  // —— 新增（全部带默认值，向后兼容旧 JSON）——
  completedAt: Option[String] = None,   // 进入 completed/failed 的时刻
  notes: List[TaskNote] = Nil,          // append-only：执行过程沉淀的关键产出
  events: List[TaskEvent] = Nil         // append-only：状态/描述/依赖变更流水
)

case class TaskNote(
  content: String,          // 一句话结论（如 "修复完成，测试 790 通过"）
  links: List[String] = Nil,// 结构化产出：文件路径(/tmp/x.svg)、commit sha、URL
  at: String,               // ISO-8601
  by: Option[String] = None // 写入者 agent 标识（多 agent 协作时溯源）
)

case class TaskEvent(
  at: String,               // ISO-8601
  kind: String,             // created | status | description | dependency | note | dismissed
  detail: String            // 摘要：status→"in_progress→completed"；description→"旧值前120字 ⇒ 新值前120字"
)
```

**关键兼容风险——circe 默认值解码**：`io.circe.generic.semiauto.deriveCodec` 对缺失字段**不应用默认值**，旧任务文件（无 notes/events/completedAt 字段）decode 会直接失败，`list()` 把它们当 corrupted 静默跳过（TaskStore.scala:135-139）——等于变相丢档。必须改用：

```scala
import io.circe.derivation.{Codec, Configuration}
given Configuration = Configuration.default.withUseDefaults(true)
given Codec[Task] = Codec.AsObject.derived
```
（TaskNote/TaskEvent 同理；现有 enum Codec 保持不变。）

**events 上限**：每任务保留最近 50 条，超出丢弃最旧（防长任务把单文件撑大）。

### B2. TaskStore 变更（TaskStore.scala）

| 方法 | 变更 |
|------|------|
| `update` | ① status 变为 completed/failed 时自动写 `completedAt`；② status/description/依赖变更自动 append `TaskEvent`；③ description 覆盖前把旧值摘要进 event.detail |
| `create` | append `TaskEvent(created)` |
| 新增 `query(sessionId, filter: TaskQueryFilter)` | 按 status/since(createdAt 或 completedAt)/keyword(subject+description) 过滤，返回按 completedAt/createdAt 倒序，limit 默认 30 |
| 新增 `listAllSessions`（供索引器） | 枚举 tasks/ 下全部 session 目录 |
| `renderForPrompt` | **一行不改**——保持只渲染 active（prompt 零膨胀是硬约束） |

### B3. 项目聚合索引（新文件，gateway 层）

`~/.nebflow/tasks/_index.json`（SessionStore `_index.json` 同款模式）：

```json
{
  "updatedAt": 1755267000000,
  "entries": [
    { "sessionId": "003021f7-...", "folderId": "fld_xxx", "folderName": "Nebflow",
      "taskId": "6", "subject": "P0: remote-exec 鉴权加固",
      "status": "completed", "createdAt": "...", "completedAt": "...",
      "noteCount": 2, "hasLinks": true }
  ]
}
```

- 构建时机：网关启动时全量重建（134 session × 平均 4 文件，IO 量 < 1s 级）+ 每次 TaskCreate/Update/Dismiss 后增量刷新该 session 段（挂进 TaskToolHelper.emitTaskListUpdate 同一管道）。
- **folderId 在索引器解析**（`SessionStore.sessions` 里查 sessionId→folderId），TaskStore 保持 core 层纯净、不反向依赖 gateway。孤儿 session（已被删除）folderId 为 null，归入"未分类"。

### B4. Agent 查询工具：TaskQuery（新文件 TaskQueryTool.scala + registry.scala 注册）

```
TaskQuery {
  scope:    "active" | "recent" | "project" | "session"   // 默认 recent
  project:  string    // folder 名（scope=project 必填，如 "Nebflow"、"CZT"）
  since:    string    // "today" | "yesterday" | "7d" | ISO-date，作用于 completedAt/createdAt
  status:   string    // completed | failed | dismissed | any（默认 any）
  keyword:  string    // subject/description 模糊匹配
  limit:    number    // 默认 30
}
```

- 输出为紧凑表格 + 单行统计：`#6 ✓ P0: remote-exec 鉴权加固 · 06-09 13:48 · 报告:/tmp/x.md · 2 notes`。
- description 明确写：「回答用户『昨天/上周干成了什么』用 scope=recent；『Nebflow 项目做了什么』用 scope=project」——把人类问法映射进去。
- 依赖：scope=project/recent 走索引器（gateway）；scope=session 走 TaskStore.query。子代理（无索引器访问权）降级为仅 session 查询。

### B5. TaskUpdate 增强（TaskUpdateTool.scala）

新增输入参数（批处理同样适用）：

```
note:       string         // 追加 TaskNote（与 status 变更可同调用："完成+沉淀"一步走）
noteLinks:  array[string]  // 产出路径/commit/URL，随 note 存入 links
```

description 文案同步改：鼓励 completed 时顺手带一条 note（"修了什么、验证结论、产出在哪"），notes 是给人类档案看的，不是给 agent 自己看的。

### B6. 前端改造（web/js/taskList.js + 新增 taskArchive.js）

改动量控制（数据通道已存在，taskListUpdate 已带全量）：

1. **「今日完成」折叠条**（taskList.js 小改）：active 列表渲染逻辑不动；卡片底部追加一行 `今日完成 N · 查看`（completedAt 在今天的终态任务数），点击展开最近 10 条（subject + completedAt + 首条 note.content），再点「查看全部」进档案视图。 dismissed 不计入。
2. **任务档案视图**（新 taskArchive.js + Canvas 面板）：REST 拉取，按项目（folder）分组 → 时间线倒序；条目详情含 events 时间线和 notes.links（文件路径点击走现有 popFile，URL 点击开 iframe——复用 Pop 的 itemType 检测逻辑）。布局：左项目栏 / 右时间线，窄屏折叠为单栏。
3. WS/REST：新增 `GET /api/nf-tasks?folderId=&since=&status=&keyword=&limit=`（RestApiRoutes）；前端档案视图与 TaskQuery 工具共用此端点（agent 走工具、人类走 UI，同一数据源）。

### B7. 兼容约束（红线）

- renderForPrompt 输出与现在 **byte-identical**（归档绝不注入 prompt）——写单测快照保证。
- 旧任务文件 decode 兼容——fixture 测试（无新字段的旧 JSON → notes=Nil, events=Nil, completedAt=None）。
- taskListUpdate WS 消息结构不变，只新增字段——旧前端忽略未知字段，无破坏。
- 磁盘不新增目录结构（只加一个 _index.json），Tasks 文件格式向后兼容。

---

## Part C. 全工具体检表

三维度：**体验**（agent 用起来顺不顺）、**token**（结果/描述进上下文的成本）、**失败模式**（错了怎么办）。严重度：🔴 高 / 🟡 中 / 🟢 低。

### C1. 文件类

| 工具 | 痛点 | 维度 | 严重度 | 建议 | 工作量 |
|------|------|------|--------|------|--------|
| Read | 512KB/2000 行全量进上下文（maxResultSize=Int.MaxValue 豁免 guard，ReadTool.scala:35），大文件一次读 = 万级 token | token | 🔴 | 大文件默认给"结构预览 + 建议 offset/limit"；超 8000 行强制分页提示 | 中 |
| Read | 读不存在的文件返回错误但 description 只说"which is fine"——初次探索时无 ls 能力，agent 只能 Bash ls 反复试探 | 体验 | 🟡 | 目录路径输入时返回前 20 项列表（不用报错给 Bash） | 小 |
| Write | **CRLF 隐患**：DiffUtil.writeFile 只归一 `\r\n`→`\n`，**孤立 `\r`**（LLM 从 CRLF 源码复制片段时常见）原样落盘，后续 Edit 的 old_string 精确匹配失败、grep 搜不到 | 失败 | 🔴 | writeFile 增加 `replace("\r","")`（保留 \r\n 已归一在前）；Edit 失败提示里检测孤立 \r 并明示 | 小 |
| Write | 覆盖已有文件时不提示与磁盘版本的差异确认（有 mtime 守卫但内容守卫只在 mtime 变化时触发，TaskModel 之外的场景够用） | 体验 | 🟢 | 现状可接受，不加噪音 | — |
| Edit | 匹配失败错误只报 not found，不给出"最接近的候选行"（常见于空白差异），agent 只能重 Read 重试一轮 | 失败 | 🟡 | 失败时附 fuzzy 最近 3 行（StringMatcher 已存在！） | 小 |
| Edit | 无 dry-run/预览模式，批量重命名 replace_all 时 agent 无法先看影响面 | 体验 | 🟢 | 暂不做，token 换来的价值低 | — |
| Glob | 按修改时间排序但**不显示时间戳**，"找最近改的文件"要再 Bash stat | 体验 | 🟡 | 结果附相对时间列（"2h ago"） | 小 |
| Grep | 默认 head_limit=250 截断，content 模式下截断提示在末尾易被忽略，agent 误以为搜全了 | 失败 | 🟡 | 截断时首行加 `[TRUNCATED showing 250/1024]` | 小 |
| Grep | 无 `--fixed-strings` 模式，搜含正则元字符的字符串（如 `$arg->x`）易踩坑 | 体验 | 🟢 | 加 literal 布尔参数 | 小 |

### C2. 执行类

| 工具 | 痛点 | 维度 | 严重度 | 建议 | 工作量 |
|------|------|------|--------|------|--------|
| Bash | **30s 自动后台对交互式命令不友好**：`sbt`/`python REPL`/`npm init` 等期待 stdin 的命令被转后台后永远不出结果，agent 也不知道命令其实在等输入 | 失败 | 🔴 | 自动后台前检测进程无 stdout 输出且退出码缺失时，先给"疑似等待输入"警告；description 教 agent 用 `</dev/null` 或 `--batch-mode` | 中 |
| Bash | 输出 30k guard 截断合理，但**无交互式 shell 能力**（`git rebase -i` 被禁、REPL 不可用），部分工作流只能绕 | 体验 | 🟡 | 维持禁令（安全正确），文档给替代方案速查 | 小 |
| Bash | description 引用 `Card` 工具（BashTool.scala:54-56）但 registry 注册名是 `SaveWorkspaceItem`（运行版本或有差异，分支内不一致）——agent 会调用不存在的工具名 | 失败 | 🔴 | 名字对齐（或本分支改为实际名）；同时审计所有工具 description 里的交叉引用 | 小 |

### C3. 通信/委派类

| 工具 | 痛点 | 维度 | 严重度 | 建议 | 工作量 |
|------|------|------|--------|------|--------|
| Mail | ask 模式 60s 后转 background，但**转后台的通知里缺原始问题上下文**，收到答案时 agent 已失去提问动机 | 体验 | 🟡 | 转后台通知附 question 摘要 | 小 |
| Mail | 地址路由规则（team 名/member/`team/agent`）对 agent 是纯记忆负担，路由错直接失败 | 体验 | 🟡 | 失败错误里附"可用地址列表"（上下文已知的 teams/members） | 中 |
| Delegate/SubTask | 两者 description 高度重叠（95% 相同文案），agent 选型犹豫、文档双份维护 | 体验 | 🟡 | 抽公共段；SubTask 注明"仅团队内自克隆"一句话差异 | 小 |
| Delegate/SubTask | 子代理产物不回流任务体系（SubAgentTaskStore 完成后短时 prune）——与任务档案打通的机会点 | 体验 | 🟡 | 完成时把 summary 写入对应 TaskNote（backlog，依赖 B 部分落地） | 中 |
| FlowTrigger | 白名单拒绝时的错误未列出当前 agent 实际可用的 flows | 失败 | 🟢 | 错误信息附 flows 列表 | 小 |

### C4. Web 类

| 工具 | 痛点 | 维度 | 严重度 | 建议 | 工作量 |
|------|------|------|--------|------|--------|
| WebSearch | **对 JS 渲染页弱**（用户已知）：Sogou/360/DDG/Baidu 全是 HTML 正则解析（WebSearchTool.scala:104-130），SPA 站点摘要为空，只有链接没有内容 | 失败 | 🔴 | 搜索结果 snippet 为空时自动对该 URL 走 WebFetch 的 Playwright 回退（能力已存在，只缺联动） | 中 |
| WebSearch | 无重试/熔断：某引擎 HTML 结构改版即静默退化为 extractGenericLinks 噪音结果 | 失败 | 🟡 | 解析结果为空时打点日志 + 结果头部标注引擎名（已有）+ 空结果换引擎重试一次 | 小 |
| WebFetch | 5min LRU 缓存对"刚改过部署想重看"场景返回旧页，且无 no-cache 参数 | 体验 | 🟡 | 加 `noCache` 布尔参数 | 小 |
| WebFetch | Playwright 回退是"if available"，不可用时错误信息没告诉 agent 该怎么办 | 失败 | 🟡 | 明确回退失败文案："建议改用 Curl 拿原始 HTML 或让用户手动打开" | 小 |
| Curl | 100k 响应上限合理；但**无重试**、无 --retry 语义，弱网 API 抖动直接失败 | 失败 | 🟢 | 5xx/超时自动重试 1 次 | 小 |

### C5. 交互/展示类

| 工具 | 痛点 | 维度 | 严重度 | 建议 | 工作量 |
|------|------|------|--------|------|--------|
| AskUserQuestion | **无超时**——用户不在场时 agent turn 无限挂起，占用会话 | 失败 | 🟡 | 可选 `timeoutMinutes`，超时返回"用户未响应"+ 建议默认项 | 中 |
| Pop | 一次只能 Pop 一个文件，"对比 A/B 截图"要两次调用两次闪烁 | 体验 | 🟢 | 支持 filePath 数组开多 tab | 小 |
| SaveWorkspaceItem | 与 Bash description 中的 `Card` 名字分裂（同 C2 第 3 条）；且 content 走 LLM 上下文，大 HTML 卡片 token 成本高 | token | 🟡 | 对齐命名；建议先 Write 文件再引用路径的文档引导 | 小 |
| Schedule | triggerAt 要求 **epoch 毫秒**——LLM 算时间戳易错（时区/毫秒位），错误率高 | 体验 | 🔴 | 支持 `"tomorrow 09:00"` / ISO 8601 本地时间字符串，服务端解析（用户时区已有） | 小 |

### C6. 上下文管理类

| 工具 | 痛点 | 维度 | 严重度 | 建议 | 工作量 |
|------|------|------|--------|------|--------|
| RemoveUnnecessary | 需 agent 主动判断+手数 rounds，实际很少被主动用；无"上下文水位"信号触发 | token | 🟡 | 上下文超 75% 时系统 reminder 提示 agent 考虑调用（注入一行，成本低） | 中 |
| ToolResultGuard（机制） | 持久化后的 preview 对 LLM 是否够用无反馈回路：persisted 文件只能靠 Read 找回 | 体验 | 🟢 | preview 里已有路径，现状够用 | — |

### C7. 版本差异备注

- 用户运行版本（v1.4.1-beta.51）的工具面（Card / Issue / CheckIssues）与本 archive/scala 分支 registry（22 个 builtin）不一致：分支内无 Issue/CheckIssues 注册，`Card` 对应本分支 `SaveWorkspaceItem`。**体检以分支代码为准**；命名对齐问题（C2 第 3 条）在运行版本可能已部分解决，实施前先 rebase 核对。

---

## Part D. 优先级排序

### 今晚可做（小时级）

| # | 事项 | 对应痛点 | 预估 |
|---|------|----------|------|
| 1 | TaskModel 加 completedAt（含 circe useDefaults 兼容改造 + 旧文件 fixture 测试）+ TaskStore.update 自动写入 | 无完成时间 | 1-2h |
| 2 | 前端「今日完成 N」折叠条（taskList.js，数据已在 WS 消息中） | 完成即消失 | 1-2h |
| 3 | Write 孤立 `\r` 清洗（DiffUtil.writeFile 一行） | CRLF 隐患 | 0.5h |
| 4 | Schedule 支持 ISO/自然语言时间字符串 | epoch 毫秒易错 | 1h |
| 5 | Bash description 工具名对齐（Card→SaveWorkspaceItem） | 幽灵工具名 | 0.5h |

### 本周做（天级）

| # | 事项 | 对应痛点 | 预估 |
|---|------|----------|------|
| 6 | TaskEvent 事件流（update 自动记录 description diff 摘要/状态迁移） | 变更历史丢失 | 0.5 天 |
| 7 | TaskUpdate note/noteLinks 参数 + TaskNote 模型 | 产出沉淀 | 0.5 天 |
| 8 | `GET /api/nf-tasks` 归档端点 + tasks/_index.json 索引器（folderId join） | 按项目区分 | 1 天 |
| 9 | TaskQuery 工具（scope/project/since） | agent 检索入口 | 0.5 天 |
| 10 | 前端任务档案视图（taskArchive.js：项目分组时间线 + notes.links 可点击） | 人类检索入口 | 1-1.5 天 |
| 11 | WebSearch 空 snippet 联动 WebFetch Playwright 回退 | JS 渲染页弱 | 0.5-1 天 |
| 12 | Grep 截断首行提示 + Glob 相对时间列 + Edit fuzzy 候选提示 | 体验三连 | 0.5 天 |

### Backlog

| # | 事项 |
|---|------|
| 13 | 三套任务体系统一档案（SubAgentTask 完成回写 TaskNote；ScheduledTask 关联 Task） |
| 14 | Bash 交互式命令"疑似等待输入"检测 |
| 15 | AskUserQuestion 超时参数 |
| 16 | RemoveUnnecessary 上下文水位自动提醒 |
| 17 | Mail 路由失败附可用地址列表 |
| 18 | Read 大文件结构预览模式 |

---

## Part E. 实施清单（文件 + 验收标准）

### E1. 后端（任务工具改造，D 项 1/6/7/8/9）

| 文件 | 改动 |
|------|------|
| `src/main/scala/nebflow/core/task/TaskModel.scala` | +TaskNote/TaskEvent case class；Task +3 字段；circe Configuration.useDefaults |
| `src/main/scala/nebflow/core/task/TaskStore.scala` | update 写 completedAt + append events；新增 query()；listAllSessions() |
| `src/main/scala/nebflow/core/tools/TaskUpdateTool.scala` | note/noteLinks 参数；description 文案更新 |
| `src/main/scala/nebflow/core/tools/TaskQueryTool.scala`（新） | 查询工具 |
| `src/main/scala/nebflow/core/tools/registry.scala` | 注册 TaskQuery |
| `src/main/scala/nebflow/gateway/RestApiRoutes.scala` | GET /api/nf-tasks |
| `src/main/scala/nebflow/gateway/TaskArchiveIndexer.scala`（新） | _index.json 构建与增量刷新；挂进 TaskToolHelper 管道 |

**验收标准（逐条二值判断）：**

1. **冒烟（硬性第一项）**：`sbt run` 真实启动 → `curl localhost:8080/api/nf-tasks?since=7d` 返回 200 + JSON 数组，含 folderName 分组字段；`curl localhost:8080/` 返回 HTML。
2. **旧档兼容**：单测用本机现有 `~/.nebflow/tasks/003021f7.../6.json`（旧格式，无新字段）做 fixture → decode 成功、notes=Nil、completedAt=None；`list()` 返回条数不因升级减少。
3. **completedAt 自动写入**：TaskUpdate status=completed → 磁盘 JSON 含 completedAt ≈ now；单测断言非空。
4. **事件流**：description 修改后 events 含 `kind=description` 且 detail 含旧值摘要；status 迁移含 `kind=status`；events >50 条时截断最旧。
5. **prompt 零膨胀**：单测快照——同一 session 下（含 5 个已完成任务）`renderForPrompt` 输出与改造前 byte-identical（归档任务不出现）。
6. **TaskQuery**：scope=recent&since=7d 返回本周完成任务；scope=project&project=Nebflow 只返回该 folder 任务；TaskQuery 模拟调用（真实 sbt test 的 tool 层测试，不 mock store）。
7. **taskListUpdate 向后兼容**：WS 消息新增字段后，现有前端（未升级）行为不变——手动验证任务卡渲染正常。

### E2. 前端（D 项 2/10）

| 文件 | 改动 |
|------|------|
| `src/main/resources/web/js/taskList.js` | 底部「今日完成 N」折叠条 + 展开最近 10 条 |
| `src/main/resources/web/js/taskArchive.js`（新） | Canvas 档案视图：项目分组时间线、events、notes.links 点击 |
| `src/main/resources/web/css/taskList.css` | 折叠条样式（遵守 CSS 变量、无 accent bar、无 emoji） |
| `src/main/resources/web/js/i18n*.js` | `task.completedToday` 等 key（中英） |

**验收标准：**

1. **Playwright 渲染**：真实启动 → 打开 localhost → 触发任务完成（或注入含 completedAt 的 taskListUpdate fixture）→ 截图 `/tmp/task-smoke.png` → 断言「今日完成」折叠条存在且数字正确。
2. **交互**：点击折叠条展开 10 条；点击「查看全部」打开档案视图；档案中 notes.links 的文件路径点击后 Canvas 打开对应文件（popFile 链路）。
3. **暗色/亮色**：两种 prefers-color-scheme 截图对比，均无对比度问题。
4. **响应式**：1280×800 / 768×1024 / 375×667 三视口截图，档案视图无溢出错位。
5. **回归**：活跃任务卡动画（entering/flipping/leaving）行为与改造前一致。

### E3. 工具体检速赢（D 项 3/4/5、12）

| 文件 | 改动 | 验收 |
|------|------|------|
| DiffUtil.scala:40 | writeFile 孤立 \r 清洗 | 单测：content 含孤立 \r → 落盘无 \r；`\r\n` 文件更新后保持 `\r\n` |
| ScheduleTool.scala | triggerAt 接受 ISO/`"tomorrow 09:00"` | 单测三格式解析正确；epoch 旧格式仍兼容 |
| BashTool.scala:54 | Card→SaveWorkspaceItem | grep 全 description 无失效工具名引用 |
| GrepTool.scala / GlobTool.scala / EditTool.scala | 截断首行提示 / 相对时间 / fuzzy 候选 | 各一条单测 |

---

## 附：设计取舍记录（供评审）

- **项目字段放哪**：不给 Task 加 projectId 字段，用 sessionId→folderId 索引时 join。理由：core 层不依赖 gateway；folder 改名/移动不需要回写任务文件；缺点是跨 session 手动移动后索引需刷新（接受，重建成本低）。
- **归档 vs 删除**：dismissed 语义保留（从活跃 UI 清除），档案中仍可见（status=dismissed 只是"用户不想再看到它在进行区"）。若用户要求彻底删除，加 archive=false 的物理删除参数（backlog）。
- **TaskQuery 与 prompt 注入**：坚持归档不进 reminder——人类视角的"干过什么"由档案 UI 与按需查询承担，不牺牲活跃上下文的 token 效率。
