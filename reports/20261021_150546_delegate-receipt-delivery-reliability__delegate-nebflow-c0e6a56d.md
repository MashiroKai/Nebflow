# delegate 回执投递可靠性（平台修复批）— issue #31 phantom slot 的治本四件

2026-10-21 · 执行 delegate `delegate-nebflow-c0e6a56d` · **引擎侧改动候重启窗生效**（HeldDeliveryWatchdog 在 GatewayMain boot 装配，当前在跑的引擎进程需重启才装载）

## 一、机制链复盘（今日实证，六支受害：深夜三支 + 403 诊断 + 旧域清扫 + Pop 桥）

```
delegate 干完活
 → 回执 ExternalEvent(source=delegate) 到达父会话
 → 子代理批屏障（drainBarrier）在 outstanding>0 时 HOLD 进 pendingEvents（内存）
 → 屏障槽位成幻影（成员 hang / 停止失败，永不归还——issue #31）
 → 会话无 wall-clock 超时（10-19 拍板，不动）
 → 永久 Idle，回执烂在队列，「直到重启才可能注入」
```

实证面：Root 的 AgentControl barrier 列挂着 `1/1`（outstanding=1 / held=1）——监管面可见、父会话不可见不可消费。旧行为下唯一出口是重启（内存台账随 actor 消亡）。

## 二、治本四件（对应作者令逐条）

### fix 1 · 投递重试 + 终态落盘 + 事件先补注入

- **持久台账** `HeldReceiptStore`（`core/processor/HeldReceiptStore.scala`）：`<dataRoot>/sessions/<sid>/held-receipts.json`，每条 held 回执一行（id=correlationId 优先 / 载荷摘要兜底、producedAt、attempts、exhausted 旗）。**载荷**仍由既有 F2 层 `CompactionQueueStore`（injection-queues.json）持久——回执本体跨重启存活，台账只做簿记（产出时刻/催注计数/终态旗），两盘分离、职责单一。
- **指数退避催注**：`HeldDeliveryPolicy.delayMs` 10min → ×2 → 30min 封顶；`HeldDeliveryMaxAttempts = 5`。
- **穷尽终态**：催注 5 次仍 held ⇒ `exhausted` 旗落盘（终态落盘：回执本体已在 injection-queues.json + SubAgentTaskStore 终态行 + 台账 summary 头），调度器停催，WARN 带 payload 头出审计面。
- **事件先补注入**（`AgentActor.heldFlushPlan` + idle 三腿接线）：父会话任何后续事件——**用户消息/Mail 队列投递/窗冲刷载体（节点通报）/非子代理 ExternalEvent（background-task·Schedule 类）**——先把全部 held 回执以 `buildEventReminder` 合批注入本回合（**前置于**该事件消息，1 次唤醒），再处理该事件；同时归还幻影屏障槽位（outstanding 清零）。

### fix 2 · Idle 挂起自检（投递可靠性自检，不是杀任务）

- `HeldDeliveryWatchdog`（`core/processor/HeldDeliveryWatchdog.scala`，GatewayMain boot，60s 周期）：扫 registry 中 **Idle** 会话的台账，到期（producedAt+退避）未注入 ⇒ 发新命令 `AgentCommand.DrainHeldReceipts` 催注（attempts+1）；Processing/frozen 态收到该命令为 no-op 留任（turn 边界 drain 兜住，调度器本就按 Idle 过滤）。**只催注回执、绝不杀会话**——「无超时」哲学逐字不动。
- idle 态收到催注 = **强制补投**：全部 held 回执合批一 turn（形状同批完成汇聚腿）+ 系统气泡（source=system，父会话可见）+ outstanding 清零；队列已空（正常 drain 抢先）⇒ 幂等 no-op 仅对齐快照。

### fix 3 · barrier 可见化（任务栏/面板徽标）

- 新 WS 帧 `heldReceipts {sessionId, held, unread}`：actor 的 hold/drain/flush 点（会话面）+ watchdog unread 变化（hub 广播面）双发射。
- **前端**：`#held-receipts-indicator` 头部胶囊（同 pending-asks 胶囊族，警告色，N>0 显示计数、归零即隐；`nebflow-session-change` 重渲；header 碰撞链 tier-1 登记）+ i18n 词条（en/zh-CN）+ activeAgents 快照新字段 `heldReceipts`/`unreadReceipts`。
- **AgentControl**：list 的 barrier 列 `o/h(+u)` 形态；status 面新增 unread 终态行 + 脚注注明 watchdog 语义。

### fix 4 · 等待态显式化（等合并轮提醒的会话进显式停靠）

- 新 `AgentStatus.Waiting`（`actor/AgentState.scala`）：写点 = BackoffSupervisor 终态判定机 `HoldForMerge` 腿（delegate ready-to-merge 被 MergeQueue 停靠时 registry 状态置 Waiting + `delegateWaiting` WS 帧）。
- **等待屏障语义**（与 WaitingForUser 同款）：TaskStuckWatcher 显式豁免（守卫注释面）；`MailIdleGate` 视同可投递（停靠会话的已产出报告照样走投递链，合并轮 Mail/续聊可达）；清理面零新增——终态腿 `notifyParentAndStop` 本就整行移除 registry。
- **前端**：Sub-Agents 行状态机新增 `waiting`（`bgRowState`：done > stuck > waiting > frozen > …；`bg-status-waiting` 中性静点 + 「等待合并轮」标签）+ bgAgentPopup 快照恢复映射 `Waiting`→waiting + `delegateWaiting` 帧实时置位。

## 三、改动清单

