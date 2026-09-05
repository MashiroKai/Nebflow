# DeepSeek Harness 技术调研报告

> 调研对象：DeepSeek Harness（`dsh`），master 分支，v0.1.0-rc.8
> 仓库：`/tmp/deepseek-harness`（pnpm monorepo，约 2700 个 TS 文件 + 14 个 Python 文件）
> 调研日期：2025-08-21
> 调研目的：快速了解项目整体架构，评估哪些技术路线可借鉴到 Nebflow（Scala/Pekko 技术栈）

---

## 目录

1. [一页速览](#1-一页速览)
2. [分层架构详解](#2-分层架构详解)
   - 2.1 Monorepo 分层
   - 2.2 插件机制（"Everything is a Plugin"）
   - 2.3 Agent 运行时模型
   - 2.4 UI 与后端通信方式
   - 2.5 安全/权限/沙箱
   - 2.6 生产级管理
   - 2.7 多设备/手机端
3. [Nebflow 借鉴评估表](#3-nebflow-借鉴评估表)
4. [优先级建议](#4-优先级建议)

---

## 1. 一页速览

### 架构图（文字版）

```
┌─────────────────────────────────────────────────────────────────────┐
│                        用户入口 (Entry Modes)                         │
│  dsh web (Web UI :3080)  │  dsh --profile headless (一次性 CLI)  │  Python SDK (JSON-RPC stdio)  │
└──────────┬───────────────┴───────────────┴───────────────┴────────────┘
           │                          │                         │
           ▼                          ▼                         ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     Profile / Bundle 组合层                           │
│   Profile = 有序 Bundle 栈 + cordis.patch.yml 用户覆盖层               │
│   dsh-base (模型适配器/工具/持久化/沙箱/审批/凭证)                      │
│   + dsh-web-app (浏览器应用)  或  + dsh-headless (无服务器一次性运行)    │
└──────────────────────────────┬──────────────────────────────────────┘
                               │ Cordis 插件上下文 (ctx) 装配
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│                        Cordis 插件树 (核心脊柱)                        │
│                                                                      │
│  ┌──────────┐  ┌───────────┐  ┌────────┐  ┌──────────┐  ┌────────┐ │
│  │ session  │  │system-prompt│ │  tools │  │  agent   │  │  llm   │ │
│  │ (事件日志) │  │ (提示词组装)│  │(工具注册)│  │(Agent注册)│  │(模型流) │ │
│  │ctx.sessions│ │ctx.systemPrompt│ │ctx.tools│ │ctx.agents│  │ctx.llm │ │
│  └────┬─────┘  └─────┬─────┘  └───┬────┘  └────┬─────┘  └───┬────┘ │
│       │              │             │             │            │      │
│       │    ┌─────────┴──────────┐  │    ┌────────┴──────┐     │      │
│       │    │ agent-loop (驱动)  │◄─┤    │  scope (per-  │     │      │
│       │    │ ctx.agentLoop      │  │    │  agent 注册)  │     │      │
│       │    └────────────────────┘  │    └───────────────┘     │      │
│       │                            │                          │      │
│  ═════╪════════════════════════════╪══════════════════════════╪══════│
│       │     可插拔能力接缝 (Capability Seams)                    │      │
│       │                            │                          │      │
│  ┌────┴────┐ ┌──────┐ ┌─────┐ ┌──────┐ ┌──────┐ ┌─────────┐ ┌──────┐│
│  │persist- │ │shell │ │ fs  │ │sandbox│ │subagent│ │approval │ │web   ││
│  │ence     │ │ctx.  │ │ctx. │ │ctx.   │ │ctx.   │ │ctx.     │ │ctx.  ││
│  │ctx.     │ │shell │ │fs   │ │sandbox│ │subagents│ │approval │ │web   ││
│  │session- │ │      │ │     │ │       │ │       │ │         │ │      ││
│  │Persist.  │ │      │ │     │ │       │ │       │ │         │ │      ││
│  └─────────┘ └──────┘ └─────┘ └──────┘ └──────┘ └─────────┘ └──────┘│
│  (JSONL/SQLite) (bash/pty) (local/ │  (bwrap/    (in-process/ │(search/│
│                                     │   e2b)       fork/acp)    │ fetch)│
│                                     │  Seatbelt/                │      │
│                                     │  Landlock)                │      │
└─────────────────────────────────────┴──────────────────────────┴──────┘
           │                                        │
           ▼                                        ▼
┌─────────────────────┐              ┌──────────────────────────────┐
│  Host (Node.js 进程)  │              │  Client (浏览器 React SPA)     │
│  - WebServer :3080   │◄──HTTP/WS───►│  - connection (RPC + WS 下行)  │
│  - /api 路由 (Typert  │              │  - runtime (会话/工作区组合)    │
│    Gateway + Proxy)  │              │  - ui-* 插件 (30+ UI 模块)     │
│  - events.mux/host   │              │  - Vite 构建                   │
│    WebSocket 下行    │              └──────────────────────────────┘
└─────────────────────┘
```

### 核心设计决策表

| 决策 | Harness 做法 | 设计理由 |
|------|-------------|---------|
| **插件架构** | Cordis 框架：一切皆插件（模型适配器、工具注册表、会话日志、agent 循环本身都是插件），通过 `ctx.<key>` 共享服务，注册是可逆 effect | 无特权核心可改；通过挂载插件而非打补丁扩展 |
| **事件溯源** | Session = 追加写入的事件日志，LLM 消息历史从日志 *派生*（`deriveMessages()`），不单独存储 | "模型可见 = 已日志记录" 的运行时不变量；fork/resume/重放全从日志派生 |
| **能力接缝** | 每个能力 = 三角色分离：Service Definition（接口）、Provider（实现）、Consumer（调用方），通过 `ctx.*` 注册可替换 | 一次 Provider 切换改变整个产品行为（如换 fs+subprocess 到 E2B 远程沙箱） |
| **沙箱** | 进程级文件效果隔离：Linux bwrap/Landlock、macOS Seatbelt、Windows ACL 限制令牌；每调用携带策略（per-call policy） | 消费者无感平台 runner；confining 模式失败即关闭（fail-closed），绝不静默放行 |
| **审批** | 闭集结果 `allowed-once`/`rejected`/`cancelled`/`unavailable`，默认 fail-closed；每会话策略 `ask`/`never` | 一次性授权、确定性拒绝、无回答器 = 不可用 = 拒绝 |
| **持久化** | JSONL（追加写入 + Zstd 压缩）或 SQLite（schema 17）双后端；批量刷新检查点；崩溃恢复保留中断 turn | 不截断中断 turn（长任务可能很大）；合成 `turn/end { interrupted }` 平衡日志 |
| **通信** | 浏览器 ↔ Host：HTTP POST（一元调用）+ WebSocket 下行双流（events.mux + events.host）；Typert 生成严格类型 RPC 契约 | 一元走 `/api` 路由，流式走 WS 下行；生成式类型安全 |
| **多 Agent** | Subagent 接缝（多 Provider 共存：in-process/fork/acp/codex/claude-code/dsh-sdk）；实验性 Agent Teams（持久花名册 + 任务 DAG + 邮箱） | 委托 = 可继续子会话；Team = 同级协作 |
| **密钥管理** | 凭证接缝：配置只存 *引用*（环境变量名），Provider 拥有值，每次操作重新解析（热更新无需重启） | 密钥不进配置文件；轮换即时生效 |
| **部署形态** | Web UI（localhost :3080）、headless（一次性 CLI）、Python SDK（子进程 JSON-RPC）；PWA manifest `display: fullscreen` | 本地优先；无 TLS/认证/origin 策略，非回环绑定 = 网络暴露风险 |

---

## 2. 分层架构详解

### 2.1 Monorepo 分层

仓库是 pnpm monorepo（`pnpm@11.7.0`，Node 22.19+），workspace 定义在 `pnpm-workspace.yaml`。分层如下：

| 层 | 目录 | 内容 | 规模 |
|----|------|------|------|
| **apps/** | `apps/cli`、`apps/web` | 产品入口。`cli` = `dsh` 命令启动器（profile 加载、参数解析）；`web` = Vite 构建的前端 SPA 入口（`index.html` + `src/main.ts`） | 2 个 app |
| **packages/** | 57 个子目录 | 核心能力包群。按功能域分组：`core/`（session/tools/agent/agent-loop/scope/system-prompt）、`host/`（webserver/apiproxy/frontend-static）、`client/`（浏览器 UI 30+ 模块）、`shell/`、`sandbox/`、`session/`、`fs/`、`web/`、`mcp/`、`subagent/`、`compaction/`、`credentials/`、`interaction/`、`schedule/`、`jobs/`、`e2b/` 等 | ~2500 TS 文件 |
| **native/** | `native/landlock-run` | 原生代码：Landlock 沙箱启动器（~300 行 C11，静态链接 musl），Linux x64/arm64 预编译 npm 包 | 1 个原生组件 |
| **python/** | `python/sdk`、`python/sdk-runtime` | Python SDK：通过 JSON-RPC stdio 驱动 Harness 运行时（非 Web UI 入口） | 14 个 Python 文件 |
| **vendor/** | Cordis 框架等 | 供应商内联包（cosmokit、schemastery 等） | — |
| **docs/** | 64 个文档 | 架构文档、子系统参考、用户指南、烹饪手册、事后分析 | — |
| **examples/** | 8 个示例 | headless-agent、acp-agent、jsonrpc-agent、mcp-memory、web-cordis、web-schedule 等 | — |
| **website/** | VitePress | 官方文档站点 | — |

**关键观察**：packages 层是真正的核心——apps 只是薄入口。`packages/*/*` 的 glob 意味着每个功能域是一个子目录含多个包（如 `packages/sandbox/` 下有 `sandbox`、`sandbox-local`、`sandbox-policy`、`sandbox-windows-acl`）。

证据：`pnpm-workspace.yaml`、`package.json` workspaces 字段、`docs/architecture.md`。

### 2.2 插件机制（"Everything is a Plugin"）

**核心框架：Cordis**（供应商内联在 `vendor/`）。Cordis 的五个核心理念（`docs/cordis-primer.md`）：

1. **插件 = 实现 Service 的对象**：函数式（带 `inject`/`apply(ctx)`）或 `Service` 子类，Cordis 将其生命周期挂载到当前上下文
2. **上下文 = 服务仓库**：服务通过稳定 key（`ctx.tools`、`ctx.llm`、`ctx.sessions`）从上下文获取，插件间通过 key 查找而非导入具体实现
3. **依赖通过 `inject` 声明**：命名所需服务的插件会等待这些服务存在，加载顺序通过服务需求表达而非手动编排
4. **类型化事件通信**：服务通过 TypeScript 声明合并声明事件名，按 `emit`/`waterfall`/`parallel`/`serial` 四种模式分发
5. **注册是可逆 effect**：提示词段、工具 schema、适配器、Provider、监听器通过 `ctx.effect()` 或 `ctx.on()` 安装，卸载/重载时可预测回滚

**"一切皆插件"具体含义**（`docs/architecture.md`）：

> "Every part of the product is a plugin, including the model adapter, the tool registry, the session log, and the agent loop itself, so every part is replaceable from configuration."

即：模型适配器、工具注册表、会话日志、**agent 循环本身**都是插件，都可通过配置替换。没有特权核心需要打补丁——你在其他插件旁边挂载一个插件即可扩展。

**Profile / Bundle 组合机制**：

- **Profile**：Harness home 中的命名组合，列出它堆叠的 bundle，持有带外插件，保存用户的 `cordis.patch.yml`
- **Bundle**：Cordis 配置行和它们挂载的代码的分发格式，声明在各自 `package.json` 的 `dsh` 字段
- 层叠顺序：profile 中 bundle 声明顺序 → profile 的 `cordis.patch.yml` → home 级 `cordis.patch.yml` → `--patch` 覆盖
- 每个 patch 行通过 id 定位，替换整行配置或插入新行
- 查看实际启动树：`dsh --profile web --dump-config`

**能力接缝（Capability Seam）——三角色分离**（`docs/architecture.md` capability-seams）：

每个可插拔能力由三角色构成：
- **Service Definition**：声明接口（如 `ctx.fs` 文件系统服务）
- **Service Provider**：实现接口（如 `dsh-fs-local` 本地磁盘、`dsh-fs-e2b` E2B 远程沙箱）
- **Consumer**：使用能力（通常是模型面向工具，如 `dsh-tool-fs`）

关键设计：**一次 Provider 切换改变整个产品行为**。Filesystem 和 subprocess Provider 共享一个执行世界，所以指向远程沙箱会把 Bash、PTY、LSP 一起搬过去，无需 Provider 分叉（`docs/architecture.md`）。

**事件分类**（`docs/architecture.md` Events）：
- **Session 事件**：持久事实追加到日志，通过 `session/event` 广播。需在重载后存活的事实用这个
- **Agent 事件**（`agent/*`）：携带活动 Agent——inbox、step、status、request、validation、continuation。观察/拦截在途工作用这个
- **能力事件**：将策略和适配器附加到接缝（`fs/*`、`tools/*`、`telemetry/*`），不导入循环

证据：`docs/cordis-primer.md`、`docs/architecture.md`、`docs/capability-seams.md`、`packages/boot/`。

### 2.3 Agent 运行时模型

**Turn / Step 两级执行模型**（`docs/architecture.md` Turn flow）：

```
turn/start
  claim next-step input + 一条排队消息
  组装提示词段 + 工具 schema
  -> agent/pre-step                   reject | enter(messages)
     reject 或首次 enter 被改写为空 → 关闭 turn（无 step）
     step/start
     追加 entered messages 为 user/message
     从日志派生模型历史
     agent/request -> llm/stream -> assistant/chunk* -> assistant/message
     tool/call* -> tools/pre-execute -> tools/execute -> tools/post-execute -> tool/result*
     step/end
     tools 欠另一个请求，或 next-step input 到达 → claim → 下一个 step
  -> agent/turn-stopping
turn/end
```

- **Step** = 一次模型请求 + 它调用的工具
- **Turn** = 零或多个 step：在首个 input 被 claim 前打开，不欠任何东西后关闭
- `turn/*`、`step/*`、`user/message`、`assistant/*`、`tool/*` 是持久 session 事件；其余是活动扩展点

**Session = 追加写入事件日志**（`docs/subsystems/session.md`、`docs/subsystems/core.md`）：

Session 是类型化 `SessionEvent` 的追加写入日志——唯一真相源。LLM 消息历史从日志 *派生*（`deriveMessages()`），不单独存储。每条事件携带单调 `seq`、`time`、`type` 判别的 `data`。

12 种事件类型（`SessionEventMap`）：
`turn/start`、`turn/end`、`step/start`、`step/end`、`user/message`、`assistant/chunk`、`assistant/message`、`tool/call`、`tool/result`、`steering/message`、`todo/write`、`request/header`

**运行时不变量**："模型可见 = 已日志记录"。任何到达模型请求的内容必须能从日志重建，运行时有断言验证（`docs/architecture.md`）。

**Agent 句柄**（`docs/subsystems/core.md`）：

`Agent` 接口是每个插件编程的表面：
- `send(message, target, wakeup)`：路由输入到 inbox 边界，可选唤醒驱动
- `followup(message)`：排队普通后续 turn
- `steer(message)`：提交步内引导
- `inject(message)`：排队模型可见上下文到下一步，不唤醒驱动
- `cancel(cause, options)`：取消活动 turn，可选保留 inbox
- `whenIdle()`：等待当前活动达到静止
- `runMaintenance(task)`：从真静止期运行非 turn 维护任务
- `ctx`：Agent 作用域上下文，贡献是 agent 本地的，处置时回滚

**Agent 创建/恢复**（`docs/subsystems/core.md`）：
- `ctx.agents.create()`：构建新 session + agent
- `ctx.agents.resume()`：先加载持久 session
- `setup` 回调：在两个 id 都未发布前组合 agent 的作用域世界——`agent/created` 和首次提示词组装前注册的一切
- `AgentFactory`：循环通过 `ctx.agents.setFactory()` 注册工厂，消费者用 `ctx.agents` 而不依赖具体循环包

**Subagent 多 Provider 接缝**（`docs/subsystems/subagent.md`）：

与 bash 只允许一个执行器不同，**多个 subagent Provider 实现可在同一上下文共存**，按名称注册（`ctx.subagents`）。Provider 包括：
- `dsh-subagent-spawn-in-process`：进程内子 agent
- `dsh-subagent-fork`：fork 当前会话
- `dsh-subagent-acp`：ACP 协议
- `dsh-subagent-codex`：Codex 后端
- `dsh-subagent-claude-code`：Claude Code 后端
- `dsh-subagent-dsh-sdk`：SDK 子进程

**可继续子 agent（Continuable Children）**：一个持久子 Session 最多有一个进程本地 Activation（活动期），Activation 可执行多个 FIFO turn。Activation 不是请求、结果、取消或 Task——它是一个驻留期（`docs/subsystems/subagent.md`）。

**实验性 Agent Teams**（`docs/subsystems/agent-team.md`）：
- `ctx.agentTeams`：私有 opt-in 协作接缝
- 持久花名册（roster）+ 任务看板（task DAG with `blockedBy` edges）+ 邮箱（durable mailbox）
- Lead Session 存储完整排队消息，目标确认仅在 pending inbox 项或记录的 user message 持久后
- `foldTeam()` 从根 Session 重放出花名册、任务看板、排队减已投递邮箱

证据：`docs/architecture.md`、`docs/subsystems/core.md`、`docs/subsystems/session.md`、`docs/subsystems/subagent.md`、`docs/subsystems/agent-team.md`。

### 2.4 UI 与后端通信方式

**架构分层**（`packages/client/README.md`、`docs/api-gateway.md`）：

```
浏览器 (Client)                           Host (Node.js)
┌─────────────────────┐                  ┌─────────────────────┐
│ client/connection   │◄── HTTP POST ──►│ /api 路由            │
│ (AbstractApiClient  │   (一元调用)      │ (Typert Gateway     │
│  + RPC 关联)         │                  │  + API Proxy 回退)   │
│                     │◄── WebSocket ──►│                     │
│ events.mux 下行      │   (双 WS 流)     │ /api/events.mux     │
│ events.host 下行     │                  │ /api/events.host    │
└─────────────────────┘                  └─────────────────────┘
```

**Typert API Gateway——生成式类型安全 RPC**（`docs/api-gateway.md`）：

业务服务在 Host 端用 `@Remote` 或 `@RemoteScope` 装饰器声明可调用方法。构建时：
1. Host `tsc -b` 编译 → Typert generator 分析 Host `ts.Program`，生成严格调用描述符 + schema
2. 生成 Host 反射产物（`typert.host.js`）和 Client 远程投影（`typert.remote-client.js`）
3. Client `tsc -b` 编译 → 消费生成的声明，打包浏览器 bundle

运行时：
- Client 调 `connection.rpc.call('/api', '<namespace>/<method>', { args }, signal)`
- HTTP 映射为 `POST /api/<namespace>/<method>`，payload 只含命名 `args` 对象
- Gateway 解析描述符和活动服务，验证参数/返回值，通过 lookup provider 解析对象

**Connection 信任边界**（`packages/client/connection/README.md`）：

`/api` 浏览器信任围栏——每个请求必须满足之一：
- `Host` 是回环地址
- `Host` 匹配 `trustedHosts` 条目（精确匹配 `host:port`，或无端口的任意端口）
- DNS 重绑定防御：通过 WHATWG 规范化比较

**`dsh web --host 0.0.0.0` 被故意标记为不支持**，直到远程访问有认证层。围栏是可达性策略，不是认证；Web carrier 不提供认证层。

**WebSocket 下行**（`packages/client/connection/README.md`）：
- `/api/events.mux` 和 `/api/events.host` 各接受一个 WebSocket 升级
- 只发送对应的 `ServerRequest` 文本消息到浏览器；客户端不在这些 socket 上发送应用数据
- 任一 socket 结束 → 当前连接 generation 失败并重建两个流
- 普通 GET 到这些路径返回 426，无 SSE 回退

**WebServer**（`docs/subsystems/web-server.md`）：
- 单个 `node:http` 插件，提供 `ctx.webServer`
- 命名路由注册表 + index.html 变换回调 + 一个回退处理器
- `host` 只接受 `127.0.0.1`（默认）或 `0.0.0.0`（故意网络暴露）；无 TLS、认证或 origin 策略
- 路由匹配顺序：精确表 → 最长前缀匹配 → 注册的回退

**前端架构**（`packages/client/README.md`）：
- 30+ UI 模块插件（`ui-conversation`、`ui-sidebar`、`ui-tool`、`ui-settings`、`ui-subagent` 等）
- `ui-layout`：三栏布局（sidebar / center / details），有自动折叠断点 `SIDEBAR_AUTO_COLLAPSE`
- `ui-renderer`：将 slot 数据绑定到 React 并挂载组装应用
- `connection`：维护浏览器-Host RPC 通信和事件投递
- `hmr`：开发时刷新客户端插件

证据：`docs/api-gateway.md`、`packages/client/connection/README.md`、`docs/subsystems/web-server.md`、`packages/client/README.md`。

### 2.5 安全 / 权限 / 沙箱

#### 2.5.1 工具权限模型

**工具执行管道——可扩展瀑布 + 单调策略**（`docs/subsystems/tools.md`、`docs/tool-execution-pipeline.md`）：

```
模型输出 tool-call
  → tools/pre-execute 瀑布（hooks, permission, sandbox）— 可排序的 allow/deny/ask
    → ctx.approval 一次性询问（无回答器 = deny）
  → 注册的单调 guards（deny 或 abstain，身份保护）
  → tools/execute 瀑布（timeout, retry, metrics，around dispatch）
  → 工具 execute() body
  → tools/post-execute 瀑布（accept, block, replace, add context）
  → finalizeContent（最后内容不变量）
  → tools/result 同步通知（冻结权威结果）
  → session 事件 tool/result
```

- `tools/pre-execute` 是可重排序的 allow/deny/ask 瀑布
- `ctx.approval` 在单调 guards 之前解析询问
- 工具 schema 中的 `output`/`execute`/`finalizeContent`/`timeoutMs`/`isConcurrencySafe`/`presentCall`/`presentResult` 永不泄漏到模型请求

**`ToolRestriction`——作用域级继承过滤器**（`docs/subsystems/tools.md`）：

部署全局层 + 每个祖先 scope 链上的工具。`allow` 白名单排除未列出的；`deny` 黑名单移除列出的。scope 自己的注册豁免（被委托的子 agent 保留它应答用的工具）。

#### 2.5.2 审批策略

**用户审批接缝**（`docs/subsystems/approval.md`）：

`ApprovalOutcome` 是闭集且 fail-closed：
- `allowed-once`：仅授权被询问的操作（唯一授权）
- `rejected`：明确拒绝
- `cancelled`：撤回请求
- `unavailable`：缺失/非拥有/抛异常/不符合规范的回答器 → 调用方 deny

**每会话策略 `ApprovalPolicy`**（`docs/subsystems/approval.md`）：
- `ask`（默认）：委托给组合回答器链，无回答默认 `unavailable`
- `never`：确定性返回 `rejected`，不分发任何回答器（CI/无人值守场景的严格立场）

**`never` 策略在服务内、瀑布分发前强制**——即使后来用 `prepend` 注册的回答器也无法绕过。

**审批审计**：`approval/asked` 和 `approval/decided` 事件对是仅日志的，不进入模型转录。每次请求获得新的 `ApprovalRequestId`。

**权限预设层**（`docs/subsystems/permission-presets.md`）：

`ctx.permissionPresets` 将两个独立旋钮——sandbox mode（`sandbox/mode`）和 approval policy（`approval/policy`）——打包成命名预设：

| 预设 | sandbox | approval | 用途 |
|------|---------|----------|------|
| `workspace-write` | workspace-write | ask | 默认：工作区可写 + 交互审批 |
| `danger-full-access` | danger-full-access | never | 完全访问 + 无审批 |

`custom` 是派生状态（两个旋钮的组合不匹配任何预设时），不是切换目标。

#### 2.5.3 命令执行沙箱

**进程沙箱接缝**（`docs/subsystems/sandbox.md`、`packages/sandbox/sandbox-local/README.md`）：

`SandboxMode` 只管文件效果：
- `read-only`：拒绝写入（POSIX runner 额外授予 `/dev/null`）
- `workspace-write`：允许工作区根 + 后端承诺的临时区写入
- `danger-full-access`：绕过限制（消费者 spawn 原始 argv，不调用 `ctx.sandbox`）

**平台后端**（`packages/sandbox/sandbox-local/README.md`）：

| 平台 | 后端 | 机制 |
|------|------|------|
| Linux | bwrap → Landlock | bwrap 优先；Landlock = 自限制后 exec（~300 行 C11，`native/landlock-run`），规则集跨 `execve` 继承 |
| macOS | Seatbelt | `sandbox-exec`（Apple 标记为 deprecated 但仍随 macOS 发布），allow-default + `(deny file-write*)` + 写入白名单 |
| Windows | ACL 限制令牌 | 每 session/workspace 对一个随机私有临时目录 + 独立 SID + 可撤销 ACE；报告 `enforcement: 'partial'`（Everyone 保留用于进程初始化） |

**每调用策略（Per-call policy）**：策略不在 provider 上固定，而是每次调用携带。`SandboxPolicy` 扩展 `SandboxExecutionPolicy`，只带 confined 模式。允许并发 session、消费者和一次性升级重试向同一 provider 请求不同边界。

**Fail-closed 原则**：
- `ctx.sandbox.confine(argv, policy)` 返回 `ConfinedArgv` 或抛 `SandboxUnavailableError`
- 无可用后端 → 抛错，**绝不静默无限制透传**
- `ConfinedArgv` 携带 `denialSignatures`（被阻止的 stderr 特征）和 `runnerFailureRules`（runner 自身失败的 stderr 特征），消费者区分"沙箱正确阻止了命令"和"沙箱 runner 坏了"

**E2B 远程沙箱 POC**（`packages/e2b/README.md`）：实验性 Provider 组合，将整个 filesystem + subprocess 执行世界放入 E2B Linux 沙箱。`dsh-bash-local`、`dsh-terminal-bash`、`dsh-lsp-stdio` 无需 E2B 专属分叉——它们委托所有执行世界操作给 `ctx.fs` 和 `ctx.subprocess`，挂载两个 E2B 适配器即可。

#### 2.5.4 密钥管理

**凭证接缝**（`docs/subsystems/credentials.md`）：

核心原则：**密钥不进配置**。
- 配置段和 `cordis.yml` 条目只存 *引用*（环境变量名）
- Provider（如 `dsh-credentials-local`）拥有值
- 消费者每次操作解析一次引用——LLM 适配器每次模型请求解析一次，**轮换的凭证在下一个请求即生效，无需重启**
- 一条规则绑定所有 Provider：空存储值在各处都视为不存在

**`CredentialRef`**：POSIX 风格环境变量名的品牌类型，防止与包间传递的其他字符串混淆。

**`describe(ref)`**：配置界面用，永不暴露值——只报告是否已配置、来自哪层、`set` 是否会成功。本地 Provider 报告由活动进程环境提供的引用为 `writable: false`（写入会看似成功但解析仍返回影子值）。

**`credentials/updated` 事件**：Provider 管理的凭证源的提交变更后触发（set、unset 或外部编辑）。环境进程环境变更不可观察，永不触发。

证据：`docs/subsystems/tools.md`、`docs/subsystems/approval.md`、`docs/subsystems/permission-presets.md`、`docs/subsystems/sandbox.md`、`packages/sandbox/sandbox-local/README.md`、`docs/subsystems/credentials.md`、`native/landlock-run/README.md`、`packages/e2b/README.md`。

### 2.6 生产级管理

#### 2.6.1 会话持久化 / 恢复

**持久化接缝**（`docs/subsystems/persistence.md`）：

抽象 `SessionPersistence` 服务 + 三个可互换后端，实现同一契约（locate/create/append/prepare/load/inspect/readFrom/list/listSnapshots）：

| 后端 | 格式 | 特点 |
|------|------|------|
| **JSONL**（`dsh-session-persistence-jsonl`） | 每会话一个追加写入 JSONL 日志，默认 Zstd 压缩的校验和级联帧 | 崩溃安全原子写入；中断 turn 恢复 |
| **SQLite**（`dsh-session-persistence-sqlite`） | opt-in `node:sqlite`，schema 17，有界物理行存储 | 只打包新持久批次；拒绝旧 schema 而非迁移 |

**刷新检查点（Flush Checkpoint）**：
- `session/event` 是同步通知；持久化插件将事件复制到每会话控制器，不阻塞生产者
- 首个待处理事件启动固定批量窗口，后续事件加入不重置截止时间
- 到期开始一个持久批次；写入期间接纳的事件收到自己的截止时间形成后续批次
- `session/flush` 取消等待并排空至静止

**崩溃恢复——保留中断的 turn**（`docs/subsystems/persistence.md`）：

后端重载崩溃 mid-turn 的日志时发现 `turn/start` 无 `turn/end`。**不截断**——单个 turn 在长周期任务中可能很大（多 step、大工具输出），这些事件在崩溃前已持久追加。而是用合成的 `turn/end { reason: { kind: 'interrupted' } }` 关闭孤立的 turn。`interrupted` 是唯一循环不发射的 `TurnEndReason`。

**SessionHeader——日志旁的元数据**（`docs/subsystems/persistence.md`）：
- 格式版本、cwd、血统、seed 边界是存储关注点，不是会话事件
- `version` 拒绝不匹配（无迁移——`SESSION_FORMAT_VERSION` 盖戳）
- 未知事件类型拒绝加载（`SessionFormatUnsupportedError`），除非 envelope 带 `ignorable: true`

**Fork / Resume**（`docs/subsystems/session.md`）：
- `ctx.sessions.create(id, { seed })`：低层重放/fork 原语
- `SessionStore.fork(source, boundary?, childSessionId?)`：接受活动 Session 或 SessionId，选择源事件到包含 `boundary` seq，要求选定的前缀在 turn 外结束，创建活动子会话
- `ctx.agents.resume({ resumeSessionId })`：恢复持久会话到活动 agent

#### 2.6.2 并发与队列

**工具并发**（`docs/subsystems/tools.md`）：
- `isConcurrencySafe(args)`：纯同步分类器，只有 `true` 选择加入并行执行
- `ToolExecutionMode`：`parallel`（可与兄弟重叠）或 `exclusive`（独占运行，形成排序屏障）
- Agent 循环用每个待处理调用的执行模式形成独占屏障和滚动池并行运行

**后台任务运行时**（`docs/subsystems/jobs.md`）：
- `ctx.jobs`：通用长运行工具运行时
- `JobId` = `<kind>-N`，访问控制依赖 owner 授权而非 id 保密
- `JobStatus` = `running | stopping | completed | killed | failed`
- `JobHooks`：`cancel()`（同步幂等）、`done`（资源释放后解决）、`readOutput()`（增量输出消费）
- Agent 处置取消并等待其拥有的 job

**Agent Inbox——唯一队列**（`docs/subsystems/core.md`）：
- 两个有序待处理消息列表：`next-turn` 和 `next-step`
- `claim(target)` 移除提议的 step 批次（所有 next-step 输入 + turn 边界时一条 next-turn 消息）
- 每个待处理项是其 `UserMessage`；`MessageId` 是唯一身份
- FIFO 顺序：接受的 follow-up 有一个可观察顺序，进行中的 turn 上的 follow-up 不能重定向

**Spill 存储**（`docs/subsystems/spill.md`）：工具输出过大时持久化溢出文本到文件，返回模型面向的定位器 + 检索指引，而非内联全文。

#### 2.6.3 可观测性

**Session 遥测**（`docs/subsystems/session-telemetry.md`）：
- `ctx.sessionTelemetry`：捕获协调器 + 固定 chunk 投影 + `session-telemetry/record` 脱敏瀑布 + 游标 + 最小后端契约
- `dsh-session-telemetry-otel`：OpenTelemetry JS SDK 的 log 管道（verbatim 配置）
- 边界公理：harness 的 aspect 到 `emit()` 为止；批处理、重试、排队、丢失策略属于报告 SDK
- 两个频道：`ledger`（session-log 镜像）和 `ops`（operational 信号：agent-error、shutdown）
- 严重性预映射：`error`（工具 isError、turn/end 错误原因、agent-error）、`info`（默认）、`warn`

**运行时不变量**（`docs/subsystems/invariants.md`）：
- `ctx.invariants`：可配置注册服务，包拥有的运行时不变量检查
- 每个 workspace 包发布 `./invariant` 伴随插件，以精确 npm 包名注册检查
- 选择：全局开关 + 包 allowlist/blocklist（正则）
- 不变量可断言权威事件流或可变数据，不断言服务/方法存在

**Token 计量**（`docs/subsystems/token-meter.md`，从 `docs/subsystems/README.md` 索引确认）：`ctx.tokenMeter` 拥有估算和重放。

**Session Query**（`docs/subsystems/session-query.md`）：
- 跨语料库列表、精确读取、源优先级、关系追踪、语义提取
- SQLite Provider 拥有全文索引生命周期
- `SessionRecord`：活动优先头克隆 + `live`/`persisted` 可用性标志

#### 2.6.4 部署形态

**三种入口模式**（`apps/cli/README.md`、`python/README.md`）：

| 形态 | 命令 | 架构 | 场景 |
|------|------|------|------|
| **Web UI** | `dsh web` | Host Node.js + WebServer :3080 + 浏览器 React SPA | 本地交互开发 |
| **Headless** | `dsh --profile headless "job"` | 无服务器，创建持久会话、打印最终答案、退出 | CI/一次性任务 |
| **Python SDK** | `deepseek-harness-sdk` | Python 客户端通过 JSON-RPC stdio 驱动 bundled runtime 子进程 | 编程式集成 |
| **ACP** | `examples/acp-agent` | Automation-oriented Agent Client Protocol server，JSON-RPC stdio | 父 agent / 子 agent provider |
| **TypeScript SDK** | `packages/sdk/` | 协议栈 + client API + stdio JSON-RPC server | 另一进程驱动 Harness |

**PWA 支持**（`apps/web/public/manifest.webmanifest`）：
- `display: "fullscreen"`，有 favicon.svg 图标
- 可安装到桌面，但非专门移动端优化

**Profile 模板**：`web` 和 `headless` 作为模板发布，首次使用时从模板自动初始化。

证据：`docs/subsystems/persistence.md`、`docs/subsystems/session.md`、`docs/subsystems/tools.md`、`docs/subsystems/jobs.md`、`docs/subsystems/spill.md`、`docs/subsystems/session-telemetry.md`、`docs/subsystems/invariants.md`、`docs/subsystems/session-query.md`、`apps/cli/README.md`、`python/README.md`、`apps/web/public/manifest.webmanifest`。

### 2.7 多设备 / 手机端

#### 结论：Harness 没有专门的多设备/手机端方案

经全面搜索（docs 全文 grep `mobile|phone|pairing|pair|scan.*code|qr|device.*registr|relay`、前端源码 grep `responsive|mobile|viewport|pwa|breakpoint`），**Harness 没有实现扫码配对、设备注册、中继架构或手机端控制桌面的通道**。

#### 现有的"远程"能力

**1. LAN 访问（受限）**（`packages/client/connection/README.md`、`docs/subsystems/web-server.md`）：

- WebServer `host` 可设为 `0.0.0.0` 暴露到网络
- `/api` 信任围栏要求 `Host` 是回环或匹配 `trustedHosts`
- **但 `dsh web --host 0.0.0.0` 被故意标记为不支持**，直到远程访问有认证层
- 围栏是可达性策略，不是认证；Web carrier 不提供认证层
- 非 loopback 组合必须显式信任其服务权威：Web runtime 从 all-interfaces 配置推导 LAN IP 字面量

**2. 前端响应式设计（桌面级，非移动端优化）**（`packages/client/ui-layout/src/client/columns.ts`）：

- 三栏布局（sidebar / center / details）
- 有 `SIDEBAR_AUTO_COLLAPSE` 断点（引用 "deepsuite LG breakpoint"），视口窄于该断点时侧边栏自动折叠为紧凑导轨
- 但这是桌面窗口缩小的适配，不是手机端设计——测试固定使用 `viewport: { width: 1440, height: 960 }`（`apps/web/tests/remote-welcome.e2e.ts`）

**3. PWA Manifest**（`apps/web/public/manifest.webmanifest`）：

- `display: "fullscreen"`，可安装到桌面/手机主屏
- 但没有 service worker、离线缓存、或移动端专用交互优化

**4. Python/TS SDK 作为"远程"控制**（`python/README.md`、`packages/sdk/README.md`）：

- SDK 通过 JSON-RPC stdio 驱动 Harness runtime 子进程
- 这是编程式集成，不是手机端控制桌面的人机通道

#### 与 NebLink 的对比

| 维度 | Harness | Nebflow NebLink |
|------|---------|-----------------|
| 远程访问 | LAN 信任围栏（无认证层，`0.0.0.0` 被标记不支持） | Rust 中继服务器（VPS）+ 客户端，JWT + Device Flow OAuth |
| 配对机制 | 无 | 账号登录制（无扫码/链接配对） |
| 手机端 WebUI | 无 | 无 |
| 远程 Bash/文件传输 | 无（沙箱是进程级本地隔离） | 支持 |
| 中继架构 | 无 | 有（Rust 中继服务器） |

**未确认项**：仓库中 `docs/rescope.md` 提到 "relay" 和 "mobile" 字样，但从 grep 结果看是中文翻译文件中的通用术语，非功能实现。标注为「未确认——可能是规划中的功能或文档迁移残留」。

证据：`packages/client/connection/README.md`（信任围栏）、`docs/subsystems/web-server.md`（host 限制）、`packages/client/ui-layout/src/client/columns.ts`（响应式断点）、`apps/web/public/manifest.webmanifest`（PWA manifest）、`apps/web/tests/remote-welcome.e2e.ts`（测试视口）、`python/README.md`（Python SDK）。

---

## 3. Nebflow 借鉴评估表

以下每项评估格式：Harness 做法 → Nebflow 现状 → 差距/可借鉴度 → 借鉴路线建议（含 Scala/Pekko 适配性）

### 3.1 事件溯源会话日志

| 维度 | 内容 |
|------|------|
| **Harness 做法** | Session = 追加写入类型化事件日志（12 种 SessionEvent），LLM 消息历史从日志 `deriveMessages()` 派生，不单独存储。"模型可见 = 已日志记录"运行时不变量。Fork/resume/重放全从日志派生。崩溃恢复用合成 `turn/end { interrupted }` 平衡日志。 |
| **Nebflow 现状** | Actor 模型 + cats-effect，会话状态在 Actor 内。有会话持久化但不确定是否事件溯源模式。消息历史可能单独存储。 |
| **差距/可借鉴度** | **中**。Nebflow 的 Actor 模型天然适合事件溯源（actor persistence 已有 event-sourced 模式），但当前可能未完整实现 derive-from-log 理念。 |
| **借鉴路线** | 用 Pekko Persistence 的 EventSourcedBehavior 实现追加写入事件日志。定义 `SessionEvent` 密封特质 + 12 种事件 case class。`deriveMessages()` = fold over events。Fork = 用事件前缀 seed 新 actor。崩溃恢复 = Pekko Persistence 内置。**适配性高**——Pekko Persistence 原生支持。 |

### 3.2 能力接缝三角色分离

| 维度 | 内容 |
|------|------|
| **Harness 做法** | 每个可插拔能力 = Service Definition（接口）+ Provider（实现）+ Consumer（调用方），通过 `ctx.*` 注册可替换。一次 Provider 切换改变整个产品（如 fs+subprocess 换到 E2B 远程沙箱，Bash/PTY/LSP 一起搬）。 |
| **Nebflow 现状** | 内置/外部脚本/MCP 工具体系，per-agent 白名单 default-deny。但工具执行世界（fs/subprocess）可能未抽象为可替换接缝。 |
| **差距/可借鉴度** | **中**。Nebflow 已有工具白名单，但"一次 Provider 切换搬动整个执行世界"的抽象层级更高。 |
| **借鉴路线** | 用 Scala trait 定义能力接缝（`trait FileSystemProvider`、`trait SubprocessProvider`），Actor 注册实现。NebLink 远程 Bash/文件传输可作为远程 Provider 实现。**适配性高**——trait + Actor 注册是 Scala 惯用模式。 |

### 3.3 进程级沙箱（多平台）

| 维度 | 内容 |
|------|------|
| **Harness 做法** | Linux bwrap/Landlock（C11 原生启动器）、macOS Seatbelt、Windows ACL 限制令牌。每调用携带策略（per-call policy），fail-closed（无后端 → 抛错，绝不静默放行）。`SandboxMode` = read-only / workspace-write / danger-full-access。 |
| **Nebflow 现状** | 无进程级沙箱。命令执行直接 spawn 进程。工具权限走 InteractionHub 询问，但无文件效果隔离。 |
| **差距/可借鉴度** | **高**。Nebflow 当前完全缺少这一层，是安全短板。 |
| **借鉴路线** | macOS 用 `sandbox-exec`（Seatbelt），Linux 用 bwrap。Scala 侧定义 `trait SandboxProvider` + `SandboxPolicy` case class，`confine(argv, policy): ConfinedArgv` 在 spawn 前包装 argv。**适配性中高**——需要平台特定外部二进制 + Scala 进程包装，但核心逻辑是 argv 前置，Scala ProcessBuilder 可做。Landlock C 启动器可复用 Harness 的 `native/landlock-run`。 |

### 3.4 闭集审批 + 权限预设

| 维度 | 内容 |
|------|------|
| **Harness 做法** | `ApprovalOutcome` 闭集（allowed-once/rejected/cancelled/unavailable），默认 fail-closed。每会话策略 ask/never。预设层将 sandbox mode + approval policy 打包成命名预设（workspace-write/danger-full-access）。`never` 在瀑布分发前强制。 |
| **Nebflow 现状** | 权限询问走 InteractionHub，per-agent 白名单 default-deny。但审批结果可能不是严格闭集，策略可能不支持 `never` 模式。 |
| **差距/可借鉴度** | **中高**。Nebflow 已有 default-deny 白名单和 InteractionHub 询问，但闭集结果 + fail-closed 默认 + 预设打包是更严谨的设计。 |
| **借鉴路线** | 用 Scala 密封特质定义 `ApprovalOutcome`（ADT），`InteractionHub` 返回 `ask()` 返回 `Future[ApprovalOutcome]`，无回答 = `Unavailable` = 拒绝。预设层 = sandbox mode + approval policy 的 case class 元组。**适配性高**——ADT 是 Scala 惯用模式。 |

### 3.5 凭证引用接缝（密钥热更新）

| 维度 | 内容 |
|------|------|
| **Harness 做法** | 配置只存引用（环境变量名），Provider 拥有值，每次操作重新解析（热更新无需重启）。`describe()` 永不暴露值。空值在各处视为不存在。 |
| **Nebflow 现状** | 密钥管理方式未明确。可能在配置文件中直接存储或环境变量一次性读取。 |
| **差距/可借鉴度** | **中**。热更新是亮点，但 Nebflow 作为本地优先工具，密钥轮换频率可能不高。 |
| **借鉴路线** | 用 Scala `CredentialRef`（opaque type）+ `CredentialProvider` trait。`resolve(ref): IO[Option[ResolvedCredential]]` 每次调用读取。**适配性高**——cats-effect IO 天然适合懒求值。 |

### 3.6 Turn/Step 两级执行模型

| 维度 | 内容 |
|------|------|
| **Harness 做法** | Turn = 零或多个 step；Step = 一次模型请求 + 工具调用。`agent/pre-step` 瀑布可重写/拒绝 claimed messages。工具并行（`isConcurrencySafe` 标记 + exclusive/parallel 调度）。 |
| **Nebflow 现状** | Actor 模型处理消息，但不确定是否有显式 Turn/Step 两级模型。工具并发调度策略未明确。 |
| **差距/可借鉴度** | **中**。Pekko Actor 天然处理消息流，但显式 Turn/Step 边界对持久化和恢复有价值。 |
| **借鉴路线** | 在 Actor 的 `receiveCommand` 中实现 step 循环：`turn/start` → claim → `step/start` → 模型请求 → 工具调用 → `step/end` → 可能下一个 step → `turn/end`。工具并行用 cats-effect `Par` + exclusive 屏障。**适配性高**——Actor + IO 组合天然适配。 |

### 3.7 生成式类型安全 RPC（Typert Gateway）

| 维度 | 内容 |
|------|------|
| **Harness 做法** | `@Remote` 装饰器声明方法 → 构建时生成严格调用描述符 + schema → Client 消费生成的声明。HTTP 映射 `POST /api/<ns>/<method>`。参数/返回值运行时验证。 |
| **Nebflow 现状** | HTTP 网关 :8080 + WebSocket，前端原生 JS。API 可能手动定义路由和序列化。 |
| **差距/可借鉴度** | **低**。Typert 深度依赖 TypeScript 编译器 API 和装饰器，Scala 没有等价物。且 Nebflow 前端是原生 JS，类型安全 RPC 的收益有限。 |
| **借鉴路线** | 不建议直接借鉴。可考虑用 tapir/endpoint 定义类型安全的 HTTP 端点（Scala 惯用），但不值得为此引入 Typert 式的代码生成管线。 |

### 3.8 WebSocket 双流下行

| 维度 | 内容 |
|------|------|
| **Harness 做法** | `events.mux` + `events.host` 两条 WS 下行流，客户端不发送应用数据。任一 socket 结束 → 连接 generation 失败并重建。普通 GET 返回 426 无 SSE 回退。 |
| **Nebflow 现状** | 已有 WebSocket。但不确定是否多流分离、generation 重建机制。 |
| **差距/可借鉴度** | **低**。Nebflow 已有 WS，单流 vs 双流的差异不大。 |
| **借鉴路线** | 可考虑将 mux（多路复用会话事件）和 host（系统级事件）分离为两条 WS，但收益不显著。保持现状即可。 |

### 3.9 Subagent 多 Provider 共存 + 可继续子 agent

| 维度 | 内容 |
|------|------|
| **Harness 做法** | 多个 subagent Provider 按名称注册共存（in-process/fork/acp/codex/claude-code/dsh-sdk）。可继续子 agent = 持久子 Session + 最多一个进程本地 Activation，Activation 可执行多 FIFO turn。 |
| **Nebflow 现状** | Agent/Team/Flow 三层实体。Agent 委托可能已有，但"可继续子 agent"（持久子会话 + Activation 驻留期）可能未实现。 |
| **差距/可借鉴度** | **中**。Nebflow 的 Team 已有同级协作，但"可继续子 agent"是委托模式的增强。 |
| **借鉴路线** | 子 agent = 子 Actor + 子 Session（事件日志前缀 seed）。Activation = 子 Actor 的驻留期，通过 Actor 生命周期管理。Provider 共存 = 不同 Actor 工厂注册。**适配性高**——Pekko Actor 层级天然适合。 |

### 3.10 多设备/手机端

| 维度 | 内容 |
|------|------|
| **Harness 做法** | 无专门方案。LAN 访问受限（`0.0.0.0` 标记不支持），PWA manifest 有但无移动端优化，无配对/中继/设备注册。 |
| **Nebflow 现状** | NebLink 有 Rust 中继 + JWT + Device Flow OAuth，支持远程 Bash/文件传输。但无扫码/链接配对，无手机端 WebUI。 |
| **差距/可借鉴度** | **低（Harness 方向）**。Harness 在此维度不如 Nebflow，无可借鉴。 |
| **借鉴路线** | 不从 Harness 借鉴。Nebflow 应继续发展 NebLink 方向——增加扫码配对（JWT 短期配对 token）+ 手机端 WebUI（响应式前端）。 |

---

## 4. 优先级建议

综合评估，以下 3 项最值得 Nebflow 优先借鉴，按优先级排序：

### 优先级 1：进程级沙箱（多平台文件效果隔离）

**理由**：
- **安全短板**：Nebflow 当前完全缺少进程级沙箱，命令执行直接 spawn 进程，这是最大的安全风险。Harness 的多平台沙箱（Linux bwrap/Landlock + macOS Seatbelt + Windows ACL）是成熟方案。
- **适配性高**：核心逻辑是 spawn 前包装 argv，Scala ProcessBuilder 可做。Landlock C 启动器可直接复用 Harness 的 `native/landlock-run`（MIT 许可）。
- **与现有架构互补**：Nebflow 已有 InteractionHub 权限询问（审批层），缺的是执行层隔离。加上沙箱后形成"审批 + 隔离"双层防护。
- **可分阶段实施**：先 macOS Seatbelt（`sandbox-exec` 随系统发布，零依赖）→ 再 Linux bwrap → 最后 Windows ACL。

**建议实施路线**：
1. 定义 `trait SandboxProvider` + `SandboxMode`（read-only / workspace-write / danger-full-access）+ `SandboxPolicy` case class
2. `confine(argv: Seq[String], policy: SandboxPolicy): ConfinedArgv` 在 spawn 前包装
3. macOS Seatbelt 后端（`sandbox-exec` profile 生成）
4. Linux bwrap 后端
5. 集成到 Bash 工具执行路径

### 优先级 2：事件溯源会话日志 + 崩溃恢复

**理由**：
- **架构一致性**：Nebflow 的 Pekko Actor 模型天然支持事件溯源（Pekko Persistence），但可能未完整实现 derive-from-log 理念。Harness 的"模型可见 = 已日志记录"不变量是严谨设计。
- **恢复能力**：崩溃恢复用合成 `turn/end { interrupted }` 平衡日志（不截断），这让长任务在崩溃后可恢复而非丢失。
- **Fork/Resume 统一**：一切从日志派生——fork = 事件前缀 seed，resume = 加载持久日志。统一了多个操作的数据路径。
- **适配性高**：Pekko Persistence 的 `EventSourcedBehavior` 原生支持追加写入事件日志 + snapshot + recovery。

**建议实施路线**：
1. 定义 `SessionEvent` 密封特质 + 事件 case class（turn/start、turn/end、step/start、step/end、user/message、assistant/chunk、assistant/message、tool/call、tool/result 等）
2. 用 `EventSourcedBehavior` 实现 Session Actor：`persist(event)` + `eventHandler` 更新状态
3. `deriveMessages()` = fold over events 投影 LLM 消息历史
4. 崩溃恢复：检测无 `turn/end` 的 `turn/start` → 追加合成 `turn/end { interrupted }`
5. Fork API：`ctx.sessions.fork(source, boundary)` = 用事件前缀 seed 新 actor

### 优先级 3：闭集审批 + 权限预设

**理由**：
- **严谨性提升**：Nebflow 已有 default-deny 白名单和 InteractionHub，但 Harness 的闭集结果（`allowed-once`/`rejected`/`cancelled`/`unavailable`）+ fail-closed 默认 + `never` 策略 + 预设打包是更系统化的设计。
- **实施成本低**：Scala ADT 是惯用模式，改动集中在 InteractionHub 返回类型和权限 UI。
- **与沙箱协同**：权限预设将 sandbox mode + approval policy 打包，用户一键切换（workspace-write+ask → danger-full-access+never），与优先级 1 的沙箱实现天然配合。

**建议实施路线**：
1. 定义 `sealed trait ApprovalOutcome`（AllowedOnce / Rejected / Cancelled / Unavailable）
2. `InteractionHub.ask()` 返回 `IO[ApprovalOutcome]`，无回答 = `Unavailable` = 拒绝
3. 定义 `ApprovalPolicy`（Ask / Never），`Never` 在分发前强制
4. 定义 `PermissionPreset`（sandbox mode + approval policy 元组），预设切换写两个旋钮
5. 前端 UI：权限选择器（workspace-write / danger-full-access / custom）

---

### 不建议借鉴的项

| 项 | 理由 |
|----|------|
| Typert 生成式 RPC | 深度依赖 TypeScript 编译器 API，Scala 无等价物；Nebflow 前端是原生 JS，收益有限 |
| Cordis 插件框架 | Nebflow 已有 Actor 模型 + cats-effect，引入 Cordis 式的插件上下文是架构重写，不现实 |
| WebSocket 双流分离 | Nebflow 已有 WS，单流 vs 双流差异不大 |
| 多设备/手机端 | Harness 在此维度不如 Nebflow（NebLink 已有中继+OAuth），应继续自研方向 |

---

> 报告完。如需深入某个具体项的实现细节（如沙箱 profile 语法、事件 schema 字段、Agent Teams 任务 DAG 等），可进一步追踪源码。
