# Header 待答徽标僵尸态 · 诊断修复批 —— 重启孤儿对账

2026-10-22 · 执行 delegate `delegate-nebflow-06572840` · **前端改动刷新即生效；引擎侧（hub 记账 + boot 对账）候重启窗生效**

## 一、诊断结论

### 1. 计数源（那条「1」是谁家的数）

Header 橙色徽标 = `#pending-asks-indicator`（`index.html:238`，chat.css 警告色胶囊族），计数 = **前端镜像** `askPending.js` 的 `pending` Map（`renderBar` → `.pa-count`）。镜像由两路驱动：hub 的 `askUser` 帧（live + 重放）与重连时的全局快照（`getPendingAsks` → hub `ListAllPendingAsks`）。**权威在 hub 内存 pending map（修复前无盘上形态）。**

实测（WS 探针 `getPendingAsks`，10-22 11:00 对在跑引擎）：`{failed:false, count:0}`；全天日志每次快照均 `0 card(s)`（09:51–10:54 十连查全零）⇒ **徽标上的「1」是纯客户端残留**：某标签页镜像里挂着一条槽位已死的条目，其所在标签页自提问入账起未再成功对账（WS 半开/休眠，收不到关卡帧也跑不了重连快照）。

### 2. 那条孤儿问题的身世

| 项 | 值 |
|---|---|
| requestId | `asknb-6554022b93bb4aa7` |
| 提问时刻 | 2026-10-20 12:58:03 |
| 提问者 | delegate 节点会话 `delegate-nebflow-ce996a9e`（stand-website 项目部署批节点，sourceAgent=nebflow） |
| 问题 | 「部署 E2E 门禁 RED，已按「不绿即停」暂停部署。红灯明细：prod 面 FAIL idp:google（Google OAuth 客户端仍缺 auth.mystand.space/callback/google 注册）；staging 面 FAIL 同款 + FAIL brand:favicon.ico…」 |
| 卡渲染面 | Nebula 根会话 `d7c3cb54-dd90-4f3f-bbf6-e7898c425cff`（即作者截图上传的来源会话） |
| 槽位死亡 | 2026-10-20 19:21 引擎重启（当日最后一次 boot）——等待方 deferred 与提问节点随旧进程消亡，**无任何收口路径执行** |
| 谎报时长 | 提问入账起 45.8h、槽位死亡起 39.4h（至截图 10-22 10:44；期间引擎又启动 4 次） |
| 处置 | hub 已空，无引擎侧清理对象；徽标点击即清（既有 jumpToAsk retire 路径），下次 WS 重连的全局快照对账同样收口。治本面见下 |

### 3. 为什么 2382b64e（10-20 微信问答治本批）没兜住

该批三机制全部以「**hub 槽位还在册**」为前提：

- **答案重入三臂**（AskAnswerLanding）：作答**到达时**的投递面——重启后槽位已不在，答案链路无对象（且本例根本无人作答）；
- **askerAlive 只读标注**：快照帧的读时投影——重启后快照恒为空帧，无从标注；
- **AskAnswerReceipt 反向回执通道**：随槽位关闭（cancel / askUserClosed / 顶旧）撤册——重启杀死槽位**不经过任何关闭路径**，回执通道自然收不到「问题作废」。

且该批明确裁定「只读不杀、零超时纪律不破」——它治的是**答案侧**与**活槽位可见性**；「重启把记账面整个带走、启动时无清点动作、pending 状态无盘上形态」这一整面是它的前提缺口，即本次的病。

## 二、修复面

### 修复① 重启恢复对账（引擎，候重启窗）

