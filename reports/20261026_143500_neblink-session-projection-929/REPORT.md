# REPORT · 台账 #929 — 后台子智能体跨设备透明（neblink 投影 + 面板注册表面）

- 日期：2026-10-26（KAI 设备原生批）
- 作者令：2026-10-26 13:51 逐字「多设备设计的核心就是透明，跑在哪个设备上，那我们的显示都应该都能看到。**不应该说只显示Mac引擎自带的子智能体**」
- 分支：`fix/neblink-delegate-projection-929`（自 `main@85a79ff74` 开出）→ 自合并 KAI main（本批合并职责在 delegate，任务书第 5 条授权）
- 变更面：11 files（2 新 Scala + 1 新 JS spec），+~640/−10

## 1. 现状断点（代码锚）

1. 后台子智能体的引擎侧权威 = `SubAgentTaskStore`（`~/.nebflow/subagent-tasks/<parent>.json`，按父分文件）——**纯本机数据根**，跨机零投影；delegate 会话原生跑在 KAI 时，Mac 引擎对这批会话零感知。
2. 前端面板（`#bgagent-indicator` → 合并载体 `#bg-dropdown` 智能体节）的数据源 = 本机引擎 WS 帧（`agentStart`/`activeAgents` 快照）+ 本机桶 `state.sessionBgAgents[view.sid]`——可见性断在设备边界，面板永远只显示本机引擎自带的子智能体。
3. 既有跨设备设施盘点：`AgentIdentitySync`（identity-sync 批，presence `sendData` 优先 + relay `relayNotify` 兜底双腿 + DeviceHmac 配对通道 + peer 变化对账推）与本批需求同构，**通道可直接复用**；`bg-device-origin-chip` 批（#928 族）解决了「后台**任务**跨设备可见」，本批补齐「后台**子智能体**」的孪生面。

## 2. 方案（AgentIdentitySync 同族同构，零新通道）

**一种载荷 `session-sync`（单会话摘要），两种触发，两条收端落地：**

| 路径 | 实现 |
|---|---|
| 投影（写后外推） | `SubAgentTaskStore` 五个记账写点（recordTask / updateStatus / markRevived / removeTask / cancelRunningForParent）成功后触发 `setWriteHook` 钩（`.start` 派 fiber，fire-and-forget，绝不阻塞记账路径）；单行 `SessionPayload` 经 DeviceHmac（配对 sharedKey，ts ±90s 窗 + nonce，逐目标签署）双腿外推到全部已配对设备 |
| 对账推（重连/发现） | `addPeerChangeCallback(pushAllSessions() *> broadcastRemoteRows())`——boot 后首联 / 对端重连 / 新配对 / 离线事件都触发全量外推（单飞闸防帧暴，identity 同款）；peer 变化同时重播 `remoteAgents` 帧（在线角标现算重刷） |
| 收端收敛 | `RemoteAgentSessions` 投影仓：键 `(fromDeviceId, sessionId)`，LWW（仓内无行 = **首见即收**——乱序到达的 spawn 行不因先到的更新行丢失；严格更旧 = 丢；等/新 = 覆盖）；持久面 `<dataRoot>/neblink/remote-agent-sessions.json`（AtomicJson 整表原子写，重启载盘）；单设备行数帽 200（剪最旧终态、再剪最旧） |
| 面板注册表面 | 收端广播 `remoteAgents` UI 帧（`wsHub.broadcast`）+ `getActiveAgents` 快照回复**捎带直发**（刷新/重连恢复，零新请求型）；前端 `onMessage('remoteAgents')` 分设备重组进 `state.remoteBgAgents`，`renderBgAgentDropdown` 在本地行后缀远端行组 |

**摘要纪律（红线）**：载荷只含 sessionId/标题（首行+截断 200 字符）/source/status/起止时间/retryCount/设备归属——**prompt 全文与会话内容永不上机**；单载荷 <2KB（spec 钉，实测 ~430B）；验签失败帧可读 WARN 且不含载荷体。

**语义决策表：**

