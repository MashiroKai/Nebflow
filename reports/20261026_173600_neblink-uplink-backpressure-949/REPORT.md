# REPORT · 台账 #949 — neblink 上行背压刷屏（inbox full 死循环）修复

- 批次：engine fix · nebflow.neblink.relay 上行路径（KAI 设备，host 任务 #949）
- 日期：2026-10-26
- 现象（作者 17:10 微信报）：Mac 引擎 stand.log 被 `WARN nebflow.neblink.relay - portal uplink inbox full: requestId=<固定 2-3 个 id> — uplink item dropped` 刷屏，17:10–17:13 近 2000 行，每秒数条，同因循环无退避无去重。
- 红线遵守：零 wall-clock（本批零新增超时/截止，全部拍计数与事件驱动）；引擎未重启；web/css/js、MergeQueue/MergeArtifactCare、DeviceProvenance 面零接触；#944（3cdcee481）改的 NeblinkRelayTunnel outboxDrain 腿原样保留、本批未触碰 NeblinkRelayTunnel.scala。

## 1. 定因（代码级，机制链完整）

刷屏点在 `PortalTunnelBridge.LocalWsListener.offerUp`（修前 :707-711）：本地 loopback peer（浏览器经 portal 桥的 WS 周期）灌帧速率 >> relay 上行泵消化速率时，128 槽 uplink inbox（`WsQueueSlots`）常满，**每一帧入队失败 = 丢一件 + 打一条 WARN**——无同因去重、无退避；丢件后 JDK `request(1)` 照常放行下一条，本地继续灌 ⇒ 死循环。三个机械根：

1. **上行泵单纤维单帧串行**（`upPumpLoop` → `RelayFrameSender.send` 逐帧等写完），吞吐 = 单 TCP 流上行带宽；2-3 个浏览器周期共享同一个 socket 单发位锁（fair 锁逐帧轮转），互相顶头——这就是「固定同样 2-3 个 requestId 同时循环」的原因。
2. **WS 面无分片无压缩**：HTTP 面有 256 KiB body_frame 分片，WS 面整帧上行（单帧上限 `MaxWsMessageBytes`=4 MiB，base64 后 ~5.6 MB 文本/帧）；UI 事件大快照一类载荷在 Mbps 级上行下每帧写完需 10–45s，inbox 必然积满。
3. **满态处置只有「丢」**：丢件对浏览器流是永久缺帧（静默数据丢失，不只是日志问题），且 WARN 无限频——「一行×每秒」。

KAI 本机日志核查：`~/.stand/logs/nebflow.log`（含轮转）0 条该 WARN——病灶在 Mac 侧（其对端上行带宽/拓扑与 KAI 不同），本批读数以代码常量 + 新增自报读数为准。

## 2. 修法（三件）

### 面一 · 同因限频（止血）— `UplinkFullAlarm`（PortalUplinkBackpressure.scala）

- 每 requestId（=每 WS 周期）一个报警器：满态第 1 条即报（状态变化），之后**每 32 次事件报 1 条**（`FullWarnEveryN=32`，事件计数非时钟），其余进 `suppressed` 读数；
- 恢复（拿到容量）即报一条带 `episode / n strikes / suppressed` 的恢复行——状态变化即报，根治「一行×每秒」（同因 2000 行 → 首条+每 32 条+恢复条，~63 条封顶）；
- WARN 行自带读数：`strike= episode= depth=D/128 lastItemBytes= suppressed=`——下次复现第一现场自带病理读数（服务面三）。

### 面二 · 排队等容量 + 拍退避 + 死信区（病理根治）— `offerUplinkAwaitCapacity` + `UplinkBackoff` + `PortalUplinkDeadLetter`

- **入队改「排队等容量」**：满不再丢——拍退避停等重试（第 r 轮停 `min(2^(r-1), 16)` 拍，1 拍=25ms 与 `RelayFrameSender.PendingRetryMillis` 同拍长；封顶 400ms 保 selector 线程让位）；**零截止时刻**，退出只认两个事件：拿到容量 / 周期死亡；
- **真背压**：`LocalWsListener.received` 的 `request(1)` 天然后置（件收下才放行下一条）——本地 peer 第一次被端到端背压，浏览器侧变慢而不是本端丢件（宁重勿丢，#944 同语义）；每个本地 ws 周期是独立 HttpClient（`connectLocalWs` 每周期新建），停等只停本周期，不碰隧道心跳/他周期；
- **确需丢弃的进持久死信区**：等容量期间周期死亡（pump 死/被摘）、或连接收尾（teardown）时仍在 inbox 排队的件 ⇒ 整帧原文（`PortalWireCodec.encode` 产物）落盘 `<dataRoot>/portal-uplink-deadletter/<enqueuedAt>-<id>.json`（AtomicJson 整写，带 source=requestId/kind/bytes/reason），**永不静默消失**；写失败只 WARN 不抛（#944 簿记纪律）；
- **恢复补投**：`PortalConnection.armLifecycle` 在隧道（重）连上第一刻 fire-and-forget `bridge.replayDeadLetters`——按入队序经当前连接 sender 补投，成功出队、失败留盘等下一轮（#944 outboxDrain 同款装配位，零墙钟、无重试节拍）；at-least-once，对面会话已死则由 ingress 丢弃。

### 面三 · 为何慢（读数与结论，只报不改默认值）

