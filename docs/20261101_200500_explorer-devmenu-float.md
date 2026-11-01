---
type: report
status: done
related: "[[index]]"
---

# 文件浏览器设备选择弹层三修批（弹层浮出）

> 批令（作者令 2026-11-01 实拍附图逐字三点）：① 设备名显示不全——头部 chip 与下拉行都截断，列表要**完整显示设备名**，弹层宽度自适应最长名（设合理上限，超宽才折行，不许默认省略号），头部 chip 放得下就全显、放不下才省略号 + title 悬浮全名；② 弹窗显示不完整——弹层被裁切、说明文案「设备来自 NebLink 网络；离线设备…」压到文件列表上，查清 z-index/overflow 链路让弹层完整浮在内容之上，说明文案给足两行或收敛一行；③ 弹层背景改毛玻璃——用既有 glass.css 单源勿再造，深浅双主题都验、文字对比度要够。
> 图谱：[[index]] · 词表 [[glossary]]。验收：双引擎 × 深/浅实拍（最长名全显帧 + 浮于文件列表之上帧）、零 console error、文件浏览其余功能零触碰。

## 0. 结论先行

| 诉求 | 根因 | 修法 | 验 |
|---|---|---|---|
| ① 设备名全显 | chip `max-width:110px` 硬截；菜单行 `.nm` nowrap+ellipsis；菜单宽锁 `min-width:220px` 不随名长 | chip 硬截改 `max-width:100%`（受挤才在 `.dev-nm` 上省略，title 常驻全名）；弹层 `width:max-content` + 上限 `min(320px, 100vw-16px)`；`.nm` 摘 nowrap（超上限折行、零省略号） | 双引擎×双主题最长名 `Mashiros-MacBook-Pro-iso-a659864a` 全显帧 ✅ |
| ② 弹层完整浮于内容上 | `#sidebar→#sidebar-panel→.panel→#explorer-section` **全链 `overflow:hidden`** 几何裁切弹层；且 `#sidebar` 自带 `backdrop-filter` 把弹层毛玻璃**采样困在自己子树**（`#explorer-tree` 是同 subtree 兄弟，但边界父一旦自带 filter 链，采样即退化——同款坑 `base.css` `.header-dropdown-menu` 注释在先「positioned relative to #main (not #header) so backdrop-filter can blur chat content」） | 菜单体**升出锚、改 `body` 级 `position:fixed`**（`placeExplorerDevMenu()` 按 chip 视口矩形锚定：下弹 6px 起步、右/下缘 8px 边距内回收、下不放翻上）；层带 = 共用右键菜单同款 `z-index:5000`（modal 族之下）；滚动/改窗判「chip 锚矩形是否真移位」再收/重锚（`#sidebar` 杂散 focus-scroll 实测 chip 位移为零，不误收） | 弹层完整在视口内 + 命中测试顶层 + 几何压树 + 说明文案整行收在弹层盒内 ✅ |
| ③ 毛玻璃单源 | 材质散落 projectPanel.css `.dev-menu` 私有阴影字面量（亮暗两套） | explorer 弹层入 **glass.css 面级浮件档**消费方名录（`--glass-bg/--glass-blur/--glass-border + --cv-shadow`，含 `@supports not` 与 `html.no-backdrop` 两降级组）；split.css 只管几何 | 双主题实拍文字对比度足、`backdrop-filter: blur()` computed 生效 ✅ |

一句话：**弹层从 overflow/filter 链里浮出来，宽度追名走，材质归 glass.css 单源。**

## 1. 改动面（4 文件 +1 harness）

- `js/explorer.js`：菜单体移 `document.body`（`.dev-menu.explorer-dev-menu`，创建一次开合复用）；`placeExplorerDevMenu()` 视口锚定；`explorerDevViewportChanged()` 锚移判定；chip 标签包 `.dev-nm`（三处 paint 点）。
- `css/split.css`：`.dev-menu.explorer-dev-menu` 几何（fixed/5000/max-content/上限 320px）+ `.nm` 折行档；chip `max-width:100%` 替代 110px 硬截 + `.dev-chip .dev-nm` 省略档。
- `css/glass.css`：面级浮件档 + 两降级组收入 `.dev-menu.explorer-dev-menu`（其余消费方零触碰）。
- `tests/picker-device-switch.spec.mjs`：R-E 选择器随菜单浮出换位（`.dev-menu.explorer-dev-menu .dev-item`），断言语义零改。
- `tests/explorer-devmenu-float-shots.mjs`（新增）：实拍 harness——静态面走本机真实 http.server（index.html boot 门脚本对 stylesheet 三态判定，满载机 Playwright route 截胡会触发门脚本整页 reload、打断 goto——实测），`/api`+`/agents` context 级截胡（webkit worker fetch 逃 page.route，实测），routeWebSocket 注答；chromium/webkit × light/dark 四组合、44 断言（含 `elementFromPoint` 顶层命中、`scrollWidth<=clientWidth` 不截断、blur computed 生效、零 console error）。

## 2. 实证

- 实拍：`.nebflow/evidence/20261101_explorer-devmenu-float/`（4 组合 × 4 帧 = 16 图 + readings.json；ALL PASS 44/44，console=0 page=0）。
- 回归：`picker-device-switch` + `ui-device-faces` + `projcreate-device-flat` 三套件 10/10 passed（30.1s）；`checkJs` 门 0 errors。
- 零触碰面：projectPanel/workspacePicker/projectTab 三处既有 `.dev-menu`（创建弹层设备行）材质与几何未动；explorer 其余功能（列目录/取件/只读闸/换根）由 R-E 套件全绿背书。

## 3. 遗产与边界

- 「弹层在 backdrop-filter 祖先内采样被困」与本文「全链 overflow:hidden 裁切」两坑，`base.css:335` 与本批 split.css 注释各留一案，后续新弹层一律 body 级浮出。
- 满载机（load avg ~9）跑 mock harness 的两条实证纪律入 §1 harness 注：静态真 server、worker 面 context 级截胡。