| 决策 | 依据 |
|---|---|
| 远端行**不入** per-session 徽标计数 | 徽标口径 = 视图会话桶（author ruling 2026-09-14「只显示运行中的，计数也只计算运行中的」是会话粒度）；远端行是跨会话集，计入会错误归属（spec P5 钉：本地 2 行 + 远端 1 行在板 ⇒ 计数恒 2） |
| **本地行胜**去重（同 sessionId 远端孪生抑制） | DeviceDelegate 形态下同一 spawn 在 host 侧与执行侧各有任务行（`DeviceDelegate.scala:563` 实证），不加抑制则同一批次渲染两行；本地行更富、可交互——与 agentStart/activeAgents 的 cross-keyspace dedupe（W1-d）同一条规则（spec P4 钉） |
| 终态行**揭示窗 30s** 后不进帧（账面保留） | 与本地「registry 完成即注销」的快照口径同律：实时终态帧到达即翻面、前端 2s 清理；刷新恢复时窗内终态行仍可见（跨设备延迟余量），窗外只留投影仓账面。窗只作用显示过滤、不删账——非超时设计 |
| 剪行 = **显式墓碑**（status="removed"）外推 | LWW 收敛下「缺席」不是消息：origin `removeTask`（终态宽限后清扫）若只靠对账缺席推不出，对端在飞行会滞留成幽灵；墓碑戳 = 剪行时刻（必新于终态戳），对端据此退役该行 |
| 断联 = 行保留最后态 + 「设备离线」角标 | 在线性是**传输面状态不是账面状态**：不落仓，帧发送时刻按 peer 新鲜度（`NeblinkService.isPeerOnline`，90s 下限 + 2×sync-interval 松弛）现算；离线行原样渲染（不消失不假装死亡），重连由对端对账推 + 角标重播自愈（spec P3 钉） |
| 防回声靠结构 | 远端收敛写入只落 `RemoteAgentSessions` 投影仓，不经 `SubAgentTaskStore` ⇒ 写钩永不因远端帧触发，结构上无回推路径（identity 同款） |
| 远端行展示专用（无点击下钻） | 跨设备会话详情流（popup 历史恢复/实时流）不在本批边界；行无点击锚（spec P1 钉），后续批可挂设备侧代理通道 |

## 3. 关键实现点

- **`AgentSessionSync.scala`（新）**：载荷构建/编码/宽容解析（缺字段 fail-closed，同 DeviceMail 纪律）/逐目标验签全部纯函数；装配缝（sendFn/selfIdFn/deviceNameFn/allTasksFn/onlineFn/panelBroadcastFn/store，`private[neblink]`）——spec 注入假发送/假在线做写读路径单测（identity 同款）。
- **`SubAgentTaskStore`（改 +58 行）**：`setWriteHook` 单点 + 五写点后置触发；`StatusRemoved` 墓碑常量（伴生对象）。记账语义零改动——钩在锁外、脱钩执行、异常只留日志。
- **`GatewayMain`**：`AgentSessionSync.initialize(Some(neblinkService), subAgentTaskStore, wsHub.broadcast)` 与 `AgentIdentitySync.initialize` 同缝同序（boot 单点）；未接线（NebLink 未配置）⇒ 显式 WARN + 跳过，**写钩不装**（单机形态记账面零触碰）。
- **`WsSessionChatHandlers.handleGetActiveAgents`**：activeAgents 帧后 `pushRemoteRowsTo(wsSend)` 捎带——投影仓空 = 零帧。
- **前端**：`state.remoteBgAgents`（分设备仓）+ `onMessage('remoteAgents')` 唯一消费点（重组/去重/终态 2s 清理 + `dismissedRemoteRows` 记忆化——同戳重发不复活，snapmerge「done 单调」教训同款防线）+ `bgRemoteRowHtml`（状态点/状态词/kind 徽标/uptime/重试片全复用既有行语言 + `.bg-task-device` 设备角标（title 复用 `bg.runsOn`「跑在 KAI」——与 bg-device-origin-chip 批同轴同词）+ `.bg-task-offline` 琥珀角标（`--status-warn-icon` 既有 token，零新色））；`remoteAgents` 入 ws.js GLOBAL 路由集（无 sessionId 帧不抢视图——`projectCreated` 同律）。
- **终态清理时序**：帧含终态行 ⇒ 行即时翻「已完成/失败」⇒ 2s 后从投影态移除；`dismissedRemoteRows`（Map，rowKey→updatedAt）拦截揭示窗内快照捎带的重发——已清理终态行不同戳不复活。

