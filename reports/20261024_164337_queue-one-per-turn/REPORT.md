# 修复批 · 消息排队投递语义回归（多消息排队被一次性全发）REPORT

- 批次：queue-one-per-turn（2026-10-24）
- 作者报障（2026-10-24 15:45 逐字）：「我们的投递消息的排队功能回退了以后，又会出现排队的消息一次性发送的问题。正确的行为是，比如我的队列中有消息在排队，应该是你轮次结束，投递一个消息，再次结束，再投递一个消息。而不是一股脑排队了几个消息，一次全部发送」
- 语义钉（作者拍板）：队列积压 N 条 ⇒ 回合结束 → 投递 1 条 → 会话消费该条产生新回合 → 回合再结束 → 再投下 1 条；投递前提 = **上一个回合真正结束**（事实闸），禁计时器节流。

## 1. 定位（排查结论）

**服务端逐条投递语义完好，回归在客户端 drain 闸。** 证据三件：

1. 生产日志（/Users/kaiyu/.stand/logs/stand.log）当日全量核对：`pending-events-injected-at-tools-complete` 恒 `events=1`（仅两笔 `events=2`，均为 delegate 回执屏障汇聚腿，系统回执非用户消息）；11:10–11:16 的 18 条桥入站积压逐条消化（remaining 18→1，每边界恰一件）——服务端「一次边界只消费队首一件」在位。
2. **病历帧**（Nebula 根会话，2026-10-24 15:41）：上一 turn 15:41:20 完成 → 客户端队列投出 m1（15:37 入队「探查一下…」）→ 服务端 15:41:22 受理开轮 → **15:41:48 m2（15:39 入队「我点diff…」）于 m1 turn 运行中被客户端投出** → 服务端 `user-input-queued pending=1` 排队 → 在 m1 turn 内（msgs 52→56）被消费，15:42:50 一次作答 = 「排队的消息一次性发送」。配图截图同一形态（15:39 气泡渲染于 15:41 回复之下 = 排队项 drain 补渲带 item.ts 入队时刻戳）。
3. 客户端闸形缺陷（main.js `armQueueDrainTimer`）：旧闸仅「busy 标志 + 运行中事实」两条件挡重复——**凡某条 speculative 腿把 busy 提前清掉，闸即失守**，冷却窗（2s）到界即把下一条排队件投进仍在跑的 turn。speculative 清除源一族：turn-start 确认网误判 / 陈旧 `sessionBusy{false}`（DeathWatcher 误报广播、崩溃恢复竞态）/ 判重回执回落腿。准确到具体哪条腿在 15:41:47 清的 busy，客户端无日志不可定谳（服务端日志只有两帧用户消息、无判重回执）；本批按**类**收口（见 §2），不赌单一腿。

排查方向核对：maildedup 相关日志（`[mail-dedup] duplicate queue delivery suppressed` 族）在当日日志中活动正常，`MailDeliveryDedup.tryDeliver` 幂等闸工作如常；社交桥入站→`ExternalEvent(source="bridge")`→`TurnBoundaryDrains.drainBarrier`（一次边界一件）链路逐字完好，引擎零触碰。

## 2. 修法（客户端三件，引擎零触碰）

1. **投递未决闸（③）**——`main.js armQueueDrainTimer`：上次派发开启的 turn 已被引擎确认开轮（`queueTurnFactAt > lastQueueDispatchAt`）但终态戳未越过派发时刻（`state.lastTerminalAt ≤ lastQueueDispatchAt`）⇒ 纵然 busy 已被 speculative 腿清掉也**不投**，等真实终态。事实闸非计时器：放行跟随回合边界。零新轮事实形态（压缩轮无 busy{true} / 幻影派发 / 断线丢 busy{true}）不受挡——queue-idle-flush 的「零事实空闲即投递」语义逐字保留。
2. **确认网开轮事实否决**——`armTurnConfirm`：引擎权威开轮事实（busy{true}/流帧族，晚于本网武装所随派发）在场 ⇒ turn 真实在跑（首 token 前静默的慢 thinking 轮不是幻影），撤网不得收 busy；本网只裁「无任何开轮事实」的真幻影（该分支落权威无-turn settle 戳）。
3. **六处权威收口点落终态戳**（`state.lastTerminalAt[sid] = Date.now()`）：`sessionBusy{busy:false}`（条件落：仅「无在跑确认轮」的 done+busy{true} 双丢形态——有在跑确认轮时未伴随 done 的 busy{false} 属 speculative 清除，不落戳）/ `compactComplete` / 判重**精确腿**（id=最新派发；**回落腿不落**——可能撞真实静默轮）/ stop 键（input.js，R3 帧丢失形态收口）/ 看门真死（chat.js）/ 后台代理对账器（main.js）。终态戳同时是 armBusyFromStream 的 TERMINAL_ARM_GUARD 事实源，语义同 clearBusyFor 既有落点。

