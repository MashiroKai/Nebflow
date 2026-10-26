# 台账 #944 跨设备回报丢件腿 — 定因与修复

时间：2026-10-26 · 设备：KAI（40742cd4，Windows）· 分支 `delegate/delegate-nebflow-c2e6b6d0`

---

## 1. 结论先行（定因翻转）

**任务书假设的丢失点不是真凶。** 任务书推测「relay 断连窗口 ⇒ 释放即丢弃 / 仅内存队列 ⇒ 重连无补投」。
实测双侧日志对账后，relay 那条腿是**干净**的：

- KAI 侧每件都打出了 `delegate-result sent to host`（13:28:46、14:05:42、14:18:06、14:26:36、15:28:24）；
- Mac 侧逐件对得上 `result received (spawnId=..., pointers=...)`（13:28:47、14:05:42、14:18:06、14:26:36、15:28:25）——
  **延迟恒 < 1s，无一件丢在 relay 上**；
- KAI 的 relay 断连窗口实测只有 **0.585 秒**（14:39:07.667 `no session token yet` → 14:39:08.252 `Relay tunnel connected`），
  且该时刻**没有任何完成件在飞**。

真凶是 **14:39:04 的 KAI 进程重启**，链路如下：

| 时刻 | 事件（KAI `~/.stand/logs/stand.log`） |
|---|---|
| 14:39:04.537 | `stand 2026.10.26-beta.2` — boot 行，**进程重启** |
| 14:39:04.795 | `[startup-recovery] 6 in-flight delegate task(s) survived a process restart — terminalizing + notifying parent session(s)` |
| 14:39:04.821–891 | 6 行 `orphan delegate-... terminalized; parent=d7c3cb54-dd90-4f **exists=false** partialResult=true` |
| 14:39:04.920 | `restart-interruption marked on 5 delegate record(s)` |

**6 个孤儿 ⇔ 6 个从未离机的 spawnId，精确一一对应**（本机 `result_sent=0` 的 6 个）：

```
dd-8c2f4367 → delegate-nebflow-96f19a06
dd-16e1a8d9 → delegate-nebflow-c478b794
dd-8ae563a0 → delegate-nebflow-73d024ef
dd-7341c180 → delegate-nebflow-401cd320
dd-4447a159 → delegate-nebflow-3b772cd4
dd-dff2cdc7 → delegate-nebflow-b6fa52a4
```

### 两个叠加缺陷

**缺陷 A（发端，`DeviceDelegate.resultProxyBehavior`）**：完成件只有「回投代理 actor 收到终态事件」
这一个触发点。进程重启把在飞会话连代理 actor 一起杀掉 ⇒ **永远没有终态事件** ⇒ 件**从未离机**。
发送腿本身是纯 fire-and-forget（原注释逐字承认：`no queueing; host may re-dispatch after the device returns`）。

**缺陷 B（兜底腿，`SubAgentStartupRecovery.recoverOne`）**：boot 收殓本该兜底通知父会话，但判据是

```scala
parentExists <- sessionStore.listSessions.map(_.exists(_.id == task.parentSessionId))
_ <- if parentExists then notifyOnce(...) else IO.unit
```

对 **device-delegate 是恒假命题**——父会话永远在**对端设备**的盘上，本机 `sessionStore` 里根本没有它。
后果：通知腿被当成「父会话已删」整条跳过（日志 6 行 `exists=false` 就是它），
而第 3 步仍写下 `"partial result salvaged and **delivered to parent**"` 的 lastError
—— **写了「已通知」，实际零投递**，静默蒸发。

这就是「KAI 那边一个都没回报」的完整机制：件死在机器重启里，兜底腿又因为跨设备判据写死而拒绝通知。

### 同族排查（任务书要求）

- **`node:` 链路（ImmediateInput）**：**不受影响**。`NodeDelivery.offerRootNotify` 在根 ref 缺失时
  返回 `RootNotifyOffer.Parked` 且**不记账**，由 30s 补投扫描重新入队（at-least-once 已在位），
  语义与本次修的跨设备腿正交。
- **dispatcher-wake（`BootDispatcherWake`）**：**不受影响**。它是 boot 链上的一次性本地唤醒
  （`DispatchNotify.defaultTrigger`），不跨设备、不经 relay，断连窗口无法触及。
- relay 的 `PortalTunnelBridge` 邮箱满丢弃（Mac 侧 14:18:06 有 `portal uplink inbox full ... dropped`）：
  经核查那是 **portal 本地 WS 桥**（console 数据面），与 `delegate-result` 走的
  `relayExec/Notify → handleDataMessage` 不是同一条腿，且同秒内 5 件 delegate-result 全部送达，
  不构成本次丢失向量。

---

## 2. 修法

### 新增 `DeviceResultOutbox`（持久待发区）
`src/main/scala/nebflow/core/delegate/DeviceResultOutbox.scala`

`<dataRoot>/device-outbox/<enqueuedAt>-<id>.json`，一件一文件，`AtomicJson` 整写：

