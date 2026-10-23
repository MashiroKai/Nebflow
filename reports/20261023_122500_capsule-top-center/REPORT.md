# 修正批 · canvas 胶囊 hover 环顶部居中（撤销沉降）+ 顶部越出克制 · 批报告

- 批次：修正批（作者令 2026-10-23 11:55 逐字「canvas的hover要保持在顶部居中，而不是整个canvas居中，顶部有些超出是正常且允许的。但是不要太多。」）
- 基线：main 最新（2868fa547 对称批已在 main，本批直接其上修正）
- 分支：`delegate/delegate-nebflow-c11e048e`
- 前批证据对照：`evidence/20261023_capsule-ring-symmetric/`（未触碰）；本批证据：`evidence/20261023_capsule-top-center/`

## 一、改了什么

### 1. 撤销环开态「沉降 canvas 垂直中点」（--ring-dy 沉降那套，三处全撤）
| 位置 | 撤销内容 |
|---|---|
| `split.css` | `.canvas-header.ring-open:not(.is-dragging)` 沉降规则整条删（transform 带全局 `--ring-dy` + 0.36s 过渡覆盖）；§⑨ 居中批注释与 §ring 对称批注释同步改写为「顶部修正批」口径 |
| `canvas.js` | `syncRingDy()` 函数整段删；`openRing()` 的 `syncRingDy()` 调用删；`closeRing()` 的 `--ring-dy` 复位删 |
| 行为 | 环锚常驻顶带（标题头中心），横轴中线居中不变；环开态纵轴 = 常态顶带位，零位移（spec CR2-8b 实测 dyOpen=dyRest=−408.0 逐位相等）；胶囊不再落 canvas 中部 |

### 2. 环开态顶部越出克制（新几何）
- `layout()` edgeY 从「`clamp(h*3, 56, 120)`（供 canvas 中点对称）」改为**面板顶几何反解**：`edgeY = max(h+2, hdr.offsetTop + root.offsetTop − h/2 + out)`，`out = clamp(RING_TOP_OUT×h, 8, 24)`，`RING_TOP_OUT = 0.8`（布局空间）。
- 语义：上缘位顶越出面板顶缘 `out`（布局），探出段被 `#canvas-panel` overflow:hidden 裁掉；下缘位 |y| 对称下垂。**视觉越出 8.2px ≈ 胶囊高 1/3**（缘位胶囊 rotateX(34°)+translateZ(−110)（perspective 640）投影把角点向环锚收回：布局 19.2px → 视觉 ~8px）。1440×900 实测：|y| = ±35.2px 对称、上缘位与顶带净隙 2.7px、下缘位净隙 4.7px、下缘位底 84.2 vs 面板底 890.0（绝不沉向 canvas 中部）。
- 定标过程（两轮亮暗截图）：布局系数 0.4 → 视觉越出仅 0.4px 且上缘位与顶带叠 5px（形态含糊）——弃；0.8 → 视觉 8.2px 形态成立——取。证据 README 有全程记录。
- 常量 clamp（8–24px）+ `h+2` 下限兜底：防极端几何退化（越出消失/缘位与中心叠置）。

### 3. hover 区联合盒（防开→收振荡）——保持，走廊腿退役
- 联合盒保持：环开点 56px ∪ 胶囊本体 ∪ 缘位胶囊（各外扩 14px）+ document pointermove 跟踪 + 140ms 宽限——「中心→缘位 ~edgeY 间隙」的误收根源仍在，防振荡机制不撤。
- 对称批的「环开点→沉底胶囊顶走廊 ±90px」腿删除：它专为「胶囊沉离指针原位」的几何而设，沉降撤销后胶囊不再离位（`hr0.top > openPt.y` 恒假 = 死代码），无事可桥。

### 4. 前批其余裁定全部保持（零触碰）
上下对称（|y| 相等 + rotateX 反号等幅 ±34°）· 胶囊尺寸恒定零缩放（134.9×36.0 常态/展开/滚动中三态全等）· 滚轮 document 捕获（面板矩形内消费）· deck 态不开环 · tab-closed 延拍重建 · frosted 材质单源 · 0.36s 环族缓动。

## 二、spec 同步

| 文件 | 改动 |
|---|---|
| `tests/canvas-capsule-ring.spec.mjs` | CR2-6「不裁切」→「顶部越出克制」（0 < out ≤ 0.5×胶囊高 + 下缘位不越底缘）；CR2-8b「环开双轴居中」→「环开顶部保持」（横轴 ±2px + 纵轴零沉降 ±1.5px）；L1 亮色腿同步；CR6-7 victim 加「rect 主体在面板内」过滤（上缘位探出段被裁，直点会逃面板）；环开证据截图 clip 改面板顶带（含越出段）；头注增补修正批 D/E 两令 |
| `tests/canvas-cardhead-capsule.spec.mjs` | CC0 注释：沉降表述改「环锚常驻顶带不沉降」（断言零改动） |
| `tests/wiki-graph-ux.spec.mjs` | §G 注释：「胶囊常驻 canvas 中心」改顶带口径（§G 断言本体零改动，12/13 基点维持） |

## 三、验证（全绿，基点既有失败逐位同败）

| 套件 | 结果 | 说明 |
|---|---|---|
| canvas-capsule-ring | **32 PASS / 1 FAIL** | 唯一 FAIL = CR2-5 材质逐项相等，HEAD stash 对照逐位同败（chat header shadow 漂移，前批 README 已记录的基点既有）——非本批面 |
| canvas-cardhead-capsule | **28 / 28** | 胶囊形态/手势/引用/手机腿全过 |
| wiki-graph-ux | **12 / 13** | 唯一 FAIL = §F frosted 视觉档，main HEAD 既有判例（graph-button-403 批 git archive 实锤）；§G 环×画布分区（本批几何敏感面）PASS |
| canvas-card-window | **22 / 22** | 卡窗族全过 |
| checkJs | **257 = 基线 257，零新增** | scripts/check-js-types.mjs |

## 四、证据（evidence/20261023_capsule-top-center/）
- shots/1-ring-open-dark.png / 3-ring-open-light.png：环开态顶带特写（clip 自面板顶上方 56px 起）——环锚顶带居中、上缘位越出段被顶缘裁掉（越出量与裁切线同框）、下缘位模糊下垂；亮暗双面。
- shots/2-rest-dark.png / 4-rest-light.png：平时态单胶囊顶带横轴居中、零环。
- 对照：evidence/20261023_capsule-ring-symmetric/（前批「沉降 canvas 中点」形态，已撤销）。

## 五、红线自检
- 最小改动：src 仅 split.css（-10 行规则 + 注释改写）与 canvas.js（-13 行沉降代码 + edgeY 反解 4 行）；断言语义变化仅 CR2-6/CR2-8b/L1（均为作者令直接要求）。
- 跟随仓库风格：spec 钉 + 证据目录 + REPORT.md + 作者令逐字引注，与前批同构。
- 未重启引擎；未触碰 main；工作全部在本分支。
