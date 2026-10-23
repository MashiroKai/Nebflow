# 排队消息空闲不自动投递修复批（queue-idle-flush）——批报告

作者报障（2026-10-23 15:10，逐字）：「我发的消息进入排队队列之后，有时候，你完成回复进入空闲状态时，我的消息也不会自动投递。」

## 1. 考古定界（两层）

### 1.1 web 层（缺陷所在层）

- **入队**：`input.js#send()` busy 腿（`isBusy = busySessionIds ∪ compactingSessionIds`）→ `queueMessage` 入 `state.messageQueue[sid]`，**只入队不出线**（10-21 BASE 形态，#768 回退批 4f15bab77 恢复）。
- **空闲→flush 挂点**：`main.js#scheduleQueueDrain`，触发源 = 终态帧族（`clearBusyFor`：done/error/interrupted/timeout/maxTokens/compactFailed）+ `sessionBusy{busy:false}` + compactComplete + 幻影收口两腿（messageDuplicate 回执 / turn-start 确认网到点）。
- **per-turn 幂等闸**（2026-09-20 阶段 B 引入，本批改造对象）：旧形 = per-session 布尔窗口令牌 `drainWindowUsed`——**派发即关窗，「新一轮事实」（sessionBusy{busy:true} / 流帧族 armBusyFromStream）到场才开窗**。
- 引擎侧核对：`AgentFinishTurn.finishTurnCont` 每 turn 终态固定发 `Done *> emitSessionBusy(busy=false)`；根会话每 turn 开场 `AgentIdle` 在 pipeLlmCall 之前发 `sessionBusy{busy:true}`；depth>0 会话从不出 sessionBusy 帧。**引擎通知腿齐备，缺陷在客户端闸形——引擎零触碰。**

### 1.2 「有时候」的精确条件（根因）

派发即关窗 + 开窗只认两族「新轮事实」⇒ **凡被派发件开启的 turn 客户端看不到任何新轮事实，窗口永久关死**：之后每个终止帧照样触发 drain 定时器，却全数卡死在窗口闸上（return 无 re-arm = 触发丢失）。实测/推演确认的四种无事实形态：

1. **queued `/compact` 的压缩轮**：压缩轮不发 busy{true}、无流帧（`AgentCompactionHandlers` 只在收尾发 busy{false} + compactStart/compactComplete）；`sessionBusy{false}` 落在压缩窗内被 compacting 闸跳过、compactComplete 补触发的定时器被已关死的窗口闸拦下 ⇒ 多条排队时第 2 条起永久滞留。
2. **零流帧轮 / 瞬时错误轮**：同形。
3. **幻影派发**（busy-stuck-fix 两收口路径：判重回执 / 确认网到点）：派发本身把窗口关死，收口后触发的 drain 同样被拦 ⇒ 余件滞留。
4. **断线丢 busy{true} 帧**：turn 真跑了但开窗事实丢失 ⇒ 下一终态被拦。

另有两条**真实 busy→idle 迁移根本没有 drain 触发腿**（只清 busy 不排）：**stop 键**（input.js stopBtn.onclick）与**看门真死收口**（chat.js onBusyWatchdogDeadline 真死分支）；加上 main.js 后台代理对账器的 stale-busy 清理（同缺）。正常路径（busy{true} + 流帧齐备）不受影响——这就是「有时候」。

### 1.3 实测抓到的次生竞态（修 R3 时撞到）

stop 腿原本「同步触发 drain + 动态 import 异步清 busy」：+50ms 定时器可能落在「busy 尚未清 + 上次派发后有新轮事实」的窗口里，被运行中事实闸当成本轮仍在跑而**拦下且无 re-arm**（触发丢失）。修法 = 触发挂进 clearBusy 同一回调（busy 先清、触发后发，次序确定）。

## 2. 修法（全客户端，引擎零触碰）

`main.js`：布尔窗口令牌 → **「派发冷却 + 运行中事实」双条件闸**（事件驱动 + 幂等，挡重复与旧闸同强度、绝不滞留触发）：

- **① 派发冷却窗**（`QUEUE_DRAIN_COOLDOWN_MS = 2000`，`lastQueueDispatchAt`）：每次真实派发起算 2s 内的触发一律压下——它只可能是同 turn 终态对（done + busy{false}）的第二帧；**压下不丢弃**：定时器 re-arm 到冷却边界再判（`armQueueDrainTimer`），瞬时错误轮（终态落在冷却窗内）到点照常投递。
- **② 运行中事实闸**（`queueTurnFactAt`，双挂点 = sessionBusy{busy:true} + armBusyFromStream 流帧族）：上次派发后已收到新轮事实且 busy 未清 ⇒ 被派件 turn 还在跑，等它自己的终态（终态对迟到第二帧越窗同挡——旧窗口防「同窗双投」职责由 ①+② 共同保留）；**零事实不受挡**——压缩轮/流哑轮/幻影/丢帧形态空闲即投递。
- 无件可派 / WS 未开 ⇒ 直接返回不落冷却戳（留待后续终止帧，防队列停摆复发）。

补腿（三条真实 busy→idle 迁移补 drain 触发）：

