---
type: design
status: implemented
---

# 件二+件三：三种会话页头同构 + 悬浮身份头（2026-11-02 作者令）

作者令原文（本节点任务书逐字）：

> 【件二 +件三 ·三种会话页头同构（左返回 /右三点 /中悬浮身份头），与主对话窗视觉
> 完全一致，且不得有横贯顶栏带】

硬约束（上一批因此打回，本批逐条兑现）：

1. 页头 = **只有左右两颗钮**：左上返回、右上三点；中间是**悬浮身份头**（圆头像 +
   名字胶囊在下居中，悬浮于内容之上，内容从后面穿过、滚动渐隐）。**禁止**做成
   「横贯屏幕、高度 ≥40 且宽 ≥90% 且带背景/描边」的 fixed 顶栏带。
2. 三种会话（好友 / 群 / 设备）与主对话（agent 会话）**视觉形态必须完全一致**，
   差异只能来自数据（头像、名字、副标题有无）。不许任一会话类型保留旧的「行内
   小头像 + 名字」形态。
3. 无名态：胶囊不渲染，头像仍居中。设备无头像 → 走品牌 logo / 平台图标兜底。

上游形态：[[20261102_090250_mobile-chat-topbar-single-row]]（批 221391120 的
`position:fixed` 真顶栏 —— 本批**整条退役**的正是它）、
[[20261101_235748_chat-head-position-fix__delegate-nebflow-9c9ef879]]（Muse 式
sticky 顶锚，同批退役）、[[20261102_muse-avatar-shrink__delegate-nebflow-d616c46a]]
（56px 头像档位，本批沿用并提升为四种会话唯一档）。
兄弟件：[[20261102_130500_profile-dual-desc__delegate-nebflow-16c2e54c]]（件一，
本批三点菜单「查看资料」直接调它的导出函数）。

---

## 1. 组件落点（唯一实现，禁第二套）

| 面 | 文件:行 | 说明 |
|----|---------|------|
| 行为实现 | `js/mobile/sessionHead.js`（新文件） | `IDHEAD_FADE_DIST=64`；`bindIdHeadFade(head, ...scrollers)`（rAF 合帧写 `--idhead-p`，多滚轴取最大 scrollTop，reduced-motion 二值，p≥1 时 `visibility:hidden`）；`createSessionIdentityHead({avatar, nameEl, moreTitle, onMore})` → `.mh-idhead.mh-sess-head` + `.mh-idhead-avatar` + `.mh-idhead-name` + `.mh-sess-more` |
| 样式唯一块 | `css/mobileLayer.css` 文件尾「会话页头同构批」块（约 3669–3870） | `.mh-idhead`（absolute / 零背景 / 零描边 / pointer-events:none / `opacity: calc(1 - var(--idhead-p))`）、头像外径 56、名字玻璃胶囊（`:is(.mh-idhead-name, .mh-idhead > #session-name)`）、`.mh-sess-more`（48px g2-fab @ top 16+safe / right 16）、会话页 `.fm-modal-header` 零高收口、`.mobile-page[data-page='conversation'] .mobile-page-body{padding-top:104px+safe}`、`#top-overlays{top:96px+safe}` |
| 主对话接线 | `js/mobile/chatChrome.js` | `chatHead.className = 'g2-chat-head mh-idhead mh-agent-head'`；`bindIdHeadFade(chatHead, chat)`；teardown 对称解绑 |
| 三会话接线 | `js/messages.js` `renderChatModal`（手机层守卫块 ≈ :1316–1390） | 头插在窗头行之后、三点键 append 到 `.fm-modal`；头像槽 `.fm-modal-avatar` 与名字 `.fm-modal-name` **整节点迁入**（非复制）；三点菜单见 §3 |
| 返回钮真关窗 | `js/mobile/navController.js` `closePageFace` `case 'conversation'`（≈ :1766） | 动态 `import('../messages.js')` 调 `closeChat()`（该函数本批升为 `export`） |
| 顶让位 | `js/main.js`（≈ :4330–4380） | fixed 顶栏实测链（`mobileHead`/`headRo`/建拆层广播）整条退役，回到 `bandBottom()` 单一真值 |
| 降级名单 | `css/glass.css`（@supports not / html.no-backdrop 两处） | `#chat > .g2-chat-head` → `.mh-idhead-name`（身份头本体零背景，只需胶囊降级） |
| 查询根收口 | `js/messages.js` `modalRoot()`（≈ :794） | 手机层窗体被 re-parent 进页体后 `overlay.querySelector` 全部落空 ⇒ 8 处查询改走 `modalRoot()`（零行为语义变化，修的是既有静默失活） |
| i18n | `js/locales/zh-CN.js` / `en.js` | 对称新增 `session.more`（更多 / More）、`session.deviceDesc`（设备描述 / Device description） |

---

## 2. 形态与判据（为什么过「无横贯顶栏带」）

