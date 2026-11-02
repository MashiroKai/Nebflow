# profile-settings 批 · 勘察报告（阶段①：五问实证 + 两项定案）

> 纯过程文件（`reports/`）。只读勘察：本批零源码改动。所有读数为 375×812 / dark / `fm_api_mock=1` 真渲染探针 + `git`/`grep` 静态核对。
> 工作区：`.nebflow/worktrees/delegate-nebflow-16c2e54c`（分支 `delegate/delegate-nebflow-16c2e54c`，起点 `main@d5b9df5f3`）。

## 0. 环境准备与基线

| 项 | 结果 |
|---|---|
| `node_modules` | 无 → 已建符号链接 `ln -s "/Users/kaiyu/Claude code/Stand/node_modules" node_modules`（工作区内仅此一个链接，`git status` 无该路径，属 gitignore 面） |
| Playwright 冒烟 | `node node_modules/@playwright/test/cli.js test tests/mobile-topbar.spec.mjs` → **3 passed / 0 failed**（5.1s）：T1 根页顶栏退役、T2 头像搬家、T3 个人主页层级，全绿 |
| `scripts/build-web.mjs` | exit 0（212 sources，orphans 0，css `app-e652a3ef.css`，UNSEALED） |
| `scripts/check-js-types.mjs` | 「new file with N errors」报 messages.js 7 / perception.js 10 / popArtifacts.js 2 / todoFace.js 1 / todoStore.js 3，`total errors 25 > baseline 0`，但进程 **exit 0**（既有红，非本批引入） |
| `scripts/check-circular.mjs` | 既有环 `mobile/navController.js ↔ mobile/taskPanel.js`（exit 1，既有红） |
| `tests/unifyfix-eight-points.spec.mjs`（直跑） | **54 probes / 10 mismatched**：`R1d`、`R2c`、`R3b`、`R5a`、`R5c2` × 亮暗两轮。**本节点零源码改动 ⇒ 这 10 条全属既有红**。其中 `R1d`（设置页无「更新/频道」设备键组残留）的实因已实证：fleet 批 2026-11-01 给设置设备行加了「更新此设备」，计数 `count=1`，与 R1d 写死的 0 冲突（见 §5.4） |

⚠️ **i18n 基线（实测口径，供下游对齐）**：`js/locales/zh-CN.js` **2360** 键 / `en.js` **2353** 键；`zh\en` 独有 **8** 条（`chat.allowTool`、`chat.permSource`、`chat.permLevel.{warning,dangerous,critical}`、`chat.permExecCmd`、`chat.copied`、`chat.copy`），`en\zh` 独有 **1** 条（`mermaid.renderFailed`）⇒ **净不对称 = 7**（与任务书「zh 2297 / en 2290（差 7）」同量级，总数差异来自计数脚本版本/提交点不同；本报告以实测为准）。新增键必须两侧对称，勿新增第 9/第 2 条独有键。

---

## ① 联系人与群的数据结构：好友有无 desc/remark，存哪、怎么同步

### 1.1 好友 = 有 `remark`（纯本地），**无** `bio`/`desc`

| 事实 | 锚点 |
|---|---|
| `FriendSummary` 字段集 = userId / username / displayName / avatar / since / blocked / **remark** —— **无** bio、无 description | `src/main/scala/nebflow/shared/PeerModels.scala:130-138` |
| `remark` 语义在类型上就写死了：「**纯本地字段**，持久化在 `FriendRemarkStore`（`<dataRoot>/friend-remarks.json`，键 = userId），由 `FriendService.applyRemarks` 在出站口注入。**上游永不带该键**（协议零变更）：Decoder 不读它，Encoder 恒出该键」 | `PeerModels.scala:118-129`（含 remark 字段本身 `:137`） |
| **Decoder 有意不读 remark**（逐字注释：即便上游带上也必须忽略，否则可覆盖用户自己的备注） | `NeblinkModel.scala:1062-1081`（注释 `:1062-1067`，解码体 `:1068-1081`） |
| **Encoder 恒出该键**（`None ⇒ null`） | `NeblinkModel.scala:1230-1240`（`"remark"` 在 `:1238`） |
| 存储 = `object FriendRemarkStore`，`path = PathUtil.dataRoot / "friend-remarks.json"`，形态 `Map[String,String]`（键 = friend `userId`） | `NeblinkModel.scala:1366-1382`（path `:1370`，load `:1372-1376`，save `:1378-1381`） |
| 边界：**零上游协议变更、零跨设备同步**；同族先例 `peer-descriptions.json` / `fm_blocked` / `fm_seen_requests` | `NeblinkModel.scala:1358-1365` |
| 注入（唯一合并点）三层：`applyRemark`（空/缺席保持原样）→ `applyRemarks`（好友列表 + SendMessage 两条取数路径收口）→ `applyRemarksToConvs`（会话行内嵌 `friend` 档案） | `FriendService.scala:128-134` / `:138-142` / `:148-152` |
| 写面唯一入口 = `setRemark`（trim 空串 = 清除；改 Ref → `save`） | `FriendService.scala:160-168`；`remarks` 只读快照 `:171` |
| 网关路由 = `PUT /api/friends/{id}/remark`，body `{remark}` → `200 {ok:true}`；**无 502 面**（本地态不触上游）；缺参 400 | `SocialRoutes.scala:290-309`（`case` 在 `:299`，缺参 400 `:308`） |
| 启动装配 = `GatewayMain` 调 `FriendRemarkStore.load` 后传进工厂（工厂保持纯函数） | `GatewayMain.scala:1406-1420`；工厂说明 `NeblinkWiring.scala:44-50` |
| 前端读点 = `personLabel`（备注 > 显示名 > neblinkId） | `web/js/messages/friendCopy.js:19-22` |
| 前端写点 = `api.setFriendRemark` → `PUT /api/friends/{id}/remark` | `web/js/friendsApi.js:859-870` |
| 前端 wire 归一必须同步加 key（否则 remark 在此边界被丢弃） | `friendsApi.js:323-333` |

**结论①-a**：好友**有** `remark`（本地真源、已落地、有存储/网关/前端全链），**没有** `bio`/`desc`（后端类型、Decoder、Encoder、前端归一四层全无该键）。

### 1.2 群 = **既无 `description` 也无 `remark`**

| 事实 | 锚点 |
|---|---|
| `GroupSummary` 字段 = groupId / title / role / memberCount / unreadCount / lastMessageId / createdAt / selfUserId —— **无 description、无 remark** | `PeerModels.scala:186-195`（类型头注 `:169-185`：「只落解析必需 + 契约已冻结的行内键」） |
| 前端群行归一出口同样无：`normalizeGroupRow` 出 `{conversationId,kind,title,lastMessage,unreadCount,memberCount,myRole,memberAvatars}`（+可选 latestEvent/selfUserId） | `web/js/friendsApi.js:1031-1052` |
| 群域 11 条路由（create/list/invites/messages/members/title/role/leave/kick/disband）**没有任何 description 面**；群面 = 纯代理（路径/方法/body/状态逐字，禁止 reshape） | `friendsApi.js:1239-1462`（`PUT /api/groups/{id}/title` `:1422-1425` 是唯一的写行内文本面）；网关侧纯代理口径 `SocialRoutes.scala:880-905`（契约真源跨仓 `neblink-server src/groups.rs:616-639`） |
| 群设置抽屉只有「群名 + 成员 + 邀请 + 角色 + 退/解散」，无简介编辑 | `web/js/friendGroups.js:537-575`（群名 `:537-575`，`saveTitle` `:566-573`） |
| 群名重名可判：`title` 无唯一性约束（≤64 字符），重名走候选列表 | `PeerModels.scala:180-182` |

