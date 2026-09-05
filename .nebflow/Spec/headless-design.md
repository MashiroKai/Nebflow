# Headless 一次性执行入口 — P0 设计文档

**日期**: 2026-08-16 · **作者**: Backend · **状态**: 待 Manager 审（先审后码）
**上游输入**: /tmp/agent-benchmark-plan.md §3（适配缺口）+ §5 P0 表（四项）· **源码基线**: archive/scala @ bc1ce06a

---

## 1. 背景与目标

Benchmark 参赛（Terminal-Bench 2.1 第一站）的前置是 headless 一次性执行：容器内一条命令发任务 → 等待完成 → 取回结果 → 退出码。当前 Nebflow 的驱动面（WS + REST）全部面向交互式前端，四条断点：

| # | 断点 | 源码证据 |
|---|---|---|
| a | 无同步回合端点——`POST /api/command` 只捕获第一条响应即返回，长任务不适用 | RestApiRoutes.scala:74-86（responseRef 单值） |
| b | 无一次性 CLI——`nebflow chat send` 发出的 `userMessage` 在 WS handleMessage **无处理 case**（fallthrough `case _ => IO.unit` 静默忽略，CLI 却收到 "status: ok"） | cli/ChatCommands.scala:49 发送方；WebSocketRoutes.scala:830 兜底分支 |
| c | 无确定性模式——memory 注入、AskUser 挂起、中文回复、telemetry 都是批跑噪声 | ContextRefresher memoryBlock / AskService / TelemetryReporter |
| d | Harbor installed-agent 要求：容器内 CLI 接 task 描述 → 产出终端结果 → 退出码 0/1 | plan §3.3 |

**P0 目标**：容器内 `nebflow run -p "list files" --json` 无人工干预返回结果（plan 阶段 0 验收标准）。

## 2. 非目标

- 不做流式输出到 stdout（turn 结果一次性返回；流式属 P1 需求再评）
- 不做 turn 取消端点（超时即失败返回，agent 侧 turn 继续跑完自然终止——容器随任务销毁，无泄漏面）
- 不做 multi-turn 编排 API（CLI 可用 --session 串联，协议层不加会话状态机）
- 不做 Docker 镜像/terminal-solver agent 定义（P1 范围，本设计只保证接口满足其要求）
- 不动前端（WS 协议新增 userMessage case 是补全既有协议语义位，前端零改动）

## 3. 接口契约

### 3.1 同步回合端点

```
POST /api/sessions/:id/turn
Authorization: Bearer <token>
{
  "content":    "task text (required, non-empty)",
  "timeoutSec": 1800,            // optional, default 1800, max 7200
  "includeHistory": false        // optional: append full turn message window
}
```

响应（200，回合已终结——成功、agent 报错、LLM 失败都算"完成"）：

```json
{
  "status":        "completed",           // completed | error
  "sessionId":     "...",
  "finalMessage":  "last assistant text of this turn",
  "toolCalls":     [ {"label": "Bash", "summary": "ls -la"} ],   // turn 内 UiMessage.Tool 序列
  "usage":         {"inputTokens": 12345, "contextWindow": 131072},
  "durationMs":    45123,
  "error":         null                    // status=error 时的失败描述
}
```

- **504** timeout（Deferred 超时；listener 已注销，agent 侧 turn 继续自然跑完）
- **409** 该 session 已有一个 in-flight turn（HTTP 层门闩，见 D1；多 session 并行不受限）
- **404** session 不存在 / **400** content 为空
- 认证与现有 REST 一致（withAuth，Bearer token）

### 3.2 CLI

```
nebflow run [-p "<task>" | < task.md]      # -p 缺省读 stdin（Harbor 管道友好）
  [--agent NAME]        # default: session 所属 agent；新 session 缺省 Nebula
  [--session ID]        # 复用会话（历史恢复走既有 restart-resume 机制）
  [--json]              # 全结构输出（stdout 单行 JSON = 3.1 响应体）
  [--timeout SEC]       # default 1800
```

