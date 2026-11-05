# Nebflow 自身架构调研 — tech-architecture 场景 demo

### 结论

Nebflow 是单 JVM 自托管的 AI agent 编排平台：`nebflow start` 起一个网关进程，浏览器 Web UI 经 WS/REST 接入；核心由编排引擎（DAG 节点调度 + 台账）、自研 Actor 之上的 Agent 运行时、61 文件的工具层、双协议 LLM 适配与 JSON 文件族持久化组成；设备互联（neblink）与飞书 / 微信渠道挂在网关上。Scala 3.5.2 + cats-effect / FS2 / sttp，`sbt assembly` 出单自包含 JAR。本文以 2026-10-04 Mac 工作树只读侦查为准（`/Users/kaiyu/Claude code/Nebflow`）。

图预算：2 / 3（总架构 + 数据流）。

## 架构总览

![Nebflow 总架构](~/.nebflow/plots/20261004_nebflow_architecture.png)

进程形态只有两种：网关 JVM（长驻）与 CLI 短命令（start/stop/热重启后继）。所有业务能力——编排、agent、工具、渠道——都在网关 JVM 内；跨进程边界只有三处：CLI 拉起/聚焦网关、hotrestart 拉起后继进程、工具层写入被操作项目的 git worktree。

## 组件分解

| 模块 | 源码位置 | 职责 | 关键文件 |
|---|---|---|---|
| Web UI | `src/main/resources/web` | 浏览器工作台：聊天 / Flow Map 画布 / 文件管理器 / 各面板；原生 JS + esbuild 打包，vendor Monaco、PDF.js | `js/chat.js`、`js/canvas.js` 等模块群 |
| CLI | `scala/nebflow/cli`（29 文件） | start/stop、CliRouter 子命令、pid 文件、单实例守卫（pid + 端口双重）、热重启后继 `--succeed` | `Main.scala`、`SingleInstanceGuard`、`ProcessManager` |
| Gateway | `scala/nebflow/gateway`（49 文件） | HTTP+WS 服务：WsHub/WsDispatch 主通道、REST 路由群、静态资源（web / web-dist）、PatAuth、STT/TTS、健康检查 | `GatewayMain.scala`、`WsHub.scala`、`StaticRoutes.scala` |
| 编排引擎 | `scala/nebflow/core/project`（40 文件） | 项目级调度：ProjectActor 拆 DAG、NodeEngine 依赖门控 / 循环预算 / 取消、FlowMap 存储与事件日志、ChainLedger 台账、TaskBoard 看板、崩溃恢复 | `ProjectActor.scala`、`NodeEngine.scala`、`FlowMapStore.scala`、`ChainLedgerStore.scala` |
| 节点邮箱 | `scala/nebflow/core/flow`（13 文件） | 节点间消息：FlowTreeActor 路由、邮箱队列持久化、空闲门、投递去重 | `FlowTreeActor.scala`、`MailQueueStore.scala`、`MailDeliveryDedup.scala` |
| 节点执行 | `scala/nebflow/core/node` | 节点执行入口，经 AgentRuntimePort 端口进入 Agent 运行时 | `NodeRunner.scala` |
| Actor 系统 | `scala/nebflow/actor`（12 文件） | 自研 Actor：ActorSystem / Behavior / 信封 / 状态 | `ActorSystem.scala`、`Behavior.scala` |
| Agent 运行时 | `scala/nebflow/agent`（32 文件） | AgentActor 状态机（Idle/Processing/Frozen）、会话执行、子代理 SubAgentTaskStore、InteractionHub、流式管线 | `AgentActor.scala`、`AgentSession.scala`、`InteractionHub.scala` |
| 上下文压缩 | `scala/nebflow/core/compact`（12 文件） | 压缩队列、压缩恢复 | `CompactService.scala` |
| 工具层 | `scala/nebflow/core/tools`（61 文件） | Bash/Read/Edit/Glob/Grep/Mail/SubTask/NodeEdit/Workflow/ProjectCreate 等工具；worktree 约束落在工具实现内 | `BashTool.scala`、`NodeEditTool.scala`、`SubTaskTool.scala` |
| 插件与技能 | `core/plugin`（7）+ `core/skill`（2） | 插件 / 技能装载；seed 预装 12 插件（`resources/seed/plugins`） | plugin 包 + seed 目录 |
| MCP 客户端 | `scala/nebflow/core/mcp`（5 文件） | 外部工具接入：McpClient/McpManager、JSON-RPC、transports | `McpClient.scala`、`JsonRpc.scala` |
| 控制 | `core/hooks` · `core/ask` · `core/sandbox` · `core/secrets` | 前后置钩子、AskUser 通道（经网关 `WsSkillsAsksHandlers` 弹卡）、文件沙箱策略、秘钥 | `FileSandbox.scala`、`SandboxPolicy.scala` |
| LLM 接入 | `scala/nebflow/llm`（21 文件） | OpenAI 兼容 + Anthropic 兼容两适配器；健康监测与自动降级链 | `providers/OpenAiAdapter.scala`、`providers/AnthropicAdapter.scala`、`decision/` |
| 设备互联 | `scala/nebflow/neblink`（33 文件） | 多设备组网：Logto 设备流登录、relay 隧道、跨设备文件传输、presence、好友名册 | `NeblinkService.scala`、`RelayTunnelDiagnostics.scala`、`LogtoDeviceFlow.scala` |
| 消息渠道 | `scala/nebflow/social`（22）+ `bridge`（2） | 飞书渠道全套（inbound/outbound/扫码绑定）+ 微信 iLink 桥（OpenClaw 官方插件缝）；BridgeManager 管理桥插件生命周期 | `FeishuChannel.scala`、`WeixinIlinkBridgePlugin.scala`、`BridgeManager.scala` |
| 文件投递 | `scala/nebflow/dropbox`（7 文件） | 跨设备文件投递，经 `core.NeblinkClientPort` | dropbox 包 |
| 存储 | `NEBFLOW_HOME` 数据根 | JSON 文件族：flow-map.json、chain-ledger.json(+journal)、task-board、邮箱队列、会话、配置、秘钥；原子写（tmp + ATOMIC_MOVE）+ checkpoint + 归档 | `ChainLedgerStore.scala` 头注（§落盘与载入） |
| 运维 | `core/scheduler` · `core/daemon` · `core/hotupdate` · `core/hotrestart` · `service/` | cron 定时任务（投递 AgentCommand）、守护面板、热更新 / 热重启、配置与备份服务 | `DaemonService.scala`、`hotrestart/SuccessorContext` |