**结论①-b**：`GroupSummary` **确认无 `description`**（也无 `remark`）——群描述两侧（契约行 + 本仓归一）皆缺。

### 1.3 别混淆：设备描述 store 只服务设备

| 事实 | 锚点 |
|---|---|
| `PeerDescriptionStore` = `<dataRoot>/peer-descriptions.json`，`Map[String,String]`（键 = **deviceId**） | `NeblinkModel.scala:1335-1351`（path `:1339`） |
| 写面 `updatePeerDescription(deviceId, description)` → 改 `peersRef` + `peerDescRef` + 落盘 | `NeblinkService.scala:242-253` |
| 网关 `PUT /api/neblink/peer-description`（body 带 `deviceId`） | `NeblinkRoutes.scala:914-925` |
| 前端写点 = `saveDeviceDescription`（本机走 `PUT /api/neblink/device-info`，远端走 `PUT /api/neblink/peer-description`） | `web/js/dropbox.js:373-390` |
| 本机设备描述字段 = `DeviceIdentity.userDescription`（`NeblinkModel.scala:77`）；**发现报文刻意不含它** ——「descriptions are purely local, never exchanged between devices」 | `NeblinkModel.scala:557-563`；`handleAnnounce` 构造 `PeerInfo` 时无 userDescription `NeblinkService.scala:378-395` |

⇒ 设备描述与「好友/群描述」**不同族**：设备描述是**对端设备的本地覆盖 + 本机身份字段**，与好友/群档案无共享存储。

---

## ② 三类会话窗头形态 + 身份头实现（含 #1365 对接点）

### 2.1 桌面·会话窗头（三类共用同一渲染器 `renderChatModal`）

| 件 | 锚点 / 读数（375 dark 实测） |
|---|---|
| 骨架 `div.fm-modal-header` | 构建 `web/js/messages.js:1162`；函数入口 `renderChatModal` `:1149` |
| CSS | `web/css/friends.css:669-675`：`display:flex; align-items:center; gap:10px; padding:12px 16px 12px 20px`。**实测 header y=64 h=64**（在手机页面壳头带之下，见 2.3） |
| 头像槽 `.fm-modal-avatar`（40px 档，三类全挂） | 建槽条件式 `messages.js:1179-1180`（`isGroupHead \|\| isDeviceHead \|\| !!conv.friend`）；CSS `friends.css:681`（`display:inline-flex`）。实测 rect 40×40 @ x=20 |
| 标题 `.fm-modal-title` | `messages.js:1193`；CSS `friends.css:683`：`flex:1; min-width:0; display:flex; align-items:baseline; gap:8px`。实测 y=86 h=21（好友/群）/ h=24（设备，因内联按钮） |
| 名 `.fm-modal-name` | `messages.js:1194`（文本 = `convTitleLabel(conv)` `:1029-1034`）；CSS `friends.css:714-723`：15px / 600 / ellipsis / nowrap |
| 副行 `.fm-modal-id` | CSS `friends.css:915-921`：11px / muted |
| 关闭 `.fm-modal-close` | `messages.js:1291-1296`；手机层 `display:none !important`（`mobileLayer.css:1904-1916`，双返回钮清查批）→ 实测 rect 0×0 / display:none |

**三类差异点（DOM 实测，mock 数据）**

| 维度 | 好友（direct） | 群（group） | 设备（device） |
|---|---|---|---|
| 头像内容 | `<span class="fm-avatar fm-avatar-40">A</span>`（字母牌） | `<div class="fm-avgrid fm-avgrid-40 fm-avgrid-n1">` 组合头像九宫格（唯一实现 `friendGroups.js:242`） | `<span class="fm-avatar fm-avatar-40 fm-avatar-device">` 平台 SVG |
| 名文本链 | `personLabel`：**备注 > 显示名 > neblinkId**（实测「我的备注」） | `groupTitleOf`：群名（实测「Recon 群」） | `deviceLabel`：**描述 > 设备名 > 占位**（实测「工位机」）；无描述 ⇒ `neblink.unknownDevice` 占位而非设备码 |
| 名上的可点面 | 无 | `.fm-modal-name.fm-modal-title-btn` + `role=button` + `tabindex=0` + `title=群设置`（点群名开群设置抽屉） | 无 |
| 副行 `.fm-modal-id` | neblinkId（实测 `alice@example.com`） | 「3 名成员」（`messages.js:1200`） | 平台文本（`deviceSubLabel` `messages.js:5558-5562`；设备码已按令摘除） |
| 窗头附加钮 | 无 | `.fm-gs-open`（users 图标，第二入口，共用同一 `mountDrawer`） | `.glass-control.fm-device-desc-btn`（pencil 图标，描述编辑开关，与名同一 flex 行） |
| 附加行 | 无 | 群设置抽屉（`header.insertAdjacentElement('afterend')`） | `.fm-device-desc[data-device-desc="1"]` 面板挂载点（收起态 `:empty` 无占位）+ presence 徽章 `.fm-device-presence.fm-modal-presence` |
| 断言面 | — | `.fm-gs-open`、`.fm-modal-title-btn` | `[data-device-desc="1"]`、`.fm-device-desc-btn` |

关键锚点：设备描述入口 `messages.js:1205-1220`（`deviceDescRow = buildDeviceDescRow(conv, title)` 在 `:1212`）；`buildDeviceDescRow` `:1505-1511`；开关 `:1513-1535`；面板 `openDeviceDescPanel` `:1537-1603`（写路径单点 `saveDeviceDescription`，失败**不收起**）；`updateModalTitle` `:1605-1611`。群入口 `messages.js:1224-1287`（`mountDrawer` `:1235-1253`，群名热区 `:1269-1283`）。

### 2.2 手机 chat 根：`.g2-chat-head` = position:fixed 单行三段式真顶栏

