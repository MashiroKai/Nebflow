# 手机端长按退役批 · 批次报告

作者令 2026-10-31 22:55（第三派；前两派零产出）：

> ① 去掉手机端长按弹出引用/复制菜单——整条退役干净（处理器/菜单 DOM/样式/i18n 全清，桌面右键不动）；② 文件和图片消息补 footer（同文本消息底部信息条族：时间/身份+下载等常用操作），替代长按触达。双主题截图三样（文件 footer/图片 footer/长按无弹），旧长按 spec 翻断言，回归合入 main。

## 一、改动面

### ① 长按整条退役（桌面右键零变化）

| 件 | 处置 |
|---|---|
| `src/main/resources/web/js/mobile/msgLongPress.js` | **整文件删除**（480ms 长按计时/滑动取消/吞合成 click 的处理器本体） |
| `js/mobile/bootstrap.js` | 激活谱系摘除 import/激活/teardown；退役记入谱系注释 |
| `js/contextMenu.js` | `swallowTouchClick` 吞窗机制整段回退（唯一消费方 = 长按腿，退役即死代码）；`showPopupMenu` 恢复三参签名。壳与 `.mfs-back` 返回钮保留——联系人行/好友窗菜单仍用 |
| `js/msgQuote.js` | `openQuoteMenuForRow` 去 opts 长按腿注释；`attachQuoteMenu` 的 contextmenu 监听加**事件时手机层闸**（Android/iOS 长按原生补发 contextmenu 也会弹菜单——不闸则「长按无弹」验收不成立）；桌面分支逐字不动 |
| i18n / 菜单 DOM / 样式 | 零长按专属键（附件批未加过 locale 键，grep 实证）；菜单 DOM/壳为共享件（联系人行等仍在用），只清长按腿专属机制 |

### ② 文件/图片消息补 footer 信息条

- `js/popArtifacts.js` `buildUserMediaRow`：行尾追加 `createMsgFooterBadge` 族徽章（与文本消息底部信息条**同族** `.duration-badge`）＝时间档（`data-ts` 同消息时间戳）＋下载键（`.msg-copy.msg-dl`，零新 CSS——`.copied` 绿勾 / `.copy-failed` 红态全复用）；引用钮由既有 `mountQuoteFooterButton`（attachQuoteMenu rAF 收敛）挂进同一徽章 ⇒ **时间 / 下载 / 引用** 三件，触屏触达不再依赖已退役的长按。
- 下载键只挂**单件行**（叠卡/多文件行一枚键对哪件负责说不清，宁缺毋假）；字节腿：`path` 走 `nfViewerSource` 账本单点（零新增取字节路）、内联 `data:` 走直落盘；两腿皆缺不挂键（零假按钮，`attachmentsSaved` 补丁腿 path 到位即补挂）。
- ts 三腿贯通：live（`chat.js renderUserBubble`）、重放（`persistence.js` 两腿 `buildUserAttachmentRows(..., m.timestamp)`）、补丁（`registerUserMediaRow` 带 ts；`patchUserMediaRow` 重渲继承原行 `data-msg-id`，补齐附件批遗留的身份漂移）。
- 注：「身份」档＝发送侧对齐（`.row.user` 同族右对齐），与文本消息 footer 口径一致（文本 footer 亦无显式身份字段）；媒体行描述符的 senderRole/senderName/ts 照旧随引用 wire。

### 顺带面：手机聊天列被未开待办页占流挤压（一行 CSS 修复，截图阶段实证）

- 缺陷：`todo-page.css:257` `body.mobile-layer #todo-page { position: static; height: 100% }` 把未开（无 `.open`，`visibility:hidden`）的待办页留在 `#main` 流内占满一整档 ⇒ 聊天列被挤成贴底 142px 窄条（诊断链：探针快照 #chat sh=2429/ch=142、`#main` 子元素几何锁定 `#todo-page` 670px）。手机端 footer/长按替代触达在真机可视性取决于此面。
- 修复：`body.mobile-layer #todo-page:not(.open) { display: none }`（未开 = 页面体语义本就不渲染；pushPage('todo') 必经 `openTodoPage()` 加 `.open`，链路核对 navController.js:543-548 / 1081-1084）。桌面不受影响（挂 `body.mobile-layer`）。
- 回归钉：翻转 spec T1 断言手机聊天列高度 ≥ 60% 视口。

## 二、spec（旧长按 spec 全部翻断言）

`tests/mobile-msg-longpress.spec.mjs`（7 例全绿）：

- T1 长按退役：探针不在场；CDP 真长按 620ms 按住/抬手零菜单；行上派发 contextmenu 零菜单（手机层闸）；轻点/按下即滑零菜单；滚动面可滚可复位；**顺带面回归钉**。
- T2 媒体行 footer：单件图（内联 data: 腿）/单件文件（path 票腿）`data-ts` 同值 + 下载键（`title=下载`）+ 引用钮；两腿点击 = 真实 download 事件（`photo.jpg` / `报告.pdf` 文件名断言）；两件行有时间+引用无下载；文本行既有族（时间+复制+引用）不回归。
- T3 触达替代（原长按引用链改 footer 入口）：两件行引用钮 → 引用 chip → 发送 → `.msg-quote-atts` 缩略带（图缩略水合 + 文件 chip），两路点击 `workspace-open-item` 真开。
- T4 历史重放腿：quote 快照票腿水合不变；重放媒体行 footer `data-ts` 同值 + 引用钮在场。
- T5 桌面右键同腿（逐字保留）：右键菜单 items `['复制','引用']`、引用块缩略带照常；桌面媒体行同样带 footer；桌面无长按探针。
- T6 双主题存证：文件 footer / 图片 footer / 长按无弹 三样 × dark/light 六图。

## 三、证据

- `evidence/20261101_043359_mobile-longpress-retire/shots/`：theme-{dark,light}_file-footer / _image-footer / _longpress-no-menu.png（六图人工复核：全高聊天列、媒体行 footer 时间/下载/引用可见、长按中无任何弹层）+ t5-desktop-quote-block.png。

## 四、回归与门禁

- 翻转 spec `mobile-msg-longpress.spec.mjs`：**7 passed (17.7s)**，exit 0。
- Playwright 受影响面 7 套件一次串跑：quote-message / msgmenu / pop-artifacts / mobile-v3 / mobile-dock / mobile-popup-retire / queuebar-informative ⇒ **72 passed (49.2s)**，exit 0，零 fail 零 skip。
- `node --check` 全触及 JS 过；`check-circular`：SCC 两条（popArtifacts/reference/msgQuote、navController/taskPanel）在 main 上逐字节复现——本批零新增环（新增边 popArtifacts→chat/durationBadges 为叶向）。
- `check-js-types`：触及文件零新增错误；total 356 vs 基线 345（本批 −5：msgLongPress.js 删除入账；ws.js/modelPanel.js/main.js/scheduled-task.js 四项 FAIL 在 main 上对照实验逐字复现 = 既有欠账 361/350，非本批引入）。

## 五、未做 / 边界

- 手机端**画布卡头**长按引用钮（navController 腿）是另一条长按链，作者令未点名，本批不动。
- 好友窗/联系人行菜单（弹窗退场批全屏动作表族）保持现状——非「消息长按」面。
- 多件行（叠卡/图+文件）下载键的「逐件下载」交互未做（需要逐件选择 UI，超出 footer 信息条族口径）。
- todo-page 占流缺陷的根因修复口径（未开面为何以 static 入流）建议后续批独立审视；本批只落最小语义等价修复 + 回归钉。
