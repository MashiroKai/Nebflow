---
type: batch
status: published
date: 2026-11-02
scope: 手机端 canvas 顶部标签环「中签两端零虚化渐隐」（只撤中签 label mask，邻签渐隐/模糊原样保留；超宽文字改整字收束 + 既有展开腿承载）；css/mobileLayer.css + js/mobile/navController.js + tests/mobile-canvas-sheet.spec.mjs（T13 按新语义改写为六条）+ tests/_label-edge-pixels.mjs（新，像素判据）+ tests/mobile-canvas-ring-center-nofade-shots.mjs（新，实拍）
---

# 中签两端零虚化渐隐批（作者令 2026-11-02 11:23）

关联：[[20261102_canvas-ring-tabbar-align__delegate-nebflow-ea997ea1]]（上一批：同水平线 / 中签放大 / 连续渐隐模糊 / 零省略号 —— 本批只撤其中「中签也渐隐」这一段）、[[20261102_083638_mobile-canvas-ring-tabbar__delegate-nebflow-807819e2]]（横向圆环标签栏本体）。词表与文档约定见 [[glossary#^vault]]。

## §1 令文与人话摘要

作者原话：「前置显示的那个标签，两端不要有虚化渐隐。」

一句话：**环心那枚（中签）的文字左右两端必须是实色硬边，不许化开；两侧邻签的淡化与模糊一个都不许动。**

- **改了什么**：只加一条 CSS —— `body.mobile-layer .mf-strip-chip.on .mf-strip-label { mask-image: none }`。中签 label 不再有水平 alpha 渐隐，端点像素 alpha = 1。
- **没改什么**：邻签的 `mask-image`（左右各 9px 渐隐）、`opacity` 曲线（1 / 0.66 / 0）、`blur` 曲线（0 / 0.70 / 2.60px）、环带 `a9-edge-fade` 条带 mask、圆角 / 底色 / 玻璃 / 顶缘折射线 / 阴影 / 内距 / 字重 / 字号 —— 全部原样。**没有**「因为难写就把所有签的渐隐都撤掉」。
- **撤了 mask 之后超宽文字怎么办**：不溢出、不硬裁半个字、不出省略号。新增 `fitRingLabels()` 做**整字收束** —— 把中签 label 的 `max-width` 收到「能完整容纳的最大整字前缀」（按 grapheme 簇收在字界），实测常态可用宽 78 CSS px ⇒ 收在 5–6 个整字；剩下的照**既有展开腿**承载（点活签 → 展开档，2600ms 自动收）。全名三处保底不变：DOM 文本 / `title` / 展开腿 —— 收束只压**绘制宽**。
- **为什么必须收字而不是硬裁**：`overflow: hidden` 下右端会切掉半个字的笔画，比渐隐更难看；收在字界则一个完整字都不缺省、也绝不出现半字。
- **像素判据怎么来的**：本批新写 `tests/_label-edge-pixels.mjs`，量 label 裁切图两侧的「边缘斜坡带宽度」。有 mask 时端点被吃掉 ⇒ 斜坡 14–18 设备像素；无 mask 只剩字形抗锯齿 ⇒ 0–3 设备像素。两档不重叠，阈值取 5。这就是「左右端点像素 alpha = 1.0」的可判据代理（见 [[glossary]] 关于像素判据的说明）。

## §2 六条机械断言与实测读数（`tests/mobile-canvas-sheet.spec.mjs` T13）

2 / 3 / 5 / 8 签四档循环（末签给超长标题），每档实测：

| # | 断言 | 判据 | 实测 |
|---|---|---|---|
| ① | 中签两端零渐隐 · 计算样式腿 | `label.mask-image === 'none'` | **none**（四档全一致） |
| ① | 中签两端零渐隐 · 像素腿 | 边缘斜坡带 ≤ 5 设备像素；端点列 `firstRatio / lastRatio > 0.5` | 斜坡 **0 / 0**；比例 0.880 / 0.963 |
| ② | 邻签渐隐仍在 · 计算样式腿 | `label.mask-image` 含 `linear-gradient` | `linear-gradient(90deg, rgba(0,0,0,0) 0px, rgb(0,0,0) 9px, rgb(0,0,0) calc(100% - 9px), rgba(0,0,0,0) 100%)` |
| ② | 邻签渐隐仍在 · 像素腿 | 边缘斜坡带 ≥ 6 设备像素 | 实拍 **8–14**（左 8–10 / 右 2–14；右 2 是短标题邻签文字右缘未触渐隐带，见 §5.3） |
| ② | 越靠边越淡越糊 | 不同环次：`opacity` 严格更低、`blur` 严格更大；同环次（左右对称位）两者相等 | op 1 → 0.66 → 0；blur 0 → 0.70 → 2.60px；左右邻签逐位相等 |
| ③ | 同水平线 | 可见签渲染盒垂直中心极差 ≤ 1px | **0px**（四档） |
| ④ | 零省略号 | 每签 `text-overflow === 'clip'`；文本与 `title` 无 U+2026；`title === textContent` | 全 true / 全 false / 全等 |
| ⑤ | 超长名整字收束 | 不溢出签盒（`label.right ≤ chip.right − 1`）、不硬裁（`labelW == max-width × --rsc ±0.6`）、收在字界（下一字累积宽 > 收束宽）、无省略号 | 「命名规约汇编与落盘纪律」（11 汉字）：收束 **65px**、渲染 **74.1px**、收下 **5** 整字、下一字 78px > 65；`label.right` 在签盒内 |
| ⑤ | 超长名走既有展开腿 | 点活签 → `.is-expanded`、展开档收束值 ≥ 文字自然布局宽（或无需收束）、自动收 | 展开签宽 **187**、label 渲染 **163.0px**（`scrollWidth` 143 布局宽，全名 11 字放下）、2600ms 后 `.is-expanded` 归 false |
| ⑥ | 视觉语言同源 | 12 项逐项相等：`borderRadius / backgroundImage / backgroundColor / borderTopColor / borderBottomColor / borderTopWidth / boxShadow / fontWeight / fontSize / fontFamily / color / backdropFilter` | **12 项全等**；差异项清单 `[]`，唯一的视觉差异 = `mask-image`（本批作者令） |
| 沿用 | 中签更大 | 中签渲染宽/高 > 可见次签 | 117.9 / 43.4 = **2.717×**；高比 1.425 |
| 沿用 | 热区 + 签心 | `::before` ≥ 43.5；中签心与两颗浮钮中心差 ≤ 2；可见签不压浮钮 | 热区恒 **44**；签心差 0；不压钮 |

## §3 实拍读数（`evidence/20261102_center-tab-no-fade/`，375×812，dsf 2，双主题）

| 帧 | 场景 | 中签 label | 中签斜坡带 | 邻签斜坡带 | cySpread / clip / ellipsis / minHit |
|---|---|---|---|---|---|
| b1 | 2 签 | 「背景任务设计」宽 74.1 | 1 / 1 | 8 / 13–14 | 0 / true / false / 44 |
| b2 | 3 签（末签超长） | 宽 79.1、收束 69.4 | 0–1 / 0 | 8 / 13–14 | 0 / true / false / 44 |
| b3 | 5 签（末签超长） | 宽 79.1、收束 69.4 | 0–1 / 0 | 9 / 8–10 | 0 / true / false / 44 |
| b4 | 3 签、中签超长（≥10 汉字） | 宽 79.1、收束 69.4、`scrollWidth` 358 | 0–1 / 0 | 10 / 14 | 0 / true / false / 44 |
| b5 | 中签放大特写（短标题） | 宽 59.3、无收束 | 1 / 1 | 10 / 14 | 0 / true / false / 44 |
| b6 | 展开全名 | 宽 182.8、收束 160.4、签宽 221.6 | 0–1 / 0 | 邻签退幕后 | 0 / true / false / 44 |
| b7 | 邻签超长（渐隐仍在） | 宽 59.3 | 1 / 1 | 8 / 13–14 | 0 / true / false / 44 |
| d1 | 桌面 1440×900 | 手机层不在场（`.mf-strip` 0 节点） | — | — | — |

附帧：每帧另有 `*-labelcrop-*`（中签 label 裁切）、`*-neighborcrop-*`（邻签 label 裁切）、`*-chipcrop-*`（中签全签裁切）；`b5-center-zoom-x4-*` 为签身 4× 最近邻放大特写（两端是否残留 alpha 渐隐一眼可判）。改前同视口对照：`z0-before-b1/b2/b3/b5-{dark,light}.png`（取自 `evidence/20261102_canvas-ring-tabbar-align/` 同 seed 帧）+ `z1-compare-*` 上下并排图（上 = 改前，下 = 改后）。**零 console error**（双主题、含桌面腿）。

## §4 改动账

| 文件 | 改动 |
|---|---|
| `src/main/resources/web/css/mobileLayer.css` | 1655-1668：新增 `.mf-strip-chip.on .mf-strip-label { -webkit-mask-image: none; mask-image: none }` + 14 行注释（只撤中签 / 邻签保留 / 超宽走 JS 整字收束 + 展开腿 / 圆角底色玻璃内距零改动 / T13⑥ 钉死）。基础档 `.mf-strip-label`（1647，含 9px 渐隐与 `text-overflow: clip`）、`.mf-strip.fade` 条带 mask、`.on` 宽度档、`::before` 热区、`.is-expanded` 钳法**一字未动**（行号为合并 main 后的当前值） |
| `src/main/resources/web/js/mobile/navController.js` | 938-1046 新增「中签整字收束」族：`FIT_EPS_PX` 961 / `graphemes()` 964（`Intl.Segmenter` grapheme，回落 `Array.from`）/ `fitProbeEl()` 975（body 直属隐藏 span，字体从 label 抄）/ `chipInnerWidth()` 997（**临时掐 `transition`** 读目标 `max-width`，扣内衬与描边）/ `fitCenterLabel()` 1011（先回原始态 → 全文放得下就不收 → 逐 grapheme 量宽收在字界 → `Math.ceil` 写回）/ `fitRingLabels()` 1038（中签收束、非中签清内联值）；`layoutRing()` 末调用；展开点击与自动收后各重跑一次；1553 `resize` 与 1556 `document.fonts.ready` 各补一次 |
| `tests/mobile-canvas-sheet.spec.mjs` | T13（538）按新语义重写为六条（① 中签 mask=none + 像素斜坡；② 邻签 mask 仍在 + 单调性；③ cy 极差；④ 零省略号；⑤ 超长名整字收束 + 展开腿 + 极端长名三条底线；⑥ 12 项 + 唯一差异项 = mask），沿用「中签更大 / 热区 + 签心」两条并保留原编号；顶部新增 `labelEdgeRamp` import |
| `tests/_label-edge-pixels.mjs`（新） | 像素判据库：`columnContrast()` 逐列墨迹对比度 + `labelEdgeRamp()` 输出两端斜坡带宽度 / 端点比例；阈值 `ink=8`、`flat=0.85`。T13① 与实拍脚本共用 |
| `tests/mobile-canvas-ring-center-nofade-shots.mjs`（新） | 本批实拍：b1–b7 + d1 双主题，每帧 PNG + JSON 探针 + label/neighbor/chip 裁切 + b5 的 4× 特写；末段从上一批 evidence 拷改前帧并生成 `z1-compare-*` 并排图 |
| `evidence/20261102_center-tab-no-fade/` | 89 个文件（PNG + JSON 探针 + 裁切 + 对照图） |

## §5 验收读数

- **spec 全绿**：`mobile-canvas-sheet` **15 passed**（含改写后的 T13，打印三类读数：尺寸比 / 斜坡带 / 整字收束）；`mobile-dock` **21 passed**；`mobile-pinned-search` **11 passed**；`mobile-topbar` **3 passed**；`mobile-gesture-tribunal`（独立脚本）**52 PASS / 0 FAIL**（Chromium + WebKit 双腿）；`mobile-touch-target`（独立脚本）**18 面板全 PASS，exit 0**（canvas 15/15）；`canvas-cardhead-capsule` **28 PASS / 0 FAIL**；`canvas-card-window` **23 PASS / 0 FAIL**；`mobile-panel-title` **ALL PASS**。
- **`make check-visual` 不可用（申报）**：本机 GNU Make 3.81 解析 `Makefile:27`（`$(shell sed … #.*$$`）报 `unterminated call to function 'shell'`，主仓同报。改跑 `node scripts/visual-baseline.mjs --check --max-ratio 0.0005`。
- **合并 main 后复跑**（main 已含 canvas 控件 48 统一批 `73c305f81` 与会话身份头同行批 `00c9eb926`）：`mobile-canvas-sheet` **15 passed**（T13 三类读数与合并前逐项相同）、`mobile-dock` **21 passed**、`mobile-pinned-search` **11 passed**、`canvas-chrome-size-align`（同区域在飞批的验收脚本）**63 PASS / 0 FAIL**（含 ⑥「环带签 / 小点 / 左右浮钮 / ask 钮渲染盒零相交」—— 本批改的是 label 内部绘制宽，签盒与外框几何零变化）；实拍脚本按合并后当前树重抓一过，全部读数不变（PNG 仅文字抗锯齿亚像素差，b5 帧 0.5%）。
- **visual-baseline 8 项既有 DIFF，与本批无关（已用干净基线复现）**：`app-empty-{dark,light}` / `chat-messages-{dark,light}` / `settings-llm-empty-{dark,light}` / `bgtask-panel-{dark,light}`（尺寸不一致 base 378×146 vs cur 338×144）。把本批两个源文件 stash 后**逐项读数完全相同**（16963 / 17003 / 126043 / 126986 / 78803 / 79182 px、bgtask 同尺寸差）；`canvas-markdown-tab-*` 与 `canvas-code-tab-*` 四场景 **SAME**（本批动的正是 canvas 环带，这四项是它的视觉基线门 —— 说明环带在基线视口（桌面 552×874）零影响）。
- **vault 校验**：`python3 check_wiki_docs.py --root docs`（本 worktree 的 `docs/`）→ **通过（155 篇文档，零违规，exit 0）**；另按批约对已入库快照复验 `git archive HEAD docs` → `/tmp/vaultchk/docs` 同脚本 → **通过（155 篇，零违规，exit 0）**。口径说明见 §6.4（主仓工作目录里另有 2026-10-22 的**未入库**历史文章，直跑会被判 orphan，与本批无关）。

## §6 申报（取舍与已知边界）

1. **超宽文字的明确行为 = 整字收束 + 既有展开腿**（作者令「必须给出明确行为」的推荐路）。常态中签可用宽 78 CSS px（= 112 宽 − 2×16 内衬 − 2×1 描边），按 grapheme 收在字界；展开档可用宽 168.6 CSS px。**没有**上调中签宽度（112px 不动），也没有引入省略号或硬裁。
2. **展开腿的全名上限是展开档几何量级（约 11–12 个汉字）**：更长的页名在展开档也放不全 —— 那时全名仍留在 DOM 文本与 `title`（读屏 / 悬停 / 桌面可见），label 本身继续按字界收束、不溢出、不切半字、不加省略号。T13 用「11 汉字」（展开档恰好放全）作「展开腿能显示全名」的判据，另用 27 汉字档单独钉「不溢出 / 不硬裁 / 零省略号」三条底线。若作者要求任意长度都在展开档全显，需要动展开钳法（`--mchrome-inset` 让位）或允许展开档横向滚动 —— 属另批决策，本批不擅自改。
3. **相邻短标题的邻签「右斜坡带」可能只有 2 设备像素**：mask 的 9px 透明段要落在**文字实际溢出**才吃得到；邻签文字比签内衬窄时右端根本没进渐隐带（左端永远进，因首字紧贴内衬起点）。这不是渐隐被撤 —— 计算样式腿已确认 `mask-image` 仍在，且同一帧里长度溢出的邻签读数是 10–14。断言因此只钉「mask 仍在 + 斜坡不设下限」，单调性另用不透明度 / 模糊两条曲线钉。
4. **vault 校验口径**：`docs/` 在 `.gitignore` 内（仅白名单文件入库），故按批约先 `git archive HEAD docs` 到 `/tmp/vaultchk/docs` 再 `python3 check_wiki_docs.py --root /tmp/vaultchk/docs`，避免主仓工作目录里既有的未入库历史文章被判 orphan（那批与本批无关）。
5. **测试副产物已还原**：`mobile-touch-target` 会覆写 `evidence/mobile-interaction-standard/touch-targets.json`，跑完已 `git checkout --`；脚本生成的 `evidence/20261102_041124_mobile-panel-title/` 属别批，已删除未提交。
6. **同区域在飞批**：canvas 工具条宽度对齐批（`delegate-nebflow-f003855e`，面板头胶囊 48 + canvas 控件尺寸统一 48）已先于本批合入 main（`73c305f81`）；本批改动全部落在 `.mf-strip*` 选择器与 `navController.js` 的环位姿族，与该批文件区间不重叠，合并时按最新 main 就地融合，无需互相让步。