- **`PendingAskLedger`**（新，`agent/PendingAskLedger.scala`）：`<dataRoot>/pending-asks.json`，原子 tmp+rename（AskUserSecretSink 先例）。hub 槽位创建随行记一行、四条收口路径（answered / CleanupForSession / CloseRequest / CancelRequest）随行销一行——稳态下台账恒等于 hub 内存面。
- **boot 清点**（GatewayMain `pendingAskBootSweep`，链位在 startupMount / projectBootWake **之前**——boot 触发链可能唤醒会话产生新提问，旧账清点必须先行）：重启后每条残留的提问其等待方都已随旧进程消亡 ⇒ **全部过期关闭**：①广播 `askUserClosed{requestId, sessionId=root, reason:"expired-orphan"}`；②root 会话历史追加过期 System 回执行——历史重载的 pending 判据（`findLastRealMessage`）结构性失效，僵尸卡不再以可作答形态复活（作答必撞 unknown requestId 的假活卡就此绝后）；③审计 `event=ask-expired`（含年龄）+ 汇总 INFO，清账。失败只 WARN 绝不阻塞启动；零残留=零动作（一行汇总）。
- **与既有裁定不冲突**：只对重启孤儿生效（boot 单次）；运行中槽位仍守「askerAlive 只读不杀、零超时纪律不破」。

### 修复② 徽标可点 + 不许「有数无卡」（前端，刷新即生效）

- **徽标可点核证**：`initPendingAsks` 点击 → `jumpToAsk`（#250 ① 既有）：切换到承载会话 → 按 `data-request-id` 定位滚动+高亮；二次 miss 即**摘除镜像条目 + 「卡片已不在」toast**——「存在则跳转、不存在则清零」已落地，本批补齐其前提。
- **24h 寿限自愈**（`askPending.js sweepAged`，renderBar 单点）：镜像条目超 24h 即在任一渲染/对账点被摘除——兜住「久未重连的标签页」这最后一个引擎够不着的形态（本次实测形态）：任何事件/重连后一个渲染周期内，僵尸计数必收口。
- **过期终态文案**（`main.js` + locales）：`askUserClosed{reason:"expired-orphan"}` → 专用锁定文案「问题已过期（引擎重启，提问方已结束）」（zh/en 双语）。

## 三、spec 钉（三场景）

1. **重启孤儿清点**：引擎 boot（listen 前、startupMount/projectBootWake 前）读 pending-asks 台账；台账内每条 = 重启孤儿（等待方已随旧进程消亡，无例外）；零残留 = 零动作仅汇总行。
2. **过期关闭**：每条孤儿三件套——`askUserClosed{reason:"expired-orphan"}` 广播 + root 会话历史过期回执行（历史重载判据失效，卡不可答化）+ `event=ask-expired` 审计（requestId/root/sourceSession/sourceAgent/ageHours）；处理后清账，boot 幂等（二次重启零命中）。
3. **徽标一致性**：hub 快照恒为唯一权威；前端镜像三条收口路——重连全局对账（#250 ③，既有）、24h 寿限自愈（本批）、点击定位/清零（#250 ①，既有）；任何路径下「有数无卡」存活不得超过一个渲染周期（重连可达）或 24h（长期半开）。

## 四、验证

- `sbt Test / compile` 干净（-Xfatal-warnings）；新增 `PendingAskLedgerSpec`（6 test）：台账文件往返 / 幂等销账 / bootReconcile 全量过期关闭（关卡帧形状+回执落点+清账+汇总）/ 空账零动作 / hub 四路径随行记账-销账 / ledger=None 缺省构造零改动。
- 前端 `node --check` 语法过（main.js / askPending.js / 两 locales）；交互面（点击定位/清零）为 #250 ① 既有路径零改动，刷新即生效。

## 五、落窗说明

- 前端四文件（main.js / askPending.js / locales×2）**刷新即生效**：既有标签页下次重连快照或任一事件即收口；病标签页（半开 WS）在下一次成功重连或任一渲染点自愈。
- 引擎三文件（PendingAskLedger.scala / InteractionHub.scala / GatewayMain.scala）**候重启窗**：重启一次后记账与 boot 对账上岗；当前在跑进程的 hub 已空，无待清理对象。