| 件 | 锚点 / 读数 |
|---|---|
| 建层 = 把 `#header .header-center` 三节点（`#agent-avatar-btn` / `#session-name` / `#memory-btn`）**整体迁入** `#chat` 首件的 `.g2-chat-head`（节点迁移非复制） | `web/js/mobile/chatChrome.js:88-118`（class 赋值 `:98`，`chat.insertBefore` `:100`） |
| 清空路径守望（5 处 `innerHTML=''` 后把层插回内容流首位） | `chatChrome.js:102-113` |
| 建/拆层广播 `nebflow-chat-head-mounted` / `-unmounted`（main.js 顶让位重取） | `chatChrome.js:114-118`、`:218-221` |
| 拆除 = 三节点按原序归还 `#header .header-center` + `paintSlot()` 重绘 | `chatChrome.js:196-231` |
| CSS（权威块，文件尾） | `web/css/mobileLayer.css:3707-3827`「会话顶栏单行三段式批（2026-11-02 07:23）」；顶栏本体 `:3730-3751`、头像 `:3759-3765`、名字 `:3775-3812`、渐隐带 mask `:3815-3826`、双钮同轴 `:3827` |
| 实测（375 dark） | `.g2-chat-head` rect `{x:0,y:0,w:375,h:56}`；`position:fixed; height:56px; display:flex; flex-direction:row; justify-content:center; padding:0 72px; z-index:26; background:rgba(24,28,38,0.68); backdrop-filter: blur(24px) saturate(1.15)` |
| 头像 | `#agent-avatar-btn` rect `{x:168,y:8,w:40,h:40}` ⇒ 水平中心 188 = 屏幕中线（375/2=187.5，偏差 0.5px） |
| 名字 | `#session-name`：`position:absolute; left:calc(50% + 28px); font-size:14px; font-weight:600`；空串 ⇒ `display:none`（`:3084` `:empty` 收口） |
| 层级契约 | 顶栏 z26 > 渐隐带 25，< 双钮层 30，< 页面栈 156，< dock 200（`mobileLayer.css:3722-3728`） |
| 消息行 | 零装饰：**已随 Muse 悬浮头像批整批退役**（每泡上方 agent 头像+名字行摘除） | `chatChrome.js:21-24`、`:122-128` |

### 2.3 手机「会话」详情页的头带叠层（本批必须处理的既有事实）

`conversation` 详情页**不是** dock 根页 ⇒ 走 `buildSection` 的详情页分支：`.mobile-page-header`（内含 `.mobile-back-btn.g2-fab` + `.mobile-page-title-mini` 胶囊）+ 页体流内首件 `.mobile-page-title` 大标题（`web/js/mobile/navController.js:307-343`，`mobile-page-nohead` 只给 dock 根页 `:344`）。

实测 rect（375 dark，好友会话）：

| 件 | rect | 计算值 |
|---|---|---|
| `.mobile-page-header` | y=0 h=0 | absolute / z20（空壳，不占位） |
| `.mobile-back-btn.g2-fab` | x=16 y=16 48×48 | 玻璃圆钮，z6 |
| `.mobile-page-title-mini`（「会话」） | x=156 y=24 64×32 | 玻璃胶囊，z5 |
| `.mobile-page-title`（大标题，流内） | y=12 h=52 | 28px/600 |
| `.fm-modal-header` | **y=64 h=64** | 紧跟大标题带之下 |

⇒ 手机会话页当前是**两条身份带上下叠**：上带 = 页面壳「会话」大标题（+ 返回钮），下带 = 会话窗头（头像 + 名字 + 副行 + 会话级按钮）。**这正是本批「悬浮身份头」要收拢的形态**：会话自身身份应升到顶栏，页名「会话」不应单独占一条带。

另外：`.g2-chat-head`（chat 根的三段式顶栏）在详情页**仍在 DOM 但被盖住**——它 z26，而 `#mobile-pages` z156（`mobileLayer.css:1912` 段注释与层级契约 `:3722-3728`），且 `#fm-chat-overlay` 在手机层 `pointer-events:none`（`mobileLayer.css:936-940`）。故详情页身份头**不复用** `.g2-chat-head`，需另建（或把 `.g2-chat-head` 的算法抽象成同源构造器）。

### 2.4 #1365 回退批：**相对 main 零提交** ⇒ 本批须自行实现

```
$ git rev-list --count main..delegate/delegate-nebflow-accdcd3b   → 0
$ git rev-list --count delegate/delegate-nebflow-accdcd3b..main   → 9
$ git merge-base --is-ancestor delegate/delegate-nebflow-accdcd3b main → yes
$ git rev-parse delegate/delegate-nebflow-accdcd3b → 769f5351e5b2c313fc27250d9f4f80263ae96a78
        （= 「Merge branch 'delegate/delegate-nebflow-f555a161'」，2026-11-02 09:14:58 +0800）
```

**结论**：该分支尖端是 main 的**祖先**，自有提交数 **0** ⇒ #1365 回退批**没有可复用的实现**（既无可摘的补丁，也无可对齐的既有代码）。本批必须**自行实现悬浮身份头**，并在本批文档中登记与 #1365 的**对接点**如下（下游照此写）：

- 对接点 A（形态位）：悬浮身份头的落位与几何尺寸，若 #1365 将来落地「会话顶栏回退」，需以本批的身份头为**唯一**落点收口，禁两套并存的「窗头 + 顶栏」双身份。
- 对接点 B（变量面）：本批**不得**复活 `--muse-head-top`（已整条退役，`chatChrome.js:136-143` + teardown 兜底 `chatChrome.js:214-216`）；若 #1365 引入新顶栏高度变量，须与本批的 `--` 前缀命名族对齐并在 linter 面登记。
- 对接点 C（层叠位）：身份头 z 值须落在既有层级契约窗口内（> 渐隐带 25、< 页面栈 156；见 `mobileLayer.css:3722-3728`），#1365 若改层级须同批改这段契约注释。

---

## ③ 设置页身份区现状：「跳官网」的确切实现

### 3.1 桌面：`#settings-avatar-entry` = `<button>`，点击 → `window.open` 新标签（**不是**外链 `<a>`）

| 件 | 锚点 / 实证 |
|---|---|
| DOM = `<button class="settings-avatar-entry" id="settings-avatar-entry" type="button">`，内含 logo `<picture>` + 隐藏的照片 `<img class="settings-avatar-photo">` | `web/js/sidebar.js:884-889` |
| 仅桌面渲染：`mobileLayer ? '' : …` | `sidebar.js:879`（判定）+ `:884`（内联条件） |
| 点击行为**不在** sidebar 里实现：`document` 级委托 `closest('#settings-avatar-entry')` → `activateAccountEntry(entry)` | `web/js/activityBar.js:544-554`（委托 `:546-553`）；设计理由（避免 sidebar↔activityBar 静态环）`:538-543` |
| 已登录 ⇒ 开两选一菜单：**个人主页** + **退出登录** | `activityBar.js:509-527`（`openAccountMenu`），菜单项 `:515-524` |
| 「个人主页」= `window.open(getProfileUrl(), '_blank', 'noopener')` | `activityBar.js:518` |
| 未登录 ⇒ `showLoginModal()`（PKCE） | `activityBar.js:492-496` |
| **实测点击捕获**（拦截 `window.open`） | `window.open` 收到 `https://mystand.space/profile?from=client`；菜单文案 `["个人主页","退出登录"]`；入口节点 `TAGNAME=BUTTON`、`avatar.querySelector('a') = false` |
| `getProfileUrl()` | `web/js/brand.js:83-104`：读 `brand.profileUrl`，**只接受 `https://` 开头**否则回落 `PROFILE_URL_FALLBACK`（`:77` = `https://mystand.space/profile`），幂等补 `from=client`（`:88-95`，契约常量 `:80-82`） |
| `profileUrl` 真值 | 仓根 `brand.conf`：`profileUrl = https://mystand.space/profile` |

