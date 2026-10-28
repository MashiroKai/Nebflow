# 设计稿 · 控制台单实例（同账号多开的接管独占）

- 日期：2026-10-27
- 作者口径（逐字要点）：Mac 和 Windows 两台电脑先后从官网进入控制台；第二台进入时，第一台应弹提示「您在其他地方登录控制台」，提示退出等。目标 = 同一时刻只有一个可信实例在查看/操作控制台（用户操作原子性）；标签页开关有了唯一增源（多实例并存正是标签页越堆越多/复活的根源之一）；并发 WS 少了，对背压也是解压。
- 落点：引擎网关（见所有 console WS）加接管注册表 + WS 控制事件；前端接管模态（样式随既有模态族）。

---

## 1. 身份与粒度

| 决策 | 依据 |
|---|---|
| 实例身份 = 门户会话（HttpOnly session cookie 的 `sessionId`，`GatewaySessionStore.SessionRecord`） | 握手已过 `GatewayAuth`（`WsAuth` rail），`Credential.ViaSession(record)` 免费携带；同浏览器刷新/多标签共享同一 cookie jar ⇒ 同一会话 |
| **接管注册表按会话粒度判定，不在连接粒度** | 同浏览器刷新不算「其他地方」：刷新=同会话新连接，注册表 owner 不变 ⇒ 结构性不自杀（无计时器、无竞态窗） |
| 不同电脑/不同浏览器 = 各自独立门户会话 ⇒ 新实例 | 作者场景的两台电脑各自登录，各自持一条 session |
| **仅 session-rail 连接参与注册表**；Bearer rail（CLI/脚本/headless 直连）不注册、不被通知、不参与竞选 | 作者场景是浏览器；非浏览器客户端没有「实例」语义，零触碰零漂移 |
| 注册表纯内存（随网关进程） | `GatewaySessionStore` 本就内存态、重启即清；重启后浏览器走既有 remint 链铸**新**会话 ⇒ 新实例语义自然成立，无需持久化 |

控制台是**每引擎一个**的互斥资源：注册表 = `owner: Option[sessionId]`（全局唯一）+ `conns: Map[sessionId, Set[connId]]`（该会话的全部活跃连接）+ `pastOwners`（曾持有过控制权的会话账，LRU 帽 64）。

## 2. 接管语义（claim 规则 · 互斥权写入点）

**写入点只有两个：新实例的认证成功（fresh claim）与用户的「重新接管」点击（retake）。断线/重连/僵尸回收永不写。**

新连接握手成功（session rail）时：

```
onConnect(S, conn):
  owner == None                → S 即 owner（首实例），无事件
  owner == S                   → 静默续位（刷新 / 同浏览器第二标签 / 掉线重连），无事件
  owner == O 且 S ∉ pastOwners → 「新实例接管」：owner ← S，S 入 pastOwners，
                                   向 O 的全部活跃连接推 takeover 帧（尽力送达）
  owner == O 且 S ∈ pastOwners → 「旧主回连不反抢」：owner 不变，向 S 本连接推
                                   takeover 帧（superseded 通知）——O 未被动过
```

- **反向踢 = 【重新接管】按钮（唯一显式路径）**：`takeoverRetake` 帧 → owner ← 本会话 → 向前 owner 的活跃连接推 takeover 帧 → 回 `takeoverResult(ok)`。互斥权最后写入者胜。
- **为什么旧主回连不自动反抢**：回连是机器自动行为（休眠唤醒/网络抖动后的 ws.js 自动重连），不携带用户意图；若回连即反抢，则「B 正在操作时 A 的后台标签悄悄醒来把 B 踢掉」——这正是作者要防的误踢（边界 3）。回连方落被踢面，一键重接管，可预期。
- **为什么 fresh claim 必胜（哪怕旧主连接死了推不到）**：作者主场景「第二台进入 ⇒ 第一台弹提示」。若第二台入口被旧主的僵尸挡住（先看到「已在别处打开」模态），产品语义就反了。旧主不在场时消息尽力而为：活连接推帧即达；无活连接则旧主下次重连时按 `pastOwners` 规则落被踢面——语义最终一致。

## 3. 状态机（会话视角：active / superseded / retaken）

