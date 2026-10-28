# thinkingtimer-fix —「思考中」计时显示失真修复报告

- 报障：2026-10-28 20:2x，作者逐字「这个思考中的计时离得也太远了」，截图：思考中 1m 58s。
- 现象：turn 明明已进行很久（当晚 provider 抖动、内部多次重试重连），turn 行「思考中」dots 计时只显示 1m58s。
- 纪律遵守：纯前端（`src/main/resources/web/js/` 四文件 + 新 spec），零引擎触碰，随下一波前端包生效。

## 1. 根因（定位）

「思考中」计时面 = turn 行 dots 项的 `.nf-wl-hint`（`workline.js startWorklineDots`，2026-10-03 stream-ux 改造后旧 thinking-placeholder 计时器已退役，计时自持于 dots 项）。其锚**直接读 `state.turnStartTimes[sid]`**（main.js 两处 `startWorklineDots(activeView, state.turnStartTimes[sid])`），而 `turnStartTimes` 会在 turn 中途被推进/删除，三腿叠加把显示变成「自最近一次尝试/重连以来」：

1. **尝试带正文的重试边界**：`textDone` → `consumeTurnDuration`（main.js，时长徽章挂点）**删除** `turnStartTimes`；引擎重试重流后重发的 `thinking` 帧命里 `if (!state.turnStartTimes[sid])` 就地**重盖 = now** → dots 重建后从 0 重数。← 报障主力腿。
2. **WS 重连**：`input.js rearmBusyQueueDrains` 无条件重盖 `turnStartTimes`（那是 turn-start 确认网的对账基点，非显示面）；且重连历史初载重放（`replayHistoryPageInitialLoad`）**整替 #chat**，live dots 行被拆，下一帧按重盖后的锚重建。
3. **重试风暴里的 `thinking` 重发**：dots → thinking 流项 →（尝试死）→ dots 反复重建，每次重建都读当时已被推进/重盖的 `turnStartTimes`。

即：计时器显示的是「当前尝试/当前重连窗」耗时，不是「该阶段」累计耗时——1m58s vs 真实已等待的远超时长。

## 2. 语义选择（作者视角裁定，已注明）

**思考中计时显示「当前阶段的连续耗时」**：从阶段开始累计，**含内部重试等待**（用户不关心内部重试切分）；阶段多次尝试不重置、连续累计；**阶段切换才归零**。

实现 = 独立段锚 `state.phaseStartAt[sid]`（state.js，与 `turnStartTimes` 分账——后者继续服务时长徽章与 busy 对账，行为零改动）：

| 动作 | 挂点 | 语义 |
|---|---|---|
| 盖戳 | input.js 四派发腿（send / dispatchOfflineItem / drain `newTurn` / 插件注入） | 真人开 turn = 新阶段 |
| 盖戳 | main.js `sessionBusy{busy:true}` **空闲**开轮（`!busySessionIds.has` 守卫，重申不盖） | 引擎自主新 turn（pendingEvents 注入轮）；同 turn 重发 busy{true} 不重置 |
| 盖戳 | main.js `roundComplete`（busy 闸内） | 引擎宣告上一轮定稿、新轮起跑 = 阶段切换 |
| 盖戳（缺才盖） | 流帧族 `thinking/thinkingDelta/textDelta` `phaseStampIfMissing` | 引擎自主轮首帧 / 深度会话 / 工具收段后重开兜底；既有段原样保留 |
| 清除 | main.js `clearBusyFor`（终态帧族唯一必经点：done/error/interrupted/timeout/maxTokens/compactFailed） | 阶段真结束 |
| 清除 | `toolCallDetected` / `toolStart`（AskUser 早退之后） | 工具调用 = 上一 LLM 轮已完整产出，等待段随工具接管该行而终结 |
| **刻意不触碰** | `retryStatus`（尝试遥测）、`textDone`（徽章消费点）、单独 `sessionBusy{false}`（终态对第二帧 / DeathWatcher 误报等 speculative 幻影清除，单独到达不权威）、`rearmBusyQueueDrains`（对账基点腿）、重连历史整替 | **内部重试/重连边界锚必须存活**——这是本修主体 |

