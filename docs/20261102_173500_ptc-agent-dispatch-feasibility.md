---
type: design
status: 勘察（只调研设计，零实现）
title: 让 PTC 脚本编程式调度 node / kernel / subagent——能力清单、审批风险与候选设计
date: 2026-11-02T17:35:00
batch: delegate-nebflow-e616097f
tags: [ptc, programmatic-tool-calling, delegate, kernel, subagent, mcp, approval, design]
related:
  - "[[20261102_pts-ptc-defect-fixes]]"
  - "[[20261102_143609_ptc-mode-two-plugins__delegate-nebflow-226f28aa]]"
  - "[[20261028_171800_审计报告二五机制__delegate-nebflow-a13975e4]]"
---

# 让 PTC 脚本编程式调度 node / kernel / subagent——可行性勘察

> 问题：能不能让 PTC 脚本（不是模型一轮一轮调工具，而是**写一段代码**）编程式地
> 创建/驱动 flow 节点、kernel 实例、subagent？
>
> 本件**只调研设计，不写实现**；所有结论都标到 `file:line`。

---

## 0. 结论先行

1. **机制上完全可行**：引擎里 node / kernel / subagent 的创建腿都是普通 `Tool` 背后的
   普通服务方法（`NodeEditTool` / `ChainWriteTool` / `DelegateTool` / `SubTaskTool`），
   没有"必须是模型才能调"的内核限制。缺的不是能力，是**授权模型**。
2. **但当前形态的 PTC 碰不到它们**：PTC 代码里的 `tools.*` 连的是**插件自带的**
   文件工具 MCP server（`plugin.json` 的 `extensions.ptc.tool_servers` + `profiles` 闭集），
   不是宿主 `ToolRegistry`。`NodeEdit/ChainWrite/Delegate/SubTask` 一个都不在这个闭集里。
3. **`McpToolGate` 会在这一步失效**：它是"**模型发起**工具调用"这一层的闸
   （`McpToolGate.decide` 的入参是 `sessionId` + 工具名，判定后由
   `AgentSessionExecution.permissionDecision` 出卡）。PTC 执行器是**插件进程**，
   它自己发起的 `tools/call` 不经过那条路径——一次 `ptc_run` 的批准，覆盖的是整段代码里
   的 N 次内部调用。**加 `tools.spawn_*` 必须自带闸，不能指望继承。**
4. **推荐**：先做"脚本只生成计划"（候选 C，代价最低、审批模型不变、与现有
   `ChainWrite` 的图模型天然对齐），把"脚本直接 spawn"（候选 A）留到有了
   spawn 政策面 + 派生会话降权之后。纯插件侧 spawn（候选 B）**明确否决**。

---

## 1. 引擎侧现有的内部入口（谁能创建/驱动什么）

### 1.1 flow 节点：`NodeEdit` / `ChainWrite`

| 入口 | 定义 | 真正的启动点 |
|---|---|---|
| `NodeEdit` | `src/main/scala/nebflow/core/tools/NodeEditTool.scala:80` `object NodeEditTool extends Tool`、`:81` `val name = "NodeEdit"`；`call` 在 `:372`（`createNode` `:993` / `proceed` `:1125` / `editNode` `:1803`） | 建位后按 `:692` `mayAutoStart = !DelegateSessions.isDelegateSession(ctx.sessionId)` 决定是否 `startNode`（delegate 会话里只落 pending） |
| `ChainWrite` | `src/main/scala/nebflow/core/tools/ChainWriteTool.scala:51-55`（`MaxNodes: Int = 12`）；`registerChain` 在 `:317` | `:556` `NodeTools.runDetached(rt, ...)(rt.engine.startNode(id))` —— 整链原子落盘后逐 entry 起点启动 |

**权限门（三层，全部与"是不是模型"无关）**

1. **挂载面**（模型能看见什么）：`src/main/scala/nebflow/agent/AgentCore.scala:198`
   `val RootExclusiveTools = Set(...)`（含 `"ChainWrite"` `:247`、`"Subagent"`/`"AgentFlow"` `:240-241`）；
   `:276` `exclusiveToolsFor`、`:664` `DispatcherFixedTools`（含 `NodeEdit`，**无 ChainWrite**）、
   `:724` `NebflowFixedTools`、`:779` `fixedToolsFor`。
   delegate 会话拿 node 四件套：`AgentSessionExecution.scala:2530` →
   `NodeTools.scala:68` `val DelegateNodeToolsFace = Set("NodeEdit","NodeList","NodeCancel","ChainWrite")`。