- 身份头 = `position:absolute`，包含块 = `#main`（主对话）/ `.mobile-page`（三会话）
  —— 二者都在滚动容器之外 ⇒ 头**不随内容滚、不被 overflow 裁**（CSS 2.1 §11.1.1）。
- 盒是**全宽零背景零描边**（`background:none; border:none; box-shadow:none;
  pointer-events:none`）⇒ 几何上不可能命中「height≥40 && width≥90% && 有背景或描边」。
  头像/名字各自 `pointer-events:auto`（点击语义不变）。
- 层序：身份头 26 < 右上三点 27 < 悬浮双钮层 30 < 页面栈 156 < dock 200。
- `--idhead-seat: 12px` ⇒ 头像中心 y = 12 + 28 = **40** = 左右 48px 钮中心
  （top 16 + 24）⇒ 三种会话与主对话的头像中心同轴同高。

---

## 3. 右上三点菜单（入口都走既有实现，禁第二套）

`showPopupMenu(x, y, items)` 挂在 `.mh-sess-more` 的 rect 下方：

| 会话 | 菜单项 | 落地函数 |
|------|--------|----------|
| 好友 | 查看资料 | `profileFace.openFriendProfile(conv.friend)`（件一导出函数，动态 import） |
| 群 | 查看资料 / 群设置 | `openGroupProfile(conv)` / `mountDrawer()`（与窗头 `.fm-gs-open`、群名热区**同一函数**） |
| 设备 | 设备描述 | `openDeviceDescPanel(conv, deviceDescRow, btn)`（既有唯一开合腿） |

🔴 **偏离申报**：设备无 Profile 面（件一只有好友/群两种），故设备三点菜单项 =
既有描述编辑面板，而不是「查看资料」。另：设备三点菜单文案用新增键
`session.deviceDesc`（既有 `neblink.deviceDescHint` 是整句提示，当菜单项太长）。

---

## 4. 与 #1365（分支 `delegate/delegate-nebflow-accdcd3b`）的对接点

`#1365` 在做同一件事（主对话顶栏回退悬浮形态），且**相对 main 零提交**（无组件
可消费）。本批落点为「同一组件名 / 同一 CSS 块 / 同一渐隐驱动器」的**唯一**实现：

- 类名族：`.mh-idhead` / `.mh-idhead-avatar` / `.mh-idhead-name` / `.mh-sess-head` /
  `.mh-sess-more`；
- CSS 块：`css/mobileLayer.css` 文件尾「会话页头同构批」（块头与 `sessionHead.js`
  文件头both 带 🔴 对接注记）；
- 驱动器：`sessionHead.bindIdHeadFade`（主对话与三会话共用，禁第二套进度）。

**合流规则**：以本批类名与取值为准；若 `#1365` 另带近似实现，**以其组件名对齐本批
类名后删另一套**，禁两套近似样式并存（文件头注记已写明同一条）。

---

## 5. 机械验收读数（`tests/conversation-heads-unify.spec.mjs`，375×812 / hasTouch / dark）

命令：`node node_modules/@playwright/test/cli.js test tests/conversation-heads-unify.spec.mjs --workers=1 --reporter=line` ⇒ **1 passed (4.7s)**。

### ① 结构同构 + ③ 视觉一致（主对话 = 对照基准）

| 读数 | 主对话（chat） | 好友 | 群 | 设备 |
|------|---------------|------|----|------|
| 返回/左键 rect | 48×48 @ (40,40) cx40 cy40 | 48×48 @ (16,16) cx40 cy40 | 同左 | 48×48 @ (15.9,15.9) cx40 |
| 三点/右键 rect | 48×48 cx335 cy40 right359 | 48×48 cx335 cy40 right359 | 同左 | 同左 |
| 头像 rect | 56×56 cx**187.5** cy40 | 56×56 cx**187.5** cy40 | 同左 | 同左 |
| 头像中心 − 屏幕中线 | — | **0.0px** | **0.0px** | **0.0px** |
| 头像中心 y − 主对话 | 0 | **0.0px** | **0.0px** | **0.0px** |
| 名字胶囊 fontSize / weight | 15px / 600 | 15px / 600 | 15px / 600 | 15px / 600 |
| 名字胶囊 padding | 3 / 14 / 3 / 14 | 同左 | 同左 | 同左 |
| 名字胶囊 borderRadius | 999px | 999px | 999px | 999px |
| 名字胶囊 background | `rgba(24, 28, 38, 0.68)` | 同左 | 同左 | 同左 |
| 胶囊中心 − 头像中心 | — | **0.0px** | **0.0px** | **0.0px** |
| 身份头 position / 背景 / 描边 | absolute / `rgba(0,0,0,0)` / 0px | 同左 | 同左 | 同左 |
| 旧窗头可见子节点 | — | **0** | **0** | **0** |
| 页面壳大标题可见 | — | **false** | **false** | **false** |