- 缺省每 run 新建 session（benchmark 隔离语义）；`--session` 显式复用
- 网关不在线：fork detached `nebflow start` + 轮询探活（≤60s），仍失败则 exit 2 提示
- 退出码：**0** status=completed 且 finalMessage 非空 · **1** status=error / finalMessage 空 · **2** 网关不可达 / 超时（504）
- 非 --json 模式 stdout 只输出 finalMessage 文本（工具轨迹不打印——harness 只关心终态）

### 3.3 WS 协议补全（断链修复）

handleMessage 新增 case（WebSocketRoutes.scala handleMessage）：

```
case "userMessage"  ≡  case "immediateInput"   // 同语义：appendUiMessages(User) + ensureAgent(ImmediateInput)
```

CLI ChatCommands.scala 零改动即修复；前端不受影响（immediateInput 保留原样，两别名等价）。

### 3.4 确定性开关

```
NEBFLOW_HEADLESS=1   # JVM 启动读一次，全局开关
```

| 触点 | 行为 | 实现位置 |
|---|---|---|
| memory 注入 | **跳过**（User/Agent memory 块不进 system prompt）——消除跨 run 状态泄漏 | ContextRefresher.buildMemoryBlock 调用点判 HeadlessMode |
| AskUser | **不挂起**：AskUserQuestionTool 直接返回 ToolError("Headless mode: no interactive user — decide autonomously and continue.") | AskUserQuestionTool 入口 |
| 语言 | system prompt 语言规则段覆盖为 "Always respond in English" | 语言规则注入点（ContextRefresher/PromptSections，实施时定位） |
| telemetry | disable（等价 NEBFLOW_TELEMETRY=false） | TelemetryReporter.create 入口 |
| 浏览器 | 不自动 openBrowser（等价 GatewayConfig.noBrowser） | GatewayMain.openBrowser 调用点 |
| 时间 reminder | **保留**（time-context 对任务执行有用，列为已知噪声源非消除项） | — |

单一开关，不做单项细分（P0 拒绝过度配置）。开关只影响"注入与交互"，不改变 agent 执行内核。

## 4. 方案设计

### D1 同步 turn 端点：wsHub listener + Deferred，零 Actor 改动

```
POST /turn 到达
  ├─ 409 门闩检查（RestApiRoutes 内 Ref[IO, Set[sessionId]]，bracket 保证清除）
  ├─ 注册 wsHub listener（回调：过滤 sessionId）
  │     type == "sessionBusy" && busy == false  → complete(Right(TurnDone))
  │     type == "error"                          → complete(Right(TurnError(msg)))
  │     type == "Done"（depth 0）                → 顺手捕获 usage/inputTokens
  ├─ 记录 turnStartTs = now
  ├─ appendUiMessages(sessionId, User(content)) + ensureAgent(ImmediateInput(content))
  │     ——与 WS immediateInput case 完全同构（复用同一段逻辑，提取私有方法）
  ├─ Deferred.get.timeout(timeoutSec)   ← REST 端点自身的 IO fiber，与 actor 无耦合
  ├─ 结果提取（见 D2）
  └─ bracket 收尾：unregister listener + 门闩移除
```

**为什么不改 AgentActor**：turn 完成信号已存在两条广播路径——`AgentStreamEvent.Done`（finishTurn 2160-2190：Done 事件 → sessionBusy busy=false）与 error 路径（error → done → busy=false）。wsHub 本来就是 fan-out 总线（WsHub.scala：conns 遍历 send，handleErrorWith 吞错），多挂一个 listener 对 actor 是零成本零感知。**"Deferred 不阻塞 actor 主循环"由构造保证**：Deferred 在 REST 的请求 fiber 里 await，actor 的 wsSend 调用（即 listener 触发）是 fire-and-forget 回调，两条 fiber 互不等待。

**备选（否决）**：InteractionHub RegisterRoot——语义是 AskUser 请求路由（按 rootSessionId 找 wsSend 答复），不是完成通知总线，复用要加消息类型，改动面更大；AgentCommand 加 TurnSubscriber——侵入 actor 协议，收益为零。

