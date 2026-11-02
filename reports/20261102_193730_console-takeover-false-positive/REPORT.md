# 控制台接管提示：卡片化 + 误报根修（2026-11-02 批）

**报障原文**：「手机端这个应该是一个卡片 现在显示很不好看 另外我怎么老被挤 我们的这个逻辑是不是有问题 没人挤我 也显示被挤」

**一句话结论**：**判定条件用错了身份**——不是「清理不及时」，也不是「阈值」，而是把**易变的会话 id**（`sessionId`）当成了设备身份。同一台手机每次会话过期/静默重座都会换到一个新 `sessionId`，服务端于是把它当成「另外一个实例」，向自己推接管帧。清理逻辑是第二个次要因素（下面 §1 假设 2），但**主因是身份口径宽错了对象**。

**归属判定**：这套接管提示的**全部实现**都在 stand 主仓（`src/main/scala/nebflow/gateway/` + `src/main/resources/web/`）。
link-server 仓（`~/.stand/projects/link-server`）全文检索 `已在其他位置打开` / `让出控制权` / `takeover` **零命中**（`git grep` 退出码 0、输出空）。link-server 只在铸票/门户会话层参与，**没有**这套 UI 与判定，故本批**未触碰 link-server**。

---

## 1. 误判根因判定（五条假设逐条）

> 判定依据三类证据：① 代码逐行 ② 生产日志实证链 ③ 验红/验绿对照（同一场景两种口径各跑一次真注册表）。

### 假设 1：自我抢占（重连/后台恢复把「自己的旧连接」当「另一个实例」）—— **✓ 成立，主因**

代码：`src/main/scala/nebflow/gateway/ConsoleTakeover.scala`（改前 HEAD）`onConnect` 的 fresh-claim 分支

```
case Some(prevOwner) =>                     // 改前 :134
  ...
  if past.exists(_._1 == sessionId) then ... // 旧主回连 ⇒ 落被踢面
  else                                      // ← 只要 sessionId 不同就判「新实例接管」
    conns <- connsRef.get.map(_.getOrElse(prevOwner, Set.empty))
    _ <- claim(sessionId)                    //   改前 :149 互斥权翻位
    _ <- conns.traverse_(cid => sendTo(cid, takeoverFrame(now)))  // 改前 :150 推帧
```

实例身份 = `SessionRecord.sessionId`（改前 `WebSocketRoutes.scala:1089`：`case Credential.ViaSession(record) => Some(record.sessionId)`）。而会话 id **会变**：
- `GatewaySessionStore.IdleTimeoutSeconds = 604800`（7 天，`GatewaySessionStore.scala:257`）——会话过期后 `verifyAndTouch` 在同一 `modify` 里删行（`:177-190`）；
- 过期或被保险丝截断 ⇒ 前端 `ws.js` 的 `remintHostSessionAndReload()`（`ws.js:656` 起）静默重座 ⇒ `POST /api/portal/console-ticket` ⇒ 回到 `/` 兑换面 ⇒ `ConsoleTicketRoutes.scala:78` `sessions.create(...)` **铸一个全新 sessionId** ⇒ 同一台设备以**新会话**连回 ⇒ 改前逻辑判「新实例接管」⇒ 向自己推 `takeover` 帧。

日志实证（这行是本条假设的铁证，19 连击相邻）：
```
$ grep "console-takeover" ~/.stand/logs/stand.2026-11-02.0.log | sed -n '1,19p'
12:29:31 session vsIM7YB8 took over from oiflH6K0 — takeover pushed to 0 conn(s)
12:31:13 session bDjTb7lp took over from vsIM7YB8 — takeover pushed to 0 conn(s)
12:32:21 session qub_Gh1- took over from bDjTb7lp — takeover pushed to 0 conn(s)
...（每条的 prevOwner 正是上一条的新主，全部 accountSub=local，同一台设备）
```
且每条都与 `console ticket redeemed … sid=<新>` 相邻（如 `35995` 的 takeover 对照 `35919` 的兑换）。持续约 25 分钟、十余次——正对作者说的「老被挤」。

**验红/验绿**（一次性脚本，跑完已删，日志留存）：同一场景（sid-A 入账 → sid-B 同浏览器连回）
- 改前口径：`RED: same browser + NEW session id ⇒ false takeover` → owner 翻到 sid-B、推 **1** 帧（弹卡）✔ 复现
- 改后口径：`GREEN: same browser + NEW session id ⇒ no takeover` → owner 不动、**0** 帧 ✔ 消除

### 假设 2：判定依据是「表里有没有别的行」而非「有没有活跃的别的持有者」，旧行残留 —— **✓ 部分成立（次要因素，不是主因）**