```
                    fresh claim / 首实例
        ┌──────────────────────────────────────┐
        │                                      ▼
   ┌─────────┐  他会话 fresh claim / 对方 retake   ┌──────────────┐
   │ active  │ ◄────────────────────────────────  │  superseded  │
   │ (owner) │                                    │ (模态阻塞操作)│
   └─────────┘ ────────────────────────────────► └──────────────┘
        ▲        本人点【重新接管】(takeoverRetake)      │
        │                                              │ 点【退出】
        │            【重新进入】(reload → 重走 superseded 面，
        │             再一键 retake)                    ▼
        └────────── retake 成功 ◄───────────────  exited（本页断开、
                    (retaken 即回到 active)        不再自动重连）
```

- `superseded` 面不自杀、不断 WS（保留 retake 通道）；`#modal-overlay` 阻塞本页操作 = 控制权独占的 UI 面；数据可见性照旧（§7）。
- 模态**强制二选一**：Escape / 点击遮罩不关闭（与既有模态族的「可关闭」默认不同，接管的二选一是互斥权的裁决点，不可误关）。
- 服务端状态只有 owner 一位；active/superseded 是连接侧对「我是否 owner」的投影，无独立服务端状态机（少一份会漂移的状态）。

## 4. 时序图

```
 Mac(会话A)                    网关(注册表)                     Windows(会话B)
    │── WS 握手(session A) ──────►│                                │
    │◄────────────────────────────│ owner←A（首实例，无事件）          │
    │        （A 活跃使用中…）      │                                │
    │                             │◄──── WS 握手(session B) ────────│
    │◄── {type:"takeover",at} ────│ B∉pastOwners ⇒ owner←B，         │
    │  （A 弹「控制台已在其他位置打开」│ 向 A 的活跃连接推 takeover        │
    │   【退出】【重新接管】）        │                                │
    │                             │                                │
    │── {type:"takeoverRetake"} ─►│                                │
    │◄── {type:"takeoverResult",  │ owner←A（retaken），             │
    │      ok:true} ──────────────│ 向 B 活跃连接推 takeover ────────►│
    │ （A 回到 active）            │                     （B 弹同一模态）│
    │                             │                                │
    │── A 点【退出】(纯前端)：置 exited 旗标 → 主动 close，不再自动重连；  │
    │   重入门 = 退出面的【重新进入】(reload) → 同会话重连 → 落 superseded │
    │   面（A∈pastOwners）→ 一键 retake 即回 active。                  │
```

僵尸回收（#960 WsZombieGuard 判死）时序——**不触发任何 takeover**：

```
 A 的 WS 静默 90s ──► 服务端判死：deadSig 双侧收口 → onFinalize
                      → takeover.onDisconnect(connId)（仅摘连接账）
                      → owner 仍是 A（掉线 ≠ 让位，无帧无事件）
 A 唤醒重连（同会话） ──► owner==A ⇒ 静默续位，全程零提示（宽限=所有权跨断线持久，
                          不新增计时器；#960 的 90s 判死窗即连接级宽限）
```

## 5. 服务端落点（引擎网关）

- **新增 `src/main/scala/nebflow/gateway/ConsoleTakeover.scala`**：注册表单体。
  - 装配缝：`sendTo: (String, Json) => IO[Unit]`（生产 = `wsHub.sendTo`，spec 注入假发送）+ `logger` + `enabled`。
  - 帧（协议，3 型）：
    - 服务端→旧实例：`{"type":"takeover","at":<epochMs>}`（无 sessionId，前端入 GLOBAL）；
    - 客户端→服务端：`{"type":"takeoverRetake"}`；
    - 服务端→接管者：`{"type":"takeoverResult","ok":true|false}`。
  - `onConnect(sessionId, connId)` / `onDisconnect(connId)` / `handleRetake(connId, reply)`；审计行 `console-takeover: …`（claim/takeover/superseded/retake 各一条 INFO）。
  - `pastOwners`：插入序 Map，帽 64 淘汰最旧（会话 ≤30d 寿命 + 内存态，量级无害）。
