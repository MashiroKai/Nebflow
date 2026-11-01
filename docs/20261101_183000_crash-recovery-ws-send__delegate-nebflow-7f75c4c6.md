---
type: audit
status: final
date: 2026-11-01
topic: wsSend 桥接批（续聊面板空白批的同类点②）——boot 重挂 delegate 作用域宿主传 None ⇒ 节点会话流帧在发射处被吞；设备腿 wsSend=None 判定为不修（附三条判据）
---

# boot 重挂腿 wsSend=None · 取证与处置（2026-11-01）

上游：[[20261101_180000_continuation-panel-blank-rca__delegate-nebflow-d86b41ad]] 修好了 Mail
续聊腿（`wsSend = None` → `ctx.wsSend`），并把「同类可疑点还有两处未动」列进开放项②：
`DeviceDelegate.scala:531`（设备腿）与 `ProjectCrashRecovery.scala:338`（boot 重挂腿）。
本篇是这两处的取证与逐处判定（一处修、一处不修，理由见 §2/§3）。

## 1. 结论（先给判定）

| 站点 | 判定 | 一句话理由 |
|---|---|---|
| `ProjectCrashRecovery.recoverDelegateScopeOn`（重挂 delegate 作用域宿主） | **修** | 它是**唯一**把 `DelegateFlowHosts.mount(wsSend = None)` 写死的宿主挂载点；None ⇒ `NodeEngine.wsSendFn` 退化成空函数 + `emitScopeEvent` 走 `fold(IO.unit)` ⇒ 恢复窗口的 node-* 会话帧与作用域 flow-map 事件**双双静默**。boot 期没有控制台连接可桥（无 `ctx.wsSend`），正确出口 = 进程级广播（`startupMount` 的 #28 接线同源同契约）。 |
| `DeviceDelegate.spawnLocalDelegate`（设备侧 delegate） | **不修**（本批） | ① 无连接上下文（spawn 由对端经 Neblink 数据通道投来，`ctx.wsSend` 这个桥缺一端）；② 进程内无全局广播单例（`WsHub` 在 GatewayMain 局部、core 禁 import gateway），且**广播不是录制**——接上广播只换来 live 流，报障形态（面板正文空白）不会消失；③ 实盘零样本（990 条 delegate 记录 `delegatedBy` 命中 0）。根治 = 连接无关的进程级 recorder（开放项 F2），届时两处一并接上。 |

## 2. 修的那处：boot 重挂腿

改动（最小 + 归因注释）：

- `ProjectCrashRecovery.recoverDelegateScopes(..., wsSend: Option[Json => IO[Unit]] = None)`
  → `recoverDelegateScope` → `recoverDelegateScopeOn` → `DelegateFlowHosts.mount(wsSend = wsSend)`
  （三处逐层透传，缺省 None = 逐字旧形态，spec 桩面零改动）；
- `GatewayMain` 的 `delegateScopeCrashSweep` 传 `Some((json) => wsHub.broadcast(json))`，
  与上方 `startupMount` 的 `mountAll(wsSend = …)` 同源同注释谱系（#28「None ⇒ no-op ⇒
  事件静默丢失」）。

为什么这是「该修的」而不是「可接受的缺省」：同族三条腿对照表——

| 腿 | wsSend 来源 | 结果 |
|---|---|---|
| 新鲜派发 `DelegateTool.spawnKernel` | 调用方 `ctx.wsSend`（录制 send） | 帧落盘 + 广播 |
| Mail 续聊 `MailTool.continueDelegate` | 改后 `ctx.wsSend` | 同上（上一批修） |
| delegate 作用域 **spawn** 挂载 | `childWsSend`（录制 send） | 同上 |
| delegate 作用域 **boot 重挂** 挂载 | 改前写死 `None` | **帧在发射处被吞** |

同一挂载函数、同一个 `wsSend` 参数，只有 boot 重挂腿传 None ⇒ 判别变量就是这一行。

诚实边界（本批**不**覆盖的面，进开放项 F2）：`wsHub.broadcast` 是**广播**不是**录制**。
重挂会话的 ai/tool 行仍不会写 `.ui.json`——这不是本腿独有，而是**项目引擎普遍持有的
形态**（boot 挂载传裸广播）。要治它必须引入连接无关的进程级 recorder（按帧内
`nodeSessionId` 落盘 + 广播）。本批只消除「帧根本不出进程」这一段，并把边界写进代码注释
（`ProjectCrashRecovery` 的 `wsSend` 参数注释与 `GatewayMain` 的接线注释各一处），
避免下一批把「已接广播」误读成「已落盘」。

## 3. 不修的那处：设备腿（三条判据）

1. **无连接上下文**：`spawnLocalDelegate` 的输入是对端经 Neblink 数据通道（presence
   `sendData` / relay 兜底）投来的 `delegate-spawn` 载荷，本进程侧不存在「发起 spawn 的
   会话连接」——已修族那三条腿桥的都是某条 WS 连接上的录制 send，这里两端缺一端。
2. **无全局 sink 可替代，且半修退化**：`WsHub` 由 GatewayMain 局部构造，`DeviceDelegate` 的
   装配缝 `initialize(resources)` 只拿到 `AgentRuntimePort`（core 端口，无 broadcast 成员；
   core 禁 import gateway，`scripts/check-scala-layers.mjs` 门禁）。要接 = 新增一条贯通
   GatewayMain → initialize → 本处的全局广播面，而换来的只有 live 流：设备侧会话的
   ai/tool 行照样不落 `.ui.json` ⇒ 面板「重开仍空白」，只是从「完全空白」变成
   「live 可见、重开空白」——比现状更难判读。