`workline.js` 保持叶模块只消费锚：`startWorklineDots(view, startedAt)` 语义升级为「当前段起点」，同段重建（重试重发帧 / 重连整替后下一帧）从原起点继续计数 ⇒ 显示单调递增；阶段切换才归零。

边界取舍（已接受）：若引擎在 turn 中途发**单独幻影终态**（无 done 的 busy{false}）且该 turn 实际继续，下一段会重开重计——该形态客户端已视 turn 结束（整替折叠），重计符合「阶段真结束」语义；重试风暴本身（retryStatus + 流重启 + 重连）全程不重置。

## 3. 改动件

- `src/main/resources/web/js/state.js` — `phaseStartAt: {}` 声明 + 分账注。
- `src/main/resources/web/js/main.js` — `phaseStamp/phaseStampIfMissing/phaseClear` 助手 + 生命周期总注；两处 dots 锚改读 `phaseStartAt`；流帧族/roundComplete/sessionBusy 盖戳；toolCallDetected/toolStart/clearBusyFor 清除。
- `src/main/resources/web/js/input.js` — 四派发腿同步盖戳；`rearmBusyQueueDrains` 明示不碰显示锚（注）。
- `src/main/resources/web/js/workline.js` — `startWorklineDots` 锚契约注释（零逻辑改动）。
- `tests/thinking-timer-phase.spec.mjs` — 新验收 spec（自足：真模块图 + routeWebSocket 桩 + 真用户动作，`node tests/thinking-timer-phase.spec.mjs`）。

## 4. 验证要点（全部实跑）

**新 spec 三面钉（30 断言 ALL PASS）**：
- 「内部重试不重置」：重试风暴（retryStatus + `thinking` 重发 + thinkingDelta 重流）+ **textDone→重试重流主力腿** + 重连历史整替（真实 `replayHistoryPageInitialLoad` 腿拆行）后，段锚逐字不变（状态面）、显示从原段起点续走（显示面）。
- 「阶段切换归零」：工具边界收段后下一段从 0 重计；roundComplete 重盖段锚后 dots 从 0 重计。
- 「显示单调递增」：风暴采样窗内 hint 秒数序列非降、锚零移动。
- 附带钉：done 清锚；新派发/空闲开轮新段；busy{true} 重申与单独 busy{false} 均不动锚；终态清锚。静态钉 E1–E9（两处 dots 调用零 `turnStartTimes` 残留、clearBusyFor 必经点清锚、rearmBusyQueueDrains 不写锚等）。
- **验红实证（变异安全）**：把 `thinking` 处理器锚临时变异回 `turnStartTimes`（旧实现形态）→ T1.6b 必红且读数 `pre=3 post=0`——正是报障「计时归零重数」形态；还原后全绿。

**回归（时长徽章家族 + 触及 handler 家族，零新增失败）**：
- `stream-ux / turn-single-badge / turn-collapse-keep-text / turn-header-persistent / turn-terminal-replay`：50 passed。
- `ui-batch-20261003 / badge-injected-turn / reconnect-collapse`：18 passed。
- `busy-lifecycle / queue-one-per-turn / chat-skeleton / queuebar-informative`（独立脚本）：全部 ALL PASS。
- **checkJs**（`node scripts/check-js-types.mjs`）：PASS——257 errors < baseline 260，零新增（state.js 属零错闸文件，直接干净）。

## 5. 假设与移交

- 假设「阶段」= 当前 LLM 轮的「思考中」等待段（dots 可见面）：round 边界（工具/roundComplete/终态）归零、段内尝试/重连连续累计——与任务裁定语句逐字对齐；若作者想改成「整 turn 一根计时到底」，只需去掉工具边界/roundComplete 两处盖戳即可（锚生命周期已集中）。
- 深度会话（delegate-/subtask- 族）弹窗为 UI-G 静态窗本就无计时器，本修不改其面；其 state 面锚随流帧族盖戳、终态帧族清除，语义一致。
- 本 worktree 里 `evidence/20261023_queue-restore/*.png` 的改动系其他会话所留，未纳入本批提交。
