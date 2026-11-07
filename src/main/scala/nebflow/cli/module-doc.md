# cli

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

命令行入口与子命令：CLI 路由、进程管理、单实例守卫。

**入口建议**：先读 [`DetachedApply.scala`](DetachedApply.scala)（本模块最大文件）。

## 文件（38）

| 文件 | 说明 |
|---|---|
| [`AgentCommand.scala`](AgentCommand.scala) | 定义: AgentCommand |
| [`AutoStartCommand.scala`](AutoStartCommand.scala) | 定义: AutoStartCommand |
| [`BindCommand.scala`](BindCommand.scala) | D6 binding-transfer guard, host face (phase 3c plan §8 component ①): - `stand bind confirm` — run on the OLD h |
| [`ChatCommands.scala`](ChatCommands.scala) | A16: resolve the session for a one-shot send. The first frame used to be a private shape |
| [`CliModel.scala`](CliModel.scala) | Command name, e.g. "session" or "model" |
| [`CliRouter.scala`](CliRouter.scala) | Main CLI router. Parses arguments, discovers the command, and dispatches. Usage: |
| [`CommandRegistry.scala`](CommandRegistry.scala) | Auto-discovery registry for all CLI commands. Add new CliCommand implementations here. |
| [`ConfigCommand.scala`](ConfigCommand.scala) | dot 路径 → 嵌套 JSON。末段路径是键名（与 ConfigGet 的全段下钻对称）。 qa #339 打回：旧基例返回裸值、丢末段键——`set a.b.c true` 实发 |
| [`ConfigInspectCommand.scala`](ConfigInspectCommand.scala) | `stand config path` + `stand config validate` —— **只读**两条子命令。 归属说明：两条都挂 `config` 名下（`stand config path\|valida |
| [`DaemonSpawn.scala`](DaemonSpawn.scala) | Daemon launcher (phase 2 daemon plan §1): the `stand start` parent process spawns the gateway as a detached ch |
| [`DetachedApply.scala`](DetachedApply.scala) | 跨平台 **detached apply**（台账 #916：自重启/自更新地基，2026-10-26 作者令）。 今天事故为镜（Mac 12:06）：dispatcher 手搓 double-fork+setsid 分 |
| [`FolderCommand.scala`](FolderCommand.scala) | 定义: FolderCommand |
| [`GatewayClient.scala`](GatewayClient.scala) | HTTP client for communicating with the running Gateway. Reads the auth token from ~/.nebflow/auth.json. |
| [`HealthCommand.scala`](HealthCommand.scala) | `stand health` — 健康读数形态（三面：自身 / 网关 / 依赖）。 只读：只探端口、只发 GET、只读本机文件与进程表；不起停实例、不改任何状态。 |
| [`LoginCommand.scala`](LoginCommand.scala) | headless 登录三命令 —— `login`（统一登录·路径② 无头密码登录）/ `logout` / `whoami`（台账 #924, 2026-10-26 统一登录批）。 |
| [`LogsCommand.scala`](LogsCommand.scala) | `stand logs` — 日志取数面（定位 / 尾读 / 筛选），**纯读**。 禁改既有日志写面：本命令不写、不截断、不轮转、不删任何日志文件，只 `os.list` |
| [`McpCommand.scala`](McpCommand.scala) | T11+A2: these four commands made no wire call at all yet reported success ("start requested" / "removed (edit  |
| [`MemoryCommand.scala`](MemoryCommand.scala) | 定义: MemoryCommand |
| [`ModelCommand.scala`](ModelCommand.scala) | 定义: ModelCommand, ThinkingCommand |
| [`PidMeta.scala`](PidMeta.scala) | `<dataRoot>/.pid.meta` — the ownership sidecar of `<dataRoot>/.pid` (phase 2 daemon plan §2.2). |
| [`PluginCommand.scala`](PluginCommand.scala) | `stand plugin list\|add\|approve\|revoke\|unblock\|enable\|disable`（阶段 2b §B.3 CLI 对等，H-14② 裁定：面板为主 + CLI 供脚本化 |
| [`ProcessManager.scala`](ProcessManager.scala) | Remove the pid file AND its ownership sidecar — the single cleanup point covering stop, shutdown hooks, doctor |
| [`ProviderCommand.scala`](ProviderCommand.scala) | `provider add <name> --base-url <url> --protocol <p> [--model <id>] [--api-key-stdin \| --api-key-file <path>  |
| [`RestartCommand.scala`](RestartCommand.scala) | `stand restart` (phase 2 daemon plan §3.4; 台账 #916 2026-10-26 detached apply 重地基) = stop + start as ONE comman |
| [`RunCommand.scala`](RunCommand.scala) | One-shot headless task execution (P0 benchmark — /tmp/headless-design.md D3). This is the Harbor installed-age |
| [`SecretsCommand.scala`](SecretsCommand.scala) | 密钥 CLI（密钥能力三件套批 A 件，作者令 2026-11-01：`stand secrets list` = 键名 + 用途 + 写入时间的元数据清单，**任何形态都不显示值**；bare |
| [`SessionCommand.scala`](SessionCommand.scala) | 定义: SessionCommand |
| [`SessionSwitchCommand.scala`](SessionSwitchCommand.scala) | `stand session switch <id>` —— 切换网关的**活动会话**。 按既有会话模型：发既有 WS 词汇表的 `switchSession` 帧（WebSocketRoutes.scala:2015 |
| [`SingleInstanceGuard.scala`](SingleInstanceGuard.scala) | Single-instance guard (2026-08-27, hard requirement after a real double-launch incident): before the gateway b |
| [`SkillCommand.scala`](SkillCommand.scala) | Local file scan — no gateway needed. Report only; verdicts belong to humans. |
| [`StopSupport.scala`](StopSupport.scala) | Process/port reverse lookup shared by the hardened stop face (phase 2 daemon plan §3.2, F-3). |
| [`SystemCommands.scala`](SystemCommands.scala) | Phase1 [[UpdateStage.stage]]（校验+staging，引擎/指针零触碰）→ 全就绪才 Phase2：pointer 布局 = [[nebflow.core.hotrestart.InstallP |
| [`TokenCommand.scala`](TokenCommand.scala) | Phase 3b console-credential commands (plan §6.4): - `stand token` / `stand token print` — break-glass: mint a  |
| [`UpdateStage.scala`](UpdateStage.scala) | two-phase update — Phase1：download-verify-stage**（台账 #916，2026-10-26 作者令）。把「取包 → 校验 → 落 staging」与「换装+重启（Phase2 |
| [`markdownRenderer.scala`](markdownRenderer.scala) | 定义: MarkdownRenderer |
| [`splash.scala`](splash.scala) | Terminal brand mark: the STAND "S" symbol (stand-symbol.svg rendered as ANSI-shadow block art — no font file d |
| [`ui.scala`](ui.scala) | 定义: NebflowUI |
| [`uiStore.scala`](uiStore.scala) | 定义: Phase, HistoryEntry, CompletedRound, UiState |

## 依赖的兄弟模块

[`shared/`](../shared/module-doc.md)、[`core/`](../core/module-doc.md)、[`gateway/`](../gateway/module-doc.md)、[`actor/`](../actor/module-doc.md)、[`neblink/core/`](../neblink/core/module-doc.md)、[`neblink/identity/`](../neblink/identity/module-doc.md)、[`core/skill/`](../core/skill/module-doc.md)、[`service/`](../service/module-doc.md)、[`core/hotrestart/`](../core/hotrestart/module-doc.md)
