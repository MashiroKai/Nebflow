# 定向审计：响应超时在 AskUser 等待与冻结期间的误报机制

- 日期：2026-09-03（冻结，只读审计，不改代码）
- 审计人：Explorer（与大盘超时审计并行的专项）
- 代码基线：主仓 `/Users/dev/Claude code/Nebflow`（未合并 worktree 改动）
- 日志基线：`~/.nebflow/logs/nebflow.log`（当日）+ `nebflow.2026-09-0{1,2}*.log`
- 上游裁定（修复验收标准）：①权限确认等待必须无超时；②MCP 工具超时机制与普通工具统一

---

## 三问三答（一行版）

1. **AskUser pending 谁在报超时**：后端 **TaskStuckWatcher（10min 零活动判定，`Defaults.StuckThresholdMs`）**——AskUser 挂起期间 registry 状态残留 `Processing` 且 `lastActivityMs` 不再刷新，每 30s 扫描必然命中；root/Team 只 warn+广播（噪声），**Delegate/Ephemeral/SubTask 会被 Stop→重启→硬取消（破坏性）**。协议里的 `WaitingForUser` 状态定义了但全仓零使用，是根因。
2. **冻结期间**：后端自身已双保险（Frozen 态豁免 TaskStuckWatcher + 前端 `frozen` 事件清计时器）——**误报来自两条漏网路径**：a) 子代理冻结（`agentFrozen`）时 root 会话仍 busy 等屏障，前端 10.5min 杀 turn 计时器不清除 → 到点发 `interrupt` 杀掉 root turn 并弹「响应超时」卡；b) AskUser pending 跨冻结窗进入时 turn 挂在工具 deferred 上而非 dispatch 边界，冻结 gate 拦不到，整个冻结窗保持 Processing 被 TaskStuckWatcher 持续误报。
3. **权限确认 / MCP 超时现状**：a) 权限确认等待有 **5 分钟硬超时**（`AgentCore.PermissionTimeout`），到期自动拒绝 + `permissionExpired` 撤卡——直接违反裁定①；b) MCP 工具全部被 **`McpClient.callTool` 一刀切 120s 硬超时**包住，与内置工具「领域自治（Bash 无命令级超时/文件工具无限/网络工具各自 readTimeout）+ TaskStuckWatcher 会话级兜底」的语义完全不同——违反裁定②。

---

## Q1：AskUser pending 期间的计时器全清单

### 等待链结构

```
LLM 返回 tool_use(AskUserQuestion)
  → AgentCore.executeTool → AskUserQuestionTool.call
    → agentRef .? (AgentCommand.AskUser(requestId, items, replyTo), timeout = None)   ← 工具 fiber 挂起点
      → AgentActor processing 行为收到 AskUser → InteractionHub.Request
        → hub 存 reply 槽位 + 向 root 会话渲染 askUser 卡
      → 用户回答 → hub.Answered → replyTo ! answers → deferred 完成 → 工具返回
```

### 计时器盘点

