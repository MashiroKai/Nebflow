# presence WS 僵尸会话回收 —— 台账 #950 旁支 REPORT

日期：2026-10-26 · 批次：conn-guard 僵尸会话回收（#950 旁支）· 分支 `delegate/delegate-nebflow-c2e6b6d0`

## 0. 结论

「客户端已断、服务端未回收」的泄漏点已定位并修复：服务端 presence WS（`GET /api/neblink/presence`）**只发 ping、从不核 pong**——half-open 僵尸会话（对端消失且无 FIN/RST 到达）的收流永不终结，finalizer（`removePeer` + 槽位回减）永不执行，per-ip 槽位（默认 64）被灌满后该 IP 一切新连接被 `per-ip-cap` 拒绝，即现场 `presence WS upgrade rejected ip=100.118.229.97 reason=per-ip-cap` 的直接成因。修复 = 入站活跃钟 + 判死挂**既有** 10s 心跳拍（零新增 wall-clock 轮询）+ 会话作用域内 acquire/release 结构配对；spec 钉两态（关闭帧立即释放 / 心跳超时回收），conn-guard 族回归绿。

## 1. 泄漏法证（任务①）

路径：`GatewayMain` → `RestApiRoutes.presenceWsRoutes` → `PresenceRoutes.routes` 的 `GET /neblink/presence` 受理臂；守护 = `nebflow.gateway.ConnGuard`（connguard.scala）。

断开检测原状（改前）：

- **关闭帧 / TCP FIN 层**：唯一回收缝 = 收流（receive）fs2 finalizer——`removePeer` + `releaseWs`（凭据配对）挂在 `_.onFinalize` 上。客户端正常 close / EOF / RST ⇒ 收流终结 ⇒ 回减必达（此腿原本就工作）。
- **心跳超时层：不存在**。服务端每 10s 向客户端发 `{"type":"ping"}`（`awakeEvery(10.seconds)`），但收流侧对 pong 及一切入站帧零追踪——`case _ => IO.unit`。half-open 僵尸（休眠唤醒 / 组网抖动 / 重连风暴中客户端消失无 FIN）的收流**永不终结**：ember 的 idle 超时只管升级前的 keep-alive 面，升级后无超时；服务端 ping 持续写进死 socket 的内核缓冲（写不报错）⇒ 槽位泄漏直至进程重启。
- **配比洞（次级）**：`acquireWs` 在 `wsb.build` **之前**入账。R-1b 注记覆盖了 build 失败形态，但漏了第三形态：response 已产出而 upgrade 会话未起跑（101 写失败 / 客户端中途消失）⇒ finalizer 无从挂载 ⇒ 幽灵计数。重连风暴加速此洞积累。

对端证据：`NeblinkPresenceService`（引擎互联的规范对端）对服务端 ping 必回 `{"type":"pong"}`（NeblinkPresenceService.scala `PresenceWsListener.onText` `case "ping" => ws.sendText(pong)`），故**入站帧活跃度**可作为本端点的失效判据（控制台 UI 不走此面，走 `/api/ws`）。

## 2. 修复（任务②）

单点收敛在 `PresenceRoutes.presenceWsStreams`（新增装配件，路由臂改为一行调用）：

1. **关闭帧 / TCP 层（结构保证）**：`acquireWs` 移进会话 fs2 scope——`Stream.bracket(connGuard.acquireWs(ip) <* touch)(connGuard.releaseWs)` 包裹收流。客户端 close/EOF/异常断开 ⇒ 收流终结 ⇒ 回减必达；「会话未起跑」形态从结构上不再占槽（恰好补掉 §1 配比洞）。
2. **判死回收（half-open 兜底）**：每连接一条入站活跃钟（`Ref[Long]`，任何入站帧即触碰，含 pong）；判死检查挂**既有** 10s ping 拍（`PresenceWsLiveness(tick=10s, deadAfter=30s)`，容忍连续两拍丢 pong，第三拍仍无任何入站帧才判死——规范对端 pong 间隔 ≤ tick，零误杀面）。判死 ⇒ 审计行 + `Deferred` 信号 ⇒ send 流终结（`interruptWhen`）⇒ ember 会话装配（0.23.30 `WebSocketHelpers.upgrade`：`readSide.concurrently(sendSide)`）对 send 终结的处置 = 写 close 帧 + 中断 receive 侧 ⇒ ①的 finalizer 回减与 `removePeer` 必达，僵尸驻留期 30s 封顶。
3. **对账（计数 ↔ 实活 socket，任务③）**：配对由结构保证（acquire/release 同一 scope，恰好一次）；启动校准 = 进程冷启动守护表为空（无残留可漂）；回收校准 = 判死走同一 finalizer 缝，`onZombieRecycle(silentMs)` 留 WARN 审计行（ip/device/沉默时长），槽位随回收立即复纳。close/EOF/error/判死/cancel 全部终止路径同归一缝 ⇒ 计数漂移无积累面。

改动文件：`src/main/scala/nebflow/gateway/PresenceRoutes.scala`（受理臂 + 新增 `presenceWsStreams` / `PresenceWsLiveness` / `PresenceWsStreams`，import 增 `Deferred`）。**零改动**：ConnGuard 本体、ConnGuardConfig 默认值、对端 `NeblinkPresenceService`、`/api/ws` 面（WebSocketRoutes）。

