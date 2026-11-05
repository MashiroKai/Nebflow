---
type: batch-report
status: current
---

# 手机端长按退役 + 文件/图片消息 footer 补齐批（作者令 2026-10-31 22:55）

> 原批（10-31 深夜）死于供应商掉线零产出，本批重派。两件：①手机端长按弹引用/复制菜单整条退役（桌面右键不动）；②文件和图片消息补 footer（时间/身份+常用操作如下载，同族风格）替代长按触达。此前长按批 spec 翻断言，双主题截图存证。

## 0. 结论速览

| 件 | 落点 | 状态 |
|---|---|---|
| ① 长按退役 | `js/mobile/msgLongPress.js` 删除；bootstrap 激活谱系摘除；contextMenu 吞窗机制摘除；msgQuote 手机层 contextmenu 守卫 | ✅ |
| ② media footer | `popArtifacts.buildMediaFooterBadge`：身份+时间+引用钮+下载钮（`.duration-badge` 同族 pill，零新色值） | ✅ |
| spec 翻断言 | `tests/mobile-msg-longpress.spec.mjs` 重写（T1 翻、T2/T2b/T2c 新、T3 翻义保留、T4/T5 保留） | ✅ |
| 双主题截图 | 文件 footer / 图片 footer / 长按无弹 × {dark, light} 共 6 张 | ✅ |

## 1. 长按退役（处理器/菜单 DOM/样式/i18n 全清）

- **处理器**：`src/main/resources/web/js/mobile/msgLongPress.js` 整文件删除（touch 长按 480ms 计时器 + `__nfMobileLongPress` 探针随之消失）；`js/mobile/bootstrap.js` 激活谱系同步摘除（import/激活/teardown 三处），退役名录补记。
- **菜单 DOM**：长按腿没有自建 DOM（改前即复用桌面 `showPopupMenu` 单壳），壳随桌面右键保留；仅长按腿消费的 `swallowTouchClick` 吞窗机制（`contextMenu.js` 的 `_swallowClicksUntil` + opts 参数）作为长按处理器机械件一并摘除——桌面路径本就不传该旗标，零行为变化。
- **样式**：chat.css `.msg-quote-atts` 引用缩略带样式为桌面右键同腿共用，**保留**；mobileLayer.css 的 `.fm-context-menu` 全屏动作表属弹窗退场批（作者令 10-31②）的另一交互面，不属长按特性，不动。
- **i18n**：长按批未新增任何键（菜单词复用 `messages.copy`/`messages.quote`）⇒ 无可摘，自然全清。
- **守卫**（本批新增一处）：移动端浏览器对触屏长按会补发**合成 contextmenu**（iOS Safari / Android Chrome），只摘 touch 处理器则长按仍会经行上 `contextmenu` 监听弹菜单。`msgQuote.attachQuoteMenu` 的监听在 `body.mobile-layer` 下直接返回——手机层不开引用/复制菜单；桌面视口（非 mobile-layer）逐字不变（T5 硬验收钉住）。

## 2. 文件/图片消息 footer（替代触达）

- **落点**：`popArtifacts.buildUserMediaRow`（live 发送 / 历史恢复 / attachmentsSaved 原位重渲三路同一构建器）行尾追加 `buildMediaFooterBadge(items, ts)`。
- **形态**：`.duration-badge.media-footer`（与文本行 footer 同族 pill，零新色值，亮暗双主题走既有 token）：身份（我，`chat.quoteSenderMe`）· 时间（`formatFooterTime` 分层标签 + `bindTimeToggle`，`data-ts`/`data-ts-text` 契约与 `chat/durationBadges.js` 逐字一致——搜索跳转锚点与时制切换就地重排天然继承）· 引用钮（`createMsgQuoteButton`，徽章级幂等使 `mountQuoteFooterButton` 收敛不重复挂）· 下载钮（lucide download 同族 SVG）。
- **下载通路**：唯一实现落位 `nfTicket.downloadViaTicket`（ticketUrl 铸票 ⇒ /api/nf-file 整件 ⇒ blob 交浏览器下载管理器）；chat.js AskUser 附件行委托同函数（禁第二套开法）。多件行单钮按序下载全部可下载件；失败可见降级（`messages.attachDownloadFailed` 两秒自清，禁静默）。
- **pathless 纪律**：live 发送未落盘（item 无 path）不挂下载钮（禁死点击）；`attachmentsSaved` 帧回传后 `patchUserMediaRow` 原位重渲，钮随行出现。ts 随 `registerUserMediaRow` 登记传递，重渲后 footer 时间 = 原发送时刻不漂移。
- **时间单源**：live 腿 `renderUserBubble` 传 `timestamp`，历史腿 `persistence.buildUserAttachmentRows` 传 `m.timestamp`（两条重放腿同步改），引用快照 `meta.ts` 同口径对齐。

