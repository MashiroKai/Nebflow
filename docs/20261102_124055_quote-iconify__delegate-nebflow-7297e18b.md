---
type: fix-report
status: published
date: 2026-11-02
scope: 叠卡引用入口移入 composer footer（web/index.html #quote-item-btn + web/js/msgQuote.js initQuoteItemButton + web/js/popArtifacts.js popQuoteState/nf-quote-front，退役 .pop-media-quote）+ 引用牌类型图标化（新 web/js/quoteLogo.js + web/js/fileIcon.js 叶子化 + css/chat.css .msg-quote-head/.nf-qicon + web/js/reference.js chip）+ 两条验收钉（tests/quote-iconify.spec.mjs、tests/quote-iconify-shots.mjs）
---

# 引用入口移入 footer + 引用牌图标化（2026-11-02 11:00 作者令 三件）

> **定位先行（改前现场）**：① 叠卡面的单张引用入口是**展开列每行左上角的悬浮图标**
> `.pop-media-quote`（`popArtifacts.js` 展开行创建块，`chat.css` 同族样式）——hover
> 才浮出；② 引用牌（气泡上方 `.msg-quote-block` 与 composer chip `.att-ref-icon`）
> 改前**无任何类型图标**，纯文字「发送方：摘要」；③ 两处引用牌分属 `msgQuote.js` 与
> `reference.js` 两个宿主，字形若各写一份即成第二套图标语言。

## 摘要

- **件一 入口退役**：`.pop-media-quote` 整条删除（CSS 块 + 创建块），叠卡面折叠/展开/
  hover 三态零命中；Canvas 角标 `.pop-media-canvas` 不动（另一族）。
- **件一 新入口**：`#input-bar` 动作行新增一枚 `.icon-btn#quote-item-btn`（附件键与发送键
  之间），动作对象 = **当前张**（`front`，即折叠面显示、拖动/横滑翻到的那张）。
  取数走 `area.popQuoteState() = {index,total,desc}`；翻页由冒泡事件 `nf-quote-front`
  同步文案，点击时再按当下 front 现算 ⇒ 文案不滞后、载荷不串张。
- **件二 图片** = 真缩略（`attachments[0].preview` 内联 `data:` 直进 `<img>`，16px 档，
  object-fit cover）；**历史腿无 preview**（后端 quote 快照白名单只有 type/name/path）
  ⇒ 退化为图片字形，**不伪造缩略**。
- **件二 文档** = **原图标**：复用既有 `fileIconStyle` + `.fc-icon.<cls>` 扩展名色族
  （单一来源，零第二张扩展名表）；本盒内压过 38px 文件卡档 / 28px 用户行档，落 16px。
- **件二 文字** = 新设计 logo（内联 SVG，viewBox 16、`currentColor`、零新色值）；
  三个候选见下，默认 A「双引号 + 双横线」。
- **零环新增**：`fileIconStyle/extOf` 从 `popArtifacts.js` 抽到叶子 `fileIcon.js`，
  `quoteLogo.js` 只依赖叶子 ⇒ `check-circular` 读数与改前逐条同值（两个 SCC 未增边）。
- **文本零污染**：`.msg-quote-head` 只装图标，`.msg-quote-text` 逐字不变 ⇒ 摘要断言、
  两行 clamp、跳转语义全保；图标走 grid 左列与摘要首行同高 ⇒ **净增高 0**。
- 托盘/文档类型由**文件名扩展名**在前端推导（无后端类型字段）；好友窗 `.fm-quote-block`
  与好友发送窗 chip 属不同产品面，本批**出界未动**，声明见「假设」。

## 读数表（①-⑦ 机械断言，实测）

| # | 断言 | 改前 | 改后 | 判据 |
|---|---|---|---|---|
| ① | 叠卡面 `.pop-media-quote` 命中数（折叠态） | 3 | **0** | =0 |
| ① | 展开列逐行命中数 | [1,1,1] | **[0,0,0]** | 全 0 |
| ① | 鼠标真悬停图片时命中数 | 1（hover 才浮出） | **0** | =0；可见按钮只余 `pop-media-canvas`×3 |
| ② | `#quote-item-btn` 在 `#input-bar` 内 / 可见 | 不存在（footer 动作行 0 枚） | 在场、`hidden=false`、几何落动作行盒内 | 真 |
| ② | footer 文案 = 当前张 | — | `引用 · 第 1/3 张`（手机 48px 触控档） | 含「第 1/3 张」 |
| ② | 拖动翻页后文案/载荷 | — | `front.index=1`、`desc=shot-1.png`、文案 `引用 · 第 2/3 张` | 三点同步 |
| ③ | 图片引用牌图标 | 无图标 | `kind=thumb`、`src` 前缀 `data:image/`、**16×16**、左列且与首行同高 | 全真 |
| ③ | 文档引用牌图标 | 无图标 | `kind=file`、`fcCls=pdf`、角标文字 `PDF` | 复用色族 |
| ③ | 文字引用牌图标 | 无图标 | `kind=glyph`、内联 SVG、`textContent=''`（不污染 chipText） | 真 |
| ③ | 历史图片（无 preview） | 无图标 | `kind=glyph`（图片字形），摘要仍 `[图片]` | 不伪造缩略 |
| ④ | 三张图三块引用牌缩略 src | 无法区分（无图标） | `new Set(src).size = 3`；同张重复引用 src 恒等 | 两两不等 |
| ⑤ | `.msg-quote-text` 内容 | `我：被引用的目标消息` | 逐字相同 | 全等 |
| ⑤ | 引用块高度相对无图标基线 | — | 增量 ≤ 22px（实测 0：左列与摘要首行同高） | ≤22 |
| ⑤ | 两行 clamp / 跳转 | ready | `textLines ≤ 2`、`data-quote-jump=ready`、`role=button`、跳转 flash 落目标行 | 全保 |
| ⑥ | composer 引用 chip 图标 | 纯文字 | `thumb` **18px** 档 / `file`(`pdf`) / `glyph` 三分支齐；chip 高 **28±2** 档不变 | 档位不变 |
| ⑦ | 其它 refType（file/document/task/html-element）chip | 走 `chipIcon` svg | `.nf-qicon` 命中 **0**、svg 仍 **1** | 逐字节不变 |