**结论③-a**：设置页头像 = **按钮 + `window.open(..., '_blank', 'noopener')` 新标签**跳 `https://mystand.space/profile?from=client`。**全仓零 `<a href=...profile>` 外链形态**（`grep profileUrl` 只命中 brand.js / 两个消费腿 / 注释）。

### 3.2 手机：设置页头像位**已撤**，身份入工作台顶部 → `meFace` 四级页

| 件 | 锚点 |
|---|---|
| 设置页头像与设备槽在手机层整块不渲染 | `sidebar.js:879` / `:884` / `:894`（两处 `mobileLayer ? '' : …`） |
| 工作台顶部头像入口 `button.mf-me-entry[data-me-entry="1"]` | `web/js/mobile/navController.js:405-443`（建节点）；挂载 `:374-393`（`buildHub` 首件 `:381`）；状态重绘 `paintWorkbenchMe` 订阅 `:391-392` |
| 点击语义：配对中忽略；已登录 `pushPage('me')`；未登录 `showLoginModal()` | `navController.js:429-441` |
| `me` 页 = 资料卡（大头像 + 名 + 邮箱）+ 账号分组三行（用户名/头像/绑定）+ 提示 + 官网键 | `web/js/mobile/meFace.js:105-153`（`buildMeHome`） |
| 三个子页全为**只读读数 + 官网出口** | `buildMeUser` `:155-176`、`buildMeAvatar` `:179-201`、`buildMeBind` `:204-224`；页 id 白名单 `navController.js:138`（`ME_PAGE_IDS`）、`PAGES` 声明 `:126-129` |
| 官网跳转单点 `openPortal()` = `window.open(getProfileUrl(), '_blank', 'noopener')` | `meFace.js:42-45`；四个出口键 `portalButton` `:48-56` |
| 页面导航语义 = 复用既有详情页壳（push/pop），本模块只产页面体 | `meFace.js:1-27`（头注）；构建入口 `buildMeFace` `:227-245` |

**结论③-b**：手机端**没有**「设置页身份区」——身份区已上收到工作台顶部（`[data-me-entry]`），四个页面**零编辑控件**，全部字段来自 `/api/neblink/status` 只读读数，任何修改一律 `window.open` 跳官网个人主页（`from=client`）。

> 旁注（非本批面，但同源）：`web/js/takeoverModal.js:186` 用 `window.location.href = getProfileUrl()`（导航而非新标签）——被接管页的「退出」腿，语义不同，勿混。

---

## ④ 账号服务接口清单（逐条核对 + 有界只读探针）

### 4.1 本仓可见的账号面（网关路由全量）

| 路由 | 锚点 | 作用 | 前端消费 |
|---|---|---|---|
| `GET /api/users/search?q=` | `SocialRoutes.scala:842-854`；客户端 `NeblinkClient.scala:637-642` | username OR email **NOCASE 精确**查号（唯一搜索入口） | `friendsApi.searchUser`（`friendsApi.js` 内 `normalizeSearch` `:337-352`） |
| `GET /api/users/lookup?q=` | `SocialRoutes.scala:826-840`；客户端 `:625-629` | 已退役查号（客户端零调用，路由仍在） | 无（`friendsApi.js:1468-1472` 注明已摘） |
| `PUT /api/users/me/neblink-id` | `SocialRoutes.scala:857-868`；客户端 `NeblinkClient.scala:648-660` | 自定义 NL 号 | **零前端调用**（`friendsApi.js:1468-1472`、`neblink.js:127-131`：10:54 裁定「客户端不提供修改入口」） |
| `GET /api/users/me/neblink-id/available?q=` | `SocialRoutes.scala:870-881`；客户端 `:663-671` | 号可用性预检 | **零前端调用**（同上） |
| `GET /api/neblink/status` | `NeblinkRoutes.scala:199-313` | 登录态 + 设备/对端 + **只读账号 claims** | `neblink.js` 状态单点 |
| 　device 只读 claims | `NeblinkRoutes.scala:274-283`：`id`/`name`/`platform`/`capabilities`/`userDescription`/`avatarUrl`/`githubLogin`/`email`/`displayName` | `displayName` = id_token `name` claim、`email` = id_token `email` claim，**均只读解码、token 不出存储** | 见 `NeblinkRoutes.scala:243-253`（`acctClaims`），设计说明 `:243-249` |
| `PUT /api/neblink/device-info` | `NeblinkRoutes.scala:904-912` → `NeblinkService.updateDeviceInfo` `:217-239` | 只改 `capabilities` / `userDescription` / `avatarUrl` / `githubLogin`（**设备**身份，非账号） | `dropbox.saveDeviceDescription`（`dropbox.js:373-390`） |
| `PUT /api/neblink/peer-description` | `NeblinkRoutes.scala:914-925` | 对端设备描述本地覆盖 | 同上 |
| `POST /api/neblink/auth/start`（可选 `provider`） | `AuthRoutes.scala:76-107`（`directSignIn = s"social:$provider"` 在 `:102`）；前端 `neblink.js:588-612` | 登录期一跳直达社交连接器（`social:github` / `social:google`） | 登录卡 |
| `POST /api/neblink/logout`、`POST /api/neblink/auth/end-session` | `NeblinkRoutes.scala:448`、`:475` | 登出 / RP 登出链 | `neblink.js` |

### 4.2 逐条核对结论

| 问题 | 结论 | 证据 |
|---|---|---|
| **有没有改 username 的接口？** | **无（客户端禁写）**。`PUT /api/users/me/neblink-id` 只改「NL 号 = Username」这一键，且前端调用**已整体摘除**，官方口径 = 官网账号中心设置；`githubLogin`/`displayName` 只能经设备身份面写（且 `displayName` 根本不是可写字段——它是 id_token claim 的只读解码） | `SocialRoutes.scala:857-868`；`friendsApi.js:1468-1472`；`neblink.js:127-131`；`NeblinkRoutes.scala:274-283` + `:250-253` |
| **有没有改 nickname（displayName）的接口？** | **无**。全仓唯一的 `displayName` 写路由是 **agent** 的 `PUT /agents/:name/display-name`（写 `agents/<name>/agent.json`），**与用户账号无关** | `PresenceRoutes.scala:1388-1400`；用户侧 `displayName` 仅 `NeblinkRoutes.scala:280` 只读 |
| **有没有 GitHub/Google 绑定查询接口？** | **无**。`githubLogin` 只作设备身份字段存在：老 GitHub 报名时写入（`NeblinkEnrollment.scala:107`/`:122`/`:262`）、登出时清空（`AuthRoutes.scala:266`），**前端刻意不映射**（`neblink.js:320-322`：client is Logto-only now, the field had no consumer in web/）。无任何「当前绑定哪些身份」的查询端点 | 见左列锚点 |
| **有没有绑定 / 解绑接口？** | **无**。唯一沾边的是**登录期** `direct_sign_in=social:github\|google`（一键直达连接器），语义是「这次登录走哪个连接器」，**不是**「把某身份绑定到当前账号」，也无解绑面 | `AuthRoutes.scala:76-107`（尤其 `:102`）；`LogtoAuthCode.scala:135-151`（参数语义与守卫） |

