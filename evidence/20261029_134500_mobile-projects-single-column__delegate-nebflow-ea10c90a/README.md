# 手机端「项目」页单列纵排 — 前后对比证据（2026-10-29）

作者令 2026-10-29 13:25（附手机截图 IMG_1619，逐字）：「手机端的项目界面 应该是一列往下翻的排布 而不是这样可以往右边拉的排布」。

## 缺陷机理（修复前，本仓现树即可复现）

P8 手机块只改 `flex-direction: column`，基座 FLOW_CSS `.team-scroll`（flowCss.js:20）的 `flex-wrap: wrap` 原样存活——wrap 语义随主轴转到纵轴：卡片总高超出滚动容器高（≥4 张卡，一屏即触）即**竖向换「列」**，第二列整列落在 x≥viewport 外。390×844 五项目实测：`scrollWidth 768 > clientWidth 390`、卡片 x ∈ {12, 390} 两列。作者截图 = 右拉后的中段态。既有 mobile-p8 spec 未拦截：夹具仅 2 张短卡（总高 < 容器高，永不换列）。

## 修法（单点，手机层作用域）

`src/main/resources/web/css/mobileLayer.css` P8 块（`body.mobile-layer #mobile-pages .team-scroll`）补两行：

- `flex-wrap: nowrap` —— 单列成立的必要条件（非 belt）；
- `overflow-x: hidden` —— 「零横向 overflow」spec 口径兜底。

桌面 FLOW_CSS 零触碰（桌面网格 row+wrap 原样，D1 断言钉回归）。

## 截图（390×844 @2x，自包含 mock harness，五项目夹具）

| 文件 | 树 | 读数 | 说明 |
|---|---|---|---|
| `before-390.png` | 修前（HEAD 5c2ad9b57 临时工作树） | scrollW 768 > clientW 390，xs=[12,390] 双列 | 静止首屏：第一列装 3 张即竖向换列，4/5 号卡在第二列 |
| `before-390-pan200.png` | 修前 | 同上，scrollLeft=200 | 作者「右拉」中段态复现（左列截左缘、右列截右缘，与 IMG_1619 同形） |
| `after-390.png` | 修后（本批） | scrollW 390 = clientW 390，xs=[12] 单列 | 一列往下翻，卡片全宽 366，零横向 overflow |

生成器：`node tests/mobile-projects-shots.mjs <outDir> [--web-root <dir>] [--pan <px>] [--tag before|after]`（「修前」= 在 HEAD 临时工作树以 `--web-root` 指向其 web 资源出图）。

## spec 读数

- 新钉 `tests/mobile-projects-single-column.spec.mjs`：**3/3 PASS**
  - M1 单列=列数 1（≥5 卡同 x、y 严格递增）；M2 零横向 overflow（scrollW≤clientW ∧ 文档不横滚 ∧ nowrap ∧ overflow-x hidden）；M3 卡全宽 366；
  - M4 字段行：超长 workspace ellipsis 收敛不撑卡；描述 = 样张 §6 口径单行省略（P8 既批设计，非新截断），行盒收敛视口内；
  - D1 桌面回归：1720 视口 row+wrap 双卡同排（网格原样）。
- 既有 `tests/mobile-p8-projects.spec.mjs`：**3/3 PASS**（回归绿）。
- checkJs：252 < 基线 260，触及件零新增。

## 归档对照（HEAD parity）

四件桌面 team-scroll 消费 spec（projcreate-fab / projcreate-redesign / ui-device-faces / content-i18n）：现树 **16 passed / 1 failed**；HEAD 临时工作树复跑**同读数**（1 failed = `projcreate-fab` T6：手机层视口档（≤768）桌面入口不可达 → `page.click('.proj-create-fab')` 超时）。T6 为 mobile-layer 落地后的存量红，非本批引入，另案清欠。

机制详版：`docs/20261029_140000_mobile-projects-single-column__delegate-nebflow-ea10c90a.md`