## 文字 logo 三候选（默认 A）

> **© 候选已废弃（2026-11-02 12:58 作者令「图标就用跟我们footer那个引用图标一样的吧」）**：
> 文字引用牌现用 **composer footer 引用入口键 `#quote-item-btn` 那颗图标**
> （`js/quoteLogo.js` 的 `QUOTE_ENTRY_ICON`，双引号双路径）。下表 A/B/C 三候选字形与
> `QUOTE_LOGO_CANDIDATES` 导出**已删除**；本节的对比帧保留作历史记录，不再由脚本生成。
> 详见 `[[20261102_133000_quote-icon-unify__delegate-nebflow-1818bb74]]`。

| 候选 | 构成 | 读作 | 16px 实尺判据 |
|---|---|---|---|
| **A（已废弃）** | 双引号（上）+ 双横线（下） | 「引用了一段文字」 | 主体引号最大，最清晰 |
| B（已废弃） | 票据卡外框 + 裁切线 + 左上引号 | 「引用卡」 | 外框占满盒体，与图片缩略的矩形轮廓撞形 |
| C（已废弃） | 三横线 + 右下火焰引号 | 「引文」，动势强 | 两件争主次，易糊 |

对比帧（历史）：`evidence/20261102_quote-iconify/after/f7-text-logo-candidates.png`
（48px / 16px 实尺 / 引用块内实样三档并排；A/B/C 三候选时代存档）。

## 验收钉与回归

- `tests/quote-iconify.spec.mjs` — **5 passed**（①-⑦ 全覆盖，含三态零钮、翻页同步、
  三分支图标、三 src 互异、chip 档位与其它 refType 零变化、语义零回归）。
- `tests/stackcard-quote.spec.mjs`（全文重写为 footer 入口驱动）— **5 passed**，
  合计 **10 passed**；手机 375×812 触屏无 hover 也能引用。
- 证据帧：`tests/quote-iconify-shots.mjs` — 改前树（`git archive` 纯净树）**8 PASS**、
  改后树 **8 PASS**（F1-F7 + console 零 error；改前帧断言写反，基线树若已是改后状态同样 FAIL）。
- 回归腿全绿：`quote-message` 13 passed（3 红为基线既有）、`mobile-msg-longpress` 7/7、
  `pop-footer-family` ALL PASS、`mobile-gesture-tribunal / mobile-dock / mobile-pinned-search /
  mobile-panel-title / mobile-topbar / ime-guard / attach-split / ask-input-composer` 43 passed。
- 静态门：`check-circular` 两个 SCC **逐条同值**（无新增环）；`check-js-types` 25→21 err，
  `popArtifacts.js` 归零（余下为基线既有文件）。

## 已知红灯（干净基线 `git stash -u` 复现，非本批）

- `tests/quote-message.spec.mjs` B2-B4 / B5（`sender='主 Agent' ≠ 'Nebula'`）/ B11 三条。
- `tests/attach-bubble-unify.spec.mjs` 5 条；`tests/composer-attach-icon-shots.mjs` 9 FAIL。
- `node scripts/visual-baseline.mjs --check` 8 项 DIFF（逐读数同值同 bbox，宿主渲染基线漂移；
  `Makefile:27` 的 `unterminated call to function 'shell'` 使 `make check-visual` 不可用）。

## 假设（显式声明）

1. 作者所说的 composer「footer」= `#input-bar` 动作行（本仓无独立 footer 元素）。
2. 扁平按钮组件库**未落地**，入口只用既有 `.icon-btn` 族（不新造按钮样式）。
3. 托盘/文档类型由文件名扩展名在前端推导（后端无类型字段）。
4. 好友窗 `.fm-quote-block` 与好友发送窗 chip 属不同产品面，本批出界未动。
5. 折叠态「当前张」= 拖动/横滑翻到的那张（`front`），与折叠面显示卡、整面点击开卡一致。

## 关联

- 叠卡单张引用一期（本批退役的 `.pop-media-quote` 入口即出自这批）：
  `[[20261102_103000_stackcard-ref-and-synccard-center__delegate-nebflow-def82876]]`
- 附件引用 chip 的先例：
  `[[20261101_233954_composer-attach-icon__delegate-nebflow-4251fd3d]]`
