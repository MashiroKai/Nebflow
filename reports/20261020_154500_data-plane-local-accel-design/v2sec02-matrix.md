## 2. 全中继面盘点矩阵（v2 新增七行 + 路径本体基准行）

图例沿用 v1 §3，并新增两列语义：**引擎侧成本** = 引擎应答该面时的本地读成本（事实）；**同机绕行判定** = 查看端与引擎同机、且经默认门户入口时，该面数据是否不必要地穿 VPS 中继（判定）。

### 2.0 行 R · 中继路径本体（判定基准，先行陈列）

| 维度 | 现状 | 锚点 |
|---|---|---|
| 门户入口 | 默认浏览器入口 = 门户站（`https://mystand.space/portal`），与设备中继分署；`NEBFLOW_OPEN_UI=local` 或显式空配置 = localhost 逃生口 | `brand.conf:16`；`gateway/PortalUrl.scala:27-45` |
| WS 归宿 | 前端 WS 一律连 `location.host/ws`——页面 origin 是谁，数据就过谁 | `web/js/ws.js:626` |
| 隧道 | 引擎主动拨 `wss://<relay>/api/device/relay-ws?deviceId=…`，门户把浏览器 HTTP/WS 请求编成 `http_request`/`ws_open`/`ws_frame` 下行帧，`PortalTunnelBridge` 在引擎本机展开为进程内 router 直调或 `ws://127.0.0.1:<port>` 回环 WS | `neblink/NeblinkRelayTunnel.scala:1340-1346`；`neblink/PortalTunnelBridge.scala:15-56,47-49` |
| 本机近路（已存在的半成品） | `nebflow open` 本地模式：本地凭据换一次性 console-ticket → `GET /?ticket` 原子核销 → 303 + HttpOnly 会话 cookie 落 **localhost origin** → 之后整站 WS+REST 全回环、零隧道 | `gateway/LocalConsoleUrl.scala:15-37`；`gateway/ConsoleTicketRoutes.scala:52-100`（含直连腿不带 Secure cookie 的口径 :79-100） |

**基准结论**：门户入口是**默认态**，localhost 近路是**逃生口态**（需 env/CLI 主动触发）——同机用户默认付出的隧道税遍布全部七类，这就是作者令「所有需要通过中继的数据都要盘点一遍」的病理本体。

### 2.1 行 J · 聊天消息流——半接（本地缓存层已有，取数仍必穿隧道）

| 子面 | 现状 | 锚点 | 同机绕行判定 |
|---|---|---|---|
| 会话历史（取数） | `getHistory` WS op（默认 50 条/页，`beforeIndex` 向上翻页）→ `handleGetHistory` 读 SessionStore `getHistoryPage` → `historyPage` 帧原路返回；重连即全量重拉（历史+explorer 树+任务列表） | `web/js/main.js:3905`（首次）、`:4118`（翻页）、`:3889-3895`（onReconnect 全量重拉）；`gateway/WsSessionChatHandlers.scala:1086-1130`；后台代理面板同款 `web/js/bgAgentPopup.js:574,:778` | ❌ 不必要——历史是引擎本机盘 append-only 日志的投影，本机查看每次开会话/翻页/重连都全量穿隧道 |
| 会话历史（本地层） | localStorage 写后缓存（「后端唯一真源」契约）+ IndexedDB 本地优先层 L0-L5——但仅作乐观显示与断线回退，**不减免** getHistory 往返 | `web/js/persistence.js:1-3`（契约自述）；`web/js/localStore.js`（L0-L5 分级）；`persistence.js:9-14`（淘汰次序） | 缺口 = 本地层从「回退」升「权威先行」（§3.1 行 J 设计） |
| 实时 token 流式帧 | 引擎按 50 字符/50ms 双闸聚合刷窗 → `textDelta`/`thinkingDelta`（B2 帧标识 round/block 单调）→ WsHub 广播 → 隧道 → 门户 → 浏览器；子代理帧 `agentTextDelta`/`agentThinking`（带 nodeSessionId）前端归一为标准帧 | `agent/AgentStreamPipelines.scala:58-59`（聚合闸）、`:81-123`（帧构造）、`:45-79`（round/block 语义）；`gateway/WsHub.scala:87`（广播）；`web/js/ws.js:83-94`（convertAgentEvent） | ❌ 不必要（同机时）——流不可缓存，但每刷窗单向穿隧道 ≈0.3s 级，本地回环 ≈1-5ms（§4） |

### 2.2 行 K · 后台 agent 面板——半接（live 靠 REST 轮询，快照/事件面已分流）

