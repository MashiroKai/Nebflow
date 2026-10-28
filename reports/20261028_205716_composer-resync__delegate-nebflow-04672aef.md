# 主会话回复完成后 composer 幽忙（红停止钮）排查与修复

## 结论

报障主根因是**重连后的主会话 busy 状态没有权威重同步源**：

- `done` / `sessionBusy{busy:false}` 是瞬时 WS 事件，断连黑暗窗内不重放。
- 重连后的 `historyPage` 能把已完成轮渲染出来，但前端 `busySessionIds` 仍是断线前镜像。
- 既有 `activeAgents` 快照同步只列在飞子智能体，并且在清 busy 时显式豁免 `state.activeSessionId`；主会话幽忙因此没有收口路径，composer 永远停在停止钮形态。
- 第二缺口：直接携带 `sessionId` 的 `agentDone` 原本只服务子代理面板行，不清 composer busy。带 `nodeSessionId` 的子代理终态则不能清父会话。

多气泡 / 多 round / text-stream 边界本身不是根因：正常轮的 done 链在修复前后都能清 busy。done 后服务端权威开启新轮时，composer 必须继续显示停止钮；该负例已钉住。

“后台忙”按最小改动处理为**语义可解释**：服务端只把 `AgentStatus.Processing` 映射为 composer busy；`Idle`（含后台任务等待）与 `WaitingForUser` 都是权威 false，不新增视觉态，后台 job 仍由既有任务/子代理面板解释。

## 改动面

### 前端

`src/main/resources/web/js/main.js`

- 新增 `applyAuthoritativeSessionBusy(sid, busy)`（约 724-751）：
  - `true`：恢复真实在飞轮，置 busy、记 turn 事实、撤确认网。
  - `false`：强制收 busy、清确认网/看门槽、落终态账、触发队列 drain，并重估本地冻结面。
- 新增 `sessionBusyStates` 帧消费（约 753-758），把服务端快照逐会话套用到上述收敛函数。
- `agentDone` 收口（约 2252-2259）：`msg.sessionId && !msg.nodeSessionId` 时走与 `done` 相同的 `clearBusyFor`；带 `nodeSessionId` 的子代理终态保持旧行为，不误清父会话。
- 重连对账（约 4315-4322）：向服务端发 `getSessionBusy`，请求范围为活跃会话 ∪ 所有本地 busy 会话；活跃主会话不再被豁免。

### 引擎 WS 协议

`src/main/scala/nebflow/gateway/WsSessionChatHandlers.scala`

- 注册并实现 `getSessionBusy`（约 39-63、1124-1156）。按请求 `sessionIds` 显式回 `sessionBusyStates{states}`；未知会话也回 false，避免“查不到就继续猜忙”。

`src/main/scala/nebflow/gateway/WebSocketRoutes.scala`

- 新增纯映射 `sessionBusyStates(registry, sessionIds)`（约 3108-3121）：只有 `AgentStatus.Processing` 为 true；`Idle` / `WaitingForUser` / 未知会话为 false。去重并保持请求范围。

### 验收与回归

- `src/test/scala/nebflow/gateway/ActiveAgentsFilterSpec.scala`：扩展 `AgentRecord` 构造，新增 `sessionBusyStates` 单测，覆盖 Processing、后台等待 Idle、WaitingForUser、未知 id、子代理 Processing、请求范围与去重。
- `tests/busy-lifecycle.spec.mjs`：新增/扩展多气泡轮 F、断连黑暗窗重连 G、直接 `agentDone(sessionId)` H、done 后真实新轮负例 J，以及 `getSessionBusy`/`sessionBusyStates` 桩与终态历史夹具。
- `tests/composer-resync-live.spec.mjs`：新增真隔离引擎 + 真 Chromium 红绿验收（下述）。

## 实机验证

### 真隔离实例红绿对照

`tests/composer-resync-live.spec.mjs` 用独立 `NEBFLOW_HOME`、assembly jar、OpenAI 兼容 SSE mock、真 Chromium 和独立 Bearer WS 探针跑同一剧本。红/绿只切换静态前端树；红使用修复前基线导出树，绿使用本支树。引擎 JVM 通过生产 stdin `q` 面自然退出，无信号收尾；浏览器/mock/home 均由 finally / delguard 清理。

- A 多气泡工具轮：UI 消息 → 文本气泡 + 真 Glob 工具卡 + 第二文本气泡 → done 后 send 恢复。红绿均过。
- B 断连黑暗窗：
  - 先让浏览器消费权威 `sessionBusy(true)`，再掐 WS 并封构造器；
  - 轮在黑暗窗内完成，独立探针捕到 done（实测 done 在进入离线后约 1.0s）；
  - 重连前旧前端停止钮幽忙；红树重连后仍幽忙，绿树重连发出 1 次 `getSessionBusy`、收到 1 次 `sessionBusyStates` 并恢复 send 形态。
- C 外部回执注入：`POST /api/callbacks/inject` → 注入气泡、composer 开轮停止钮、done 收口。红树因 B 幽忙连坐导致 C 前置失败（已在 red 判据中显式容忍），其余过；绿树全过。

终态输出：

- red：`[red] EXPECTED RESULT ACHIEVED`（病因断言 PASS：重连不清幽忙；C 前置连坐失败）。
- green：`[green] EXPECTED RESULT ACHIEVED`，关键读数 `{"asked":1,"answered":1}`。

前后截图：

- `.nebflow/evidence/20261028_composer-resync/red/B-offline-stale-stop.png`
- `.nebflow/evidence/20261028_composer-resync/red/B-after-reconnect.png`（仍停止钮 = 病因）
- `.nebflow/evidence/20261028_composer-resync/green/B-offline-stale-stop.png`
- `.nebflow/evidence/20261028_composer-resync/green/B-after-reconnect.png`（send 已恢复）
- 另有 `A-multi-bubble-idle.png` 与 `C-external-receipt-idle.png` 红/绿各一套。

### 桩链与既有回归

- `node tests/busy-lifecycle.spec.mjs`：ALL PASS（含 G2 权威查询、G3 幽忙收口、H1 `agentDone` 收口、J 新轮置位负例）。
- `node tests/console-input-direct.spec.mjs` / `f1-send-queue.spec.mjs` / `queue-idle-return.spec.mjs`：ALL PASS。
- Playwright Test：`turn-terminal-replay` 14 passed，`reconnect-collapse` 9 passed。
- Scala：`sbt testOnly nebflow.gateway.ActiveAgentsFilterSpec` + `compile` 全过（7/7）。
- `node scripts/check-js-types.mjs`：PASS，257 errors < 基线 260，无新增。
- `git diff --check` 干净。

## 隔离与安全

- 在产引擎（java pid 70481 / port 6138）未触碰；收尾后复查仅它在监听 6138，隔离 8690/8691 均关闭。
- 隔离 JVM 用 `GatewayMain.waitForQuit` 的 stdin `q` 退出；未向 sbt/java/nebflow 发送信号。
- 所有临时 `qa-composer-resync-*` home 已用删除守卫清理；`/tmp` 下的修复前基线导出树已核验内容后删除。

## 边界与说明

- 实机脚本的红绿差异面刻意钉在前端静态树：红树 + 新后端时，旧前端不发起 `getSessionBusy`，因此重连幽忙仍在；绿树 + 新后端完成查询/收口。
- `agentDone` 的子代理面不改：`nodeSessionId` 在场时仍只更新面板行，避免父会话被子代理终态误清。
- 服务端 busy 快照是当前 AgentRegistry 相位，不是历史重放；对断连窗内已完成且 registry 已回 Idle 的轮，这正是修复所需的权威 false。
