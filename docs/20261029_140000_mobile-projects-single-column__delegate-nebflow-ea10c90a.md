---
type: fix-report
status: published
date: 2026-10-29
scope: 手机端「项目」页布局（mobileLayer.css P8 块）/ Flow Map 无涉
---

# 手机端「项目」页单列纵排修复批（作者令 2026-10-29 13:25）

作者令（附手机截图 IMG_1619，逐字）：「手机端的项目界面 应该是一列往下翻的排布 而不是这样可以往右边拉的排布」。

## 结论（三行）

1. **缺陷机理**：P8 手机块（2026-10-20）只改了 `flex-direction: column`，基座 FLOW_CSS `.team-scroll`（`src/main/resources/web/js/flowCss.js:20`）的 `flex-wrap: wrap` 原样存活。wrap 语义随主轴转到纵轴——卡片总高超出滚动容器高（≥4 张卡，手机一屏即触）就**竖向换「列」**，第二列整列落在 x≥viewport 外：390×844 五项目实测 `scrollWidth 768 > clientWidth 390`、卡片 x ∈ {12, 390} 两列，页面可整体右拉、右列截断。作者截图 = 右拉后的中段态。
2. **修法（单点、手机层作用域）**：`src/main/resources/web/css/mobileLayer.css` P8 块（`body.mobile-layer #mobile-pages .team-scroll`）补 `flex-wrap: nowrap`（单列成立的**必要条件**，非 belt）+ `overflow-x: hidden`（零横向 overflow 兜底钉）。桌面 FLOW_CSS 零触碰。
3. **验证**：新钉 spec 3/3 绿（单列/零横溢/全宽/字段行/桌面回归），既有 mobile-p8 spec 3/3 回归绿，checkJs 252<260 基线零新增，前后对比截图三张落 `evidence/20261029_134500_mobile-projects-single-column__delegate-nebflow-ea10c90a/`。

## 为什么既有 spec 没拦住

`tests/mobile-p8-projects.spec.mjs` T2（2026-10-20 P8 批）已钉「全宽单列堆叠」，但其夹具只有 **2 张短卡**（总高 ~358px < 容器高 729px）——永不触发竖向换列，断言恒真。作者真机 5-6 个项目（长描述 → 卡高 ~173-220px）才越出一屏。本批新钉 spec 的夹具按「修复前必换列」的量取 ≥5 卡，把缺陷面补进断言面。

## 断言钉（tests/mobile-projects-single-column.spec.mjs，3/3）

| 钉 | 口径 |
|---|---|
| M1 单列 | ≥5 卡同 x、y 严格递增（列数=1） |
| M2 零横向 overflow | `scroll.scrollWidth ≤ clientWidth` ∧ 文档不横滚 ∧ 计算值 `nowrap` ∧ `overflow-x: hidden` |
| M3 卡片全宽 | 每卡宽 = 滚动容器内容宽（390−2×12=366） |
| M4 字段行 | 超长 workspace ellipsis 收敛不撑卡；描述 = 样张（设计演示样例，见 [[glossary]]）§6 口径单行省略（P8 既批设计非新截断），行盒收敛视口内 |
| D1 桌面回归 | 1720 视口 `.team-scroll` 保持 `row`+`wrap`、双卡同排（桌面网格原样，本批零触碰） |

## 证据与回归读数

- 前后截图（生成器 `tests/mobile-projects-shots.mjs`，HEAD 临时工作树出「修前」）：`before-390.png`（双列 768>390）、`before-390-pan200.png`（右拉中段态，与 IMG_1619 同形）、`after-390.png`（单列 390=390）。
- 回归：`mobile-p8-projects` 3/3；`projcreate-fab`/`projcreate-redesign`/`ui-device-faces`/`content-i18n` 四件现树 **16 passed / 1 failed**，HEAD 临时工作树对照**同读数**（1 failed = projcreate-fab T6，手机层视口档桌面入口不可达 → click 超时，mobile-layer 落地后存量漂移，非本批引入，另案清欠）。
- checkJs：252 < 基线 260，触及件零新增。

## 关联

背景知识见 [[glossary]]（手机层 = 独立交互层而非响应式补丁，`js/mobile/bootstrap.js` 激活判据 `max-width: 768px`）；P8 既有口径见 [[index]] 挂链的 mobile-secondary-screens 系列。入库证据目录与本批 commit 一一对应。
