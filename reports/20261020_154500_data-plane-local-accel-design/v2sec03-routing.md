## 3. 按类分流设计（静态/半静态 vs 实时流）

分流判据一句话：**数据可否在不损语义的前提下「先给旧值、后台对账」**——可以 = 静态/半静态（可缓存/可账本/可 304）；不可以 = 实时流（不可缓存，只能短路传输路径或增强传输协议）。

### 3.1 类一 · 静态/半静态面：套用 v1 管线（loc 位 + 账本 + HTTP 缓存语义）

v1 的「locality 位 + 判序先账本后分流 + nfFileCache 账本」（v1 §4/§5/§6）对控制台数据面**原样成立**——这些面的 loc 恒为 `local`（真源 = 引擎本机盘/内存），字节腿恒走既有票据路/REST，无需新通道。v2 增量只有两件事：

**① 取数语义补齐（引擎侧，逐端点一小刀）**

| 端点 | 现状 | 设计 | 语义依据 |
|---|---|---|---|
| `historyPage`（WS 帧） | 无再验语义，每次全量 | 帧附页指纹 `{total, offset, lastMsgId}`；前端本地层存页 + 指纹，重连/翻页先渲本地再对账，不符拉差 | 历史 append-only：非尾页不可变（翻页天然可缓存）；尾页由 B2 帧流增量维护，无需重拉全页 | 
| `GET /api/usage/aggregate` | 裸 200 | ETag = `窗口键 + 已完结小时数`；已完结小时不可变，命中 304（本机实测条件往返 ≈1.6ms，v1 §A.1 local-timings.json） | `UsageAggCache.scala:37-42` 小时粒度 cell——窗口不变则聚合不变 |
| `GET /api/projects/<n>/flow-map/archive` | 裸 200 | 已归档批次 immutable：`Cache-Control: private, immutable`（TTL=归档窗 24h）+ 批 id 定位增量拉取 | 归档一经落库不改（flowMapArchive refetch 语义已按「新增链 id diff」设计，`flowMapArchive.js:308-313`） |
| `GET /api/projects/<n>/flow-map` | 裸 200 | ETag = `meta.updatedAt`（载荷已带该键）→ 304 | 快照元数据自版本化 |
| `GET /api/delegates/:id`（含台账全文） | 裸 200 | ETag = `record 版 + 台账条目数` | 台账 = 不可变事实流（TaskLedgerStore 条目只增不改） |
| `GET /api/chains` | 裸 200 | ETag = 链集合 gen（flowMapTab 已有 bumpGen 概念） | 快照对账键现成 |

口径纪律：全部 `private`（凭据在会话 cookie/内嵌票据，永不进共享缓存），与 #538 同规（v1 §2.1）。

**② 前端账本键族扩展（nfFileCache 既有形态内）**

- `hist:<sessionId>:<offset>`（历史页；localStorage/IndexedDB，无字节账本参与——文本页小，不占 256MiB 字节预算）；
- `usage:<窗口键>`（用量聚合；会话内存缓存已存，补 localStorage 持久化 + 304 对账）；
- 快照/台账走 ETag 条件往返即达标，**不**入字节账本（载荷小、变化频，字节账本模型不合算——账本价值在「重下代价高的字节」，决策记录于此防过度设计）。

**明示取舍**：历史页「本地权威先行」与 persistence.js 既有契约「后端唯一真源」存在张力——本地先行渲染的陈旧窗 ≤ 一次后台对账往返，且历史不可变性使陈旧窗实际为空窗（仅尾页例外，由流帧维护）。此取舍登记为开放问题 Q18 候作者裁。

### 3.2 类二 · 实时流：不可缓存，设计传输路径短路 + 协议增强

覆盖面：token 流式帧（行 J）、delegate live 输出（行 K）、AskUser 卡片/作答帧（行 O）、节点事件流（行 K）。共同性质：一次性、时序敏感、旧值无意义——缓存/账本/304 全不适用。可用的杠杆只有两个：**把路径变短**（近路）与**把协议变省**（帧增强）。

#### 3.3 同机直连近路（主杠杆，一次判定七面受益）

**现状病理**：门户入口是默认态，浏览器 origin = VPS 门户站；WS 连 `location.host`（`ws.js:626`）⇒ 同机查看时**整站控制台数据**（七类全部）沿「浏览器→VPS→隧道→本机」绕行。引擎侧明明就在同一台机器上回环可达（网关端口本机监听）。

**既有半成品**：`nebflow open` 本地模式已实现完整的近路产品链——本地凭据 → `POST /api/auth/console-ticket` 铸一次性票 → `GET http://localhost:<port>/?ticket` 原子核销（二次用必拒）→ 303 + HttpOnly 会话 cookie 落 **localhost origin** → 之后 WS+REST 全回环（`LocalConsoleUrl.scala:15-37`、`ConsoleTicketRoutes.scala:52-100`；直连腿 cookie 不带 Secure 的坑已修 ：79-100）。缺的只是**自动判定**：现在必须用户/CLI 主动选 local，门户入口不会自己发现「引擎就在本机」。

**设计（M1 建议）**：

