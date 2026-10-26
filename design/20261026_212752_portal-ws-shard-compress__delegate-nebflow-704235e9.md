# Portal WS 面分片 + 应用层压缩 跨端协议设计（台账 #957 · plan-only 设计稿）

日期：2026-10-26　作者令：只设计不实现（本稿零实现代码）。
范围：stand 引擎（Scala，本仓）× link-server（Rust relay/portal ingress）× 浏览器控制台客户端（本仓 `src/main/resources/web/js/`，随引擎静态树经隧道伺服）。
红线遵守：逐仓读码取证（证据行号逐条给出）；未经代码证实的判断一律标 **待核实**；全文无任何「X 秒内完成」式时限承诺（既有实现常量只作为现状事实引用）。

---

## 0. 结论（TL;DR）

1. **分片选型**：新增**契约帧类型** `ws_data_frame`（字段 `requestId / seq / more / kind / enc / data`，语义镜像既有 `body_frame`），不复用 RFC 6455 帧分片、**不**给 `ws_frame` 加可选字段。理由：v1 读端对未知**键**是「静默丢弃」（PortalWireCodec.scala:41 契约注释、decode 主流程），给 `ws_frame` 加 `seq/more` 会被旧端当**整消息**消费 = 静默截断损坏；而未知**类型**走 `unknown_type` 拒收（fail-closed）——新帧类型是把不兼容变成「显式失败」的唯一安全形态。
2. **压缩选型**：**应用层 gzip**，在 `ws_data_frame` 帧族内以 `enc: none|gzip` 携带；**不采用 permessage-deflate**（relay 的 axum 0.8.9 ws 抽取器无此能力——已核实 vendored 源码；引擎 JDK HttpClient WebSocket 无扩展 API——待核实 javadoc；即 PMCE 在两端栈上均不可用，只有浏览器↔Caddy 段可协商但 relay 永不接受，全链无效）。gzip 两端**零新依赖**（JDK Deflater；link-server 已依赖 flate2）。zstd 留 `enc` 枚举版本位，本批不实施（两端各引一个新绑定，收益/复杂度比不合算）。
3. **协商**：复用本仓 relay 已生产的 subprotocol 协商先例（`negotiate_wire_mode`，routes.rs:4749，v2 binary 面即此形）：隧道升级时引擎 offers 新 token（暂名 `neblink.portal.v2`），relay 侧环境开关 + per-device 灰度 allowlist 决定是否 select；**协商失败不破链**（老端根本不发该头，新端发而无 select ⇒ 读 `getSubprotocol()` 回落 v1 整帧）。浏览器侧**零改动**（relay 重组后以无压缩整消息投递）。
4. **推荐次序**：阶段一 = 引擎**上行**（engine→relay）分片 + 压缩（#949 痛点方向）；relay 的**下行**切片实现但默认关（env）。relay 先灰度（双栈收发能力就位），引擎后上。
5. **参数叠放**：分片不解除、也不依赖引擎本地 assembler cap（`MaxWsMessageBytes`，现值 4 MiB，#955 配对批拟下调——**待核实**该批落点）；超 cap 消息照旧 1009 关环。分片解决的是「单帧写占住发送位」的队头阻塞，不是「消息体积上限」。

---

## 1. 逐仓读码取证（三端实况）

### 1.1 拓扑定案（含对简报的一处勘误）

实际数据路径（全部已核实，见下）：

```
浏览器控制台 UI（ws.js 等，由引擎静态树伺服，经隧道取到）
   │  HTTPS/WSS（Caddy 反代，X-Real-IP 注入）
   ▼
link-server（Rust / axum 0.8.9，/Users/kaiyu/.stand/projects/link-server，独立 git 仓，生产头 58b41d8）
   ├─ portal_ingress.rs + routes.rs：portal 契约终止点（HTTP face + console WS face）
   └─ relay.rs：每宿主引擎一条 WSS 隧道（契约帧 + legacy neblink wire 双栈）
   │  WSS 隧道（契约帧=TEXT，legacy v2=binary；引擎侧 JDK HttpClient WebSocket）
   ▼
stand 引擎（Scala / http4s；NeblinkRelayTunnel 隧道客户端 + PortalTunnelBridge + PortalWireCodec）
   └─ loopback 回源：HTTP face 进程内直调；WS face 连 ws://127.0.0.1:<port>（真 loopback WS）
```

勘误与身份澄清：
- 简报所称「门户 nebflow-website」**不在数据路径上**：`/Users/kaiyu/.stand/projects/nebflow-website/` 为空壳（仅 `.nebflow`，已核实）；真正的门户站是 `/Users/kaiyu/.stand/projects/stand-website/`（Next.js + Vercel + Logto/Supabase，身份/引导面），仓内**无任何契约词**（`ws_frame|body_frame|ws_open` 零命中，已核实）——它不发也不收 portal 帧。
- **portal ingress = link-server**（`portal_ingress.rs` + `routes.rs`），不是独立 Node relay。link-server 同时持有契约文本的 byte-identical 副本（`.nebflow/Spec/portal-wire-contract-v1.2.md` 等，已核实）并跑 Rust conformance 套件。
- `/Users/kaiyu/.stand/projects/neblink-server/` 为**旧副本**（无自有 .git，git log 上溯到数据根仓；`portal_wire.rs` 与 link-server 逐字节一致已核实，仓内其余面是否漂移**待核实**）。本设计一律以 **link-server** 为 relay 仓。
- 浏览器控制台客户端（ws.js/messages.js 等）随引擎静态树伺服（console-fastpath 批 REPORT：隧道脸 `GET /` 由服务端把 stylesheet/vendor 内联织进 HTML，已核实 `reports/20261024_003000_console-fastpath-batch-lan__delegate-nebflow-791ae42c/REPORT.md`），故「第三端」的代码源也在本仓。