| # | 计时器 | 默认值 | 代码位置 | AskUser pending 时是否在走 | 到期动作 | 定性 |
|---|--------|--------|----------|---------------------------|----------|------|
| 1 | 工具 actor ask 超时 | `None`（无限） | `AskUserQuestionTool.scala:196-199`；`ActorRef.scala:28-30`（注释明言 "pass None to wait indefinitely (e.g. for human interaction)"） | 是（但永不到期） | — | 设计正确 |
| 2 | **TaskStuckWatcher** | 阈值 10min（`Defaults.scala:159`），扫描间隔 30s（`:162`） | `TaskStuckWatcher.scala:121-124` | **是——必然命中** | root/Team：warn 日志 + `taskStuck(action=attention)` 广播（`:292-297`）；Delegate/Ephemeral/SubTask：`Stop` → BackoffSupervisor 重启 → 2 次无效后硬取消在飞 LLM → 4 次后 supervisor `Cancelled`（`:227-291`） | **误报源；对子代理破坏性** |
| 3 | 前端 sessionBusyTimeouts | `streamTimeoutMs + 30s` = 630s（`Defaults.StreamTimeoutSec=600`，`Defaults.scala:19`；前端默认 `state.js:181`） | 武装：`input.js:757/1038/1127`；清除：`main.js:1236-1240`（askUser）/`main.js:1354-1358`（askPermission） | 否（askUser 事件到达即清除，注释 "suppress stream timeout indefinitely"） | `sendWs({type:'interrupt'})` + 渲染「响应超时」卡（`chat.js:1329 renderTimeoutNotice`） | 正常路径有保护；重武装漏洞见下 |
| 4 | LLM 流 watchdog 族（first-token 90s / inactivity 120s / no-progress 600s / LlmTimeoutMs 600s） | `Defaults.scala:29/44/61/64` | `interface.scala:164-213 inactivityTimeout`（只 race 在 in-flight 流上，`interface.scala:547/840`） | 否——LLM 请求已返回 tool_use，无 in-flight 请求 | — | 不适用 |
| 5 | ProviderHealthMonitor 探测 | probe 间隔 120s / 超时 30s（`HealthMonitor.scala:26-29`） | `HealthMonitor.scala` | 走但 provider 级，与会话无关 | 标记 provider Up/Down | 不适用 |
| 6 | Bash 卡死窗（120s）/ 后台任务族 | `Defaults.scala:117/85` 等 | `shell.scala` | 否（无 Bash 在跑） | — | 不适用 |

### 根因：`Processing` 残留 + `WaitingForUser` 死代码

- `touchRegistryActivity` 的写入点只有三处：LLM 调用开始+每个流 chunk（`AgentCore.scala:721/744`）、turn 完成（`AgentActor.scala:1771/2926` 等）、Bash 活动桥接（`AgentCore.scala:1880-1885` 注释）。**AskUser 派发与等待期间零 touch**。
- `AgentCommand.AskUser` 处理器（`AgentActor.scala:2335-2366`）转发 hub 后原样返回 `processing(...)`，不更新 registry。
- 协议枚举 `AgentStatus.WaitingForUser`（`protocol.scala:752`）**全仓唯一出现处即定义行**——语义早已设计，从未接线。

### 到期动作分级（报错无害 vs 报错且产生动作）

- **root（Nebula）**：仅日志 + `taskStuck` 广播，不杀 turn——但今日日志中每 30s 一条、单会话连刷 1 小时+，且 `taskStuck` 会点亮管理面板 stuck 徽标（`main.js:2094-2107`、`bgAgentPopup.js:463`、`flowAgentPopup.js:902`）。
- **Team**：只读 attention 广播（`TaskStuckWatcher.scala:159-166`）。
- **Delegate/Ephemeral/SubTask（有 parentRef）**：**破坏性链**——`Stop`（挂起在 deferred 上的 agent 不消费 mailbox）→ 计数 ≥2 硬取消在飞 LLM（此时无在飞请求，取消 0 个，但 Stop 继续无效）→ ≥4 次 supervisor `Cancelled` 释放父屏障（`:268-291`）。用户后来回答的 answer 路由到已死的 replyTo/已删槽位，**问句永久失效**。

### 日志证据（2026-09-03 当日，nebflow.log）

今日共 **116 条** `stuck in Processing`（09-01/09-02 日志为 0 条——该形态与近期 rest-turn 定时唤起 Nebula 会话直接提问的用法相关）。四个会话全部实锤为 AskUser pending：

| 会话 | AskUser 派发时刻 | 证据行 |
|------|------------------|--------|
| `49323500…` | 18:08:02.810 `InteractionRequest kind=AskUser requestId=da94d4b7` | 18:30 起被误报，idleSecs 1376→1946s（32min+），每 30s 一条 |
| `4d32ce8a…` | 18:11:10.287 `InteractionRequest kind=AskUser requestId=a8a3cd58` | 18:30 起误报 idleSecs 1189s→1759s |
| `9737ffc8…` | 18:23:13.236 `InteractionRequest kind=AskUser requestId=0158e5e4` | 18:33 起误报 idleSecs 616s→1036s |
| `779d4246…` | （同行为模式） | 18:38 起误报 idleSecs 618s→708s |

