# 修正批 · canvas 胶囊 hover 环顶部居中（撤销沉降）+ 顶部越出克制（作者令 2026-10-23 11:55）

> 作者令逐字：「canvas的hover要保持在顶部居中，而不是整个canvas居中，顶部有些超出是正常且允许的。但是不要太多。」

## 两令落地

### ① hover 环锚常驻顶部居中（撤销沉降）
- 撤销对象：对称批（20261023_capsule-ring-symmetric）第三项「环开态胶囊整体沉降 canvas 垂直中点」——`.ring-open` + `--ring-dy` 双轴居中那套（split.css 沉降规则、canvas.js `syncRingDy()`、收环 `--ring-dy` 复位，三处全撤）。
- 修正后：环锚常驻顶带（标题头中心），横轴中线居中保持不变（v2①「居中标题头」原位）；环开态胶囊纵轴 = 常态顶带位，零位移、零过渡（spec CR2-8b 实测 dyOpen=dyRest=−408.0，逐位相等）。
- 撤销依据：作者令「保持在顶部居中，而不是整个canvas居中」——对称批为给竖环对称空间把胶囊沉到画布中部，作者裁定不要。

### ② 顶部越出克制（允许略微越出，绝不沉向 canvas 中部）
- 环缘位上下对称保持（前批 A 令）：上一签 −edgeY / 下一签 +edgeY，|y| 严格相等（实测 ±35.2px）；rotateX 反号等幅 ±34°。
- 上缘位允许**略微越出面板顶缘**：负向超出正常且允许，探出段被 `#canvas-panel` overflow:hidden 裁掉。
- 越出量定标（亮暗截图视觉证据定）：布局空间 `RING_TOP_OUT = 0.8 × 胶囊高`（clamp 8–24px）→ 视觉越出 **8.2px ≈ 胶囊高 1/3**（胶囊 24px，1440×900 实测）。为什么布局 0.8 视觉只剩 1/3：缘位胶囊带 rotateX(34°)+translateZ(−110)（perspective 640）投影把角点向环锚收回，投影半高 24→16.6px——布局越出 19.2px 投影后剩 ~8px。
- 对照定标过程（本批内两轮截图）：布局系数 0.4 时视觉越出仅 0.4px（= 完全不越出，且上缘位与顶带叠 5px，形态含糊）——弃；0.8 时视觉 8.2px、上缘位与顶带净隙 2.7px、下缘位净隙 4.7px——「顶部有些超出…不要太多」形态成立，取 0.8。
- 下缘位照常下垂（面板内完整），整环绝不下探 canvas 中部（spec CR2-6 实测下缘位底 84.2 vs 面板底 890.0）。

## 前批其余裁定全部保持（本批未触碰）
- 上下对称（|y| 相等 + rotateX 反号等幅，CR2-3 ✔）；胶囊尺寸恒定零缩放（CR2-7/CR2-7b：134.9×36.0 三态全等，scale=1.000 ✔）。
- hover 区联合盒防开→收振荡（环开点 56px ∪ 胶囊本体 ∪ 缘位胶囊，各外扩 14px）——对称批的「环开点→沉底胶囊顶走廊 ±90px」随沉降撤销一并退役（胶囊不再离位，走廊无事可桥）。
- 滚轮 document 捕获（面板矩形内消费为换选，CR3 族 ✔）；deck 态不开环（CR6-5/6-6 ✔）；tab-closed 延拍重建（保留未动）。

## spec 同步（tests/canvas-capsule-ring.spec.mjs）
- CR2-6 重写：不裁切 → **顶部越出克制**（0 < out ≤ 0.5×胶囊高 + 下缘位不越底缘）。
- CR2-8b 重写：环开双轴居中 → **环开顶部保持**（横轴中线 ±2px + 纵轴零沉降 = 常态顶带位 ±1.5px）。
- L1 亮色腿同步（对称 + 越出约束 + 环锚顶带）；CR6-7 victim 选择加「rect 主体在面板内」过滤（上缘位探出段被裁，点它会逃出面板）；证据截图 clip 改面板顶带（含越出段上下文）；头注增补修正批 D/E 两令。
- 邻族注释同步：canvas-cardhead-capsule.spec.mjs CC0 注、wiki-graph-ux.spec.mjs §G 注（「胶囊常驻 canvas 中心」表述随沉降退役修正——§G 断言本体未动，12/13 基点维持）。

## 结果
- canvas-capsule-ring 32 PASS / 1 FAIL（CR2-5 材质逐项相等 = 基点既有，HEAD stash 对照逐位同败，非本批面）· canvas-cardhead-capsule 28/28 · wiki-graph-ux 12/13（§F frosted 同败 main HEAD 既有判例）· canvas-card-window 22/22 · checkJs 257 = 基线 257 零新增。
- 证据：本目录 shots/ 1-ring-open-dark / 2-rest-dark / 3-ring-open-light / 4-rest-light（亮暗 × 环开/收起；环开面 clip 自面板顶上方 56px 起，顶缘裁切线与越出段同框可对照）。
