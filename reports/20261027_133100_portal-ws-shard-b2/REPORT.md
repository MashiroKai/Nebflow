# 批收口报告：引擎 WS 面压缩批 B2（台账 #957/#958/#955 续）

分支 `delegate/delegate-nebflow-8fc878c1`，代码一提交 + 报告一提交，未触碰 main：

- **B2** = `ecadba91e`「portal v1.3 B2: ws_data_frame whole-message gzip (worth-gated uplink + decode-at-completion downlink)」
- 前序：B0 = `b5e93cb2a`、B1 = `1049153d8`（均已落 main，见 `reports/20261027_123600_portal-ws-shard-b0b1/`）

## 1. 交付面

diff 仅两文件：`PortalTunnelBridge.scala`（+506/−184 与 spec 合计）+ `PortalWsShardSpec.scala`；**契约文件与向量 JSON 零改动**、`NeblinkRelayTunnel.scala` 零改动（协商面原样）。

- `encodeWsUplink`（发送位，PortalTunnelBridge.scala:1518）：**先整消息压缩判定、后按 raw 字节切片**（契约 2.14「whole-message transform first, byte-slicing second」）。raw > `WsCompressMinBytes` **且** gzip 流**严格更小** ⇒ enc=gzip，切片作用于 gzip 流（分片边界与编码成员边界无关）；小帧 / 不可压（worth 门）⇒ enc=none 原字节——不可压载荷 wire 字节不增。非 v2 回落面恒单条 v1 `ws_frame`，零 gzip 暴露（测试钉「回落帧文本零 gzip 字样」；契约 2.14：enc 只存在于 ws_data_frame 族内）。
- `WsCompressMinBytes`（:1314）：默认 256KiB，与 `WsShardBytes` **同源同值**（body_frame 口径）；env `NEBLINK_WS_COMPRESS_MIN_BYTES` 覆写，钳位 [1KiB, MaxWsMessageBytes]。**新增 env 为 additive**，既有 env 语义零变化。
- `gzipBytes`/`gunzipBytes`（:1552/:1566）：JDK `GZIPOutputStream`/`GZIPInputStream`，零新依赖；压缩级别为实现常量非契约值。
- `WsReasmBuffer` 完成位（:1632-1638）：B0 的 fail-closed 占位（「enc=gzip 到齐即拒绝」）替换为**重组完成处一次解压**（绝不逐帧）；坏流（非魔数 / deflate 损坏 / CRC 尾码错 / 截断）或解压输出超 cap ⇒ Left = violation ⇒ **丢整个 cycle**——沿袭 B0 纪律，绝不把损坏字节或未钳制载荷投给本地 face。
- gzip 炸弹防线：解压 cap = 重组 cap = `MaxWsMessageBytes`（与 #955 的 loopback cap 取等关系保持，两面同钳——wire 字节受重组 cap 钳而解压字节不受的 asymmetry 被封死）。

## 2. 测试读数（全绿）

回归集（精确口径，见 §3）：**12 类 224/224**，跑在格式化终态树上（sbt `testOnly`，2026-10-27 13:29，job 日志 `Passed: Total 224, Failed 0, Errors 0`）。

| 面 | 读数 |
|---|---|
| `PortalWsShardSpec`（本批主 spec） | **22/22**（B1 时 17；占位测退役 −1、压缩腿 +6：worth 门 / gzip raw 切片 / 完成处一次解压往返 / 坏流三面 fail-closed / 解压超 cap 炸弹防线 / 两端口真 socket 压缩 round-trip） |
| `PortalTunnelBridgeSpec`（回归） | 27/27 |
| `PortalWireCodecConformanceSpec`（生产 face，50 向量 + 结构面） | 59/59 |
| `PortalWireContractV1Spec`（模型 face，50 向量 + 结构面） | 56/56 |
| `PortalUplinkBackpressureSpec` | 10/10 |
| `PortalProtoFrontCheckSpec` | 7/7 |
| `PortalWsMessageAssemblerSpec` | 5/5 |
| `BindConfirmUplinkSpec` | 4/4 |
| `NeblinkRelayFrameSenderSendCycleSpec` | 11/11 |
| `NeblinkRelayTunnelHeartbeatSendSpec` | 11/11 |
| `NeblinkRelayTunnelFragReasmSpec` | 11/11 |
| `RelayFrameSenderTimingHarness` | 1/1 |

### 新增压缩测试读数（判据载荷即测量，同 JVM jshell 复测口径一致）

| 载荷（确定性） | raw | gzip 流 | 压缩比 | 断言结果 |
|---|---|---|---|---|
| randomLetters 3×256KiB（seed 7） | 786,432 B | 499,813 B | **0.636×** | enc=gzip 多片，gunzip 往返字节级 ✓ |
| randomBytes 2×256KiB+1234（seed 42，高熵） | 525,522 B | 525,705 B | gz ≥ raw | **worth 门保持 enc=none**，wire 不增字节 ✓ |
| randomLetters 5×256KiB（真 socket round-trip） | 1,310,720 B | 833,114 B | **0.636×** | 4 片 gzip 流过真隧道，loopback 面收到解压后整消息 ✓ |
| 64KiB 全零（炸弹判据载荷） | 65,536 B | **96 B** | ≈0.0015× | wire 90B 级 << cap，解压超 1KiB cap ⇒ violation ✓ |
| 不可压 5×256KiB round-trip（binary face） | 1,310,720 B | — | 不压 | 恰 5 片 enc=none，binary face 重组字节级 ✓ |

