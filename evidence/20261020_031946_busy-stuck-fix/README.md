# busy-stuck-fix — 会话 busy 态卡死修复批（2026-10-20）

报障（作者实撞）：agent 轮次实际已结束（回复已完整落），客户端会话 busy/spinner
态不清除；后续消息全部进入本地待发队列且永不 flush（busy 永不清 = 队列唯一的
终态 drain 挂点永不触发）。

## ① 根因定位（busy 生命周期全链读 + 三嫌疑回归排查）

**结论：客户端 busy 生命周期误判；引擎/Scala 零触碰。**

正常轮次结束链（引擎 `AgentFinishTurn.finishTurnCont`：`Done *> emitSessionBusy(busy=false)`，
双帧成对、每 turn 必发）→ 客户端 `clearBusyFor(done)` + `sessionBusy{busy:false}` 处理器
双双 `clearBusy` —— 这条链本身没有断点。卡死发生在**另一种派发**上：

- **#569（f45858019）语义反转**：服务端对已受理过的 `clientMessageId` **丢弃不开 turn**
  （AgentIdle 直投 / AgentFrozen 唤醒 / AgentProcessing busy 排队三处去重点 +
  `messageDuplicate` 回执）。而客户端派发腿（`send` / `drainMessageQueue` /
  `dispatchOfflineItem` / `injectUserMessage`）在**写帧成功后乐观置位 busy**——
  「帧写出去」从此不再蕴含「turn 会跑」= **幻影 busy**：无 turn 就没有终态帧，
  busy 永不自清。
- **回执消费面两缺口**（main.js `onMessage('messageDuplicate')`，#569 引入）：
  1. 15s 守卫读**全局** `state.lastStreamActivity`——多代理工作区任何其他会话的
     流帧都给本会话幻影 busy 续命 ⇒ 回执**永不**收 busy（报障主因）；
  2. 即使收了 busy 也**不 `scheduleQueueDrain`** ⇒ 幻影期间排队的消息永久停摆
     （报障的「入队发不出」半边）。
- **无回执形态无收口**：补发重放被旧后端/丢帧窗吞掉、冻结唤醒被搁置（schedule
  未开窗）等形态连回执都没有。看门的「真死」分支在 WS 存活时被 pong 活性证据
  永久挡住（`hasLiveness = inbound > armedAt`，pong 每 30s 刷新——by design 判
  「慢」，判不了「幻影」）⇒ 同样无兜底。

**三嫌疑回归排查结论**：
- F1（9c2beee9c）：`dispatchOfflineItem` 重放已送达项时**无条件 `setBusy`**（input.js:694）
  —— 幻影 busy 的放大器（重放被 #569 判重 ⇒ 必然幻影），修法不触碰其补发语义；