样本（`49323500`，ask 后该会话除误报外零事件——正是等用户回答）：

```
18:08:02.717 INFO nebflow.ws - User text (rest-turn) for session 49323500… (10 chars)
18:08:02.810 INFO nebflow.agent.interaction - InteractionRequest kind=AskUser requestId=da94d4b7 root=49323500… sourceAgent=Nebula
18:30:59.435 WARN nebflow.core.processor.stuck - TaskStuckWatcher: root agent 49323500… stuck in Processing for 1376s — not auto-restarting, broadcast taskStuck for user decision
…（每 30s 重复至 18:40:29 idleSecs=1946s）
```

---

## Q2：冻结期间的计时器与误报路径

### 冻结语义与已有保护（这部分是对的）

- **挂起点**：所有 dispatch 经过 `pipeLlmCall` 冻结 gate（`AgentActor.scala:3294-3346`，F2 单一咽喉）——工具已执行完、下一轮 LLM 不派发 → `enterFrozen`（`:3522-3568`）：发 `Frozen` WS 事件 + `touchRegistryActivity(…, AgentStatus.Frozen)`（`:3557`）→ 进入 `frozen` behavior 持完整 state 等恢复。
- **后端豁免**：TaskStuckWatcher 只扫 `status == Processing`（`TaskStuckWatcher.scala:123`）→ Frozen 态天然豁免（`GatewayMain.scala:746-747` 注释明言）。
- **前端保护**：`frozen` 事件处理器清掉该会话 `sessionBusyTimeouts`（`main.js:373-379`，F8/F4 注释："a freeze can last hours — kill the activity stream timeout"）。
- **恢复驱动**：FreezeScheduler 30s 轮询发 `CheckFreezeGate`（`FreezeScheduler.scala:38-49`）+ 用户消息唤醒；恢复后活动事件重武装前端计时器。
- LLM watchdog 族不适用（冻结期无 in-flight 请求）。

**日志佐证**：今日 09:00:05（`freeze-enter … node-8b0 resumeAt=12:00`）、14:00:03、14:01:38 三次进入冻结，冻结窗内后端零 stuck/超时误报——豁免生效。

### 漏网路径（误报真正来源）

| # | 路径 | 机制 | 后果 | 证据 |
|---|------|------|------|------|
| A | **子代理冻结，root 忙等屏障** | 子代理冻结只发 `agentFrozen`（`main.js:581-598`），该处理器只标记 bgAgent 条目 + `applyLocalFreeze()`（`:493-521`，仅改输入栏 CSS）——**不清 root 会话的 `sessionBusyTimeouts`**。root turn 挂在 `outstandingSubagentResults` 屏障上，busy 保持、无活动事件 → 10.5min 计时器到点 → `sendWs({type:'interrupt'})` + 「响应超时」卡（`input.js:757-771` / `main.js:338-346`）。root 收到 interrupt 走 processing 态 `cancelCurrentTurn()` + 回 idle（`AgentActor.scala:2007-2022`）——**挂起等结果的 turn 被杀**；子代理出冻结段完成后的结果投递落空 | 冻结跨小时（09:00-12:00 段）时 100% 复现「冻结期间响应超时」 | 代码链完整闭合，今日日志未见实例（root 恰好无跨冻结屏障等待） |
| B | **root 等冻结子代理屏障（后端侧）** | root 屏障等待期 status=Processing、零 touch → TaskStuckWatcher 10min 命中 → `taskStuck(attention)` 噪声（不杀 root） | 面板误标 + 日志刷屏 | 同 Q1 机制 |
| C | **AskUser pending 跨冻结窗进入** | AskUser 挂起在**工具 deferred** 上而非 dispatch 边界——冻结 gate 只拦 `pipeLlmCall`，拦不到已挂起的工具 fiber → 整个冻结窗（如 09:00-12:00）状态保持 Processing → TaskStuckWatcher 从第 10min 起每 30s 误报，直到用户回答 | Q1 误报在冻结窗内被放大；子代理问句跨冻结窗即被 Q1 破坏性链杀掉 | Q1 四会话中任一跨入 09:00 即为此形态 |