**竞态窗（源码实证，必须有对策）**：finishTurnCont idle 路径里 persist（`ctx.forkTurn(saveMessagesForSession)`）与 Done+busy=false（另一 `ctx.forkTurn`）**并行 fork**——listener 触发时最后一条 Ai 消息可能尚未落盘。对策：busy=false 后 **retry-read**——每 200ms 拉一次 `sessionStore.getUiMessages`，直到出现 turnStartTs 之后的 Ai 消息或重试 25 次（5s）用尽（用尽则 finalMessage 取当前已见内容，status 仍 completed——消息滞后不是回合失败）。turnStartTs 取**发送 ImmediateInput 之前**的时间戳，边界包含本 turn 的 User 消息。

**Done 事件顺路捕获 usage**：AgentStreamEvent.Done 携带 model/contextWindow/inputTokens/compactThreshold（AgentActor.scala 2154-2159 构造），listener 存入 Ref，响应的 usage 字段直接用——比事后估算准。

### D2 结果提取：sessionStore 读侧

finalMessage = turnStartTs 之后**最后一条** `UiMessage.Ai` 的 text；toolCalls = 同窗 `UiMessage.Tool` 的 label+summary。不解析 messages.json 内部结构（UiMessage 是 history 端点既有契约，RestApiRoutes.scala:105 同款读法），向后兼容。

### D3 CLI：GatewayClient 直连 + ProcessManager 拉起

- `GatewayClient.post("/api/sessions/<id>/turn", ...)` 现成（post/command 均有）；token/baseUri 装配逻辑沿用 CliRouter 现有 ctx.client 构造
- 网关拉起：`ProcessBuilder("nebflow", "start")` detached（nebflow.cmd/java -jar 均可自举）+ 200ms 间隔轮询 `/api/sessions` 探活 ≤60s。**不内嵌 GatewayMain**（同 JVM 混跑 CLI/gateway 生命周期纠缠，detached 子进程随容器管理最干净）
- `--session` 复用 = turn 端点对既有 session 发 ImmediateInput；agent spawn 走 ensureAgent 既有路径，历史由 sessionStore 持久化恢复（AgentState 从不持久化、重启恢复首轮 isLifecycleRebuild 是结构性保证——见 protocol.scala 注释，无需额外处理）

### D4 userMessage 断链：服务端补 case（CLI 零改动）

`case "userMessage"` 与 `case "immediateInput"` 合并到同一处理体（提取私有方法 `handleUserText(sessionId, content)`）。理由：WS 协议里 userMessage 是语义完备的消息类型（前端 ui 事件里早有同名概念），发送方存在接收方缺席是协议残缺，修复应落在协议侧。ChatSend 后续迁移到 turn 端点（同步拿结果）留 P1 顺手改，本设计不强制。

> NOTE: ScheduledTaskActor.scala:116 也发 "userMessage"——但那是 server→client 的展示性 broadcast，任务路由走 routeToAgent 直达，**不经过 handleMessage**，与本修复无交集也不受影响（Manager 审批批注 2 归档）。

### D5 Headless 开关载体

`object HeadlessMode { val enabled: Boolean = sys.env.get("NEBFLOW_HEADLESS").contains("1") }`（core 包，无依赖）。各触点静态读。**不做** per-request 开关（turn 端点请求头控制）——benchmark 容器整进程即 headless，进程级开关与部署形态对齐。

## 5. 测试策略（含 LLM 成本）