### 1.2 stand 引擎（发送/接收端）——已核实

| 面 | 证据 | 现状 |
|---|---|---|
| 契约编解码单点 | `src/main/scala/nebflow/neblink/PortalWireCodec.scala` | 13 帧清单 ：59-73；`SupportedProto=1` :56；`data` 一律 RFC4648 padded base64 :540-547；未知类型 `unknown_type` :171；proto 超版拒收 ：177-181；「unknown extra keys 静默忽略」为冻结契约行为（类注 ：16-23） |
| HTTP face 分片（对照面） | `PortalTunnelBridge.scala` | 响应体 `body.chunkLimit(BodyChunkBytes=256KiB)`（:1093）→ `body_frame{seq,more}`（:282-305）；seq 乱序**丢整个 cycle** :322-344；空响应也发 `more=false` 终结帧 :299-304 |
| WS face 上行（问题面） | `PortalTunnelBridge.scala` LocalWsListener | loopback 整消息重组（`PortalWsMessageAssembler`，上限 `MaxWsMessageBytes = 4 MiB` :1101，注释明言「base64 后仍低于 relay listener 8 MiB 重组上限」）→ **一个** `ws_frame{…, fin=true}`（:666-670）——**零分片**；超限 `message-too-large` → 关环 1009 :672-679 |
| 发送串行化 | `RelayFrameSender`（同文件 :870-1088） | 全部上行帧共用 JDK 单发位；`ReentrantLock(true)` 双车道让位（2026-10-26 稳定性批）：≤64KiB 字符=控制车道越队（`ControlLaneMaxChars` :1077），bulk（body_frame 349K 字符、ws 大帧至 5.6M 字符）按 FIFO 持位逐帧等写完；单帧写预算 `SendTimeoutSec=15`（:1064，现状常量） |
| WS cycle 队列 | 同文件 ：1188-1194 | 每 cycle 两条 `LinkedBlockingQueue(WsQueueSlots=128)`（:1099；简报称已批下调 64——**待核实**，本 worktree 现值 128） |
| 隧道下行重组 | `NeblinkRelayTunnel.scala` | RFC6455 分片重组器 `FrameReassembler`，`MaxAssembledFrameChars = 8 MiB 字符`（:1971）、`AssemblyDeadlineMs=30s`（:1984）；完整帧才进 `handleFrame`（:1707-1735） |
| 隧道客户端栈 | 同文件 connectOnce :740-768 | `java.net.http.HttpClient` + `newWebSocketBuilder()`（JDK WebSocket）——无扩展 API（**待核实** JDK javadoc 口径；社区共识：OpenJDK 实现不协商 permessage-deflate） |
| 隧道活性 | relay 侧常量 + 引擎 pong 口 | relay ping 3s / 3-miss / idle 90s（relay.rs:1191-1192、:1166）；引擎 pong 走 `PortalConnection.sendControlText` → `RelayFrameSender` 控制车道（PortalTunnelBridge.scala:1299-1315） |
| 参数联动面 | 同文件 | `BodyQueueSlots=64`（:1094）；`FirstBodyFrameTimeout=30s`（:1111）；契约「section 4 值为占位，待 T-1 回冻」（:1092 注释） |

### 1.3 link-server（relay = portal ingress）——已核实

| 面 | 证据 | 现状 |
|---|---|---|
| 契约编解码 | `src/portal_wire.rs` | `SUPPORTED_PROTO: i64 = 1`（:50）；九码拒收词汇表 :53-61；`probe()` 值层拦截（{type,proto} 三分：ProtoUnsupported / NewFrame / Legacy）供读循环先行分诊 |
| 契约文本 | `.nebflow/Spec/portal-wire-contract-v1.2.md` | v1.2 = v1.1 的**纯勘误版**（无帧形变化、向量集不变=仍用 v1.1 向量；已核实 diff：仅 §4 心跳检测线公式更正） |
| HTTP face 分片（对照面） | `src/portal_ingress.rs` + `routes.rs` | `DEFAULT_PORTAL_BODY_CHUNK_BYTES = 256KiB`（ingress.rs:45，env `PORTAL_BODY_CHUNK_BYTES`）；浏览器请求体 → `ingress_body_framer` 切 `body_frame`（routes.rs:3955-3991、:4270）；上行 `dispatch_uplink` seq 乱序丢环（ingress.rs:563-575）、队列满丢环 + `T1_BACKPRESSURE_DROPS_TOTAL`（:578-598） |
| WS face（问题面，两向均零分片） | `routes.rs` console_ws_bridge / console_ws_pump | 浏览器整消息 → **一个** `ws_frame{fin=true}` 推隧道（:3727-3750，`push_ws_frame(…, true)`）；隧道满 → 对浏览器 1011 "ingress backpressure"（:3728-3747）；host→浏览器方向：`dispatch_uplink` 的 `WsFrame` → `to_browser` mpsc(**64**)（:3570）`try_send`，满即丢环 1011（ingress.rs:644-667）；`fin` 被忽略（「axum sends whole messages」，:3707） |
| ws_open 桥 | routes.rs:3557-3679 | 浏览器升级 → `ws_open`（v1.1 D9：cookie 头 verbatim :3601-3615）→ 引擎 `ws_open_result` 判定后才发 101（预算复用 resp_header 窗，:3634-3637） |
| 隧道写面 | `src/relay.rs` relay_ws_loop | 写任务 `biased` select：控制车道（无界 ctrl mpsc：ping/pong/Close）先于 bulk（:1635-1654）——契约帧走 `OutboundFrame::Text` 已编码 JSON（:1776-1789）；bulk 出站队列 `NEBLINK_RELAY_OUTBOUND_QUEUE_DEPTH`，**默认 0 = 无界**（:1504-1508） |
| 隧道读面 | 同文件 ：1834-1884 | `probe()` 三分诊 → NewFrame 解码 → `dispatch_uplink`；`ProtoUnsupported` 无 fallback 全族拒收（:1853-1861）；未知类型落 Legacy 面 → 既有 W9-W12 留痕丢弃（:1879-1883、:1211-1214） |
| WS 栈能力 | Cargo.lock + vendored 源 | axum 0.8.9 + tokio-tungstenite 0.29；**axum ws 抽取器无 permessage-deflate**（vendored `axum-0.8.9/src/extract/ws.rs` 全文仅 `max_message_size`/`max_frame_size` builder，无 deflate/compression 字样——已核实）；两上限未配置 → 库默认（**待核实** axum 文档默认值 64MiB/16MiB） |
| v2 binary 协商先例 | routes.rs:4704-4768 | 升级握手 `Sec-WebSocket-Protocol: neblink.relay.v2` offers → `negotiate_wire_mode`（env 开关 + per-device 灰度 allowlist `binary_enabled_for`）→ `WireMode`；「协商失败绝不破链（I2）」「服务端是适配点两向转码（I4）」；`relay_frame::decode(bytes, 32MiB)` 为 v2 面 cap（:1746、:1896） |
| 好友腿补投（对照面） | relay.rs:1535-1564 | 隧道注册时 P1-B4/S1/S2/撤回墓碑四组 at-least-once 重放（ledger+cursor，客户端 eventId 去重）；设备腿 live-only 无补投（:1565-1585 裁定） |
| ingress 计时器 | ingress.rs:47-55 | resp_header 15s / body_idle 60s / ws_idle 90s（env 可覆写；现状常量引用） |

