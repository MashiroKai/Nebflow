---
type: batch
status: done
related: "[[20261031_mobile-askuser-whitescreen]]"
---

# 手机端长按菜单退役批 + 媒体行 footer 补齐（2026-10-31 22:55 作者令）

> 批令（作者 10-31 22:55）：反悔当日 09:04「手机端长按=桌面右键」批——① 手机端长按弹出的引用/复制菜单整条退役（长按不再弹任何动作面；桌面右键菜单不动）；② 文件和图片消息补 footer（现文本消息有的底部信息条，文件/图片缺）——footer 内容自定（时间 + 常用操作入口下载，照文本消息 footer 族风格），让文件图片也有下方落点，替代长按触达。约束：手机层专属；退役要干净（处理器/菜单 DOM/样式/i18n 全清，不留死代码）；双主题截图；相关 spec 全绿（含此前长按批的 spec 翻断言）。

## 1. 退役面（①）——逐项清点

| 退役物 | 处置 |
|---|---|
| `js/mobile/msgLongPress.js` | **整文件删除**（git rm）：touch 长按计时器、手势取消、探针 `__nfMobileLongPress` 全部随之消失 |
| `js/mobile/bootstrap.js` | 激活谱系摘除 msgLongPress（import / activate / teardown 三处）；头注改记退役事实与替代落点 |
| `js/contextMenu.js` | `swallowTouchClick` 吞窗机制整段退役（`_swallowClicksUntil` + `opts` 参数 + 捕获期 click 监听还原为 `() => hidePopupMenu()`）——该机制唯一消费者就是长按腿 |
| `js/msgQuote.js` | `openQuoteMenuForRow` 撤 `opts` 形参；头注改写（菜单唯一入口 = 桌面 contextmenu）；兄弟媒体行合入注释去「长按/」字样 |
| `tests/mobile-msg-longpress.spec.mjs` | 断言翻面（见 §4） |
| `tests/type-baseline.json` | `--update` 重锚（删档净减 5 错，339 总错 < 原 350，只降不升） |

**保留不退役**（同名异批，边界裁定）：引用块附件缩略带（`.msg-quote-atts`，桌面右键同腿受益）；`contextMenu.js` 弹窗退场批的手机层全屏动作表样式（`.mfs-back`）；canvas 卡头长按引用钮（UI批⑨ 的 navController 腿，非本批对象）；`.duration-badge` footer 族全部。

## 2. 替代落点（②）——`js/mobile/mediaFooter.js`（新模块）

- **对象**：`.row.user-media`（用户文件/图片/视频附件卡行）——手机层激活期给行尾补 `.duration-badge.mh-media-footer` 徽标；**桌面零注册零监听**（bootstrap 激活谱系铁律），拆除 = 摘徽标 + 断守望 + 摘监听，桌面行形逐字节还原。
- **footer 内容**（照文本消息 footer 族，零新 CSS）：
  - **时间**：`createMsgFooterBadge(ts, '')` 单源——ts 取兄弟文本行（同 `data-msg-id`、`:not(.user-media)` 防自指）的 `.duration-badge-time[data-ts]`，与文本 footer 同一 `formatFooterTime`/`bindTimeToggle` 分层 label 链（12/24 时制切换就地重排自动覆盖）；兄弟行缺席 ⇒ 时间不渲染。
  - **下载**：`button.msg-copy.mh-media-download`（复用复制钮材质：常驻 0.35 / hover 0.6 / 成功绿勾 1.5s，`chat.download` i18n 新键 zh/en）——附件清单取行描述符 `__nfQuoteDesc.attachments`（attachQuoteMenu 单点载荷，与桌面右键同源）：内联 `preview`（data:）直用；有 `path` 走 `nfTicket.ticketUrl` 票腿（与媒体卡字节腿同路由，点击时惰性解析不预铸票）；**零可下载项 ⇒ 不出钮**（零死点击纪律）。多项 300ms 间隔顺序落盘。
- **长按退役配套压制**：Android/iOS 长按由浏览器**原生合成 contextmenu**，行上 `attachQuoteMenu` 的既有监听若不压制会照弹已退役菜单 ⇒ 激活期对 `#chat .row` 内 contextmenu 做捕获期 `preventDefault + stopPropagation`；桌面右键路径不经过本模块（逐字不动），手机无右键 ⇒ 不夺真实入口。
- **增量覆盖**：MutationObserver（childList+subtree）一次性吃三条腿——历史重放、live 发送、`attachmentsSaved` 原位重渲（replaceWith 的新行）；行级 `__nfMediaFooter` expando 幂等。

## 3. 边界与不做

- LLM Pop 产物行（`.row.pop-artifact` 无 `.user-media`）不加 footer——长按菜单从未触达它们，本批不扩面。
- footer 不放引用/复制钮——作者令整条退役长按菜单的引用/复制触达，footer 只补时间 + 下载（建议项中的身份省略：用户侧 footer 族（v1.2 裁定）本就不带身份标，右对齐气泡即身份）。
- 好友窗/群聊窗媒体消息不在本批（#chat 之外的另一族）。

## 4. spec 翻面（tests/mobile-msg-longpress.spec.mjs 重写）

| 测试 | 断言 |
|---|---|
| T1 长按退役 | 探针 `__nfMobileLongPress` 不在场 / `__nfMobileMediaFooter.active` 在场；文本行+媒体行 CDP 真触屏长按 650ms ⇒ `.fm-context-menu` 恒 0（抬手后再断言）；右键（=原生合成 contextmenu 同源路径）在手机层被压制；按住即滑滚动位移真实；**暗/亮「长按无弹」截图** |
| T2 媒体行 footer | 文件行/图片行各有 `.mh-media-footer`（时间 `\d{1,2}:\d{2}` + `aria-label=下载` 下载钮）；文件行下载点击 ⇒ **构建期零预铸票 → 点击后 nf-ticket 铸票** + Playwright download 事件 `suggestedFilename='报告.pdf'`；**暗/亮「文件 footer / 图片 footer」截图** |
| T3 手机层专属 | 桌面视口 ⇒ 探针不在场、媒体行零 `.duration-badge`（行形逐字节不变）、右键菜单照弹（复制+引用在列——桌面不动的边界断言） |
| T4 引用缩略带·历史腿 | （保留）落盘 quote 快照票腿水合 `.msg-quote-atts` |
| T5 桌面右键引用 | （保留）桌面右键 → 引用块缩略带同渲染腿 |

## 5. 验证

- `check-js-types`：**PASS**（339 错，baseline 同步重锚 350→339，mediaFooter.js 零 checkJs 错误）。
- `check-circular`：输出与 HEAD 逐字节一致（既有 SCC 原样，零新环）。
- 本 spec + 邻族回归：见 §5 交付报告（随批提交）。

## 6. 开放项

- 卡头长按引用钮（UI批⑨）与本批同名不同面——若作者「整条退役」意欲包含它，另行小批摘除（本次按「反悔 10-31 09:04 长按菜单批」的字面范围处理）。
