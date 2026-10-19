# 手机端消息「同步慢 + 丢」审计 — 判定矩阵（只读诊断）

时间：2026-10-19 17:10 – · 执行：手机同步审计 delegate（993d31ef）· 纪律：零源码改动、零 push；证据全部 file:line 实读核验（三条只读侦察腿 + 人工抽查）
口径：手机端 = 移动浏览器控制台，现役走旧 relay 腿（neblink.nebflow.space）。**服务端滑窗根修（link-server `fix/ingress-sliding-refresh` @6782d3b）已部署**——该修在 relay 自己的 HTTP 门（`require_user`）内，**与域名无关**：旧域/新域同一个 relay 进程，作者**不迁域也已吃到死窗根修**（520 取证 §修复2；迁新域对本组症状无额外收益，属卫生项 #512）。

## 0. 判定矩阵（症状 × 归属）

| # | 症状 | 归属 | 根因（一句话） | 关键证据 | 迁新域自愈？ | 结构修法 |
|---|---|---|---|---|---|---|
| S1 | 慢：登录 ~15min 后点击全部失灵/转圈 | **旧腿死窗（已治）** | relay 15min access cookie 无滑动续期、前端从不调 `/api/auth/refresh` ⇒ same-origin `/api/*` 裸 403 | 520 取证 §结论/§e（routes.rs:551-562, auth.rs:10）；本批无新测 | **否（已自愈）**：滑窗根修域无关，旧域同享 | 无需再修；死窗残余=0 |
| S2 | 慢：活窗内每次交互固有秒级延迟 | **结构性** | 每次隧道调用 ≥1-2.4× RTT（Mac↔VPS ping 264ms；铸票 med 635ms、兑换 med 609ms）+ 二进制 base64 **+33.4%** 线上字节 + 2.8MB 开图全链 ≈2.79s（62× 本地 45ms） | 538 计时表 §①、§结构常数（PortalWireCodec.scala:348 b64、PortalTunnelBridge.scala:1004 256KB 分片） | 否（物理 RTT + 编码都是隧道结构） | 见 F4/F5 |
| S3 | 慢：锁屏/切后台回来恢复慢 | **结构性**（死窗期叠加 reload） | 隐藏页心跳不计数（ws.js:662）⇒ 假开 zombie 最坏 3×grace(≤30s)≈**90s** 才判死；重连 backoff 2s→30s；重连后**全量 last-50 重拉 + 7 引导帧**；死窗期还得整页 reload 重穿隧 **5-10s/次** | ws.js:295-301/514-517/897-936；main.js:3777-3808；538 §④#1 | 部分自愈（reload 腿消失），探活+全量重拉仍在 | 见 F1/F2 |
| S4 | 丢-发送：WS 未开时按发送=无声消失 | **结构性** 🔴最大丢面 | `input.js:237-240` WS 非 OPEN **早退 return**（仅 console.warn）——纯文本 Enter **从不入队**；有界队列只救「到达 sendWs 的帧」（斜杠命令等） | input.js:237-240 vs ws.js:368-377（两套语义）；腿C 实读 | 否 | F1（小时级） |
| S5 | 丢-发送：已写 socket 的帧死在隧道 | **结构性** | WS 无应用层 ack（ws.js:347-352 注自认）⇒ `ws.send()` 成功≠送达；隧道 4 个静默丢弃点（§2.3）；服务端判重只在「收到时」生效 | ws.js:347-352；PortalTunnelBridge.scala:337/584/702-706/792-816 | 否 | F3（1-2 天） |
| S6 | 丢-发送：重连后自己发的气泡消失 | **结构性**（S5 的可见面） | 重连 historyPage **整替 DOM**（`innerHTML=''`）⇒ 服务端没收到的乐观气泡无声蒸发，无错误、输入框不还原 | main.js:1629-1648 | 否 | F3 附带 |
| S7 | 丢-接收：断开期引擎新消息 | **结构性**（历史兜底不完整） | 广播 fire-and-forget **零离线保留**（WsHub 只投已注册连接）；重连补投 = **last-50 + 快照**，>50 行要**手动滚屏**才知道；无游标自动补投 | WsHub.scala:87-96；main.js:3793（limit:50）；SessionStore.scala:1712-1720（offset/hasMore，无 since 游标） | 否 | F2（2-4 天） |
| S8 | 丢-接收：流式 delta 断线/刷新期间 | **结构性**（兜底有界） | delta 内存态不落盘；最终文本靠 SessionRecorder 在 done/toolEnd/system/user 落盘 ⇒ **turn 跑完的能救回，turn 中断的中间视图永久丢**（busyTail 平铺）；子 agent 运行中 getHistory 为空 | SessionRecorder.scala:49-80；ws.js:779-784；main.js:1647-1648 | 否 | 低优先（最终态有兜底） |
| S9 | 丢-接收：高负载/卡顿期 | **结构性** | 网关每连接出站队列 1024：流帧满**丢最旧**（delta 碎片永久丢）、控制帧满**拆连接**；隧道 WsCycle inbox 128 满整 cycle 丢 / 上行满**单帧丢**（WARN） | WebSocketRoutes.scala:945-971/1546；PortalTunnelBridge.scala:584/702-706 | 否 | 随 F2 游标补投一并自愈 |
| S10 | 丢-发送：死窗期间 | **叠加**（旧腿×结构性） | 已建 WS 死窗期**照常通行**（520 实证 22:13-22:29 对话通行）⇒ 死窗本身不丢发送；但 WS **一旦断开**，重连升级疑被同一 15min cookie 门拒 ⇒ 粘票入口，期间 S4 全量生效。⚠️ 升级路径门控行为在 link-server（Rust，仓未检出），此腿为推断非实证 | 520 §时间线；ws.js:398-402（拒手裁定格粘票入口） | **是**（滑窗已部署，该叠加面清零） | 已治 |
| S11 | resend-dedup（#569）修复后的**残余丢面** | **结构性** | 判重防「重复送达」，不产生「送达保证」：①write-success 不可判（S5）②ImmediateInput / skill / ask 的**服务端**去重未收口（帧已带 id，wire 先行）③服务端判重窗口=100 条（`takeRight(100)`） | resend-dedup README §0/§边界申报；AgentSession.scala:19-30；AgentProcessing.scala:1350-1362 | 否 | F3 |
| S12 | 丢-发送（边缘）：大附件帧 | **结构性**（边缘） | 附件 base64 内嵌 content 帧（input.js:324-328）；隧道组装上限 4MiB，超限 close 1009 ⇒ 整条 WS 断 + 该帧已写=丢 | PortalTunnelBridge.scala:1010-1012；PortalWsMessageAssembler（message-too-large ⇒ 1009） | 否 | F1 顺带（分片/预检） |