### 1.4 浏览器控制台客户端——已核实

- `ws.js`：发送面**有界 pending 队列**（`PENDING_SEND_CAP=100`，:435；`sendWs` 未连接入队、溢出丢最旧 ：466-475；`onopen` 先补发后引导命令 :451-463）+ `clientMessageId` 幂等（resend-dedup 批 #569，:443-450）；心跳 3-miss 自我逐出 + 传输 shelter（:791-898）；静默重座 `attemptSilentReseat`（2026-10-26 稳定性批）。
- `messages.js`：W13 待补队列（`pendingFriendFrames`，cap 32，每帧至多补投一次，:4732-4743）。
- 上述客户端机制全部骑在 portal 的 `ws_open/ws_frame` 桥上，**不感知桥内帧形**——这是「浏览器零改动」结论的客户端依据。

### 1.5 #949「已交付的死信/补投面」盘点

**待核实**：#949 的台账原文不在本仓（reports/evidence/design 三处及 git log 均 grep 不到 #949 归属件；台账在作者处）。仓内**实存**的死信/补投族 face 为下表五件；本设计第 6.4 节按「五件全数复验」编制，#949 究竟指哪几件请作者在派发实现批时圈定：

| # | face | 位置 | 与本批关系 |
|---|---|---|---|
| D1 | 浏览器控制台 WS 发送 pending 队列 + 重连补发 + clientMessageId 去重 | ws.js:435-475 | 逻辑消息身份不变 ⇒ 分片/压缩对它透明；复验点=补发序与整消息语义不变 |
| D2 | 好友事件 W13 待补队列（补投一次） | messages.js:4732-4743 | 帧内 payload 为 legacy JSON 事件（不经契约分片）⇒ 零影响；复验=回归 |
| D3 | ingress cycle 级丢弃语义（backpressure/seq 违规/链路下线传播） | portal_ingress.rs:443-506、routes.rs:3971-3977 | 分片后「丢」的单位仍是**整个 WS cycle**（见 §4.4）⇒ 语义保持；复验=计数器断言 |
| D4 | 引擎 loopback 超限 1009 关环（`message-too-large`） | PortalTunnelBridge.scala:672-679 | 分片**不解除**本地 cap（§7）；复验=超 cap 消息仍 1009 |
| D5 | 好友/设备邮件 at-least-once 重放 + eventId 去重 | relay.rs:1535-1564、DeviceMailInbox.scala:30-37 | 走 legacy wire（非契约面）⇒ 零影响；复验=回归 |

---

## 2. 现状问题机械链（为什么单帧即队头）

已核实的链路事实，全部可由 §1 证据行号复核：

1. 引擎上行：控制台一条大 JSON 消息（UI 状态/日志洪峰类，#949 诊断口径 4 MiB 量级）经 loopback WS 整消息重组（cap 4 MiB）→ **一个** `ws_frame` → base64 ≈ **5.6 MB**（4×4/3=5.33 MiB≈5.59e6 字节）→ `RelayFrameSender` 持 JDK 单发位直到这条帧写完。跨境上行带宽下这一写就是「大快照单帧写 10–45s」的现场（#949 诊断结论；本稿不复述为承诺）。
2. 期间后果（三族，同源）：并发 HTTP cycle 的 `body_frame` 流在公平锁 FIFO 上排在这条巨帧后（bulk 车道内无越队）；ingress `resp_header` 15s 窗（现状常量）超窗回 504；`to_browser(64)`/cycle inbox(128) 有界队列积满即丢环。控制帧（pong/响应头）已被 2026-10-26 双车道让位救出，**bulk↔bulk 之间的队头阻塞原样保留**——这正是 2026-10-26 稳定性批明文不动、留给本批的面（RelayFrameSender 类注「车道决策只影响不同流之间的排队序」之上，粒度仍是整帧）。
3. 下行同病但载荷小：浏览器→引擎为指令小帧（**待核实**：未做下行载荷分布测量；console 指令帧按代码面看为 KB 级），且 relay 侧 `console_ws_pump` 也是整消息 `ws_frame`。故本批主攻上行，下行切片机制就位、默认关。

