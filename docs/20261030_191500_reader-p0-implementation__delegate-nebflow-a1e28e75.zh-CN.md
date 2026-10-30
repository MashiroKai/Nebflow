---
type: design
status: implemented
title: 阅读器 P0 止血实施批——EPUB 图片根治/三主题/1-3 分区/查看面顶垫
date: 2026-10-30
batch: delegate-nebflow-a1e28e75
tags: [reader, epub, viewer, mobile-ux, implementation]
related:
  - "[[20261030_181655_reader-experience-design__delegate-nebflow-38afccc8.zh-CN]]"
---

# 阅读器 P0 止血实施批（2026-10-30）

> 实施锚：[[20261030_181655_reader-experience-design__delegate-nebflow-38afccc8.zh-CN]]（作者已全批设计稿）。
> 本件记录 P0 期落地实况；分期边界严格按设计稿 §7.1，P1/P2/P3 面一律未碰。

## 落地清单（P0 范围）

| 面 | 实现 | 位置 |
|---|---|---|
| EPUB 图片根治 | manifest 双索引（id 序 + 归一 href→entry）；章节 DOM 化渲染前改写（img/image/svg/source/audio/video/track 属性 + srcset + 内联 style 的 url()）；blob: object URL 同书缓存、切书 revoke（pane._epubBlobUrls）；缺失 → data-nf-res-miss + 摘属性（零 404 噪声）+ 一次性 toast（epub.resMissing）；spine 外裸路径回查 zip 全集 | `src/main/resources/web/js/viewers/epub.js`；`shared.js` 新增 `decompressZipEntryBytes`（与文本腿共用 ZIP_BUDGET 账本） |
| 单图 8MB 闸 | declared 预判 + 实际字节双检；超限 → data-nf-res-too-big 占位框，正文不阻塞 | epub.js `bookResourceUrl`；split.css 占位样式 |
| 章节样式 | `<head>`/body 的 `<style>` 与 manifest 内 stylesheet 提取 → url() 异步改写 → 只读清洗 → 选择器挂 `.epub-reader-content` scope（@media 递归、@keyframes 只清洗、@font-face 原样）→ 单点 `<style class="epub-book-style">` 注入，原 style 节点全摘除 | epub.js `extractChapterStyles`/`rewriteBookCss` |
| 书内硬编码色清洗 | color/background/background-color/-webkit-text-fill-color 四属性：非 inherit/none/currentColor 值删除；background 图片类（url()）保留；排版声明（字体/字号/行距/斜体/对齐）全保留 | epub.js `sanitizeDeclarationBlock`/`sanitizeInlineStyles` |
| 三主题 | 纸质 #F7F3EA/#3B342C（设计稿字面量）；明亮/暗夜 = base.css 面板 token 亮/暗档取值（#ffffff/#1b1e26、#1c1f27/#e8eaed，非新 token）；默认 auto 跟随面板明暗（data-theme 优先于系统色方案，双路监听）；暗夜任意面板态 ≥4.5:1 | epub.js `THEMES`；split.css `data-epub-theme` 段 |
| 字号/行距/段距/字族 | 字号 14-22px（默 16）/行距 1.5-2.0（默 1.8）/段距 紧凑·标准·宽松/衬线·无衬线；`nf.reader.prefs` localStorage 持久化，改档即重排 | epub.js 阅读菜单（中间 1/3 唤出） |
| 点按分区 | 1/3-1/3-1/3：左 prev / 右 next / 中唤菜单；菜单开时点正文仅收菜单不翻页；选区守卫维持既有口径；章节内锚点 preventDefault（防单页应用导航） | epub.js viewport click |
| 查看面顶垫 | 共享类 `.canvas-viewer-safe-top`（单源：calc(var(--capsule-h,36px)+16px)）；手机层常态垫；桌面环展开态由 canvas.js 给 #canvas-content 挂 `.capsule-ring-open`（240ms 与环过渡同长，收起即撤）；桌面常态不额外垫；md 面例外常驻垫（移位批 2026-10-22 真实遮挡，同表达式非第二把尺）；monaco 容器配套 border-box | split.css 尾块；monaco/markdown/image/epub 四渲染点挂类；canvas.js openRing/closeRing |

i18n key（验收表 §8 回填）：`epub.prev/next/menu.close/theme.{label,paper,light,dark}/aa.{fontSize,lineHeight,paraSpace,paraCompact,paraStandard,paraLoose,fontFamily,serif,sans}/resMissing/imgTooBig`（zh-CN + en 双份）。

## 验收（tests/reader-p0.spec.mjs，mock 档零替身，29/29 通过）

- A1 图片可见：%20 编码 + 原始空格双形态命中（解析期 blob 缓存 2）、零 4xx、样式表 url() 改写 blob、排版保真（italic 保留）。
- A2 缺失可见降级：data-nf-res-miss + 摘 src +「1 个资源缺失」toast。
- A3 暗面板：auto→暗夜；正文对比度 ≥4.5:1；inline color:#333 与书内 `<style>`/stylesheet 硬编码色全部清洗（computed = 主题墨）。
- A4 分区：左/右/中三分区行为 + 菜单开时点正文仅收菜单 + toggle。
- A9 手机层：md/monaco/image 三查看面 padding-top ≥ 52px。
- A11 持久化：主题 dark + 字号 20px 重载后保持。
- 8MB 闸：lying-header 9MB 声明单图 → data-nf-res-too-big 占位、正文先行。
- 截图存证：`.nebflow/evidence/20261030T11172_reader-p0/`（6 张）。

## 未做/开放项

- P1（底栏重排/进度条/TOC/翻页动效/统一内核 chrome）、P2（PDF 闸拆分/懒渲染——已由并行批 delegate-nebflow-f8da1d4a 落地，与本批无冲突）、P3（mobi/azw3/标注）一律未碰。
- 章节内锚点跳转、横滑翻页手势：P1 面，本批仅 preventDefault 防破页。
- blob URL 生命周期：切书/重开统一 revoke；页签直接关闭（不经过重渲染）的 revoke 挂账（pane 存活期内 Map 引用，量级同书图片数）。
- 触摸横滑翻页、目录抽屉、进度条：按分期归 P1。

## 关键假设

- 「明亮/暗夜」取面板 token 两档字面量（而非运行期 var 引用）：暗夜须在任何面板态下保证 ≥4.5:1 且语义恒定（auto 档才随面板）。
- md 面桌面常态顶垫保留（移位批作者报障在前），值与共享类同源同表达式。
- 阅读菜单承载主题/Aa（P0 必备）；目录/进度入口属 P1 未做。
