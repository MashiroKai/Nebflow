# 修复批 · 排队消息空闲不投递复发（手机后台化场景）REPORT

- 批次：queue-idle-return（2026-10-25）
- 作者令（2026-10-25 13:58 逐字）：「搜一下 我之前让修复消息队列的问题 修复了吗 现在我还是发现存在 我在消息队列里的消息 你轮次结束进入空闲 但是消息不注入」。作者主用手机 Safari（移动层）。
- 语义钉（不可破，本批逐字保留）：队列积压 N 条 ⇒ 回合结束投 1 条 → 新回合结束再投下一条；投递前提 = 上一回合真正结束；**禁计时器节流**（事件驱动合法，定时器节流非法）。

## 1. 读码定谳（A/B 都是真凶，且相互复合；对照读码为准，非转述旧报告）

前两批（queue-idle-flush / queue-one-per-turn）修的闸形全部前提是「**触发事件在页面活着时被处理**」。手机 Safari 后台/锁屏冻结页面的 timers+WS，帧恰在冻结窗内到场/丢失——两处缺口读码定谳：

**缺口 A（回前台无补触发腿，嫌疑 A 实锤）**：drain 的既有触发面 = 终态帧族（`clearBusyFor`）+ `sessionBusy{false}` + compactComplete + 幻影收口（判重回执 / 确认网），全部挂在前述前提上。冻结窗内帧要么挂起到恢复后（缓冲形态，恢复后自愈——旧实现即通），要么随 socket 死亡永久丢失（对端已写 TCP 缓冲）——后者**没有任何腿再触发 drain**：`visibilitychange` 只有 ws.js 的 `checkConnection`（连接层），`pageshow` 无监听，`onReconnect`（main.js:4013）只重取 history/agents/tasks/listSessions，零队列面。

**缺口 B（断线窗丢终态帧 = 缺戳死锁形，嫌疑 B 实锤）**：`busy{true}` 在场（投递未决闸③的「有在跑事实」）+ `done`+`busy{false}` 终态对双丢 + 重连后永无后续帧 ⇒ ③的两个前提「有在跑事实 ∧ 无越界终态戳」**永久成立——但那是断线前的旧知识，turn 实际已在黑暗窗内结束**。逐路审计六处权威终态戳（main.js:635 `clearBusyFor` 无条件戳覆盖 done/error/interrupted/timeout/maxTokens/compactFailed(max)；main.js:2161 compactComplete 条件戳；main.js:1375 判重精确腿戳；input.js:1443 stop 键戳；chat.js:1728 看门真死戳；main.js:4237 对账器戳）——**全部依赖「终态帧到场」，本形态永无帧到场**。三条既有兜底腿又全部失效：
1. 后台代理对账器（main.js `activeAgents` 快照同步 busySessionIds）**显式豁免活跃会话**（`sid !== state.activeSessionId`）——手机场景恰是活跃会话；
2. 看门真死分支（chat.js:1707 `!wsAlive || !hasLiveness`）被重连后的 pong/入站活性永久挡住（`state.lastWsInboundAt` 是全局口径，任何会话流量都续命）；
3. turn-start 确认网早已被 `busy{true}` 撤网（main.js:2507 `cancelTurnConfirm`）。

**缺戳终态路逐条放行（B 项审计收口，读码实证）**：
- `messageDuplicate`（判重回执，AgentSession.scala:51 带 sessionId 广播）与 `timeout`（客户端终态处理器在册帧型）**不在任何路由集**（GLOBAL/TERMINAL/STREAM 三集皆无）⇒ 非活跃会话帧被入口过滤器（ws.js:1057）静默丢弃 ⇒ 判重精确腿（收幻影 busy + 落越界戳 + drain）对后台会话**不可达**。**本批补入 TERMINAL_MSG_TYPES 收口**。
- `sessionBusy{false}` 的条件戳（main.js:2525，`factAt <= dispatchedAt` 才落）**维持原样**：终态对由 `AgentFinishTurn.finishTurnCont` 同刻同 socket 发出，有序可靠流上「done 丢而 busy{false} 到」不可达；落戳条件正是 2026-10-24 15:41 病历（speculative 清除放行）的防线，动它 = 病历复发。
- interrupted 窄形核销：后端三条 interrupt 路（AgentProcessing.scala:869/921/1521）恒发 `interrupted` 广播帧，客户端 `clearBusyFor` 无条件戳——**无缺口**。
- 其余四戳（stop 键/看门/对账器/compactComplete）逐路在场，**无缺戳**。

**C 项核销**：c8dd31c37（remove-activity-panel）拆的是 turnActivity.js 的 `queuebar-refresh` 监听（活动面板会话切换重渲面），与 TERMINAL_MSG_TYPES/终态帧路由零交集（提交信息自证「ws.js TERMINAL_MSG_TYPES 路由修复保留」，比对 183-185 行监听体确认）；#845 批（ddb74b7b9）只把 `pendingAsksSnapshot` 加入 GLOBAL（无 sessionId 直回帧防抢视图），TERMINAL/STREAM 零改动。**两项均无波及**；本批顺手收口的只有上述 messageDuplicate/timeout 路由缺口。

## 2. 修法（全客户端，引擎零触碰；main.js +102 行 / ws.js +9 行）