## 3. per-ip-cap 配比核查（任务③读数，未改默认值）

- `wsPerIpCap=64`（connguard.scala 默认；env `GATEWAY_CONN_GUARD_WS_PER_IP_CAP`）：`/neblink/presence` 合法并发 = 每对端引擎 1 条（`NeblinkPresenceService.connections` 按 deviceId 单连接，重连顶替）；控制台单实例不走此面。合法 1–2 条/IP vs 上限 64 ⇒ 30–60× 余量——上限从不咬合法流量，此前唯一咬合形态就是僵尸堆积。
- 修复后风暴算术：判死驻留 ≤30s + 拆除（ember shutdownTimeout 30s 封顶，环回实测 ms 级）；~1 conn/s 重连风暴下并发残留僵尸 ≤ ~31 + 合法 1–2 ≈ 33 < 64 ⇒ 上限在风暴形态下不可达。
- `wsTotalCap=1024` vs `EmberMaxConnections=4096`：WS 会话 ⊂ 全部连接，4× 余量给非 WS 面（tunnel/HTTP），合理；`warnPct=80` 双腿独立预警照旧。
- 建议：**维持 64 不变**（覆盖 NAT 后多设备形态；降到 32 收益有限且削 NAT 余量）。附注：不要把风暴对端 100.x 加入 `GATEWAY_CONN_GUARD_TRUSTED_IPS`——受信面绕过 per-ip 上限，信任闸不构成资源面防线（R-1b 已有判注）。

## 4. Spec（任务④）

新增 `src/test/scala/nebflow/gateway/PresenceWsLifecycleSpec.scala`（munit CatsEffectSuite；测试用 50ms 短节拍驱动**同一**判死机制，生产节拍仍 10s）：

- **态①a 关闭帧**：客户端一声 pong 后收流终结 ⇒ `wsTotal` 立即回零、`acceptedTotal=1`、peer 已摘、无僵尸判词、`checkWs` 复纳。
- **态①b 线路语义**：对端发 `{"type":"ping"}` ⇒ 发送线出现 `{"type":"pong"}` 回复（服务端自身心跳 ping 的应答 pong 无回复、只触碰活跃钟——既有协议语义，回归钉）。
- **态② 心跳超时**：`Stream.never` 收流（半开僵尸、入站永久沉默）⇒ 判死读数 ≥ deadAfter 且在节拍面内完成（超时兜底不触发）、`wsTotal=0`、peer 已摘、槽位复纳。
- **对照腿（活跃不误杀）**：pong 每 40ms 触碰活跃钟跨 400ms（2.7× deadAfter）⇒ 零判词；随后正常关闭照常释放。
- **升级期幽灵计数回归（R-1b 第三洞）**：仅 send 单边运行（会话未起跑）⇒ `wsTotal=0`、`acceptedTotal=0`；首发 ping 仍在拍上（既有节拍不变）。

conn-guard 族回归：`ConnGuardSpec` 全绿（27 项，本次未改 ConnGuard 本体，属基线确认）；合计 32/32 绿。

**fs2 语义实测注记**（选型依据，临时探针已删）：fs2 3.11.0 `concurrently` 的后台流终结**不**中断前台——生产装配因此让判死信号 `deadSig` **双侧直达**（send 侧 None-terminated 双分支终结、receive 侧自身 `interruptWhen`），不依赖 ember `readSide.concurrently(sendSide)` 对 send 终结的处置方向；测试组合同样只依赖「前台终结取消后台」这一 scope 保证。

## 5. 验证证据

```
sbt -batch 'set Test / sources ~= 排除他批在途 DeviceProvenance 测试源（内存 set，零文件改动）' \
  "testOnly nebflow.gateway.PresenceWsLifecycleSpec nebflow.gateway.ConnGuardSpec"
[info] Passed: Total 32, Failed 0, Errors 0, Passed 32
[success] Total time: 15 s
```

presence 端到端家族（真 ember + 真对端拨号）：NeblinkPresenceServiceSpec / NeblinkPresenceHeartbeatLifecycleSpec / DeviceFaceHardeningRoutesSpec —— 读数见提交后补记（本文件 §5b）。

## 6. 红线自检

- 零超时红线：判死检查只挂在**既有** 10s 心跳拍上（原实现即存在的 `awakeEvery(10.seconds)`），未新增任何 wall-clock 轮询 / 独立计时面。测试内 50ms 节拍是同一机制的短拍驱动，不属生产计时面。
- 未重启引擎、未动他批文件（DeviceProvenance* / PortalUplinkBackpressure* / web/js / NeblinkRoutes.scala 等在途改动原样未碰；本次跑测用 sbt `set` 在内存中排除他批在途测试源，零文件改动）。

## 7. 开放项

- `/api/ws`（控制台面，WebSocketRoutes）同样是「只发心跳不核活」形态——本批范围外，建议另立台账（控制台前端自身有 3-miss 逐出，服务端面暂无对等判死）。
- ember 升级后无 idle 超时是上游行为；本批的判死回收已覆盖 presence 面资源防线，其余 WS 面如需同等防线可复用 `presenceWsStreams` 形态。

## 8. 交付

- 提交：本文件 + `PresenceRoutes.scala` + `PresenceWsLifecycleSpec.scala`（见 git log）；报 merge。