- `enqueue` — **写前投**；同 id 幂等零重写（保留原入队序）；落盘失败只 WARN 不阻断发送；
- `drain(send)` — 按入队序逐件补投；成功即出队；失败/抛异常的件**留盘**，一条炸不拖垮其余；
- `delivered` — 成功删除；`pending` — 坏文件跳过并 WARN（簿记面 fail-soft）；
- `attempts` 是**纯读数**：只升序可见，**不参与任何判失败逻辑**（作者红线「设计零超时」，不设 TTL/不按墙钟判死）。

### 接线

| 文件 | 改动 |
|---|---|
| `DeviceDelegate.sendDurable` | **新增**：先落盘再发；成功出队，失败留盘 |
| `DeviceDelegate.resultProxyBehavior` | 改走 `sendDurable`（spawnId 作幂等键）；失败 WARN 文案改为「已留盘、重连补投」（原「host will not receive」） |
| `DeviceDelegate.enqueueLostResultNotice` | **新增**：boot 收殓腿的跨设备补录——把收殓件折成与真终态**同构**的 `delegate-result`（`status=failed` + 抢救出的部分结果）落进待发区 |
| `DeviceDelegate.handleResult` | 收端幂等闸：`correlationId = spawnId`，注入前查既有 `DeliveredReceiptLedger`（**复用 #886 台账，不新造第二套**）；已投 ⇒ 抑制注入但台账注记照跑 |
| `SubAgentStartupRecovery.recoverOne` | 收口修复：`DelegateRegistry.find(taskId).delegatedBy` 有值 ⇒ 走设备待发区；无值 ⇒ 本机会话腿语义逐字不变 |
| `NeblinkRelayTunnel` | 新构造参 `outboxDrain`；`Relay tunnel connected` 之后 fire-and-forget 补投（**断连窗口攒下的件在连上第一刻全部出去**） |
| `GatewayMain` | 注入 `outboxDrain = DeviceDelegate.drainOutbox(sharedResources)` |

**宁重勿丢的实现**：语义 = at-least-once；幂等在**收端**（`DeliveredReceiptLedger`，键 = spawnId），
重投绝不二次唤醒父会话。零墙钟、零重试节拍、零超时判死。

---

## 3. 验证

新增 `src/test/scala/nebflow/core/delegate/DeviceResultOutboxSpec.scala`（7 例，纯桩驱动，无网络无 actor）：

| 用例 | 钉的面 |
|---|---|
| W1 | 件产出即在盘上——模拟「发送腿从未跑到」后读盘仍在（durability = disk is truth） |
| **W2** | **断连窗口 → 重连补投的两次状态迁移**：第一轮 drain 全失败 ⇒ 3 件全留盘；第二轮（relay 恢复）⇒ 按序送达并出队，顺序 `dd-a,dd-b,dd-c` 保持 |
| W3 | 同 id 重复入队 = no-op（同槽位同序，不重复投） |
| W4 | 单件抛异常不阻断整批（只留失败件） |
| W5 | 送达即出队，第二次 drain 零动作（不重复发） |
| W6 | `attempts` 只升序计数，三轮失败后件仍在（**零超时**纪律的判红锚） |
| W7 | 坏件文件跳过不阻断可读件（簿记 fail-soft） |

编译与用例读数见 §5。

---

## 4. 诚实边界 / 未做

1. **重启前已在 `sendData` 途中、落盘之后**的极小窗口：件在盘上，会重投一次 ⇒ 收端判重挡掉。行为正确，重复开销可接受。
2. **`~/.stand/device-outbox/` 无容量上限**：零删除纪律下不设淘汰（与 `DeliveredReceiptLedger` 的
   drop-oldest 不同——那边是纯簿记，这边是**未投递的成果**，删了就是丢件）。若长期积压需人工核查，
   日志每轮 drain 都有 `still undeliverable, kept on disk` 读数。
3. **未改动 `AgentIdentitySync`**：身份外推是 fire-and-forget 的幂等推（对账自愈：`addPeerChangeCallback` 变化即全量重推），
   丢一件下一轮自动补，不构成本族缺陷。
4. **未新增 `RelayTunnelPort` 成员**：补投钩子走构造参注入（`outboxDrain: Option[IO[Unit]] = None`），
   默认 None 让 23 个既有 spec 构造点零改动。
5. **本批未触碰**：`web/css/js`、`MergeQueue`、`MergeArtifactCare`（#943 批在飞面）——零接触已核。

---

## 5. 检验读数

（见最终回复文本；编译 `sbt compile` 绿，spec 读数见下）

---

## 6. 复现命令

```bash
# KAI 侧定因读数
grep -n "startup-recovery\] 6 in-flight" ~/.stand/logs/stand.log
grep -n "orphan delegate-.*terminalized" ~/.stand/logs/stand.log
grep -n "delegate-result sent to host" ~/.stand/logs/stand.log   # 只 6 件，含的正是重启后跑的
# Mac 侧对账
grep -n "result received (spawnId=" ~/.stand/logs/stand.log
```
