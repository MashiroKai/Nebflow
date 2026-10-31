---
type: fix-report
status: published
date: 2026-10-31
scope: 联系人页设备分组（contacts.js / messages.js / updateCard.js 退役）+ 手机层会话窗收编（mobile/navController.js / mobileLayer.css）+ locales zh+en
---

# 设备平级 + 手机端设备对话窗口修复批（2026-10-31 09:06 作者令 + 截图两张）

作者两张手机现场截图（[[20261030_100600_projcreate-device-flat-three-fixes__delegate-nebflow-a97665b1]] 设备平级设计语言批的接续令）：联系人页「本机 2026.10.26-beta.2 · beta · 4e339ad64」行退役、本机 Mac 作为普通设备进「设备」分组；条目不显示版本/commit；手机端设备对话窗两病——两窗重叠（KAI 与 DESKTOP-IJBBEOT 同屏各带输入条）+ 消息可左右滑。正常形态 = 单一固定对话窗（一进一出，像微信聊天页）。

## 三修结论（根因一句/落点）

### 修① 本机进设备列（设备平级）

- **根因**：本机被单列为一条「版本·通道·commit」更新卡（`updateCard.js` 的 `fm-local-update-card`），与对端设备行两种规格——设备平级设计语言下本机应是对端同规格的条目。
- **落点**：
  - `messages.js` `devicePeers()`：本机支前置（`{...rel.device, online:true, directOnline:true, isLocal:true}`，同 state 单点、按 deviceId 防御剔重）；`deviceConvs` 既有本机防御（不成窗）原样接住。
  - `contacts.js`：退役 `buildLocalUpdateCard()` 挂载；`deviceRow` 对 `isLocal` 行**不挂**远程更新键组（`remoteUpdate` 是远控对端语义，本机更新归编排器/设置面——版本读数设置侧栏脚 `sidebar.js` serverVersion 已有）。
  - `updateCard.js` 模块连同 `neblink.updateCard.*` i18n 键组（zh+en）与 `.fm-update-card*` CSS 规则**整块删除**（沿 ⑤b/auth-trim 先例：零死键死规则）。
- **验收**：设备分组首行 = 本机（名/在线/系统，无更新键），对端行规格不变；联系人页零版本/commit 串；点本机行进对话窗。设置页设备展开区同源渲染（同 `devicePeers()`），本机行同步入列。

### 修② 手机端双窗重叠（截图②实锤）

- **根因**：`navController.js` `watchChatModal` 收编时把 `.fm-modal` re-parent **出** `#fm-chat-overlay` 进 conversation 页体；桌面 `renderChatModal` 整建（remove + create overlay）只摘除空 overlay 壳——**旧窗体留在页体内**，新窗 append 后页体两窗同屏、各带各的输入条。
- **落点**：收编新窗分支弃槽时 `chatAdopted?.modal?.remove()`（旧注释「旧节点已随旧 overlay 摘除」的假设不成立——re-parent 后旧节点已不在 overlay 里）。
- **验收**：375 视口 本机→KAI→返回→DESKTOP 一进一出，每拍页体内恰一 `.fm-modal`；全页（含 overlay 壳）`.fm-modal` 计数恒 1。

### 修③ 禁横向滚动（截图②实锤）

- **根因**：`.mobile-page-body` / `.fm-flow` 都只声明 `overflow-y:auto`——按 CSS 规范，另一轴 `visible` 计算为 `auto` ⇒ 宽内容（长路径/宽卡/pre 块）把页体/消息流变成横向可拖。
- **落点**：`mobileLayer.css` 三条（全部 `body.mobile-layer` 作用域，桌面零触碰）：页体 `overflow-x:hidden`（页内一切收编面兜底）+ `.fm-modal .fm-flow` `overflow-x:hidden` + 气泡 `overflow-wrap:anywhere`（断词承接长串，零内容丢失——气泡面既有 `word-break:break-word` 之上补 anywhere 档）。
- **验收**：注入 300 无空格长串 + 800px 固定宽块后，页体/消息流 computed `overflow-x` = hidden、气泡折行贴容器宽、document 级无横向滚动条。

## 回归读数

- 新套件 `tests/mobile-device-chat-windows.spec.mjs`（静态 route + stub，零后端）：M1 行规格（3 行/首行本机/无更新卡/无版本串/本机无更新键）+ M2 一进一出不串窗 + M3 禁横向滚动（机制 + 窗口级双不变量）+ D1 桌面端不回退（1280 视口同规格 + overlay 单窗开闭）——**2/2 绿**。
- 验红实证（变异安全）：① 摘 `chatAdopted?.modal?.remove()` ⇒ M2 在 KAI 拍抓到 2 窗（复现作者截图形态）；② 摘全部三条 clamp/断词 ⇒ M3 机制断言红。**修复是唯一直达绿的路径**。
- 相邻既有套件 32 项全绿：`devnamefix-window-header` / `devpanelname-panelrows` / `ui-device-faces`（25 项含 mobile-dock）/ `mvp2-device-client` / `mobile-v3`。
- 证据截图：`.nebflow/evidence/20261031_device-chat-windows-fix/`（m1 本机行 / m2 单窗×3 / m3 / d1×2）。

## 开放项

- 本机行的「点进对话」开窗成立（与对端同链），但本机对话窗的历史数据面（legacy 台账/服务端行）本机不成窗（`deviceConvs` 语义：本机行只是对端窗的公共半边）——窗体恒空属既有语义，未在本批扩面。
- 手机端会话页返回键只弹页不关闭桌面态会话窗（`closePageFace` conversation 面态不翻转，既有口径）——本次靠收编整建时的旧窗摘除保证不串窗，未改返回语义。
