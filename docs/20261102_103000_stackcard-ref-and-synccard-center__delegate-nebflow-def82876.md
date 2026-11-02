---
type: fix-report
status: published
date: 2026-11-02
scope: 叠卡单张引用（web/js/{msgQuote,popArtifacts,reference}.js + WebSocketRoutes.quoteFromRefs 白名单 + css/chat.css）+ 同步卡状态文字居中（css/cards.css）+ 两条验收钉（tests/{stackcard-quote,synccard-center}.spec.mjs）
---

# 叠卡单张可引用 + 同步卡状态居中（2026-11-02 09:19 作者令 两件）

> **定位先行（改前现场，报告开头口径）**
> ① **叠卡引用**：改前叠卡只有**一个行级**引用描述符——`popArtifacts.js`
> `buildUserMediaRow` / `buildPopArtifactRow` 交给 `attachQuoteMenu` 的是「整行」描述符，
> `attachments` 是**全量**、引用目标 = 行的 `messageId`。展开态每行没有自己的引用入口
> ⇒ 单张**完全不支持**（引用「第 2 张」得到的引用牌、注入正文、深链目标全等于「整组」）。
> ② **同步卡**：卡顶动态状态标题 `.nf-entry-title` **本来就居中**（实测文字盒中心
> 719.992 vs 卡中线 720，Δ0.008px）；真正左对齐的是标题**下面那四行**加载项清单
> （`.nf-entry-items`/`.nf-entry-item`，行基样式 `.nfc-steps`/`.nfc-step`，改前
> `text-align:left` + 行左对齐）——作者所指「下面的状态」即此，本批只动这一处。

## 摘要

- **件一选方案 A（扩展负载，最小侵入）**：引用目标仍是行 `messageId`；新增
  `content.itemIndex`（0 基）+ `content.itemTotal`（组张数）作位置标注，单张引用时
  `attachments` 只带**被引那张**。代价 = 后端 quote 白名单放行两个整数（缺席不落键 ⇒
  旧载荷字节不变）。未选方案 B（图片自身 id 当引用目标）：消息级语义一分为二（跳转/
  就绪判据/历史快照/降级矩阵全要按 id 反查 DOM），代价远大于一处分组 UI。
- **交互路径 = 展开态每行左上角单张引用钮**（`.pop-media-quote`）：收起态仍是「拖动
  翻页 + 整面打开 Canvas」，钮只活在展开层且 `stopPropagation` ⇒ 与手势仲裁零交集。
- **引用牌可区分**：`excerpt`/`fullText` 带「第 N/total 张」且**前置**于文件名（引用块
  两行 clamp，尾部会被截）；reference id = `ref:chatmsg:<mid>:i<n>` ⇒ 三块两两不同。
- **深链到第 N 张**：块钉 `data-quote-item-index`；`jumpToQuote` → `revealQuoteItem`
  收起态先 `pill.click()` 展开，再给第 N 行打 `.quote-item-flash`（1.8s），滚动优先该张；
  同一 msgId 的兄弟行一起 flash（引用对象是整条消息）。
- **单图退化**：无 `itemTotal` ⇒ 零张数 UI（摘要/正文/引用块/DOM 与改前逐字一致）。
- **件二**：`.nfc-steps` `text-align:center` + `.nfc-step` `justify-content:center` +
  `.nfc-step:has(> .nf-entry-dot)::after` 一枚与状态点等宽（14px）的右端留白 ⇒ 状态文字
  中心 = 卡内容中线（实测 Δ0px，三态各验）。垂直零改动（16/8px、行高 21px）；勾/叉是
  点内绝对定位伪元素，不参与行内排版。同类面点名：失败卡 `.nf-boot-card` 与接管弹窗
  `.nfc-body` 改前**已居中**，本批作为回归钉留存、未改动。

## 读数表（实测）