**一句话总判**：作者现在的「慢+丢」**主体是结构性缺口，不是死窗残余**。死窗（S1/S10）已被已部署的滑窗根修治好（域无关，不迁域也生效）；剩下的是一个**无 ack、无游标、无离线保留**的尽力而为（best-effort）同步面：发送侧 S4 是最大无声丢面（idle+WS 未开=直接丢），接收侧 S7 是结构性缺口的根（无离线补投，last-50 只是碰巧兜底）。

## 1. 发送路径解剖（client → WS → 隧道 → 引擎）

链路：`input.js send()` →（busy? 排队 : 直发）→ `ws.js sendWs()` →（OPEN? 写 socket : pendingSends 有界队列）→ 隧道 ws_frame（host 侧 `PortalTunnelBridge` WsCycle 上行 inbox 128）→ relay → 引擎 `WebSocketRoutes` → AgentActor。

- **WS 未开的两套语义**（不一致 = 丢面）：
  - `sendWs`（ws.js:368-377）：入 `pendingSends`（cap 100，**溢出丢最旧**，内存态不持久化，reload 即清），onopen 先于引导帧按序补发（ws.js:640），补发中途失败退回队首停轮（ws.js:353-365）。
  - `input.js:237-240`：**早退 return**——busy 排队腿（:226-235，`queueMessage`+persistQueue 持久化）在它**之前**，所以只有「闲态纯文本 / skill / ask / compact / 立即发送 / drain」走早退 = **静默丢，零 UI 反馈**。
- **死窗期行为**：已建 WS 通行 ⇒ 发送不受死窗影响（520 §时间线物证：tunnelReq 平线期对话照常）。WS 断开+死窗 ⇒ 重连升级疑被门拒（S10，推断面）。
- **幂等重发（#569）现状**：clientMessageId 生成于 input.js:272、**跨重发稳定**（重试/排队/立即发送全腿同 id）；服务端三丢弃点（idle 直投 AgentIdle / 冻结唤醒 AgentFrozen / busy 排队 AgentProcessing.scala:1350-1362「查而不记」）均回 `messageDuplicate` 回执（AgentSession.scala:43-56），客户端回执收幻影 busy/看门（main.js:1259-1274）。**覆盖=判重；不覆盖=送达保证**（S5/S11）。

## 2. 接收路径与离线丢失