---

## 3. 方案选型（分片 × 压缩）

### 3.1 分片形态

| 候选 | 裁定 | 理由 |
|---|---|---|
| **A. 新契约帧类型 `ws_data_frame`（选）** | ✅ | 镜像 `body_frame` 的 `seq/more` 纪律，两端都有成熟先例与测试面（引擎 seq 检查 ：329-331；ingress `next_uplink_seq` :564-576）；未知类型在**所有** v1 端 fail-closed（Scala `unknown_type` :171；Rust probe→Legacy 面 W9 留痕丢弃），不兼容=显式丢帧+留痕，绝不静默截断 |
| B. `ws_frame` 加可选 `seq/more` 字段 | ❌ | v1.1 冻结行为=未知键静默丢弃（PortalWireCodec 类注 ：16-23）；旧读端会把每个分片当整消息消费 ⇒ **静默损坏**，违反契约「never partially processed」精神 |
| C. RFC 6455 帧级分片（协议层 fragmentation） | ❌ | 引擎上行侧 JDK `sendText` 不暴露分片控制；relay 侧 axum/tungstenite 整消息语义（console_ws_pump :3707 注释自证「fragments were reassembled upstream」）；即便栈支持，也只是把队头从应用层挪到传输层，发送位占用问题原样存在 |
| D. `relay_response`/工具面分片 | ⏸ 另案 | 工具输出面另有 v2 binary 载体（32MiB cap，relay.rs:1746）与其自身灰度；不与本批混 |

分片参数：`shardBytes` 默认 **256 KiB（raw）**，对齐 `body_frame` 既有口径（引擎 `BodyChunkBytes`、ingress `PORTAL_BODY_CHUNK_BYTES`），单点新常量 + env；base64 后 ≈349K 字符，稳定落在 bulk 车道（控制车道阈值 64KiB 字符，语义余量与现有 body_frame 同注）。4 MiB 消息 = 16 片。**择一备选** 128 KiB（片更细、粒度更好、片数翻倍）——定稿留给实现批用实测选定，本稿默认 256 KiB。

### 3.2 压缩形态

| 候选 | 裁定 | 理由 |
|---|---|---|
| **A. 应用层 gzip（选）** | ✅ | 两端零新依赖（JDK `Deflater`/`GZIPOutputStream`；link-server 已依赖 `flate2 = "1"`，Cargo.toml 已核实）。console 消息为 JSON 文本，简报预期 5–20×（**实现批实测口径，非本稿承诺**）；CPU 量级为「毫秒-百毫秒/消息」，与「单帧跨洋写」的现行瓶颈差两个数量级（工程估算，非承诺）。压缩点=**整消息压缩后切片**（不是逐片压），解压点=重组完成处：压缩率不碎化、seq 纪律不变 |
| B. permessage-deflate（PMCE） | ❌（本链不可用） | ① relay：axum 0.8.9 ws 抽取器无该能力（vendored 源码已核实；tungstenite 下游即便有 feature 也穿不透 axum 抽取面）——要上就得换 WS 栈/等 axum 上游，属于另立的基建批；② 引擎：JDK WebSocket 无扩展 API（**待核实** javadoc 口径）；③ 浏览器↔Caddy 段即便协商成功，relay 不接受 ⇒ 无效。结论：PMCE 在本链是「需要换两端栈」的方案，与本批（零基建变更、三端小改）目标冲突 |
| C. zstd | ⏸ 留版本位 | `enc` 枚举预留 `zstd` 值不实现：两端各引一个新绑定（zstd-jni / zstd crate），收益需实测说话；枚举扩展走契约 minor 版 + 能力位，本批不占 |

压缩边界（重要）：
- 压缩**只存在于 `ws_data_frame` 帧族**；v1 路径（`ws_frame`、`body_frame`、其余 11 帧）字节级原样——旧端可见行为零变化。
- `enc` 是**每帧字段**且同一消息的各帧必须同值（mismatch = `invalid_format` 丢环；见 §4.4）——把校验保持在每帧无状态，重组器只消费不判定。
- relay 是压缩的**终止点**：重组+解压后以无压缩整消息投浏览器（axum `Message::Text/Binary`），浏览器零改动、零感知。

### 3.3 推荐组合与次序

**「新帧族分片 + 帧族内整消息 gzip + subprotocol 能力协商」，阶段一引擎上行先行，下行机制就位默认关。**
收益顺序：分片（把发送位占用粒度从整消息降到单帧，bulk↔bulk 队头阻塞解除）独立可发布；压缩（字节量降 5–20×，预期口径）随帧族同批搭车，二者共享一套协商与灰度，无次序耦合。

---

## 4. 协议设计

### 4.1 新帧：`ws_data_frame`

```
{ "type": "ws_data_frame", "proto": 1,
  "requestId": <uuid>,        // 与 ws_open/ws_frame 同一面：一个 requestId = 一个 WS cycle
  "seq": <int ≥0>,            // 0-based，每方向每 requestId 严格 +1（契约 2.5 body_frame 同口径）
  "more": <bool>,             // true=后继还有本消息分片；false=终结帧（data 可为空）
  "kind": "text"|"binary",    // 重组后消息的投递 kind（对浏览器 face 的语义，非本帧 wire 形态）
  "enc": "none"|"gzip",       // 本帧 data 的编码；同一消息各帧必须同值
  "data": <base64>            // enc=none: 原始分片字节；enc=gzip: 整消息 gzip 流的第 seq 片
}
```