改前其实**没有**用「会话表有没有别的行」来判定（判据是全局唯一 `ownerRef` + `pastOwners` 向量，见代码）。但**残留效应真实存在**、且形态是「陈旧 owner 不清」：

- `ownerRef` 只在 fresh claim / retake 时改写，**从不因超时清空**（改前全文无清扫路径）；
- `connsRef` 在 `onDisconnect` 只摘连接、**不摘会话键**（改前 `:171-177`；注释明写「掉线 ≠ 让位」）；
- 于是「owner 的会话早已消失、页面早已关掉」时，owner 仍挂在表上——下一个进入者会被判「接管僵尸持有者」，并被推到「被顶出」面。

它不是这次报障的主因（主因见假设 1），但确实是同族缺陷，本批一并修掉。

### 假设 3：单实例也触发（边界写反，如 `>=` 或把自己算进候选集）—— **✗ 不成立（但等价症状由假设 1 造成）**

改前单实例路径是干净的两分支：`owner==None ⇒ claim`、`owner==自己 ⇒ IO.unit`（改前 `:126-133`），**没有**单实例误触发的代码路径，也没有 `>`/`>=` 型边界错误。作者观察到的「一个人在线也提示」全部由假设 1 解释（**一个人的两个会话** = 系统眼里的两个实例）。本批用 A11 断言钉死：单实例零帧。

### 假设 4：心跳时钟问题（服务端判活阈值 vs 客户端心跳间隔）—— **✗ 不成立**

接管判定**完全不读心跳/时间戳**：`ownerRef` 无 TTL、无计时器（改前文件头注释「无计时器、无竞态窗」与代码一致），`takeover` 帧的唯一发射点是 fresh claim 与显式 retake。心跳只走 `ws.js` 的 pong 旁路与 `WsZombieGuard`（#960）判死回收，而回收路径（`onFinalize ⇒ onDisconnect`）**零出帧**。所以心跳被移动端节流拉长**不会**产生接管提示（它只会导致重连——而重连正是假设 1 的触发器，真正的病根仍在身份，不在时钟）。

### 假设 5：刷新/多标签被算作第二实例 —— **✗ 不成立（同会话时）**

改前按 `sessionId` 判身份，而同浏览器刷新/多标签**共享 cookie jar ⇒ 同一 sessionId** ⇒ 走 `Some(c) if c == sessionId ⇒ IO.unit`（改前 `:131`）**静默续位**，语义正确（A2/A10 一直守着这条）。所以「刷新即被挤」在改前不发生——除非刷新**同时**发生了会话轮换（假设 1 的链），那时它才被误判。**改后仍然如此，且口径更硬**（clientId 与 cookie 生命周期解耦，刷新/多标签/后台恢复/静默重座恒同身份）。

---

## 2. 「同设备重连」与「真抢占」的判据分界

| | 改前（HEAD） | 改后 |
|---|---|---|
| 实例身份 | `sessionId`（会话凭据，会过期/轮换） | `clientId`（`deviceIdentity.js` 的 localStorage 稳定 id，随 `/ws?clientId=` 与 retake 请求体上行）；无 clientId ⇒ 会话退路（老缓存页） |
| 分界判据 | `新 sessionId ≠ owner` ⇒ 抢占 | 只有**双方身份都可证且不同**（两个 clientId 都存在且不等）⇒ 抢占；任一方不可证 ⇒ **不提示不踢** |
| 刷新 | 同会话 ⇒ 静默（对） | 同 clientId ⇒ 静默（对） |
| 静默重座/换会话 | **误判抢占**（错误） | 同 clientId ⇒ 静默（对） |
| 后台恢复 | 会话若已轮换 ⇒ 误判 | 同 clientId ⇒ 静默 |
| 换浏览器/换设备 | 抢占 ✔ | 抢占 ✔（两个不同 clientId） |
| 老缓存页（不带 id） | 按会话判 | 不提示（宁可假阴性） |
| 陈旧账 | 永不清理（owner 可挂僵尸） | 入账时机会式清扫：零活连接 + 超 `StaleTtlMs`(10min) 无入账 ⇒ 摘账，若为 owner ⇒ owner 一并清空 |
| 内容帧闸 | 按会话 | 跟身份走：可证不同才断喂；不可证**照常放行**（避免「无提示但全白」） |

安全边界（保守性声明）：`clientId` 是 **UX 级身份键**，不是鉴权凭据——鉴权仍由 HttpOnly 会话 cookie 决定（`GatewayAuth`）。伪造 clientId 的最坏后果是「少弹一次提示」，拿不到任何数据面。这条边界是本设计的前提，也是「宁可少提示」能成立的原因。