| 挂点 | 文件 | 形态 |
|---|---|---|
| stop 键 | `input.js` stopBtn.onclick | 触发挂在 clearBusy 同一动态 import 回调内（§1.3 竞态） |
| 看门真死收口 | `chat.js` onBusyWatchdogDeadline 真死分支 | clearBusy（本模块同步）后 `state.scheduleQueueDrain?.(sid)` |
| 后台代理对账器 | `main.js` bg-agent 快照对账 | stale-busy 清理后同模块直调 `scheduleQueueDrain(sid)` |

跨模块接缝：`state.scheduleQueueDrain` 槽（state.js 声明，main.js 赋值）——与 `armTurnConfirm` 同款模式，零新增静态 import。旧 `drainWindowUsed` 全量退役（全仓零残留）。

## 3. spec（tests/queue-idle-flush.spec.mjs，35 断言 ALL PASS）

真模块图 + routeWebSocket 桩服务端 + 真用户动作（harness 同 busy-lifecycle.spec.mjs 族；条件等待 waitUntil + 场景间 settle，规避冷却窗跨场景残留的固定 sleep 假红）：

- **P1 回复完成→空闲→队列自动投递**（主钉）：busy 挂住 → 入队 → 终态对 → 恰投一条、busy 清、队列空。
- **P2 回复结束瞬间入队（竞态双序）**：(a) 挂轮占 busy → 入队 → 终态帧 150ms 后才到 ⇒ 照常投递；(b) 终态已处理后再发送 ⇒ 直发不出队零滞留。
- **P3 多条排队依次投递**：一次终态恰投一条、按队序、慢轮运行中不被抢跑（运行中事实闸）、每条恰一次零重复。
- **R1 零新轮事实轮**（压缩轮形态：compactStart/compactComplete 包裹、无 busy{true} 无流帧）：压缩窗内不投递（compacting 闸 + 冷却窗）、冷却到界余件照常投递——**旧窗口闸必红**。
- **R2 幻影派发**（判重回执收口、无 turn）：收口后余件照常投递——**旧窗口闸必红**。
- **R3 stop 键清 busy 即投递**（interrupted 帧缺席形态）：stop 即投递——**旧实现必红**。
- **E 静态钉**：冷却窗/事实闸/re-arm/接缝/三补腿在源面在场；旧布尔令牌零残留；全程零 pageerror。

**验红实证（变异安全）**：`git stash` 摘除 src 修复后对同一 spec 跑pre-fix 代码——R1/R2/R3 三捕获器与 E 静态钉全数 FAIL（frames=0 滞留实锤），P1–P3 不变量钉保持 PASS（旧实现本来就对的部分不误报）；stash pop 后 ALL PASS。复跑 3 次 ALL PASS（稳定性）。

## 4. 邻族回归 + 门

| 项 | 结果 |
|---|---|
| busy-lifecycle（29）| ALL PASS |
| f1-send-queue（25）/ resend-dedup（21）/ queuebar-informative（30）/ send-bubble-independence（12）/ queue-image-refresh（25）| ALL PASS |
| msgqueue-recall-attach / msgqueue-image-mime | **pre-existing 环境 fail**（模块装载期报 Playwright Test test() 误用——plain node 拉起 @playwright/test；stash 摘修复后逐字节同败，与本批无关，申报不动）|
| checkJs | 257 = 冻结基线 257，零新增 PASS |
| check-circular | 与 main HEAD 同一既有 SCC[3]（popArtifacts/reference/msgQuote），零回归 |
| npm run build | 180 sources 全入图，orphans 0 |

亮暗截图：**跳过**（批令「可选」；本批为纯投递时序修复，呈现面零改动——queue 条/drain 态视觉与 #768 批基点逐位相同，spec 以帧级行为断言覆盖）。

## 5. 残余面申报（不属本批，已核）

- WS 断连期间 busy 项无重连补发轨（flushOfflineQueue 只拣 offline 项）：断线期间终端帧丢失的滞留需重连后一次终态迁移解锁；重连腿扩面属另一批。
- 幻影派发（确认网到点形态）下被派件已 splice 离队且帧未被服务端受理 ⇒ 该条消息本体丢失：busy-stuck-fix 既定取舍（确认网只收 busy 不回捞），本批不动。
- 冷却窗常数 2s：零事实轮形态下余件投递附加 ≤2s 时延（正常轮不受影响）；取值覆盖终态对帧间隔抖动，压同窗双投。
- depth>0 会话（delegate-/node-/…）：引擎从不出 sessionBusy 帧，本批闸形靠流帧族事实 + 零事实兜底腿覆盖，比旧窗口闸（流帧缺失即永久关死）严格更优。

## 6. 文件清单

| 文件 | 变更 |
|---|---|
| `src/main/resources/web/js/main.js` | drain 闸改造（冷却窗 + 运行中事实 + re-arm）；事实双挂点；对账器补腿；state 接缝赋值 |
| `src/main/resources/web/js/state.js` | `scheduleQueueDrain: null` 槽声明 |
| `src/main/resources/web/js/input.js` | stop 键补腿（clearBusy 同回调内触发） |
| `src/main/resources/web/js/chat.js` | 看门真死收口补腿 |
| `tests/queue-idle-flush.spec.mjs` | 新增（35 断言） |