## 3. spec 翻断言（tests/mobile-msg-longpress.spec.mjs）

| 用例 | 改前 | 改后 |
|---|---|---|
| T1 | 长按弹复制/引用菜单+吞窗保活 | 长按 620ms **无**菜单；探针 `__nfMobileLongPress` 不在场；合成 contextmenu 无菜单（暗/亮按住态截图） |
| T2 | 长按媒体行引用发送 | media footer 结构（身份/时间属性/引用钮/下载钮）+ 下载点击走铸票→nf-file 字节通路（nf-file 命中表断言）+ 双主题截图 |
| T2b/T2c | —（新） | pathless 无下载钮；footer 引用钮→引用态→发送→缩略带（手机端引用可达面闭环） |
| T3 | 滑动取消长按 | 滑动/轻点恒无菜单（翻义保留，防处理器复辟） |
| T4 | 历史引用快照缩略带 | 保留不动（渲染器未变） |
| T5 | 桌面右键同腿 | 保留并升格为「桌面右键不动」硬验收（菜单项逐字 ['复制','引用']） |

## 4. 验证与门禁

- `node scripts/check-js-types.mjs`：本批触碰面全清（popArtifacts 干净；msgLongPress 条目随文件退役摘出基线，total 350→345 只降不升）。main 上**既存**两处漂移（`modelPanel.js` 新文件 10 错、`scheduled-task.js` TS2339 9>8）与本批无关（两文件与 HEAD 逐字节一致），未越权代修。
- `scripts/check-circular.mjs`：main 上既存红（navController↔taskPanel 双静态边，HEAD 可溯），本批新边 popArtifacts→timeFormat（branding+i18n 叶子）不成环。
- 回归：本 spec 全绿 + quote-message（16）/msgmenu/pop-artifacts/mobile-v3/mobile-dock/attach-bubble-unify 族（桌面右键与媒体行渲染腿未动的旁证）。
- 截图存证：`evidence/<ts>_mobile-msg-footer-longpress-retire/shots/`（6 张双主题 + 桌面引用块 1 张）。

## 5. 边界与让渡

- 手机层「右键」入口（B 族全屏动作表）仍在（弹窗退场批面），本批只保证**主聊天消息行**长按/合成 contextmenu 不再弹引用/复制菜单；好友窗 msgmenu 等其它上下文菜单面不属本令范围，未动。
- 纯图/纯文件消息的历史腿既有「裸行时间小标」（`createAiCopyBadge` 无正文时 time-only）为平台既存行为，未翻案；media footer 叠在其下，若嫌重复可后续单令收敛。
- LLM Pop 产物行（工具类）按 beta.56 裁定不挂 footer，本批未翻案。

## 6. 关联

- 改前长按批设计：本 spec 文件头注 + git 历史 dda644590（`mobile long-press msg menu + quote attachment thumbs`）。
- 同系手机层批上下文：[[20261030_084500_dock-message-badge__delegate-nebflow-12dfb591]]（dock 收形/消息流族）——mobileLayer 弹窗退场批（B 族全屏动作表）为长按菜单改前的手机层呈现壳。
- 字节通路单一化：nfTicket 铸票机制（nfTicket.js 头注）——`downloadViaTicket` 为其消费侧新出口。