## 4. 门禁读数

- `sbt testOnly nebflow.neblink.AgentSessionSyncSpec nebflow.neblink.AgentIdentitySyncSpec nebflow.gateway.ActiveAgentsFilterSpec nebflow.gateway.ActiveAgentsEntrySpec` → 见 §6（运行时回填）。
- 前端渲染钉 `node tests/remote-bg-agents.spec.mjs`（自包含 harness，stub WS + 静态服务器）→ **28/28 ALL PASS**：P1 在线行入板（状态词/kind/设备角标/title/uptime/无点击锚 7 断言）、P2 终态实时翻面 + 2s 自清 + 同戳重发不复活、P3 断联保留 + 「设备离线」角标 + 重连补推角标消失 + 重试片、P4 本地行胜（孪生抑制/本地行原样/名不被覆写）、P5 徽标计数红线（远端不入计数）、E1-E6 源面静态钉。
- identity-sync 族回归全绿：`agent-identity-sync.spec.mjs` ALL PASS（P1-P5+E 全量）；`bg-merged-panel.spec.mjs` 3 passed；`bg-device-origin-chip.spec.mjs` 3 passed（「跑在」轴对照行逐字回归）；`bgagent-dedupe.spec.mjs` 2 passed。
- `node scripts/check-js-types.mjs` → checkJs PASS（260 errors = baseline 260，零新增）。
- 全仓 Test 树编译通过（含 GatewayMain/WsSessionChatHandlers/SubAgentTaskStore 接线面）。

## 5. KAI main 与 Mac main 拓扑口径（step-0 取证）

- **bundle 同步第 0 步已验**：Mac main `21f19dfafdc42cfccfb060c2962aa2d7666ca81f` 经只读 Mac 面直读，`git merge-base --is-ancestor` 判定**已是 KAI main 祖先**（#910 批已同步）——零传输需求；`bundle` remote 引用已不在 .git/config（陈旧 refs 残留，bundle/main ⊂ main 115 commits），KAI main = dev 主线。
- 本批在 `fix/neblink-delegate-projection-929` 开发 → 自合并推进 KAI main；Mac 仓只读未触碰。
- 同仓并行纪律：共享 checkout（`C:/Users/Kai/stand`）上另有六批在飞的未提交 WIP，本批全程在独立 worktree `C:/Users/Kai/stand-929` 进行，未触碰他批工作区。

## 6. 开放项（诚实清单）

1. **静默陈旧角标界**：peer「静默老化」（lastSeen 超窗但无离线事件）不触发重播——角标等下一次 peer 变化或任一 session-sync 帧才翻转。与 identity-sync 同口径（设计零超时，无新增定时器）；如需即时翻转，挂 `syncLoop` 既有 45s 拍即可，一行改动，待作者裁定。
2. **两端运行时验证待做**：本批为引擎+前端全链路实现 + 双端 spec 钉，未做两台真机对跑观测（KAI/Mac 引擎都需运行新代码）；真机验收路径 = KAI 起 delegate → Mac 面板见行（设备角标 KAI）→ KAI 断网 → Mac 行保留 + 离线角标 → KAI 重连 → 行刷新。
3. **Mac 侧面板消费前提**：Mac 引擎升级到含本批构建后，`remoteAgents` 帧才有生产者；旧 Mac 引擎收帧面静默（kind 不识即弃，无兼容风险）。
4. **远端行点击下钻**未做（见 §2 决策表末行）——后续批可经既有 DeviceMail/文件通道挂「读远端会话历史」面。
5. 投影仓终态行保留有界（单设备 200 行帽）但无时间窗——如作者要「账面只留 N 天」，一处 prune 条件可加（现按帽剪最旧终态，账面永不无界膨胀）。