胶囊宽随文本（老友 57.7 / 项目群 72.6 / KAI 52.5），高度恒 24 —— 差异只来自数据。

### ② 横贯顶栏带扫描（fixed 元素全量）

判据 = `height≥40 && width≥90vw && (有背景 || 有描边) && 落在上半屏`。

| 场景 | fixed 总数 | 顶栏带命中 | 下半屏豁免（读数） | near-miss（读数） |
|------|-----------|-----------|-------------------|------------------|
| 主对话 | 2 | **[]** | `#mobile-dock` 351×54 (93.6%) top750 bg `rgba(24,28,38,0.68)` border=true | `#mobile-pages` 375×812 (100%) bg 透明 border=false |
| 好友会话 | 3 | **[]** | 同上 `#mobile-dock` | `#mobile-pages` 375×812 透明无描边；`#fm-chat-overlay` 375×812 透明无描边 |
| 群会话 | 3 | **[]** | 同上 | 同上 |
| 设备会话（描述面板展开态） | 3 | **[]** | 同上 | 同上 |

两条 near-miss 的 `#mobile-pages` / `#fm-chat-overlay` 都是**透明零描边的全屏页面壳
/ 空 overlay 壳**（宽 100% 但无背景无描边 ⇒ 视觉上零存在），不是顶栏带。
唯一几何命中者是**底 dock**（top 750，在视口下半屏）——作者禁令针对顶栏，故按竖直
位置二分显式豁免并列读数，零隐藏。

### ④ 滚动渐隐（好友会话，消息流 40 条 ⇒ 可滚）

| 时点 | `--idhead-p` | computed opacity | visibility | scrollTop |
|------|--------------|------------------|------------|-----------|
| 滚动前 | `0` | **1** | visible | 0 |
| 滚动后（scrollTop 120） | `1` | **0** | **hidden** | 120 |
| 回滚后 | `0` | **1** | visible | 0 |

（还原路径同测：防「一隐不返」。）

### ⑤ 无名态

胶囊 `display: none`（`:empty` 收口），头像中心 **187.5** = 屏幕中线 **187.5**（偏差 0）。

---

## 6. 回归（本分支实测）

| spec | 结论 |
|------|------|
| `conversation-heads-unify.spec.mjs`（新） | **1 passed** |
| `mobile-device-chat-windows` | **2 passed**（含返回钮真关窗 M2） |
| `mobile-dock` | **21 passed**（D19 合成 `fm-modal-close` 判据仍绿） |
| `mobile-pinned-search` | **11 passed**（P7 top 4→**16** 判据已随批改写） |
| `profile-dual-desc`（含手机 D4） | **4 passed**（D4 群设置入口改走三点菜单） |
| `mobile-v3` | 8 passed / 1 failed —— T2 任务角标 `10 vs 8` **在 HEAD 上同红**（fixture 漂移，非本批） |
| `friend-trust-sealed`（桌面 1440） | **ALL PASS** |
| `msgmenu`（桌面 1280） | failures=1（S6 mic-orb 单例）**在 HEAD 上同红** |
| `unifyfix-eight-points`（1440） | mismatched 10 条 **在 HEAD 上同数同项** |
| `liquid-glass` | 6 failed —— **在 HEAD 上同数**（桌面按钮族 token/折射层批遗留） |
| `node scripts/build-web.mjs` | orphans **0**、244 lazy chunks |
| `node scripts/check-circular.mjs` | 仅既有两条 SCC（popArtifacts/reference/msgQuote、navController/taskPanel），**零新增** |

---

## 7. 未完成 / 等合流 / 偏离申报

1. **等 #1365 合流**：两批同一件事，合流时按 §4 规则只保留一份实现（本批已把可
   合并面对齐到同一类名/CSS 块/驱动器）。
2. **偏离：设备无 Profile 面** —— 三点菜单给「设备描述」而非「查看资料」。
3. **偏离：`.fm-modal-header` 视觉收口而非 DOM 删除** —— 会话页内窗头行压成 1px /
   透明 / `pointer-events:none`，子件 `display:none`（DOM 保留 ⇒ 桌面形态与既有断言面
   `.fm-modal-name` 文本、`.fm-trust-slot`、零 diff）。
4. **偏离：群球径走 cqw 等比** —— `.fm-avgrid` 的逐档球径表以容器 `cqw` 表达，换成
   56 容器后球径自动等比放大，无需逐档重算（读数见批内 spec 的头像外径断言）。
5. **偏离：身份头 absolute 全宽盒但零背景零描边** —— 全宽只为结构性水平居中，视觉
   足迹只有头像与胶囊（② 号判据的正面读数）。
6. 六个旧 shots 脚本按批注记处理：五个加退役横幅（形态已被整条取代，重跑必红是
   预期），`dock-avatar-edge-unify-shots.mjs` 的顶部头像档 40 → **56** 就地改写。
