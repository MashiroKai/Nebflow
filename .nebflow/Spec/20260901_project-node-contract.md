# Project + Node 阶段 0 契约（REST + WS + 数据结构）

> 状态：阶段 0（#28 0b）交付契约 —— 同步 Frontend（#27 对接用）。
> 后端：NodeRunner 共享内核（0a）+ 新模型后端（0b）。日期：2026-09-01。
> 对应实现：`nebflow/core/project/*`（ProjectStore / FlowMapStore / NodeEngine / ProjectActor）+ `nebflow/core/tools/NodeTools.scala`。

## 1. REST 端点（需 auth token）

| 方法 | 路径 | 成功 | 失败 |
|---|---|---|---|
| GET | `/api/projects` | 200 `{projects:[...]}` | 401 |
| GET | `/api/projects/<name>/flow-map` | 200 NodeList 载荷（见 §3） | 404 `{error}`（未挂载） |
| GET | `/api/projects/<name>/agent.md` | 200 `{content:"..."}`（工作区 `.nebflow/Agent.md`） | 404（项目不存在 / 无 Agent.md） |
| PUT | `/api/projects/<name>/agent.md` | 200 `{saved:true}`（body `{content:"..."}` 写回） | 404（项目不存在） |

> 路由定义细节：REST routes 挂载在 `Router("/api" -> ...)` 下，路由内部写相对段
> （`Root / "projects"`），对外 URL 即 `/api/projects...`。误写 `"api"` 段会双前缀
> `/api/api`（QA P1② 已修）。

`GET /api/projects` 载荷：

```json
{
  "projects": [
    {
      "name": "demo",
      "workspace": "/abs/path/to/workspace",
      "agentFile": "/abs/path/to/workspace/.nebflow/Agent.md",
      "description": null,
      "createdAt": 1770000000000
    }
  ]
}
```

> 说明：项目由 Nebula 用 ProjectCreate 工具创建并挂载（挂载 = FlowMapStore + ProjectActor
> 就绪）。未挂载的项目在 projects 列表可见（磁盘读），但 flow-map 端点 404。阶段 1 补启动自动挂载。

## 2. WS 事件（广播帧，`{type, project, nodeId, node}`）

| type | 触发 | 载荷 |
|---|---|---|
| `nodeCreated` | NodeEdit 创建节点 | node（§4 全量） |
| `nodeUpdated` | NodeEdit 编辑 / 状态变更（running / failed / cancelled / rewired） | node 全量 |
| `nodeCompleted` | 节点完成（结果已写 result） | node 全量（含 result） |
| `nodeRemoved` | TTL 到期移归档（终态节点 5min 显示消失） | node 空对象（前端按 nodeId 移除卡片） |

WS 事件由节点引擎经会话 wsSend 通道广播（与现有 agent 事件同通道），前端按 `type` 分发
（新增这 4 种，与现有 WS 消息类型共存，互不干扰）。

时序示例（入口节点 task+out=Nebula）：
`nodeCreated` → `nodeUpdated`(running) → `nodeCompleted`(result) —— TTL 5min 后 `nodeRemoved`。

## 3. NodeList 载荷（NodeList 工具返回；flow-map REST 同 shape）

```json
{
  "nodes": [
    {
      "id": "n-3f9a2c1b",
      "name": "scan-repo",
      "agent": "Backend",
      "skill": "code-review",
      "mcp": null,
      "preset": "fast",
      "status": "completed",
      "in": [],
      "out": "Nebula",
      "hasWorktree": false,
      "worktree": null,
      "result": "…≤500 字摘要…",
      "retries": 0,
      "createdAt": 1770000000000,
      "completedAt": 1770000001000,
      "ttlLeftSec": 299
    }
  ],
  "worktrees": ["feature-1"],
  "meta": { "project": "demo", "updatedAt": 1770000001000, "archived": 2 }
}
```

字段说明：
- `status`：`wiring`（建了但无 task/in，等接线）/ `pending` / `running` / `completed` / `failed` / `cancelled`
- `in`：上游节点 id 列表（barrier：全部上游 deliveredTo 才启动下游）
- `out`：单值——`"Nebula"`（结果注入根会话）/ 节点 id / `null`（悬空，结果保留）
- `skill` / `mcp` / `preset`：节点配置（NodeEdit 参数，可为 null）——前端 Flow Map 卡片徽标 + 详情展示（子任务 C）
- `ttlLeftSec`：终态显示剩余秒数（5min TTL）；非终态 null
- `worktrees`：`<workspace>/.nebflow/worktrees/` 下目录名（磁盘推导）
- `meta.archived`：归档区节点数（终态 5min 后移入，结果全文保留可再接线）

## 4. Node 数据结构（NodeDef，§2.1 JSON 全量）

```json
{
  "id": "n-3f9a2c1b",
  "name": "scan-repo",
  "agent": "Backend",
  "skill": null,
  "mcp": null,
  "worktree": null,
  "preset": null,
  "task": "扫描仓库…",
  "in": [],
  "out": "Nebula",
  "deliveredTo": [],
  "status": "completed",
  "result": "最终输出全文",
  "retries": 0,
  "maxRetries": 1,
  "createdAt": 1770000000000,
  "startedAt": 1770000000500,
  "completedAt": 1770000001000,
  "ttlExpireAt": 1770000004000
}
```

- `out` 语义：单权威边。设 out 时事务内同步更新目标节点 `in`（旧目标 in 移除、新目标
  in 追加）——不存在 1 对多；数组 out 在 NodeEdit 校验层拒绝。
- `deliveredTo`：该节点已收到哪些上游的结果（barrier 计数源，防改接重投）。

## 5. 持久化文件（项目工作区 `.nebflow/`）

| 文件 | 内容 | 写入者 |
|---|---|---|
| `flow-map.json` | FlowMapState：`{v:1, project, updatedAt, nodes}`（活动区） | FlowMapStore（唯一写者） |
| `flow-map-archive.json` | FlowMapArchive：`{project, nodes}`（终态 5min 后移入） | FlowMapStore |
| `Agent.md` | 项目级 agent 指令模板 | ProjectCreate（仅缺省时写） |
| `.gitignore` | 内容 `.nebflow/` | ProjectCreate（仅缺省时写） |
| `worktrees/` | 并行工作区（git worktree 管理，Node 的 worktree 参数指向其下子目录） | 用户/分发器 Bash |

项目定义：`~/.nebflow/projects/<name>/project.json`（`{name, description, workspace,
agentFile, createdAt}`）。

## 6. Node 执行语义（前端状态机参考）

- 入口节点（task 且无 in）→ NodeEdit 创建即运行（异步，非阻塞）
- 完成 → 结果写 result（持久化）→ 沿 out 投递：
  - out=Nebula → 注入根会话 `[Node '<name>' completed]\n<result>`
  - out=节点 → barrier 计数；全部上游到达 → 启动下游（输入 = task 上下文 + 各上游
    result 带 `=== Node <name> ===` 头）
  - out=null → 悬空保留；接线后（改接投递 §2.3）自动补投
- 失败 → `[Node '<name>' failed]\n<err>` 同路径投递；取消 → 不投递
- 节点无 Mail 身份、无记忆、ephemeral；**运行不设超时**（TTL 只管显示）