### 4.3 有界只读探针（无凭据、不改状态）

```
$ for u in /api/me /api/account /api/users/me /api/identities /profile; do curl -s -o /dev/null -m 12 -w '%{http_code}' https://mystand.space$u; done
https://mystand.space/api/me           -> 404
https://mystand.space/api/account      -> 404
https://mystand.space/api/users/me     -> 404
https://mystand.space/api/identities   -> 404
https://mystand.space/profile          -> 307   (Location: https://mystand.space/settings ；跟随到底 final 200)
```

**结论**：官网**不对控制台开放任何 `/api/*` 账号面**（四条全 404）；`/profile` 只是 307 到 `/settings` 的站点页路由（无 API 语义）。

### 4.4 🔴 接口缺口清单（**卡点**，禁止前端造假）

| # | 缺口 | 影响 | 前端禁令 |
|---|---|---|---|
| G-1 | **无用户昵称/显示名写接口**（`displayName` 是 id_token 只读 claim） | 「改昵称」只能 defer 官网；控制台不可能内联编辑 | 禁伪造本地昵称覆盖（不得在前端造第二真相源） |
| G-2 | **无用户名（NL 号）写入口的可用前端**（路由在、调用已摘） | 若要恢复，需先解 2026-09-05 10:54 裁定（客户端不提供修改入口）——**属决策项，非实现项** | 禁「先做 UI 再议」：无裁定不接 UI |
| G-3 | **无 GitHub/Google 绑定查询接口** | 「绑定」子页只能显示邮箱 + 设备数 + 登录态（现状即如此，`meFace.js:204-224`），无法显示「已绑定 GitHub: xxx」 | **禁**把 `githubLogin`（遗留报名字段、前端已不映射）当绑定态渲染；**禁**静态写死「未绑定/已绑定」 |
| G-4 | **无绑定 / 解绑接口** | 任何绑定动作都无法落地 | 禁假按钮、禁 toast 假装成功 |
| G-5 | **官网 `/api/*` 全 404** | 无法从控制台侧直连官网账号 API 兜底 | 禁 CSRF/凭据跨站拼接；唯一合法出口保持 `window.open(getProfileUrl())` |

> 结论口径：**客户端不承担账号编辑**（2026-09-05 裁定 + auth-trim 批 + 官网 404 三重证据）。本批若涉及「身份区」改造，只允许：改**展示形态**（悬浮身份头）、改**跳转落点**（仍 `getProfileUrl()`）、增**本地描述**（见 §6 契约）。任何「控制台内改用户名/昵称/绑定」的需求必须先立卡点决策。

---

## ⑤ 联系人里设备更新的确切入口（≠ 设置页入口）

### 5.1 联系人入口（本批要移除的那一处）

| 件 | 锚点 |
|---|---|
| 触发 `triggerDeviceUpdate(d)` | `web/js/contacts.js:532-542`：`dn = d.deviceName`；`key = pendingDeviceUpdateKey(dn) \|\| newClientRequestId()`（重试复用同键）；`markDeviceUpdating(dn, key)`；`setTimeout(markDeviceUpdateTimeout, 300_000)`（`REMOTE_UPDATE_ACCEPT_TIMEOUT_MS` `:521`）；`sendWs({type:'remoteUpdate', device:dn, beta:false, clientRequestId:key})` |
| 行内两件 `deviceUpdateEntry(d)` | `contacts.js:544-557`：状态 `span.neblink-peer-status[data-device-update-state=dn]` + `button.neblink-peer-update-btn[data-device-update-btn=dn]`，文案 `t('neblink.update')`；`e.stopPropagation()` 让位行激活 |
| 进度回显 `syncDeviceUpdateUI()` | `contacts.js:559-589`：按 `[data-device-update-state]` 现读扫描；四态 → `neblink.updating` / `neblink.restarting` / `neblink.updateTimeout` / 后端原文；`updating|done ⇒ btn.disabled=true` |
| 挂载点 | `deviceRow(d)` `contacts.js:466-493`：`if (!d.isLocal) for (const node of deviceUpdateEntry(d)) row.appendChild(node)`（**本机行不挂**，`:489-491`） |
| 订阅 | `onDeviceUpdateChange(syncDeviceUpdateUI)`（`contacts.js:1255`） |
| 状态机唯一实现 | `web/js/neblink.js:227`（`deviceUpdateState`）+ 访问器 `onDeviceUpdateChange:234` / `getDeviceUpdateState:248` / `markDeviceUpdating:254` / `markDeviceUpdateTimeout:265`；结果帧消费仍只有 `initNeblink()` 一处（`:217-226` 注记） |
| 服务端腿 | `sendWs {type:'remoteUpdate'}` → `WsSystemHandlers.scala:51` 注册、`handleRemoteUpdate` `:510-…`（可选 `clientRequestId` 仅在既有帧上加键，`:517-530`） |

### 5.2 设置页设备面（保留的入口）

| 件 | 锚点 |
|---|---|
| 区块 HTML `settingsDevicesHTML()` | `web/js/messages.js:5602-5629`：入口行 `[data-settings-devices-entry]` + **fleet 条** `[data-fleet-entry]` + `<button class="fleet-btn" data-fleet-open="1">` 文案 `t('fleet.startAction')`（「全部更新」，`:5623`）+ 行列表 `[data-settings-device-section="1"]` |
| 每行「更新此设备」 | `settingsDeviceRowHTML` `messages.js:5631-5648`：`d.isLocal ? '' : '<button class="neblink-peer-update-btn" data-settings-device-update="<deviceName>">'+t('neblink.update')` （`:5637-5639`） |
| 点击委托 | `bindSettingsDevicesOnce` `messages.js:5654-5717`：`[data-fleet-open]` → `import('./fleetUpdate.js').then(m => m.openFleetUpdatePanel())`（`:5671-5675`）；`[data-settings-device-update]` → `m.updateSingleDevice(dn)`（`:5676-5684`）；行点击 → `openDeviceChat(d)`（`:5685-5695`） |
| 动作实现 | `fleetUpdate.js:339-347` `openFleetUpdatePanel()`（开阻塞卡 + `fleetUpdateSnapshot` + `GET /api/update/fleet`）；`:391-396` `updateSingleDevice(deviceName)` = `sendWs({type:'remoteUpdate', device, beta:false})` **后开同一张卡**（**不带** `clientRequestId`） |
| 挂载（仅桌面） | `sidebar.js:894` 槽位（手机层空串）、`:1126-1128` / `:1163-1165` 惰性 `mountSettingsDevices` |

### 5.3 「联系人入口 ≠ 设置页入口」的判断依据

