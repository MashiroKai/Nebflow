# 对话层活性 spec：长回合活动流 · 插话即时确认 · 断连横幅

批：对话层活性流式批（2026-10-22）· 修「看着像卡死」
实施：`web/js/main/turnActivity.js`（活动条）+ `web/js/ws.js`（conn-liveness 横幅）+
`web/js/input.js`（插话确认腿）+ `web/css/liveness.css`（两面的材质）。
验证：`tests/turn-activity-stream.spec.mjs`、`tests/busy-queue-ack.spec.mjs`、
`tests/conn-banner.spec.mjs`、`tests/turn-liveness-shots.mjs`（前后对照截图）。

## 0. 症状与诊断

**症状**（作者实测澄清）：agent 长回合（尤其委派/后台批并跑的回合）期间，对话面
完全静默，只有最终文本落地才动；插话没有对话面反馈；传输断连更不可见——三件事
叠加，体感等同死机。

**诊断**（ws 事件面逐帧盘点）：事件面**不缺**——委派/节点/后台任务事件
（`agentStart` / `agentToolStart` / `agentToolEnd` / `agentDone` /
`delegateWaiting` / `backgroundTaskUpdate`）后端已全部经 `wsHub.broadcast`
推送（带 `rootSessionId` 归属），根会话自身工具更有
`toolCallDetected`/`toolStart`/`toolEnd` 全家。缺的是**对话层消费**：

| 事件 | 修前唯一消费面 | 对话面 |
|---|---|---|
| agentStart / agentToolStart | Sub-Agents 下拉行（不点开不可见） | 无 |
| agentDone | 下拉行 2s 保留后删除 | 无 |
| delegateWaiting | Sub-Agents 行 waiting 标 | 无（且被入口过滤器丢弃，见 §1.3） |
| backgroundTaskUpdate | 头部后台任务徽标 | 无 |
| busy 中插话 | 底部队列条（长对话滚离视口） | 无 |
| ws 断连 | 发送按钮变灰 | 无 |

## 1. 场景一：回合活动实时流

### 1.1 面

- 宿主：`#activity-strip`（`#input-area` 顶部、`#queue-bar` 之上，主窗独有；
  弹窗视图无此面）。账本：`state.sessionActivity[sessionId]`（全局，非激活会话
  照收，切回重放；`done` 终态清空）。
- 默认收起 = **一行滚动摘要**：`[图标] 活动 · N 条 · <最新一行>`；点击整条展开
  最近 12 条（新在上，带 HH:MM 时钟）；再点收起。
- 不刷屏纪律：同 `kind+text+who` 1.5s 窗内连发只涨计数（摘要带 `×N`）；
  账本容量 40 条；`done` 清空（终态摘要由 turn-header 徽标承担）。

### 1.2 数据源（前端消费点，main.js）

| 帧 | kind | 行文本 |
|---|---|---|
| `agentStart` | dispatch | `<批名> — <任务标题>`（无标题取任务描述前 60 字） |
| `agentToolStart` | tool | `<批短名> · <工具摘要>`（label = 后端 summarizeToolCall） |
| `agentDone` | done | `<批短名>`（成功档图标） |
| `delegateWaiting` | waiting | `<批短名>`（待合并轮见证） |
| `backgroundTaskUpdate` | bgtask | `<描述或 taskId> → <status>` |

归属键 = 事件信封 `rootSessionId`（嵌套委派归到用户正在看的顶层会话，与
sessionBgAgents 落桶同律）。非在场会话的事件只记账，不抢当前条。

### 1.3 路由修复（本批顺带收口）

`delegateWaiting`（sessionId = 委派子会话自身）与 `heldReceipts`（sessionId =
持有积压的父会话）此前**不在任何入口过滤集**——被 ws.js 入口过滤器按
`sessionId !== activeSessionId` 静默丢弃，处理器饥饿。本批补入
`TERMINAL_MSG_TYPES`（taskStuck 同律先例：子代理生命周期状态帧必须达处理器）。

### 1.4 可断言点