| 面 | 改前 | 改后 | 判据 |
|---|---|---|---|
| 同步卡行「样式表」(done) 文字盒中心 → 卡中线 @375 | 104.25 vs 187.5（Δ**83.25px**） | 187.5 vs 187.5（Δ**0.000px**） | ≤1px |
| 同步卡行「界面模块」(active) @375 | 111.25 vs 187.5（Δ76.25px） | 187.5 vs 187.5（Δ0.000px） | ≤1px |
| 同步卡行「连接主机」(pending) @375 | 111.25 vs 187.5（Δ76.25px） | 187.5 vs 187.5（Δ0.000px） | ≤1px |
| 同步卡行「同步数据」(fail) @375 | 111.25 vs 187.5（Δ76.25px） | 187.5 vs 187.5（Δ0.000px） | ≤1px |
| 同上四行 @1440 | Δ114 / 107 / 107 / 107 px | Δ0.000px ×4 | ≤1px |
| 卡顶状态标题 | 719.992 vs 720（Δ0.008px） | 719.992 vs 720（Δ0.008px，未动） | ≤1px |
| 行高 / 字号 / 行距 / 距标题 | 21px / 14px / 8px / 16px | 21px / 14px / 8px / 16px | 全等 |
| 三张图引用 payload id | 改前：三张同 id（无法区分） | `ref:chatmsg:<mid>:i0/i1/i2` 两两不同 | 两两不等 |
| 跳「第 2 张」 | 改前：只到整组、无展开 | 展开 + 第 2 行 `.quote-item-flash` + 在视口 | 断言 |

## 验收钉

- `tests/stackcard-quote.spec.mjs` — **5 passed**：① 三张各自 payload（id 两两不同 /
  itemIndex 对应 / 只带该张）② 三块引用牌两两不同 ③ 点第 2 张的牌 ⇒ 展开 + 第 2 行高亮
  + 入视口 ④ 单图退化（无「第 N/M 张」、平铺面无钮）⑤ 折叠面整面点击/拖动翻页/行级
  钮照旧 + 手机 375 触屏可点。
- `tests/synccard-center.spec.mjs` — **4 passed**（mobile/desktop × light/dark）：S1 三态
  文字中心 ↔ 卡中线 ≤1px；S2 点仍在文字左侧、点垂直居中、行高 21px 不被勾叉顶偏；
  S3 间距 16/8px；S4 同类面（失败卡标题/正文、接管弹窗标题/正文）≤1px；S5 双端；S6 零
  真异常 + 样张（[[glossary#^mockup-sample]]，实拍存于
  `evidence/20261102_stackcard-ref-and-synccard-center/`）。
- 回归腿（本批复跑全绿）：`stackcard-quote(5) + synccard-center(4) + pop-artifacts +
  pop-stack-fan + pop-footer-family` = **38 passed**；`mobile-dock` 21 passed；
  `mobile-gesture-tribunal` 全绿；`mobile-pinned-search / mobile-panel-title /
  mobile-topbar / ime-guard / console-takeover` 14 passed；独立脚本
  `node tests/entry-veil.spec.mjs` ALL PASS、`node tests/cards-family.spec.mjs` ALL PASS、
  `node tests/attcard-unify-shots.mjs` failures=0。

## 已知红灯（基线既有，非本批）

- `tests/quote-message.spec.mjs` B2-B4 / B5 / B11 三条红：`git stash` 掉本批改动后同样红
  （B5 报「被引用者名 span 期望 Nebula 实得主 Agent」等），非本批回归。
- `scripts/check-circular.mjs`：SCC `popArtifacts/reference/msgQuote` 与
  `mobile/navController/taskPanel` 两处静态环，stash 前后同红（`msgQuote → reference`
  与 `reference → msgQuote` 的环基线既有，本批未新增边）。
- `node scripts/visual-baseline.mjs --check`：8 项 DIFF（app-empty / chat-messages /
  settings-llm-empty / bgtask-panel 尺寸），stash 掉本批改动逐读数同值同 bbox ⇒
  宿主渲染基线漂移，与本批无关。

## 关联

- 卡片家族语言（同步卡与 `.nfc-*` 同源）：
  `[[20261101_162000_cards-family-design__delegate-nebflow-67f23d42.zh-CN]]`
- 同卡字标/状态点的上一批（本批改动面紧邻）：
  `[[20261102_084500_bootcard-brandicon-and-emoji-audit__delegate-nebflow-f555a161]]`
- 引用一期的规格来源：`src/main/resources/web/js/msgQuote.js` 文件头「设计规格
  2026-10-09 quote-message-design-spec §7.1」（该规格稿不在本仓 docs/ 内，故不作
  wikilink，避免断链）。
- 附件引用批（`content.attachments` 与白名单的先例）：
  `[[20261101_233954_composer-attach-icon__delegate-nebflow-4251fd3d]]`