- #569/#570（f45858019）：回执消费面缺口即主因（见上）；
- mobile v3（a8f7c1234）：逐 hunk 过 busy 清除点 —— 零触碰 busy 生命周期
  （main.js 仅 +initAgentProfile；mobile/* 全族无 busy 引用），嫌疑排除。
  checkJs 新增两处失败（agentProfile.js / mobile/floatController.js「new file with
  errors」）系该批遗留、与本批无关（本批 4 触碰文件零新错误，总量 273→258 只降）。

## ② 修法（两件，均在客户端；红线内）

- **Fix 1 · 回执升级权威无-turn 信号**（main.js `messageDuplicate` 处理器重写）：
  `msg.clientMessageId === state.lastUserSendId[sid]`（本会话最新派发幂等键）且
  派发点后本会话**零流活动**（新增 `state.sessionStreamActivity` per-session 戳，
  与全局 `lastStreamActivity` 孪生）⇒ 判幻影，收 busy + 照常 `scheduleQueueDrain`；
  id 对不上回落 **per-session** 15s 静默窗（旧守卫语义、粒度收敛）。
- **Fix 2 · 乐观置位草签化**（turn-start 确认网，`state.turnConfirmMs`=20s 可注入）：
  四条派发腿武装；引擎权威开turn 信号——`sessionBusy{busy:true}` / 终态帧
  （`clearBusyFor`）/ 本会话流帧（`armBusyFromStream` 内记账，含 `toolHeartbeat`
  族防长工具调用误判）/ 回执 / stop 键 / 看门真死收口——任一到场即撤；到点无
  turn 事实 ⇒ 判幻影收 busy + drain。depth>0 前缀会话（delegate-/subtask-/node-/
  dispatcher-/team-）**不武装**（引擎从不出 sessionBusy 帧、首帧延迟不可上界）。

**红线自检**：F1 补发语义零触碰（offline 入队/重连幂等补发/翻面照旧——确认网只
在「无任何 turn 事实」时收口，正常受理的补发帧被 busy{true} 即时撤网）；队列
语义零改动（drain 自身 per-turn 窗口闸 / ws 闸照旧）。修的是 busy 误判，不是队列。

## ③ 验证

- `tests/busy-lifecycle.spec.mjs`（新增，29 断言）：A 终态链回归钉（busy 清 + 队列
  恰派发一条）/ B 判重回执幻影（精确腿 + B2a 另会话流帧回归捕获器【旧全局守卫
  必红】+ B2b 前置流活动探针【回落 15s 窗必红】+ B3 真流不误杀负例）/ C 无回执
  确认网 + 幻影窗口排队 flush / D 慢 turn 不误清负例 / E 源面静态钉。
  **ALL PASS ×3 稳定**（`busy-lifecycle-all-pass.log`）。
- **验红实证**（`red-proof.log`）：对已提交正式版注两变异——MUTATION-1 精确腿失效
  ⇒ B2b 定向红；MUTATION-2 send 腿不武装确认网 ⇒ C2/C3/E2 红（C4/C6/D1 为
  幻影传染级联）。还原后复绿。
- 回归族（`regression-families.log`）：f1-send-queue ALL PASS（×3）/ resend-dedup
  ALL PASS（×3）/ msgqueue-recall-attach 6 passed / msgqueue-image-mime 5 passed /
  tool-heartbeat-timer 2 passed。
- `node --check` 四触碰文件零错误；checkJs 总量 258 < 基线 273（-15），本批 4 文件
  零新错误（2 处失败为 mobile-v3 遗留 new-file 错误，非本批）。

## ④ 手动验证路径（作者侧复撞场景）

1. **判重幻影**（原报障主形态）：任一会话正常收一轮回复后，立刻对同一条消息触发
   重发（「仍在处理」行重试 / resendLastInput 族）使服务端判重 ⇒ 修复前 busy 永久
   「正在运行」+ 后续消息入队不出；修复后回执到场即恢复可发送，排队消息照常
   flush（~百毫秒级，无 15s 等待、不受其他会话流活动影响）。
2. **静默无-turn**（无回执形态）：断网发送 → 重连补发若被旧服务端吞掉（无回帧）⇒
   修复前永久 busy；修复后 ≤20s 确认网自动收口（`[busy-confirm]` console.warn 可查）
   并 flush 队列。
3. **负例（不误伤）**：慢首帧 turn（busy{true} 已到、正文流延迟 >20s）spinner 全程
   保留至 done——确认网在 busy{true} 到场瞬间撤网，不误清。
4. **冻结搁置**：冻结窗外补发唤醒帧（服务端搁置不开 turn）⇒ 20s 后幻影 busy 收口，
   冻结输入条形态不受影响（frozenSessions 面零触碰）。

## ⑤ 文件与证据

- 源面：`state.js`（+sessionStreamActivity/turnConfirmTimers/槽位/turnConfirmMs）、
  `main.js`（确认网 + 回执重写 + 记账/撤网接缝）、`input.js`（四派发腿武装 + stop 键
  撤网）、`chat.js`（看门真死收口撤网）。
- spec：`tests/busy-lifecycle.spec.mjs`。
- 本目录：`busy-lifecycle-all-pass.log` / `red-proof.log` / `regression-families.log`。
- 提交：d7d1a062d（正式版）+ 本批 evidence/B2b 补充提交。
