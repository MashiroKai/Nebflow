# 对话层活性流式批 · 收口报告（长回合活动流 / 插话即时确认 / 断连横幅）

2026-10-22 · `delegate-nebflow-c886330f`

## 1. 症状与诊断

作者实测澄清：卡的不是 delegate 也不是工具，是**消息对话层**——长回合期间对话面
完全静默，只有最终文本落地才动；插话零反馈；断连不可见。体感等同死机。

ws 事件面逐帧盘点结论：**事件面不缺，缺的是对话层消费**。委派/节点/后台任务事件
（agentStart / agentToolStart / agentDone / delegateWaiting /
backgroundTaskUpdate）后端已全部经 wsHub.broadcast 推送（带 rootSessionId 归属），
修前的唯一消费面是 Sub-Agents 下拉与后台任务徽标——用户不开弹窗，对话面一行都
没有。详见 `docs/design/turn-liveness-spec.zh-CN.md` §0 逐帧表。

顺带发现并修复一个真实路由缺口：`delegateWaiting` 与 `heldReceipts` 两帧不在
ws.js 任何入口过滤集，sessionId 非当前会话时被入口过滤器**静默丢弃**（处理器
饥饿）——补入 TERMINAL_MSG_TYPES（taskStuck 同律先例）。

## 2. 交付面

| # | 场景 | 实现 | 钉子 |
|---|---|---|---|
| 1 | 回合活动实时流 | `web/js/main/turnActivity.js`（新）+ main.js 五个消费点 + `#activity-strip`（input-area 顶部，Sapphire 玻璃） | tests/turn-activity-stream.spec.mjs（22 断言全绿） |
| 2 | 插话即时确认 | input.js busy 腿乐观气泡 +「已收到 · 排队第 N 位」确认标；flipOfflineMark 双标单点；翻面落空补渲收口 | tests/busy-queue-ack.spec.mjs（13 断言全绿） |
| 3 | 断连横幅 | ws.js updateConnBanner + `#conn-banner` 顶栏；滞留计数（offline 项）；刷新点 = 连接态翻转 + 离线队列三变更点 | tests/conn-banner.spec.mjs（9 断言全绿，真断开真重连链路） |
| 4 | spec | docs/design/turn-liveness-spec.zh-CN.md（三场景 + 可断言点） | — |

前后对照证据（evidence/20261022_conversation-liveness__delegate-nebflow-c886330f/）：

- `nf-liveness-01-before-silent.png` — **修前**：同一长回合同一时刻（委派批已跑
  Read×1 / Grep×1 / Edit×1 + 后台 sbt 任务），对话面只有「思考中 1s」一行，批的
  活动零可见。
- `nf-liveness-02-after-strip.png` — **修后**：同刻活动条在场，收起 = 一行滚动
  摘要「活动 · 5 条 · 实现批 · Edit turnActivity.js」。
- `nf-liveness-03-after-expanded.png` — **修后展开**：最近 5 条（批派发/工具×3/
  后台任务），新在上、带时钟。
- `nf-liveness-04-disconnect-banner.png` — 断连横幅「连接断开 · 正在重连」+
  断线期间插话即时确认「已收到 · 排队第 1 位」+ 队列条滞留可见（三场景同框）。

## 3. 设计纪律要点

- **不刷屏**：默认收起一行滚动摘要；同文 1.5s 聚合窗（×N 计数）；账本 40 条帽、
  展开列表 12 条；`done` 终态清空（终态摘要归 turn-header 徽标）。
- **零后端改动、零新帧型**：纯前端消费既有事件面 + 两帧路由修复。
- **既有契约零触碰**：#chat 子节点枚举（turn-* specs 钉子）不动——活动条在
  #chat 之外；workline/turnGroup 零改动。
- **复用先于新建**：确认标复用 f1-send-queue 的 rendered 寻址面（撤回/移除/溢出
  收泡单点 removeOfflineBubble）；横幅刷新走 state 槽（updateBgTasksUI 同款机制，
  避免反向 import 成环）；材质 = #queue-bar 同款玻璃（零新色值，全部既有 token）。
- **checkJs 门禁**：257 = 基线（新文件 turnActivity.js 零错误入账）。

## 4. 验证

- 新 spec 3 件全绿：turn-activity-stream（22）/ busy-queue-ack（13）/
  conn-banner（9，真实 close→指数退避重连→flushOfflineQueue 冲刷全链路）。
- 回归 sweep 全绿（零 FAIL）：f1-send-queue、msgqueue-recall-attach、
  msgqueue-image-mime、queue-image-refresh、queuepos-detail、stream-ux、
  turn-collapse-keep-text、turn-single-badge、turn-header-persistent、
  badge-injected-turn、chat-autoscroll-follow、busy-lifecycle、
  bgagent-injected-bubbles、bgagent-dedupe、bgagent-toolline-truncate、
  askuser-refresh-survive、clientconn-wake、reconnect-collapse、
  tool-heartbeat-timer。
- checkJs：PASS（257/257）。

## 5. 开放项 / 未做

- 「某批刚合并」目前以 delegateWaiting（待合并轮）+ 注入报告气泡（auto-merge
  尾注）承担；独立 `merged` ws 帧候后续批（spec §4 已声明非目标）。
- 活动条不进手机层独立形态（mobile 层与桌面同 DOM，随 #input-area 悬浮卡
  呈现）；若要手机专属密度，另批。
- en/zh 双语已同步；其他 locale 不存在。