## 数据流

![一次多节点任务链的数据流](~/.nebflow/plots/20261004_nebflow_dataflow.png)

① 用户在 Web UI 输入目标 → ② Gateway WS 会话收 turn（`WsSessionChatHandlers`）→ ③ ProjectActor 拆解为节点 DAG 并落 `flow-map.json` + 看板 → ④ NodeEngine 依赖门控，入边齐备的节点放行 → ⑤ NodeRunner 经 AgentRuntimePort 启动节点 agent（worktree 上下文）→ ⑥ Agent 工具循环：LLM 流式调用 ↔ 工具执行（Bash/Read/Edit…）→ ⑦ 需要人决策时经 AskUser 通道弹卡（`WsSkillsAsksHandlers`），答复后继续 → ⑧ 节点完成，NodeDelivery 沿 out 边投递结果 → ⑨ 邮箱去重 / 空闲门放行，唤醒下游节点回到 ④ → ⑩ CompletionGate 收链、合并互斥策略收口，ChainLedger / 看板更新，全程事件经 WS 推送 Flow Map 画布实时呈现。

## 关键设计取舍

| 取舍点 | 选了什么 | 代价 / 边界 |
|---|---|---|
| 进程形态 | 单 JVM 单体，单自包含 JAR（sbt assembly），除所配 LLM 外无外部服务依赖 | 能力扩展都在进程内；无横向扩缩，单机边界 |
| 存储 | JSON 文件族 + 原子写 + journal 增量 + checkpoint + 冷档归档，零数据库依赖 | 大规模历史查询走归档文件而非索引；一致性靠写序纪律（ChainLedgerStore 头注） |
| 并发模型 | 自研 ActorSystem（12 文件）+ cats-effect/FS2 | 轻量可控；分布式、监督升级树等 Actor 生态能力自担 |
| 节点隔离 | git worktree（工具层强制读写边界）+ FileSandbox 策略 | 比 容器轻；隔离强度依赖策略配置而非内核边界 |
| LLM 接入 | 双适配器（OpenAI 兼容 / Anthropic 兼容）+ 健康监测自动降级链 | 协议差异消化在适配器内；新协议形态需扩适配器 |
| 升级 | 热重启：编排器拉起后继进程，`--succeed <intent>` 接力（intent 走静态 holder 不走 argv；老 pid 确认死亡后才写新 pid） | 会话不断；pid / 端口 / 单实例门禁需专门处理（`Main.scala` 热重启批设计注释） |
| 中断 | GracefulInterruptHook 单钩子：draining → 节点 CAS interrupted → 最后才撤在途 LLM 流（防 token 燃烧，顺序即正确性） | 关机路径多一步编排；可用 Defaults 开关回退旧行为 |

## 附录

- 侦查记录：源码树 `/Users/kaiyu/Claude code/Nebflow`（Mac 设备 Mashiros-MacBook-Pro，只读 Read/Glob/Grep/Bash，2026-10-04 21:00–21:10 +08:00）；包规模实测：core 236 / gateway 49 / agent 32 / neblink 33 / cli 29 / tools 61（.scala 文件数）。
- 渲染记录：graphviz dot 16.1.0（本机 `C:\Users\Kai\.nebflow\tools\graphviz\bin\dot.exe -V` 实测），`dot -Tpng` 真实渲染；dot 源 `C:\Users\Kai\.nebflow\projects\general\.nebflow\tmp\nebflow_arch.dot`、`nebflow_dataflow.dot`；输出 `~/.nebflow/plots/20261004_nebflow_architecture.png`（3372px 宽）、`20261004_nebflow_dataflow.png`（3640px 宽）。PNG 为浅色实底（core §3：PNG 无主题适配，浅底深字按亮色底校验对比度）。
- 工具自检对照：mmdc 本机与 Mac 侧均未装（`mmdc --version` 实测 command not found）；npm registry 端点验活 `@mermaid-js/mermaid-cli` → 12.0.0；Mac 侧 `dot -V` → 14.1.5。
- 遗留：桌面语音悬浮球的进程形态未入图（`packaging/` 仅见 dmg/msi/linux 安装脚本，orb 源码位置未查证）；seed 12 插件逐个职责未展开（本 demo 聚焦平台分层）；前端 js 模块群只列代表文件未逐个归类。