---

## Q3a：权限确认等待的超时现状

- **有超时：5 分钟**，`AgentCore.scala:29` `private val PermissionTimeout = 5.minutes`（注释自述动机：防 popup 错过/WS 故障/离开键盘导致会话永久锁死）。
- 等待实现：`AgentCore.scala:1228-1232` —— `deferred.get.map(Some(_)).timeoutTo(PermissionTimeout, IO.pure(None))`。InteractionHub 本身无任何计时器（纯路由 + reply 槽位，`InteractionHub.scala` 全文无 timeout），5min 在请求侧。
- **到期动作**（`AgentCore.scala:1248-1265`）：
  1. 向 root 会话发 `permissionExpired` WS → 前端删卡 + 清 attention（`main.js:1372-1386`）；
  2. 工具返回错误文本 `"Permission timed out — no response within 5 min, auto-denied. Re-issue the command if needed."` 注入下一轮 LLM。
  3. 超时**不**计入劝停 denial 计数（`:1240` 注释："用户未操作≠拒绝"）——但执行已被拒绝。
- 前端侧 `askPermission` 事件会清 sessionBusyTimeouts（`main.js:1354-1358`）——前端计时器对权限等待有保护；**打扰完全来自后端 5min 定时器**：用户 4min59s 回答也一样有效，但 5min01s 回答时卡已被撤、工具已按拒绝处理。
- 附带自洽性：5min < TaskStuckWatcher 的 10min，权限等待期不会触发 stuck 误报——但这是「两个超时互相将错就错」，裁定①落地后需一并复核（见修复建议 R4）。
- hub 崩溃恢复注释（`InteractionHub.scala:26-29`）也把「5 分钟权限超时自动拒绝」当作兜底语义引用——修复时需同步改注释与该兜底预期。

**结论：违反裁定①。** 权限卡不是「无人值守的定时任务」——它与 AskUser 同属「人在环」等待，前端已经把两者同等对待（都清杀 turn 计时器），后端却只给 AskUser 无限、给权限 5min。

## Q3b：MCP 工具 vs 内置普通工具超时对比

执行外层（`AgentCore.executeToolInner`，`AgentCore.scala:1384+`）**无统一超时包装**，直接 await 各工具 `call` 的 IO——超时语义完全由各工具自治：

| 工具类 | 超时机制 | 默认值 | 位置 | 到期行为 |
|--------|----------|--------|------|----------|
| **MCP 工具（全部，不分语义）** | **客户端层硬超时包住整个 `tools/call`** | **120s** | `McpClient.scala:82`（`transport.send(request).timeout(120.seconds)`） | handleError → `ToolError("Error: …")`，错误文本进下一轮 LLM（turn 不死但工具必败） |
| MCP `tools/list` | 同上 | 30s | `McpClient.scala:50` | 初始化失败 |
| Bash 前台 | **无命令级超时**（2026-08-30 裁定：依赖卡死检测；显式 `timeout` 参数可选，MAX 1h） | 近似无限（365.days） | `BashTool.scala:474-499`（注释明言）、`BashTool.scala:19-20` | 显式 timeout 才 watchdog 杀树；否则三层兜底：no-progress ceiling（10min 零输出零 CPU）→ TaskStuckWatcher（turn 级） |
| Read / Glob / Grep | 无超时（`IO.blocking`） | 无限 | `ReadTool.scala:108`、`GlobTool.scala:66` 等 | 等 OS 返回 |
| WebFetch | HTTP readTimeout | 120s（`Defaults.scala:76`） | `WebFetchTool.scala` | 工具错误 |
| WebSearch | 每引擎 fetch timeout | 10s（`WebSearchTool.scala:14`） | 同左 | 该引擎失败，race 其余引擎 |
| Curl | readTimeout | 120s max（`Defaults.scala:75`） | `CurlTool.scala` | 工具错误 |
| AskUserQuestion | actor ask `timeout=None` | 无限 | `AskUserQuestionTool.scala:199` | — |
| 远程设备执行（device: 路径） | HTTP readTimeout | 同步 120s / 后台 3600s（`RemoteExecutor.scala:32,35`） | 同左 | 工具错误 |