| # | 步骤 | 说明 | 复用/新增 |
|---|---|---|---|
| 1 | 门户页探测本机网关 | 门户页加载时（带 `?device=<id>` 已有，`PortalUrl.scala:60-68`）并发探 `http://localhost:<port>/health`（短超时 ≈1s）。https 页探 http://localhost 合法——localhost 是 potentially trustworthy origin，混合内容规则豁免；需网关对本机健康端点回 CORS 白名单（新增一小刀） | 新增（门户侧一段 + 网关 CORS 头） |
| 2 | 身份核对 | 探测响应含 deviceId；与 URL `?device=` 参数一致才切换——防止「本机跑着别人的引擎」错连 | 复用 health/conn 已回的设备面（`PresenceRoutes.scala:293` 族） |
| 3 | 近路换票 | 经**既有隧道**铸 local 类票（`LocalConsoleUrl.issue` 同构，门户代发或引擎 CLI 代发）→ `GET localhost/?ticket` → 303 + cookie 落 localhost origin | 全复用 `ConsoleTicketRoutes.scala`（零改） |
| 4 | 整站迁移 | location 换到 localhost consoleUrl——此后 WS/REST/文件面全部回环，七类数据面**零逐面改造**同时受益 | 零（origin 换了，ws.js:626 自动跟随） |
| 5 | 降级 | 探测失败/超时/身份不符 → 停留门户入口，现状路径原样（隧道永远在，见 Q16 降级策略） | 现状兜底 |

**边界申报（事实 vs 解读分开）**：
- 事实：`ConsoleTicketRoutes` 票证核销、会话落 localhost、直连 cookie 口径——全部已并 main，本设计零引擎端新面（除 CORS 一小刀）。
- 解读（工程判断）：门户侧探测是门户仓库改动（独立发版物）——若门户发版节奏不可控，**降级方案** = 引擎侧自助：设置面板/侧栏展示「本机近路」一键入口（`nebflow open` 已有能力 UI 化），判定权交给用户而非自动探测；两案取舍登记 Q15。

**同机判定纪律**：`loc=local` 的语义在 v2 扩展为**双主体判定**——不只「数据在本机」（v1 口径），还要「**查看端**在本机」（近路口径）。位仍由持有事实的一方写入：入口层判定（步骤 1-2）产出的近路状态属于**会话/标签页**维度，不逐载荷携带（一次判定整站生效，与 v1 §5.2「写入时判定一次」同哲学）。

#### 3.4 跨设备取舍 + 实时流协议增强

**跨设备（查看端 ≠ 引擎机）时实时流的传输面**：现状 = 隧道（中继兜底，100% 可达，恒定 ≈0.6s RTT 税）。理论上限 = 设备通道直传（P2P 打洞/同 LAN 直连）：

| 维度 | 中继隧道（现状） | 设备通道直传（设计选项） |
|---|---|---|
| 可达性 | 100%（双侧出站 WS，无 NAT 要求） | 打洞失败即回落——业界 P2P 打洞成功率经验值 ~60-80%（**解读**，非本项目实测；Symmetric NAT/企业网失败率高） |
| 延迟 | 恒定 ≈0.6s RTT（#538 实测口径） | 同 LAN ≈1-5ms；跨网打洞成功 ≈两端裸 RTT |
| 工程面 | 已并 main，零新增 | 控制台 WS 流变设备间协议：帧封装/重连/鉴权全要新做（字节腿的 DropboxChunkTransports 形态不可直接复用——那是文件分块不是流帧） |
| 既有先例 | 隧道（PortalTunnelBridge） | 字节腿 P2P 优先→relay 兜底分派（v1 §2.4 `RemoteExecutor.dispatchBrowse :574-595`）——**分派哲学可搬，传输面要新造** |

**建议**：M 期内跨设备实时流**保持中继**（付恒定税、零新工程），把打洞边界留给 Q14 候裁；先用协议增强把税变轻：

1. **刷窗自适应**：`FlushWindowMs=50ms` 固定闸（`AgentStreamPipelines.scala:59`）→ 隧道腿按 RTT 探测上调（≈100-150ms）——字节/帧数省 50%+，可感延迟损失 ≈窗口增量本身（流式打字机场景不敏感）。近路腿保持 50ms 甚至更激进。
2. **live 输出轮询变推送**：delegate/bg-task 输出现为 3s/2s REST 轮询（§2.2）→ 复用 WsHub 推增量尾帧（`outputTail {taskId, offset, chunk}`），可见性闸保留（无查看窗不订阅）。省 N×每 3s 的隧道往返；近路下轮询本身已 <20ms，推送为锦上添花（两条腿同语义，Q19 订阅管理候裁）。
3. **时序健康面**（实时流版进度纪律）：帧已带 `(sessionId, round, block)` 单调标识（B2）——前端可断言流连续性：>2s 无帧且 turn 未完 ⇒ 「停滞」提示（复用 v1 §4.4 停滞语义），近路/隧道切换时按序键去重续流，乱序帧丢弃不重画（B2 自注的确定性判据直接可用）。

### 3.5 分流总表

| 类 | 面 | 缓存/账本 | 路径 | 协议 |
|---|---|---|---|---|
| 静态/半静态 | 历史页、用量聚合、快照、归档、台账 | ETag/304 + immutable + `hist:`/`usage:` 键 | 近路（同机）/ 隧道（跨设备） | 不变 |
| 实时流 | token 帧、live 输出、AskUser 帧、节点事件 | **禁**（无意义） | **近路（主杠杆）** | 刷窗自适应 + 轮询变推送 + 序键健康面 |
| 通道税 | 隧道保活/设备心跳 | — | 近路成立 ⇒ 隧道不建立（保活消失）；心跳保留（设备发现本体） | — |
