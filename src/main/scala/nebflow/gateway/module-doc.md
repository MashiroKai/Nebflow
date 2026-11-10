# gateway

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

HTTP/WS 网关：路由装配、鉴权、会话与连接守卫。

**入口建议**：先读 [`WebSocketRoutes.scala`](WebSocketRoutes.scala)（本模块最大文件）。

## 文件（86）

| 文件 | 说明 |
|---|---|
| [`ApiJson.scala`](ApiJson.scala) | REST/WS 响应信封构造助手(Phase 3 去重,行为保持重构,2026-09-25):只收敛三类 经 grep 实证**完全同形**的手拼 Json.obj——REST 裸 `{ok:true}`(8 处)、RE |
| [`AskUserAttachmentRoutes.scala`](AskUserAttachmentRoutes.scala) | 单件上限（与工具出向面同值常量——设计稿 §3.6「上限尺共享」）。 |
| [`AskUserCancelRoutes.scala`](AskUserCancelRoutes.scala) | 一次取消的出口读数。`state`：cancelled / unknown / not-ask-user / no-hub。 |
| [`AssetStampRoutes.scala`](AssetStampRoutes.scala) | GET /api/nf-asset-stamp — 前端热更新地基的指纹单点（台账 #919，2026-10-27）。 `stamp` = 引擎**实际伺服**的 index.html **原始字节**（brand 注入 |
| [`AssetVersions.scala`](AssetVersions.scala) | 版本指纹 = SHA-256 前 16 hex（碰撞概率对静态资源集可忽略；URL 短）。 |
| [`AuthAdminRoutes.scala`](AuthAdminRoutes.scala) | The AuthAdmin face (phase 3b): break-glass console-ticket minting, gateway token rotation, and console session |
| [`AuthRoutes.scala`](AuthRoutes.scala) | 登录回调域(auth):原 RestApiRoutes 的 PKCE 单飞槽(pkceLogin)、切号单窗 切换标记(switchHandoff)与 /auth 挂载面(GET /callback、GET /logge |
| [`BindConfirmStore.scala`](BindConfirmStore.scala) | Host-side pending state for the D6 binding-transfer confirmation code (phase 3c plan §8 component ①). |
| [`BridgeAnswerReflection.scala`](BridgeAnswerReflection.scala) | askUser 形判据单点：载荷携带 `answers` 数组（桥腿三条作答路径的统一形态）。 |
| [`ChatRoutes.scala`](ChatRoutes.scala) | Phase 3b: the LIVE file token (Ref, not a boot snapshot) — a rotation swaps it and the old Bearer dies on this |
| [`ChatSearchRoutes.scala`](ChatSearchRoutes.scala) | ChatSearchRoutes —— 聊天检索查询 API（datalayer-spec 批 2026-10-30，C2）。 `GET /api/search/chat` —— 与旧全扫路径**并行存在**（设计稿 § |
| [`ConfigRoutes.scala`](ConfigRoutes.scala) | 配置域(config,F 步 2026-09-24):原 RestApiRoutes.routes 巨型 match 的 /config(GET+PATCH)、/safety 全族、/canvas-tabs 全族 cas |
| [`ConsoleAssetBatchRoutes.scala`](ConsoleAssetBatchRoutes.scala) | 静态资产批量打包面（console-fastpath 批 2026-10-23，作者令腿②「请求打包」）： `POST /api/console-assets` 一次请求打包至多 [[MaxPathsPerRequest |
| [`ConsoleTakeover.scala`](ConsoleTakeover.scala) | 控制台单实例 · 接管注册表（2026-10-27 批，设计稿 `reports/20261027_231456_console-single-instance/DESIGN.md`）。 |
| [`ConsoleTakeoverRoutes.scala`](ConsoleTakeoverRoutes.scala) | 控制台单实例 · 【重新接管】HTTP 腿（接管必达批 2026-11-01 作者令①）。 `POST /api/takeover/retake` —— 被顶出页面点【重新接管】的**必达**入口： |
| [`ConsoleTicketRoutes.scala`](ConsoleTicketRoutes.scala) | `GET /?ticket=<one-time console ticket>` — the redemption face that trades a short-lived portal/break-glass ti |
| [`ExplorerWatchSession.scala`](ExplorerWatchSession.scala) | Per-connection Explorer watch subscriptions (explorer-rt · design card chain-n-1981ce87, case C hybrid — push  |
| [`FsOps.scala`](FsOps.scala) | 伴生对象侧 logger（#159/#176 wtsurv 批，2026-09-14）：`deletePathsSafely` 是 纯核**（companion 成员，spec 直接静态调用），其**成功分支的审计留痕* |
| [`GatewayAuth.scala`](GatewayAuth.scala) | Which rail authenticated the request (priority order = declaration order below). |
| [`GatewayMain.scala`](GatewayMain.scala) | Kill stale Stand processes before starting — but ONLY verified Stand processes (P0 2026-09-06 host-kill ha |
| [`GatewaySessionStore.scala`](GatewaySessionStore.scala) | One console session record: the value of the HttpOnly session cookie the `?ticket=` redemption face mints (por |
| [`GzipMiddleware.scala`](GzipMiddleware.scala) | P0 gzip middleware for the static-resource tree (esbuild-design.md 决策 3). Wraps the routes mounted at "/" so t |
| [`HealthRoutes.scala`](HealthRoutes.scala) | 健康域(health,F 步 2026-09-24):原 RestApiRoutes.routes 巨型 match 的 /health、/health/conn、/usage/aggregate、/tts、/comma |
| [`HistoryPageFingerprint.scala`](HistoryPageFingerprint.scala) | WS `historyPage` 页指纹（M2 · 设计 v2 §3.1 ①「本地权威先行」的**数据基础刀**）。 帧附 `pageFingerprint: {total, offset, last, lastAt,  |
| [`HttpCache.scala`](HttpCache.scala) | HTTP 缓存语义小刀（M2 · 设计 v2 §3.1 清单「①取数语义补齐」的单一实现面）。 六端点（`/api/usage/aggregate`、`/api/projects/<n>/flow-map[/archiv |
| [`ImageGenRoutes.scala`](ImageGenRoutes.scala) | 生图 REST 面（imagegen 双层批 2026-10-24；功能开放批 2026-10-30 起随 seal 缺省编入常态在场）——腿②（delegate 等下层 |
| [`InputHistory.scala`](InputHistory.scala) | Builder for `~/.nebflow/input_history.jsonl` entries. Extracted pure so the field contract is testable: text/t |
| [`JevProbe.scala`](JevProbe.scala) | 有界诊断超时（ProviderModelProbe.TimeoutMs 同档）。 |
| [`LanAddresses.scala`](LanAddresses.scala) | 本机 LAN 地址枚举（console-fastpath 批 2026-10-23，作者令三腿之「设备识别 快速通道」）——给近路探测端点 `/api/neblink/nearpath` 提供「已知网关地址」 |
| [`LocalConsoleUrl.scala`](LocalConsoleUrl.scala) | Establish a browser session using the existing local credential, without placing that long-lived credential in |
| [`LoopbackLeg.scala`](LoopbackLeg.scala) | 「环回腿」判据（本地加速 M1 引擎侧批 ①/② 的共用传输事实）。 一个请求是否真的来自**本机**，唯一机械可信的信号 = 服务器接受的 socket |
| [`NeblinkRoutes.scala`](NeblinkRoutes.scala) | 设备互联域(neblink,E 步 2026-09-24):原 RestApiRoutes.routes 巨型 match 尾段 的 /neblink 全族(discover/announce/scan/status/a |
| [`NfFilePolicy.scala`](NfFilePolicy.scala) | The `/api/nf-file` credential-namespace policy engine (C1/R1/R2 batch, design plan §3.3) — extension whitelist |
| [`NfFileRoutes.scala`](NfFileRoutes.scala) | 判据拒的日志可见性（Pop 图片 403 批 2026-10-24）：修前 `/api/nf-file` 的 403 全族（verdict 判据拒 + 票拒）**零日志**——前端只回「Failed to render: |
| [`NfOfficeRoutes.scala`](NfOfficeRoutes.scala) | 支持的源扩展名（小写无点）。ppt 族主目标；doc/xls 族尽力而为（作者令「顺带」）。 |
| [`NfServeGate.scala`](NfServeGate.scala) | #750（2026-10-23，nf-file 媒体面高载 503 硬失败批）：`/api/nf-file` 文件腿的 过载准入闸——在飞服务预算。 |
| [`NfTicketStore.scala`](NfTicketStore.scala) | A short-lived, path-bound read credential for `GET /api/nf-file`. `/api/nf-file` used to be gated by the globa |
| [`OpenAiChatShim.scala`](OpenAiChatShim.scala) | OpenAI wire-format shim for the EXISTING inbound chat face (batch B2, "立项（最小形态）"; author ruling 2026-09-24). |
| [`OwnerIdentity.scala`](OwnerIdentity.scala) | owner 等值判据的引擎侧锚（门户身份透传批，方案 A2/A4，2026-10-28）。 「这个已认证会话是不是 owner 本人在用」的判据 = `session.accountSub == 引擎 |
| [`PatAuth.scala`](PatAuth.scala) | 网关接受面 · **轨 2** —— Logto 签发的账号级长期令牌（PAT）的**离线验签**。 信任模型（root 2026-09-20 定案，`decision-10-pat-token.md:14` 的 R3） |
| [`PerceptionDaemonDock.scala`](PerceptionDaemonDock.scala) | 统一 daemon 管理进程的引擎侧 dock 腿（统一 daemon 批 2026-10-29；作者裁定 「独立daemon吧 正好和我们心跳进程合并 那就是管daemon的」）。 |
| [`PerceptionIngestRoutes.scala`](PerceptionIngestRoutes.scala) | 引擎侧 ingest 端点（统一 daemon 批 2026-10-29）——统一 daemon 管理进程的 三条出域腿的承接面（`X-Nf-Perception-Token` 门，非会话门：daemon 无会话； |
| [`PerceptionRoutes.scala`](PerceptionRoutes.scala) | 感知域 REST 面（统一 daemon 批 2026-10-29 起 = **代理腿**）。 面板 API 的权威实现自本批起整体迁入统一 daemon 管理进程 |
| [`PortalUrl.scala`](PortalUrl.scala) | Browser entry resolution. The website portal is independent of the relay server. Explicit portal overrides and |
| [`PresenceRoutes.scala`](PresenceRoutes.scala) | 在线状态域(presence):原 RestApiRoutes.presenceWsRoutes 的全部 case 逐字迁入 (presence WS 受理臂 + teams/agents/plugins/social/ |
| [`PresenceSessionRegistry.scala`](PresenceSessionRegistry.scala) | presence 接受面 per-device 单会话注册表（console-reconnect-stability 批 · 2026-10-28，台账 #950/#960 同族收尾）。 |
| [`ProjectsRoutes.scala`](ProjectsRoutes.scala) | 项目域(projects,F 步 2026-09-24):原 RestApiRoutes.routes 巨型 match 的 /projects 全族 case(列表/归档/flow-map 快照/归档批次/节点结果全文 |
| [`ProviderModelProbe.scala`](ProviderModelProbe.scala) | 每模型连接性测试（作者令 2026-10-28 21:38 B 件：「在 provider 每一个模型旁边， 加一个小按钮，用于连接性测试，测试模型是否可用」）——设置页模型行尾测试钮的 |
| [`ProviderProbe.scala`](ProviderProbe.scala) | One model-list probe outcome. `NoEndpoint` is the only verdict that lets the caller move on to the next declar |
| [`ReaderAnnoStore.scala`](ReaderAnnoStore.scala) | Pure core of the reader-annotation store WS op (unit-tested without WS). |
| [`RefResolver.scala`](RefResolver.scala) | #303 D1/D5 — resolve a unified Reference (spec 20260825_global-reference-spec v1.1 §2.3 ③) into a backend inje |
| [`RegistryRoutes.scala`](RegistryRoutes.scala) | 注册表面板域(F 步 2026-09-24):原 RestApiRoutes.routes 巨型 match 的 /models 全族、/agents(全局面板)、/folders、/mcp、/memory case 逐 |
| [`RestApiCtx.scala`](RestApiCtx.scala) | REST 域分发上下文(B 步起,仿 WsDispatchCtx 先例,行为保持重构 2026-09-24): val 成员为 RestApiRoutes 构造参数与 logger 的引用。 |
| [`RestApiRoutes.scala`](RestApiRoutes.scala) | REST API routes for CLI consumption. These endpoints mirror the WebSocket message handlers but over HTTP. |
| [`SecretsRoutes.scala`](SecretsRoutes.scala) | 密钥域(secrets,secrets-panel backend leg 2026-09-27):统一密钥面板的 4 条 REST 端点(`/secrets` 列表 / POST 写 / PUT 覆盖 / DELETE |
| [`SessionRecorder.scala`](SessionRecorder.scala) | Records streaming events as UiMessages for session history, and forwards all events to an underlying wsSend (t |
| [`SessionRoutes.scala`](SessionRoutes.scala) | 会话域(sessions,F 步 2026-09-24):原 RestApiRoutes.routes 巨型 match 的 /sessions 全族 case(同步 turn / 列表 / 历史 / 建 / 删)逐字迁 |
| [`SocialRoutes.scala`](SocialRoutes.scala) | 社交域(social,D 步 2026-09-24):原 RestApiRoutes.routes 巨型 match 的 /friends、/conversations、/attachments、/users、/grou |
| [`StaleProcessGuard.scala`](StaleProcessGuard.scala) | P0 2026-09-06 (09:07 host-kill incident) — identity check BEFORE any kill. Incident: a node script exec'd `jav |
| [`StaticRoutes.scala`](StaticRoutes.scala) | W1 (mobile-quickwins 2026-10-20): which static tree the gateway feeds. Single switch point for the static tree |
| [`SttService.scala`](SttService.scala) | STT 配置文件路径。def（非 val）——PathUtil.dataRoot 可被测试 setDataRoot 重定向，val 会在类加载时冻结（#295）。 |
| [`TextStream.scala`](TextStream.scala) | Pure function layer of the text-stream read-only protocol (design card `.nebflow/reports/20260920_212955_texts |
| [`TextStreamSearch.scala`](TextStreamSearch.scala) | The text-stream engine behind the WS `textWindow` / `textIndex` / `textSearch` / `textCancel` legs (design car |
| [`TtsService.scala`](TtsService.scala) | TTS 语音合成服务。根据文本语言自动选择音色。 |
| [`TurnEndpoint.scala`](TurnEndpoint.scala) | Headless synchronous turn executor (P0 benchmark — /tmp/headless-design.md D1/D2). Standalone object (zero cla |
| [`WebSocketRoutes.scala`](WebSocketRoutes.scala) | Phase 3b: the LIVE file token (Ref, not a boot snapshot) — a rotation swaps it and the old value dies on this  |
| [`WikiRoutes.scala`](WikiRoutes.scala) | `GET /api/wiki/graph`                — merged document graph over every project's `<workspace>/docs` ROOT vaul |
| [`WindowsProcs.scala`](WindowsProcs.scala) | Windows process-info legs for the identity/stop faces (新设备首用批 2026-10-22). WHY this exists — the Windows comma |
| [`WorkspaceDirPicker.scala`](WorkspaceDirPicker.scala) | 工作区目录原生选择器（2026-09-05 作者裁定，workspace-picker 批次）。 前端 ProjectCreate「选择工作区」卡片点击 → WS `pickWorkspaceDir` → 本组件在 |
| [`WsAskHandlers.scala`](WsAskHandlers.scala) | .ui.json 转录 → dock 气泡面（injected 族 = sys；回问行带前缀照常入 sys）。 |
| [`WsConfigHandlers.scala`](WsConfigHandlers.scala) | 配置域(config setters):思考/冻结/STT/TTL/模型档/安全档/MCP 等配置读写臂。 |
| [`WsDispatch.scala`](WsDispatch.scala) | F 步消息分派注册表(行为保持重构,2026-09-24):键 = handleMessage 原 msgType match 的 case 字符串(一字不差),查不到时的回退 = 原 `case _` 腿(留在 Web |
| [`WsDropboxHandlers.scala`](WsDropboxHandlers.scala) | Dropbox 域(dropbox):跨设备消息与文件传输臂。 |
| [`WsFileOpsHandlers.scala`](WsFileOpsHandlers.scala) | 文件操作域(文件操作):文件树、watch、读写删移、文本流(textWindow/Index/Search)。 |
| [`WsHub.scala`](WsHub.scala) | WebSocket multicast hub — decouples root agents from individual connections. All WebSocket connections registe |
| [`WsMemoryFoldersHandlers.scala`](WsMemoryFoldersHandlers.scala) | 记忆与文件夹域(memory/folders):memory 读写、文件夹管理、文件夹规则。 |
| [`WsSessionChatHandlers.scala`](WsSessionChatHandlers.scala) | 会话与聊天域(sessions/chat):会话 CRUD、输入/命令、智能体回合控制与历史。 |
| [`WsSkillsAsksHandlers.scala`](WsSkillsAsksHandlers.scala) | 技能与提问域(skills/asks):ask 卡片执行、技能激活、pending-AskUser 快照。 |
| [`WsSystemHandlers.scala`](WsSystemHandlers.scala) | 系统域(system):ping/转写/热重启/更新/自启/后台任务/智能体提示词等。 |
| [`WsTasksWorkspaceHandlers.scala`](WsTasksWorkspaceHandlers.scala) | 任务与工作区域(tasks/workspace):定时任务、任务清单、工作区知识、目录选择链。 |
| [`auth.scala`](auth.scala) | Rotate the gateway token (phase 3b, criterion 7): generate a fresh value, atomically persist it to `auth.json` |
| [`codecs.scala`](codecs.scala) | 定义: GatewayCodecs |
| [`connguard.scala`](connguard.scala) | Per-IP connection-face guard (修复批 R-1b · 2026-09-22 conn-guard 批). 背景（91min 失联法证 + R-1a 热修注释）：单一受信对端的 presence |
| [`consoleponglane.scala`](consoleponglane.scala) | Console 心跳 pong 优先道（console-reconnect-stability 批 · 2026-10-28）。 病灶：客户端 30s 应用层 ping 的 pong 应答此前与**本连接全部数据帧共用一 |
| [`gatewayConfig.scala`](gatewayConfig.scala) | CLI --port override, takes priority over env var and default. |
| [`zombieguard.scala`](zombieguard.scala) | WS 面僵尸会话回收装配单点（conn-guard 家族；台账 #950 旁支 2026-10-26 presence 面落地，#960 平移 /api/ws console 面共用本核）。 |

## 依赖的兄弟模块

[`shared/`](../shared/module-doc.md)、[`neblink/core/`](../neblink/core/module-doc.md)、[`core/`](../core/module-doc.md)、[`actor/`](../actor/module-doc.md)、[`agent/`](../agent/module-doc.md)、[`core/tools/`](../core/tools/module-doc.md)、[`neblink/portal/`](../neblink/portal/module-doc.md)、[`service/`](../service/module-doc.md)、[`core/project/`](../core/project/module-doc.md)、[`core/schedule/`](../core/schedule/module-doc.md)、[`core/skill/`](../core/skill/module-doc.md)、[`llm/`](../llm/module-doc.md)、[`core/entity/`](../core/entity/module-doc.md)、[`core/mcp/`](../core/mcp/module-doc.md)、[`core/flow/`](../core/flow/module-doc.md)、[`neblink/identity/`](../neblink/identity/module-doc.md)、[`neblink/logto/`](../neblink/logto/module-doc.md)、[`neblink/relay/`](../neblink/relay/module-doc.md)、[`neblink/social/`](../neblink/social/module-doc.md)、[`neblink/sync/`](../neblink/sync/module-doc.md)、[`core/daemon/`](../core/daemon/module-doc.md)、[`llm/imagegen/`](../llm/imagegen/module-doc.md)、[`bridge/`](../bridge/module-doc.md)、[`core/seed/`](../core/seed/module-doc.md)、[`core/task/`](../core/task/module-doc.md)、[`core/hotrestart/`](../core/hotrestart/module-doc.md)、[`llm/decision/`](../llm/decision/module-doc.md)、[`neblink/misc/`](../neblink/misc/module-doc.md)、[`perception/`](../perception/module-doc.md)、[`core/search/`](../core/search/module-doc.md)、[`core/hooks/`](../core/hooks/module-doc.md)、[`core/scheduler/`](../core/scheduler/module-doc.md)、[`cli/`](../cli/module-doc.md)、[`llm/providers/`](../llm/providers/module-doc.md)、[`neblink/attachment/`](../neblink/attachment/module-doc.md)、[`core/wiki/`](../core/wiki/module-doc.md)、[`core/ask/`](../core/ask/module-doc.md)
