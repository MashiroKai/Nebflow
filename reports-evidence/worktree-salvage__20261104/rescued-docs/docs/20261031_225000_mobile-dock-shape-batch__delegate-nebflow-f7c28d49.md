---
type: design-spec
status: frozen
date: 2026-10-31
scope: 手机层 dock 收形批六件（作者 10-31 22:50 令+实拍截图）——消息流头像+agent 名恢复 / dock 中键去文字 / 无头像 stand logo 回落 / 中键边缘收简 / 中键收回 dock+输入间隙收窄 / 发送钮退回纯圆；电脑端零触碰
---

# 手机端 dock 收形批 · 施工记录（2026-10-31 22:50 作者令六件）

关联：[[20261031_161500_mobile-topbar-batch__delegate-nebflow-53e293d1]]（会话头/悬浮顶缘语义来源）、[[20261031_170000_liquid-glass-ab-final__delegate-nebflow-a1031f13]]（g2 材质族）、f1b24d5df（dock 中键改版 23:36——本批②③④⑤的改写对象）、07e20bffc（主对话头像恢复 11:38 令②——本批①的旧令源头）。

## §0 六件与判据

| # | 令 | 现状根因 | 施工 |
|---|---|---|---|
| ① | 主对话每条 agent 消息带头像+agent 名（旧令「不要去掉」手机端从未生效——考古确认 per-message 身份头**从未实现**，非批间漏挂：主对话只有会话头 `.g2-chat-head` 一处身份面，`.row.ai` 行生来就是裸泡，桌面同形态） | 无实现可补漏 | chatChrome.js 新增 `#chat` childList 守望：`.row.ai`（直子 `.bubble.ai`、非代发行）泡上方挂 `.g2-msg-meta`（28px 圆头像 + 名）；头像 = `agentAvatarDataUrl()`，名 = `rootDisplayName()`；头像/名变化（`onAgentAvatarChange`/`nebflow-root-name-change`）全量重刷；teardown 全量摘除 ⇒ 桌面 DOM 逐字节还原 |
| ② | dock 中键只留图案，「对话」二字去掉 | buildDock 中键与四键同构挂 label | navController.buildDock 中键分支不再建 `.dock-label`，语义由 `aria-label`（`tt(DOCK_CHAT_DEF.zh/en)`，随 locale 更新）承接 |
| ③ | 无头像 agent 用 stand logo 占位，不显示空白/默认人形 | 中键回落 message-circle 气泡字形；会话头空态 = 中性 user 人形；消息流新面无回落 | 三面统一 stand logo（`brand.logoUrl/logoDarkUrl` 双主题 picture，与「我」入口同货源）：中键 `.dock-center-logo`（62% contain）、消息流 `.g2-msg-logo`（72%）、会话头 `.agent-avatar-stand-logo`（58%，slot childList 守望注入——只接管人形字形档，首字母牌是身份标记保留）；teardown 摘注入 |
| ④ | 中键边缘收简（现大红钮一圈复杂边），纯色或极简描边 | rest `0 2px 8px …/0.35` 浮影 + active `0 0 0 3px …/0.18` 光环 | 两档 box-shadow 全摘（`box-shadow` 计算值 = none，spec 钉），纯色 fill；active 视觉反馈收口为按压 scale(.94) |
| ⑤ | 中键不超出 dock（上缘探出收回）+ 输入框与 dock 间隙收窄 | `--dock-center-lift: 12px` 负 margin 顶出 dock；`#input-area` padding-bottom 含 lift | `--dock-center-lift` 12 → 0（变量保留 = spec 探针面不变），`.dock-center` margin-top 摘除、`.dock-key-center` 改居中；输入卡底缘与 dock 顶缘缝 = lift = 0（spec 恒等式断言） |
| ⑥ | 发送钮退回圆形（绿保留） | 药丸 r999 h32（d4cc8d189 v2 换代） | 手机层覆盖块：36×36 r50% padding 0（考古 `d4cc8d189^` 圆钮基准，与输入条 36px 图标钮同高）；`--ctl-send-*` 绿三态 token 单源不动（桌面药丸原样） |

约束面：全部改动落 `css/mobileLayer.css`（body.mobile-layer 作用域）+ `js/mobile/*`（bootstrap 手机视口才 import 的模块）⇒ 桌面端由构造保证零触碰；桌面回归由 button-system 桌面档 + liquid-glass 桌面 describe 钉。

## §1 改动清单

### 1.1 js/mobile/navController.js

- `DOCK_CHAT_DEF` 注记改写（回落 = stand logo；zh/en 只承接 aria-label）。
- `buildDock`：中键分支 `btn.setAttribute('aria-label', tt(zh,en))` + 只 `append(center, badge)`；label 移入四键分支（中键零孤儿节点）；`onLocale` 判空 `.dock-label` + 中键 aria-label 随 locale 更新。
- `paintDockCenterAvatar`：回落腿 `holder.innerHTML = <picture><source dark=logoDarkUrl><img.dock-center-logo src=logoUrl>`（幂等判据 `.dock-center-logo`）；lucide 字形腿退役（`createIconsIn(holder)` 调用随批移除，四键图标腿不变）。

### 1.2 js/mobile/chatChrome.js