| 层 | 内容 | LLM 成本 |
|---|---|---|
| 路由级（必进 CI） | TurnRoutesSpec：真实 AgentActor + RecordingLlm harness（SavePhaseZeroToolTurnSpec 范式：fake sendStream 脚本 TextDelta+Done/ToolCall+Done/error）断言 ①happy path——Deferred 完成、finalMessage/toolCalls/usage 结构 ②409 门闩（同 session 并发第二个 turn）③504 超时（Stream.never 挂死 + timeoutSec=1）④retry-read 竞态对策（Done 先于 persist 的注入时序由 harness 控制）⑤error 事件 → status=error ⑥userMessage case ≡ immediateInput（两类型同响应）。HeadlessModeSpec：触点单测（memory 跳过/AskUser 错误返回/telemetry disable） | **0**（fake LLM） |
| 集成 smoke（不进 CI，显式脚本） | scripts/smoke-headless.sh：隔离实例（NEBFLOW_HOME + 端口错峰）+ 真 GLM——3 用例：echo turn / 带 Bash 工具的 turn / timeoutSec=5 超时。~10 次 LLM 调用 ≈ 50k tokens ≈ **¥0.5-1**（GLM-5.2 价位） | **≤¥1/次全跑** |
| E2E（P0 验收标准） | 容器内（本地 docker 模拟）`nebflow run -p "create hello.txt with content world" --json && test -f hello.txt`——终态验证退出码 + 副作用 | **≤¥0.5** |
| CLI 单元 | RunCommand 参数解析/stdin/session 决策/退出码映射（fake GatewayClient） | **0** |

注：真实 AgentActor harness 三坑已有 memory 沉淀（LlmLogWriter.setEnabled(false)、TriggerCompaction 处理时机、compactComplete 信号），TurnRoutesSpec 直接沿用规避。

## 6. 实施计划（对齐 plan §5 P0 估时，总 5-8d）

| 步 | 内容 | 估时 | 依赖 |
|---|---|---|---|
| 1 | userMessage case + 提取 handleUserText（含 WS 路由测试） | 0.5d | — |
| 2 | TurnRoutes（D1 全链：门闩/listener/Deferred/retry-read/usage 捕获）+ TurnRoutesSpec | 2-3d | — |
| 3 | RunCommand + 网关拉起 + 退出码（D3）+ CLI 单测 | 2d | 步 2（端点先在） |
| 4 | HeadlessMode 触点 a-e（D5 清单）+ HeadlessModeSpec | 1-2d | — |
| 5 | smoke 脚本 + E2E 容器验证（§5 后两行） | 0.5d | 步 2-4 |

步 1/2/4 可并行（不同文件域）；步 3 依赖端点契约冻结（本文档 3.1 即契约）。

## 7. 风险与开放问题

| # | 风险 | 缓解 |
|---|---|---|
| R1 | persist 竞态窗（D1 已析） | retry-read 5s 上限 + CLI 空结果 exit 1 兜底；若实测频繁（不应——save 通常 <100ms）再评估 actor 侧顺序化 |
| R2 | 前端与 benchmark 同 session 并发：listener 收全量 broadcast，同 session 在线前端会看到注入消息 | 容器场景无前端；本地开发并发属可接受噪音，文档注明 |
| R3 | 409 门闩与 actor 内 ImmediateInput 排队语义并存：门闩拦截的是"HTTP 并发"，排队仍存在（listener 挂着期间第二个 turn 被 409 而非排队）——两 turn 串行需调用方重试 | 文档化：benchmark 单 session 单 turn，无串行需求；HTTP 429 语义未来需要再加 |
| R4 | 容器 API key 链（env → nebflow.json） | Dockerfile/entrypoint 职责（P1），本设计仅保证 NEBFLOW_HEADLESS 与 key 配置互不干扰 |
| R5 | turn 超时后 agent 继续跑：容器即焚无泄漏；本地开发长跑残留 busy session | StatusCommand 可见；P1 可加 cancel 端点（非目标已列） |

**开放问题**（实施中决策，不阻塞审批）：
- O1 语言规则注入点的确切位置（ContextRefresher vs PromptSections）——步 4 实施时定位，一处 if HeadlessMode
- O2 usage.outputTokens 缺失（Done 事件只有 inputTokens）——P1 从 LlmLogWriter 路由日志补，P0 响应体字段允许 null
- O3 `nebflow start` detached 在 Windows 容器的行为——Terminal-Bench 容器是 Linux，Windows 路径走手工 start，文档注明即可

---

**审批请求**：D1 listener 方案（vs InteractionHub/Actor 协议改动）、409 门闩语义、userMessage 服务端修复方向、HeadlessMode 单开关粒度——四点如无异议按 §6 排期实施。
