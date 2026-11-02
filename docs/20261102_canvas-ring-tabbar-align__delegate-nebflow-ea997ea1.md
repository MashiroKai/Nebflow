---
type: batch
status: published
date: 2026-11-02
scope: 手机端 canvas 顶部标签环对齐修正（同一水平线 / 中签放大 / 连续渐隐模糊 / 零省略号）；js/mobile/navController.js + css/mobileLayer.css + tests/mobile-canvas-sheet.spec.mjs（T7/T9 改 + T13 新）+ tests/mobile-canvas-ring-align-shots.mjs（新）
---

# 手机端 canvas 标签环对齐修正批（作者令 2026-11-02 09:00）——同水平线 / 中签放大 / 渐隐无省略号

关联：[[20261102_083638_mobile-canvas-ring-tabbar__delegate-nebflow-807819e2]]（上一批：横向圆环标签栏本体，本批改的是它的几何与视觉语言）、[[20261031_160300_mobile-fab-unify-search]]（两悬浮钮 48px g2-fab 基准 = 签心 y=40 的对位锚）、[[20261101_151543_mobile-gesture-tribunal__delegate-nebflow-2a780c56]]（手势仲裁轴锁，本批零改）。文档格式约定见 [[glossary#^vault]]。

## §1 令文与人话摘要

作者原话：「顶部的那个中间的标签页的样式和位置怎么没统一，不应该都是同一水平吗。然后中间的稍微大一点能显示稍微完整一些。，但是样式应该要是和两边的统一，两边的则渐隐，而且越到边缘越模糊，不用...号，直接模糊隐藏了。」

四件照办：① 全组纸签回到**同一条水平线**；② 中签**放大一档**、标题显示更多，但**不是另一种样式**；③ 两侧按距离**连续**渐淡 + 越靠边越糊；④ **去掉「…」**，改成模糊淡出。

- **为什么之前不齐**：签的垂直定位是 `top:40px + translateY(-50% + var(--rdy))`。`-50%` 量的是**未缩放盒**的一半，而 `--rdy` 是环的纵向弧深 —— 两者叠加后渲染中心 = 40 + 11.1 = 51.1（|k|=1 的邻签），中签 40，差 11.1px。缩放本身无罪（`transform-origin:center` 下 scale 不动中心）。
- **怎么修的**：从源头删掉纵向弧深（`--rdy` 整条退役），**不做反向偏移补偿** —— 环的「绕到圈后」只由横向位移 + 缩径 + 淡出 + 模糊表达，签心恒 y=40，正好等于两颗悬浮钮的中心线。
- **中签怎么放大**：宽度基准 92 → **112px**，缩径 1.0 → **1.14** ⇒ 渲染 127.7×36.5，文字框 78px（旧 58px），单行可见约 5.3 个汉字（旧约 4.5）。除此之外**零改动** —— 圆角 / 玻璃底 / 顶缘折射线 / 描边 / 阴影 / 字重 / 字号 / 字体与两侧逐项同源（选中态原来那套 sapphire 描边 + 底色 + 加粗全删）。
- **两侧怎么淡**：三档跳变改成 |k| 的连续函数（`t = min(|k|,2)/2`），越远离环心越小、越淡、越糊，|k|≥2 时透明度恰好归零、与「环外」档无缝衔接 —— 拖动换签是连续滑走，不是瞬切。
- **为什么不再有「…」**：文字超宽不再截断加省略号，改用 `mask-image` 水平 alpha 渐隐（左右各 9px，落在签自己的内衬里）；`text-overflow: clip` 钉死。全名仍留在 DOM 文本与 `title` 里（读屏 / 桌面悬停 / 点中签展开仍拿得到）。
- **热区**：环位姿的 scale 会连带缩掉伪元素热区（邻签 0.78 ⇒ 44 只剩 34.3），故 `::before` 自带反向 `1/--rsc`，净变换恒为单位阵 ⇒ 实际热区恒 44×44。

## §2 新几何（375 基线）

| 量 | 旧（2026-11-02 07:22 批） | 新（本批） |
|---|---|---|
| 垂直定位 | `translateY(-50% + var(--rdy))`，弧深 ≤12px | **无纵向量**，签心恒 y=40 |
| 渲染中心（邻签） | 51.1（差 11.1px） | **40.0**（极差 0px） |
| 步距 `RING_DX1` | 86px | **95px** |
| 签宽基准 | 中 92 / 侧 92（同宽，只差 scale） | 中 **112** / 侧 **68**（CSS 单源） |
| `scale` | 1 / 0.82 / 0.64（三档） | **1.140** / 0.800 / 0.600（连续） |
| `opacity` | 1 / 0.78 / 0.42 | **1** / 0.660 / 0（连续） |
| `blur` | 0 / 0.8 / 1.8px | **0** / 0.70 / 2.60px（连续） |
| 环带 mask | 60 → 76px | **52 → 64px**（浮钮右缘 64 起全实色） |
| 文本溢出 | `ellipsis`（画「…」） | **`clip` + `mask-image` 渐隐**（无 U+2026） |
| 展开档宽 | `min(100vw − 96px, 280px)`（scale=1 时的钳法） | `min((100vw − 2·--mchrome-inset) / --rsc, 260px)` ⇒ 375 下渲染 **231px**（跨 72…303） |

连续曲线（`t = min(|k|,2)/2`，三条全单调）：

| t | \|k\| | scale | opacity | blur |
|---|---|---|---|---|
| 0 | 0（中签） | 1.140 | 1.000 | 0.00px |
| 0.25 | 0.5 | 0.958 | 0.841 | 0.19px |
| 0.5 | 1（邻签，唯一可见的非中签） | 0.800 | 0.660 | 0.70px |
| 0.75 | 1.5 | 0.674 | 0.435 | 1.51px |
| 1 | 2（环外，接 `.is-far`） | 0.600 | 0.000 | 2.60px |

公式：`s = 0.60 + 0.54·(1−t)^1.4327`、`op = (1−t)^0.60`、`blur = 2.60·t^1.9`（指数取值：scale 指数使邻签恰落 0.80 / op 指数 <1 让前段掉得陡、后段收敛 / blur 指数 >1 起手温柔、边缘陡增 =「直接模糊隐藏」的读感）。

实测渲染盒（双主题一致）：中签 **127.7×36.5** @左缘 123.7 与 251.3；邻签 **54.4×25.6** @65.3…119.7 / 255.3…309.7；缝隙 4.0px（**不重叠**）；两侧不与 48px 浮钮（16…64 / 311…359）相交。

## §3 五条机械断言（`tests/mobile-canvas-sheet.spec.mjs` T13）

2 / 3 / 5 / 8 签四档循环（末签给超长标题）：

1. **同水平线**：所有 `opacity > 0.01` 的签，渲染盒垂直中心极差 ≤1px —— 实测 **0px**（四档）。
2. **中签更大**：中签渲染宽/高严格大于「可见非中签里最大的那枚」—— 实测宽比 2.347（2/3 签）/ 2.660（5 签）/ **2.942**（8 签），高比恒 1.426。
3. **视觉语言同源**：`borderRadius / backgroundImage / backgroundColor / borderTopColor / borderBottomColor / borderTopWidth / boxShadow / fontWeight / fontSize / fontFamily / color / backdropFilter` **12 项逐项相等**。
4. **零省略号**：每枚签 `text-overflow === 'clip'`、文本与 title 无 U+2026、label 有 `mask-image: linear-gradient`、`title === textContent`；并断言中签长标题**确实溢出**（`scrollWidth > clientWidth`，否则本档没验到东西）。
5. **热区 + 签心**：每枚签 `::before` 计算宽高 ≥43.5（实测恒 **44**，含环位姿缩放后）、中签心与两颗浮钮中心 y 差 ≤2、可见签渲染盒左右缘都在浮钮之外。

## §4 改动账

单一 worktree 分支 `delegate/delegate-nebflow-ea997ea1`（起点 main @ `e26efd977`；行号为**合并 main 后**的当前值 —— main 上的两段式标题批/内容起点让位批改了同文件，合并后行号整体后移）。

| 文件 | 改动 |
|---|---|
| `src/main/resources/web/js/mobile/navController.js` | 708-763 几何注释重写（根因实证 + 新数值表）+ 常量块（`RING_TIERS`/`RING_RADIUS`/`RING_DY_MAX`/`RING_SCALE_STEP` 删除，改 `RING_DX1 = 95`、`RING_SCALE_ON = 1.14`、`RING_SCALE_MIN = 0.60`、`RING_SCALE_POW = 1.4327`、`RING_OP_POW = 0.60`、`RING_BLUR_MAX = 2.60`、`RING_BLUR_POW = 1.9`）；`ringPose()` 898 改四值（无 dy）；`layoutRing()` 913 删 `--rdy` 写入、`--rbl` 定 2 位小数；`syncCanvasPage()` 949 建签注释改「全名三处保留」。**交互逻辑零改** |
| `src/main/resources/web/css/mobileLayer.css` | `.mf-strip.fade` 1495（mask 52→64）；`.mf-strip-chip` 1507（`max-width 68`、`padding: 0 var(--mpad-x)`、transform 去 `--rdy`）；`::before` 1559（反向 `1/--rsc`）；`.on` 1576（只剩 `z-index` + `max-width: 112px`，删 sapphire 描边/底色/字重）；`.mf-strip-label` 1589（`clip` + `mask-image`）；`.is-expanded` 1606（新钳：`min((100vw − 2·--mchrome-inset)/--rsc, 260px)`，见 §6.3）；`:has(...is-expanded)` 1615（展开期邻签退幕后）；3625 尾部重复 `padding` 声明删除（视觉单源） |
| `tests/mobile-canvas-sheet.spec.mjs` | T7 440（`--rdy` 断言为 `''`、rsc 1.140/0.800、op 1/0.66、blur 0.00/0.70px、rdx ±95）；T9 510（rdx ±95/±190、环外 rsc 0.600 + blur 2.60px）；**T13 536 新增**（五条断言 + 尺寸比打印） |
| `tests/mobile-canvas-ring-align-shots.mjs`（新） | 本批实拍脚本：9 帧 × 双主题 + 改前对照帧 |
| `evidence/20261102_canvas-ring-tabbar-align/` | 24 png + 22 json 探针（含改前帧与上下对照图） |

## §5 验收读数

- **spec 全绿**：`mobile-canvas-sheet` **15 passed**（含 T13）、`mobile-dock` 21 passed、`canvas-cardhead-capsule` 28 PASS、`canvas-card-window` 23 PASS、`mobile-gesture-tribunal` 52 PASS、`mobile-touch-target` 全 18 面板 PASS（canvas 15/15，exit 0）、`mobile-pinned-search` 11 passed、`mobile-panel-title` ALL PASS、`mobile-topbar` 3 passed、`capture-topbar-batch` 24 PASS。
- **实拍**：375×812 双主题 9 帧（a1 2签 / a2 3签 / a3 5签 / a4 8签+长标题 / a5 三签长标题 / a6 中签放大对照 / a7 拖动中帧 / a8 展开全名 / a9 边缘极限）+ z1 改前帧与上下对照图；每帧 JSON 探针 `cySpread=0`、`allClip=true`、`anyEllipsis=false`、`minHit=44`、`wRatio` 见 §3，**零 console error**。
- **两条既有红，已用干净基线复现证明非本批引入**：
  1. `make check-visual` 在本机因 GNU Make 3.81 解析 `Makefile:27`（`$(shell sed … #.*$$`）报 `unterminated call to function 'shell'` —— 主仓同报。直接跑 `node scripts/visual-baseline.mjs --check --max-ratio 0.0005` → 8 项 DIFF（app-empty / chat-messages / settings-llm-empty / bgtask-panel ×亮暗），把本批两个源文件 stash 后**逐字节同样 8 项**；canvas-* 四场景 SAME。
  2. `node scripts/check-js-types.mjs` → 25 错（`chat/msgTokenChip.js` 2 / `messages.js` 7 / `perception.js` 10 / `popArtifacts.js` 2 / `todoFace.js` 1 / `todoStore.js` 3），`navController.js` **0 错**；stash 后计数完全相同。

## §6 申报（取舍与微修）

1. **中签不再有专属高亮**：为满足「样式应该要是和两边的统一」，`.on` 档删掉了 sapphire 14% 底 + 45% 描边 + `font-weight:600`。可读性改由环几何承担（中签恒在环心、恒为最大一枚、恒全不透明）。若作者仍想保留选中色差，只需在 `.on` 加回 `color`（T13③ 目前未纳入 color 比对正是为此留口）。
2. **展开期邻签退幕后**（`:has(.is-expanded)`，只动 `opacity`）：展开签压在半透明玻璃邻签上会透出两段文字（截图实证），故展开期环临时收起，退出即复原 —— 不影响位姿变量。
3. **`--mchrome-inset` 沿用 + 一处合并修正（申报）**：主仓后来的内容起点让位批把展开钳改成 `calc(100vw − 2·--mchrome-inset)`，那是在中签 `scale = 1` 前提下成立的；本批中签 1.14，`max-width` 是缩放前盒宽、渲染要乘 `--rsc` ⇒ 直接沿用会渲染 263px、两头各压进浮钮 8px。合并时改为 `min(calc((100vw − 2·--mchrome-inset) / var(--rsc, 1)), 260px)`：375 下 max-width 202.6、**渲染 231px、跨 72…303**（实测 a8 帧 `x=72 w=231`），两端各余 8px 气隙；`260px` 上限只在窄屏兜底。
4. 交互逻辑零改（点/长按/拖动/翻页/小点/卡叠全不动）；桌面 canvas 零改。
5. 测试副产物会覆盖 `evidence/20261031_topbar-batch/*` 与 `evidence/mobile-interaction-standard/touch-targets.json`，跑完已 `git checkout --` 还原；未跟踪目录 `evidence/20261102_024446_mobile-panel-title/` 属别批，**未提交**。