### 改动落点
| 文件 | 改动 |
|---|---|
| `src/main/scala/nebflow/gateway/ConsoleTakeover.scala` | 实例身份改 holder（`c:<clientId>` / 会话退路）；`verdict()` 判据单源；实例账 `Instance(conns, clientId, lastSeen)`；`sweepStale` 机会式清扫（含 owner 清空）；`gateFrame` 按 holder + 可证性裁决 |
| `src/main/scala/nebflow/gateway/WebSocketRoutes.scala` | 读 `/ws?clientId=`（长度 ≤128、空过滤），随 `onConnect`/`gateFrame` 使用 |
| `src/main/scala/nebflow/gateway/ConsoleTakeoverRoutes.scala` | retake 请求体读 `clientId`（坏体/缺失 ⇒ 会话退路，绝不 500） |
| `src/main/resources/web/js/ws.js` | 握手带 `?clientId=`（storage 全禁 ⇒ 省略，落退路） |
| `src/main/resources/web/js/takeoverModal.js` | retake POST 带 `{clientId}`（与握手同值） |

---

## 3. UI 改动前后形态

**改前成因**（不是「没设计」，是**被另一条批的通用规则吃掉**）：`css/mobileLayer.css` 的「弹窗退场批（作者令 2026-10-31②『手机端不再有任何形式的弹窗界面』）」把 `#takeover-box` 与其余桌面模态**同批铺平**：`width/height 100% + radius 0 + 左右 border 退役 + padding-top: 64px`，且 `#modal-overlay` 吃到不透明页面底。接管卡因此变成整屏一块白，标题/正文/两钮全挤在顶部 1/3，下方 2/3 空白——与作者实拍逐像素对齐。

改前实测（HEAD CSS，375×812）：`before box {"x":0,"y":0,"width":375,"height":812}` ——**正好铺满整个视口**，机械证明了「不是卡片」。

改后：把 `#takeover-box` 从 A 族铺平规则里**摘出**（`:not(.takeover-live)`），单列「接管卡手机形态」块：

| 项 | 改前 | 改后 |
|---|---|---|
| 形态 | 全屏白面（375×812） | 居中浮层卡 343×295，圆角 18、1px 边、实色卡面 |
| 留白 | 内容挤在顶部，下方 2/3 空 | 上下各 258.4px（垂直居中，Δ0） |
| 左右边距 | 0（贴边） | 左右各 16px |
| 两钮 | 不等宽胶囊 | 等宽 140.5×48 一行，gap 12 |
| 热区 | 34（桌面档继承） | 48（≥44 规范） |
| 正文字号 | 14 | 17 |
| 标题字号 | 21 | 20 |
| 超窄屏 <360px | 挤压 | 两钮改纵向全宽 |

桌面形态**不变**（384 宽家族卡 / radius 18 / 居中）——正如作者要求：**一个组件，桌面/手机只差卡宽与内边距**。

样张（`evidence/`）：
- `evidence/card-mobile-375-BEFORE.png`（改前，HEAD CSS 铺满屏）
- `evidence/card-mobile-375-light.png` / `card-mobile-375-dark.png`（改后手机亮/暗）
- `evidence/card-desktop-light.png`（改后桌面，形态未变）

> 注：`BEFORE` 样张由一次性取证脚本以 **HEAD 版 mobileLayer.css** 渲染生成（脚本跑完已删，不入验收面）；改后样张由 `tests/console-takeover-falsepositive.spec.mjs` 每次运行覆盖生成。

---

## 4. 五条验证的实际输出

### ① 单实例不提示 / ② 后台恢复不提示 / ③ 刷新不提示 / ④ 真抢占要提示

**服务端判定（真注册表，`sbt testOnly nebflow.gateway.ConsoleTakeoverSpec`）**：
```
Passed: Total 26, Failed 0, Errors 0, Passed 26
  + A11 single instance never sees a takeover frame (no self-conflict)
  + A12 silent reseat (same clientId, NEW session id) resumes silently — the false-positive root
  + A13 background resume / refresh: same clientId reconnect with stale conn alive stays silent
  + A14 genuine takeover: two distinct clientIds ⇒ takeover frame to the OLD client only
  + A15 undecidable identity (either side without clientId) never prompts and never kicks
  + A16 stale instance is swept on next connect: owner cleared, zero takeover frame
  + A17 content gate follows identity: proven-different gated, unprovable passes
  + A1..A10 / B1..B5 （既有 19 条回归全绿）
```
（A1/A4/A5/A9/A10/B1/B5 等真抢占用例本批补上了显式 `clientId`——这正是新口径的语义：真抢占必须双方身份可证且不同。）

