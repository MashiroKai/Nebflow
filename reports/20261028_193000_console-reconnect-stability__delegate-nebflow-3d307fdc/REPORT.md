# 控制台/门户「连接中断·正在重连」频发根因排查+修复

批次：console-reconnect-stability · 2026-10-28 · 作者报障（17:4x「经常会突然显示连接中断 正在重连」）
引擎：pid 70481/6138 在产零触碰（排查期只读 + 离线测试复现）；修复随下一波包生效。

## 一、断因桶计数（2026-10-28 09:37 引擎启动 → 17:53，stand.log + 18:40 `/api/health/conn` 活读数）

| # | 桶 | 计数/日 | 时间分布 | 与 banner 的关系 |
|---|---|---|---|---|
| B1 | **portal 上行拥塞**（`inbox full` 64/64，relay 慢于本机对端） | **3,967 回合** | 09h:436 / 10h:310 / 11h:122 / 12h:12 / 14h:84 / **15h:605 / 16h:2,190** / 17h:208 | ⭐主打：pong 排队饥饿 ⇒ 前端 3-miss 自逐出 |
| B2 | **控制台连接churn**（`WebSocket client connected`，含本机直连+门户桥接回环会话） | **47 次** | 10h:14、17h:8；短连接簇 15:54–16:12 每 2–4 分钟一条（与 B1/B4 峰值同窗） | banner 的直接呈现面（每次重连必经此帧） |
| B3 | **relay 隧道硬断** | **4 次** | 09:39:16（120s 短命）、10:08:59（1782s）、10:18:44（584s，伴 2 次 hb timeout 判僵尸）、**17:44:22（26737s）** | 17:44 命中报障窗口；每次断=全部 portal 会话同掉 |
| B4 | **tunnel-per-ip-cap 429**（portal 末端 210.45.118.11） | **2,396 次** | 15:56–16:00 峰值 53–94/min | API 轮询族被打死=「假死」观感，诱发手动刷新（新连接）；非 banner 直接成因 |
| B5 | **presence 拨号风暴**（conn-guard per-ip-cap 拒绝，全部 100.118.229.97=DESKTOP-IJBBEOT） | **44,860 次**（18:40 活读数 47,092，仍以 ~38/min 继续） | 全天 64/64 槽被单设备占满（每快照 top=`100.118.229.97:64`） | 间接（日志噪声/CPU/共享桶）；该机自己开控制台会被锁死 |
| B6 | **服务端主动拆线** | **0** | outbound overflowDrops=0、overflowCloses=0（18:40 活读数）；`portal ws cycle dropped`=0 | 排除：引擎从不主动关 console 连接 |
| B7 | **WsZombieGuard 误杀** | console **1 次**（15:29:27，ip=127.0.0.1，silentMs=99,916=后台标签页，by design）；presence **0 次** | — | #960 判据干净，不是断因 |

关键排除证据：B6/B7 几乎为零 ⇒ 关闭不在引擎侧主动行为，而在**客户端心跳判死**与**隧道腿**；B2 短连接簇与 B1/B4 峰值同窗 ⇒ 饥饿链成立。

## 二、主打根因（机制级）

**门户腿心跳 pong 排队饥饿。** 前端 30s 应用层 ping 的 pong 必须走完全链：
`引擎出站 FIFO → relay 上行 64 槽队列 → relay socket → link-server 下行队列 → 浏览器`。

- 引擎侧出站队列**从未打满**（overflowDrops/Closes=0/0）——饥饿不在容量溢出，而在 **FIFO 排队**：大帧阵发（historyPage 补页、大 toolResult、文件字节）期间 pong 排在本周期 bulk 之后；relay 上行 64 槽再叠一层（#949 既有课题，16h 档 2,190 回合满载）。
- 前端 `hbProbe` 连续 3 次 miss（30s 间隔 + max(5s,3×RTT) 宽限）即自我逐出 → 顶栏「连接断开 · 正在重连」。仓内既有同案注记：HealthRoutes.scala「本地点大文件触发重连：…文件字节全走隧道把心跳 pong 饿死 ⇒ 前端 3-miss 自我逐出」。
- 心跳间隔本身无问题：relay 隧道 ping 3s / 判死 9s，远小于任何 NAT 静默超时——「第三方掐断」不成立；B3 的 4 次硬断成因在 link-server 侧（需 relay 日志核对 close frame，见 §五）。
- presence 共享桶（`wsPerIpCap=64`，presence+console 同一 perIp 桶）不直接影响作者两条腿（本机=环回信任；门户=tunnel 面 429 独立计费），但接受面缺陷让 DESKTOP-IJBBEOT 恒占 64/64：接受面只 `upsertPeer` 不摘旧 socket、旧连接 finalizer 无条件 `removePeer` 拍掉新注册 ⇒ 对端再拨 ⇒ 并存更多 ⇒ 「拒—拨」无退避风暴（对端为 #950 前时代构建，无拨号退避）。

## 三、修复清单（最小集合；引擎侧与 relay 侧分文件）

**引擎（Stand，commit a4a116ff4，本批分支）**