1. **补触发腿三条（缺口 A）**——`main.js sweepQueueDrains()`：对所有队列非空会话 `scheduleQueueDrain`，挂 `visibilitychange`→visible / `pageshow`(bfcache persisted) / WS `onReconnect` 三腿。**纯重触发**：冷却窗/运行中事实闸/投递未决闸/ws 闸照旧裁决，一次一条语义零改动（与 pendingAsks 可见期补账同族同位）。
2. **重连对账网（缺口 B）**——`main.js armQueueReconcileNet(sid)`：重连（= 帧流被切断的权威事件）时对「持有形态」会话（队列非空 ∧ busy ∧ 事实晚于派发 ∧ 无越界终态戳）武装；预算同 `turnConfirmMs` 旋钮。到点复核三条件：期间有真终态戳 ⇒ 撤；有本会话流活动（`sessionStreamActivity`——流帧族与 toolHeartbeat 族双挂点，turn 活着）⇒ 撤；仍持死证 ⇒ 裁定「终态丢失于黑暗窗」：落重连 settle 终态戳 + 清 stale busy + `scheduleQueueDrain`。**全事件驱动，语义钉不破**：正常形态投递仍跟随事实边界，网只裁「证据被切断」的失证形态——turn-start 确认网同款先例。`armTurnConfirm` 头部补 `cancelQueueReconcileNet`：任何新派发重置证据链（网不在新 turn 静默期误裁）。
3. **缺戳终态路路由收口**——`ws.js`：`'timeout', 'messageDuplicate'` 入 TERMINAL_MSG_TYPES（与 done/error 同律；timeout 现无引擎生产点，防御性在册）。

## 3. 回归测试（钉死）

**新增 `tests/queue-idle-return.spec.mjs`（21 断言 ALL PASS）**，真模块图 + routeWebSocket 桩 + 真用户动作（harness 同 queue-idle-flush 族）：

- **H1 可见补触发腿**：空闲+队列非空滞留形态 ⇒ `visibilitychange`→visible 恰投一条（旧实现无监听必红）。
- **H2 bfcache 腿（负钉+正钉）**：`pageshow persisted:false` 不投（普通加载不走补触发）；`persisted:true` 恰投一条。
- **H3 重连对账网（作者报障本体，旧实现必红）**：直发锚 busy{true} 在场 + 入队 + **黑暗窗**（终态对永不到场，桩直接断开）+ 重连 ⇒ 网裁丢失、恰投一条、stale busy 收口清。
- **H4 对账网让位于活轮（一次一条语义钉）**：重连后活轮（busy{true}+流帧 >1.2s > 网预算 800ms）期间**零抢跑**；活轮真终态后恰投一条——无复核面的裸网必红。
- **E 静态钉**：三补触发腿 / 对账网复核面三条件 / 新派发撤网 / messageDuplicate+timeout 路由收口 / 投递未决闸③语义钉不动（`unresolvedFactAt > dispatchedAt && termStampedAt <= dispatchedAt`）。

**邻族零回归**（全绿）：queue-idle-flush 35 断言（38 PASS 行含 boot 钉，ALL PASS）、queue-one-per-turn 34 断言 ALL PASS（P 组一次一条语义全保留）、busy-lifecycle、f1-send-queue、queuebar-informative、send-bubble-independence 全部 ALL PASS。

**既有红申报（与本批无关，stash 摘修复后逐字节同败，不动）**：resend-dedup D3「immediateInput 帧带 clientMessageId」——静态钉正则是**字段序敏感**的（要求 `clientMessageId` 紧随 `content`），provenance 批 aefea8f77（2026-10-23 19:40，晚于 queue-idle-flush 批的全绿跑）在帧内插入 `device: deviceStamp(),` 后必然失配；行为面完好（clientMessageId 仍在帧上，input.js:986），属过期钉非行为回归。

**质量闸**：checkJs 260 = 冻结基线 257 + 3，全部来自 `web/js/nearpath.js`（console-fastpath 批 ab81fbc02 的在制件，queue-one-per-turn 报告 §3 同款申报；本批 diff 面 = main.js/ws.js 两文件，ws.js 属 ZERO_ERROR_FILES 零门、main.js 基线内零新增行）。

## 4. 残余面申报

- **对账网误裁残余（唯一新增面，与既有申报同级）**：重连恰落在「首 token 前静默 thinking 轮」（零流帧零心跳）时，网到点误裁「黑暗窗终态丢失」⇒ 下一条排队件投进仍在跑的 turn ⇒ 服务端排队闸受理、逐条边界消费（**不重复不丢失**，最坏 = 同轮作答的一次性观感）。该残余 ≤ queue-one-per-turn §5 已申报的「零事实放行」残余等级，且比它晚一个网预算（默认 20s）才发生；活轮的流帧/工具心跳/真终态任一到场即撤网。
- 深度>0 会话（delegate-/node- 族）同样被网覆盖：它们的终态（agentDone→done 转换）与流帧是网的生命证据，黑暗窗丢失形态同批收口。
- 引擎零触碰：`sbt run` 在产实例不受影响；**本批为纯前端批，浏览器刷新即生效**。验证路径：手机 Safari 刷新页面后复现原操作（发消息→锁屏→等回复完成→回前台）——队列下一条应在回前台后（重连腿）或活轮终态后照常逐条投递；桌面端行为零变化（补触发腿被既有闸面消解为 no-op）。

## 5. 文件清单

| 文件 | 变更 |
|---|---|
| `src/main/resources/web/js/main.js` | `sweepQueueDrains`（三补触发腿 + 模块面 visibilitychange/pageshow 注册）；`armQueueReconcileNet`/`cancelQueueReconcileNet`（重连对账网）；onReconnect 接线；`armTurnConfirm` 撤网 |
| `src/main/resources/web/js/ws.js` | `messageDuplicate`/`timeout` 入 TERMINAL_MSG_TYPES（缺戳终态路收口） |
| `tests/queue-idle-return.spec.mjs` | 新增（21 断言：H1-H4 行为钉 + E 静态钉） |