**前端（`node tests/console-takeover-falsepositive.spec.mjs`）**：
```
PASS  P1 握手带上 clientId  — ["ws://127.0.0.1:54862/ws?clientId=client-stable-aaa"]
PASS  P1 单实例不提示（开场 3s 无接管卡）
PASS  P2 后台恢复（同 clientId 重连）不提示  — wsUrls 1 → 2
PASS  P2 重连确实发生了（新握手，且 clientId 不变）
PASS  P3 刷新（新连接 + 同 clientId）不提示
PASS  P3 刷新后 clientId 不变（localStorage 稳定 id）
PASS  P4 真抢占提示在场（卡面由 takeover 帧弹出）
PASS  P4 桌面包裹为卡片（radius 18 / 1px 边 / 居中）  — {"x":528,"y":312.3,"w":384,"h":275.4,"radius":"18px","border":"1px"}
ALL PASS
```

### ⑤ UI：375px 下卡片不贴边、两钮不挤压、热区 ≥44px
```
PASS  P4-M 手机层激活（body.mobile-layer）
PASS  P4-M 卡片不贴边（左右各 ≥16px）  — left=16.0 right=16.0
PASS  P4-M 卡片有卡面（圆角 18 / 1px 边 / 非透明底）  — {"r":"18px","b":"1px","bg":"rgb(255, 255, 255)"}
PASS  P4-M 不铺满整屏（高度 < 视口 80%）  — cardH=295.3 vh=812
PASS  P4-M 卡片垂直居中（上下留白均衡，|Δ| ≤ 2px）  — top=258.4 bottom=258.4
PASS  P4-M 两钮等宽一行不挤压（宽度差 ≤ 1px，同一行）  — 140.5 / 140.5，同一 y
PASS  P4-M 热区 ≥44px（两钮高 ≥44）  — [48,48]
PASS  P4-M 正文字号 ≥17px、标题 ≥20px  — 17px/20px
PASS  P6 陈旧账：有活连接永不清 / 窗内保留 / 零连接+超窗摘账 / 陈旧窗 10min ≪ 会话 7d
ALL PASS
```

### 回归（本批未改其断言，证明零漂移）
```
node tests/console-takeover.spec.mjs   → ALL PASS（F1/F1-dark/F2/F2b/F3/F4/F5 全绿）
node tests/cards-family.spec.mjs       → ALL PASS（C1–C6，chromium+webkit 双引擎）
```

### 验红实证（证明这批改动真的挡住了 bug）
同一场景两种口径各跑一次真注册表：
```
RED   （HEAD 口径）：same browser + NEW session id ⇒ false takeover (owner flips, frame pushed) ✓ 复现
GREEN （本批口径）：same browser + NEW session id ⇒ no takeover (owner kept, zero frames)     ✓ 消除
```

---

## 5. 一句话结论

**是判定条件太宽（用会话 id 当设备身份），不是清理不及时**——清理缺失（陈旧 owner 不清）是同一处的次要缺陷，本批一并修掉，但即使清理不到位，只要身份口径对（clientId），同一台设备也不会再被自己挤。

---

## 附：本批不做 / 待验证

- **待验证**：手机上「同一设备确实开了两个浏览器（Safari + 微信内置）」应弹提示——clientId 各自独立，理论成立，但**未实机验证**（本批只到 Playwright + 真注册表层）。
- **待验证**：`clientId` 在用户清 localStorage / 换隐私模式后会变 ⇒ 视作新设备（会弹一次提示）。这是有意的：那种情况确实无法证明「是同一个人」。
- **不做**：不动 link-server 仓（无相关实现）；不动其他在飞 agent；未 push。
- **部署提示**：服务端改动需重启引擎生效（前端资源同样随重启/刷新下发）。

## 附：文件清单
- 改：`src/main/scala/nebflow/gateway/ConsoleTakeover.scala`、`WebSocketRoutes.scala`、`ConsoleTakeoverRoutes.scala`
- 改：`src/main/resources/web/js/ws.js`、`src/main/resources/web/js/takeoverModal.js`、`src/main/resources/web/css/mobileLayer.css`
- 改：`src/test/scala/nebflow/gateway/ConsoleTakeoverSpec.scala`（+A11–A17；既有用例补显式身份）
- 新：`tests/console-takeover-falsepositive.spec.mjs`
- 证据：`evidence/card-mobile-375-BEFORE.png`、`card-mobile-375-light.png`、`card-mobile-375-dark.png`、`card-desktop-light.png`