语义钉：
- **整消息压缩、按字节切片**：`enc=gzip` 时 `data` 各帧顺接 = 完整 gzip 流；解压发生在 `more=false` 到齐后。分片边界与 gzip 边界无关（按 raw 字节切，不按 gzip 成员切）。
- **同帧族替代**：能力协商成立后，发送端对该 cycle 的应用消息**一律**走 `ws_data_frame`（单帧消息 = `seq:0, more:false`），不留「小消息回退 ws_frame」的第二路径——单解码路径，杜绝同 cycle 两族混流。legacy `ws_frame` 类型保留，仅用于未协商隧道。
- `kind` 的既有序（同 cycle 内 FIFO）由既有构造保证（引擎：单 cycle 单 fiber 逐帧等完成；relay：写任务单消费者），跨 cycle 乱序与现状同权（非契约面，双车道批已声明同口径）。
- 契约版本：帧清单 13→**14**，`proto` 仍为 1（v1.1 先例：additive 面以「能力 readiness 门控 + 冻结先行」落位，类注 PortalWireCodec.scala:16-23）。契约文件拟为 **v1.3**（additive re-freeze），向量文件补 `ws_data_frame` 正/负向量后随既有 byte-identical 机制分发三处（stand `src/test/resources/neblink/`、link-server `.nebflow/Spec/`）。
- 拒收语义沿用九码词汇表：`unknown_type`（v1 端）、`invalid_format`（seq<0 / enc 未知值 / 同消息 enc 不一致——后者需重组器判，见 §4.4）、`invalid_base64`、`missing_required_field` 等。

### 4.2 能力协商（握手字段 = subprotocol token）

复用 relay 已生产的 v2 binary 协商形（routes.rs:4704-4768 全套纪律照搬）：

- 引擎隧道升级（`NeblinkRelayTunnel.connectOnce`，:740-768）追加 `subprotocols("neblink.portal.v2")`（token 名定稿随契约冻结）。
- relay 隧道升级 handler：`negotiate_portal_caps(headers, limits, device_id)` —— 与 `negotiate_wire_mode` 同构：env 总开关（如 `NEBLINK_PORTAL_V2_ENABLED`）∧ per-device 灰度 allowlist ∧ client offer 含 token ⇒ select 并 echo（`ws.protocols([...])`）；否则 select 空。
- 引擎侧判定：JDK `WebSocket.getSubprotocol()` 读回非空 ⇒ 本隧道 portal-v2 生效；空 ⇒ **回落 v1 整帧**，零分支差异、零探测期。
- 「协商失败不破链」（I2 原句纪律）：老引擎不发 offer（行为不变）；老 relay 收到 offer 不 select（axum 对未 echo 的 subprotocol 不影响升级；**待核实** axum/tungstenite 对「offer 了但服务端未 echo」的升级放行行为——实现批以真 socket fixture 钉死，同为 relay_legacy_text 既有兜底语义的观察窗）。
- 浏览器↔relay 段**不协商**（浏览器零改动）；下行对浏览器始终整消息。

per-tunnel 能力存 `TunnelLiveState`（relay 侧，与 `wire_mode` 同位）；引擎侧存连接装配处（`PortalConnection` 同生命周期）。

### 4.3 兼容矩阵（旧/新 引擎 × 旧/新 relay × 任意浏览器）

| 引擎 | relay | 浏览器 | 上行（引擎→relay） | 下行（relay→引擎） | relay→浏览器 | 结果 |
|---|---|---|---|---|---|---|
| 旧 | 旧 | 任意 | `ws_frame` 整帧（现状） | `ws_frame` 整帧 | 整帧 | 现状 |
| **新** | 旧 | 任意 | 引擎 offer 无 echo ⇒ **回落 v1 整帧** | `ws_frame` 整帧 | 整帧 | 功能不变，仅无优化 |
| 旧 | **新** | 任意 | `ws_frame` 整帧（relay 照收，v1 分支零改动） | relay 见无 offer ⇒ v1 整帧 | 整帧 | 功能不变 |
| **新** | **新** | 任意 | `ws_data_frame`（分片+gzip，env 可关） | **阶段一仍整帧**；阶段二 `ws_data_frame` 下行切片（env 开） | **整帧、无压缩**（relay 重组解压后投递） | 本批目标态 |

拒收兜底（契约既有语义，非新增机制）：
- 新引擎误向旧 relay 发 `ws_data_frame`（协商 bug 场景）：probe→Legacy 面→W9-W12 留痕丢弃（relay.rs:1879-1883、:1211-1214）——该 cycle 停滞并由 ws_idle(90s) 收尾，不炸隧道。实现批加互操 spec 把此格钉死为「丢帧+WARN+计数」，不出现 panic/连接死亡。
- 新 relay 误向旧引擎发 `ws_data_frame`：`PortalWireCodec.decode` → `unknown_type` 拒收（:171），tunnel 读面按既有 undecodable 路径留痕丢帧。同上互操钉死。

### 4.4 流级纪律（分片后的「丢」语义）

- **seq 违规 / enc 不一致 / 重组超 cap ⇒ 丢弃整个 WS cycle（1011 "ingress backpressure" 家族）**，镜像 body_frame 的丢环纪律（引擎 ：330-331、ingress :563-575）——不做「丢单条消息续流」的软语义：对有序流做静默挖洞比显式断环更伤（浏览器端既有 3-miss/重连面接得住断环，接不住幽灵空洞）。
- 重组缓冲上限：relay 侧新常量（默认对齐 4 MiB，env `PORTAL_WS_REASM_MAX_BYTES`）；引擎侧 loopback 消息 cap（`MaxWsMessageBytes`）**原样保留**（§7）。超 cap ⇒ `message-too-large` 家族语义不变（引擎下行重组超限走既有 1009 面；relay 上行重组超限走丢环+新计数器）。
- 无新增计时器：分片在有序 FIFO 上连续到达，重组不设独立 deadline（引擎既有 RFC6455 重组器的 30s deadline 是**另一层**的机制，不动、不与之耦合——两层语义在 §5.1 落点表分列）。
- 终结帧缺席面（发送端死在中途）：归入 cycle 撕除面（隧道重连/链路下线传播已覆盖，ingress.rs:443-488），不设「半消息超时回收」新机制。