- **2.1 引擎出站**：`WsHub.broadcast` 序列化一次、投给每个已注册连接、**错误吞掉**（WsHub.scala:87-96）；**无按会话过滤**（R1b 未做，注释自认）——手机收到全部会话的帧、浏览器侧自行过滤。每连接出站队列 1024（WebSocketRoutes.scala:1546）：流帧满丢最旧 / 控制帧满拆连接（:948-971）。
- **2.2 断开期**：连接 unregister ⇒ 期间引擎产出的帧**不存在任何离线保留**（WSSessionStore 无积压；恢复=磁盘历史）。
- **2.3 隧道丢弃点（四道）**：①BodyChannel 64 满⇒整 cycle 取消无响应（PortalTunnelBridge.scala:337/348）②WsCycle 下行 inbox 128 满⇒整 cycle 丢（:584）③上行满⇒**单帧丢** 仅 WARN（offerUp :702-706）④隧道断开 teardown **弃全部在途缓存**（:792-816）。Portal 帧无 per-frame ack ⇒ 丢失只表现成浏览器侧超时/静默。
- **2.4 重连补投 = last-50 + 快照**：`onReconnect`（main.js:3777-3808）= getHistory{limit:50} + getPendingAsks + getTaskList + getActiveBgTasks + getActiveAgents + listSessions；AskUser 卡片有 `replayed:true` 重放（WebSocketRoutes.scala:320-332）。**无 sinceSeq/游标**：UiMessage 无 id/seq（shared/protocol.scala:260-370），唯一「游标」= ui.json 数组下标（SessionStore.scala:1712-1720 的 offset/beforeIndex），**可支撑 but 未做**增量补投。
- **2.5 「消息离线 TTL 三态分化」现状核实**（🟡 项；⚠️ 该字面串全仓/主仓/~/.nebflow 均检不到，以下为代码实证的三态）：

| 态 | 通道 | 离线保留 | 常数出处 |
|---|---|---|---|
| ① 浏览器 WS 活帧 | WsHub 广播 | **0（无保留）**，恢复=磁盘历史（无 TTL 但受 50 行窗约束） | WsHub.scala:77-96 |
| ② 客户端断线期发送 | pendingSends | cap 100、无 TTL、**丢最旧**、内存态（reload 清） | ws.js:337/368-377 |
| ③ DeviceMail（跨设备伴生通道） | relay 设备邮包 | **at-least-once 直到 ack**（AckTimeout 15s / EarlyAckTtl 30s cap 64 / 判重账本 2048 仅进程内重启清） | DeviceMailAck.scala:60-72; DeviceMailInbox.scala:30-97 |

**手机控制台命中的是最差态①**：活帧零保留，唯一兜底=SessionRecorder 落盘的历史（turn 终态文本、user、system；流式 delta 与 agentStart 不在）。DeviceMail 的可靠腿（③）与浏览器控制台**不相通**（它注入 agent 会话，落回的只是普通广播帧的历史形态）。
- **2.6 刷新丢面**：刷新=WS+内存全清（pendingSends/sessionTexts 丢、busy 排队除外——persistQueue 持久化）；重拉 last-50；turn 中断的中间流式视图丢（busyTail 平铺）；**未送达的乐观气泡被整替 DOM 无声抹掉**（S6）。

## 3. 慢的解剖（数字）

| 分段 | 实测/常数 | 出处 |
|---|---|---|
| 隧道 RTT 地板 | ping 264ms avg | 538 §结构常数 |
| 每次隧道 REST 调用 | 铸票 med **635ms**（≈2.4×RTT）、兑换 med **609ms** | 538 §①远程腿 |
| 字节穿隧 | 2.8MB=11×256KB 帧，med **1531ms**；全链 ≈**2.79s**（62× 本地 45ms） | 538 §① |
| 线上膨胀 | base64+JSON 信封 **+33.4%**（≈字节段 25%，2.8MB 图 ≈+380ms）；**ws_frame 对 JSON 文本帧同样套 base64** ⇒ 聊天每帧也吃 +33% + ~150-200B 信封 | PortalWireCodec.scala:348/338-343; PortalTunnelBridge.scala:1004 |
| 每条消息往返数 | 发送=1 WS 帧 **0 REST 0 ack**；接收=推送帧；历史=**1 WS 往返/50 行**；图片=铸票+兑换 2 隧道往返 + 字节 | ws.js/input.js/main.js 链路 |
| 历史同步形态 | **非增量**：每次连接/重连/切会话全量重拉 last-50（行内联全文，未截断——50 行典型几十 KB、肥 content 可 MB 级）；更早历史只能手动滚屏 50 行/趟 | main.js:1616-1648/3793; sidebar.js:2128-2161 |
| 回前台恢复 | 隐藏页心跳不计 miss（ws.js:662）⇒ zombie 最坏 3×grace(≤30s)=**90s**；正常 CLOSED 秒判，重连 1.6-2.4s（backoff 2s^n cap 30s ±20%） | ws.js:295-301/514-547/897-936 |
| 死窗期 reload（已治） | 整页重穿隧 **5-10s/次**，60s 冷却 | 538 §④#1; sessionGate.js:31-33 |
| 发送看门（无 ack 的代偿） | 预算 (streamTimeoutMs 600s+30s)×{1,2,4} = 10.5/21/42min；「连接丢失」卡只在顶档且 !wsAlive 才现 ⇒ **最坏 ≈73.5min 才有可见失败** | chat.js:1600-1686; state.js:218 |
| 隧道自愈常数 | 心跳 3s、liveness 9s、backoff 0-30s 阶梯、StableConnectionMs 30s、RelayFrameSender 锁 15s | NeblinkRelayTunnel.scala:1434-1560; PortalTunnelBridge.scala:996 |

