---
type: batch
status: published
date: 2026-11-02
scope: 手机端 canvas 面板顶部标签栏重做（横向圆环标签栏 + 底部小点指示器 + 堆叠卡翻页 + 两钮图标修复）；js/mobile/navController.js + css/mobileLayer.css + js/canvas.js（仅加一处导出）+ 六份 spec/脚本
---

# 手机端 canvas 标签栏重做批（作者令 2026-11-02 07:22）——横向圆环标签 / 底部小点 / 卡叠翻页 / 图标修复

关联：[[20261101_212200_mobile-dock-capsule__delegate-nebflow-7fa8a6bb]]（底部 dock 胶囊化，本批小点指示器的「不压 dock」对位基准）、[[20261031_160300_mobile-fab-unify-search]]（悬浮钮统一族 = 48px g2-fab，本批工具栏两颗钮的几何与材质单源）、[[20261101_151543_mobile-gesture-tribunal__delegate-nebflow-2a780c56]]（手势仲裁法庭，本批长按拖动/纵向翻页沿用同一套轴锁与内容优先纪律）、[[20261101_222500_composer-mobile-slim__delegate-nebflow-6e995fd9]]（同文件尾批次记录形制）。文档格式约定见 [[glossary#^vault]]。

## §1 令文与口径

作者令四件，逐条落成：

| # | 要求 | 落法 |
|---|---|---|
| 一 | 左右两颗圆形按钮**显示不出来**（空圆）——先定位再修 | 根因 = 图标替换作用域漏了 toolbar，补一次 `createIconsIn(toolbar)`（§2） |
| 二 | 标签栏改**常驻展开的横向圆环**：当前签居中、左右邻签按弧排列、放两钮之间、玻璃单源、双主题、长名省略+点击展开 | `layoutRing()` 写五个环变量（`--rdx/--rdy/--rsc/--rop/--rbl`），CSS 只消费（§3） |
| 三 | 底部小点指示器：内容区底部居中、1 签不显示/2 起显示/最多 3 点、胶囊底+三点、长按放大后可左右拖动换签、触感 + reduced-motion 降级 | `.mf-dots` 独立行 + `bindDots()`（§4） |
| 四 | 右上堆叠钮 → **卡片上下翻动**（卡标题/预览/关闭），退出回标签视图 | `.mf-stack-view` → `.mf-deck` 卡叠（§5） |

纪律：只动手机端 canvas 顶部与底部指示器；canvas 内容渲染零触碰；桌面逻辑零改（`canvas.js` 只新增一处导出，无既有行改动）。

## §2 图标 bug 根因与修法

`buildCanvasPage(body)` 把工具条挂在 **section 根**（`body.parentElement`），而图标替换只跑了 `createIconsIn(wrap)` ——wrap 子树里没有 toolbar ⇒ 两颗钮的 `<i data-lucide>` 元素永不替换成 `<svg>`，渲染成空圆。

探针实证（修前）：`{closeIcon:{svg:0,i:1}, stackIcon:{svg:0,i:1}}`；修后：`{close:1, stack:1, leftoverI:0}`（`evidence/.../a1-toolbar-icons-*.json` 的 `icons` 字段）。

修法：`navController.js` 内 toolbar 挂载后补 `createIconsIn(toolbar)`（与 wrap 同一次调用族，零新造工具）。回归断言写进 `tests/mobile-canvas-sheet.spec.mjs` T1：两钮 `svg===1 && i===0`，计数盒为 sr-only（`1×1`）。

## §3 横向圆环标签栏（电脑端竖向环的横向等价映射）

电脑端语言 = hover 后上下展开的竖向圆环（`canvas.js` 胶囊环：缘位逐级缩径 + 减淡 + 弧深，越出即读作绕到圈后）。手机端无 hover，故映射为**常驻展开**，轴向由「上下」转「左右」，缩径/减淡/模糊三档深度语言逐档沿用。

### 3.1 几何数值（375 基线，全部取值单点在 `navController.js` 常量块 651-700）

| 量 | 值 | 出处/推导 |
|---|---|---|
| 环带高（`.mf-strip`） | 72px | 签 32 + 弧深 12 + 上下气隙 |
| 签（`.mf-strip-chip`） | max-width 92px × 32px，padding 0 14px，圆角 999 | 玻璃单源：`--glass-bg` + `--glass-blur` saturate(1.15) + `--glass-border`（底缘透明化）+ `--sapphire-refraction` 顶折线 + `--cv-shadow` |
| 中签锚 | `top:40px; left:50%` + `translate(calc(-50% + var(--rdx)), calc(-50% + var(--rdy)))` | 环心 = 屏宽中线（实测 cx 187.5） |
| 步距 `RING_DX1` | **86px** | 中签半宽 46 + 邻签半宽 92×0.82/2 ≈ 38 + 2px 缝 |
| 环半径 `RING_RADIUS` | **340px** | 弧深 dy = R − √(R² − dx²) |
| 弧深上限 `RING_DY_MAX` | **12px** | \|k\|=1 → dy 11.1；\|k\|=2 理论 46.7 被上限压到 12 |
| 缩径 `RING_SCALE_STEP` | **0.18**/档 | 1 / 0.82 / 0.64 |
| 减淡 | **0.78 / 0.42** | \|k\|=1 / \|k\|=2 |
| 模糊 | **0.8px / 1.8px** | 与减淡同档 |
| 露出半径 `RING_VISIBLE` | **1** | \|k\|≥2 挂 `.is-far`：op 0 + `pointer-events:none` + tabIndex −1（**位姿仍算** ⇒ 换签是连续位移而非瞬切）；环带净宽 223px（375−2×76）只容中签+左右各一邻签 |
| 双侧渐隐（`.fade`） | `transparent 60px → #000 76px`（2 签起挂） | 与悬浮钮 16+48+4 栅格同源，签滑到钮前先淡出 |
| 长名 | 单行 ellipsis（92px）；点活签 `.is-expanded` 解限 `min(calc(100vw − 96px), 280px)`，2600ms 自动收 | 按既有省略规则 + 点击展开全名 |

实拍/探针读数（`evidence/20261102_mobile-canvas-ring-tabbar__delegate-nebflow-807819e2/b2-ring-3tabs-light.json`）：环带 `w375 h72`、中签 `x142 w92`（cx 188）、左邻 `x64 w75 rdx −86.0px rdy 11.1px rsc 0.820 op 0.78 blur 0.8px`、右邻镜像；5 签帧（`b3-ring-5tabs-dark.json`）环外签 `rdx ±172.0px rdy 12.0px rsc 0.640 op 0 far=true`。

### 3.2 计数与 sr-only

`3/3` 这类档位读数从视觉面退役为 **sr-only**（1×1 + clip-path），读数下沉到底部小点指示器；`aria-selected` / `tabIndex` 由 `layoutRing()` 逐签写。

## §4 底部小点指示器

位置：`.mf-canvas-page` 内、`host` 之后的**独立行**，`align-self:center` + `margin:6px 0 10px` —— 内容区底部居中，不压 dock（实测：小点盒 `y714 h26`，dockTop 750 ⇒ 净缝 10px）。

| 量 | 值 |
|---|---|
| 胶囊 | 高 26px、padding 0 12px、gap 6px、圆角 999、底 `rgba(128,128,128,.16)` + `--glass-border`（双主题两档：浅档点 `rgba(70,78,96,.42)`，深档点 `rgba(255,255,255,.55)`） |
| 点（`.mf-dot`） | 常态 **5.5px**，当前点 **8px** + 更亮 |
| 数量规则 | `n ≤ 1` → 整条 `hidden`（0 点）；`n = 2/3` → 2/3 点一一对应；`n > 3` → **恒 3 点** |
| 长按阈值 `DOT_LONGPRESS_MS` | **260ms** → `.is-grip`（整胶囊 scale **1.24** + 底加深 `.26`）+ 触感 |
| 拖动步距 `DOT_DRAG_STEP_PX` | **34px**/签（grip 态按 `round(dx/34)` 逐签切） |
| 点按容差 `DOT_TAP_SLOP_PX` | **8px**（未过阈 = 点按跳签，按落点 bucket 取签） |
| 松手 | 吸附到最近标签（已逐签切换，抬手即定格，无回弹动画） |
| 触感 | `navigator.vibrate` 存在则 8ms（`hapticTap`），缺失静默降级 |
| reduced-motion | 点尺寸/胶囊过渡压 0s 直切；**长按放大保留**（它是状态指示不是动效） |

### 4.1 省略策略申报（n > 3）

点数恒为 3，点位 = 标签序号**归一映射** `bucket = round(idx × 2 / (n−1)) ∈ {0,1,2}` —— 点本身不滑动（位置稳定、不随换签跳动），当前位置由「哪一点高亮放大」表达。n=5 时序号 0/1 → 点 0、2/3 → 点 1、4 → 点 2（即首/中/末三段各占一档）。此策略替代了「点随重签滑动」的方案，理由是 3 点下面滑动条的可读性差且与「胶囊内三点」参考样式冲突。

## §5 堆叠卡翻页（.mf-stack-view → .mf-deck）

右上堆叠钮 `toggleDeck()`：浮层宿主沿用既有 `.mf-stack-view` 类名（开合动画与工具条让位 `:has()` 选择器零改），内部 `.mf-deck` = 卡叠舞台，每张 `.mf-deck-card` = 卡头（`deckFaceOf` 类型图标 + 标题 + ✕44px 命中盒，卡头高 46px）+ 缩略预览（`canvas.js::deckFaceOf` 单源 mock：表格/图片/页面/流程图/浏览器五族，令牌 `--bar-1/2/3 --th-border` 单源）。

| 量 | 值 |
|---|---|
| 卡高 / 圆角 | `min(62vh, 460px)` / **18px** |
| 深度 | `depth = \|k\| + (k > 0 ? 0.5 : 0)`（环两侧同距时右侧让 0.5 档破平，否则两卡完全重叠） |
| 下沉 `DECK_PEEK_PX` | **16px**/档（`--dty = depth×16 + deckDragDy`） |
| 缩径 `DECK_SCALE_STEP` | **0.05**/档（`1 − min(depth,4)×0.05`） |
| 减淡 | \|k\|=0 → 1；=1 → 0.82；=2 → 0.5；>2 → 0（`.is-far`） |
| `transform-origin` | `center bottom`（后卡缩径时底边不动 ⇒ 只在前卡底缘下露出 16/32px 卡身，读作一叠卡） |
| 翻页阈 `DECK_SWITCH_PX` | **56px**（纵拖过阈松手翻一页） |
| 轴锁 `DECK_AXIS_PX` | **8px**（内 = 点按，外 = 拖动；**过锁后才 setPointerCapture**） |
| 开合 | 浮面 0.2s 淡入 + 卡叠逐张升起（stagger 30ms×6），收面 `.closing` 清零递延 |
| 交互 | 纵滑翻页（换活动签）；点后卡 = 换签**不退出**；点前景卡 = 收面；卡内 ✕ = 关卡；✕/Esc 退出回标签视图 |

## §6 改动账

单一 worktree 分支 `delegate/delegate-nebflow-807819e2`（起点 main @ `45f05b7f7`）。

| 文件 | 改动 |
|---|---|
| `src/main/resources/web/js/mobile/navController.js` | 2111 → 约 2300 行：常量块 651-700；`RING_TIERS`/`ringOffset`/`ringPose`/`layoutRing` 800-855；`syncCanvasPage` 862（签重建 + 活签展开全名）；`armLabelCollapse` 921；`dotBucket`/`dotIndex`/`syncDots`/`hapticTap`/`bindDots` 935-1045；卡叠族 `buildDeckView` 1081 / `bindDeckSwipe` 1112 / `buildDeckCard` 1154 / `layoutDeck` 1203 / `syncDeck` 1228 / `toggleDeck` 1250；`buildCanvasPage` 重写 1285+ |
| `src/main/resources/web/css/mobileLayer.css` | `.mf-strip` 1192 / `.fade` 1204 / `.mf-strip-chip` 1214 / `.is-far` 1252 / `.on` 1253 / `.mf-strip-label` 1266 / `.is-expanded` 1272 / `.mf-cv-count` 改 sr-only 1310 / `.mf-dots` 1330 / `.is-grip` 1352 / `.mf-dot` 1357 / `.on` 1371 / reduced-motion 1381 / `.mf-stack-view`+`.mf-deck`+`.mf-deck-card` 1408-1610 |
| `src/main/resources/web/js/canvas.js` | 1826-1834 新增 `export function deckFaceOf(entry)`（类型族/图标/缩略 mock 单源）；**桌面逻辑零改**，仅加导出 |
| `tests/mobile-canvas-sheet.spec.mjs` | T1 加图标/计数 sr-only 断言；T3b 改卡叠（标题/图标/缩略/✕/dty/dsc/圆角 18px，点后卡翻签不退出、点前景卡收面）；T7 改横向圆环几何；新增 T9（5 签环外）、T10（小点 1/2/3 + 归一映射 + 居中不压 dock）、T11（长按 grip/横拖换签/轻触跳签/reduced-motion）、T12（纵拖翻页/卡内 ✕/✕+Esc 退出） |
| `tests/mobile-dock.spec.mjs` | D4 加 `st.dots === {hidden:false, n:2, onIdx:1}` |
| `tests/canvas-card-window.spec.mjs` | CW6-2 改 `.mf-deck-card` + 缩略；CW6-3 改「点后卡翻签不退出」；新增 CW6-4「点前景卡收面」 |
| `tests/capture-topbar-batch.mjs` | 条探针改环（`stripRing`/`chipOnCenter`/`.fade` 门控）+ 卡叠 stagger 探针 |
| `tests/canvas-batch10-shots.mjs`、`tests/canvas-mobile-fix5-shots.mjs` | 类名/选择器跟随（`.mf-stack-card` → `.mf-deck-card`；环外签不可点 → 走 `setActiveTab`） |
| `tests/mobile-canvas-ring-tabbar-shots.mjs`（新） | 本批实拍脚本：11 帧 × 双主题 = 22 图 + 22 份 JSON 探针 |
| `evidence/20261102_mobile-canvas-ring-tabbar__delegate-nebflow-807819e2/` | 实拍证据（22 png + 22 json） |

## §7 验收读数

- **spec 全绿**：`mobile-canvas-sheet` + `mobile-dock` + `canvas-card-window` + `canvas-cardhead-capsule` + `mobile-fab-search` + `mobile-pop-canvas-open` 一次运行 **46 passed**（1.3m）；`mobile-gesture-tribunal` 独立脚本 **52 PASS**；`capture-topbar-batch` **24 PASS**；`canvas-batch10-shots` / `canvas-mobile-fix5-shots` 跑通。
- 已知 flaky：`mobile-dock` D14 在整包连跑时偶发失败，单跑/复跑稳定通过（与本批无关，D14 只读消息徽标）。
- **实拍**：22 帧（375 双主题），`console errors: 0`。帧目 = 图标一帧（a1）、环 2/3/5 签各一帧（b1/b2/b3）、小点 1/2/3（c1/c2/c3，1 签帧 `dots.hidden=true`）、长按拖动中帧（d1，`grip=true` 且点尺寸 6.8/9.9/6.8）、卡叠（e1，5 卡 dty 0/16/24/32/40 + dsc 1.000→0.875）、纵拖翻页后（e2，`{on:'rk-4', chipOn:'rk-4', stackOpen:true}`）、退出回标签视图（e3）。
- **伪影排查**：b3/a1 帧在预览缩放下有「中央幽灵胶囊」目视感，像素实测该带（y150-260）最大亮度 22 = 纯背景（`>25` 计数 0），且 `.mf-dots` 已独立成行不再覆盖签区 ⇒ 判定为预览缩放伪影，非真实缺陷。

## §8 未做 / 既有问题申报

1. **checkJs 门禁既有漂移（非本批引入）**：`node scripts/check-js-types.mjs` 在**主仓 main 与本支上报同样的 25 错**（`chat/msgTokenChip.js` 2、`messages.js` 7、`perception.js` 10、`popArtifacts.js` 2、`todoFace.js` 1、`todoStore.js` 3；`tests/type-baseline.json` total 为 0）。本批触碰的三文件（`navController.js` / `canvas.js` / CSS）在项目级 tsc 下**零新增错误**（未出现在失败清单）。门禁恢复需另批以 `--update` 重锚定基线。
2. 小点指示器的 3 点省略策略（§4.1）是本批自定，作者可改口径。
3. 桌面端标签胶囊环零改（本批只做手机端映射）。
4. 卡叠视图的缩略预览是 mock（`deckFaceOf` 单源生成），不是真实内容缩略图——与桌面 `#deck-layer` 卡面同策略，未做真实截图缩略。