2. **身份/作用域闸**（工具内部）：`ChainWriteTool.scala:264`
   `if !DelegateSessions.isDelegateSession(ctx.sessionId) then` ⇒ `CHAINWRITE_SCOPE_FORBIDDEN`；
   `:266` 传 `project` 即拒（作用域只认会话身份）。`NodeTools.scala:84-110`
   `resolveRuntime`：delegate 会话 → `resolveDelegateScope`（`DELEGATE_SCOPE_FOREIGN` 拒他人项目）。
3. **审批**：**没有**。`src/main/scala/nebflow/core/permissions.scala:168` `EditTools = Set("Write","Edit")`、
   `:183` `else true // all other tools (Read, Grep, MCP, ScriptTool, etc.) auto-approved`；
   默认档 `:149` `.fold(SafetyMode.AutoAll)`。即 NodeEdit/ChainWrite/Delegate/SubTask
   在任何档位都不出卡。

🔴 **关键机制事实**：**执行期不校验挂载面**——`AgentSessionExecution.scala:2057`
`ToolRegistry.TOOL_MAP.get(call.name) match`，`:2256` 只对"未注册名"报 `No such tool available`。
挂载面只决定模型能看到什么 schema；"谁能真调"靠工具内部的身份闸。
⇒ 从引擎进程内部（例如一个插件 broker）直接调 `NodeEditTool.call(...)`/`ChainWriteTool.registerChain(...)`
在机制上是通的，**但等于绕开审批语义**（本来也没有审批）。

### 1.2 delegate / kernel：同一条腿

- 工具：`src/main/scala/nebflow/core/tools/DelegateTool.scala:35/69`（`name = "Delegate"`），`call` 在 `:435`。
- 链路：`call` → `:519 internalSpawn` → `:726 spawnKernel`（mode=kernel）→
  `:791 private[nebflow] def spawnBackground` —— **进程内唯一 spawn 单点** →
  `:948 spawnWithPlugins` → `:1091` `NodeRunner.spawnAgentActor(system, params)` + `:1092 spawnSupervisedAdapter`。
- 权限面：没有任何审批；有的是**结构性闸**——
  `:45 MaxDepth = 5` 与 `:450 ctx.depth >= MaxDepth`、`:457 ExecutorRegistry.resolveRequested`
  （不可用显式失败）、`:834 decideMode`（双模式闸）、
  `:886 PluginInjection.allocatePreparation`（失败 ⇒ `:890` `DELEGATE_PLUGINS_REFUSED`）、
  `:894 resources.pluginMcp.acquire`。
- 派生会话的工具面**在子会话侧重算**：`AgentSessionExecution.scala:558 buildToolList`，
  `:2514 kernelFace = delegateSession && mode == kernel` ⇒ `:2518` 摘掉
  `Subagent`/`AgentFlow`/`node_report`，`:2531` 对 kernel 不追加 node 四件套。
  ⇒ **kernel 实例天然不带编排工具**（这是现成的"派生不继承编排权"隔离手段）。
- kernel 的编程入口：进程内只有 `DelegateTool.spawnBackground`（`private[nebflow]`），
  现存复用点三个：`MailTool.scala:1352`（续聊复活）、`DeviceDelegate.scala:520`（设备侧）、
  `DelegateRevival.scala:109`（同 id 复活）。**没有 REST 端点**能起 delegate/kernel：
  网关只暴露建会话 `/api/sessions`（`gateway/SessionRoutes.scala:82`）与"打一轮"
  `POST /sessions/{id}/turn`（`SessionRoutes.scala:26` → `TurnEndpoint.runTurn:57`），
  OpenAI 兼容面 `gateway/OpenAiChatShim.scala:21` 同理——**都不能点名工具或 executor**。

### 1.3 subagent：`SubTask`（自克隆后台会话）

- `src/main/scala/nebflow/core/tools/SubTaskTool.scala:40/46`，`call` 在 `:121`，`spawnWorker` 在 `:251`；
  `:267` `subtaskId = s"subtask-${UUID…}"`，`:284/287` 双件套 spawn，`:297 source = "subtask"`。