### 4.5 灰度顺序

1. **B1 relay 先**（双栈能力就位，默认全关）——线上行为字节级不变；只为新引擎的到来备好接收面。
2. **B2 引擎后**（offer subprotocol + 上行分片+gzip），per-device 灰度 allowlist 逐台放行（含 #949 的 Mac 发送端首台）。
3. 上行稳定后，**下行切片** env 打开（阶段二）。
4. 浏览器 B3 复验批与 2-3 并行（零协议改动，只回归）。
每步可独立回退：引擎侧 env 关 = 回整帧；relay 侧 allowlist 摘除 = 拒 select。

---

## 5. 三端改动清单（函数级落点）

依赖序：**B0 契约 → （B1 relay ∥ B2 引擎）→ B3 前端复验 → B4 参数/灰度**。B1/B2 代码可并行，互操验收依赖双方就位。

### 5.1 stand 引擎（本仓）

| 落点 | 改动 |
|---|---|
| `src/main/scala/nebflow/neblink/PortalWireCodec.scala` | 帧清单 +`ws_data_frame`（:59-73）；新增 case class `WsDataFrame(requestId, seq, more, kind, enc, data)`；decodeBody/encode 对称分支（:183-323 / :350-458）；`enc` 枚举校验（复用 `jsonEnum`） |
| `PortalWsMessageAssembler.scala` | **不动**（本地 loopback 整消息面照旧，cap 语义原样） |
| `PortalTunnelBridge.scala` 上行 | `LocalWsListener.received`（:666-670）：整消息到手后按能力改道——portal-v2 ⇒ gzip 整消息 → `shardBytes` 切片 → 循环 `sender.send(WsDataFrame(…))`（每片独立过 `RelayFrameSender`，bulk 车道）；非 v2 ⇒ 原 `ws_frame` 路径逐字不变。新单点函数（如 `encodeWsUplink(caps, requestId, kind, bytes): Vector[String]`）承载「整帧 or 分片」判定，spec 直测 |
| `PortalTunnelBridge.scala` 下行 | `feedWsFrame`（:583-590）+ `WsCycle`（:1188-1211）：新增 per-cycle 重组槽（`WsReasmBuffer`：seq 期望值、enc 钉定、累计字节 cap、完成回调）——`ws_data_frame` 进槽、`more=false` 完成后组装原字节按既有 `toLocal.offer(DownlinkItem.Frame(...))` 投递；violation ⇒ `dropWsCycle(cycle, reason)` 既有丢环函数 |
| `NeblinkRelayTunnel.scala` | `connectOnce` 的 builder 追加 subprotocol offer（:764-767）；升级完成后读 `getSubprotocol()` 写入连接能力位；契约帧分派面 `handleFrame` 新增 `WsDataFrame` 分支（proto 前置校验 :1945-1946 已有，加帧族分支） |
| 常量 | `PortalTunnelBridge.WsShardBytes`（默认 256KiB，env 可覆写）；能力位布尔；`ControlLaneMaxChars/ControlGrantCap/SendTimeoutSec` 不动 |
| spec（新增/扩展） | `PortalWireCodecConformanceSpec`（v1.3 向量）；`PortalTunnelBridgeSpec` 增：上行切片序/fin 单点/超 cap 仍 1009/非 v2 回落逐字不变；下行重组 spec（序、violation 丢环、enc 混值丢环、超 cap 丢环、跨 cycle 不互串）；`RelayFrameSenderPrioritySpec` 回归（分片帧全 bulk 车道断言）；`NeblinkRelayFrameSenderSendCycleSpec` 回归 |

### 5.2 link-server（relay）

| 落点 | 改动 |
|---|---|
| `src/portal_wire.rs` | `PortalFrame` enum + `WsDataFrame`；`decode/encode` 对称分支（struct 字段声明序=契约序，模块注释 :8-13 的纪律）；`SUPPORTED_PROTO` 不动 |
| `src/relay.rs` | 读循环 ：1834-1884：`NewFrame` 分支自动覆盖新类型（probe→decode→dispatch，**大概率零改**，互操 spec 钉）；写面 `OutboundFrame::Text` 照载（:1776-1789 零改）；`TunnelLiveState` + portal-v2 能力位；`negotiate_portal_caps` 新函数（同 `negotiate_wire_mode` :4749 形） |
| `src/portal_ingress.rs` | `WsCycle` 增重组槽（`reasm: Option<WsReasm>`：期望 seq、enc、`Vec<u8>` 累计、cap 常量）；`dispatch_uplink` 的 `F::WsDataFrame` 分支：进槽 / 完成时以**重组后整消息**走既有 `to_browser.try_send(WsMsg::Frame{…fin:true})` 路径（:644-667 语义复用）；violation ⇒ `fail_cycle(1011)` + 新计数器 `T3_WS_REASM_DROPS_TOTAL` 族 |
| `src/routes.rs` | 隧道升级 handler（:4704-4737 区）：caps 协商调用与 echo；`console_ws_pump` 出向（:3727-3750）：阶段二加「对 v2 隧道整消息→`ws_data_frame` 切片」分支（默认关，env）；`ingress_body_framer` 不动 |
| 常量/env | `PORTAL_WS_SHARD_BYTES`（下行切片用，默认 256KiB）、`PORTAL_WS_REASM_MAX_BYTES`（默认 4MiB）、`NEBLINK_PORTAL_V2_ENABLED`、per-device allowlist（沿用 `relay_limit` 机制） |
| test | `portal_wire_test.rs`（v1.3 向量一致性）；`portal_ingress_test.rs`（重组序/violation/超 cap/enc 混值）；既有真 socket harness（relay_ws_loop 测试基建）互操三格：新×新 / 新×旧 / 旧×新 |

