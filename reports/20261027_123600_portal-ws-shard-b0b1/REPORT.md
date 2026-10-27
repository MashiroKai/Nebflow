# 批 G 收口报告：引擎 WS 面分片+压缩实现批 B0+B1（台账 #957/#958/#955）

分支 `delegate/delegate-nebflow-86f1ae41`，两段式提交，未触碰 main：

- **B0** = `b5e93cb2a`「portal v1.3 B0: ws_data_frame wire contract + downlink reassembly slot」
- **B1** = `1049153d8`「portal v1.3 B1: ws uplink sharding + neblink.portal.v2 negotiation」

## 1. 交付面

### B0（契约 + 下行重组槽）
- `src/test/resources/neblink/portal-wire-contract-v1.3.md`：自 v1.2 additive 重冻结——§2.14 ws_data_frame（seq 全周期严格 +1 不按消息重置；enc 枚举 none|gzip；流级违规丢整个 cycle；无独立重组计时器=周期撕除随 cycle 对象消亡）、§2.15 协商 `neblink.portal.v2`、§6 向量口径 40+10=50、帧清单 14、§8 PMCE 拒绝/zstd 留位。
- `src/test/resources/neblink/portal-wire-vectors-v1.3.json`：v1.1 的 40 条保序保留 + ws_data_frame 正 4（单帧 text/binary、空终结帧、gzip 帧）负 6（seq<0、enc 未知、缺 enc、缺 more、坏 base64、kind 非法）。
- `PortalWireCodec.scala`：FrameInventory 14、`PortalV2Subprotocol`、`WsDataFrame(requestId,seq,more,kind,enc,data)` decode/encode 对称分支（契约逐字段拒绝码）。
- 两契约 spec（模型 face + 生产 face）切 v1.3 资源，同 50 向量 byte-exact 交叉对账。
- `PortalTunnelBridge.scala`：`WsReasmBuffer`（per-cycle `WsCycle.reasm`；expectedSeq 全周期单调；enc/kind 首片钉定；cap=MaxWsMessageBytes 与 #955 取等；enc=gzip 完成即 Left 拒绝——fail-closed 等压缩批）+ `feedWsDataFrame`（无 cycle WARN；violation→dropWsCycle；完成→toLocal 整消息）。
- `NeblinkRelayTunnel.scala`：`ws_data_frame` 进契约帧族分诊。

### B1（上行分片 + 协商）
- `PortalTunnelBridge.scala`：`portalV2` 连接能力位（升级时判定一次）；portal-v2 周期 listener 产消息级 `Sharded` 件 → uplink pump 在发送位上 `encodeWsUplink` 逐片发送（单 fiber 保序=seq 序）；`encodeWsUplink` 纯函数（v2：seq 0..n-1/末片 more=false/enc=none/空消息单条空终结帧/恰整除无空尾片；v1：单条 ws_frame fin=true 字节级原路 golden）；`WsShardBytes` 默认 256KiB（对齐 body_frame），env `NEBLINK_WS_SHARD_BYTES` 钳位 [1KiB, 4MiB]；满片 base64 ≈349K 字符恒走 bulk 车道。
- `NeblinkRelayTunnel.scala`：升级 offer `neblink.portal.v2`（总闸 `NEBLINK_PORTAL_V2=0/false/off` 关，def 每连接现读）；对端未 echo ⇒ v1 整帧零漂移（协商失败不破链）；连接日志带 `portalV2=`。
- 测试：`PortalWsShardSpec.scala` 17 测（encodeWsUplink 纯函数组 6、WsReasmBuffer 组 5、桥面真 loopback 4、两端口真 socket round-trip 2）。

## 2. 测试读数（全绿）

| 面 | 读数 |
|---|---|
| B0 中间态（reasm 半 + 回归 8 类 + 双契约 spec） | 208/208 |
| B1 全量（同集 + B1 10 测） | 218/218（`PortalWsShardSpec` 17/17） |
| 其中双契约 spec（50 向量 × 双 face byte-exact） | 115 断言面全绿 |
| web 双锚（`node tests/resend-dedup.spec.mjs` / `session-gate.spec.mjs`，独立 node 脚本直跑） | 18/18 ALL PASS + 23 passed 0 failed |

## 3. 两端口配对自测说明（完成标准「本机起两端口对发大帧验证重组」）