- 闸：`:127 ctx.depth >= MaxDepth`；`:129` 根代理**硬拒**（文案点名改用 `Delegate`）。
- 不在 `RootExclusiveTools` 里 ⇒ 任何非收敛 agent 都能在自己的 `agent.json tools` 里声明到它。

### 1.4 工具注册面（新增工具的落点）

- 主注册表：`src/main/scala/nebflow/core/tools/registry.scala:13 val builtins: Map[String, Tool]`，`:202 tools.putAll(...)`，
  `:237 def registerTool(tool: Tool)`（动态注册，外部/脚本工具走这条）。
- 每次会话过滤：`AgentSessionExecution.scala:2300 buildAllowedToolSet` → `:2550 buildToolList`。
- 各面常量全在 `agent/AgentCore.scala`（`:198/385/664/684/694/724/731/843`，分派单点 `:779`）。
- 插件可授的**内建工具白名单**（只三件）：`core/plugin/PluginRegistry.scala:104`
  `val BuiltinToolWhitelist = Set("WebSearch","WebFetch","Curl")`（消费点 `AgentSessionExecution.scala:2460`）。

---

## 2. PTC 的 `tools.*` 现在暴露了什么，加 `tools.spawn_*` 要动哪里

### 2.1 现状：`tools.*` 是**插件自己的**工具面，不是宿主工具面

- 声明：`plugins/ptc-python/plugin.json` 的 `extensions.ptc.tool_servers` 只指一个 stdio server
  （`skills/ptc-python/scripts/ptc_tools_fs.py`），`profiles` 是**闭集**：
  `readonly = [read, glob, grep, stat]`、`readwrite = [read, glob, grep, stat, write, edit, remove]`。
- 执行器是那个 server 的 MCP client（`ptc_executor.py:75-160` `ToolServer` 类），代码里的
  `tools.read(...)` 变成一次 `tools/call` 打到**插件进程自己的子进程**。
- 宿主看到的只有插件对外的两个工具：`mcp__plugin_ptc-<lang>_ptc__ptc_run[_rw]`
  （`core/plugin/PluginMcpManager.scala:serverIdFor`），这两个**走 `McpToolGate`**。

⇒ 也就是说：**`McpToolGate` 的闸口在 `ptc_run` 这一层，闸的不是代码里的每次 `tools.*`。**
一次 `ptc_run_rw` 被批准 = 整段代码里的 64 次内部调用全批准（默认 `max_tool_calls: 64`）。

### 2.2 加一个 `tools.spawn_*` 要动的地方（两条路，性质完全不同）

| 路线 | 动哪里 | 是否经过 McpToolGate | 性质 |
|---|---|---|---|
| **B（插件侧自造）** | 插件里再写一个 tool server（或直接在被调 server 里加 `spawn` 方法），自己起进程/自己调 gateway | ❌ 完全不经过 | 等于给模型一个**受脚本控制的 shell/编排面**；`McpToolGate`、`AgentCore` 身份闸、`safetyMode` 全部绕开。**否决** |
| **A（引擎侧 broker）** | 引擎新增一个 MCP server（或复用现有 MCP 面）暴露 `spawn_node/spawn_agent`，插件 manifest 的 `tool_servers` 指向它；实现体内部调 `ChainWriteTool.registerChain` / `DelegateTool.spawnBackground` + 自带政策闸 | ❌ 子调用仍不经过（发起方是插件进程，不是模型）；这套实现**必须自带闸与审计** | 唯一可接受的技术路径，但需要新政策面 |
| **C（两段式计划）** | 插件侧**不加**工具；约定 PTC 代码的返回值/`stdout` 是一份**计划 JSON**，由宿主在 `ptc_run` 返回后解析并交给现有 `ChainWrite` 注册（或人/上层模型确认） | ✅ 派发动作重新回到模型面/宿主面 | 代价最低；审批语义不变（还是"一次 ptc_run 直通 + 一次显式派发"） |

**机制层结论**：`tools.spawn_*` 不能"顺手加上"。只要 spawn 的发起方是插件进程，
现有任何一个审批面都不在链上。要让审批重新生效，只有两种形态：把 spawn 拉回宿主面
（C），或者给 spawn 建一条**自己的**政策/审批通道（A）。