### 5.3 stand-website（门户站）

**零改动**（已核实不在数据路径，§1.1）。验收只需一条：回归其既有 e2e 不受影响（与本项目无交集，联调清单不排期）。

### 5.4 浏览器控制台客户端（本仓 web/js）

**零协议改动**（relay 终止压缩并重组，浏览器只见整消息）。B3 批只做复验：ws.js pending 补发序（D1）、W13（D2）、心跳/重连与重组后流量的互动回归。

---

## 6. 测试计划与联调

### 6.1 契约层（双端一致性，沿用既有机制）

- v1.3 向量集：`ws_data_frame` 正向量（单帧/多帧/gzip/空终结帧）+ 负向量（seq<0、enc 未知、enc 混值、缺字段、坏 base64、unknown-proto 交互）——Scala `PortalWireContractV1Spec` / `PortalWireCodecConformanceSpec` 与 Rust `portal_wire_test.rs` 跑**同一**冻结文件，判定逐字节一致（既有 28-向量机制扩容）。
- 契约文件三处 byte-identical 分发检查入 B0 验收（现有先例：stand `src/test/resources/neblink/` ↔ link-server `.nebflow/Spec/`）。

### 6.2 单端 spec

- 引擎：§5.1 表列（切片序、回落逐字不变、重组 violation、1009 面原样、sender 车道回归）。
- relay：§5.2 表列（probe 兜底、fail_cycle 计数、caps 协商真 socket fixture）。

### 6.3 三端联调路径

1. 本机环回矩阵：本机起 link-server（`PORTAL_*` env 矩阵）+ 引擎以 NeblinkDiscovery endpoint 指向本机 relay + 浏览器开控制台；按 §4.3 矩阵逐格跑（能力开关四象限）。
2. 载荷注入：构造 4 MiB 级 console 大消息（UI 状态洪峰），对照组 v1 整帧 vs v2 分片+gzip，读 `RelayFrameSenderTimingHarness`（扩展：分片帧序 + 控制拍不回归断言）与 ingress `T1/T3` 计数器；读数仅作**对照证据**，不设时限判据。
3. 故障注入：重组中途断链（隧道 kill）、seq 回格注入、enc 混值注入、超 cap 注入——四路全部断言「丢环 + 计数 + 对端可读关闭」，无静默。

### 6.4 #949 死信/补投面复验清单（对 §1.5 五件逐项）

| face | 新格式下的复验断言 |
|---|---|
| D1 ws.js pending 补发 | 分片重组对浏览器透明 ⇒ 补发序=发送序、clientMessageId 幂等不重不丢；断言：v2 开关两态下 pending 补发行为逐字节一致 |
| D2 W13 待补队列 | 事件帧为 legacy JSON（不经契约）⇒ 纯回归 |
| D3 ingress cycle 丢弃语义 | 分片 violation ⇒ 仍走 `fail_cycle`（非新失败面）；`T1_BACKPRESSURE_DROPS` 读数在 v2 下语义不变（单位仍是 cycle）；新增重组计数器并在 `portal_metrics_summary` 挂行 |
| D4 引擎 1009 面 | 超 `MaxWsMessageBytes` 的 loopback 消息**仍** 1009 关环（分片发生在重组之后，cap 前置）；spec 断言 cap 前置序 |
| D5 legacy 重放+去重 | 全程不经契约面 ⇒ 纯回归；灰度各步跑一次好友/设备邮件回归组 |

### 6.5 既有回归面（防倒退）

- `RelayFrameSenderPrioritySpec` / `PrefixQueueOrderProbe`（双车道批件）：分片不改变车道判定与越队语义。
- `PortalTunnelBridgeSpec` + `HeartbeatSendSpec` + `SendCycleSpec`（稳定性批回归组 32 件）全绿。
- relay `portal_ingress_test.rs` / `portal_test.rs` / `relay_*_test.rs` 全绿；`NeblinkRelayTunnelFragReasmSpec`（RFC6455 层）不动全绿——两层重组互不干扰的证明。

---

## 7. 参数面联动（与 WsQueueSlots=64、MaxWsMessageBytes 下调的叠放）

| 参数 | 现状（已核实） | 叠放关系 |
|---|---|---|
| 引擎 `WsQueueSlots` | 128（:1099）；简报称已批下调 64（**待核实**，未落本 worktree） | 分片使「每消息帧数」×N：cycle inbox 与重组槽按**消息数**记账（重组槽是消息级单槽，不随片数膨胀）；WsQueueSlots 若下调，等同下调「在飞消息窗口」，与本批正交、无需联动改值 |
| 引擎 `MaxWsMessageBytes` | 4 MiB（:1101）；#955 配对批拟下调（**待核实**落点值） | 该 cap 是 **loopback 消息上限**，分片不解除（§4.4/D4）：超 cap 消息进不了分片面、照旧 1009。relay 侧 `PORTAL_WS_REASM_MAX_BYTES` 建议与其**取等**（一消息两 cap 夹逼会造成「relay 收齐了、引擎组装不下」的不对称丢环）；#955 定值后两处同批对齐 |
| 引擎 `ControlLaneMaxChars`=64KiB 字符 / `ControlGrantCap`=16 | :1077/:1084 | 256KiB raw 片 → base64 ≈349K 字符 = bulk 车道（与 body_frame 同档）；尾片落控制车道无害（既有类注同口径）。无需改值 |
| relay `NEBLINK_RELAY_OUTBOUND_QUEUE_DEPTH` | 默认 0=无界（relay.rs:1504-1508） | 分片后队列元素数×16（4MiB 消息）：B4 批给出生产建议值并入 A10 门（分片世界「无界」的峰值记忆 = 消息数×N 片，应顺手关掉无界档） |
| relay `to_browser`(64) / 引擎 cycle inbox(128/64) | routes.rs:3570 / :1099 | 记账单位保持在「消息」（重组后入队）⇒ 深度语义不变；这是把重组槽放 **cycle 内**而非 ingress 全局的理由之一 |
| relay ping 3s/3-miss、ingress 15s/60s/90s | relay.rs:1191-1192、ingress.rs:47-55 | 全部不动：分片让每帧写时间回落，控制帧预算自动回裕，不靠改超时救 |
| `PORTAL_BODY_CHUNK_BYTES`(256KiB) | ingress.rs:45 | 与 WS 面新常量分立（`PORTAL_WS_SHARD_BYTES`/`PORTAL_WS_SHARD_BYTES` 上行在引擎侧）：HTTP 面与 WS 面调参解耦，默认值对齐 |