- 新增 imports：`agentProfile.{agentAvatarDataUrl,onAgentAvatarChange}` / `avatarRender.avatarImgNode` / `rootName.rootDisplayName` / `brand`（leaf 模块，check-circular 与 main 逐字节同——navController↔taskPanel 既有 SCC[2] 存量不动）。
- 消息流身份头：`paintMsgMeta` / `decorateAiRow` / `repaintAllMsgMeta` 三函数 + `msgMo` 守望（childList，行一律 appendChatRow 直挂 #chat 单点入口；行内泡流式 innerHTML 不在 childList 面 ⇒ 守望零热路径成本）；装饰判据 = 直子 `.bubble.ai` 且无直子 `.agent-badge`（代发行有自己的徽章，标 root 名反错标）；teardown `document.querySelectorAll('#chat .g2-msg-meta').remove()`。
- 会话头空态 logo：`paintAgentSlotLogo` + `avatarSlotMo`（slot childList——paintSlot 的 replaceChildren 每绘必抹注入，守望随绘随补）；接管面 = 人形字形档；teardown 摘 `.agent-avatar-stand-logo`。
- 头部注记补收形批条目。

### 1.3 css/mobileLayer.css

- 头注补收形批六件块；`--dock-center-lift: 0px`（批⑤）。
- 中键块改写：`.dock-key-center` 居中（flex-start→center，padding 2px）；`.dock-center` 摘 margin-top/两档 box-shadow/`.dock-center svg` 字形档；新增 `.dock-center-logo`（62% contain）；active 光环块 + `.dock-label` 字重档（死规则）摘除。
- 文件尾批次块：`#input-area` 注记改写（lift 归 0 语义）；`#send-btn` 手机层圆钮块（批⑥）；消息流身份头块（`.g2-msg-meta/.g2-msg-avatar-slot/.g2-msg-avatar/.g2-msg-logo/.g2-msg-name`）；会话头 logo 槽块（`.agent-avatar-stand-logo/.agent-avatar-logo-img`）。

### 1.4 spec 换代（判据随令换代，全绿）

- `tests/mobile-dock.spec.mjs`：D1 labels 中键位 null + aria-label=对话；D10 lifted→inside + shadow none + logo 回落（标题/断言换代）；D13 lift 12→0；D11 回落腿 logo img 断言。
- `tests/mobile-v3.spec.mjs`：T1 labels 数组中键位 null。
- `tests/cs1030-coldstart-batch.mjs`：腿 D1/D2 断言换代（inside / gap≈0）。
- `tests/liquid-glass.spec.mjs`：M5 发送键换代（r999 h32 → r50% 36×36，收形批⑥）。
- 新增 `tests/mobile-dock-shape-shots.mjs`：六件逐条机械探针 × 亮暗 × 有/无头像四腿 + 截图取证（见 §2）。

## §2 证据

- `node tests/mobile-dock-shape-shots.mjs` ALL PASS（24 探针零红）：产出 `evidence/20261031_mobile-dock-shape/`——`dock-{dark,light}.png`（dock 全景，中键 = 头像图）、`msgflow-{dark,light}.png`（消息流带头像样张：每条 agent 消息 = 圆头像 + Nebula 名）、`logo-fallback-dark/light-nologo-seed.png`（无头像回落样张：中键+消息流+会话头三面 stand logo，零默认人形）、`dock-*-nologo-seed.png`。探针覆盖：① ai 行全带身份头（名非空+img 可绘）②中键零 label+aria=对话 ③三面 logo/有头像三面 img ④中键 box-shadow==none ⑤圆内 dock+输入卡缝 0.00px ⑥send 36×36 r50% rgb(7,193,96)。
- 回归：mobile-dock 21/21、mobile-v3 7/7、liquid-glass 11/11（含桌面 describe——桌面 #header 基线零触碰）、mobile-topline-clear 3/3、mobile-fab-search、mobile-chat-topclip、agent-identity-sync、mobile-p5-memory、mobile-p12-settings、uifix-quad、quote-message 16/16、f1-send-queue 13/13、mobile 全族 battery 63 passed；composer-slim-shots ALL PASS；checkJs 344 < baseline 350 PASS；check-circular 与 main 同（SCC[2] 存量）。
- 存量红申报（净 HEAD 同判，与本批零关）：cs1030-coldstart A7a 缩放条阴影 ≠ #input-bar 双主题（pristine 同签名）；mobile-p3-reminder T4「今天/明天」跨日翻牌（23:5x 时点夹具 00:54 落明天，pristine 同判）；button-system-monochrome 12 fail（10-30 墨族批被 v3 绿色换代覆盖后的判据漂移，pristine 同 18 ok/12 fail）。

## §3 不动面

- 桌面端：`#send-btn` 药丸/`#header`/`#chat` DOM 零变化（手机装饰 teardown 摘除还原；button-system 桌面档同判存量）。
- 代发行（`.agent-badge`）、user 行、tool 行、notice/error 行不挂身份头。
- 首字母牌（已命名无头像会话头）保留——身份标记非「空白/默认人形」。
- `--dock-center-lift` 变量保留（0px）：spec 探针面（D13 marginTop var 探针）语义不变。