- **`WebSocketRoutes` 接线**（`GET …/ws` 受理体，最小侵入）：
  1. `case Some(cred)` 捕获 `ViaSession(record)` ⇒ `takeoverSession = Some(record.sessionId)`（Bearer ⇒ None，直通）；
  2. for 内 `wsHub.register` 之后、`wsb.build` 之前：`takeoverSession.traverse_(sid => takeover.onConnect(sid, hubConnId))`（推帧经 outbound 队列缓冲，随 serverConfig 送达）；
  3. 收流管 `evalMap` 前置拦截：`isRetakeFrame(text)` ⇒ `takeover.handleRetake(hubConnId, perConnJsonSend)`（连接身份只在管上有，WsDispatchCtx 不动）；其余照旧 `handleMessage`；
  4. `onFinalize` 追加 `takeover.onDisconnect(hubConnId)`（与 `wsHub.unregister` 同拍——判死回收/关闭/异常四途同归，均零事件）。
  - 构造参数 `takeover: ConsoleTakeover = ConsoleTakeover.disabled`（`connGuard` 同款缺省缝，既有 spec 构造点零改动）；`GatewayMain` 注入生产实例。
- **总闸（灰度/回退）**：env `CONSOLE_SINGLE_INSTANCE`（经 `Branding.env` 双前缀：`STAND_/NEBFLOW_`）。
  - 未设/设 `on/1/true/yes` = 启用（**默认启用**——本批即产品行为）；
  - `off/0/false/no`（大小写不敏感）= 总闸关闭：`onConnect`/`onDisconnect`/`handleRetake` 全 no-op、收流不拦截 retake 帧（未知帧走既有 `case _` 回退）——**现行为零漂移**。

## 6. 前端落点（接管模态 + ws.js 路由）

- **新增 `web/js/takeoverModal.js`**（叶子模块：只 import ws.js/state.js/i18n.js，无回边）：
  - `onMessage('takeover')` ⇒ `state.consoleSuperseded = true` + 弹模态（幂等：重连补推不叠弹）；
  - `onMessage('takeoverResult')` ⇒ `ok` ⇒ 收模态、`consoleSuperseded=false`；`!ok` ⇒ toast；
  - 【重新接管】⇒ `sendWs({type:'takeoverRetake'})`（单飞）；
  - 【退出】⇒ 先置 `state.takeoverExited=true` 再 `ws.close()`：`onclose` 见旗标**跳过自动重连与 remint/粘贴票面**，模态翻退出面（「已退出控制台」+【重新进入】→ `location.reload()`）；
  - ws.js 两处小改：`GLOBAL_MSG_TYPES` += `takeover`/`takeoverResult`（无 sessionId 帧必须 GLOBAL，否则 `:485-494` 把它当会话内事件抢走 activeView——`remoteAgents` 同律先例）；`updateConnBanner`/`onclose` 读 `takeoverExited`（退出面在场时不再谎报「正在重连」）。
- **模态 DOM/CSS**：`index.html` 增 `#takeover-box`（与 `#delete-box`/`#conflict-box` 同层同结构）；`modal.css` 把 `#takeover-box` 并入三处族选择器（玻璃卡/暗色阴影/折射线）+ 自有块（镜像 `#conflict-box` 尺寸字阶，零新 token）。
- **i18n**：`locales/zh-CN.js` + `en.js` 增 `takeover.*` 6 键（标题/正文/两按钮/退出面标题/退出面正文）。
- `main.js` 侧引入一行（ESM 图覆盖 dev 直服与 build-web 打包两形态）。

## 7. 边界与不做的事

1. **WS 掉线 ≠ 让位**：owner 按会话持久，断线不摘 owner（`onDisconnect` 只摘连接账）；同会话重连=静默续位。宽限**不新增计时器**——结构性「无限宽限」（掉线永不转移所有权），连接级宽限沿用 #960 的 90s 判死窗；网络抖动误踢在结构上不可表达（抖动期 owner 从未离开）。
2. **僵尸回收零提示**：判死回收只走 `onFinalize` ⇒ `onDisconnect`，注册表不出帧；takeover 帧的唯一发射点是 fresh-claim 翻位与显式 retake。
3. **多设备透明（#929）不受影响**：takeover 帧只发给「被移位的那个会话」的连接，不广播；`remoteAgents`/数据面帧照发全部连接（含 superseded 面）——本特性只独占**主动控制权**，不做只读观察者角色（作者未要求；若日后要「旁观者模式」，在 superseded 面加显式降级按钮即可，服务端零改动）。
4. **控制权独占的执行面 = 被踢模态**（overlay 阻塞本页操作）。服务端按连接拒写命令是后续加固项（触及全部 WS 命令分派，爆炸半径大），本批不做，报告开放项申报。
5. **Bearer / 隧道桥 / headless**：不注册不被通知；`takeoverRetake` 来自未注册连接 ⇒ `ok:false`。
6. 老前端收 `takeover` 帧：类型不在其处理集 ⇒ 静默 no-op（ws.js 未知类型零订阅即无操作），无兼容风险。