---

## 8. 实施切分（可独立派发批）

| 批 | 仓 | 内容 | 依赖 | 验收 spec 定义（摘） |
|---|---|---|---|---|
| **B0 契约冻结批** | stand（契约文本+向量） | v1.3 契约稿：`ws_data_frame` 帧定义/帧清单 14/subprotocol token 定名/enc 枚举/拒收语义扩条；正向量+负向量；三处 byte-identical 分发 | 无 | ① 双端 conformance（Scala+Rust）对同一向量文件全绿；② 契约 diff 仅 additive（v1.2→v1.3 审读清单）；③ 帧清单/枚举值三处逐字节 diff-clean |
| **B1 relay 批** | link-server | probe/decode/dispatch 新帧分支、WsCycle 重组槽+计数器、caps 协商（env+allowlist，默认关）、（阶段二件同批实现默认关）下行切片 | B0 | ① 向量套件绿；② 重组序/violation 丢环/超 cap/enc 混值四组 spec 绿；③ 真 socket 互操三格（新×新/新×旧/旧×新）行为=§4.3 矩阵；④ 默认全关时既有套件全绿（`portal_ingress_test`/`relay_*`）=零漂移证明 |
| **B2 引擎批** | stand | `PortalWireCodec` 新帧；上行整消息 gzip+切片改道（非 v2 逐字原路）；下行重组槽；subprotocol offer+能力位；常量 | B0（互操验收需 B1） | ① `PortalWireCodecConformanceSpec` v1.3 绿；② 非 v2 路径逐字不变 spec（回落面 golden）；③ 切片序/单 fin 点/超 cap 1009 前置 spec；④ 下行重组四组 spec；⑤ sender 族回归（Priority/SendCycle/Heartbeat）全绿；⑥ 与 B1 的三端矩阵联调记录（§6.3） |
| **B3 前端复验批** | stand（web/js） | 零协议改动；§6.4 D1/D2 复验 + 心跳/重连回归 | B2 联调件 | ① `node tests/session-gate.spec.mjs` 等 ws.js 族全绿；② pending 补发两态一致性断言脚本；③ 手工矩阵一页记录 |
| **B4 参数+灰度批** | link-server + 运维 | env 定稿（shard/reasm/caps 开关/allowlist 名单）、`OUTBOUND_QUEUE_DEPTH` 生产建议、灰度与回退 runbook（§4.5 顺序落文） | B1、B2 | ① 灰度四步各含「回退=env/摘名单」演练记录；② 参数对照表（§7）入 runbook；③ 计数器读数面（`portal_metrics_summary` 扩行）上线可读 |

---

## 9. 边界（本批明确不做）

1. 不写任何实现代码（本稿为设计件；各批代码改动以 §5 落点表为派发口径）。
2. `relay_response`/工具面（32MiB v2 binary 面、单帧 output string）分片另案，不与本批混线。
3. permessage-deflate 不立项（§3.2 论证）；zstd 只留枚举位。
4. 迁移面（MigrationChunk，phase 5 PLACEHOLDER，两侧均显式 drop）不动。
5. 浏览器协议面零改动；stand-website 零改动。
6. HTTP face（`body_frame` 256KiB 链）不动——它是本设计的对照面与纪律来源，不是改造对象。
7. 全文无时限承诺：所有以秒计的数字均为现状实现常量引用（标行号）或 #949 诊断既有结论引用，非本设计的验收判据；实现批验收一律用「序/语义/计数器」判据。

## 10. 待核实清单（派发实现批前须补证）

| # | 事项 | 补证路径 |
|---|---|---|
| Q1 | #949 台账原文与「死信/补投」确切指涉（本稿按 §1.5 五件全数复验兜底） | 作者台账 |
| Q2 | #955 配对批的 `MaxWsMessageBytes` 定值与 `WsQueueSlots=64` 是否已落 | 作者台账 / main |
| Q3 | JDK WebSocket 无扩展 API（permessage-deflate 不可协商）的 javadoc 口径落文 | JDK javadoc `java.net.http.WebSocket` |
| Q4 | axum 0.8.9 对「client offer 但服务端未 echo subprotocol」的升级放行行为 | 真 socket fixture（B1 批件①前置小验） |
| Q5 | axum/tungstenite 默认 `max_message_size/max_frame_size`（64MiB/16MiB 口碑）实测值 | B1 批 fixture 读数 |
| Q6 | 下行载荷分布（浏览器→引擎消息尺寸谱）——决定阶段二开启收益 | B3/B4 批计数器（按消息尺寸分桶一行日志，临时诊断件） |
| Q7 | `neblink-server` 旧副本与本设计的同步义务（是否随 B0 分发第 4 处，或宣告弃用） | 作者裁定 |