## 3. 回归测试（钉死）

**新增 `tests/queue-one-per-turn.spec.mjs`（34 断言 ALL PASS）**，作者钉逐字落地：

- **P 组**：锚轮占 busy → 队列预置 3 条 → 锚终态恰投 m1 一条（m2/m3 原地不动）→ m1 轮运行中（2.6s 慢轮越过 2s 冷却窗）m2/m3 零抢跑 → m1 终态恰投 m2 → m2 轮内 m3 零抢跑 → m2 终态恰投 m3 → 队列清空；全局每条恰一次、按队序、任一投递窗口零 flush≥2（P.1–P.11）。
- **X1 回归捕获器**（旧实现实测验红）：m1 派发开轮（busy{true} 在场）→ 轮中段陈旧 `sessionBusy{false}`（无 done）清 busy ⇒ 旧闸在 D+2s 冷却到界把 m2 投进运行中轮（stash 对照 m2=1 **RED**）；新闸投递未决闸按住（m2=0），真实终态到场恰投一条。
- **X2 回归捕获器**（旧实现实测验红）：判重回执**回落腿**（id 对不上的陈旧回执 + 15s 流静默）误收真轮 busy ⇒ 旧闸 D+2s 到界投出 m2（stash 对照 **RED**）；新闸按住、真实终态后放行。确认网在本场景 defer 至 60s（生产 20s 网预算 > 回落腿 15s 静默窗，回落腿恒先到；测试加速网抢跑属另一形态，由 E4 静态钉护）。
- **E 组静态钉**：投递未决闸双判读数、确认网否决、六处权威戳、精确腿落戳/回落腿不落。

**邻族回归**（全绿）：queue-idle-flush 35 断言（P1/P2/P3/R1/R2/R3 行为面零回归；E7 对账器腿邻接窗 220→500 重锚——本批在对账器腿 clearBusy 与 scheduleQueueDrain 之间插入终态戳一行，钉的契约面「先清 busy 后触发 drain」不变）、busy-lifecycle、f1-send-queue、send-bubble-independence、queuebar-informative 全部 ALL PASS。

**质量闸**：checkJs 触文件（main.js/input.js/chat.js）零错误贡献——门面 260 = 冻结基线 257 + 3，全部来自 `web/js/nearpath.js`（**未跟踪文件、无 git 历史、mtime 今日 15:46，本 worktree 其他在活会话的在制件，非本批产物**；本批 stash 前后两趟门跑均含它）。

## 4. 交付面

- `src/main/resources/web/js/main.js`：投递未决闸（③）、确认网否决 + 真幻影 settle 戳、sessionBusy{false} 条件戳、compactComplete 戳、判重精确腿戳、对账器戳。
- `src/main/resources/web/js/input.js`：stop 键终态戳（R3 保留）。
- `src/main/resources/web/js/chat.js`：看门真死收口终态戳。
- `tests/queue-one-per-turn.spec.mjs`：新增回归 spec（作者钉 + 双捕获器 + 静态钉）。
- `tests/queue-idle-flush.spec.mjs`：E7 邻接窗重锚（220→500，契约面不变）。

## 5. 残余与申报

- **残余（双故障形态）**：若 speculative 清除与「引擎开轮事实全丢」同时发生（busy{true} 帧丢失 ∧ turn 20s 静默 ∧ 确认网判真幻影），客户端无从分辨真幻影，投递按幻影恢复放行——WS 有序可靠传输下需断线窗才可能，概率极低；发生时服务端排队闸兜底（帧受理排队、逐条边界消费），不产生重复/丢失。已在对账器/看门/stop 三腿用上位证据（后端快照/阶梯到顶/用户权威）压到最小。
- 多端同队（多标签页各自 localStorage 队列）不在本批范围（f1-send-queue/resend-dedup 族既有边界）。
- 服务端 ToolsComplete 边界逐条注入（「avoids being stashed」设计，2026-09-15 裁定逐条化）保持原样：本批收口的是客户端投递闸；已投出帧在服务端边界的一次一件消费非本报障形态（当日日志 events=1 实证）。