- A 组：`agentStart` ⇒ 条显现、绑定归属会话、派发行 + 计数 1。
- B 组：`agentToolStart` ⇒ 摘要更新为工具行，带批短名前缀。
- C 组：同 label 1.5s 内重发 ⇒ `×2` 聚合，不新增行。
- D/E 组：`backgroundTaskUpdate` / `delegateWaiting` ⇒ 各上屏一行；展开列表
  新在上、条数正确。
- F 组：再点收起。
- G 组：异会话事件不重画当前条（账本照记）。
- H 组：`done` ⇒ 条隐藏 + 账本清空。
- 钉子：`tests/turn-activity-stream.spec.mjs`（22 断言）。

## 2. 场景二：插话即时确认

### 2.1 语义

busy 中插话 = **微信式回执**：乐观气泡立即上屏（在对话流里，不依赖底部条），
footer 前置文字小标「已收到 · 排队第 N 位」（N = 入队位次快照）；队列条照旧
承担管理面（重排/立即发送/撤回/移除）。派发时（done 后 drain / 立即发送）原位
摘标 = 翻「已派发」态；气泡恰一份（零重复气泡）。

### 2.2 机制

- 入队腿（input.js send busy 支）：`queueMessage` 后置 `item.rendered = true` +
  `renderUserBubble(…, { msgId: item.clientMessageId, queueNote })`——与离线腿
  共用 rendered 寻址面（撤回/移除/溢出收泡单点 `removeOfflineBubble`）。
- 翻面单点：`flipOfflineMark` 扩为摘 `.msg-pending-mark` **与**
  `.msg-queued-mark` 两枚小标。
- 翻面落空补渲（本批收口）：行不在场（刷新后 / historyPage 整替）⇒
  drain/sendImmediate 补渲一次已送达态气泡——消息已受理，不能凭空消失。
- 失败面沿用 qattachvault：载荷丢失 ⇒ 小标原地翻红 `!`。

### 2.3 可断言点

- A/B 组：两条插话 ⇒ 两枚确认标（第 1/第 2 位）+ 输入框清空 + 队列条计数。
- C 组：drain ⇒ 队首标摘除、气泡仍恰一份、队尾留队（drain 每回合一一条）、
  busy/expecting 重新武装。
- D 组：撤回（移除）⇒ 气泡随项收掉。
- 钉子：`tests/busy-queue-ack.spec.mjs`。

## 3. 场景三：传输层可见化

### 3.1 面

- 宿主：`#conn-banner`（body 顶部固定横幅，不推挤布局；`role=status` +
  `aria-live=polite`）。
- 断开 ⇒ 「连接断开 · 正在重连」；存在离线入队消息（`offline && !failed`）⇒
  「连接断开 · 正在重连 · N 条消息待送达」（滞留状态可见）。
- 重连成功 ⇒ 横幅收起；冲刷由既有 `flushOfflineQueue`（onReconnect）与
  `flushPendingSends`（onopen）承担，本批零改动。
- 刷新点：连接态翻转（onopen/onclose/onerror，挂在 `syncSendButtonConnState`）
  + 离线队列变更（入队 `enqueueOfflineMessage`、派发 `dispatchOfflineItem`、
  刷新恢复 `restoreQueue`，经 `state.updateConnBanner` 槽——模块图上 input.js →
  ws.js 单向，反向 import 成环，与 `updateBgTasksUI` 同款机制）。

### 3.2 可断言点

- R 组：在线 ⇒ 横幅隐藏。
- D 组：服务端真断开（close 帧）⇒ 真实 onclose ⇒ 横幅显现 + 文案正确 +
  `state.connected === false`。
- Q 组：断开期间插话 ⇒ 横幅计数「1 条消息待送达」+ 气泡待送达标。
- R 组（续）：真实指数退避重连 ⇒ 横幅收起 + 离线队列自动冲刷（帧出线、
  小标摘除、行保留）。
- 钉子：`tests/conn-banner.spec.mjs`。

## 4. 非目标

- 不动后端事件面（零 Scala 改动）；不新增 ws 帧类型。
- 不改 workline（回合行）与 turn-header 徽标的既有契约；活动条在 `#chat`
  之外，turn-* specs 的 #chat 子节点枚举不受影响。
- 合并完成事件（批已合并）暂以 delegateWaiting + 注入报告气泡承担；独立
  `merged` 帧候后续批。