`roundTrip`（真 socket ×2，无任何引擎改动替身化的路径）：
- **端口 R（隧道面）**：`PairingRelay` 原始 socket 服务端，echo `neblink.portal.v2`（协商格）+ 帧回授 + 记录引擎上行帧原文。
- **端口 B（loopback 面）**：`LoopbackCapture` 原始 socket 服务端，升级后写下发大消息，记录引擎回送的重组后整消息。
- 测试客户端 = 真 JDK WebSocket（恒 offer subprotocol，listener 带 RFC 分帧重组——生产 `RelayWsListener.FrameReassembler` 的同款义务）。
- **协商链**：1.3MB 消息 → 引擎上行 5 片 ws_data_frame（seq 0..4、末片 more=false）→ R 记录并原样回授 → 桥下行重组 → B 收到逐字重组件，`Arrays.equals` 字节级断言。
- **回落链**：不 echo ⇒ 18KB 消息以**单条** v1 ws_frame 原路上行（零 ws_data_frame 混流），回授后 B 整消息收到。
- 调试期间钉出的实现事实（对后续批有用）：JDK `sendText` 对 ≥16KB 消息自行分片成 16KB+余量的 WS fragment 帧；生产 FrameReassembler 侧无此问题（onText(last) 语义），但任何 raw-socket 测试替身的重组器必须只在低 4 位非零的帧上钉 opcode——本次两替身最初都在 continuation 帧（首字节 0x80）上把 opcode 覆写成 0，静默丢整消息（已在 `PairingRelay`/`LoopbackCapture` 修正并留诊断读数 framesRead/serveError/frameTrace）。

## 4. 三处裁定

1. **web 侧「发送端同步改」：不改。** 任务行「配套 #955 的前端 chunked 发送配对改造（web 侧发送端同步改）」与设计稿 §5.4/§9.5 明钉「浏览器零协议改动」冲突；仓内无 #955 落地件佐证。按设计稿执行：web/js 零 diff，双锚 41 测复跑全绿佐证行为面无漂移。#955 定值问题移交 B4（见 §6）。
2. **MaxWsMessageBytes 保持 4MiB 不动。** `PortalTunnelBridge.MaxWsMessageBytes` 现值 4MiB；#955 的定值不在仓内（设计稿标待核实）。重组槽 cap 取同常量**取等**（B0 代码注释已钉配对关系）；#955 若改值，reasm cap 与 loopback cap 必须同源同改——已列入 B4。
3. **「超时清理」= 周期撕除，无新计时器。** 设计稿 §4.4 明钉不新增计时器；重组槽生命周期随 WsCycle 消亡（teardown/dropWsCycle 摘除即整体回收），无独立 timer。与任务措辞「超时清理」的差异按设计稿裁定。

## 5. 红线自查

不重启引擎 ✓（全程纯 sbt 测试进程）；不动 presence/conn-guard 面 ✓（diff 仅三主文件+两契约 spec+两资源）；无 sleep/轮询等待实现面 ✓（测试里的 `waitUntil` 有界轮询与 `IO.sleep` 均为既有测试机械；引擎代码零新增轮询）。

## 6. B2-B4 后续派发建议

- **B2（压缩批）**：enc=gzip 启用——引擎上行发送位 gzip + 下行重组槽解压（B0 已留 enc 枚举与 fail-closed 拒绝，拒收向量已在册）；契约补 gzip 正向量与「解压超限」负向量；建议同批钉「压缩后分片边界仍按 raw 字节」与压缩级别为实现常量。
- **B3（link-server relay 批）**：`portal-wire-contract-v1.3.md` + 向量 JSON 以 **byte-identical** 副本分发到 relay 仓并跑同款 conformance（本批双 face 交叉对账机制已就绪）；需钉死 relay 对 axum 未 echo subprotocol 时的现行为（Q4：echo 空 vs 501，影响协商矩阵的「未放行」格）。
- **B4（参数/灰度批）**：#955 定值核实后 MaxWsMessageBytes/`PORTAL_WS_REASM_MAX_BYTES` 取等改值（如改，reasm cap 与 loopback cap 必须同源）；`NEBLINK_PORTAL_V2` allowlist 灰度（按 friend/device 白名单 offer）；回退 runbook（env 关 = 即时回 v1 整帧，零漂移已验证）+ 引擎不重启热翻面评估（现 def 每连接现读，重连即生效）。
