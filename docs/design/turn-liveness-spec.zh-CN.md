# 对话层活性 spec：插话即时确认 · 断连横幅（场景一已退役）

批：对话层活性流式批（2026-10-22）· 修「看着像卡死」
实施：`web/js/ws.js`（conn-liveness 横幅）+ `web/js/input.js`（插话确认腿）+
`web/css/liveness.css`（横幅材质）。
验证：`tests/conn-banner.spec.mjs`、`tests/turn-liveness-shots.mjs`（断连横幅截图）。

> **退役记录（2026-10-23）**：原场景一「回合活动实时流」（对话框上方的活动
> 面板——`#input-area` 顶部、`#queue-bar` 之上的可折叠活动流条）已按作者令
> **整链退役**。作者令逐字：「这个活动面板我不是让删掉吗，就是对话框上面
> 这个」。退役面 = UI 挂载点 + `js/main/turnActivity.js` 模块 +
> `state.sessionActivity` 账本 + 五个 main.js 事件接线点（agentStart /
> agentToolStart / agentDone / delegateWaiting / backgroundTaskUpdate）+
> liveness.css ①段 + locale `activity.title`/`activity.count` 键。
> **防复活钉：`tests/turn-activity-stream.spec.mjs`**（静态源钉 + 运行时注帧
> 钉 + 邻接零波及钉）——本文件与源码从此不再描述任何活动面板实现。

## 0. 症状与诊断

**症状**（作者实测澄清）：agent 长回合期间，对话面完全静默，只有最终文本落地
才动；插话没有对话面反馈；传输断连更不可见——三件事叠加，体感等同死机。

**诊断**（ws 事件面逐帧盘点）：事件面**不缺**——委派/节点/后台任务事件后端已
全部经 `wsHub.broadcast` 推送（带 `rootSessionId` 归属），根会话自身工具更有
`toolCallDetected`/`toolStart`/`toolEnd` 全家。缺的是**对话层消费**。修复择面：
活动流条（场景一，已退役——活性见证由 Sub-Agents 下拉行承担）+ 插话即时确认
（场景二）+ 传输层可见化（场景三）。

## 1. 场景一：回合活动实时流（已退役，存档摘要）

实现本体与可断言点已随链删除，此处只留两条存续遗产：

### 1.1 路由修复（存续，本批顺带收口）

`delegateWaiting`（sessionId = 委派子会话自身）与 `heldReceipts`（sessionId =
持有积压的父会话）此前**不在任何入口过滤集**——被 ws.js 入口过滤器按
`sessionId !== activeSessionId` 静默丢弃，处理器饥饿。本批补入
`TERMINAL_MSG_TYPES`（taskStuck 同律先例：子代理生命周期状态帧必须达处理器）。
该修复服务 Sub-Agents 行 waiting 标与 heldReceipts 胶囊，与已退役的活动条
无关，保留。

## 2. 场景二：插话即时确认

### 2.1 语义

busy 中插话 = **微信式回执**：乐观气泡立即上屏（在对话流里，不依赖底部条），
footer 前置文字小标「已收到 · 排队第 N 位」（N = 入队位次快照）；队列条照旧
承担管理面。派发时（done 后 drain / 立即发送）原位摘标 = 翻「已派发」态；
气泡恰一份（零重复气泡）。

> 注：排队面形态已由 2026-10-23 输入消息排队回退批（作者令「采用我们之前的…
> 大概两天以前的设计」）回归 BASE——本节语义随该批的 spec 换代为准。

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
- 不改 workline（回合行）与 turn-header 徽标的既有契约；横幅在 `#chat` 之外，
  turn-* specs 的 #chat 子节点枚举不受影响。
- 合并完成事件（批已合并）暂以 delegateWaiting + 注入报告气泡承担；独立
  `merged` 帧候后续批。
- 活动面板**不再回来**：任何后续批不得在对话输入区上方重建活动流/活性条；
  断言钉在 `tests/turn-activity-stream.spec.mjs`。