## 8. 与 #910 墓碑/恢复链的交互（标签页持久化联动 · 设计约定，本批只立约）

- 现状：`CanvasTabStore`（`dataRoot/canvas_tabs.json`）合并写 + 墓碑/重开账已收敛多窗口竞态；但**写入方仍是所有标签页**——多实例并存时「哪个页面的开放集作数」没有唯一裁决者（#910 报告开放项 1 的跨设备面同理）。
- 本批立约：**唯一活跃实例（owner 会话）= 标签页账本的唯一写入方**。含义：
  1. owner 页面的 `persistTabs()` PUT 是权威写入；superseded 页面的 PUT 应被跳过（前端按 `state.consoleSuperseded` 门闸即可落地）并在重接管后立即全量 PUT 一次（重同步）；
  2. 重启恢复（页面 reload 后的标签页还原）只认 owner 会话写出的账——非 owner 页面不做还原性 PUT（陈旧全量覆盖的墓碑防线已有，#910 的 merge 语义兜底乱序）；
  3. 服务端 PUT 闸（按 owner 拒写）不做——前端门闸 + 既有墓碑/合并语义已足，服务端闸留待「服务端按连接拒写命令」加固批同车。
- 本批实现面：takeoverModal 翻位时写 `state.consoleSuperseded`（唯一读点约定），canvas.js 消费留接线注释，**不在本批改 canvas.js**（爆炸半径控制；作者令只要求联动写进设计稿）。

## 9. 灰度与回退

- 回退 = `CONSOLE_SINGLE_INSTANCE=off` 重启引擎：注册表全 no-op、retake 帧走未知类型回退、前端 `takeover` 帧不再产生 ⇒ 零漂移回现行为。前端侧无独立开关（无帧即无行为，天然跟随服务端总闸）。
- 灰度观测点：审计行 `console-takeover: …`（claim/takeover/superseded/retake）；`/api/health` 面不加新读数（保持观测面最小，需要时后补）。

## 10. 验收矩阵（spec）

| # | 项 | 钉在哪 |
|---|---|---|
| A1 | 二进一踢：B fresh claim ⇒ owner 翻位 + A 活跃连接收 takeover 帧 | `ConsoleTakeoverSpec` |
| A2 | 刷新不自杀：同会话重连静默续位，零帧 | `ConsoleTakeoverSpec` |
| A3 | 断线宽限不误踢：owner 掉线不摘位；同会话重连零帧；僵尸回收（onDisconnect）零帧 | `ConsoleTakeoverSpec` |
| A4 | 重新接管反向踢：retake ⇒ owner 翻回 + 旧 owner 连接收帧 + result ok | `ConsoleTakeoverSpec` |
| A5 | 旧主回连不反抢：pastOwner 重连落 superseded（本连接收帧，owner 不动） | `ConsoleTakeoverSpec` |
| A6 | 僵尸回收不触发提示：回收只摘连接账、无任何帧 | `ConsoleTakeoverSpec` |
| A7 | 总闸关闭=零漂移：disabled ⇒ 全 no-op 零帧、retake 帧不被拦截 | `ConsoleTakeoverSpec` |
| A8 | Bearer 直通：未注册连接 retake ⇒ ok:false；帧形/判别函数钉 | `ConsoleTakeoverSpec` |
| F1 | takeover 帧弹模态（文案/两按钮/GLOBAL 不抢视图） | `tests/console-takeover.spec.mjs` |
| F2 | 重新接管：发帧 + result ok 收模态 | 同上 |
| F3 | 退出：close + 不再重连 + 退出面/重入门 | 同上 |
| F4 | 模态强制二选一（Escape/遮罩不关） | 同上 |