---

## 3. 风险清单

| 风险 | 机制依据 | 后果 | 缓解手段 |
|---|---|---|---|
| **① 审批颗粒度本已很粗，再扩一层会变形** | 一次 `ptc_run` 批准覆盖 `max_tool_calls: 64` 次内部调用 | 若代码里能派 agent，"批准 1 次代码执行"= 批准 N 个后台 session | spawn 单独审批（每个 spawn 一张卡 / 预算式 N 次）/ 只允许计划（C） |
| **② 派生会话的权限继承是**放大面** | `DelegateTool.scala:537 safetyMode <- resources.effectiveSafetyMode` 原样传给子会话；子会话工具面 = `NebflowFixedTools`（`AgentCore.scala:724`，含 Bash/Write/Edit） | 脚本派出的 agent 拿到与父会话同级的写权限，且**不受父 turn 生命周期约束**（后台跑） | 派生面裁剪（现成的 kernel 面 `AgentSessionExecution.scala:2514-2531` 就是"不继承编排工具"的先例）；可再加"spawn 出来的会话 plugins=[] + 只读工具面" |
| **③ 深度/扇出护栏是软的** | `MaxDepth = 5` 只拦 `Delegate`/`SubTask` 的 `ctx.depth`；脚本内循环可在一个 turn 里连开 N 个（无 spawn 预算概念） | 一个 `ptc_run` 可能打出几十个后台会话 | 每次 ptc_run 的 spawn 预算（写进 manifest `limits`，与 `max_tool_calls` 同族）+ 全局限流 |
| **④ 无审批面的工具被"内部直调"** | `AgentSessionExecution.scala:2057` 执行不校验挂载面，`permissions.scala:183` 除 Write/Edit 全自动批准 | 一旦引擎内部存在 spawn 入口，"谁能调"只靠工具内部身份闸；新增入口若不检查 `DelegateSessions`/`modeFaceOf`，等于开洞 | 新入口复用 `NodeTools.resolveRuntime` 的作用域闸 + 显式测试 |
| **⑤ 审计断点** | 现在每次模型 tool call 都有 audit 行；插件内部的 `tools/call` 只在 `ptc_run` 响应的 `tool_calls[]` 台账里 | spawn 若发生在插件内部，引擎侧只有 1 条 `ptc_run` 记录，派人证据在插件台账里，跨两份日志 | broker 侧逐次落 `FlowMapEventLog`/`ChainStore` 行（`ChainWrite` 已有 `chain-registered` 先例 `ChainWriteTool.scala:519`） |
| **⑥ 生命周期归属模糊** | delegate 结果经 `Mail`/系统消息回到父会话；脚本执行早已结束（进程退） | "脚本派出去的 agent 结果回哪"没有自然答案 | 派生物必须绑定一个持久地址（`delegate:<id>` 或 chain id），结果进 chain ledger，脚本不负责收尾 |

**有没有"脚本派生的 agent 不继承写入权限"这类隔离手段？**
有现成骨架，但要新拼：
- **工具面裁剪**：`AgentSessionExecution.scala:2514-2531`（kernel 面摘编排工具）就是模板——
  可加一个 `spawned` 面 = `BaseTools` 去 Write/Edit/Bash，只留读。
- **插件面零授予**：`DelegateTool.scala:900-902` 只在 `grant` 非空时挂插件；派生腿可以显式
  `agentDef.copy(pluginMcpServers = Nil, pluginTools = Nil)`。
- **档位降档**：`SpawnParams(safetyMode = …)`（`:537`）可强制 `Confirm` 而非继承（`permissions.scala:149`
  缺省 `AutoAll`，这是当前"几乎不出卡"的根因）。
- **写闸在文件面兜底**：`FileSandbox.checkWrite` 独立于围栏（`GrepTool` 注释里明确"读侧不留假闸"），
  派生会话的 worktree 隔离（delegate 自带 worktree）是最后一道。

---

## 4. 候选设计（三个）+ 推荐

### 候选 A：引擎侧 spawn broker（`tools.spawn_*`，完整形态）

- **能力边界**：`tools.spawn_agent({task, executor?, readOnly: true})` 与
  `tools.spawn_node({...})`；只允许 `executor = nebflow|kernel`；单次 `ptc_run` 内 spawn 预算
  （如 3）、全局并发上限；派生物**只读面 + 零插件**。