| 子面 | 现状 | 锚点 | 同机绕行判定 |
|---|---|---|---|
| delegate/spec live 输出流 | **REST 轮询**：任务面板对运行中外部代理每 3s 一轮 `GET /api/delegates/:id/output?offset=N`（增量尾读，尾窗 2000 字符，无缓冲 15s 退避）；bg-tasks 输出弹窗每 2s 一轮 | `web/js/taskList.js:785-788`（TICK=3000/TAIL=2000/退避）、`:856`（fetchDelegateOutput）、`:884-895`（可见行才轮询的闸）；`web/js/bgTaskOutputPopup.js:487,:525`（setInterval 2000）；服务端 `gateway/PresenceRoutes.scala:530-553`（bg-tasks）、`:561-580`（delegates output，ExternalOutputStore 增量读） | ❌ 不必要——服务端是纯内存/文件增量读（bg-tasks 注释自证「内存读毫秒级」），轮询每轮却付 ≈0.6s 隧道 RTT；N 个运行中代理 = 每 3s N 次穿隧道 |
| delegate 聚合单查 | `GET /api/delegates/:id` = record 全量 + live 活性 + verdict + **task 台账全文** + childrenCount + output 头（视图④详情首绘单缝） | `PresenceRoutes.scala:582-613`；星型子代理 `:607-632`（`/children`） | ❌ 不必要——引擎内存装配 |
| 任务面板（链快照+行渲染） | 链快照 REST `GET /api/chains`，WS 事件触发防抖镜像重拉；行渲染吃 flow-map 节点载荷 | `PresenceRoutes.scala:455-469`（/api/chains）；`web/js/delegateChainStub.js:11-14`（「REST 快照 + WS 触发的防抖镜像」）；`web/js/taskList.js:38` | ⚠️ 半必要——快照可 304（§3.1），WS 事件腿在近路下自动受益 |
| flow-map 快照/事件 | 快照 REST（元数据 only 载荷，result 键不下行）；结果全文按需 REST（本机实测 <20ms，12s 超时守）；归档 REST（24h 窗服务端已滤）；live = WS 四类节点事件增量管线，断线重连全量快照对账 | `web/js/nodeData.js:26`（快照端点）、`:31`（result）、`:41`（archive）、`:127-130`（<20ms 实测自注）、`:190-202`（archive fetch）；`web/js/flowMapTab.js:2316-2345`（增量管线）、`:2266-2268`（重连对账自注） | ⚠️ 快照面可缓存（归档批次天然 immutable），live 事件腿近路受益 |

### 2.3 行 L · token 用量面板——未接（响应零缓存语义）

- 取数：`GET /api/usage/aggregate?dim&from&to&provider&model&agent`（`gateway/HealthRoutes.scala:75-85`）→ `UsageRecordStore.aggregate`——引擎侧已有**小时粒度增量聚合缓存**（`core/UsageAggCache.scala:37-42` cell 合并、注释明示「window 全在窗内 hours + 边缘两小时精读」），应答本机成本极低。
- 前端：面板按需拉取（loadSummary/loadHeatmap/loadCurve/loadDrill），会话内存缓存 rankCache/dimDayCache（`web/js/usageDashboard.js:55-57`，`:418-421` clearCaches）——刷新页面即失。
- 缺口：响应**无 Cache-Control/无 ETag**（`HealthRoutes.scala:85` 裸 `Ok(agg.asJson)`）——聚合以小时为最细键、已完结小时天然不可变，却每次开面板/换维度全量穿隧道重拉。
- 判定：❌ 不必要（同机绕行 × 缓存缺位双重税）。

### 2.4 行 M · portal 轮询面——混合（通道税 + 门户域税）

| 子面 | 现状 | 锚点 | 判定 |
|---|---|---|---|
| portal_status / console-ticket | 会话重立路径：WS 握手被拒 → 同源跑门户自己的 `GET /api/portal/status` + `POST /api/portal/console-ticket` → 302 去 consoleUrl；30s sessionStorage 防重载闸。**此二路由是门户站自有路由**（门户仓库域，非本库） | `web/js/ws.js:479-515`（注释自证「re-run the portal's own cycle same-origin」） | ⚠️ 门户域——引擎设计域之外，显式划界（开放问题 Q20） |
| 设备心跳 | 引擎→中继 `POST /api/device/heartbeat` 每 30s（会话续期 + peer 列表 + 本机端点重探测） | `gateway/GatewayMain.scala:320-332`（「Every 30 seconds」自注）；`neblink/NeblinkClient.scala:504-514`；交接文档 §4 | ✅ 必要税——跨设备在线面本体；同机近路不消除它（它服务的是设备发现，不是控制台数据） |
| 隧道保活 | 隧道 WS ping 每 5s、10s 无 pong 退休 + 自动重连 | `neblink/NeblinkPresenceService.scala:197-198`、`:1288` | ⚠️ 通道税——近路成立时整条隧道不必建立，此税整体消失 |
| 面板侧状态轮询 | neblink 状态按需 fetch（面板打开/配对后 +1.5s 刷新）；设备流登录轮询仅登录期 | `web/js/neblink.js:301`（fetchNeblinkStatus）、`:544-617`（device-flow 轮询） | ⚠️ 低频、按需——量级可忽略，不单列加速 |

### 2.5 行 N · 日志/证据读取——继承 v1（无新增缺口，锚点归并）