1. **F1 console pong 旁路**——`gateway/consoleponglane.scala`（新）+ `WebSocketRoutes.scala` 接线：pong 走 16 深独立队列，与出站 FIFO **merge** 出线，不再排本连接 bulk 之后；道满回落原路径。风险：pong 与数据帧相对顺序不再保证（安全：ws.js 仅按 `type=="pong"` 清 pendingPong，重排零语义）；其余控制帧仍走原 FIFO+overflow 拆线，政策不变。回归：`ConsolePongLaneSpec` 3 绿。
2. **F2 presence per-device 顶替**——`gateway/PresenceSessionRegistry.scala`（新）+ `PresenceRoutes.scala` 接线：同 device 新会话受理即关闭旧会话（镜像拨号侧 `retireConnection(incumbent,"superseded")` 既有语义），旧 finalizer 凭代际判定跳过 `removePeer`。并存连接恒 ≤1 ⇒ 64 槽风暴环断开。风险：同 deviceId 双实例同机互顶替（客户端重连梯 30s 封顶，振荡有界）。回归：`PresenceSessionSupersedeSpec` 2 绿 + `PresenceWsLifecycleSpec` 6 绿。
3. **F3 配置建议（零代码，旋钮已存在）**：`GATEWAY_CONN_GUARD_TUNNEL_PER_IP_CAP=4096`（campus NAT 210.45.118.11 后多查看者聚合，1024/min 不够公平）；`GATEWAY_CONN_GUARD_WS_PER_IP_CAP` 保持 64；relay 心跳 3s / 判死 9s **不动**。

**relay（link-server 仓，补丁 `reports-evidence/20261028_link-server-edge-pong.patch`，apply 后随下一波部署生效）**

4. **F4 边缘心跳应答**——`portal_ingress.rs`（`is_console_heartbeat_ping` + `edge_pong_enabled` + `T2_EDGE_PONGS_TOTAL`）+ `routes.rs`（`console_ws_pump` Text 分支）：浏览器 ping 在 ingress **就地回 pong**、**照常转发** host。pong 不再跨全链排队；host 僵尸判死的入站活跃钟不变（ping 仍到）；真断链仍走既有 HOST_OFFLINE 1011 传播 + ws_idle 90s 看门狗。止损：`PORTAL_EDGE_PONG=0` 一键回退。风险：掩盖「host 活但数据滞」极端形态（修前前端自逐出重连同样救不了数据面，属换险不增险）；指标 pin 16→17 计数已更新。回归：portal 家族 **88 绿**（含新 `edge_pong_ping_detector_is_exact_and_cheap`）。

## 四、验证

- 引擎：`sbt compile`（643 源）+ `Test/compile`（880 源）绿；新 spec 5 用例绿；既有 WS/relay 家族回归 **73 绿零新增失败**（ConsolePongLane 3、PresenceSessionSupersede 2、PresenceWsLifecycle 6、ConnGuard、PortalUplinkBackpressure、NeblinkRelayTunnelZombieHeal 4、ConsoleWsLifecycle、WsHubUnregister、ConsoleTakeover 22 合批）。
- link-server：`cargo build` 绿；`cargo test portal` **88 passed / 0 failed**（17 计数 pin 修正后）。
- A/B 预期（上线后观测面）：`edge_pongs` 计数 >0 且 `T2_WS_IDLE_TIMEOUTS_TOTAL` 不升、`WebSocket client connected` 频次降至 ≈真硬断水平（≤0.5/h）；conn-guard `rejectedTotal` 日增量从 4.5 万级回落到拨号次数量级（<100/日）。

## 五、上报不修 / 开项

1. **B3 的 4 次隧道硬断成因**在 link-server 生产日志（本机无该侧日志）：需运维核对 10:08/17:44 两条断开的 close frame/cause；引擎侧已把每次断开的「存活时长+短命连击」记入既有日志面。
2. **浏览器端 close code 未采集**：banner 归因目前依赖引擎侧反证（服务端 0 拆线）。建议后续在 ws.js `onclose` 上报 close code/reason 到 eventLog（本批未动前端）。
3. **DESKTOP-IJBBEOT 需换 #950/#960 后时代构建 + 拨号退避**：F2 是接受面防线（风暴环断开）；拨号侧根治仍需该机升级（沿用 #1013 报告结论）。
4. **presence+console 共享 per-ip 桶**（旋钮 `GATEWAY_CONN_GUARD_WS_PER_IP_CAP`）：F2 后单设备 ≤1 连接，共享桶风险大幅缩水，按脸分桶（presence 独立小桶）留作引擎后续批。

## 六、文件清单

- 引擎：`src/main/scala/nebflow/gateway/consoleponglane.scala`、`PresenceSessionRegistry.scala`（新）；`WebSocketRoutes.scala`、`PresenceRoutes.scala`（接线）；`src/test/scala/nebflow/gateway/ConsolePongLaneSpec.scala`、`PresenceSessionSupersedeSpec.scala`（新）
- relay：补丁 `reports-evidence/20261028_link-server-edge-pong.patch`（`portal_ingress.rs`、`routes.rs`、`portal_ingress_test.rs`；工作树未提交，交 relay 责任方 apply）
- 本报告：`reports/20261028_193000_console-reconnect-stability__delegate-nebflow-3d307fdc/REPORT.md`

## 七、关键假设

- 批次链 id 无显式传入，按本 delegate 会话命名 `delegate-nebflow-3d307fdc`。
- 47 次「WebSocket client connected」混合本机直连与门户桥接回环会话（PortalTunnelBridge 对每个门户控制台会话开 `ws://127.0.0.1` 真连接），无法按 IP 分腿——分腿结论由 B6/B7（服务端零拆线）+ 时间窗相关性支撑。
- 作者「双通道」中 portal 为主用腿（campus IP 429 风暴 + 短连接簇同窗）；本机腿全天仅 1 次服务端回收（hidden tab）。