**差异要点**：内置工具的超时是「领域自治 + 会话级 TaskStuckWatcher 兜底」——快工具自带紧超时，慢工具（Bash/文件）不设命令级上限，真卡死由 turn 级活动检测收口。MCP 则是「一刀切 120s 客户端硬顶」：不看工具语义（浏览器自动化、深度检索、长仿真的 MCP 工具 120s 必死），错误文本是原始 `Error: timeout…`，且这 120s 内 TaskStuckWatcher 的活动桥接不覆盖 MCP（无 lastActivityMs 刷新），长 MCP 调用还会连带触发 Q1 的 stuck 误报。

**结论：违反裁定②。**

---

## 修复建议（对齐两条裁定）

### R1（裁定①核心）：权限确认等待去掉 5min 定时器，改为无限期 + 可见性兜底
- `AgentCore.scala:1228-1232`：去掉 `.timeoutTo(PermissionTimeout, IO.pure(None))`，直接 `deferred.get.map(Some(_))`（与 AskUser 完全同构——AskUser 靠「InteractionHub 槽位 + 前端卡 + 刷新重放（ListPendingAsks，2026-09-03 刚落地）」保证可达性，权限卡应共享同一套机制而非靠定时放弃）。
- 原注释担心的「永久锁死」实际由既有机制覆盖：hub 的 F4 fallback 广播（`InteractionHub.scala:116-143`，目标 root 不可达时扇出全局 toast）、`permissionExpired` 保留用于**会话被用户主动取消/interrupt**时的撤卡路径（interrupt 处理器补发该事件即可），不再由定时器触发。
- 同步更新三处注释：`AgentCore.scala:26-28`、`InteractionHub.scala:28-29`、`:116/:187` 的 "5-minute timeout denial" 表述。

### R2（裁定①配套）：把「人在环等待」提升为一等 registry 状态
- AskUser 派发时 `touchRegistryActivity(…, WaitingForUser)`（在 `AgentActor.scala:2335` AskUser 处理器与权限 `permissionDeferredRef.modify` 挂起点，`AgentCore.scala:1192-1199`）；回答/到期/取消时恢复 Processing/Idle。`AgentStatus.WaitingForUser`（`protocol.scala:752`）已是现成枚举值，零协议改动。
- `TaskStuckWatcher.scala:123` 扫描条件排除 `WaitingForUser`（只防真卡死，不防等人）——一行改动消灭 Q1 全部误报与 Q2-C 的冻结窗放大。
- 可选加固：hub 增加与 `ListPendingAsks` 对称的 pending 权限快照重放，保证 R1 去掉定时器后权限卡刷新/重连也不丢。

### R3（裁定②）：MCP 超时统一到普通工具语义
- 移除 `McpClient.scala:82` 的一刀切 `.timeout(120.seconds)`（`tools/list` 的 30s 保留——那是基础设施探测，不是工具执行）。
- MCP 工具从此与 Bash/Read 同权：默认无限跑到返回，卡死由既有三层兜底收口（no-progress ceiling / TaskStuckWatcher turn 级 / R2 的 WaitingForUser 豁免逻辑天然覆盖「MCP 工具内等用户」的场景）。
- 若个别 MCP server 需要上限，走 server 配置声明（`mcp.json` 加可选 `timeoutMs`），而不是客户端全局硬顶；`RemoteExecutor` 的 120s/3600s 是网络传输层语义，不在本次统一范围。