- 证据/reports 字节面：v1 行 H 已盘点（无专属静态路由，走 nf-file 票腿 + Canvas readFile，`WsFileOpsHandlers.scala:293-309,387-388`）——v2 无补充。
- LLM 路由日志：落 `<dataRoot>/logs/router/{date}_{summary|full|sse}.jsonl` 三族（`core/LlmLogWriter.scala:18-24`）；web 侧仅开关状态帧 `getLlmLog`→`llmLogState`（`gateway/WsConfigHandlers.scala:331-338`，布尔值），**内容读取走文件面**（readFile/nf-file）= v1 行 D/H 管线覆盖。
- 会话录制：SessionRecorder 写 .jsonl（`gateway/WebSocketRoutes.scala:2319` 起录制腿）——引擎侧产物，读取同文件面。
- 判定：❌ 同机时不必要——全部是本机盘读，v1 三档管线（票据路 ≈45ms + 账本零取数）已给达标样板；本行并入 v1 无新设计。

### 2.6 行 O · AskUser 问答对——半接（附件腿有进度，问答帧腿纯穿隧道）

- 卡片下行：引擎组 `askUser` 帧（`agent/AgentActor.scala:849-901`、`agent/InteractionHub.scala:259`）→ WsHub 推送 → 隧道。
- 快照回放：连接建立/历史页后重发 `pendingAsksSnapshot`（全库 pending 卡单帧，`gateway/WebSocketRoutes.scala:336-371`；历史页后同队列重放 `WsSessionChatHandlers.scala:1126-1132` 注释）。
- 作答上行：`askUserAnswer` WS op（`web/js/chat.js:3017,:3045`；`gateway/WsSessionChatHandlers.scala:65-88`——含 secret 槽 fail-closed 分流 :82-86 与作答回传附件 :78-81）；取消 `askUserCancel`（`chat.js:3041`）。
- 附件腿：`POST /api/askuser/attachments` multipart + 流式 sha256 + 进度/重试（`gateway/AskUserAttachmentRoutes.scala:4,:199`；`web/js/askAttachments.js:246,:284-291`）——v1 行 F 腿 B 已盘点，本行不重复。
- 判定：❌ 不必要（同机时）——问答对是引擎内存态（InteractionHub pending 表），一来一回各付 ≈0.3s/0.6s 隧道税；附件字节腿另有 v1 管线可套。

### 2.7 行 P · 任务台账——未接（无台账专属面，随载荷搭车）

- 存储：引擎侧统一台账 TaskLedgerStore，三态条目 wire schema（`core/project/TaskLedgerStore.scala:19-28`；note 时间线独立于条目，注释明示）。
- web 消费面（无专属路由，三处搭车）：①delegate 聚合单查内嵌「task 台账全文」（`PresenceRoutes.scala:582-586` 注释自证）；②链节点载荷的 taskNo 台账号（`web/js/delegateChainStub.js:90`）；③会话切换时台账标题批量投影（`gateway/WsSessionChatHandlers.scala:1055` `titleOfSync`）。
- 判定：❌ 不必要——台账是引擎本机 JSON 文件（AtomicJson 落盘族），随面板载荷穿隧道属搭车税；台账全文（不可变事实流）天然可 ETag/304（§3.1）。

### 2.8 矩阵小结（盘点 → 分流归 类）

| 行 | 类 | 引擎侧成本 | 同机现状 | v2 设计落点 |
|---|---|---|---|---|
| R 路径本体 | — | — | 门户默认，localhost 是逃生口 | §3.3 同机直连近路（主杠杆） |
| J 消息流·历史 | 静态/半静态 | 本机盘 append-only 读 | 必穿隧道 | §3.1 本地权威先行 + 304 |
| J 消息流·token 帧 | **实时流** | 内存缓冲刷窗 | 逐窗穿隧道 | §3.3 近路 + §3.4 流增强 |
| K agent 面板·live | **实时流** | 内存/文件增量读 | 3s 轮询穿隧道 | §3.4 轮询变推送 + 近路 |
| K agent 面板·快照 | 静态/半静态 | 内存装配 <20ms | 必穿隧道 | §3.1 ETag/304 + immutable 归档 |
| L token 用量 | 静态/半静态 | 增量聚合缓存 | 必穿隧道 | §3.1 ETag（小时粒度不可变） |
| M portal 轮询 | 通道税/门户域 | — | 心跳必要、隧道税可消 | §3.3（近路消隧道税）；门户域划界 Q20 |
| N 日志/证据 | 静态 | 本机盘读 | v1 管线已覆盖 | 引用 v1，无新设计 |
| O AskUser 问答 | **实时流**（帧腿）+ 静态（附件） | 内存 pending 表 | 来回各穿隧道 | §3.3 近路；附件腿套 v1 |
| P 任务台账 | 静态/半静态 | 本机 JSON 读 | 搭车穿隧道 | §3.1 ETag/304 |