3. **无消费者证据**：本机 `~/.stand/delegates/*.json` 990 条记录中 `delegatedBy` 命中 **0**
   （从未收到过设备派活）⇒ 无实盘样本、无报障面；该会话的结果面经 `resultProxyBehavior`
   折成 `delegate-result` 回投 host，host 面板渲染的是**父会话**。

判定已就地留在代码里（`DeviceDelegate.spawnLocalDelegate` 的 `wsSend = None` 上方），
并由 spec S6 做留痕钉（None 处必须带『同类可疑点 / 不修 / recorder』三段判据，
禁「悄悄改回去」也禁「悄悄修了却没判据」）。

## 4. 实盘取证（可复跑：`evidence/20261101_crash-recovery-ws-send/census.py`）

### 4.1 node-* 会话面板取数面（判别变量 = 该会话的帧有没有进录制层）

ai/tool/thinking 三类 `.ui.json` 行的**唯一**写者是录制层
（`WebSocketRoutes#makeRecordingWsSend`，按帧内 `nodeSessionId` 落盘）；注入行不经 wsSend
（`AgentActor#emitInjectedUserEvent` 直写 `SessionStore`）。故「只有 user 行、零 ai/tool」
= 面板只剩归因徽章行的物理形态。

| 指标 | 数 |
|---|---|
| node-* 会话有 `.ui.json` | **4071** |
| 其中零 ai+tool 行 | **3899** |
| 其中「transcript 有助手产出（真干过活）却零正文行」 | **3872** |
| 有 ai/tool 行 | 172 |

标本（前 5）：`node-e5dd9022`（ui 2 行全 user / transcript 135 条）、`node-e7eb502b`
（1 / 232）、`node-a411eebe`（1 / 110）、`node-7e3dd576`（1 / 210）、`node-70ae565e`（1 / 48）。

⇒ 这不是个别会话：node-* 族的面板历史面在现网**大面积为空**，与「重启后某些会话面板
空白」同源（缺录制腿），量级远超本批两处（开放项 F2 的真实体量）。

### 4.2 重启恢复窗口面（判别变量 = boot 认领后的帧去了哪）

数据源 = `**/.nebflow/flow-map-events.jsonl` 的 `type=boot-recovery` 且 summary 带
`sessionRef=node-…` 的条目（= boot 认领（resume）的节点会话）。

| 指标 | 数 |
|---|---|
| 认领事件 | 7（resume 4 / failed-transcript-lost 3） |
| claim 后仍有 transcript 产出（可测） | **3** |
| 其中 claim 后**零** ui.json 行 | 2 |
| 其中 claim 后**只有注入行**（零 ai/tool） | 1 |

明细：`node-cd843ea1` post_msgs=16 / post_rows=0；`node-b0fb86a4` post_msgs=50 / post_rows=0；
`node-2b0afd87` post_msgs=186 / post_rows=9（全 user，ai+tool=0）。
⇒ **实测空白 3/3**（该窗口内的会话面板 100% 只剩注入行或完全空白），与代码判据
（None ⇒ 帧吞 + emitScopeEvent 静默）一致，无反例。

### 4.3 设备腿样本面

`~/.stand/delegates/*.json` = 990 条，`delegatedBy` 命中 **0** ⇒ 同类可疑点①在本机无
实盘样本（不修的判据③）。

## 5. 回归钉

`src/test/scala/nebflow/core/project/DelegateScopeRecoveryWsSendSpec.scala`（同族写法，
参照 `DelegateContinuationWsSendSpec`）：

- **S1 吞帧机制**：`mount(wsSend = None)` ⇒ `engine.wsSendFn` 是空转，节点帧零落点（改前形态）；
- **S2 落盘键盖章**：`mount(wsSend = Some(sink))` ⇒ `agentTextDelta` 到点且盖
  `nodeSessionId / sessionId / rootSessionId`（录制层判归属的三键）；
- **S2b 第二消费面**：`emitScopeEvent`（flow-map `nodeUpdated` 等）同源到点（改前 `fold(IO.unit)` 零帧）；
- **S3 契约不破**：帧自带 `nodeSessionId/sessionId` 不被覆盖；同作用域二次挂载返回同实例；
- **S4 防复活钉**（源码钉）：重挂腿必须 `wsSend = wsSend`、禁 `wsSend = None`；入口保留
  `wsSend` 参数；GatewayMain 必须给 `wsSend = Some(…)` 且含 `wsHub.broadcast`；
- **S5 取证件在册**：普查脚本可复跑（本批判据来源）；
- **S6 判定留痕钉**：`DeviceDelegate` 的 `wsSend = None` 必须带本批三段判据注释。

## 6. 开放项

- **F1（本批已修的残余）**：重挂会话的 ai/tool 行仍不落 `.ui.json`（广播 ≠ 录制）。要治必须
  引入**连接无关的进程级 recorder**：`~/.stand` 侧可复用的现成骨架 = `gateway.SessionRecorder`
  （连接无关、按会话写 `UiMessage`），但它的分支族是 root 会话帧（`textDelta/toolEnd/done`），
  节点会话帧是 `agentTextDelta/agentToolEnd/agentDone` 族 ⇒ 需一个「按帧内 nodeSessionId
  懒建 recorder」的薄封套。落点候选 = `WebSocketRoutes#makeRecordingWsSend` 的 agent* 分支
  单点抽出（禁复制第二份规则），装配点 = GatewayMain boot（`startupMount` / 崩溃恢复两腿）。
- **F2（本批不修的那处的根治）**：进程级 recorder 落地后，`DeviceDelegate.spawnLocalDelegate`
  与 boot 重挂腿一并接上（同一 sink），届时设备侧会话与项目 node-* 会话的面板历史一并恢复。
- **F3**：已产生的空白会话不可回溯补录（帧从未落盘）；普查脚本随时可复算判别变量。