| 因子 | 读数 | 判定 |
|---|---|---|
| 单帧尺寸 | WS 面 0 分片，单帧上限 4 MiB（base64 ~5.6 MB）；HTTP 面有 256 KiB 分片而 WS 面没有 | **主因**。UI 事件流大 JSON 在慢上行上每帧 10–45s，泵必积压 |
| 并发/窗口 | 上行泵每周期 1 纤维逐帧等写完（JDK 单发位契约，无法并行）；跨周期 fair 锁 bulk↔bulk 轮转；`WsQueueSlots`=128 槽 | 结构性（不可并行），非参数过小；128 槽偏大——虚高积压且放大死信体积，建议 32–64（等作者裁，未改） |
| 对面门户 | 不在本仓，无直接读数；客户端证据（本端 inbox 常满+丢件）说明第一卡点是**本端出不去**（上 WAN 带宽×大帧），非对面拒收 | 次要嫌疑，待新读数（WARN 行 lastItemBytes/depth）复现实测 |

建议（只报不改）：① WS 面比照 HTTP 面做分片/压缩（JSON 事件典型 5–20×，需 ingress 配合的协议面，跨端批另立）；② 纯本端可做：下调 `MaxWsMessageBytes`（超限帧本地 1009，逼本地 app 分片）——改行为面，等作者裁；③ `WsQueueSlots` 128→32-64。本批已交付的 request(1) 真背压本身就是把「不限速+丢件」换成「限速+不丢」的结构解。

## 3. 触碰面（与在飞批避让核对）

- 改：`src/main/scala/nebflow/neblink/PortalTunnelBridge.scala`（offerUp→排队等容量、WsCycle 死信投影、teardown 残件落盘、armLifecycle 补投钩子；PortalConnection 内，#944 的 NeblinkRelayTunnel outboxDrain 零接触）
- 改：`src/main/scala/nebflow/neblink/PortalUplinkBackpressure.scala`（新增：限频器/拍退避/死信区）
- 新增 spec：`src/test/scala/nebflow/neblink/PortalUplinkBackpressureSpec.scala`
- 零接触：web/css/js、MergeQueue/MergeArtifactCare、DeviceProvenance*、NeblinkRelayTunnel.scala

## 4. 验证（见 §6 执行记录）

新 spec **10/10 绿**：同因限频断言（首条/每32/恢复即报/新回合重起算）+ inbox full 不丢件两态（等容量入队保序 / 周期死亡死信落盘）+ 死信迁移两态（补投成功出队/失败留盘+attempts）+ teardown 全量落盘保序 + 拍退避翻倍封顶 + replay 走 sender。

relay 族回归 **158/158 绿（0 失败）**——8 个 suite 覆盖本批全部触碰面（bridge 生命周期/teardown、wire codec 契约、ws 组包、上行 sender 周期与优先级、portal 前门校验）。前任中途报告的「82 绿基线」是其会话早段的部分读数、精确 suite 构成未及落笔（§6 为空即中断点）；本批以 8-suite 全家 158 读数为准（超集，含新 spec 10）。

## 5. 开口项

- 参数类建议（WsQueueSlots/MaxWsMessageBytes/WS 分片压缩）只报未改，等作者裁（§2 面三）。
- 测试批执行时共享工作树上有他批在飞（DeviceProvenance #926 族 spec 编译腿），见 §6 说明。

## 6. 执行记录

执行者：delegate stand（KAI 设备，2026-10-26 20:16–20:25 +08:00）。零 wall-clock 超时引入；引擎未重启。

1. **单跑新 spec**（sbt 内存 set 排除他批测试源 `DeviceProvenance*`/`DeviceStampAutoTagging*`，`root/testOnly nebflow.neblink.PortalUplinkBackpressureSpec`）：
   `Passed: Total 10, Failed 0, Errors 0, Passed 10`（5s，含主编译全量绿——他批 DeviceProvenance.scala 主面可编译，测试损坏面仅其 spec）。**零实现改动**：前任修桩后的 spec 与实现语义一致，首跑即全绿。
2. **relay 族回归**（同一 set 过滤，`testOnly` 8 suite）：PortalTunnelBridgeSpec、PortalUplinkBackpressureSpec、PortalWireCodecConformanceSpec、PortalWireContractV1Spec、PortalWsMessageAssemblerSpec、NeblinkRelayFrameSenderSendCycleSpec、RelayFrameSenderPrioritySpec、PortalProtoFrontCheckSpec →
   `Passed: Total 158, Failed 0, Errors 0, Passed 158`（18s）。PortalTunnelBridgeSpec 既有 27 例在「offerUp 改排队等容量」后原样通过——旧行为断言未被本批破坏。
3. **三件验收对账**：① 同因限频=spec 面一 2 例（首条/每32/恢复/新回合）✔；② 退避+死信落盘不丢=spec 面二 7 例（等容量 FIFO 保序、周期死亡死信、teardown 全量落盘、两态迁移、严格按序、sender 补投、空区 no-op）✔，运行日志实证 20 位零填充 seq（`file=…-00000000000000000008-req-order.json`）；③ relay 慢根因诊断=§2 面三（WS 面零分片大帧为主因，结构性单发位锁不可并行；参数建议只报未改）✔。
4. **stage 纪律**：仅 5 文件入册（新实现+新 spec+两处改动+本报告）；`DeviceProvenance*`、web/css/js、evidence/、reports-kai/、他批 reports/ 目录零接触。

原始运行日志：`reports-kai/.sbt-bp-spec-run.log`、`reports-kai/.sbt-relay-regression.log`（工作区未跟踪，不入册）。