### R4（Q2-A/Q2-B）：冻结事件的计时器对称性
- `agentFrozen` 处理器（`main.js:581-598`）比照 `frozen` 处理器，**清掉 `msg.rootSessionId || msg.sessionId` 对应会话的 `sessionBusyTimeouts`**（或最小化：root 存在 outstanding 屏障时清）；`agentResumed` 后由活动事件重武装——与 F8/F4 既有契约（`resumed` re-arms via activity）一致。
- 后端侧 Q2-B 随 R2 自动消解（root 等屏障若需可见性，可由屏障等待也标 `WaitingForUser` 之外的专用态或沿用 taskStuck 但降频）。

### 验收点（二值、可自动化）
1. 冒烟：真实启动 gateway → AskUser pending 超 11min → 日志无 `stuck in Processing`、前端无「响应超时」卡、无 `interrupt` 出站帧。
2. 权限卡挂起 6min 后回答 → 工具照常执行（当前版本此处必失败，修复后必须通过）。
3. 注入一个 sleep 150s 的 MCP 工具 → 返回真实结果而非 `Error: timeout`（当前 120s 必死，修复后必须通过）。
4. 子代理冻结 30min 场景（本地时钟拨进冻结段 + delegated agent 在跑）→ root 会话无 interrupt 出站、无超时卡；出冻结段后 root 屏障正常收到结果。
5. 既有回归：TaskStuckWatcher 对真卡死（LLM 流零活动）仍能在 10min 量级触发 Stop/硬取消——`TaskStuckWatcherSpec` 语义不回退。

---

## 证据文件索引

| 结论 | 文件:行 |
|------|---------|
| 权限 5min 超时定义 | `src/main/scala/nebflow/agent/AgentCore.scala:29` |
| 权限超时等待与到期动作 | `AgentCore.scala:1228-1265` |
| AskUser 无限等待 | `src/main/scala/nebflow/core/tools/AskUserQuestionTool.scala:196-199`；`src/main/scala/nebflow/actor/ActorRef.scala:28-30` |
| `WaitingForUser` 死代码 | `src/main/scala/nebflow/agent/protocol.scala:752` |
| TaskStuckWatcher 判定/动作 | `src/main/scala/nebflow/core/processor/TaskStuckWatcher.scala:121-124, 227-297`；`src/main/scala/nebflow/shared/Defaults.scala:159,162` |
| registry touch 点（无 ask 覆盖） | `AgentCore.scala:1880-1899, 721, 744` |
| 冻结 gate 与 enterFrozen | `src/main/scala/nebflow/agent/AgentActor.scala:3294-3346, 3522-3568` |
| Frozen 豁免注释 | `src/main/scala/nebflow/gateway/GatewayMain.scala:743-747` |
| 前端 frozen 清计时器 / agentFrozen 不清 | `src/main/resources/web/js/main.js:354-379（清）, 581-598（不清）, 493-521（applyLocalFreeze 仅 CSS）` |
| 前端杀 turn 计时器（630s） | `main.js:332-347`；`input.js:750-771, 1030-1048, 1122-1137`；`chat.js:1329-1359`（响应超时卡）；`state.js:181` |
| 前端 askUser/askPermission 清计时器 | `main.js:1233-1240, 1353-1358` |
| processing 态 interrupt 杀 turn | `AgentActor.scala:2007-2022` |
| MCP 120s 硬超时 | `src/main/scala/nebflow/core/mcp/McpClient.scala:82`（tools/list 30s 于 `:50`） |
| Bash 无命令级超时裁定注释 | `src/main/scala/nebflow/core/tools/BashTool.scala:474-499`；`Defaults.scala:19, 117` |
| 执行外层无统一超时 | `AgentCore.scala:1384+` |
| InteractionHub 无计时器 | `src/main/scala/nebflow/agent/InteractionHub.scala`（全文） |
| 日志：116 条 stuck 误报、四会话 AskUser 关联 | `~/.nebflow/logs/nebflow.log`（2026-09-03 18:30-18:40 段；18:08:02 / 18:11:10 / 18:23:13 的 InteractionRequest kind=AskUser 行） |
| 日志：冻结窗后端零误报 | 同日志 09:00:05 / 14:00:03 / 14:01:38 `freeze-enter`，窗口内无 stuck WARN |
