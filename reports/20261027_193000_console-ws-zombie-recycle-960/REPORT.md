# 台账 #960 —— /api/ws 控制台 WS 僵尸连接回收（#954 presence 同款病同款修）

## 0. 结论

presence conn-guard 装配（dbc010c0b）平移到 console `/api/ws` 面：**共享装配核
`WsZombieGuard` 单点化**（入站活跃钟 + tick 拍判死 + deadSig 双侧直达 + acquire/release
会话 scope 结构配对），presence/console 两面同一核、只差接线参数。spec 全绿
（37 + 19，含真 ember presence e2e 家族），presence 面零行为漂移。

## 1. 病灶（#954 报告开放项的兑现）

`/api/ws`（WebSocketRoutes `GET -> Root/"ws"`）收流侧零活跃追踪、服务端零判死——
half-open 僵尸（休眠唤醒/组网抖动后对端消失无 FIN）的收流永不终结 ⇒ finalizer
（`releaseWs`/`wsHub.unregister`/watchSession.close）永不执行 ⇒ per-ip 槽位
（默认 64）泄漏，`ConnGuard.checkWs` 以 per-ip-cap 拒掉该 IP 新连接。另带 R-1b
注记的升级期幽灵洞：acquire 在 `wsb.build` 前，upgrade 未起跑即永久泄漏 1 槽。

## 2. 修复（三层，手法与 #954 同源）

1. **结构配对**：acquire 移进收流管 fs2 scope（`guardedReceive` bracket——收流管
   起跑 = 升级会话起跑才计槽），release 同 scope 恰好一次；幽灵洞随之结构封死。
2. **判死回收**：入站活跃钟（任何入站帧触碰）+ 10s 判死拍；连续 `deadAfter`
   无入站帧 ⇒ 审计 WARN + deadSig 双侧直达（receive 侧 interruptWhen 中断 ⇒
   第 1 层 finalizer 必达；send 侧 interruptWhen 终结 ⇒ ember 写 close 帧，与
   既有 overflow 拆线同款，前端既有重连+resync 腿接住）。
3. **对账**：判死回收审计行与 presence 同格式——
   `conn-guard: console zombie recycled ip=… silentMs=… — slot released (heartbeat dead-detection)`；
   槽位随回收立即复纳。

**console 面口径**（`WsZombieGuard.ConsoleLiveness`）：deadAfter=90s = 3 × 客户端
自 ping 间隔（ws.js `HB_INTERVAL_MS=30s`，可见标签页每 30s 一声 ping、服务端
pong 应答早已在 `WsSystemHandlers.handlePing`）——容忍两拍丢失；服务端不补发
心跳帧（浏览器对服务器文本 ping 无应答面，发了也是白发）。后台标签页（hidden
停拍）静默 90s 后一并回收，唤醒侧 ws.js `checkConnection()`（CLOSED ⇒
forceReconnect / OPEN ⇒ wake-probe）既有重连腿接住。回收时延带 90–100s。

## 3. 与 #954 的手法对照

同一装配核（presence 面改由 `WsZombieGuard` 驱动，行为不变）：差异只在——
presence 的 tick 拍兼任既有服务端 ping 节拍、send 侧终结走 sendQueue None 终结符；
console 不发帧、deadAfter=90s（3×客户端拍）、send 侧终结走 deadSig interruptWhen。
🔴 探针新钉（B2）：判死腿终结须 `unNoneTerminate`（裸 `unNone` 是 collect 语义，
判死后本腿带死不僵 ⇒ merge 永不收口）；「本拍不发帧」与「判死终结」不同型表达
（`Some(Seq)` vs `None`）。

## 4. spec 结果

- 新增 `ConsoleWsLifecycleSpec` 5/5：A1 关闭帧立即释放+复纳、B half-open 判死回收
  （silentMs ≥ deadAfter、拍带内）、B2 判死 send 侧自终结（ember close 帧同款）、
  活跃不误杀对照腿、console 口径锁（10s/90s）。
- 回归：`PresenceWsLifecycleSpec` 5/5（共享核改排后零漂移）+ `ConnGuardSpec` 27/27
  = **37/37**；`IndexEntryAliasSpec`（真实 WebSocketRoutes 路由表）+
  `DeviceFaceHardeningRoutesSpec` + `NeblinkPresenceServiceSpec` +
  `NeblinkPresenceHeartbeatLifecycleSpec`（真 ember + 真对端 e2e）= **19/19**，
  t1 读数 180 采样 0 超时/0 掉线/0 重连——判死腿对健康会话零误杀。

## 5. 红线自检

- 未改 ConnGuard 默认值与判定面；判死拍与连接面同生共死（`guardedSend` merge
  进会话作用域），无独立计时漂移。
- 未重启引擎（改动随下一次重启生效）；未动他批在途文件。
- 前端零改动（ws.js 既有心跳/重连/唤醒面原样复用）。

## 6. 交付

- 新增 `src/main/scala/nebflow/gateway/zombieguard.scala`（共享装配核）、
  `src/test/scala/nebflow/gateway/ConsoleWsLifecycleSpec.scala`；
- 改排 `PresenceRoutes.scala`（presenceWsStreams 收敛至共享核，签名/spec 兼容）、
  `WebSocketRoutes.scala`（console 面接线：guardedReceive/guardedSend，入账移
  scope）。报 merge。