| 判据 | 联系人入口 | 设置页入口 |
|---|---|---|
| 代码所有者 | `contacts.js`（hotupdate 批 3 · G7 的「新家」） | `messages.js` 设置面 + `fleetUpdate.js`（fleet 批 2026-11-01） |
| DOM 断言面 | `[data-device-update-btn]`（按 **deviceName** 定位） | `[data-settings-device-update]`（按 deviceName）+ `[data-fleet-open]` |
| 进度面 | 行内 `.neblink-peer-status` 文本四态（`neblink.updating/restarting/updateTimeout`） | 阻塞式**全屏状态卡**（`fleet.*` 整套文案 + 阶段条 + 收口四句） |
| 幂等键 | **带** `clientRequestId`（重试复用同键，契约 §D.2） | **不带**（fleet 腿走编排器自己的 job 语义） |
| 范围 | 单台（行内一键） | 全设备（`全部更新`）+ 单台（`更新此设备`，但走同一张卡） |
| 数据源 | `devicePeers()` 本机 + peers（本机不挂） | 同上（本机不挂：`d.isLocal ? ''`） |
| 手机层在场 | **在场**（联系人页设备段在手机层完整保留，`mobile-device-chat-windows.spec.mjs:137-138` 钉着） | **不在场**（`sidebar.js:894` 手机层不渲染槽位） |

⇒ 两处是**两条并列的独立入口**：联系人 = 行内轻量单台（带幂等键、行内回显），设置 = 卡片化批量/单台（带编排、审计、确认）。**删掉联系人入口不影响设置入口的任何一条**（无共享 DOM、无共享进度面；唯一共享只有 `deviceUpdateState` 状态机，而设置腿根本不用它——它走 `fleetUpdate` 自己的帧）。

### 5.4 全部引用点清单（移除联系人入口时必须一并处理的面）

**代码**

| 类别 | 位置 |
|---|---|
| 函数体（4 个） | `contacts.js:532` `triggerDeviceUpdate`、`:544` `deviceUpdateEntry`、`:559` `syncDeviceUpdateUI`、`:524` `newClientRequestId`、`:521` 常量 `REMOTE_UPDATE_ACCEPT_TIMEOUT_MS` |
| 调用点 | `contacts.js:491`（`deviceRow` 内挂载）、`:357`（`render()` 内 `syncDeviceUpdateUI()`）、`:1255`（`onDeviceUpdateChange` 订阅） |
| import | `contacts.js:8`：`getDeviceUpdateState, markDeviceUpdating, markDeviceUpdateTimeout, onDeviceUpdateChange, pendingDeviceUpdateKey`（全部仅服务本入口——移除后应清空这五个导入符号） |
| 上游状态机（**保留**，设置腿与结果帧仍需要） | `neblink.js:227/234/248/254/265`（`deviceUpdateState` 家族 + 结果帧处理；`:217-226` 明写「未删保留面」）；`WsSystemHandlers.scala:51/510` |

**i18n 键**（zh/en 各 4 条；移除后若 `fleet.*` 不复用则应同批删或标注保留理由）

| 键 | zh | en | 移除后是否仍有消费者 |
|---|---|---|---|
| `neblink.update` | `更新` | `Update` | ✅ 有（设置页每行 `messages.js:5639`） |
| `neblink.updating` | `更新中…` | `Updating...` | ❌ 仅 `contacts.js:576` |
| `neblink.restarting` | `重启中…` | `Restarting...` | ❌ 仅 `contacts.js:579` |
| `neblink.updateTimeout` | `等待超时（300 秒）——请检查设备状态` | `Timed out (300s) — check the device state` | ❌ 仅 `contacts.js:582` |

（zh 位置：`locales/zh-CN.js:2095 / 2099 / 2100 / 2103`；en 位置：`locales/en.js:2167 / 2171 / 2172 / 2176`。**注意**：`fleet.updateThisDevice`（zh `:2121` / en `:2194`）是**死键**——全仓零消费者，设置行实际用的是 `neblink.update`。若要「无死键」收口，这条也在清理候选内，但**不属本批新增**。）

**CSS**

| 类 | 位置 | 移除联系人入口后 |
|---|---|---|
| `.neblink-peer-update-btn` | `web/css/neblink.css:192-207`（含 `:hover` `:203-207`）；`web/css/sapphire.css:334`（Pattern B 基底）/`:354`（hover）/`:370`（active） | **保留**（设置页每行仍在用） |
| `.neblink-update-inline` | `neblink.css:209-213` | 已死（零 JS 消费者） |
| `.neblink-ch-btn` / `.neblink-ch-cancel` | `neblink.css:215-245`；`sapphire.css:335/355/371` | 已死 |
| `.neblink-update-status` | `neblink.css:247-260` | 已死（`contacts.js:508` 注释明确「刻意不用」该档） |

**QA data-* 断言面 / 涉及 spec**

| spec | 断言 | 移除后影响 |
|---|---|---|
| `tests/mobile-device-chat-windows.spec.mjs:137-138` | `rows.nth(1/2).locator('[data-device-update-btn]')` → `toHaveCount(1)` | **必改**：本批移除后应为 `toHaveCount(0)`（本机行 `:140` 已是 0） |
| `tests/unifyfix-eight-points.spec.mjs:202` / `:208` | `R1d`：`content.querySelectorAll('.neblink-peer-update-btn, .neblink-ch-btn, .neblink-ch-cancel').length === 0` | **既有红**（实测 `count=1`，因 fleet 批给设置行加了「更新此设备」）；本批若只删联系人侧，R1d **仍红**——需同批裁定「R1d 的口径是否随 fleet 批失效」 |
| `tests/unifyfix-eight-points.spec.mjs:321/351/358` | `R5a` / `R5c2`（设备窗副行 / 描述往返） | **既有红**（实测 `modalId="macOS"` 已正确但不满足其写法；`PUT` 空数组）——与本批无关，勿背 |
| `tests/devpanelname-panelrows.spec.mjs:162-164/333-335` | `[data-device-section="1"] .fm-device-row` 名文本链 | 不受影响（不用 update 键） |
| `tests/devnamefix-window-header.spec.mjs:239-246` | 同上 | 不受影响 |
| `tests/settings-cleanup.spec.mjs:214-219` | 设置账号段无 `.neblink-peers-list`（槽位外 `.neblink-peer` 计数） | 不受影响（只数行，不数键） |
| `scripts/e2e-fleet-unified-update.mjs` | fleet 真装包真重启 E2E（8 条剧本） | **保留**（设置腿）；本批不得触碰 |
| `scripts/shot-fleet-update-card.mjs` | fleet 卡截图 | 保留 |
| `tests/device-provenance.spec.mjs:143` | fleet roster 身份戳 | 保留 |

**服务端（保留）**：`WsSystemHandlers.scala:51/510-…`（`remoteUpdate` + 可选 `clientRequestId`）；`NeblinkRoutes.scala` 的 `POST /neblink/remote-update`（`:1154`）与本入口无关（不同腿）。

---

## ⑥ 定案 A：**双描述数据契约**（下游照此执行，禁各行其是）

### 6.1 契约表