锚定面（压缩批之前的既有判据全部保持绿）：非协商回落 = 单条 v1 `ws_frame` 原路（18KB 消息）；空消息 = 单条空终结帧 enc=none；恰整除无空尾片；满片 base64 ≈349K 字符恒 bulk 车道。

## 3. 回归集组装口径（精确集，计数来源 = 逐类 census + 逐类复跑）

- **直接受影响面（改动符号的编译级依赖）**：`PortalTunnelBridge|portalV2|WsShardBytes|WsReasmBuffer|WsCycle|WsOpen` 全仓 spec 引用扫描 → 5 类（census 计数 22+27+59+56+10；其中双契约 spec 的 59/56 为向量生成面，源码 `test(` 声明 11/8）。
- **B1 回归 8 类复刻**：B0/B1 收口报告的 218 = 8 类 86（Bridge 27 + Backpressure 10 + FrontCheck 7 + FragReasm 11 + HeartbeatSend 11 + SendCycle 11 + Assembler 5 + BindConfirm 4）+ 双契约 115 + ShardSpec 17，逐类计数精确对账 ✓；本批 12 类 = 该 8 类 + 双契约 + ShardSpec + census 集里的 `RelayFrameSenderTimingHarness`（发送位 harness 探针，恰在本批改动的发送面上）+ `PortalProtoFrontCheckSpec`（B1 集成员）。
- census job（3732a8d0，sbt 单会话逐类计数）11 类计数 22/27/4/11/11/11/1/10/5/59/56 与复跑逐类读数一致（census 时 ShardSpec 已是 22 = B2 工作树态）。

## 4. 与 B0/B1 的兼容说明

- **B0（契约 + 下行重组槽）**：契约 md 与向量 JSON **字节零改动**；双契约 spec 115 向量面全绿。唯一行为替换 = B0 显式留位的「enc=gzip 完成即拒绝」→「完成处一次解压」——B0 的 fail-closed 语义（绝不投未解码/损坏字节）以 violation 丢环形式完整保留，且有坏流三面（魔数/截断/CRC）+ 超 cap 四条负测钉死。50 向量负集（含 enc 未知、缺 enc 等）原样绿。
- **B1（上行分片 + 协商）**：`NeblinkRelayTunnel.scala` 零 diff ⇒ 协商 offer/回退/`NEBLINK_PORTAL_V2` 总闸面原样；`NEBLINK_WS_SHARD_BYTES` 语义不变，新增 `NEBLINK_WS_COMPRESS_MIN_BYTES` 纯 additive。不可压载荷的 wire 与 B1 **字节级全等**（同 seq/slice 结构，worth 门只把「会更小」的载荷换流）；回落面 golden 不变且新增「零 gzip 字样」断言。默认阈值 256KiB 与分片阈值同值 ⇒ 默认态下「压不压」与「切不切」同刻度。
- **跨批配对关系**：重组 cap = 解压 cap = `MaxWsMessageBytes`（#955 取等约束在压缩批保持；#955 未来改值时三处同源同改的 B4 待办不变）。

## 5. 环境事实（非本批引入，如实记载）

- `sbt scalafmtCheckAll` 在 HEAD 仓库级失败（182 文件，含与本批无关的 social/outbound、core/* 等已提交文件）；本批两文件经 `scalafmtOnly` 三遍迭代至**格式不动点**（第三遍 0 改动，`git diff` shasum 稳定），并以格式化终态树复跑回归全绿。
- `sbt "scalafix --check"` 仓库级失败（同一批既有漂移）；归因核对：`PortalWsShardSpec` 零违例；`PortalTunnelBridge` 的唯一期望块落在其**既有 import 区（:5-22，B2 未触碰行）**——本批 diff 未新增任何违例面。仓库级修复不属于本批范围（会造 180 文件无关 diff）。

## 6. 红线自查

不重启引擎 ✓（纯 sbt 测试进程）；不动 presence/conn-guard 面 ✓（diff 仅桥一主文件 + 一 spec）；无新增轮询/计时器 ✓（解压在完成位同步发生，坏流归 violation 丢环，无新 timer）。

## 7. B3/B4 后续派发建议（沿 B0+B1 报告 §6 不变，压缩批无新增待办）

- B3：契约 + 向量 byte-identical 分发 relay 仓跑同款 conformance（gzip 正向量已在册，relay 侧解压面届时对齐）。
- B4：#955 定值核实后三 cap 同源同改；`NEBLINK_PORTAL_V2` 灰度 allowlist；回退 runbook 不变（env 关 = 回 v1 整帧）。