| 文件 | 面 |
|---|---|
| `core/processor/HeldReceiptStore.scala`（新） | 持久台账 + 重试策略纯函数 |
| `core/processor/HeldDeliveryWatchdog.scala`（新） | 60s 扫描器：催注/穷尽/徽标（scanCore 纯决策面可 spec） |
| `actor/AgentCommand.scala` | `DrainHeldReceipts` 命令（idle 强制补投 / processing·frozen no-op） |
| `actor/AgentState.scala` | `AgentStatus.Waiting` + `AgentRecord.unreadReceiptCount`（watchdog 独占写） |
| `agent/AgentActor.scala` | `syncHeldReceipts`/`emitHeldReceiptsBadge`/`heldFlushPlan`/`flushHeldSideEffects`/`emitForceDrainVisibility` 助手（决策/副作用分离，决策面可 spec） |
| `agent/AgentIdle.scala` | 催注强制补投分支 + idle UserInput/窗载体/非子代理 ExternalEvent 三腿「先补注入」 |
| `agent/AgentProcessing.scala` | ExternalEvent 入 held 台账对账 + 催注 no-op 分支 |
| `agent/AgentFrozen.scala` | 催注 no-op 分支（冻结门不穿透） |
| `agent/AgentFinishTurn.scala` | drainBarrier 投递面台账对账 |
| `agent/BackoffSupervisor.scala` | HoldForMerge → Waiting 停靠 + delegateWaiting 帧 |
| `core/processor/TaskStuckWatcher.scala` | Waiting 豁免守卫 |
| `core/flow/MailIdleGate.scala` | Waiting 视同可投递 |
| `core/tools/AgentControlTool.scala` | barrier 列 `o/h(+u)` + status unread 行 + 脚注 |
| `gateway/WebSocketRoutes.scala` | activeAgentEntryJson + `heldReceipts`/`unreadReceipts` |
| `gateway/GatewayMain.scala` | watchdog boot 装配（候重启窗生效点） |
| `shared/Defaults.scala` | 扫描周期 60s / 首催 10min / 封顶 30min / 穷尽 5 次 |
| 前端 `index.html` `css/chat.css` `js/main.js` `js/main/bgAgents.js` `js/bgAgentPopup.js` `js/locales/{en,zh-CN}.js` | 头部胶囊 + Sub-Agents waiting 行 + 快照字段消费 + 词条 |

## 四、台账一致性矩阵（谁写谁清，交叉面）

- 写（producedAt）：`AgentProcessing` ExternalEvent 入 held 点（processing 期到达即记，**不等 turn 结束**——watchdog 的「N 分钟未投递」时钟从回执产出起算，不受长 turn 遮蔽）。
- 清（投递）：idle 批完成汇聚腿、`finishTurnCont` drainBarrier 投递面、三腿先补注入、催注强制补投——均 `syncHeldReceipts(sid, 当前pendingEvents)` 对账（簿记保真：producedAt/attempts 跨对账不重置）。
- 幂等短路：对账集合身份未变 ⇒ 零重写（高频 hold 点不刷盘）。

## 五、语义边界（刻意不做 / 已知留口）

1. **批纯洁性让步**：催注/先补注入会提前注入部分回执（仍在飞的真实成员此后走 idle 单件腿照常注入）。这是 fix 2 的题中之义——「批聚合」让位「回执必达」；正常批次（无幻影）不受影响（到期前批已完成汇聚注入）。
2. **crash 窗**：先补注入到 LLM 消费之间的消息未落盘窗口与既有批完成腿同形态（F2 既定范围），未扩面。
3. **持久 Delegate**：其完成事件走 source=address 轨、不构成批屏障增量（countBarrierIncrements 既有豁免），本批零触碰。
4. **restarted 会话**：RecoverPersistedQueues 重放 pendingEvents（载荷面）+ 台账簿记面跨重启存活——重放后 hold 点再对账即闭环，无新增迁移面。

## 六、验收证据

- 新 spec 三件套（见 git 提交）：
  - `HeldReceiptStoreSpec`——P1 回执身份单点（correlationId/摘要兜底）、P2 退避节奏 10→20→30 封顶 + 到期边界、P2c 穷尽判定、S1 对账（新记/出账/簿记保真）、S2 记账幂等 + exhausted 终态 + unread 面、S3 容错（损坏/缺键/空 sid）；
  - `HeldDeliveryWatchdogSpec`——W1 到期催注、W2 未到期/空台账/已穷尽零动作、W3 穷尽⇒Exhaust+BadgeSync（停催 + 徽标对账）、W4 快照一致无广播、W5 attempts 窗口、W6 混合批次部分催注；
  - `HeldDeliveryFlushSpec`——F1 空队列 None（零影响）、F2 先补注入计划（reminder 前置 + 槽位归还 + 其余队列保真）、F3 reminder 单点形态、W1 MailIdleGate Waiting 可投递、W2 Waiting 判别词面。
- 分层门禁：本批零新增反向边（`check-scala-layers.mjs` 既有 cli→gateway / core↔dropbox 失败在基线逐字复现，与本批无关）。

## 七、引擎侧候重启窗注记（作者令要求项）

本批全部生效点都在**引擎进程内**：watchdog 扫描器（GatewayMain boot）、idle/processing/frozen 三态的 `DrainHeldReceipts` 分支、Waiting 停靠写点。**当前在跑引擎需重启装载**；重启前旧行为维持（回执仍可能烂队列——重启本身恰是旧形态下 held 回执的注入出口，重启后新台账/调度器即刻接管）。前端资源随构建刷新即可，无强耦合。