| 主体 | 字段 | 语义 | 真源 | 本仓职责 | 落盘 | 网关路由 | 现状 |
|---|---|---|---|---|---|---|---|
| **好友** | `bio` | 对方**自我介绍** | **上游** | **只做缓存/回显**（不写、不改、不合成） | 不落盘（随上游下发透传） | 无（需上游先出字段） | ❌ **上游无该字段**（`PeerModels.scala:130-138`；Decoder `NeblinkModel.scala:1068-1081` 不读；Encoder `:1230-1240` 不出） |
| **好友** | `remark` | **我的备注** | **本仓本地** | 唯一真源、唯一写面 | `<dataRoot>/friend-remarks.json`（`Map[userId→String]`） | `PUT /api/friends/{id}/remark` `{remark}` → `{ok:true}` | ✅ **已存在**（§1.1 全链） |
| **群** | `description` | **群介绍** | **上游群管理** | 只做缓存/回显 | 不落盘 | 无（需上游群路由先出字段） | ❌ **上游行无该字段**（`PeerModels.scala:186-195`；`friendsApi.js:1031-1052` 归一亦无）；群面是纯代理，禁 reshape（`SocialRoutes.scala:880-905`） |
| **群** | `remark` | **我的群备注** | **本仓本地** | 新增真源（与好友 remark 同族同形） | `<dataRoot>/group-remarks.json`（`Map[groupId→String]`） | `PUT /api/groups/{id}/remark`（**新增**） | ❌ **不存在**（需新建，属新面） |
| **自己** | `bio`（`selfDescription`） | 我的自我介绍 | 本仓本地 | 唯一真源 | 复用 `DeviceIdentity.userDescription`（`NeblinkModel.scala:77`；持久化经 `DeviceIdentity.save` 落 `<dataRoot>/neblink/device.json`） | `PUT /api/neblink/device-info {userDescription}`（`NeblinkRoutes.scala:904-912`） | ⚠️ **语义冲突风险**：该字段现语义 =「设备描述，Agent 选设备用」（`neblink.deviceDescHint` `locales/zh-CN.js:2093`），且**发现报文刻意不含它**（`NeblinkModel.scala:557-563`） |

### 6.2 落盘/路由口径（逐条）

- 好友 `remark`：**不动**（既有键、既有文件、既有端点）。`trim` 后空串 = 清除（`FriendService.scala:160-165`）；长度 ≤64（前端 `REMARK_MAX` `contacts.js:649` + 提交边界再夹 `:676`）。
- 好友 `bio`：**不落盘**。上游一旦加性出键，只需在 `FriendSummary` 加 `bio: Option[String] = None`（形参置末 + 默认值 ⇒ 既有构造点零改动，同 `remark` 先例 `PeerModels.scala:128-129` 的加性最简形态）、Decoder 读它、Encoder 出它、前端 `personFromWire` 同步加键（`friendsApi.js:331`）。**在此之前前端一律按 `null` 处理，禁占位文案冒充**。
- 群 `description`：**不落盘**，同 `bio` 的加性路径（`GroupSummary` 加字段 + `normalizeGroupRow` 加键）。群面是纯代理，**禁在网关 reshape**（`SocialRoutes.scala:880-905` 明令），故必须上游先出。
- 群 `remark`：新建 `GroupRemarkStore`（**逐字镜像 `FriendRemarkStore`**：`path = PathUtil.dataRoot / "group-remarks.json"`、`Map[String,String]`、键 = `groupId`、`load/save` 同形）+ `FriendService`（或新的 `GroupService`）`applyGroupRemark(s)` 在群列表出站口注入 + 新路由 `PUT /api/groups/{id}/remark`（形态照抄 `SocialRoutes.scala:299-309`：`withAuth` + 缺参 400 + 恒 `{ok:true}`，**无 502 面**）。⚠️ 这是**新后端面**，本批若不含则显式列为未做项。
- 自己 `bio`：**建议不改现有语义**——`userDescription` 是设备描述（有 Agent 消费面），把「自我介绍」塞进去是语义污染。两条路：
  - **(a) 复用**：`userDescription` 兼作自述。代价 = 语义双关；`neblink.deviceDescHint` 文案要改；`DeviceProfile` 等已把它当「设备描述」消费（`core/tools/DeviceProfile.scala:179` 同族注释）。**不推荐**。
  - **(b) 新增** `DeviceIdentity.selfBio: Option[String]`（`NeblinkModel.scala:70-80` 加字段，加性）+ `PUT /api/neblink/device-info` 接受 `selfBio` + `/api/neblink/status` 回显。代价 = 一个新字段面 + 序列化兼容（`DeviceIdentity` 有手写 codec，`NeblinkModel.scala:92-116` 用 `as[Option[String]]` 折叠，加性安全）。**推荐 (b)**。

### 6.3 是否同步 neblink（同步缺口 = 卡点）

**现状实证：三条本地描述全部零同步。**

| 面 | 证据 |
|---|---|
| 好友 `remark` | 「**零上游协议变更、零跨设备同步**（⑦-D1 不做）——备注按 home 本地存」`NeblinkModel.scala:1364-1366` |
| 设备描述 `userDescription` | 「intentionally NOT included — descriptions are purely local, never exchanged between devices」`NeblinkModel.scala:560-562`；`handleAnnounce` 构造对端时也不带 `NeblinkService.scala:382-388` |
| 群名/群介绍 | 群面纯代理，本仓无本地覆盖层（`SocialRoutes.scala:880-905`） |

**可选方案与代价**

| 方案 | 做法 | 代价 | 判断 |
|---|---|---|---|
| S1 保持纯本地 | 不动 | 零 | ✅ **本批取此**（与既有三处先例一致） |
| S2 折进设备互联 | 把 `bio`/`remark` 塞进 `DeviceDiscoveryInfo`/`device-info` 的 relay 面 | 需改发现报文 + 跨设备信任面；备注是「我对他的私有标注」，**广播出去语义就变味**（等于公开给对端） | ❌ 语义错误 |
| S3 新增服务端字段 | 跨仓 `neblink-server` 加 `friend_remarks` / `group_description` 表与端点 | 跨仓契约变更 + 迁移 + 本仓三处（`NeblinkClient`/`FriendService`/`SocialRoutes`）改造 + 版本门槛 | ❌ 超出本批范围；且与「官网是账号真源」的分工冲突 |

**定案**：本批按 **S1**——所有描述字段**纯本地**，文档必须显式登记「**零跨设备同步**」为已知缺口（不是缺陷，是先例口径）。

---

## ⑦ 定案 B：**「描述进检索」走哪条路**

### 7.1 现状实证：两条检索腿，一条都不认描述

| 腿 | 入口 | 实现 | 能检索什么 |
|---|---|---|---|
| **后端查号**（唯一真检索） | `contacts.js:803-841` 搜索块（`input.fm-search-input` + `button.fm-search-btn`）→ `submitSearch` `contacts.js:769-797` → `api.searchUser(q)` | 网关 `SocialRoutes.scala:842-854` → `NeblinkClient.scala:637-642` → 上游 `/api/users/search` | **username OR email 的 NOCASE 精确匹配**（非模糊、非子串）。注释逐字：`>256 字符 422 invalid_query、超频 429`（`NeblinkClient.scala:631-636`） |
| **手机常驻搜索条·联系人腿** | `mobile/socialSearch.js:217-224` `runContactsSearch(q)` → **写回** `contacts.js` 的 `searchQ` 单一源 → 同一 `submitSearch(q, {throttle:false})` | 同上（**不是**第二条实现，是同一流水） | 同上（零范围 chips：群/设备/好友切面只在**消息页**腿有，`socialSearch.js:51-54` + `scopeFilter:370-375`） |
| **手机常驻搜索条·消息腿** | `socialSearch.js:413-458` `runSearch()` | 快照 `conversationSnapshot()` + 每会话 `getMessages` 前向翻页（20 页 × 50 = 1000 条）+ 300ms debounce + `AbortController` | **只有消息正文**（`scanConversation`），不含任何档案描述 |