- **审批模型**：新增一个 `RiskTier`（L3-adjacent）：**每个 spawn 一张审批卡**（不继承
  `ptc_run` 的批准），或"一次批准 = N 次预算"由用户显式选择；批准记录进 `McpToolGate.SessionApprovals`
  同族（但键要含 spawn 预算计数，而非布尔）。
- **实现代价**：**高**。引擎侧新 MCP server（或新内建工具 + 插件 `tool_servers` 指向）+
  政策面 + 预算 + 审计行 + 派生面裁剪 + spec（至少 5 个文件、一条新审批面、跨两个插件 manifest）。
- **风险**：审批面新增一种"卡"，与现有 `AskMcp` 正交；误配即开洞。

### 候选 B：插件侧自建 spawn（**否决**）

- 能力上最省事（插件里 `child_process` 起一个 agent CLI 或直接打 gateway `POST /sessions/x/turn`），
  但完全绕开 `McpToolGate`、`AgentCore` 身份闸、`safetyMode`、审计面。等价于把 shell 交给模型。
- 唯一可取的极小变体：**只调 gateway 的 `POST /sessions/{id}/turn`**（不带工具选择的公开面），
  那也等于给模型一个"无限自我对话"通道，且绕过 `MaxDepth`。

### 候选 C：两段式"脚本只生成计划"（**推荐先落**）

- **能力边界**：PTC 代码不 spawn；它返回（或 `ptc.args.plan_out` 指定文件写入）一份
  **计划 JSON**：`{nodes: [{id, task, deps, plugins?}], agents?: [{name, task}]}`。
  宿主在 `ptc_run` 返回后：
  - 若当前会话是 delegate/dispatcher 作用域 ⇒ 走**现有** `ChainWriteTool.registerChain`
    （`ChainWriteTool.scala:317/519/556`，含 12 节点上限、作用域闸、`chain-registered` 审计）；
  - 若是普通会话 ⇒ 计划作为结果交给模型/人确认，不自动派发。
- **审批模型**：**完全不变**。`ptc_run` 一次批准 = 允许生成计划；真正的派发仍受
  `ChainWrite` 的既有身份闸与（未来的）审批面约束，且每次派发仍是显式可审计的 chain 注册。
- **实现代价**：**低**。插件侧：约定 + schema + 文档（不需要新工具，`ptc_run` 返回值本来就带
  `value`/`files_written`）；引擎侧：可选地加一个"把 plan 交给 registerChain"的消费腿
  （也可先由模型读 plan 再自己调 `ChainWrite`，零引擎改动）。
- **为什么推荐**：把"代码的强表达力"用在**构造图**上，而不是**执行图**上；审批颗粒度不退化
  （一次批准 = 一次建图，而不是 N 次 spawn）；与现有 `AgentFlow`/`ChainWrite` 的 DAG 模型天然对齐
  （脚本擅长算什么、连什么；引擎负责派）。

### 推荐结论

1. **本批先落候选 C**（零引擎改动即可验证：脚本产出 plan → 上层调用 `ChainWrite`）。
   它是"能不能"这个问题的**是**，且不动审批语义。
2. **候选 A 排在 C 之后**：等 C 跑出真实需求（"必须脚本内闭环"的用例）再立项；
   立项时必须同时交付：spawn 预算、派生面裁剪（只读 + 零插件）、逐次审计行、
   `safetyMode` 强制 Confirm 四项，否则不开。
3. **候选 B 永不落**。

---

## 5. 未决问题（需作者裁定）

1. "脚本派生的 agent 不继承写入权限"是否作为**硬约束**（即 A 的派生腿恒为只读面）？
   若是，则"脚本内闭环编排"的价值会显著缩水——这也是我倾向 C 的理由之一。
2. spawn 是否需要**独立审批卡**（每卡一次），还是接受"一次 ptc_run 批 N 次"的预算式？
3. 脚本派生物的结果投递面：回父会话（`Mail`/系统消息）还是只进 chain ledger？
4. 是否顺带把 `NodeEdit`/`ChainWrite` 补进 `McpToolGate` 的风险面（当前它们无审批：
   `permissions.scala:183`）——这是与本件独立的既有缺口。