## 4. 结构修法候选（按收益×量级排序）

| # | 候选 | 关的洞 | 量级 |
|---|---|---|---|
| F1 | **input.js 早退腿改走 sendWs 队列**（或可见 toast+自动入队；同 id 已有幂等轨），顺带给 pendingSends 掉线持久化（复用 persistQueue 轨）+ 溢出可见告警 | S4/S6 入口、S12 预检（>3MB 附件先拦） | **小时级**；#569 已把幂等键铺好，本项只改「谁入队」 |
| F2 | **增量游标补投**：UiMessage 加单调 per-session seq（或先用现成 ui.json 下标），getHistory 增 `after=` 变体；客户端记 lastIndex，重连只拉增量、缺口自动翻页补齐 | S7（根）、S3 慢的重拉面、S9 高负载丢帧 | **2-4 天**（SessionStore+WsSessionChatHandlers+ws.js/main.js+spec） |
| F3 | **发送侧送达账本**：client 端 inflight 集合（id→帧），onopen 对 inflight 补发（服务端 #569 判重已兜「已送达」腿）＝ at-least-once + 幂等接收者；顺带收口 ImmediateInput/skill/ask 服务端判重（wire 已先行） | S5/S6/S11 | **1-2 天**（Trigger 层新建；判重轨现成） |
| F4 | 网关每连接 replay 环（近 N 条 seq 环 + register 时按游标重放），或 WsHub R1b 按会话过滤+每会话缓冲 | S7/S9 的服务端半边 | 2-3 天（与 F2 二选一或互补） |
| F5 | 隧道二进制帧（去 base64）+ nf-file Cache-Control patch（538 候选#2 patch 已备） | S2 慢：字节 -25%、重复开图 0 字节 | codec 2-3 天 / patch 即用 |
| F6 | 回前台快路径：visible 先发 ping 探活（500ms 短宽限）再决定 close，替代 3×30s 阶梯 | S3 的 90s 尾 | 半天 |

**不做/降级**：流式 delta 落盘（S8）——终态有 SessionRecorder 兜底，中间视图丢可接受；DeviceMail 腿接到控制台（三态③→①打通）——大改，F2/F4 达成后无必要。

## 5. 证据索引

- 死窗：`evidence/20261019_001800_imgdoor520-forensics.md`（§结论/§e/§时间线/§修复）
- 慢的量化：`evidence/20261019_082500_imgslow538-segmented-timing.md`（§①计时表/§结构常数/§④候选）
- resend-dedup 修复批：`evidence/20261019_161500_resend-dedup/README.md`（§0 根因/§边界申报）
- 代码（本 worktree 实读核验）：ws.js:295-377/514-547/640-664/897-936；input.js:226-335；main.js:1259-1274/1616-1648/3777-3808；sessionGate.js；AgentSession.scala:14-60；AgentProcessing.scala:1348-1366；WsHub.scala:24-96；WebSocketRoutes.scala:940-1012/1546；PortalTunnelBridge.scala:281-357/561-620/702-716/792-816/1004-1012；NeblinkRelayTunnel.scala:1434-1560；SessionRecorder.scala:49-80；SessionStore.scala:1712-1720；DeviceMailAck.scala:60-72；DeviceMailInbox.scala:30-97
- 未决/推断面：①link-server（Rust）仓未检出 ⇒ 死窗期 **WS 升级门控行为**（S10）与 relay 侧常数为推断/取证转述，非仓内实证；②「消息离线 TTL 三态分化」字面审计项未检索到，三态为代码实证重构（§2.5）；③本批零实测（纯代码+既有取证），远程腿数字全部转引 538 真会话实测。