**关键事实**：**好友列表本身没有任何本地过滤**——`contacts.js:367-392` 的 `#fm-friend-list` 是 `for (const f of friends) list.appendChild(friendRow(f))`（全量渲染，零 filter 谓词）。搜索结果走的是**独立结果卡**（`buildResultCard` `contacts.js:885-…`），不参与好友列表。

### 7.2 定案：走**前端本地过滤**，不走后端检索

**理由（三条，均可复核）**

1. **后端检索物理上够不着本地字段**：`remark` 是本仓本地 map（`<dataRoot>/friend-remarks.json`），上游 `/api/users/search` 的匹配键只有 username/email（`NeblinkClient.scala:637-642`）；把本地备注送上游检索 = 隐私外泄 + 协议变更。
2. **群面是纯代理**，网关禁止 reshape（`SocialRoutes.scala:880-905`），所以群 `description`/`remark` 也不可能进上游索引。
3. **既有先例已成体系**：群成员备注就是客户端本地解析（`api.friendRemarkOf` 唯一取值点，`friendsApi.js:555-585`；理由注记「group legs are zero-injection pass-through … group surfaces must resolve it client-side」）。

**落地载体（可实证的搜索入口，下游照此改）**

| 载体 | 文件:行 | 改法（本批口径） |
|---|---|---|
| **桌面联系人页好友列表** | `contacts.js:367`（`el('div','fm-friend-list')`）+ `:392`（全量 append） | 在 `render()` 内对 `friends` 施加一个**本地过滤谓词**（键 = `remark` + 未来的 `bio`；`String.prototype.includes`，大小写不敏感），过滤结果为空时给 `contacts.empty` 同族空态。**不动**搜索结果卡 |
| **手机联系人页常驻条** | `socialSearch.js:217-224` → `contacts.js:769` `submitSearch` | 保持「输入即写回 `searchQ` 单一源」的既有机制；`contacts.js` 侧按 `searchQ` 同时驱动 (a) 后端查号结果卡（既有，`searching/searchResult` 三态不变）与 (b) 好友列表本地过滤（新增）。**禁**在 `socialSearch.js` 里另写一套过滤（会出现第二真相源） |
| 现有测试面 | `contacts.js` 搜索块 `buildSearch` `:803-841`；`searchQ` 跨重渲保留 `:85`、`:810` | 新过滤必须复用同一 `searchQ`（否则清空/重渲后过滤态与输入框脱钩） |

**明确不做**：不改 `GET /api/users/search` 语义（仍是精确查号 = 「加好友」入口）；不给消息腿加描述检索（消息腿只检正文，`scanConversation` 单点）。

---

## ⑧ 需要新增的 i18n 键（对称纪律）

- 基线：zh **2360** / en **2353**，`zh\en` 独有 8、`en\zh` 独有 1（净差 7，见 §0）。
- 本批新增键**必须** `locales/zh-CN.js` 与 `locales/en.js` **同批同键对称新增**，位置按语义归组（备注族沿用 `contacts.menuSetRemark` / `contacts.remarkPlaceholder` / `contacts.remarkHint` 邻位：zh `:347-349` / en `:372-374`）。
- 若移除联系人设备更新入口：`neblink.updating` / `neblink.restarting` / `neblink.updateTimeout` 三键**失去唯一消费者**（§5.4），必须同批决定「删键」或「保留 + 注释理由（保留下游设置腿将来复用）」——**不允许**留成无声死键。
- 顺手登记（非本批新增）：`fleet.updateThisDevice` 已是死键（zh `:2121` / en `:2194`，零消费者；设置行实际用 `neblink.update`）。

---

## ⑨ 未决项 / 卡点清单（交给下游与决策面）

| # | 项 | 性质 | 建议 |
|---|---|---|---|
| U-1 | 好友 `bio` 上游无字段 | **卡点**（跨仓） | 本批只做展示位，缺失即不渲染；禁占位冒充「无自我介绍」以外的语义 |
| U-2 | 群 `description` 上游无字段（且本仓禁 reshape） | **卡点**（跨仓） | 同上；群介绍编辑入口本批**不做** |
| U-3 | 群 `remark` 需新建 store + 路由 | **新面**（本仓可做） | 若本批不含，显式写进「未做」；若做，逐字镜像 `FriendRemarkStore` |
| U-4 | 自己 `bio` 与 `userDescription` 语义冲突 | **决策**（二选一） | 取 §6.2 (b) 新增 `selfBio`；不建议复用（会污染 Agent 选设备语义） |
| U-5 | `unifyfix R1d` 与 fleet 批冲突（既有红） | **既有红** | 同批裁定 R1d 口径（是否承认「设置行每设备更新键」为合法新形态） |
| U-6 | 账号昵称/绑定无接口（G-1..G-5） | **卡点**（跨仓 + 裁定） | 控制台维持「只展示 + 跳官网」；禁前端造假 |
| U-7 | `check-js-types` 25 errors / `check-circular` 1 环 | **既有红** | 不背；改 `messages.js`/`contacts.js` 时注意不要新增（`messages.js` 已在 errors 名单内） |
| U-8 | `mobile-device-chat-windows.spec.mjs:137-138` 直接钉住 `[data-device-update-btn]` | **必改断言** | 移除入口的同批改 `toHaveCount(0)`；否则 spec 转红 |

---

## ⑩ 探针产物（不入库，仅供复核）

- 探针脚本（临时，已删）：`/tmp/recon-probe.mjs`、`/tmp/recon-probe2.mjs`、`/tmp/recon-probe3.mjs`（均已从工作区摘除，`git status` 干净）。
- 截图：`/tmp/recon-shots/01-chat-root.png`（chat 根单行三段式顶栏：左任务键/中头像/右搜索键）、`02-friend-conv.png`（好友：上「会话」大标题 + 返回钮，下窗头「我的备注 / alice@example.com」）、`03-group-conv.png`（群：窗头「Recon 群 / 3 名成员」+ 群设置钮）、`04-device-conv.png`（设备：窗头「工位机 / Windows / pencil 键 / 在线」）、`06-desktop-settings-open.png`（桌面设置页账号区：logo 头像入口 + 退出登录 + 「设备」折叠区含「全部更新」与行内「更新」）。
- 探针方法：静态路由引真实 `js/css/locale`，`/api/*` 与 WS 全 stub（`fm_api_mock=1` 种子 + `/api/neblink/status` 假读数），零后端、零真实端口；`pageerror` 全程 0。
